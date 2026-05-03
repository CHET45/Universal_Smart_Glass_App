package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.file.FileHandle

/**
 * Mirrors the official HeyCyan DeviceCmdInit flow after GATT service discovery.
 *
 * The order matters: initEnable -> sync time/info/battery/features -> feature-dependent
 * work-type/gyro/volume sync. These calls warm the Oudmon large-data state before UI commands
 * such as photo, video, media count and P2P transfer are issued.
 */
object HeyCyanDeviceInitializer {
    private const val TAG = "HeyCyanDeviceInitializer"

    fun init(context: Context) {
        val appContext = context.applicationContext
        val handler = LargeDataHandler.getInstance()

        runCatching { handler.initEnable() }
            .onFailure { Log.w(TAG, "initEnable failed", it) }

        syncTime(handler)
        syncDeviceInfo(appContext, handler)
        syncBattery(appContext, handler)
        syncFeatureSupport(appContext, handler)
        syncVolume(appContext, handler)
        syncMediaCounts(handler)
    }

    private fun syncTime(handler: LargeDataHandler) {
        runCatching {
            handler.syncTime { cmdType, response ->
                Log.d(TAG, "syncTime callback cmdType=$cmdType response=$response")
            }
        }.onFailure { Log.w(TAG, "syncTime failed", it) }
    }

    private fun syncDeviceInfo(context: Context, handler: LargeDataHandler) {
        runCatching {
            handler.syncDeviceInfo { cmdType, response ->
                Log.d(TAG, "syncDeviceInfo callback cmdType=$cmdType response=$response")
                HeyCyanDeviceStateStore.saveDeviceInfo(context, response)

                val hardware = response?.readStringCompat("hardwareVersion")
                val firmware = response?.readStringCompat("firmwareVersion")
                if (hardware != null) MyApplication.getInstance().hardwareVersion = hardware
                if (firmware != null) MyApplication.getInstance().firmwareVersion = firmware
            }
        }.onFailure { Log.w(TAG, "syncDeviceInfo failed", it) }
    }

    private fun syncBattery(context: Context, handler: LargeDataHandler) {
        runCatching {
            handler.addBatteryCallBack("device_init") { cmdType, response ->
                Log.d(TAG, "battery callback cmdType=$cmdType response=$response")
                HeyCyanDeviceStateStore.saveBattery(context, response)
            }
            handler.syncBattery()
        }.onFailure { Log.w(TAG, "syncBattery failed", it) }
    }

    private fun syncFeatureSupport(context: Context, handler: LargeDataHandler) {
        val callback: (Int, Any?) -> Unit = { cmdType, response ->
            Log.d(TAG, "feature support callback cmdType=$cmdType response=$response")
            HeyCyanDeviceStateStore.saveFeatureSupport(context, response)
            syncWorkType(handler)
            syncGyro(handler)
        }

        val invoked = runCatching {
            invokeFirstAvailable(handler, FEATURE_SUPPORT_METHOD_NAMES, callback)
        }.getOrDefault(false)

        if (!invoked) {
            Log.w(TAG, "No compatible wear/function support method found in LargeDataHandler")
        }
    }

    private fun syncVolume(context: Context, handler: LargeDataHandler) {
        val callback: (Int, Any?) -> Unit = { cmdType, response ->
            Log.d(TAG, "volume callback cmdType=$cmdType response=$response")
            HeyCyanDeviceStateStore.saveVolume(context, response)
        }

        val invoked = runCatching {
            invokeFirstAvailable(handler, VOLUME_METHOD_NAMES, callback)
        }.getOrDefault(false)

        if (!invoked) {
            Log.d(TAG, "No compatible volume sync method found in LargeDataHandler")
        }
    }

    private fun syncWorkType(handler: LargeDataHandler) {
        runCatching {
            handler.glassesControl(byteArrayOf(0x02, 0x09)) { cmdType, response ->
                Log.d(TAG, "workType callback cmdType=$cmdType dataType=${response.dataType} error=${response.errorCode}")
            }
        }.onFailure { Log.d(TAG, "syncWorkType failed", it) }
    }

    private fun syncGyro(handler: LargeDataHandler) {
        runCatching {
            handler.glassesControl(byteArrayOf(0x02, 0x0A)) { cmdType, response ->
                Log.d(TAG, "gyro callback cmdType=$cmdType dataType=${response.dataType} error=${response.errorCode}")
            }
        }.onFailure { Log.d(TAG, "syncGyro failed", it) }
    }

    private fun syncMediaCounts(handler: LargeDataHandler) {
        runCatching {
            FileHandle.getInstance().clear()
        }
        runCatching {
            handler.glassesControl(byteArrayOf(0x02, 0x04)) { cmdType, response ->
                Log.d(
                    TAG,
                    "media count callback cmdType=$cmdType dataType=${response.dataType} images=${response.imageCount} videos=${response.videoCount} records=${response.recordCount}",
                )
            }
        }.onFailure { Log.d(TAG, "syncMediaCounts failed", it) }
    }

    private fun invokeFirstAvailable(
        handler: LargeDataHandler,
        names: List<String>,
        callback: (Int, Any?) -> Unit,
    ): Boolean {
        for (name in names) {
            val method = handler.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1
            } ?: continue

            return runCatching {
                method.invoke(handler, callback)
                true
            }.getOrElse { error ->
                Log.d(TAG, "Invocation of $name failed", error)
                false
            }
        }
        return false
    }

    private fun Any.readStringCompat(name: String): String? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        val methodNames = listOf(name, "get$capitalized")
        methodNames.forEach { methodName ->
            val method = javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                return runCatching { method.invoke(this)?.toString() }.getOrNull()
            }
        }

        return runCatching {
            val field = javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(this)?.toString()
        }.getOrNull()
    }

    private val FEATURE_SUPPORT_METHOD_NAMES = listOf(
        "wearFunctionSupport",
        "getWearFunctionSupport",
        "syncWearFunctionSupport",
        "syncFunctionSupport",
        "getFunctionSupport",
    )

    private val VOLUME_METHOD_NAMES = listOf(
        "getVolumeControl",
        "syncVolumeControl",
        "getVolume",
        "syncVolume",
    )
}
