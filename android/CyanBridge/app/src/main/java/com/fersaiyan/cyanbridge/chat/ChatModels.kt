package com.fersaiyan.cyanbridge.chat

/**
 * Chapter 1 (MVP): lightweight in-memory models.
 * Chapter 2 will replace this with persistent storage.
 */
data class ChatThread(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
)

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
)
