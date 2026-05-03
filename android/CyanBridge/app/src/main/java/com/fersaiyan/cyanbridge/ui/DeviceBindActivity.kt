package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.recyclerview.widget.LinearLayoutManager
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.databinding.ActivityDeviceBindBinding
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfile
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.fersaiyan.cyanbridge.protocol.AppGlassesProtocolManager
import com.fersaiyan.cyanbridge.protocol.GlassesDevice
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.xiasuhuei321.loadingdialog.view.LoadingDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private lateinit var binding: ActivityDeviceBindBinding
    private lateinit var adapter: DeviceListAdapter
    private lateinit var glassesProtocolManager: AppGlassesProtocolManager
    private var scanSize: Int = 0
    private val runnable = MyRunnable()

    private lateinit var loadingDialog: LoadingDialog
    private val myHandler: Handler = object : Handler(Looper.getMainLooper()) {}

    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback: BleCallback = BleCallback()
    private var preserveProtocolAfterSuccessfulConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
        glassesProtocolManager = AppGlassesProtocolManager(this)
        EventBus.getDefault().register(this)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        requestLocationPermission(this, PermissionCallback())
        binding.startScan.performClick()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(messageEvent: BluetoothEvent) {
        Log.i(TAG, "onMessageEvent: " + messageEvent.connect)
        if (messageEvent.connect) {
            if (::loadingDialog.isInitialized) loadingDialog.close()
            finish()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setupViews() {
        super.setupViews()

        adapter = DeviceListAdapter(this, deviceList)
        binding.run {
            deviceRcv.layoutManager = LinearLayoutManager(this@DeviceBindActivity)
            deviceRcv.adapter = adapter
            titleBar.tvTitle.text = getString(R.string.text_1)
            titleBar.ivNavigateBefore.setOnClickListener { finish() }
        }

        adapter.setOnItemClickListener { _, _, position ->
            val device = deviceList.getOrNull(position) ?: return@setOnItemClickListener
            showDeviceTypePickerAndConnect(device)
        }

        setOnClickListener(binding.startScan) {
            if (!hasBluetooth(this@DeviceBindActivity)) {
                requestBluetoothPermission(this@DeviceBindActivity, object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) binding.startScan.performClick()
                    }
                })
                return@setOnClickListener
            }

            if (!hasLocationPermission(this@DeviceBindActivity)) {
                requestLocationPermission(this@DeviceBindActivity, object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) binding.startScan.performClick()
                    }
                })
                return@setOnClickListener
            }

            runCatching {
                AutoPairManager.setAutoReconnectSuppressed(true, reason = "manual_scan")
                deviceList.clear()
                adapter.notifyDataSetChanged()

                BleScannerHelper.getInstance().reSetCallback()

                if (!BluetoothUtils.isEnabledBluetooth(this@DeviceBindActivity)) {
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 300)
                } else {
                    scanSize = 0
                    BleScannerHelper.getInstance().scanDevice(this@DeviceBindActivity, null, bleScanCallback)
                    myHandler.removeCallbacks(runnable)
                    myHandler.postDelayed(runnable, 15 * 1000)
                }
            }.onFailure { error ->
                AutoPairManager.setAutoReconnectSuppressed(false, reason = "manual_scan_failed")
                Log.e(TAG, "Start BLE scan failed", error)
                Toast.makeText(
                    this@DeviceBindActivity,
                    "Scan failed: ${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDeviceTypePickerAndConnect(device: ScannedDevice) {
        val classes = listOf(
            DeviceClass.META_RAYBAN,
            DeviceClass.GENERIC_AUDIO,
            DeviceClass.HEY_CYAN,
            DeviceClass.EYEVUE_S2,
            DeviceClass.HSC_H5_15,
        )
        val labels = classes.map { it.displayName() }.toTypedArray()
        var selectedIndex = classes.indexOf(device.detectedClass).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select glasses type")
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("Connect") { dialog, _ ->
                dialog.dismiss()
                connectSelectedDevice(device, classes.getOrNull(selectedIndex) ?: device.detectedClass)
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun connectSelectedDevice(device: ScannedDevice, selected: DeviceClass) {
        myHandler.removeCallbacks(runnable)
        BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")
        device.userSelectedClass = selected

        val profile = DeviceProfile(
            macAddress = device.macAddress,
            advertisedName = device.advertisedName,
            detectedClass = device.detectedClass,
            selectedClass = selected,
            userOverridden = true,
        )
        DeviceProfileStore.saveLastSelected(this@DeviceBindActivity, profile)

        if (selected == DeviceClass.HEY_CYAN) {
            HeyCyanDeviceStateStore.bind(this, device.macAddress, device.advertisedName)
            HeyCyanDeviceStateStore.rememberScanBackup(this, device.macAddress, device.advertisedName)
        }

        val protocol = glassesProtocolManager.currentOrCreate(selected)
        loadingDialog = LoadingDialog(this@DeviceBindActivity)
        loadingDialog.setLoadingText(getString(R.string.text_22)).show()

        if (protocol == null) {
            Log.w(TAG, "No protocol implementation for selected class: $selected")
            BleOperateManager.getInstance().connectDirectly(device.macAddress)
            return
        }

        val protocolDevice = GlassesDevice(
            address = device.macAddress,
            name = device.advertisedName,
            rssi = device.rssi,
            serviceUuids = device.serviceUuids.map { it.uuid.toString() },
            protocolHint = protocol.id,
        )

        CoroutineScope(Dispatchers.Main).launch {
            runCatching { protocol.connect(protocolDevice) }
                .onSuccess {
                    preserveProtocolAfterSuccessfulConnect = true
                    // Do not finish here. connectDirectly only starts the BLE request; the app must wait for
                    // MyBluetoothReceiver.onServiceDiscovered/BluetoothEvent(true), otherwise initialization is skipped.
                }
                .onFailure { error ->
                    Log.e(TAG, "Protocol connect failed", error)
                    if (::loadingDialog.isInitialized) loadingDialog.close()
                    Toast.makeText(
                        this@DeviceBindActivity,
                        "Connect failed: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun upsertDevice(
        mac: String,
        name: String?,
        rssi: Int,
        scanRecord: ScanRecord? = null,
        rawScanRecord: ByteArray? = null,
    ) {
        val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val effectiveRawScanRecord = rawScanRecord?.takeIf { it.isNotEmpty() }
        val srUuids = scanRecord?.serviceUuids.orEmpty()
        val existingIndex = deviceList.indexOfFirst { it.macAddress.equals(mac, ignoreCase = true) }

        if (sanitizedName != null) {
            HeyCyanDeviceStateStore.rememberScanBackup(this, mac, sanitizedName)
        }

        if (existingIndex >= 0) {
            val existing = deviceList[existingIndex]
            existing.rssi = rssi
            if (existing.advertisedName.isNullOrBlank() && sanitizedName != null) {
                existing.advertisedName = sanitizedName
            }
            val uuids = scanRecord?.serviceUuids
            if (!uuids.isNullOrEmpty()) existing.serviceUuids = uuids
            if (effectiveRawScanRecord != null) existing.rawScanRecord = effectiveRawScanRecord
            existing.setDetectedClass(
                DeviceClassifier.guessDeviceClass(existing.advertisedName, existing.serviceUuids, existing.rawScanRecord)
            )
            adapter.notifyItemChanged(existingIndex)
            return
        }
        if (sanitizedName == null && DeviceClassifier.guessDeviceClass(null, srUuids, effectiveRawScanRecord) == DeviceClass.UNKNOWN) {
            return
        }

        val newDevice = ScannedDevice(
            macAddress = mac,
            advertisedName = sanitizedName,
            rssi = rssi,
            serviceUuids = srUuids,
            rawScanRecord = effectiveRawScanRecord ?: ByteArray(0),
        )

        val savedOverride = DeviceProfileStore.getUserOverrideForMac(this, mac)
        if (savedOverride != null && savedOverride != newDevice.detectedClass) {
            newDevice.userSelectedClass = savedOverride
        }

        scanSize++
        deviceList.add(newDevice)
        adapter.notifyItemInserted(deviceList.size - 1)

        if (scanSize > 30) BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
    }

    inner class MyRunnable : Runnable {
        override fun run() {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
            AutoPairManager.setAutoReconnectSuppressed(false, reason = "manual_scan_timeout")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        myHandler.removeCallbacks(runnable)
        if (!preserveProtocolAfterSuccessfulConnect) {
            glassesProtocolManager.close()
            AutoPairManager.setAutoReconnectSuppressed(false, reason = "bind_destroy_no_connection")
        }
        EventBus.getDefault().unregister(this)
    }

    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) = Unit

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) XXPermissions.startPermissionActivity(this@DeviceBindActivity, permissions)
        }
    }

    inner class BleCallback : ScanWrapperCallback {
        override fun onStart() {}
        override fun onStop() {}

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val addr = device?.address ?: return
            val name = try { device.name } catch (_: SecurityException) { null }
            upsertDevice(addr, name, rssi, rawScanRecord = scanRecord)
        }

        override fun onScanFailed(errorCode: Int) {
            AutoPairManager.setAutoReconnectSuppressed(false, reason = "scan_failed")
            Log.w(TAG, "Scan failed: $errorCode")
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            val addr = device?.address ?: return
            val name = try { scanRecord?.deviceName ?: device.name } catch (_: SecurityException) { scanRecord?.deviceName }
            val rssi = deviceList.firstOrNull { it.macAddress.equals(addr, true) }?.rssi ?: 0
            upsertDevice(addr, name, rssi, scanRecord)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
    }
}
