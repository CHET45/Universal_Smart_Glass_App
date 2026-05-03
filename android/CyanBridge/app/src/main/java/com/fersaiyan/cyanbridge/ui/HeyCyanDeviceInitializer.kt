package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.file.FileHandle

/**
 * Mirrors the official HeyCyan DeviceCmdInit flow after GATT service discovery.
 */
object HeyCyanDeviceInitializer {
    private const val TAG = "HeyCyanDeviceInitializer"

    fun init(context: Context) {
        val appContext = context.applicationContext
        val handler = LargeDataHandler.getInstance()

        clearFileCallbacks()
        runCatching { handler.initEnable() }
            .onFailure { Log.w(TAG, "initEnable failed", it) }

        syncTime(handler)
        syncDeviceInfo(appContext, handler)
        syncDeviceSetting(appContext, handler)
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

    private fun syncDeviceSetting(context: Context, handler: LargeDataHandler) {
        runCatching {
            handler.addBatteryCallBack("device_init") { cmdType, response ->
                Log.d(TAG, "battery callback cmdType=$cmdType response=$response")
                HeyCyanDeviceStateStore.saveBattery(context, response)
            }
            handler.syncBattery()
        }.onFailure { Log.w(TAG, "syncBattery failed", it) }

        runCatching {
            handler.wearFunctionSupport { cmdType, response ->
                Log.d(TAG, "wearFunctionSupport callback cmdType=$cmdType response=$response")
                HeyCyanDeviceStateStore.saveFeatureSupport(context, response)
                getGlassesWorkType(handler)
            }
        }.onFailure { Log.w(TAG, "wearFunctionSupport failed", it) }

        runCatching {
            handler.getVolumeControl { cmdType, response ->
                Log.d(TAG, "volume callback cmdType=$cmdType response=$response")
                HeyCyanDeviceStateStore.saveVolume(context, response)
            }
        }.onFailure { Log.d(TAG, "getVolumeControl failed", it) }

        syncMediaCounts(handler)
    }

    /**
     * Archive DeviceCmdInit.getGlassesWorkType sends 01 0A; this is not the media-count command.
     */
    private fun getGlassesWorkType(handler: LargeDataHandler) {
        runCatching {
            handler.glassesControl(byteArrayOf(0x01, 0x0A)) { cmdType, response ->
                Log.d(TAG, "workType callback cmdType=$cmdType dataType=${response.dataType} error=${response.errorCode}")
            }
        }.onFailure { Log.d(TAG, "getGlassesWorkType failed", it) }
    }

    private fun syncMediaCounts(handler: LargeDataHandler) {
        runCatching {
            handler.glassesControl(byteArrayOf(0x02, 0x04)) { cmdType, response ->
                Log.d(
                    TAG,
                    "media count callback cmdType=$cmdType dataType=${response.dataType} images=${response.imageCount} videos=${response.videoCount} records=${response.recordCount}",
                )
            }
        }.onFailure { Log.d(TAG, "syncMediaCounts failed", it) }
    }

    private fun clearFileCallbacks() {
        val fileHandle = runCatching { FileHandle.getInstance() }.getOrNull() ?: return
        for (methodName in listOf("clearCallback", "clear")) {
            val method = fileHandle.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: continue
            runCatching {
                method.invoke(fileHandle)
                Log.d(TAG, "FileHandle.$methodName invoked")
            }.onFailure { Log.d(TAG, "FileHandle.$methodName failed", it) }
            return
        }
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
}
