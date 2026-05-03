package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.oudmon.ble.base.bluetooth.DeviceManager
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

/**
 * UserConfig-compatible state used by the HeyCyan/Oudmon path.
 *
 * The official app persists more than the BLE MAC: it also keeps scan backup data, the classic
 * Bluetooth MAC, Wi-Fi P2P credentials, feature flags, battery, version and volume state. The
 * protocol-neutral DeviceProfileStore is still useful for product classification, but it is not
 * enough for the HeyCyan SDK reconnect/media flow.
 */
object HeyCyanDeviceStateStore {

    private const val TAG = "HeyCyanStateStore"
    private const val PREFS = "heycyan_device_state"

    private const val KEY_BOUND = "bound"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_ADDRESS_NO_CLEAR = "device_address_no_clear"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_NAME_NO_CLEAR = "device_name_no_clear"
    private const val KEY_DEVICE_ADDRESS_SCAN_BACKUP = "device_address_scan_backup"
    private const val KEY_DEVICE_NAME_SCAN_BACKUP = "device_name_scan_backup"
    private const val KEY_CLASSIC_ADDRESS = "classic_address"
    private const val KEY_WIFI_NAME = "glass_device_wifi_name"
    private const val KEY_WIFI_PASSWORD = "glass_device_wifi_password"
    private const val KEY_HARDWARE_VERSION = "hardware_version"
    private const val KEY_FIRMWARE_VERSION = "firmware_version"
    private const val KEY_WIFI_HARDWARE_VERSION = "wifi_hardware_version"
    private const val KEY_WIFI_FIRMWARE_VERSION = "wifi_firmware_version"
    private const val KEY_BATTERY = "battery"
    private const val KEY_CHARGING = "charging"
    private const val KEY_VOLUME = "volume"
    private const val KEY_VOLUME_CONTROL = "volume_control"
    private const val KEY_MODEL = "model"

    private val BOOLEAN_FEATURE_KEYS = listOf(
        "wearCheckSupport",
        "volumeControl",
        "translationSupport",
        "screenSupport",
        "lowBatterySupport",
        "batterySupport",
        "gyroSupport",
        "cameraSupport",
        "videoSupport",
        "recordSupport",
        "otaSupport",
        "earphone",
        "gimbalCamera",
        "rotationSupport",
        "supportAI",
        "supportVideoDirection",
        "supportVideoTimestamp",
        "supportOfflineVoice",
        "supportGptAnswer",
        "supportRTChat",
        "supportLiveReview",
        "supportResolution",
        "support1300W",
        "supportWaveGuide",
        "v881Support",
        "imageEnhancement",
        "glassesCurrWorkType",
    )

    fun rememberScanBackup(context: Context, address: String, name: String? = null) {
        if (address.isBlank()) return
        prefs(context).edit()
            .putString(KEY_DEVICE_ADDRESS_SCAN_BACKUP, address)
            .putString(KEY_DEVICE_NAME_SCAN_BACKUP, name.orEmpty())
            .apply()
    }

    fun bind(context: Context, address: String, name: String? = null, classicAddress: String? = null) {
        if (address.isBlank()) return
        val safeName = resolveName(context, name)
        val safeClassicAddress = classicAddress?.takeIf { it.isNotBlank() } ?: address
        prefs(context).edit()
            .putBoolean(KEY_BOUND, true)
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_ADDRESS_NO_CLEAR, address)
            .putString(KEY_DEVICE_NAME, safeName)
            .putString(KEY_DEVICE_NAME_NO_CLEAR, safeName)
            .putString(KEY_CLASSIC_ADDRESS, safeClassicAddress)
            .putString(KEY_WIFI_NAME, buildWifiName(safeName, address))
            .putString(KEY_WIFI_PASSWORD, "123456789")
            .apply()

