package com.fersaiyan.cyanbridge.privacy

import android.content.Context

/**
 * Centralizes Chapter 8 export privacy behavior.
 *
 * Note: current Note schema (Chapter 7) is owned elsewhere. This formatter accepts plain strings
 * so sharing/export flows stay decoupled from persistence details.
 */
object NoteExportFormatter {

    data class Input(
        val title: String,
        val noteBody: String,
        val fullTranscript: String? = null,
    )

    fun format(context: Context, input: Input, redactor: Redactor = HeuristicRedactor()): String {
        val redact = PrivacyPrefs.isRedactNamesEnabled(context)
        val transcriptStorage = PrivacyPrefs.isTranscriptStorageEnabled(context)
        val includeTranscript = PrivacyPrefs.isIncludeFullTranscriptionInExportsEnabled(context)

        fun maybeRedact(s: String): String = if (redact) redactor.redact(s) else s

        val sb = StringBuilder()
        sb.appendLine(maybeRedact(input.title))
        sb.appendLine()
        sb.appendLine(maybeRedact(input.noteBody))

        val transcript = input.fullTranscript
        // Full transcript is included only when both storage and export toggles permit it.
        if (transcriptStorage && includeTranscript && !transcript.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine("Full transcription")
            sb.appendLine()
            sb.appendLine(maybeRedact(transcript))
        }

        return sb.toString().trimEnd()
    }
}
