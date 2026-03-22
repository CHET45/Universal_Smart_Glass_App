package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import kotlinx.coroutines.delay

class LocalAgentStepEngine(
    private val context: Context,
    private val executor: LocalAgentActionExecutor,
) {
    /**
     * Executes a list of actions sequentially.
     * Some actions may be enqueued for approval instead of being executed immediately.
     */
    suspend fun execute(actions: List<LocalAgentAction>) {
        for (a in actions) {
            executor.ensureNotCancelled()

            if (a is LocalAgentAction.Sleep) {
                delay(a.ms)
                continue
            }

            val risk = LocalAgentActionManager.classifyRisk(a)
            val requireConfirm = LocalAgentPrefs.isRequireActionConfirmationEnabled(context)
            val autoLowRisk = LocalAgentPrefs.isAutoExecuteLowRiskEnabled(context)

            val shouldAutoExecute = !requireConfirm || (risk == LocalAgentActionManager.Risk.LOW && autoLowRisk)

            if (shouldAutoExecute) {
                // Try executing as a system intent first (e.g. Email)
                val intentOk = LocalAgentActionManager.executeNow(context, a)
                if (intentOk) {
                    Log.i(TAG, "action=${a.javaClass.simpleName} executed as system intent")
                } else {
                    // Fallback to accessibility
                    val ok = executor.execute(a)
                    Log.i(TAG, "action=${a.javaClass.simpleName} executed via a11y ok=$ok")
                }
            } else {
                // Enqueue for manual approval.
                Log.i(TAG, "action=${a.javaClass.simpleName} requires approval, enqueuing.")
                LocalAgentActionManager.processPlannedAction(context, a)
                // When an action is pending, we typically want to stop the current plan
                // until the user approves it.
                break 
            }
        }
    }

    interface LocalAgentActionExecutor {
        suspend fun execute(action: LocalAgentAction): Boolean
        fun ensureNotCancelled()
    }

    private companion object {
        private const val TAG = "LocalAgentSteps"
    }
}
