package com.fersaiyan.cyanbridge.protocol.heycyan

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.oudmon.ble.base.bluetooth.DeviceManager

/**
 * Local replacement for the vendor app's UserConfig fields that are required by the
 * HeyCyan/Oudmon connection path.
 */
object HeyCyanDeviceStateStore {
    private const val PREFS = "heycyan_device_state"

    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_NAME_NO_CLEAR = "device_name_no_clear"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_ADDRESS_NO_CLEAR = "device_address_no_clear"
    private const val KEY_DEVICE_NAME_SCAN_BACKUP = "device_name_scan_backup"
    private const val KEY_DEVICE_ADDRESS_SCAN_BACKUP = "device_address_scan_backup"
    private const val KEY_DEVICE_BIND = "device_bind"
    private const val KEY_CLASSIC_BT_MAC = "classic_bluetooth_mac"
    private const val KEY_GLASS_WIFI_NAME = "glass_device_wifi_name"
    private const val KEY_GLASS_WIFI_PASSWORD = "glass_device_wifi_password"
    private const val KEY_UNIQUE_ID_HW = "unique_id_hw"
    private const val KEY_BATTERY = "battery"
    private const val KEY_CHARGING = "charging"
    private const val KEY_LOW_BATTERY = "low_battery"
    private const val KEY_HW_VERSION = "hardware_version"
    private const val KEY_FW_VERSION = "firmware_version"
    private const val KEY_WIFI_HW_VERSION = "wifi_hardware_version"
    private const val KEY_WIFI_FW_VERSION = "wifi_firmware_version"
    private const val KEY_VOLUME_CONTROL = "volume_control"
    private const val KEY_GLASSES_MODEL = "glasses_model"
    private const val KEY_GLASSES_MODEL_STRING = "glasses_model_string"
    private const val KEY_CAMERA_8MP = "camera_8mp"
    private const val KEY_OPEN_EIS = "open_eis"

    private fun prefs(context: Context = MyApplication.Companion.CONTEXT) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberScanBackup(
        name: String?,
        address: String,
    ) {
        prefs().edit()
            .putString(KEY_DEVICE_NAME_SCAN_BACKUP, name.orEmpty())
            .putString(KEY_DEVICE_ADDRESS_SCAN_BACKUP, address)
            .apply()
    }

    fun saveConnectedDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        val resolvedName = safeName(device)
            ?: deviceNameScanBackup.takeIf { it.isNotBlank() }
            ?: "HeyCyan"

        val previousNoClearAddress = deviceAddressNoClear
        val resetUniqueId = previousNoClearAddress.isNotBlank() &&
            !previousNoClearAddress.equals(address, ignoreCase = true)

