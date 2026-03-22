package com.fersaiyan.cyanbridge.localagent

/**
 * Intent contract between the app UI and the (optional) local agent runtime.
 *
 * The service implementation may not exist in all builds yet; callers must be resilient.
 */
object LocalAgentIntents {
    // Commands (UI -> Service)
    const val ACTION_START = "com.fersaiyan.cyanbridge.localagent.action.START"
    const val ACTION_STOP = "com.fersaiyan.cyanbridge.localagent.action.STOP"
    const val ACTION_DEMO = "com.fersaiyan.cyanbridge.localagent.action.DEMO"
    const val ACTION_GET_STATUS = "com.fersaiyan.cyanbridge.localagent.action.GET_STATUS"

    // Events (Service -> UI)
    const val ACTION_STATUS_CHANGED = "com.fersaiyan.cyanbridge.localagent.action.STATUS_CHANGED"

    // Extras
    const val EXTRA_STATUS = "status"
    const val EXTRA_LAST_ERROR = "last_error"
}
