package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import org.greenrobot.eventbus.EventBus

/**
 * @author hzy ,
 * @date 2020/8/3,
 *
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val connectState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (connectState == BluetoothAdapter.STATE_OFF) {
                    Log.i("qc", "Bluetooth is off --> ")
                    BleOperateManager.getInstance().setBluetoothTurnOff(false)
                    BleOperateManager.getInstance().disconnect()
                    EventBus.getDefault().post(BluetoothEvent(false))
                } else if (connectState == BluetoothAdapter.STATE_ON) {
                    Log.i("qc", "Bluetooth is on --> ")
                    BleOperateManager.getInstance().setBluetoothTurnOff(true)

                    // Route through AutoPairManager so user-initiated disconnect suppression is respected.
                    AutoPairManager.requestConnect(context, reason = "bt_state_on")
                    HeyCyanClassicBtManager.startDiscoveryForBoundDevice(context, reason = "bt_state_on")
                }
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    HeyCyanClassicBtManager.handleBondStateChanged(context, device)
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // If the phone connects to the glasses over classic BT (audio),
                // opportunistically (re)connect the BLE control channel too.
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val saved = HeyCyanDeviceStateStore.getBoundDeviceAddress(context)
                        ?: DeviceManager.getInstance().deviceAddress
                    val name = try { device.name } catch (_: SecurityException) { null }
                    val looksLikeGlasses = name?.contains("HeyCyan", ignoreCase = true) == true ||
                        name?.contains("Cyan", ignoreCase = true) == true ||
                        name?.startsWith("O_") == true ||
                        name?.startsWith("Q_") == true

                    if (!saved.isNullOrBlank() && saved.equals(device.address, ignoreCase = true)) {
                        HeyCyanDeviceStateStore.bind(context, device.address, name, classicAddress = device.address)
                        AutoPairManager.requestConnectToMac(context, device.address, reason = "acl_connected_saved")
                    } else if (looksLikeGlasses) {
                        HeyCyanDeviceStateStore.bind(context, device.address, name, classicAddress = device.address)
                        AutoPairManager.requestConnectToMac(context, device.address, reason = "acl_connected_name")
                    }
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // BLE reconnect loop will handle this; just trigger an immediate attempt.
                AutoPairManager.requestConnect(context, reason = "acl_disconnected")
            }

            BluetoothDevice.ACTION_FOUND -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    HeyCyanClassicBtManager.handleFoundDevice(context, device)
                }
            }
        }
    }
}