        prefs().edit()
            .putBoolean(KEY_DEVICE_BIND, true)
            .putString(KEY_DEVICE_NAME, resolvedName)
            .putString(KEY_DEVICE_NAME_NO_CLEAR, resolvedName)
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_ADDRESS_NO_CLEAR, address)
            .putString(KEY_CLASSIC_BT_MAC, address)
            .putString(KEY_GLASS_WIFI_NAME, deriveWifiName(resolvedName, address))
            .putString(KEY_GLASS_WIFI_PASSWORD, "123456789")
            .apply()

        if (resetUniqueId) {
            prefs().edit().putString(KEY_UNIQUE_ID_HW, "").apply()
        }

        syncIntoSdkDeviceManager(resolvedName, address)
    }

    fun clearBindState() {
        prefs().edit()
            .putBoolean(KEY_DEVICE_BIND, false)
            .putString(KEY_DEVICE_ADDRESS, "")
            .apply()
        runCatching { DeviceManager.getInstance().deviceAddress = "" }
    }

    fun saveDeviceInfo(
        hardwareVersion: String?,
        firmwareVersion: String?,
        wifiHardwareVersion: String?,
        wifiFirmwareVersion: String?,
    ) {
        prefs().edit()
            .putString(KEY_HW_VERSION, hardwareVersion.orEmpty())
            .putString(KEY_FW_VERSION, firmwareVersion.orEmpty())
            .putString(KEY_WIFI_HW_VERSION, wifiHardwareVersion.orEmpty())
            .putString(KEY_WIFI_FW_VERSION, wifiFirmwareVersion.orEmpty())
            .apply()

        MyApplication.Companion.getInstance().hardwareVersion = hardwareVersion.orEmpty()
        MyApplication.Companion.getInstance().firmwareVersion = firmwareVersion.orEmpty()
    }

    fun saveBattery(percent: Int, charging: Boolean) {
        prefs().edit()
            .putInt(KEY_BATTERY, percent.coerceIn(0, 100))
            .putBoolean(KEY_CHARGING, charging)
            .putBoolean(KEY_LOW_BATTERY, percent in 0..15)
            .apply()
    }

    fun saveVolumeControl(value: String) {
        prefs().edit().putString(KEY_VOLUME_CONTROL, value).apply()
    }

    fun saveSupportFlag(name: String, value: Boolean) {
        prefs().edit().putBoolean("support_$name", value).apply()
    }

    fun saveSupportInt(name: String, value: Int) {
        prefs().edit().putInt("support_$name", value).apply()
    }

    fun saveOpenEis(value: Int) {
        prefs().edit().putInt(KEY_OPEN_EIS, value).apply()
    }

    fun getOpenEis(): Int = prefs().getInt(KEY_OPEN_EIS, 0)

    fun saveCamera8Mp(value: Boolean) {
        prefs().edit().putBoolean(KEY_CAMERA_8MP, value).apply()
    }

    val isCamera8Mp: Boolean
        get() = prefs().getBoolean(KEY_CAMERA_8MP, false)

    fun saveGlassesModel(model: Int) {
        val hex = "v0x" + model.toString(16).padStart(2, '0').lowercase()
        prefs().edit()
            .putInt(KEY_GLASSES_MODEL, model)
            .putString(KEY_GLASSES_MODEL_STRING, hex)
            .apply()
    }

    val deviceName: String
        get() = prefs().getString(KEY_DEVICE_NAME, null)
            ?: runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
            ?: ""

    val deviceAddress: String
        get() = prefs().getString(KEY_DEVICE_ADDRESS, null)
            ?: runCatching { DeviceManager.getInstance().deviceAddress }.getOrNull()
            ?: ""

    val deviceAddressNoClear: String
        get() = prefs().getString(KEY_DEVICE_ADDRESS_NO_CLEAR, "").orEmpty()

    val deviceNameScanBackup: String
        get() = prefs().getString(KEY_DEVICE_NAME_SCAN_BACKUP, "").orEmpty()

    val deviceAddressScanBackup: String
        get() = prefs().getString(KEY_DEVICE_ADDRESS_SCAN_BACKUP, "").orEmpty()

    val classicBluetoothMac: String
        get() = prefs().getString(KEY_CLASSIC_BT_MAC, null) ?: deviceAddress

    val glassDeviceWifiName: String
        get() = prefs().getString(KEY_GLASS_WIFI_NAME, "").orEmpty()

    val glassDeviceWifiPassword: String
        get() = prefs().getString(KEY_GLASS_WIFI_PASSWORD, "123456789").orEmpty()

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: SecurityException) {
        null
    }

    private fun deriveWifiName(deviceName: String, address: String): String {
        val addressNoColon = address.replace(":", "")
        val base = if ("_" in deviceName) {
            val parts = deviceName.split("_")
            if (parts.size > 2) parts.last() else parts.first()
        } else {
            deviceName
        }.take(20)

        return "${base}_$addressNoColon"
    }

    private fun syncIntoSdkDeviceManager(name: String, address: String) {
        val manager = DeviceManager.getInstance()
        runCatching { manager.deviceName = name }
        runCatching { manager.deviceAddress = address }
        setOptionalString(manager, "setDeviceNameNoClear", name)
        setOptionalString(manager, "setDeviceAddressNoClear", address)
        setOptionalString(manager, "setClassicBluetoothMac", address)
    }

    private fun setOptionalString(target: Any, method: String, value: String) {
        runCatching {
            target.javaClass.methods.firstOrNull { m ->
                m.name == method && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
            }?.invoke(target, value)
        }
    }
}