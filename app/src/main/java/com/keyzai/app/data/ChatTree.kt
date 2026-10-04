package com.keyzai.app.data

/**
 * Model percakapan sebagai pohon pesan (message tree) — port langsung dari
 * src/state/tree.js di repo web KeyzAI.
 *
 * Setiap edit atau "buat ulang" membuat cabang baru; pesan lama tidak pernah
 * hilang. Hanya satu rantai (root -> activeLeafId) yang dirender.
 */

private const val MAX_TREE_WALK = 500
private val ROLE_LIMITS = mapOf("user" to 2000, "assistant" to 32000)
private const val CUT_MARK = "\n\n[…dipotong…]"

fun newMessage(
    id: String,
    role: String,
    content: String = "",
    timestamp: Long = System.currentTimeMillis() / 1000,
    parentId: String? = null,
    state: String = if (role == "assistant") MsgState.STREAMING else MsgState.DONE,
): ChatMessage {
    require(role == "user" || role == "assistant") { "Invalid role: $role" }
    return ChatMessage(
        id = id, role = role, content = content,
        timestamp = timestamp, parentId = parentId,
        children = emptyList(), state = state,
    )
}

fun newConversation(id: String, title: String = "", createdAt: Long = System.currentTimeMillis()): Conversation =
    Conversation(id = id, title = title, createdAt = createdAt, updatedAt = createdAt)

/** Judul darurat dari teks user pertama (maks 30 char + elipsis). */
fun fallbackTitle(content: String?): String {
    val c = content?.trim().orEmpty()
    if (c.isEmpty()) return "Chat"
    return if (c.length > 30) c.take(30) + "..." else c
}

private fun walkToRoot(messages: Map<String, ChatMessage>, id: String?): List<ChatMessage> {
    val out = ArrayDeque<ChatMessage>()
    val seen = mutableSetOf<String>()
    var cur = id
    while (cur != null && cur !in seen && out.size < MAX_TREE_WALK) {
        val m = messages[cur] ?: break
        seen.add(cur)
        out.addFirst(m)
        cur = m.parentId
    }
    return out.toList()
}

/** Daun terdalam dari sebuah node: turuti anak terakhir sampai habis. */
fun deepestLeaf(messages: Map<String, ChatMessage>, id: String?): String? {
    val seen = mutableSetOf<String>()
    var cur = id
    while (cur != null && cur !in seen) {
        val m = messages[cur] ?: return cur
        if (m.children.isEmpty()) return cur
        seen.add(cur)
        cur = m.children.last()
    }
    return cur
}

/** Rantai aktif root -> activeLeafId: percakapan yang dirender. */
fun getActivePath(conv: Conversation?): List<ChatMessage> {
    if (conv == null) return emptyList()
    return walkToRoot(conv.messages, conv.activeLeafId)
}

/**
 * Konteks untuk API: pesan dari anchor ke root (termasuk anchor),
 * maks `limit` pesan terakhir. Konten yang melebihi batas worker dipotong.
 */
fun getContextFromAnchor(conv: Conversation?, anchorId: String?, limit: Int = 20): List<ApiMessage> {
    if (conv == null || anchorId == null) return emptyList()
    return walkToRoot(conv.messages, anchorId)
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(limit)
        .map { m ->
            val max = ROLE_LIMITS[m.role] ?: 2000
            val content = m.content
            if (content.length > max) {
                ApiMessage(m.role, content.take(max - CUT_MARK.length) + CUT_MARK)
            } else {
                ApiMessage(m.role, content)
            }
        }
}

/** Pasang pesan ke pohon: link parent <- child, pindahkan activeLeafId. */
fun attachMessage(conv: Conversation, msg: ChatMessage): Conversation {
    val messages = conv.messages.toMutableMap()
    messages[msg.id] = msg
    var rootId = conv.rootId
    val parentId = msg.parentId
    if (parentId != null) {
        val parent = messages[parentId]
        if (parent != null) {
            messages[parentId] = parent.copy(children = parent.children + msg.id)
        } else {
            rootId = msg.id // parent hilang (data rusak) — jadi root baru
        }
    } else {
        rootId = msg.id
    }
    return conv.copy(messages = messages, rootId = rootId, activeLeafId = msg.id, updatedAt = System.currentTimeMillis())
}

/** Update konten/state sebuah pesan di pohon. */
fun updateMessage(conv: Conversation, msgId: String, transform: (ChatMessage) -> ChatMessage): Conversation {
    val m = conv.messages[msgId] ?: return conv
    val messages = conv.messages.toMutableMap()
    messages[msgId] = transform(m)
    return conv.copy(messages = messages, updatedAt = System.currentTimeMillis())
}

/**
 * Buang sebuah pesan beserta seluruh turunannya; koreksi activeLeafId ke
 * daun terdekat yang tersisa. Dipakai untuk bubble AI kosong (abort /
 * jawaban kosong) agar tidak meninggalkan pesan mati.
 */
fun detachSubtree(conv: Conversation, msgId: String): Conversation {
    val messages = conv.messages.toMutableMap()
    val target = messages[msgId] ?: return conv
    val orphans = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    stack.add(msgId)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (!orphans.add(cur)) continue
        messages[cur]?.children?.let { stack.addAll(it) }
    }
    val parentId = target.parentId
    if (parentId != null) {
        messages[parentId]?.let { p ->
            messages[parentId] = p.copy(children = p.children.filter { it !in orphans })
        }
    }
    orphans.forEach { messages.remove(it) }

    val rootId = if (conv.rootId != null && messages.containsKey(conv.rootId)) conv.rootId else null
    var activeLeafId: String? = null
    if (rootId != null) {
        val back = if (parentId != null && messages.containsKey(parentId)) parentId else rootId
        activeLeafId = deepestLeaf(messages, back)
    }
    return conv.copy(messages = messages, rootId = rootId, activeLeafId = activeLeafId, updatedAt = System.currentTimeMillis())
}

/**
 * Navigasi cabang: pindah ke sibling sebelum/sesudah pesan ini, lalu turun
 * ke daunnya. Mengembalikan activeLeafId baru, atau null bila tidak ada.
 */
fun navigateBranch(conv: Conversation, msgId: String, dir: Int): String? {
    val m = conv.messages[msgId] ?: return null
    val parentId = m.parentId ?: return null
    val parent = conv.messages[parentId] ?: return null
    val idx = parent.children.indexOf(msgId)
    if (idx == -1) return null
    val next = idx + dir
    if (next < 0 || next >= parent.children.size) return null
    return deepestLeaf(conv.messages, parent.children[next])
}

/** Index sibling (0-based) dan total sibling — untuk indikator "1 / 2". */
fun siblingPosition(conv: Conversation, msgId: String): Pair<Int, Int>? {
    val m = conv.messages[msgId] ?: return null
    val parentId = m.parentId ?: return null
    val parent = conv.messages[parentId] ?: return null
    if (parent.children.size < 2) return null
    val idx = parent.children.indexOf(msgId)
    if (idx == -1) return null
    return idx to parent.children.size
}