        runCatching {
            DeviceManager.getInstance().deviceAddress = address
            if (!safeName.isNullOrBlank()) {
                DeviceManager.getInstance().deviceName = safeName
            }
        }.onFailure { Log.w(TAG, "Failed to mirror bind state into DeviceManager", it) }
    }

    fun bindFromBluetoothDevice(context: Context, device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        bind(context, device.address, name, classicAddress = device.address)
    }

    fun clearBinding(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            DeviceManager.getInstance().deviceAddress = ""
            DeviceManager.getInstance().deviceName = ""
        }
    }

    fun getBoundDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun getClassicBluetoothAddress(context: Context): String? =
        prefs(context).getString(KEY_CLASSIC_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun getDeviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun getWifiName(context: Context): String? =
        prefs(context).getString(KEY_WIFI_NAME, null)?.takeIf { it.isNotBlank() }

    fun getWifiPassword(context: Context): String? =
        prefs(context).getString(KEY_WIFI_PASSWORD, null)?.takeIf { it.isNotBlank() }

    fun saveDeviceInfo(context: Context, response: Any?) {
        if (response == null) return
        val editor = prefs(context).edit()
        response.readString("hardwareVersion")?.let { editor.putString(KEY_HARDWARE_VERSION, it) }
        response.readString("firmwareVersion")?.let { editor.putString(KEY_FIRMWARE_VERSION, it) }
        response.readString("wifiHardwareVersion")?.let { editor.putString(KEY_WIFI_HARDWARE_VERSION, it) }
        response.readString("wifiFirmwareVersion")?.let { editor.putString(KEY_WIFI_FIRMWARE_VERSION, it) }
        (response.readString("classicBluetoothMac")
            ?: response.readString("classicBtMac")
            ?: response.readString("btMac")
            ?: response.readString("edrMac"))
            ?.let { editor.putString(KEY_CLASSIC_ADDRESS, it) }
        response.readString("model")?.let { editor.putString(KEY_MODEL, it) }
        editor.apply()
    }

    fun saveBattery(context: Context, response: Any?) {
        if (response == null) return
        val battery = response.readInt("battery") ?: response.readInt("electricity") ?: response.readInt("level")
        val charging = response.readBoolean("charging") ?: response.readBoolean("isCharging")
        prefs(context).edit().apply {
            if (battery != null) putInt(KEY_BATTERY, battery.coerceIn(0, 100))
            if (charging != null) putBoolean(KEY_CHARGING, charging)
        }.apply()
    }

    fun saveFeatureSupport(context: Context, response: Any?) {
        if (response == null) return
        val editor = prefs(context).edit()
        BOOLEAN_FEATURE_KEYS.forEach { key ->
            response.readBoolean(key)?.let { editor.putBoolean(key, it) }
        }
        response.readInt("model")?.let { editor.putInt(KEY_MODEL, it) }
        response.readInt("glassesModel")?.let { editor.putInt(KEY_MODEL, it) }
        response.readString("classicBluetoothMac")?.let { editor.putString(KEY_CLASSIC_ADDRESS, it) }
        editor.apply()
    }

    fun saveVolume(context: Context, response: Any?) {
        if (response == null) return
        val volume = response.readInt("volume")
            ?: response.readInt("currVolume")
            ?: response.readInt("currentVolume")
            ?: response.readInt("currVolumeMusic")

        val archiveVolumeControl = listOf(
            response.readInt("minVolumeMusic"),
            response.readInt("maxVolumeMusic"),
            response.readInt("currVolumeMusic"),
            response.readInt("minVolumeCall"),
            response.readInt("maxVolumeCall"),
            response.readInt("currVolumeCall"),
            response.readInt("minVolumeSystem"),
            response.readInt("maxVolumeSystem"),
            response.readInt("currVolumeSystem"),
            response.readInt("currVolumeType"),
        ).takeIf { values -> values.any { it != null } }
            ?.joinToString(",") { it?.toString().orEmpty() }

        prefs(context).edit().apply {
            if (volume != null) putInt(KEY_VOLUME, volume)
            if (archiveVolumeControl != null) putString(KEY_VOLUME_CONTROL, archiveVolumeControl)
        }.apply()
    }

    fun snapshot(context: Context): Map<String, Any?> {
        val p = prefs(context)
        val result = linkedMapOf<String, Any?>()
        result[KEY_BOUND] = p.getBoolean(KEY_BOUND, false)
        result[KEY_DEVICE_ADDRESS] = p.getString(KEY_DEVICE_ADDRESS, null)
        result[KEY_DEVICE_ADDRESS_NO_CLEAR] = p.getString(KEY_DEVICE_ADDRESS_NO_CLEAR, null)
        result[KEY_DEVICE_NAME] = p.getString(KEY_DEVICE_NAME, null)
        result[KEY_DEVICE_NAME_NO_CLEAR] = p.getString(KEY_DEVICE_NAME_NO_CLEAR, null)
        result[KEY_DEVICE_ADDRESS_SCAN_BACKUP] = p.getString(KEY_DEVICE_ADDRESS_SCAN_BACKUP, null)
        result[KEY_DEVICE_NAME_SCAN_BACKUP] = p.getString(KEY_DEVICE_NAME_SCAN_BACKUP, null)
        result[KEY_CLASSIC_ADDRESS] = p.getString(KEY_CLASSIC_ADDRESS, null)
        result[KEY_WIFI_NAME] = p.getString(KEY_WIFI_NAME, null)
        result[KEY_WIFI_PASSWORD] = p.getString(KEY_WIFI_PASSWORD, null)
        result[KEY_HARDWARE_VERSION] = p.getString(KEY_HARDWARE_VERSION, null)
        result[KEY_FIRMWARE_VERSION] = p.getString(KEY_FIRMWARE_VERSION, null)
        result[KEY_WIFI_HARDWARE_VERSION] = p.getString(KEY_WIFI_HARDWARE_VERSION, null)
        result[KEY_WIFI_FIRMWARE_VERSION] = p.getString(KEY_WIFI_FIRMWARE_VERSION, null)
        result[KEY_BATTERY] = if (p.contains(KEY_BATTERY)) p.getInt(KEY_BATTERY, -1) else null
        result[KEY_CHARGING] = if (p.contains(KEY_CHARGING)) p.getBoolean(KEY_CHARGING, false) else null
        result[KEY_VOLUME] = if (p.contains(KEY_VOLUME)) p.getInt(KEY_VOLUME, -1) else null
        result[KEY_VOLUME_CONTROL] = p.getString(KEY_VOLUME_CONTROL, null)
        BOOLEAN_FEATURE_KEYS.forEach { key ->
            result[key] = if (p.contains(key)) p.getBoolean(key, false) else null
        }
        return result
    }

    private fun resolveName(context: Context, name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs(context).getString(KEY_DEVICE_NAME_SCAN_BACKUP, null)?.takeIf { it.isNotBlank() }
            ?: prefs(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }

    private fun buildWifiName(name: String?, address: String): String {
        val macCompact = address.replace(":", "")
        val base = if (!name.isNullOrBlank() && name.contains("_")) {
            val parts = name.split("_").filter { it.isNotBlank() }
            if (parts.size > 2) parts.last() else parts.firstOrNull().orEmpty()
        } else {
            name.orEmpty()
        }.take(20).ifBlank { "HeyCyan" }
        return "${base}_${macCompact}"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Any?.readString(name: String): String? =
        this?.readPropertyValue(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun Any?.readInt(name: String): Int? {
        val value = this?.readPropertyValue(name) ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun Any?.readBoolean(name: String): Boolean? {
        val value = this?.readPropertyValue(name) ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.lowercase(Locale.US)) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun Any.readPropertyValue(name: String): Any? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        val methodNames = listOf(name, "get$capitalized", "is$capitalized")
        methodNames.forEach { methodName ->
            val method: Method? = javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }
            if (method != null) return runCatching { method.invoke(this) }.getOrNull()
        }

        val field: Field? = runCatching { javaClass.getDeclaredField(name) }.getOrNull()
            ?: javaClass.fields.firstOrNull { it.name == name }
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.get(this)
            }.getOrNull()
        }

        return null
    }
}
