package com.fersaiyan.cyanbridge.localagent

/**
 * Minimal action model for the local agent step engine.
 *
 * Actions are expected to be provided as JSON objects with a "type" field.
 * Example:
 *   [{"type":"sleep","ms":250},{"type":"global_back"}]
 */
sealed interface LocalAgentAction {
    data class Sleep(val ms: Long) : LocalAgentAction

    data object GlobalBack : LocalAgentAction
    data object GlobalHome : LocalAgentAction

    /** Best-effort: find a node with matching visible text and click it. */
    data class ClickText(val text: String) : LocalAgentAction

    /** Best-effort: set text into a focused (or first editable) field. */
    data class TypeText(val text: String) : LocalAgentAction

    /** Send an email via ACTION_SENDTO mailto: (requires user confirmation in email app). */
    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String
    ) : LocalAgentAction
}
