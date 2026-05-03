package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fersaiyan.cyanbridge.protocol.heycyan.HeyCyanDeviceStateStore
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * System Bluetooth receiver aligned with the vendor reconnect/pairing path.
 */
class BluetoothReceiver : BroadcastReceiver() {
    private val handler = Handler(Looper.getMainLooper())
    private var bleOpen = true
    private var connectAttempt = 1
    private var classicDevice: BluetoothDevice? = null
    private var classicPairAttempt = 0
    private var lastContext: Context? = null

    private val uiRunnable = Runnable {
        if (!BleOperateManager.getInstance().isConnected) {
            beginConnect(0)
        }
    }

    private val classicBluetoothRunnable = object : Runnable {
        override fun run() {
            val device = classicDevice ?: return
            if (!device.address.equals(HeyCyanDeviceStateStore.classicBluetoothMac, ignoreCase = true)) return

            classicPairAttempt++
            runCatching { BleOperateManager.getInstance().createBondBluetoothJieLi(device) }
                .onFailure { Log.w(TAG, "classic BT bond retry failed", it) }

            if (classicPairAttempt < 3) {
                handler.postDelayed(this, 5_000L)
            }
        }
    }

    private val connectRunnable = Runnable {
        val mac = HeyCyanDeviceStateStore.deviceAddress
        val context = lastContext ?: MyApplication.CONTEXT
        if (mac.isNotBlank() && !BleOperateManager.getInstance().isConnected) {
            reconnect(context, mac, "receiver_retry_$connectAttempt")
            val next = connectAttempt + (connectAttempt / 10) + 1
            connectAttempt = next
            connectAgain(((next / 10) + 1) * 60_000L)
        } else {
            connectAttempt = 1
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        lastContext = context.applicationContext
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> handleAdapterState(context, intent)
            BluetoothDevice.ACTION_ACL_CONNECTED -> handleAclConnected(context, intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleAclDisconnected(context, intent)
            BluetoothDevice.ACTION_FOUND -> handleDeviceFound(intent)
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent)
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT -> {
                handler.removeCallbacks(uiRunnable)
                handler.postDelayed(uiRunnable, 1_000L)
                if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED) {
                    runCatching { LargeDataHandler.getInstance().syncTime { _, _ -> } }
                }
            }
        }
    }

    private fun handleAdapterState(context: Context, intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
            BluetoothAdapter.STATE_OFF -> {
                connectAttempt = 1
                bleOpen = false
                BleOperateManager.getInstance().setBluetoothTurnOff(false)
                Log.i(TAG, "Bluetooth is off")
                disconnectDeviceDelayed()
                EventBus.getDefault().post(BluetoothEvent(false))
            }
            BluetoothAdapter.STATE_ON -> {
                bleOpen = true
                BleOperateManager.getInstance().setBluetoothTurnOff(true)
                Log.i(TAG, "Bluetooth is on")
                val saved = HeyCyanDeviceStateStore.deviceAddress
                if (saved.isNotBlank()) {
                    runCatching { BleOperateManager.getInstance().setReConnectMac(saved) }
                    reconnect(context, saved, "bt_state_on")
                    beginConnect(2_000)
                    handler.removeCallbacks(uiRunnable)
                    handler.post(uiRunnable)
                }
            }
        }
    }

    private fun handleAclConnected(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val saved = HeyCyanDeviceStateStore.deviceAddress
        Log.i(TAG, "ACL connected ${device.address}")
        if (saved.isBlank() || !device.address.equals(saved, ignoreCase = true)) return

        connectAttempt = 1
        if (!BleOperateManager.getInstance().isConnected) {
            beginConnect(5_000)
        }
        reconnect(context, saved, "acl_connected")
    }

    private fun handleAclDisconnected(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val saved = HeyCyanDeviceStateStore.deviceAddress
        if (saved.isBlank() || !device.address.equals(saved, ignoreCase = true)) return

        Log.i(TAG, "ACL disconnected ${device.address}")
        connectAttempt = 1
        connectAgain(22_000)
        handler.post(uiRunnable)
        reconnect(context, saved, "acl_disconnected")
    }

    private fun handleDeviceFound(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val classicMac = HeyCyanDeviceStateStore.classicBluetoothMac
        if (classicMac.isBlank() || !device.address.equals(classicMac, ignoreCase = true)) return
        if (HeyCyanDeviceStateStore.deviceAddress.isBlank()) return

        classicDevice = device
        classicPairAttempt = 0
        handler.removeCallbacks(classicBluetoothRunnable)
        handler.postDelayed(classicBluetoothRunnable, 5_000L)
        runCatching { BleOperateManager.getInstance().createBondBluetoothJieLi(device) }
            .onFailure { Log.w(TAG, "classic BT bond failed", it) }
    }

    private fun handleBondStateChanged(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!device.address.equals(HeyCyanDeviceStateStore.classicBluetoothMac, ignoreCase = true)) return

        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "classic BT bonded ${device.address}")
                classicPairAttempt = 0
                handler.removeCallbacks(classicBluetoothRunnable)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.w(TAG, "classic BT bond failed ${device.address}")
                if (classicPairAttempt >= 2) {
                    handler.removeCallbacks(classicBluetoothRunnable)
                }
            }
        }
        handler.removeCallbacks(uiRunnable)
        handler.post(uiRunnable)
    }

    private fun beginConnect(delayMs: Long) {
        handler.removeCallbacks(connectRunnable)
        if (bleOpen) handler.postDelayed(connectRunnable, delayMs)
    }

    private fun connectAgain(delayMs: Long) {
        if (!bleOpen || connectAttempt > 20) return
        handler.postDelayed(connectRunnable, delayMs)
    }

    private fun disconnectDeviceDelayed() {
        handler.postDelayed(
            {
                if (BleOperateManager.getInstance().isConnected) {
                    BleOperateManager.getInstance().disconnect()
                }
            },
            1_500L,
        )
    }

    private fun reconnect(context: Context, mac: String, reason: String) {
        runCatching { BleOperateManager.getInstance().setReConnectMac(mac) }
        runCatching { AutoPairManager.requestConnectToMac(context, mac, reason = reason) }
            .onFailure {
                Log.w(TAG, "AutoPairManager reconnect failed; falling back to connectDirectly", it)
                runCatching { BleOperateManager.getInstance().connectDirectly(mac) }
            }
    }

    companion object {
        private const val TAG = "BluetoothReceiver"
    }
}
