package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.util.Log

/**
 * Autopair/autoreconnect is intentionally disabled.
 *
 * Keep this object as a compatibility shim for callers that still reference
 * AutoPairManager, but never initiate BLE connection or classic BT bonding from
 * here. Manual scan/connect/disconnect flows remain handled by the UI/protocol
 * layer.
 */
object AutoPairManager {

    private const val TAG = "AutoPairManager"

    fun start(context: Context) {
        Log.i(TAG, "Autopair disabled; ignoring start for ${context.packageName}")
    }

    fun setAutoReconnectSuppressed(suppressed: Boolean, reason: String = "") {
        Log.i(TAG, "Autopair disabled; ignoring suppression=$suppressed reason=$reason")
    }

    fun requestConnect(context: Context, reason: String = "") {
        Log.i(TAG, "Autopair disabled; ignoring requestConnect reason=$reason package=${context.packageName}")
    }

    fun requestConnectToMac(context: Context, macAddress: String, reason: String = "") {
        Log.i(
            TAG,
            "Autopair disabled; ignoring requestConnectToMac mac=$macAddress reason=$reason package=${context.packageName}"
        )
    }
}
