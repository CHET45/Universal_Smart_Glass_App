package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reconnects the HeyCyan BLE control channel to the last bound glasses.
 */
object AutoPairManager {

    private const val TAG = "AutoPairManager"
    private const val RETRY_DELAY_MS = 4_000L
    private const val MAX_ATTEMPTS = 4

    @Volatile
    private var autoReconnectSuppressed: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val requestSerial = AtomicInteger(0)

    fun start(context: Context) {
        requestConnect(context.applicationContext, reason = "app_start")
    }

    fun setAutoReconnectSuppressed(suppressed: Boolean, reason: String = "") {
        autoReconnectSuppressed = suppressed
        if (suppressed) requestSerial.incrementAndGet()
        Log.i(TAG, "setAutoReconnectSuppressed=$suppressed reason=$reason")
    }

    fun requestConnect(context: Context, reason: String = "") {
        if (autoReconnectSuppressed) {
            Log.i(TAG, "Auto reconnect suppressed, skip requestConnect. reason=$reason")
            return
        }

        val appContext = context.applicationContext
        val mac = readReconnectMac(appContext)
        if (mac.isNullOrBlank()) {
            Log.i(TAG, "No saved HeyCyan device address, skip requestConnect. reason=$reason")
            return
        }

        requestConnectToMac(appContext, mac, reason)
    }

    fun requestConnectToMac(context: Context, macAddress: String, reason: String = "") {
        if (autoReconnectSuppressed) {
            Log.i(TAG, "Auto reconnect suppressed, skip requestConnectToMac. reason=$reason")
            return
        }

        val mac = macAddress.trim().takeIf { it.isNotEmpty() }
        if (mac == null) {
            Log.i(TAG, "Empty macAddress, skip requestConnectToMac. reason=$reason")
            return
        }

        val appContext = context.applicationContext
        val serial = requestSerial.incrementAndGet()
        scheduleConnect(appContext, mac, reason, attempt = 1, serial = serial, delayMs = 0L)
    }

    fun onConnected(context: Context, device: android.bluetooth.BluetoothDevice?) {
        requestSerial.incrementAndGet()
        autoReconnectSuppressed = false
        if (device != null) {
            HeyCyanDeviceStateStore.bindFromBluetoothDevice(context, device)
        }
    }

    fun onDisconnected(context: Context, reason: String = "") {
        runCatching { BleOperateManager.getInstance().isReady = false }
        requestConnect(context.applicationContext, reason = reason.ifBlank { "disconnected" })
    }

    private fun scheduleConnect(
        context: Context,
        mac: String,
        reason: String,
        attempt: Int,
        serial: Int,
        delayMs: Long,
    ) {
        handler.postDelayed({
            if (serial != requestSerial.get() || autoReconnectSuppressed) return@postDelayed

            val ready = runCatching { BleOperateManager.getInstance().isReady }.getOrDefault(false)
            if (ready) {
                Log.i(TAG, "BLE is already ready; skip reconnect. reason=$reason")
                return@postDelayed
            }

            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                DeviceManager.getInstance().deviceAddress = mac
                Log.i(TAG, "Connecting to $mac reason=$reason attempt=$attempt")
                BleOperateManager.getInstance().connectDirectly(mac)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request BLE connect to $mac reason=$reason attempt=$attempt", e)
            }

            if (attempt < MAX_ATTEMPTS) {
                scheduleConnect(context, mac, reason, attempt + 1, serial, RETRY_DELAY_MS)
            }
        }, delayMs)
    }

    private fun readReconnectMac(context: Context): String? {
        val storedMac = HeyCyanDeviceStateStore.getBoundDeviceAddress(context)
        if (storedMac != null) return storedMac

        val managerMac = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (managerMac != null) return managerMac

        val lastProfile = DeviceProfileStore.loadLastSelected(context) ?: return null
        if (lastProfile.selectedClass != DeviceClass.HEY_CYAN) return null

        HeyCyanDeviceStateStore.bind(context, lastProfile.macAddress, lastProfile.advertisedName)
        return lastProfile.macAddress
    }
}
