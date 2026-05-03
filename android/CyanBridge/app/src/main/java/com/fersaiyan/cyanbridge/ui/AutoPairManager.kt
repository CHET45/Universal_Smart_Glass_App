package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager

object AutoPairManager {


    private const val TAG = "AutoPairManager"

    @Volatile
    private var autoReconnectSuppressed: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {

        requestConnect(context.applicationContext, reason = "app_start")
    }

    fun setAutoReconnectSuppressed(suppressed: Boolean, reason: String = "") {
        autoReconnectSuppressed = suppressed
        Log.i(TAG, "setAutoReconnectSuppressed=$suppressed reason=$reason")
    }

    fun requestConnect(context: Context, reason: String = "") {
        if (autoReconnectSuppressed) {
            Log.i(TAG, "Auto reconnect suppressed, skip requestConnect. reason=$reason")
            return
        }

        val mac = DeviceManager.getInstance().deviceAddress

        if (mac.isNullOrBlank()) {
            Log.i(TAG, "No saved device address, skip requestConnect. reason=$reason")
            return
        }

        requestConnectToMac(context, mac, reason)
    }

    fun requestConnectToMac(context: Context, macAddress: String, reason: String = "") {
        if (autoReconnectSuppressed) {
            Log.i(TAG, "Auto reconnect suppressed, skip requestConnectToMac. reason=$reason")
            return
        }

        if (macAddress.isBlank()) {
            Log.i(TAG, "Empty macAddress, skip requestConnectToMac. reason=$reason")
            return
        }

        handler.post {
            try {
                Log.i(TAG, "Connecting to $macAddress reason=$reason")
                BleOperateManager.getInstance().connectDirectly(macAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $macAddress reason=$reason", e)
            }
        }
    }
}