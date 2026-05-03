package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import org.greenrobot.eventbus.EventBus

/**
 * Bridges Oudmon BLE callbacks into the app and runs the HeyCyan post-discovery init sequence.
 */
class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {

    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.e("connectStatue", "---connectStatue connected=$connected")
        if (device != null && connected) {
            val name = runCatching { device.name }.getOrNull()
            if (!name.isNullOrBlank()) {
                DeviceManager.getInstance().deviceName = name
            }
            AutoPairManager.onConnected(MyApplication.CONTEXT, device)
        } else {
            EventBus.getDefault().post(BluetoothEvent(false))
            AutoPairManager.onDisconnected(MyApplication.CONTEXT, reason = "connect_status_false")
        }
    }

    override fun onServiceDiscovered() {
        HeyCyanDeviceInitializer.init(MyApplication.CONTEXT)
        EventBus.getDefault().post(BluetoothEvent(true))
        Log.e("onServiceDiscovered", "---onServiceDiscovered")
        BleOperateManager.getInstance().isReady = true
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        if (data != null) {
            bleIpBridge.onCharacteristicChanged("notify:$uuid", data)
        }
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            val version = String(data, Charsets.UTF_8)
            when (uuid) {
                Constants.CHAR_FIRMWARE_REVISION.toString() -> {
                    Log.e("rom----", version)
                    MyApplication.getInstance().firmwareVersion = version
                }
                Constants.CHAR_HW_REVISION.toString() -> {
                    Log.e("hardware----", version)
                    MyApplication.getInstance().hardwareVersion = version
                }
                else -> {
                    bleIpBridge.onCharacteristicChanged("read:$uuid", data)
                }
            }
        }
    }
}
