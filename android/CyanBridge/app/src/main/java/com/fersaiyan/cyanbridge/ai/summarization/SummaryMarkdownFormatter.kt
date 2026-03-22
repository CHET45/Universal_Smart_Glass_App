package com.fersaiyan.cyanbridge.ai.summarization

/**
 * Chapter 7: Stable, structured summary formatter.
 *
 * This is intentionally deterministic and emits a fixed set of headings in a fixed order.
 * Tests should target this to enforce export stability across providers.
 */
object SummaryMarkdownFormatter {

    private const val NONE = "(none)"

    fun format(summary: StructuredSummary): String {
        val title = summary.title.trim().ifBlank { "Meeting Notes" }
        return buildString {
            appendLine("# $title")
            appendLine()

            section("Summary", coerceBullets(summary.summaryBullets))
            appendLine()
            section("Action items", summary.actionItems)
            appendLine()
            section("Key decisions", summary.keyDecisions)
            appendLine()
            section("Open questions", summary.openQuestions)
            appendLine()
            section("Timeline highlights", summary.timelineHighlights)
        }.trimEnd()
    }

    private fun StringBuilder.section(heading: String, bullets: List<String>) {
        appendLine("## $heading")
        val cleaned = bullets.mapNotNull { it.cleanBulletOrNull() }
        if (cleaned.isEmpty()) {
            appendLine("- $NONE")
            return
        }
        cleaned.forEach { appendLine("- $it") }
    }

    private fun String.cleanBulletOrNull(): String? {
        val t = trim().removePrefix("-").trim()
        if (t.isBlank()) return null
        // Ensure single-line bullets for stable export.
        return t.replace(Regex("\\s+"), " ")
    }

    private fun coerceBullets(bullets: List<String>): List<String> {
        val cleaned = bullets.mapNotNull { it.cleanBulletOrNull() }
        if (cleaned.isEmpty()) return emptyList()
        // Keep within recommended 5–10 when possible.
        return if (cleaned.size <= 10) cleaned else cleaned.take(10)
    }
}
