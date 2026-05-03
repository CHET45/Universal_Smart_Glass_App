package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager

/**
 * Classic Bluetooth pairing helper for the HeyCyan audio channel.
 *
 * The archive pairs the classic BT side after BLE binding so microphone/audio functions and
 * headset routing survive app restarts. This helper keeps that behavior isolated from BLE connect.
 */
object HeyCyanClassicBtManager {
    private const val TAG = "HeyCyanClassicBtManager"

    fun startDiscoveryForBoundDevice(context: Context, reason: String = "") {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!hasConnectPermission(context)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT, skip classic BT discovery. reason=$reason")
            return
        }
        if (!hasScanPermission(context)) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN, skip classic BT discovery. reason=$reason")
            return
        }

        val classicAddress = HeyCyanDeviceStateStore.getClassicBluetoothAddress(context)
            ?: HeyCyanDeviceStateStore.getBoundDeviceAddress(context)
            ?: return

        val alreadyBonded = runCatching {
            adapter.bondedDevices.firstOrNull { it.address.equals(classicAddress, ignoreCase = true) }
        }.getOrNull()

        if (alreadyBonded != null) {
            Log.i(TAG, "Classic BT device already bonded: $classicAddress")
            return
        }

        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
            Log.i(TAG, "Started classic BT discovery for $classicAddress reason=$reason")
        }.onFailure { Log.w(TAG, "Failed to start classic BT discovery", it) }
    }

    fun handleFoundDevice(context: Context, device: BluetoothDevice) {
        val target = HeyCyanDeviceStateStore.getClassicBluetoothAddress(context)
            ?: HeyCyanDeviceStateStore.getBoundDeviceAddress(context)
            ?: return
        val name = runCatching { device.name }.getOrNull()
        val addressMatches = device.address.equals(target, ignoreCase = true)
        val nameMatches = isLikelyHeyCyanName(name)
        if (!addressMatches && !nameMatches) return

        HeyCyanDeviceStateStore.bind(context, device.address, name, classicAddress = device.address)

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            runCatching {
                BleOperateManager.getInstance().createBondBluetoothJieLi(device)
            }.onFailure { Log.w(TAG, "Failed to create classic BT bond", it) }
        }
    }

    fun handleBondStateChanged(context: Context, device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        if (device.bondState == BluetoothDevice.BOND_BONDED && isLikelyHeyCyanName(name)) {
            HeyCyanDeviceStateStore.bind(context, device.address, name, classicAddress = device.address)
        }
    }

    private fun isLikelyHeyCyanName(name: String?): Boolean {
        val safe = name?.trim().orEmpty()
        val lower = safe.lowercase()
        return lower.contains("heycyan") || lower.contains("cyan") || safe.startsWith("O_") || safe.startsWith("Q_")
    }

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
}
