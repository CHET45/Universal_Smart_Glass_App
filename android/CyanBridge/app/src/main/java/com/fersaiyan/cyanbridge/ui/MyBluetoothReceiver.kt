package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fersaiyan.cyanbridge.protocol.heycyan.HeyCyanDeviceStateStore
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * Callback receiver for the Oudmon BLE stack.
 *
 * The important vendor behavior is: do not report connection success until services are discovered,
 * initialize LargeDataHandler at that point, then run the post-discovery init commands and start
 * the classic-BT discovery/pairing path.
 */
class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.i(TAG, "connectStatue connected=$connected device=${device?.address}")
        if (device != null && connected) {
            HeyCyanDeviceStateStore.saveConnectedDevice(device)
            return
        }

        EventBus.getDefault().post(BluetoothEvent(false))
    }

    override fun onServiceDiscovered() {
        Log.i(TAG, "onServiceDiscovered")
        runCatching { LargeDataHandler.getInstance().initEnable() }
            .onFailure { Log.e(TAG, "LargeDataHandler.initEnable failed", it) }

        runCatching { BleOperateManager.getInstance().isReady = true }
        EventBus.getDefault().post(BluetoothEvent(true))

        mainHandler.postDelayed(
            {
                runCatching { BleOperateManager.getInstance().classicBluetoothStartScan() }
                    .onFailure { Log.w(TAG, "classicBluetoothStartScan failed", it) }
            },
            1_000L,
        )

        DeviceCmdInit.initDeviceSetting()
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        if (data == null) return

        // Keep the app's existing BLE-IP sniffing path, but do not let it replace the Oudmon parser.
        bleIpBridge.onCharacteristicChanged("notify:$uuid", data)
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid == null || data == null) return

        val version = runCatching { String(data, Charsets.UTF_8) }.getOrNull().orEmpty()
        when (uuid) {
            Constants.CHAR_FIRMWARE_REVISION.toString() -> {
                Log.i(TAG, "firmware=$version")
                MyApplication.getInstance().firmwareVersion = version
            }
            Constants.CHAR_HW_REVISION.toString() -> {
                Log.i(TAG, "hardware=$version")
                MyApplication.getInstance().hardwareVersion = version
            }
            else -> {
                bleIpBridge.onCharacteristicChanged("read:$uuid", data)
            }
        }
    }

    override fun bleStatus(status: Int, newState: Int) {
        super.bleStatus(status, newState)
        Log.d(TAG, "bleStatus status=$status newState=$newState")
    }

    companion object {
        private const val TAG = "MyBluetoothReceiver"
    }
}
