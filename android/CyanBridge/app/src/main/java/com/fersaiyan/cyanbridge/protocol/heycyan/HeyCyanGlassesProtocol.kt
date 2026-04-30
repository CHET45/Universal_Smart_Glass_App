package com.fersaiyan.cyanbridge.protocol.heycyan

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import com.fersaiyan.cyanbridge.protocol.GlassesCapability
import com.fersaiyan.cyanbridge.protocol.GlassesCommandResult
import com.fersaiyan.cyanbridge.protocol.GlassesConnectionState
import com.fersaiyan.cyanbridge.protocol.GlassesDevice
import com.fersaiyan.cyanbridge.protocol.GlassesDeviceInfo
import com.fersaiyan.cyanbridge.protocol.GlassesEvent
import com.fersaiyan.cyanbridge.protocol.GlassesMediaCounts
import com.fersaiyan.cyanbridge.protocol.GlassesProtocol
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolError
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolId
import com.fersaiyan.cyanbridge.protocol.GlassesProtocolProvider
import com.fersaiyan.cyanbridge.protocol.GlassesScanFilter
import com.fersaiyan.cyanbridge.protocol.GlassesTransferEvent
import com.fersaiyan.cyanbridge.protocol.MediaDownloadOptions
import com.fersaiyan.cyanbridge.ui.BluetoothEvent
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Adapter between the protocol-neutral glasses API and the existing HeyCyan/Oudmon SDK.
 *
 * This class intentionally lives in the app module, not in :glasses-protocol, because it depends on
 * the concrete SDK classes: BleOperateManager, BleScannerHelper and LargeDataHandler.
 */
