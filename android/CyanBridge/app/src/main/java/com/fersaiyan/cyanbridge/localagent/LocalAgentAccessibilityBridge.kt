package com.fersaiyan.cyanbridge.localagent

import android.util.Log
import com.fersaiyan.cyanbridge.localagent.accessibility.LocalAgentAccessibilityService

/**
 * Simple in-process bridge between our foreground [LocalAgentService] and the
 * [LocalAgentAccessibilityService] singleton.
 */
object LocalAgentAccessibilityBridge {
    private const val TAG = "LocalAgentBridge"

    fun isConnected(): Boolean = LocalAgentAccessibilityService.instance != null

    fun snapshotScreenText(maxChars: Int = 12_000): String? {
        val svc = LocalAgentAccessibilityService.instance ?: return null
        return try {
            svc.dumpActiveWindowText()
                ?.take(maxChars)
        } catch (e: Exception) {
            Log.w(TAG, "snapshotScreenText failed: ${e.message}")
            null
        }
    }

    suspend fun perform(action: LocalAgentAction): Boolean {
        val svc = LocalAgentAccessibilityService.instance ?: return false
        return try {
            when (action) {
                is LocalAgentAction.Sleep -> true
                LocalAgentAction.GlobalBack -> svc.pressBack()
                LocalAgentAction.GlobalHome -> svc.pressHome()
                is LocalAgentAction.ClickText -> svc.clickByTextOrDesc(action.text)
                is LocalAgentAction.TypeText -> svc.typeTextBestEffort(action.text)
                is LocalAgentAction.SendEmail -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "perform failed: ${e.message}")
            false
        }
    }
}
