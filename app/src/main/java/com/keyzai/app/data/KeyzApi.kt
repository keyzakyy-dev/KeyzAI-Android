package com.keyzai.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Dilempar saat server menjawab 401 — sesi berakhir, user harus login ulang. */
class AuthException(message: String) : Exception(message)

/** Dilempar saat user menekan Stop di tengah streaming. */
class StreamCancelledException : Exception("Dihentikan")

/**
 * HTTP client untuk worker KeyzAI. Mirror dari src/api.js + src/lib/sync.js.
 * Semua request terautentikasi memakai `Authorization: Bearer <session JWT>`.
 */
class KeyzApi(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) {
    companion object {
        const val BASE_URL = "https://keyzai-worker-prod.2406007.workers.dev"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ util

    private fun authed(builder: Request.Builder): Request.Builder {
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun buildRequest(path: String, method: String = "GET", bodyJson: String? = null): Request {
        val b = Request.Builder().url(BASE_URL + path)
        authed(b)
        val body = bodyJson?.toRequestBody(jsonMedia)
        when (method) {
            "POST" -> b.post(body ?: "{}".toRequestBody(jsonMedia))
            "PUT" -> b.put(body ?: "{}".toRequestBody(jsonMedia))
            "PATCH" -> b.patch(body ?: "{}".toRequestBody(jsonMedia))
            "DELETE" -> if (body != null) b.delete(body) else b.delete()
            else -> b.get()
        }
        return b.build()
    }

    /** Eksekusi request JSON; melempar AuthException saat 401. */
    private fun executeJson(req: Request): JsonObject {
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401) {
                onUnauthorized()
                throw AuthException("Sesi berakhir. Silakan masuk kembali.")
            }
            val obj = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw Exception("Respons server tidak valid (HTTP ${res.code})")
            }
            if (obj["success"]?.jsonPrimitive?.booleanOrNull != true) {
                throw Exception(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Permintaan gagal")
            }
            return obj
        }
    }

    // ------------------------------------------------------------------ auth

    /** POST /api/auth/google { idToken } -> (sessionToken, user). */
    fun loginWithGoogle(idToken: String): Pair<String, User> {
        val payload = buildJsonObject { put("idToken", idToken) }.toString()
        val obj = executeJson(buildRequest("/api/auth/google", "POST", payload))
        val token = obj["token"]?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Token tidak ada di respons")
        val user = json.decodeFromJsonElement(User.serializer(), obj["user"] ?: throw Exception("User tidak ada di respons"))
        return token to user
    }

    /** GET /api/auth/me -> user (validasi session). */
    fun me(): User {
        val obj = executeJson(buildRequest("/api/auth/me"))
        return json.decodeFromJsonElement(User.serializer(), obj["user"]!!)
    }

    // ------------------------------------------------------------------ chat

    data class ChatResult(val message: String, val model: String, val tokensUsed: Long)

