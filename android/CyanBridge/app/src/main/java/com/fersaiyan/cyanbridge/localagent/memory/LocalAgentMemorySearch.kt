package com.fersaiyan.cyanbridge.localagent.memory

import android.content.Context
import com.fersaiyan.cyanbridge.memoryvault.MemorySearchOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Backward-compatible entry point for local memory retrieval.
 *
 * Existing callers keep using this API while internals are orchestrated by
 * the privacy-aware memory vault search layer.
 */
object LocalAgentMemorySearch {
    private const val DEFAULT_FACT_LOOKBACK_DAYS: Int = 7
    private const val DEFAULT_TOP_FACTS: Int = 6
    private const val DEFAULT_TOP_SUMMARY_LINES: Int = 5

    fun buildRelevantMemoryBlock(
        context: Context,
        queryText: String,
        date: String,
        lookbackDaysFacts: Int = DEFAULT_FACT_LOOKBACK_DAYS,
        topFacts: Int = DEFAULT_TOP_FACTS,
        topSummaryLines: Int = DEFAULT_TOP_SUMMARY_LINES,
        maxChars: Int = 1400,
    ): String {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                MemorySearchOrchestrator.buildRelevantMemoryBlock(
                    context = context,
                    queryText = queryText,
                    date = date,
                    params = MemorySearchOrchestrator.SearchParams(
                        lookbackDaysFacts = lookbackDaysFacts,
                        topFacts = topFacts,
                        topSummaryLines = topSummaryLines,
                        topScreenHits = 3,
                        maxChars = maxChars,
                    ),
                )
            }
        }.getOrDefault("")
    }
}
