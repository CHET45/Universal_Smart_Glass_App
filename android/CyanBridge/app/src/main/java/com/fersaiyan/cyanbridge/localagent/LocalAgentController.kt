package com.fersaiyan.cyanbridge.localagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Best-effort launcher for LocalAgentService via intent actions.
 *
 * Notes:
 * - Starting services via implicit intents is not allowed on Android 5.0+.
 * - We resolve the service and set an explicit component when possible.
 * - If the service is not present in this build, calls fail gracefully.
 */
object LocalAgentController {

    data class CommandResult(
        val ok: Boolean,
        val userMessage: String,
        val error: String? = null,
    )

    // If/when LocalAgentService is added, we expect it to live here.
    private const val DEFAULT_SERVICE_CLASS = "com.fersaiyan.cyanbridge.localagent.LocalAgentService"

    fun start(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_START)

    fun stop(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_STOP)

    fun demo(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_DEMO)

    fun requestStatus(context: Context): CommandResult =
        sendServiceCommand(context, LocalAgentIntents.ACTION_GET_STATUS)

    private fun sendServiceCommand(context: Context, action: String): CommandResult {
        val pm = context.packageManager

        // 1) Prefer resolving by action (requires LocalAgentService to declare an intent-filter).
        val implicit = Intent(action).setPackage(context.packageName)
        val resolved = pm.queryIntentServicesCompat(implicit)

        val explicitIntent = when {
            resolved.isNotEmpty() -> {
                val svcInfo = resolved.first().serviceInfo
                val comp = ComponentName(svcInfo.packageName, svcInfo.name)
                Intent(action).setComponent(comp)
            }

            // 2) Fallback to explicit class name (requires LocalAgentService to exist + be declared).
            else -> Intent(action).setClassName(context.packageName, DEFAULT_SERVICE_CLASS)
        }

        val canResolve = pm.resolveService(explicitIntent, 0) != null
        if (!canResolve) {
            return CommandResult(
                ok = false,
                userMessage = "Local agent is not available in this build.",
                error = "No service found for $action",
            )
        }

        return try {
            val needsForeground = action == LocalAgentIntents.ACTION_START
            if (needsForeground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(explicitIntent)
            } else {
                context.startService(explicitIntent)
            }
            CommandResult(ok = true, userMessage = "Command sent: ${action.substringAfterLast('.')}" )
        } catch (e: Exception) {
            CommandResult(
                ok = false,
                userMessage = "Failed to send agent command.",
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun PackageManager.queryIntentServicesCompat(intent: Intent): List<android.content.pm.ResolveInfo> {
        @Suppress("DEPRECATION")
        return queryIntentServices(intent, 0)
    }
}
