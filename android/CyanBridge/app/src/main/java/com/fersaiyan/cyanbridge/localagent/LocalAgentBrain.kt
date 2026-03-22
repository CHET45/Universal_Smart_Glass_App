package com.fersaiyan.cyanbridge.localagent

/**
 * "Brain" interface: takes an observation and returns a JSON plan.
 *
 * Keep it pluggable so we can later route to a local LLM, remote endpoint,
 * or a scripted policy.
 */
interface LocalAgentBrain {
    suspend fun next(observation: LocalAgentObservation): LocalAgentBrainOutput
}

data class LocalAgentBrainOutput(
    /**
     * JSON array of action objects, e.g.
     *   [{"type":"sleep","ms":250},{"type":"click_text","text":"OK"}]
     */
    val actionsJson: String? = null,
    val note: String? = null,
)

class NoOpLocalAgentBrain : LocalAgentBrain {
    override suspend fun next(observation: LocalAgentObservation): LocalAgentBrainOutput {
        return LocalAgentBrainOutput(actionsJson = "[]", note = "noop")
    }
}
