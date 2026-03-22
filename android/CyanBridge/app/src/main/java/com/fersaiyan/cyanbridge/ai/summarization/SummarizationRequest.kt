package com.fersaiyan.cyanbridge.ai.summarization

data class SummarizationRequest(
    val transcript: String,
    val hintTitle: String? = null,
    val maxSummaryBullets: Int = 10,
    val minSummaryBullets: Int = 5,
)
