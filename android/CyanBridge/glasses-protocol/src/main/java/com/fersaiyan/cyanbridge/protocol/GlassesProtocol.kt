package com.fersaiyan.cyanbridge.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main protocol boundary used by UI/application code.
 * Existing HeyCyan code and future S100/Eyevue code should both be hidden behind this contract.
 */
interface GlassesProtocol {
    val id: GlassesProtocolId
    val capabilities: Set<GlassesCapability>
    val connectionState: StateFlow<GlassesConnectionState>
    val events: Flow<GlassesEvent>

    fun scan(filter: GlassesScanFilter = GlassesScanFilter(protocolId = id)): Flow<GlassesDevice>

    suspend fun connect(device: GlassesDevice)

    suspend fun disconnect()

    suspend fun syncTime(): GlassesCommandResult = unsupported("syncTime")

    suspend fun requestBattery(): Result<GlassesBattery> = unsupportedResult("requestBattery")

    suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> = unsupportedResult("requestDeviceInfo")

    suspend fun requestMediaCounts(): Result<GlassesMediaCounts> = unsupportedResult("requestMediaCounts")

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
