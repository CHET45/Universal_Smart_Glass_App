package com.fersaiyan.cyanbridge.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Protocol-neutral actions used by UI/application code.
 * MainActivity should call beforeAction(action) instead of embedding protocol-specific
 * safety steps such as "stop audio before every command".
 */
enum class GlassesAction {
    TIME_SYNC,
    DEVICE_INFO,
    PHOTO,
    VIDEO_START,
    VIDEO_STOP,
    AUDIO_START,
    AUDIO_STOP,
    BATTERY,
    VOLUME,
    MEDIA_COUNT,
    DATA_DOWNLOAD,
}

/**
 * Main protocol boundary used by UI/application code.
 * Existing HeyCyan code and future S100/Eyevue/HSC code should be hidden behind this contract.
 */
interface GlassesProtocol {
    val id: GlassesProtocolId
    val capabilities: Set<GlassesCapability>
    val connectionState: StateFlow<GlassesConnectionState>
    val events: Flow<GlassesEvent>

    fun scan(filter: GlassesScanFilter = GlassesScanFilter(protocolId = id)): Flow<GlassesDevice>

    suspend fun connect(device: GlassesDevice)

    suspend fun disconnect()

    /**
     * Protocol-specific pre-flight hook.
     *
     * Important: this default is intentionally conservative. Only the legacy HeyCyan/Oudmon
     * protocol keeps the old app behavior of stopping audio before most actions. BLE-native
     * protocols such as Eyevue S2/S100 and HSC/H5-15 must not receive an extra stop-audio
     * command before battery/photo/video/time/version commands because it can occupy the GATT
     * write pipeline and make the real command fail or time out.
     */
    suspend fun beforeAction(action: GlassesAction): GlassesCommandResult {
        if (!shouldStopAudioBeforeAction(action)) {
            return GlassesCommandResult.Accepted
        }

        runCatching {
            setAudioRecording(false)
        }

        return GlassesCommandResult.Accepted
    }

    /**
     * Whether MainActivity should pause automatic audio capture after beforeAction(action).
     *
     * The legacy stop-audio preflight is kept only for HeyCyan. S100/Eyevue and HSC handle
     * audio as a normal explicit action.
     */
    fun shouldStopAudioBeforeAction(action: GlassesAction): Boolean {
        if (id != GlassesProtocolId.HEY_CYAN) return false

        return when (action) {
            GlassesAction.AUDIO_START,
            GlassesAction.AUDIO_STOP -> false
            else -> capabilities.contains(GlassesCapability.AUDIO_RECORDING)
        }
    }

    /**
     * Protocol-level feature gate. Volume is currently implemented only by the legacy HeyCyan
     * path. HSC and S100 should not fall through to Oudmon volume calls.
     */
    fun supportsAction(action: GlassesAction): Boolean {
        return when (action) {
            GlassesAction.VOLUME -> id == GlassesProtocolId.HEY_CYAN
            else -> true
        }
    }

    suspend fun syncTime(): GlassesCommandResult = unsupported("syncTime")

    suspend fun requestBattery(): Result<GlassesBattery> = unsupportedResult("requestBattery")

    suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> = unsupportedResult("requestDeviceInfo")

    suspend fun requestMediaCounts(): Result<GlassesMediaCounts> = unsupportedResult("requestMediaCounts")

    suspend fun requestVolume(): Result<String> = unsupportedResult("requestVolume")

    suspend fun takePhoto(aiTransfer: Boolean = false): GlassesCommandResult = unsupported("takePhoto")

    suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult = unsupported("setVideoRecording")

    suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult = unsupported("setAudioRecording")

    fun downloadMedia(options: MediaDownloadOptions = MediaDownloadOptions()): Flow<GlassesTransferEvent> =
        kotlinx.coroutines.flow.flowOf(
            GlassesTransferEvent.Failed(
                GlassesProtocolError("UNSUPPORTED", "downloadMedia is not supported by $id")
            )
        )

    fun close()

    private fun unsupported(command: String): GlassesCommandResult =
        GlassesCommandResult.Rejected(
            GlassesProtocolError("UNSUPPORTED", "$command is not supported by $id")
        )

    private fun <T> unsupportedResult(command: String): Result<T> =
        Result.failure(UnsupportedOperationException("$command is not supported by $id"))
}
