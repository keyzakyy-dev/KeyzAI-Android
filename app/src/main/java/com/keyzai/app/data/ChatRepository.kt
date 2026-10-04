package com.keyzai.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SendMode {
    data object New : SendMode
    data class Edit(val targetId: String) : SendMode
    data class Regenerate(val assistantMsgId: String) : SendMode
}

sealed interface SendResult {
    data object Done : SendResult
    data object Empty : SendResult // model selesai tanpa output
    data object Aborted : SendResult // user menekan stop
}

/**
 * Orkestrasi chat: mirror dari useChatStream.js + useChatStore.js (web).
 *
 * - Pohon pesan dipertahankan (edit/regenerate = cabang baru).
 * - Streaming SSE dengan update inkremental.
 * - Judul otomatis untuk percakapan baru (seperti web).
 * - Sinkron ke worker via PUT setelah turn selesai / pin / rename.
 */
class ChatRepository(
    private val api: KeyzApi,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** ID pesan AI yang sedang streaming (untuk indikator UI). */
    private val _streamingMsgId = MutableStateFlow<String?>(null)
    val streamingMsgId: StateFlow<String?> = _streamingMsgId.asStateFlow()

    private val cancelFlag = AtomicBoolean(false)
    private var streamSource: EventSource? = null

    // ------------------------------------------------------------ dasar

    fun get(id: String): Conversation? = _conversations.value.find { it.id == id }

    private fun update(conv: Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
    }

    suspend fun loadConversations() {
        val list = withContext(Dispatchers.IO) { api.getConversations() }
        _conversations.value = list
    }

    /** Ambil detail penuh bila daftar ringan belum punya messages. */
    suspend fun ensureFull(id: String): Conversation? {
        val c = get(id) ?: return null
        if (c.messages.isNotEmpty()) return c
        return try {
            val full = withContext(Dispatchers.IO) { api.getConversation(id) }
            update(full)
            full
        } catch (e: Exception) {
            c // jaringan gagal — pakai yang ada
        }
    }

    /** Buat percakapan lokal baru (belum disimpan sampai pesan pertama terkirim). */
    fun createLocal(): Conversation {
        val now = System.currentTimeMillis()
        val conv = newConversation(newConvId(), createdAt = now)
        _conversations.value = listOf(conv) + _conversations.value
        return conv
    }

    /** Buang percakapan lokal yang tidak jadi dipakai (tanpa pesan). */
    fun dropIfEmpty(id: String) {
        val c = get(id) ?: return
        if (c.messages.isEmpty()) {
            _conversations.value = _conversations.value.filter { it.id != id }
        }
    }

    fun clearLocal() {
        _conversations.value = emptyList()
        _streamingMsgId.value = null
    }

    // ------------------------------------------------------------ kirim

    /**
     * Kirim pesan. [onDelta] dipanggil di thread IO setiap ada chunk —
     * teruskan ke UI via handler yang aman-thread.
     */
    suspend fun sendMessage(
        convId: String,
        content: String,
        model: String,
        mode: SendMode = SendMode.New,
        onDelta: (msgId: String, partial: String) -> Unit = { _, _ -> },
    ): SendResult {
        val trimmed = content.trim()
        if (mode is SendMode.New || mode is SendMode.Edit) {
            require(trimmed.isNotEmpty()) { "Pesan tidak boleh kosong" }
            require(trimmed.length <= 2000) { "Pesan terlalu panjang (maks 2000 karakter)" }
        }

        var conv = get(convId)
        if (conv == null) {
            // Seharusnya tidak terjadi (layar chat selalu dibuka dengan id valid),
            // tapi buat dengan id yang diminta agar tidak kehilangan konteks.
            val now = System.currentTimeMillis()
            conv = newConversation(convId, createdAt = now)
            _conversations.value = listOf(conv) + _conversations.value
        }

        val nowSec = System.currentTimeMillis() / 1000
        val wasNew = conv.messages.isEmpty()

        val userMsg: ChatMessage?
        val aiMsg: ChatMessage
        val context: List<ApiMessage>
        val titleSource: String

        when (mode) {
            is SendMode.Regenerate -> {
                val oldAi = conv.messages[mode.assistantMsgId] ?: return SendResult.Empty
                val userOld = oldAi.parentId?.let { conv.messages[it] } ?: return SendResult.Empty
                userMsg = null
                aiMsg = newMessage(newMsgId(), "assistant", parentId = userOld.id, timestamp = nowSec + 1)
                context = getContextFromAnchor(conv, userOld.id, 20)
                titleSource = userOld.content
            }
            is SendMode.Edit -> {
                val target = conv.messages[mode.targetId] ?: return SendResult.Empty
                userMsg = newMessage(newMsgId(), "user", trimmed, timestamp = nowSec, parentId = target.parentId)
                aiMsg = newMessage(newMsgId(), "assistant", parentId = userMsg.id, timestamp = nowSec + 1)
                context = getContextFromAnchor(conv, target.parentId, 20) + ApiMessage("user", trimmed)
                titleSource = trimmed
            }
            SendMode.New -> {
                val anchorId = conv.activeLeafId
                userMsg = newMessage(newMsgId(), "user", trimmed, timestamp = nowSec, parentId = anchorId)
                aiMsg = newMessage(newMsgId(), "assistant", parentId = userMsg.id, timestamp = nowSec + 1)
                context = getContextFromAnchor(conv, anchorId, 20) + ApiMessage("user", trimmed)
                titleSource = trimmed
            }
        }

        // Pasang ke pohon (seperti START_SEND di web)
        if (userMsg != null) conv = attachMessage(conv, userMsg)
        conv = attachMessage(conv, aiMsg)
        update(conv)
        _streamingMsgId.value = aiMsg.id

        cancelFlag.set(false)
        streamSource = null

        return try {
            val text = withContext(Dispatchers.IO) {
                api.chatStream(
                    message = titleSource,
                    model = model,
                    history = context,
                    onDelta = { partial ->
                        // Tulis parsial langsung ke pohon (StateFlow thread-safe)
                        // supaya Stop mempertahankan teks yang sudah keluar.
                        get(convId)?.let { cur ->
                            update(updateMessage(cur, aiMsg.id) { it.copy(content = partial) })
                        }
                        onDelta(aiMsg.id, partial)
                    },
                    cancelFlag = cancelFlag,
                    onSource = { streamSource = it },
                )
            }

            if (text.isEmpty()) {
                // Model selesai tanpa output — buang bubble kosong (seperti web)
                update(detachSubtree(get(convId) ?: conv, aiMsg.id))
                SendResult.Empty
            } else {
                var c = get(convId) ?: conv
                c = updateMessage(c, aiMsg.id) { it.copy(content = text, state = MsgState.DONE) }
                update(c)
                persist(c)

                // Judul otomatis hanya untuk percakapan baru (seperti web)
                if (wasNew) {
                    scope.launch { generateAndApplyTitle(c.id, titleSource, text, model) }
                }
                SendResult.Done
            }
        } catch (e: StreamCancelledException) {
            var c = get(convId) ?: conv
            val partial = c.messages[aiMsg.id]?.content.orEmpty()
            c = if (partial.isEmpty()) {
                detachSubtree(c, aiMsg.id)
            } else {
                updateMessage(c, aiMsg.id) { it.copy(state = MsgState.ABORTED) }
            }
            update(c)
            persist(c)
            SendResult.Aborted
        } finally {
            _streamingMsgId.value = null
            streamSource = null
        }
    }

    /** Hentikan generasi yang sedang berjalan. */
    fun stopGeneration() {
        cancelFlag.set(true)
        streamSource?.cancel()
    }

    private suspend fun generateAndApplyTitle(convId: String, userText: String, aiText: String, model: String) {
        val prompt =
            "Ringkas percakapan berikut jadi judul sangat pendek (maks 4 kata). " +
                "Hanya keluar judul, tanpa kutip, tanpa tanda baca di akhir.\n\n" +
                "User: ${userText.take(300)}\n\nAssistant: ${aiText.take(500)}"
        val title = try {
            val res = withContext(Dispatchers.IO) {
                api.chatJson(prompt, model, emptyList(), system = false)
            }
            res.message.trim().split("\n").firstOrNull().orEmpty()
                .replace(Regex("^[\"'`\\s]+|[\"'`\\s]+$"), "")
                .replace(Regex("[.!?]+$"), "")
                .trim().take(40).ifEmpty { null }
        } catch (e: Exception) {
            null
        } ?: fallbackTitle(userText)

        val conv = get(convId) ?: return
        update(conv.copy(title = title, titlePending = false))
        try {
            withContext(Dispatchers.IO) { api.patchConversation(convId, title = title, titlePending = false) }
        } catch (e: Exception) {
            // judul lokal tetap dipakai; sync penuh berikutnya memperbaiki
        }
    }

    /** Simpan pohon utuh ke worker (best effort, seperti web). */
    private fun persist(conv: Conversation) {
        scope.launch(Dispatchers.IO) {
            runCatching { api.saveConversation(get(conv.id) ?: conv) }
        }
    }

    // ------------------------------------------------------------ aksi daftar

    suspend fun rename(id: String, title: String) {
        val conv = get(id) ?: return
        update(conv.copy(title = title))
        withContext(Dispatchers.IO) {
            runCatching { api.patchConversation(id, title = title) }
        }
    }

    suspend fun togglePin(id: String) {
        val conv = get(id) ?: return
        val pinned = !conv.pinned
        // Urutan ulang lokal: pinned dulu, lalu updatedAt (seperti server)
        update(conv.copy(pinned = pinned))
        resort()
        withContext(Dispatchers.IO) {
            runCatching { api.patchConversation(id, pinned = pinned) }
        }
    }

    private fun resort() {
        _conversations.value = _conversations.value.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt },
        )
    }

    suspend fun delete(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        withContext(Dispatchers.IO) {
            runCatching { api.deleteConversation(id) }
        }
    }

    suspend fun deleteAll(): Int {
        val n = withContext(Dispatchers.IO) {
            runCatching { api.deleteAllConversations() }.getOrDefault(0)
        }
        _conversations.value = emptyList()
        return n
    }

    fun navigateBranch(convId: String, msgId: String, dir: Int) {
        val conv = get(convId) ?: return
        val newLeaf = navigateBranch(conv, msgId, dir) ?: return
        update(conv.copy(activeLeafId = newLeaf, updatedAt = System.currentTimeMillis()))
        persist(get(convId) ?: return)
    }
}