    /**
     * POST /api/chat non-stream (dipakai untuk generate judul).
     * system=false = opt-out dari injeksi prompt kartu pilihan (seperti web).
     */
    fun chatJson(message: String, model: String?, history: List<ApiMessage>, system: Boolean? = null): ChatResult {
        val payload = buildJsonObject {
            put("message", message)
            if (model != null) put("model", model)
            if (history.isNotEmpty()) put("messages", json.encodeToJsonElement(history))
            if (system != null) put("system", system)
        }.toString()
        val obj = executeJson(buildRequest("/api/chat", "POST", payload))
        return ChatResult(
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
            model = obj["model"]?.jsonPrimitive?.contentOrNull ?: "",
            tokensUsed = obj["tokensUsed"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /**
     * POST /api/chat dengan stream:true. Mengembalikan teks penuh.
     * [onDelta] dipanggil dengan teks kumulatif setiap ada chunk baru.
     * [onSource] menerima EventSource agar bisa di-cancel dari luar;
     * set [cancelFlag] sebelum memanggil `EventSource.cancel()` supaya
     * kegagalan dikenali sebagai pembatalan user (StreamCancelledException).
     */
    fun chatStream(
        message: String,
        model: String?,
        history: List<ApiMessage>,
        onDelta: (String) -> Unit,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onSource: (EventSource) -> Unit = {},
    ): String {
        val payload = buildJsonObject {
            put("message", message)
            put("stream", true)
            if (model != null) put("model", model)
            if (history.isNotEmpty()) put("messages", json.encodeToJsonElement(history))
        }.toString()

        val req = authed(Request.Builder().url("$BASE_URL/api/chat"))
            .post(payload.toRequestBody(jsonMedia))
            .header("Accept", "text/event-stream")
            .build()

        val latch = CountDownLatch(1)
        val sb = StringBuilder()
        var failure: Throwable? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val d = data.trim()
                if (d == "[DONE]") return
                try {
                    val el = json.parseToJsonElement(d).jsonObject
                    el["error"]?.jsonPrimitive?.contentOrNull?.let {
                        failure = Exception(it)
                        eventSource.cancel()
                        return
                    }
                    val delta = el["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(sb.toString())
                    }
                } catch (_: Exception) {
                    // keepalive / baris non-JSON — abaikan (seperti web)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (cancelFlag.get()) {
                    failure = StreamCancelledException()
                } else if (response?.code == 401) {
                    onUnauthorized()
                    failure = AuthException("Sesi berakhir. Silakan masuk kembali.")
                } else {
                    failure = t ?: Exception("Stream gagal (HTTP ${response?.code})")
                }
                latch.countDown()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(req, listener)
        onSource(source)

        val finished = latch.await(180, TimeUnit.SECONDS)
        if (!finished) {
            cancelFlag.set(true)
            source.cancel()
            throw Exception("Stream timeout (tidak ada respons)")
        }
        failure?.let { throw it }
        return sb.toString()
    }

    // ------------------------------------------------------- conversations

    fun getConversations(): List<Conversation> {
        val obj = executeJson(buildRequest("/api/conversations"))
        val arr = obj["conversations"]?.jsonArray ?: return emptyList()
        return arr.map { json.decodeFromJsonElement(Conversation.serializer(), it) }
    }

    fun getConversation(id: String): Conversation {
        val obj = executeJson(buildRequest("/api/conversations/$id"))
        return json.decodeFromJsonElement(Conversation.serializer(), obj["conversation"]!!)
    }

    /** PUT /api/conversations/:id — upsert pohon utuh (seperti web). */
    fun saveConversation(conv: Conversation) {
        val payload = json.encodeToString(Conversation.serializer(), conv)
        executeJson(buildRequest("/api/conversations/${conv.id}", "PUT", payload))
    }

    fun patchConversation(id: String, title: String? = null, titlePending: Boolean? = null, pinned: Boolean? = null) {
        val payload = buildJsonObject {
            if (title != null) put("title", title)
            if (titlePending != null) put("titlePending", titlePending)
            if (pinned != null) put("pinned", pinned)
        }.toString()
        executeJson(buildRequest("/api/conversations/$id", "PATCH", payload))
    }

    fun deleteConversation(id: String) {
        executeJson(buildRequest("/api/conversations/$id", "DELETE"))
    }

    fun deleteAllConversations(): Int {
        val obj = executeJson(buildRequest("/api/conversations", "DELETE"))
        return obj["deleted"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    }

    // ---------------------------------------------------------- preferences

    fun getPreferences(): Pair<UserPreferences, User?> {
        val obj = executeJson(buildRequest("/api/preferences"))
        val prefs = obj["preferences"]?.let {
            json.decodeFromJsonElement(UserPreferences.serializer(), it)
        } ?: UserPreferences()
        val user = obj["user"]?.let { json.decodeFromJsonElement(User.serializer(), it) }
        return prefs to user
    }

    fun savePreferences(patch: Map<String, String>) {
        val payload = buildJsonObject {
            patch.forEach { (k, v) -> put(k, v) }
        }.toString()
        executeJson(buildRequest("/api/preferences", "PUT", payload))
    }
}
