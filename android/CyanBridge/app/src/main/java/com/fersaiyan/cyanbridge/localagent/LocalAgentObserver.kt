package com.fersaiyan.cyanbridge.localagent

object LocalAgentObserver {
    fun observe(): LocalAgentObservation {
        val text = LocalAgentAccessibilityBridge.snapshotScreenText()
        return LocalAgentObservation(
            createdAtMs = System.currentTimeMillis(),
            screenText = text,
        )
    }
}
