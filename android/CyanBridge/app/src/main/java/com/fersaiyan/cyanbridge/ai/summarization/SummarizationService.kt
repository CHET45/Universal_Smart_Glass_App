package com.fersaiyan.cyanbridge.ai.summarization

/**
 * Chapter 7: Summarization interface.
 *
 * Input: transcript text
 * Output: structured notes
 */
interface SummarizationService {
    suspend fun summarize(request: SummarizationRequest): StructuredSummary
}
