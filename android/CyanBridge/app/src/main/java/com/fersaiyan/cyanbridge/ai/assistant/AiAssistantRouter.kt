package com.fersaiyan.cyanbridge.ai.assistant

import android.content.Context
import com.fersaiyan.cyanbridge.chat.ChatMessage

/**
 * Central routing point for the chat assistant.
 *
 * For now, this is intentionally small: it exists to make provider routing explicit and testable.
 */
class AiAssistantRouter(
    private val localAgentRouter: LocalAgentRouter = LocalAgentRouter(),
) {

    suspend fun generateReply(
        context: Context,
        providerType: AiProviderType,
        messages: List<ChatMessage>,
    ): String {
        return when (providerType) {
            AiProviderType.LOCAL_AGENT -> localAgentRouter.generateReply(context, messages)

            // Not yet wired: keep behavior deterministic and safe.
            else -> "Provider not implemented: ${providerType.label}"
        }
    }
}
