package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * System Bluetooth receiver with autopair/autoreconnect disabled.
 *
 * This receiver only mirrors adapter state to the app and keeps harmless time
 * sync behaviour. It must not call connectDirectly(), requestConnectToMac(),
 * createBondBluetoothJieLi(), or schedule reconnect retries.
 */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> handleAdapterState(intent)
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED -> runCatching {
                LargeDataHandler.getInstance().syncTime { _, _ -> }
            }.onFailure {
                Log.w(TAG, "Time sync after system clock change failed", it)
            }
            else -> Unit
        }
    }

    private fun handleAdapterState(intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
            BluetoothAdapter.STATE_OFF -> {
                BleOperateManager.getInstance().setBluetoothTurnOff(false)
                Log.i(TAG, "Bluetooth is off; autopair disabled")
                disconnectIfConnected()
                EventBus.getDefault().post(BluetoothEvent(false))
            }
            BluetoothAdapter.STATE_ON -> {
                BleOperateManager.getInstance().setBluetoothTurnOff(true)
                Log.i(TAG, "Bluetooth is on; autopair disabled")
                EventBus.getDefault().post(BluetoothEvent(false))
            }
        }
    }

    private fun disconnectIfConnected() {
        runCatching {
            if (BleOperateManager.getInstance().isConnected) {
                BleOperateManager.getInstance().disconnect()
            }
        }.onFailure {
            Log.w(TAG, "Disconnect after Bluetooth off failed", it)
        }
    }

    companion object {
        private const val TAG = "BluetoothReceiver"
    }
}
