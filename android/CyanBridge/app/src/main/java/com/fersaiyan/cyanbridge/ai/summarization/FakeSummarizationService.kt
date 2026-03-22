package com.fersaiyan.cyanbridge.ai.summarization

/**
 * Deterministic summarizer for tests.
 */
class FakeSummarizationService(
    private val fixedTitle: String = "Test Meeting",
) : SummarizationService {

    override suspend fun summarize(request: SummarizationRequest): StructuredSummary {
        val title = request.hintTitle?.trim().takeUnless { it.isNullOrBlank() } ?: fixedTitle

        val summary = (1..7).map { "Summary bullet $it" }
        val actions = listOf("Action item 1", "Action item 2")
        val decisions = listOf("Decision 1")
        val questions = listOf("Open question 1?")
        val timeline = listOf("00:00 Started")

        return StructuredSummary(
            title = title,
            summaryBullets = summary,
            actionItems = actions,
            keyDecisions = decisions,
            openQuestions = questions,
            timelineHighlights = timeline,
        )
    }
}
