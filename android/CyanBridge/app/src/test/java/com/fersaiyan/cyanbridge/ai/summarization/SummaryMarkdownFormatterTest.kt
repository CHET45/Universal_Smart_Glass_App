package com.fersaiyan.cyanbridge.ai.summarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryMarkdownFormatterTest {

    @Test
    fun `format emits stable headings in order and fills empty sections`() {
        val summary = StructuredSummary(
            title = "Test Title",
            summaryBullets = emptyList(),
            actionItems = emptyList(),
            keyDecisions = emptyList(),
            openQuestions = emptyList(),
            timelineHighlights = emptyList(),
        )

        val out = SummaryMarkdownFormatter.format(summary)

        val expectedHeadings = listOf(
            "# Test Title",
            "## Summary",
            "## Action items",
            "## Key decisions",
            "## Open questions",
            "## Timeline highlights",
        )

        var lastIndex = -1
        for (h in expectedHeadings) {
            val idx = out.indexOf(h)
            assertTrue("Missing heading: $h", idx >= 0)
            assertTrue("Heading out of order: $h", idx > lastIndex)
            lastIndex = idx
        }

        // Each empty section should emit a bullet.
        assertTrue(out.contains("## Summary\n- (none)"))
        assertTrue(out.contains("## Action items\n- (none)"))
        assertTrue(out.contains("## Key decisions\n- (none)"))
        assertTrue(out.contains("## Open questions\n- (none)"))
        assertTrue(out.contains("## Timeline highlights\n- (none)"))
    }

    @Test
    fun `format normalizes bullets and trims`() {
        val summary = StructuredSummary(
            title = "  ",
            summaryBullets = listOf("-  first\nline", "   second   line   "),
            actionItems = listOf("- todo:   x"),
            keyDecisions = emptyList(),
            openQuestions = emptyList(),
            timelineHighlights = emptyList(),
        )

        val out = SummaryMarkdownFormatter.format(summary)

        assertTrue(out.startsWith("# Meeting Notes"))
        assertTrue(out.contains("- first line"))
        assertTrue(out.contains("- second line"))
        assertTrue(out.contains("## Action items\n- todo: x"))
    }
}
