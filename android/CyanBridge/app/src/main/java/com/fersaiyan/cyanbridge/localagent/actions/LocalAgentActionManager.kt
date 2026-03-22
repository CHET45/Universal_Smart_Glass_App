package com.fersaiyan.cyanbridge.localagent.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ui.MyApplication
import org.json.JSONObject

/**
 * Manages action enqueuing, approval logic, and execution of system intents.
 */
object LocalAgentActionManager {

    enum class Risk { LOW, MEDIUM, HIGH }

    fun classifyRisk(action: LocalAgentAction): Risk {
        return when (action) {
            is LocalAgentAction.Sleep,
            is LocalAgentAction.GlobalBack,
            is LocalAgentAction.GlobalHome -> Risk.LOW
            is LocalAgentAction.ClickText,
            is LocalAgentAction.TypeText -> Risk.MEDIUM
            is LocalAgentAction.SendEmail -> Risk.HIGH
        }
    }

    /**
     * Enqueue a planned action for approval or auto-execution.
     */
    suspend fun processPlannedAction(context: Context, action: LocalAgentAction, source: String = "agent"): Boolean {
        val risk = classifyRisk(action)
        val requireConfirm = LocalAgentPrefs.isRequireActionConfirmationEnabled(context)
        val autoLowRisk = LocalAgentPrefs.isAutoExecuteLowRiskEnabled(context)

        val shouldAutoExecute = !requireConfirm || (risk == Risk.LOW && autoLowRisk)

        if (shouldAutoExecute) {
            return executeNow(context, action)
        }

        // Store as pending
        val dao = MyApplication.database.pendingActionDao()
        dao.insert(
            PendingAction(
                ts = System.currentTimeMillis(),
                source = source,
                actionJson = actionToJson(action).toString(),
                status = "pending"
            )
        )
        // TODO: Notify user?
        return false
    }

    /**
     * Executes the action immediately (system intents or accessibility).
     * Accessibility actions are still eventually handled by LocalAgentAccessibilityBridge.
     */
    suspend fun executeNow(context: Context, action: LocalAgentAction): Boolean {
        return when (action) {
            is LocalAgentAction.SendEmail -> {
                sendEmailIntent(context, action)
                true
            }
            else -> {
                // For a11y actions, we typically need to be in the service context.
                // If this is called from the service loop, it's fine.
                // If called from UI (ApprovalActivity), we may need to delegate.
                // Let's assume the service handles accessibility ones.
                false 
            }
        }
    }

    private fun sendEmailIntent(context: Context, action: LocalAgentAction.SendEmail) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${action.to}")
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun actionToJson(action: LocalAgentAction): JSONObject {
        val obj = JSONObject()
        when (action) {
            is LocalAgentAction.Sleep -> {
                obj.put("type", "sleep")
                obj.put("ms", action.ms)
            }
            is LocalAgentAction.GlobalBack -> obj.put("type", "back")
            is LocalAgentAction.GlobalHome -> obj.put("type", "home")
            is LocalAgentAction.ClickText -> {
                obj.put("type", "click_text")
                obj.put("text", action.text)
            }
            is LocalAgentAction.TypeText -> {
                obj.put("type", "type_text")
                obj.put("text", action.text)
            }
            is LocalAgentAction.SendEmail -> {
                obj.put("type", "send_email")
                obj.put("to", action.to)
                obj.put("subject", action.subject)
                obj.put("body", action.body)
            }
        }
        return obj
    }
}
