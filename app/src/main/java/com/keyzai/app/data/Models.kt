package com.keyzai.app.data

import kotlinx.serialization.Serializable

/**
 * Data model — mirror dari kontrak JSON worker KeyzAI
 * (worker/src/db.js rowToConv + src/state/tree.js).
 * Format ini SAMA dengan yang dipakai web, sehingga riwayat
 * tersinkron dua arah antara web dan aplikasi Android.
 */

@Serializable
data class User(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val picture: String? = null,
)

object MsgState {
    const val STREAMING = "streaming"
    const val DONE = "done"
    const val ABORTED = "aborted"
    const val ERROR = "error"
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant"
    val content: String = "",
    val timestamp: Long = 0L, // detik (seperti web)
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val state: String = MsgState.DONE,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String = "",
    val titlePending: Boolean = false,
    val createdAt: Long = 0L, // ms
    val updatedAt: Long = 0L, // ms
    val pinned: Boolean = false,
    val rootId: String? = null,
    val activeLeafId: String? = null,
    val messages: Map<String, ChatMessage> = emptyMap(),
)

/** Pesan untuk konteks API: hanya role + content. */
@Serializable
data class ApiMessage(val role: String, val content: String)

data class AiModel(
    val id: String,
    val label: String,
    val provider: String,
    val tagline: String,
)

val MODELS = listOf(
    AiModel("qwen3.8-flash", "Qwen 3.8 Flash", "Alibaba", "Cepat & serbaguna"),
    AiModel("deepseek-v4-flash", "DeepSeek V4 Flash", "DeepSeek", "Reasoning terbaik"),
    AiModel("Atria-Dawn-Preview", "Atria Dawn Preview", "Z.ai", "Baru & eksperimental"),
)
const val DEFAULT_MODEL = "qwen3.8-flash"

fun modelById(id: String?): AiModel = MODELS.find { it.id == id } ?: MODELS[0]

@Serializable
data class UserPreferences(
    val default_model: String? = null,
    val display_name: String? = null,
)

fun newConvId(): String = "conv_${java.util.UUID.randomUUID()}"
fun newMsgId(): String = "msg_${java.util.UUID.randomUUID()}"
