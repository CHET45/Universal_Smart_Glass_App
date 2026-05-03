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
 * Minimal UserConfig-compatible state store for the HeyCyan/Oudmon connection path.
 *
 * The vendor app keeps this state in UserConfig and uses it for reconnect, feature gates,
 * model/version screens, volume, battery and Wi-Fi P2P workflows. Keeping it separate from the
 * protocol-neutral DeviceProfileStore avoids losing HeyCyan SDK state during generic pairing.
 */
object HeyCyanDeviceStateStore {

    private const val TAG = "HeyCyanStateStore"
    private const val PREFS = "heycyan_device_state"

    private const val KEY_BOUND = "bound"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CLASSIC_ADDRESS = "classic_address"
    private const val KEY_HARDWARE_VERSION = "hardware_version"
    private const val KEY_FIRMWARE_VERSION = "firmware_version"
    private const val KEY_WIFI_HARDWARE_VERSION = "wifi_hardware_version"
    private const val KEY_WIFI_FIRMWARE_VERSION = "wifi_firmware_version"
    private const val KEY_BATTERY = "battery"
    private const val KEY_CHARGING = "charging"
    private const val KEY_VOLUME = "volume"
    private const val KEY_MODEL = "model"

    private val BOOLEAN_FEATURE_KEYS = listOf(
        "wearCheckSupport",
        "classicBluetoothSupport",
        "volumeControlSupport",
        "lowBatterySupport",
        "batterySupport",
        "gyroSupport",
        "cameraSupport",
        "videoSupport",
        "recordSupport",
        "otaSupport",
        "glassesCurrWorkTypeSupport",
    )

    fun bind(context: Context, address: String, name: String? = null) {
        if (address.isBlank()) return
        prefs(context).edit()
            .putBoolean(KEY_BOUND, true)
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .apply()

        runCatching {
            DeviceManager.getInstance().deviceAddress = address
            if (!name.isNullOrBlank()) {
                DeviceManager.getInstance().deviceName = name
            }
        }.onFailure { Log.w(TAG, "Failed to mirror bind state into DeviceManager", it) }
    }

    fun bindFromBluetoothDevice(context: Context, device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        bind(context, device.address, name)
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

    fun getDeviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun saveDeviceInfo(context: Context, response: Any?) {
        if (response == null) return
        val editor = prefs(context).edit()
        response.readString("hardwareVersion")?.let { editor.putString(KEY_HARDWARE_VERSION, it) }
        response.readString("firmwareVersion")?.let { editor.putString(KEY_FIRMWARE_VERSION, it) }
        response.readString("wifiHardwareVersion")?.let { editor.putString(KEY_WIFI_HARDWARE_VERSION, it) }
        response.readString("wifiFirmwareVersion")?.let { editor.putString(KEY_WIFI_FIRMWARE_VERSION, it) }
        response.readString("classicBluetoothMac")
            ?: response.readString("classicBtMac")
            ?: response.readString("btMac")
            ?: response.readString("edrMac")
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
        editor.apply()
    }

    fun saveVolume(context: Context, response: Any?) {
        val volume = response.readInt("volume")
            ?: response.readInt("currVolume")
            ?: response.readInt("currentVolume")
        if (volume != null) {
            prefs(context).edit().putInt(KEY_VOLUME, volume).apply()
        }
    }

    fun snapshot(context: Context): Map<String, Any?> {
        val p = prefs(context)
        return buildMap {
            put(KEY_BOUND, p.getBoolean(KEY_BOUND, false))
            put(KEY_DEVICE_ADDRESS, p.getString(KEY_DEVICE_ADDRESS, null))
            put(KEY_DEVICE_NAME, p.getString(KEY_DEVICE_NAME, null))
            put(KEY_CLASSIC_ADDRESS, p.getString(KEY_CLASSIC_ADDRESS, null))
            put(KEY_HARDWARE_VERSION, p.getString(KEY_HARDWARE_VERSION, null))
            put(KEY_FIRMWARE_VERSION, p.getString(KEY_FIRMWARE_VERSION, null))
            put(KEY_WIFI_HARDWARE_VERSION, p.getString(KEY_WIFI_HARDWARE_VERSION, null))
            put(KEY_WIFI_FIRMWARE_VERSION, p.getString(KEY_WIFI_FIRMWARE_VERSION, null))
            put(KEY_BATTERY, if (p.contains(KEY_BATTERY)) p.getInt(KEY_BATTERY, -1) else null)
            put(KEY_CHARGING, if (p.contains(KEY_CHARGING)) p.getBoolean(KEY_CHARGING, false) else null)
            put(KEY_VOLUME, if (p.contains(KEY_VOLUME)) p.getInt(KEY_VOLUME, -1) else null)
            BOOLEAN_FEATURE_KEYS.forEach { key ->
                put(key, if (p.contains(key)) p.getBoolean(key, false) else null)
            }
        }
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
            is String -> value.lowercase(Locale.US).toBooleanStrictOrNull()
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
