package com.fersaiyan.cyanbridge.ui

import android.util.Log
import com.fersaiyan.cyanbridge.protocol.heycyan.HeyCyanDeviceStateStore
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.file.FileHandle
import org.greenrobot.eventbus.EventBus

/**
 * Post-service-discovery HeyCyan/Oudmon initialization.
 *
 * This ports the important parts of the vendor DeviceCmdInit flow from the reference archive:
 * clear file callbacks, sync time/device info, sync battery, feature support, volume and gyro/work-type state.
 */
object DeviceCmdInit {
    private const val TAG = "DeviceCmdInit"

    fun initDeviceSetting() {
        runCatching { init() }
            .onFailure { Log.e(TAG, "initDeviceSetting failed", it) }
    }

    private fun init() {
        runCatching { FileHandle.getInstance().clearCallback() }

        LargeDataHandler.getInstance().syncTime { _, _ ->
            Log.d(TAG, "syncTime callback")
        }

        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response == null) return@syncDeviceInfo
            HeyCyanDeviceStateStore.saveDeviceInfo(
                hardwareVersion = callString(response, "getHardwareVersion", "hardwareVersion"),
                firmwareVersion = callString(response, "getFirmwareVersion", "firmwareVersion"),
                wifiHardwareVersion = callString(response, "getWifiHardwareVersion", "wifiHardwareVersion"),
                wifiFirmwareVersion = callString(response, "getWifiFirmwareVersion", "wifiFirmwareVersion"),
            )
            Log.d(TAG, "device info synced")
        }

        syncDeviceSetting()
    }

    private fun syncDeviceSetting() {
        LargeDataHandler.getInstance().addBatteryCallBack("init") { _, response ->
            if (response == null) return@addBatteryCallBack
            val battery = callInt(response, "getBattery", "battery") ?: return@addBatteryCallBack
            val charging = callBoolean(response, "isCharging", "getCharging", "charging") ?: false
            HeyCyanDeviceStateStore.saveBattery(battery, charging)
            EventBus.getDefault().post(GlassesBatteryUpdateEvent(battery, charging))
            Log.d(TAG, "battery=$battery charging=$charging")
        }
        LargeDataHandler.getInstance().syncBattery()

        LargeDataHandler.getInstance().wearFunctionSupport { _, response ->
            if (response == null) return@wearFunctionSupport
            saveSupportResponse(response)
            val currentWorkTypeSupported = callBoolean(
                response,
                "isGlassesCurrWorkType",
                "getGlassesCurrWorkType",
                "glassesCurrWorkType"
            ) == true
            if (currentWorkTypeSupported) {
                getGlassesWorkType()
            }

            val gyroSupported = callBoolean(response, "isGyroSupport", "getGyroSupport", "gyroSupport") == true
            if (gyroSupported) {
                initGyro()
            }
        }

        LargeDataHandler.getInstance().getVolumeControl { _, response ->
            if (response == null) return@getVolumeControl
            val value = listOf(
                "getMinVolumeMusic",
                "getMaxVolumeMusic",
                "getCurrVolumeMusic",
                "getMinVolumeCall",
                "getMaxVolumeCall",
                "getCurrVolumeCall",
                "getMinVolumeSystem",
                "getMaxVolumeSystem",
                "getCurrVolumeSystem",
                "getCurrVolumeType",
            ).joinToString(",") { method -> callInt(response, method).orZero().toString() }
            HeyCyanDeviceStateStore.saveVolumeControl(value)
            Log.d(TAG, "volume=$value")
        }
    }

    fun getGlassesWorkType() {
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x01, 0x0A)) { _, response ->
            val current = response?.let { callInt(it, "getGlassesCurrentType", "glassesCurrentType") }
            if (current != null) {
                HeyCyanDeviceStateStore.saveSupportInt("glasses_current_work_type", current)
            }
        }
    }

    fun initGyro() {
        // Some bundled Oudmon SDK variants do not expose gyroConfig as a Kotlin-visible API.
        // Keep the vendor init path best-effort through reflection so this file compiles across SDK builds.
        invokeLargeDataHandlerMethod("gyroConfig", 1)
        invokeLargeDataHandlerMethod("gyroConfig", 2)
    }

    private fun invokeLargeDataHandlerMethod(methodName: String, intArg: Int) {
        val handler = LargeDataHandler.getInstance()
        val method = handler.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterTypes.size >= 1
        }

        if (method == null) {
            Log.d(TAG, "$methodName not available in this Oudmon SDK build")
            return
        }

        runCatching {
            val args = method.parameterTypes.mapIndexed { index, parameter ->
                when {
                    index == 0 && (parameter == Int::class.javaPrimitiveType || parameter == Int::class.javaObjectType) -> intArg
                    index == 0 && (parameter == Byte::class.javaPrimitiveType || parameter == Byte::class.javaObjectType) -> intArg.toByte()
                    else -> null
                }
            }.toTypedArray()
            method.invoke(handler, *args)
        }.onFailure { error ->
            Log.d(TAG, "$methodName reflection call skipped: ${error.message}")
        }
    }

    private fun saveSupportResponse(response: Any) {
        fun bool(key: String, vararg names: String) {
            callBoolean(response, *names)?.let { HeyCyanDeviceStateStore.saveSupportFlag(key, it) }
        }
        fun int(key: String, vararg names: String) {
            callInt(response, *names)?.let { HeyCyanDeviceStateStore.saveSupportInt(key, it) }
        }

        bool("wear_check", "isWearCheckSupport", "getWearCheckSupport", "wearCheckSupport")
        bool("volume_control", "isVolumeControl", "getVolumeControl", "volumeControl")
        bool("translation", "isTranslationSupport", "getTranslationSupport", "translationSupport")
        bool("earphone", "isEarphone", "getEarphone", "earphone")
        bool("gimbal_camera", "isGimbalCamera", "getGimbalCamera", "gimbalCamera")
        bool("video_direction", "isVideoDirection", "getVideoDirection", "videoDirection")
        bool("video_timestamp", "isVideoTimestamp", "getVideoTimestamp", "videoTimestamp")
        bool("offline_voice", "isOfflineVoice", "getOfflineVoice", "offlineVoice")
        bool("video_cut", "isVideoCut", "getVideoCut", "videoCut")
        bool("horizontal_correction", "isHorizontalCorrection", "getHorizontalCorrection", "horizontalCorrection")
        bool("wave_guide", "isSupportWaveGuide", "getSupportWaveGuide", "supportWaveGuide")
        bool("rt_chat", "isSupportRTChat", "getSupportRTChat", "supportRTChat")
        bool("v881", "isSupportV881", "getSupportV881", "supportV881")
        bool("live_review", "isSupportLiveReview", "getSupportLiveReview", "supportLiveReview")
        bool("1300w", "isSupport1300W", "getSupport1300W", "support1300W")
        bool("resolution", "isSupportResolution", "getSupportResolution", "supportResolution")
        bool("image_enhancement", "isImageEnhancement", "getImageEnhancement", "imageEnhancement")
        bool("ai", "isSupportAI", "getSupportAI", "supportAI")
        bool("gyro", "isGyroSupport", "getGyroSupport", "gyroSupport")
        bool("rotation", "isRotationSupport", "getRotationSupport", "rotationSupport")
        bool("current_work_type", "isGlassesCurrWorkType", "getGlassesCurrWorkType", "glassesCurrWorkType")

        int("glasses_model", "getGlassesModel", "glassesModel")
        callInt(response, "getGlassesModel", "glassesModel")?.let { HeyCyanDeviceStateStore.saveGlassesModel(it) }
    }

    private fun saveGyroResponse(response: Any?) {
        if (response == null) return
        callInt(response, "getVideoResolution", "videoResolution")?.let {
            HeyCyanDeviceStateStore.saveSupportInt("gyro_video_resolution", it)
        }
    }

    private fun callString(target: Any, vararg names: String): String? =
        callAny(target, *names) as? String

    private fun callInt(target: Any, vararg names: String): Int? = when (val value = callAny(target, *names)) {
        is Int -> value
        is Number -> value.toInt()
        else -> null
    }

    private fun callBoolean(target: Any, vararg names: String): Boolean? = when (val value = callAny(target, *names)) {
        is Boolean -> value
        else -> null
    }

    private fun callAny(target: Any, vararg names: String): Any? {
        val clazz = target.javaClass
        names.forEach { name ->
            runCatching {
                val method = clazz.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                if (method != null) return method.invoke(target)
            }
            runCatching {
                val field = clazz.getDeclaredField(name).apply { isAccessible = true }
                return field.get(target)
            }
        }
        return null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