class HeyCyanGlassesProtocol(
    private val activity: Activity,
) : GlassesProtocol {

    override val id: GlassesProtocolId = GlassesProtocolId.HEY_CYAN

    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.BLE_SCAN,
        GlassesCapability.BLE_CONNECT,
        GlassesCapability.CLASSIC_BT,
        GlassesCapability.DEVICE_INFO,
        GlassesCapability.TIME_SYNC,
        GlassesCapability.PHOTO_CAPTURE,
        GlassesCapability.AI_PHOTO_CAPTURE,
        GlassesCapability.VIDEO_RECORDING,
        GlassesCapability.AUDIO_RECORDING,
        GlassesCapability.MEDIA_COUNT,
        GlassesCapability.WIFI_P2P_DOWNLOAD,
        GlassesCapability.OTA,
    )

    private val _connectionState = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private var lastDevice: GlassesDevice? = null
    private val seenScanDevices = ConcurrentHashMap<String, GlassesDevice>()

    init {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun scan(filter: GlassesScanFilter): Flow<GlassesDevice> = callbackFlow {
        seenScanDevices.clear()
        setState(GlassesConnectionState.Scanning)

        val callback = object : ScanWrapperCallback {
            override fun onStart() = Unit

            override fun onStop() = Unit

            override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                val glassesDevice = device.toGlassesDevice(rssi = rssi, serviceUuids = emptyList()) ?: return
                if (!matchesFilter(glassesDevice, filter)) return

                seenScanDevices[glassesDevice.address] = glassesDevice
                trySend(glassesDevice)
            }

            override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
                val serviceUuids = scanRecord?.serviceUuids
                    ?.mapNotNull { parcelUuid -> parcelUuid?.uuid?.toString() }
                    .orEmpty()

                val fallbackRssi = seenScanDevices[device?.address]?.rssi
                val glassesDevice = device.toGlassesDevice(
                    rssi = fallbackRssi,
                    serviceUuids = serviceUuids,
                    scanRecordName = scanRecord?.deviceName,
                ) ?: return

                if (!matchesFilter(glassesDevice, filter)) return

                seenScanDevices[glassesDevice.address] = glassesDevice
                trySend(glassesDevice)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit

            override fun onScanFailed(errorCode: Int) {
                val error = GlassesProtocolError(
                    code = "SCAN_FAILED",
                    message = "HeyCyan BLE scan failed: $errorCode",
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                close(IllegalStateException(error.message))
            }
        }

        try {
            BleScannerHelper.getInstance().reSetCallback()
            BleScannerHelper.getInstance().scanDevice(activity, null, callback)
        } catch (t: Throwable) {
            val error = t.toProtocolError("SCAN_START_FAILED", "Failed to start HeyCyan scan")
            _events.tryEmit(GlassesEvent.Error(error))
            setState(GlassesConnectionState.Failed(error))
            close(t)
        }

        val timeoutJob = launch {
            delay(filter.timeoutMillis)
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            runCatching { BleScannerHelper.getInstance().stopScan(activity) }
            if (_connectionState.value is GlassesConnectionState.Scanning) {
                setState(GlassesConnectionState.Idle)
            }
        }
    }

    override suspend fun connect(device: GlassesDevice) {
        lastDevice = device.copy(protocolHint = id)
        setState(GlassesConnectionState.Connecting(lastDevice ?: device))

        try {
            BleOperateManager.getInstance().connectDirectly(device.address)
        } catch (t: Throwable) {
            val error = t.toProtocolError("CONNECT_FAILED", "Failed to connect to HeyCyan device ${device.address}")
            _events.tryEmit(GlassesEvent.Error(error))
            setState(GlassesConnectionState.Failed(error))
            throw t
        }
    }

    override suspend fun disconnect() {
        setState(GlassesConnectionState.Disconnecting)
        try {
            BleOperateManager.getInstance().unBindDevice()
            setState(GlassesConnectionState.Disconnected("disconnect requested"))
        } catch (t: Throwable) {
            val error = t.toProtocolError("DISCONNECT_FAILED", "Failed to disconnect HeyCyan device")
            _events.tryEmit(GlassesEvent.Error(error))
            setState(GlassesConnectionState.Failed(error))
            throw t
        }
    }

    override suspend fun syncTime(): GlassesCommandResult =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<GlassesCommandResult> { continuation ->
                try {
                    LargeDataHandler.getInstance().syncTime { _, _ ->
                        if (continuation.isActive) {
                            continuation.resume(GlassesCommandResult.Accepted)
                        }
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(
                            GlassesCommandResult.Rejected(
                                t.toProtocolError("SYNC_TIME_FAILED", "Failed to sync HeyCyan time"),
                            ),
                        )
                    }
                }
            }
        } ?: timeoutRejected("syncTime")

    override suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<GlassesDeviceInfo>> { continuation ->
                try {
                    LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                        if (!continuation.isActive) return@syncDeviceInfo

                        if (response == null) {
                            continuation.resume(Result.failure(IllegalStateException("HeyCyan device info response is null")))
                            return@syncDeviceInfo
                        }

                        val info = GlassesDeviceInfo(
                            hardwareVersion = response.hardwareVersion,
                            firmwareVersion = response.firmwareVersion,
                            bluetoothFirmwareVersion = response.firmwareVersion,
                            wifiFirmwareVersion = response.wifiFirmwareVersion,
                            raw = mapOf(
                                "hardwareVersion" to response.hardwareVersion.orEmpty(),
                                "firmwareVersion" to response.firmwareVersion.orEmpty(),
                                "wifiHardwareVersion" to response.wifiHardwareVersion.orEmpty(),
                                "wifiFirmwareVersion" to response.wifiFirmwareVersion.orEmpty(),
                            ),
                        )

                        _events.tryEmit(GlassesEvent.DeviceInfoChanged(info))
                        continuation.resume(Result.success(info))
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(t))
                    }
                }
            }
        } ?: Result.failure(IllegalStateException("requestDeviceInfo timed out"))

    override suspend fun requestMediaCounts(): Result<GlassesMediaCounts> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<GlassesMediaCounts>> { continuation ->
                try {
                    LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, response ->
                        if (!continuation.isActive) return@glassesControl

                        if (response.dataType != 4) {
                            continuation.resume(
                                Result.failure(IllegalStateException("Unexpected HeyCyan media-count response type: ${response.dataType}")),
                            )
                            return@glassesControl
                        }

                        val counts = GlassesMediaCounts(
                            photos = response.imageCount,
                            videos = response.videoCount,
                            audios = response.recordCount,
                        )

                        _events.tryEmit(GlassesEvent.MediaCountsChanged(counts))
                        continuation.resume(Result.success(counts))
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(t))
                    }
                }
            }
        } ?: Result.failure(IllegalStateException("requestMediaCounts timed out"))

    /**
     * The existing app uses 02 01 01 for the camera button path.
     * aiTransfer is mapped to 02 01 02 as a best-effort placeholder until the exact SDK command is verified.
     */
    override suspend fun takePhoto(aiTransfer: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = "takePhoto",
            payload = byteArrayOf(0x02, 0x01, if (aiTransfer) 0x02.toByte() else 0x01.toByte()),
        )

    /**
     * Best-effort command mapping; keep this isolated here so it can be corrected without touching UI code.
     */
    override suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = if (enabled) "startVideoRecording" else "stopVideoRecording",
            payload = byteArrayOf(0x02, 0x02, enabled.toByteFlag()),
        )

    /**
     * Best-effort command mapping; keep this isolated here so it can be corrected without touching UI code.
     */
    override suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = if (enabled) "startAudioRecording" else "stopAudioRecording",
            payload = byteArrayOf(0x02, 0x03, enabled.toByteFlag()),
        )

    override fun downloadMedia(options: MediaDownloadOptions): Flow<GlassesTransferEvent> =
        flowOf(
            GlassesTransferEvent.Failed(
                GlassesProtocolError(
                    code = "NOT_MOVED_YET",
                    message = "HeyCyan media download is still implemented in MainActivity Wi-Fi P2P flow. Move that flow here next.",
                ),
            ),
        )

    override fun close() {
        runCatching { BleScannerHelper.getInstance().stopScan(activity) }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        val device = lastDevice ?: GlassesDevice(
            address = "",
            protocolHint = id,
        )

        val state = if (event.connect) {
            GlassesConnectionState.Connected(device)
        } else {
            GlassesConnectionState.Disconnected("bluetooth event")
        }

        setState(state)
    }

    private suspend fun sendGlassesControlCommand(
        commandName: String,
        payload: ByteArray,
    ): GlassesCommandResult =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<GlassesCommandResult> { continuation ->
                try {
                    LargeDataHandler.getInstance().glassesControl(payload) { _, response ->
                        if (!continuation.isActive) return@glassesControl

                        if (response.errorCode == 0) {
                            continuation.resume(GlassesCommandResult.Accepted)
                        } else {
                            continuation.resume(
                                GlassesCommandResult.Rejected(
                                    GlassesProtocolError(
                                        code = "COMMAND_REJECTED",
                                        message = "$commandName was rejected by HeyCyan device. errorCode=${response.errorCode}",
                                    ),
                                ),
                            )
                        }
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(
                            GlassesCommandResult.Rejected(
                                t.toProtocolError("COMMAND_FAILED", "$commandName failed"),
                            ),
                        )
                    }
                }
            }
        } ?: timeoutRejected(commandName)

    private fun setState(state: GlassesConnectionState) {
        _connectionState.value = state
        _events.tryEmit(GlassesEvent.ConnectionChanged(state))
    }

    private fun BluetoothDevice?.toGlassesDevice(
        rssi: Int?,
        serviceUuids: List<String>,
        scanRecordName: String? = null,
    ): GlassesDevice? {
        val device = this ?: return null
        val address = device.address ?: return null
        val safeName = try {
            scanRecordName ?: device.name
        } catch (_: SecurityException) {
            scanRecordName
        }

        return GlassesDevice(
            address = address,
            name = safeName,
            rssi = rssi,
            serviceUuids = serviceUuids,
            protocolHint = id,
        )
    }

    private fun matchesFilter(device: GlassesDevice, filter: GlassesScanFilter): Boolean {
        if (filter.protocolId != null && filter.protocolId != id) return false

        val prefix = filter.namePrefix
        if (prefix != null && device.name?.startsWith(prefix, ignoreCase = true) != true) return false

        if (filter.serviceUuids.isNotEmpty()) {
            val actual = device.serviceUuids.map { it.lowercase() }.toSet()
            val required = filter.serviceUuids.map { it.lowercase() }
            if (required.none { it in actual }) return false
        }

        return true
    }

    private fun Throwable.toProtocolError(code: String, fallbackMessage: String): GlassesProtocolError =
        GlassesProtocolError(
            code = code,
            message = message ?: fallbackMessage,
            cause = this,
        )

    private fun timeoutRejected(commandName: String): GlassesCommandResult =
        GlassesCommandResult.Rejected(
            GlassesProtocolError(
                code = "TIMEOUT",
                message = "$commandName timed out for HeyCyan protocol",
            ),
        )

    private fun Boolean.toByteFlag(): Byte = if (this) 0x01.toByte() else 0x00.toByte()

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }
}

class HeyCyanGlassesProtocolProvider(
    private val activity: Activity,
) : GlassesProtocolProvider {

    override val id: GlassesProtocolId = GlassesProtocolId.HEY_CYAN

    override fun supports(device: GlassesDevice): Boolean {
        if (device.protocolHint == id) return true

        val name = device.name?.trim().orEmpty()
        val lower = name.lowercase()

        return lower.contains("heycyan") ||
            lower.contains("cyan") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
    }

    override fun create(): GlassesProtocol = HeyCyanGlassesProtocol(activity)
}
