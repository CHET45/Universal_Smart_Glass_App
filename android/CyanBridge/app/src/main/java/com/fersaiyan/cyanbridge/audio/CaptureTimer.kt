package com.fersaiyan.cyanbridge.audio

/**
 * Pure timer helpers so we can unit test timer behavior without Android dependencies.
 */
object CaptureTimer {
    fun computeStopAtMs(startAtMs: Long, durationSec: Long?): Long? {
        if (durationSec == null) return null
        if (durationSec <= 0) return null
        return startAtMs + durationSec * 1000L
    }

    fun remainingMs(nowMs: Long, stopAtMs: Long?): Long? {
        if (stopAtMs == null) return null
        return stopAtMs - nowMs
    }

    fun isExpired(nowMs: Long, stopAtMs: Long?): Boolean {
        if (stopAtMs == null) return false
        return nowMs >= stopAtMs
    }
}
