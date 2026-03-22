package com.fersaiyan.cyanbridge.ai.summarization

/**
 * Chapter 7: Structured notes returned by SummarizationService.
 *
 * Keep this model provider-agnostic and deterministic to enable stable formatting tests.
 */
data class StructuredSummary(
    val title: String,
    val summaryBullets: List<String>,
    val actionItems: List<String>,
    val keyDecisions: List<String>,
    val openQuestions: List<String>,
    val timelineHighlights: List<String> = emptyList(),
)
