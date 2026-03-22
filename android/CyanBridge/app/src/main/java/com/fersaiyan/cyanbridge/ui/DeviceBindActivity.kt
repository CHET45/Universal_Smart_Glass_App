package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.databinding.ActivityDeviceBindBinding
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfile
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.xiasuhuei321.loadingdialog.view.LoadingDialog
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private lateinit var binding: ActivityDeviceBindBinding
    private lateinit var adapter: DeviceListAdapter
    private var scanSize: Int = 0
    private val runnable = MyRunnable()

    private lateinit var loadingDialog: LoadingDialog
    private val myHandler: Handler = object : Handler(Looper.getMainLooper()) {}

    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback: BleCallback = BleCallback()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
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
            if (::loadingDialog.isInitialized) {
                loadingDialog.close()
            }
            finish()
        }
    }

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

            // Always force an explicit device-type confirmation before connecting.
            showDeviceTypePickerAndConnect(device)
        }

        setOnClickListener(binding.startScan) {
            deviceList.clear()
            adapter.notifyDataSetChanged()

            BleScannerHelper.getInstance().reSetCallback()
            if (!BluetoothUtils.isEnabledBluetooth(this@DeviceBindActivity)) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                activity!!.startActivityForResult(intent, 300)
            } else {
                scanSize = 0
                BleScannerHelper.getInstance().scanDevice(this@DeviceBindActivity, null, bleScanCallback)
                myHandler.removeCallbacks(runnable)
                myHandler.postDelayed(runnable, 15 * 1000)
            }
        }
    }

    private fun showDeviceTypePickerAndConnect(device: ScannedDevice) {
        val classes = listOf(
            DeviceClass.META_RAYBAN,
            DeviceClass.GENERIC_AUDIO,
            DeviceClass.HEY_CYAN,
        )
        val labels = classes.map { it.displayName() }.toTypedArray()

        var selectedIndex = classes.indexOf(device.detectedClass).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select glasses type")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Connect") { dialog, _ ->
                dialog.dismiss()

                // Stop scan timer and scanning to avoid extra UI churn while connecting.
                myHandler.removeCallbacks(runnable)
                BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)

                val selected = classes.getOrNull(selectedIndex) ?: device.detectedClass

                // User explicitly initiated pairing/reconnect.
                AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")

                // Force a user-confirmed selection (even if it matches detected).
                device.userSelectedClass = selected

                val profile = DeviceProfile(
                    macAddress = device.macAddress,
                    advertisedName = device.advertisedName,
                    detectedClass = device.detectedClass,
                    selectedClass = selected,
                    userOverridden = true,
                )
                DeviceProfileStore.saveLastSelected(this@DeviceBindActivity, profile)

                BleOperateManager.getInstance().connectDirectly(device.macAddress)

                loadingDialog = LoadingDialog(this@DeviceBindActivity)
                loadingDialog.setLoadingText(getString(R.string.text_22)).show()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun upsertDevice(
        mac: String,
        name: String?,
        rssi: Int,
        scanRecord: ScanRecord? = null
    ) {
        val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val existingIndex = deviceList.indexOfFirst { it.macAddress.equals(mac, ignoreCase = true) }

        if (existingIndex >= 0) {
            val existing = deviceList[existingIndex]
            existing.rssi = rssi

            // Update name if we got a better one.
            if (existing.advertisedName.isNullOrBlank() && sanitizedName != null) {
                existing.advertisedName = sanitizedName
            }

            val uuids = scanRecord?.serviceUuids
            if (!uuids.isNullOrEmpty()) {
                existing.serviceUuids = uuids
            }

            // Recompute detection if not overridden.
            val newDetected = DeviceClassifier.guessDeviceClass(existing.advertisedName, existing.serviceUuids)
            existing.setDetectedClass(newDetected)

            // Do NOT reorder the list during scanning.
            // Reordering causes the UI to "jump" and users can accidentally tap the wrong item.
            adapter.notifyItemChanged(existingIndex)
            return
        }

        // Ignore unnamed devices to keep the pairing list focused and readable.
        if (sanitizedName == null) {
            return
        }

        val srUuids = scanRecord?.serviceUuids.orEmpty()
        val newDevice = ScannedDevice(
            macAddress = mac,
            advertisedName = sanitizedName,
            rssi = rssi,
            serviceUuids = srUuids
        )

        // Apply any previously saved per-device override.
        val savedOverride = DeviceProfileStore.getUserOverrideForMac(this, mac)
        if (savedOverride != null && savedOverride != newDevice.detectedClass) {
            newDevice.userSelectedClass = savedOverride
        }

        scanSize++
        // Append new devices to keep existing item positions stable while the scan is running.
        deviceList.add(newDevice)
        adapter.notifyItemInserted(deviceList.size - 1)

        if (scanSize > 30) {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        }
    }

    inner class MyRunnable : Runnable {
        override fun run() {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            // Permissions handled at app level; scan is started in onResume.
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) {
                XXPermissions.startPermissionActivity(this@DeviceBindActivity, permissions)
            }
        }
    }

    inner class BleCallback : ScanWrapperCallback {
        override fun onStart() {}
        override fun onStop() {}

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val addr = device?.address ?: return

            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            }

            upsertDevice(addr, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            val addr = device?.address ?: return
            val name = try {
                scanRecord?.deviceName ?: device.name
            } catch (_: SecurityException) {
                scanRecord?.deviceName
            }

            // RSSI is not available here; keep existing RSSI if any.
            val rssi = deviceList.firstOrNull { it.macAddress.equals(addr, true) }?.rssi ?: 0
            upsertDevice(addr, name, rssi, scanRecord)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            // Not used by this scanner wrapper.
        }
    }
}
