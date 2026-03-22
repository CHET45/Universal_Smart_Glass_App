package com.fersaiyan.cyanbridge.ai.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    @Test
    fun plan_nullOrNonPositiveDuration_returnsEmpty() {
        assertTrue(ChunkPlanner.plan(null, 60_000L).isEmpty())
        assertTrue(ChunkPlanner.plan(0L, 60_000L).isEmpty())
        assertTrue(ChunkPlanner.plan(-1L, 60_000L).isEmpty())
    }

    @Test
    fun plan_nonPositiveChunkDuration_returnsSingleRange() {
        val ranges = ChunkPlanner.plan(totalDurationMs = 120_000L, chunkDurationMs = 0L)
        assertEquals(1, ranges.size)
        assertEquals(0L, ranges[0].startMs)
        assertEquals(120_000L, ranges[0].endMsExclusive)
    }

    @Test
    fun plan_shortAudio_returnsSingleChunk() {
        val ranges = ChunkPlanner.plan(totalDurationMs = 10_000L, chunkDurationMs = 60_000L)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].index)
        assertEquals(1, ranges[0].total)
        assertEquals(0L, ranges[0].startMs)
        assertEquals(10_000L, ranges[0].endMsExclusive)
    }

    @Test
    fun plan_exactMultiple_returnsEvenChunks() {
        val ranges = ChunkPlanner.plan(totalDurationMs = 180_000L, chunkDurationMs = 60_000L)
        assertEquals(3, ranges.size)
        assertEquals(listOf(0L, 60_000L, 120_000L), ranges.map { it.startMs })
        assertEquals(listOf(60_000L, 120_000L, 180_000L), ranges.map { it.endMsExclusive })
        assertTrue(ranges.all { it.total == 3 })
    }

    @Test
    fun plan_remainder_lastChunkShorter() {
        val ranges = ChunkPlanner.plan(totalDurationMs = 125_000L, chunkDurationMs = 60_000L)
        assertEquals(3, ranges.size)
        assertEquals(0L, ranges[0].startMs)
        assertEquals(60_000L, ranges[0].endMsExclusive)
        assertEquals(60_000L, ranges[1].startMs)
        assertEquals(120_000L, ranges[1].endMsExclusive)
        assertEquals(120_000L, ranges[2].startMs)
        assertEquals(125_000L, ranges[2].endMsExclusive)
        assertTrue(ranges.all { it.total == 3 })
    }
}
