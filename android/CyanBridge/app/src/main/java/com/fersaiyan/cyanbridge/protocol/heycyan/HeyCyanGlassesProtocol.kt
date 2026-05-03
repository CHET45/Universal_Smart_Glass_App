package com.fersaiyan.cyanbridge.protocol.heycyan

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import com.fersaiyan.cyanbridge.protocol.GlassesBattery
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
import com.fersaiyan.cyanbridge.protocol.heycyan.HeyCyanDeviceStateStore
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter between the protocol-neutral glasses API and the existing HeyCyan/Oudmon SDK.
 *
 * Important: `connect()` waits for the same service-discovery event that the vendor app uses
 * before it reports success. Calling `connectDirectly()` only submits a request and is not a
 * finished bind.
 */
class HeyCyanGlassesProtocol(
    private val activity: Activity,
) : GlassesProtocol {

    override val id: GlassesProtocolId = GlassesProtocolId.HEY_CYAN

    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.BLE_SCAN,
        GlassesCapability.BLE_CONNECT,
        GlassesCapability.CLASSIC_BT,
        GlassesCapability.BATTERY,
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
    @Volatile
    private var pendingConnect: CancellableContinuation<Unit>? = null

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
                val serviceUuids = scanRecord?.serviceUuids?.mapNotNull { it?.uuid?.toString() }.orEmpty()
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
        val protocolDevice = device.copy(protocolHint = id)
        lastDevice = protocolDevice
        HeyCyanDeviceStateStore.rememberScanBackup(device.name, device.address)
        setState(GlassesConnectionState.Connecting(protocolDevice))

        val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                pendingConnect?.cancel()
                pendingConnect = continuation
                continuation.invokeOnCancellation {
                    if (pendingConnect === continuation) pendingConnect = null
                }

                try {
                    BleOperateManager.getInstance().setReConnectMac(device.address)
                    BleOperateManager.getInstance().connectDirectly(device.address)
                } catch (t: Throwable) {
                    if (pendingConnect === continuation) pendingConnect = null
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            }
        }

        if (connected == null) {
            val error = GlassesProtocolError(
                code = "CONNECT_TIMEOUT",
                message = "HeyCyan connect timed out before service discovery for ${device.address}",
            )
            _events.tryEmit(GlassesEvent.Error(error))
            setState(GlassesConnectionState.Failed(error))
            throw IllegalStateException(error.message)
        }
    }

    override suspend fun disconnect() {
        setState(GlassesConnectionState.Disconnecting)
        try {
            pendingConnect?.cancel()
            pendingConnect = null
            BleOperateManager.getInstance().unBindDevice()
            HeyCyanDeviceStateStore.clearBindState()
            setState(GlassesConnectionState.Disconnected("disconnect requested"))
        } catch (t: Throwable) {
            val error = t.toProtocolError("DISCONNECT_FAILED", "Failed to disconnect HeyCyan device")
            _events.tryEmit(GlassesEvent.Error(error))
            setState(GlassesConnectionState.Failed(error))
            throw t
        }
    }

    override suspend fun syncTime(): GlassesCommandResult = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        suspendCancellableCoroutine<GlassesCommandResult> { continuation ->
            try {
                LargeDataHandler.getInstance().syncTime { _, _ ->
                    if (continuation.isActive) continuation.resume(GlassesCommandResult.Accepted)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(
                        GlassesCommandResult.Rejected(
                            t.toProtocolError("SYNC_TIME_FAILED", "Failed to sync HeyCyan time")
                        )
                    )
                }
            }
        }
    } ?: timeoutRejected("syncTime")

    override suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
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
                    HeyCyanDeviceStateStore.saveDeviceInfo(
                        hardwareVersion = response.hardwareVersion,
                        firmwareVersion = response.firmwareVersion,
                        wifiHardwareVersion = response.wifiHardwareVersion,
                        wifiFirmwareVersion = response.wifiFirmwareVersion,
                    )
                    _events.tryEmit(GlassesEvent.DeviceInfoChanged(info))
                    continuation.resume(Result.success(info))
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(t))
            }
        }
    } ?: Result.failure(IllegalStateException("requestDeviceInfo timed out"))

    override suspend fun requestMediaCounts(): Result<GlassesMediaCounts> = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        suspendCancellableCoroutine<Result<GlassesMediaCounts>> { continuation ->
            try {
                LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, response ->
                    if (!continuation.isActive) return@glassesControl
                    if (response.dataType != 4) return@glassesControl

                    val counts = GlassesMediaCounts(
                        photos = response.imageCount,
                        videos = response.videoCount,
                        audios = response.recordCount,
                    )
                    _events.tryEmit(GlassesEvent.MediaCountsChanged(counts))
                    continuation.resume(Result.success(counts))
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(t))
            }
        }
    } ?: Result.failure(IllegalStateException("requestMediaCounts timed out"))

    override suspend fun takePhoto(aiTransfer: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = if (aiTransfer) "takeAiPhoto" else "takePhoto",
            payload = byteArrayOf(0x02, 0x01, 0x01),
        )

    override suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = if (enabled) "startVideoRecording" else "stopVideoRecording",
            payload = byteArrayOf(0x02, 0x01, if (enabled) 0x02.toByte() else 0x03.toByte()),
        )

    override suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult =
        sendGlassesControlCommand(
            commandName = if (enabled) "startAudioRecording" else "stopAudioRecording",
            payload = byteArrayOf(0x02, 0x01, if (enabled) 0x08.toByte() else 0x0c.toByte()),
        )

    override fun downloadMedia(options: MediaDownloadOptions): Flow<GlassesTransferEvent> = callbackFlow {
        val downloader = HeyCyanWifiMediaDownloader(
            activity = activity,
            options = options,
            emit = { event -> trySend(event).isSuccess },
        )
        downloader.start()
        awaitClose { downloader.close() }
    }

    override fun close() {
        pendingConnect?.cancel()
        pendingConnect = null
        runCatching { BleScannerHelper.getInstance().stopScan(activity) }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        val device = lastDevice ?: GlassesDevice(address = HeyCyanDeviceStateStore.deviceAddress, protocolHint = id)

        if (event.connect) {
            setState(GlassesConnectionState.Connected(device))
            pendingConnect?.let { continuation ->
                pendingConnect = null
                if (continuation.isActive) continuation.resume(Unit)
            }
        } else {
            setState(GlassesConnectionState.Disconnected("bluetooth event"))
            pendingConnect?.let { continuation ->
                pendingConnect = null
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("HeyCyan Bluetooth disconnected before service discovery"))
                }
            }
        }
    }

    private suspend fun sendGlassesControlCommand(
        commandName: String,
        payload: ByteArray,
    ): GlassesCommandResult {
        var commandSubmitted = false

        val callbackResult = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine<GlassesCommandResult> { continuation ->
                try {
                    LargeDataHandler.getInstance().glassesControl(payload) { _, response ->
                        if (!continuation.isActive) return@glassesControl
                        if (response.dataType == 1 || response.errorCode == 0 || response.errorCode == -1) {
                            continuation.resume(GlassesCommandResult.Accepted)
                        } else {
                            continuation.resume(
                                GlassesCommandResult.Rejected(
                                    GlassesProtocolError(
                                        code = "COMMAND_REJECTED",
                                        message = "$commandName was rejected by HeyCyan device. errorCode=${response.errorCode}, dataType=${response.dataType}",
                                    )
                                )
                            )
                        }
                    }
                    commandSubmitted = true
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(
                            GlassesCommandResult.Rejected(
                                t.toProtocolError("COMMAND_FAILED", "$commandName failed")
                            )
                        )
                    }
                }
            }
        }

        return callbackResult ?: if (commandSubmitted) GlassesCommandResult.Accepted else timeoutRejected(commandName)
    }

    override suspend fun requestBattery(): Result<GlassesBattery> = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        suspendCancellableCoroutine<Result<GlassesBattery>> { continuation ->
            try {
                val callbackKey = "protocol_${System.currentTimeMillis()}"
                LargeDataHandler.getInstance().addBatteryCallBack(callbackKey) { _, response ->
                    if (!continuation.isActive) return@addBatteryCallBack
                    continuation.resume(parseBatteryResponse(response))
                }
                LargeDataHandler.getInstance().syncBattery()
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(t))
            }
        }
    } ?: Result.failure(IllegalStateException("requestBattery timed out"))

    private fun parseBatteryResponse(response: Any?): Result<GlassesBattery> {
        if (response == null) return Result.failure(IllegalStateException("HeyCyan battery response is null"))
        return try {
            val clazz = response.javaClass
            val battery = readInt(clazz, response, "battery", "getBattery")?.coerceIn(0, 100) ?: 0
            val charging = readBoolean(clazz, response, "charging", "isCharging", "getCharging") ?: false
            HeyCyanDeviceStateStore.saveBattery(battery, charging)
            Result.success(GlassesBattery(percent = battery, charging = charging))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun readInt(clazz: Class<*>, target: Any, vararg names: String): Int? {
        names.forEach { name ->
            runCatching { clazz.getDeclaredField(name).apply { isAccessible = true }.get(target) }.getOrNull()?.let {
                if (it is Number) return it.toInt()
            }
            runCatching { clazz.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target) }.getOrNull()?.let {
                if (it is Number) return it.toInt()
            }
        }
        return null
    }

    private fun readBoolean(clazz: Class<*>, target: Any, vararg names: String): Boolean? {
        names.forEach { name ->
            runCatching { clazz.getDeclaredField(name).apply { isAccessible = true }.get(target) }.getOrNull()?.let {
                if (it is Boolean) return it
            }
            runCatching { clazz.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target) }.getOrNull()?.let {
                if (it is Boolean) return it
            }
        }
        return null
    }

    private fun setState(state: GlassesConnectionState) {
        _connectionState.value = state
        _events.tryEmit(GlassesEvent.ConnectionChanged(state))
    }

    private fun BluetoothDevice?.toGlassesDevice(rssi: Int?, serviceUuids: List<String>, scanRecordName: String? = null): GlassesDevice? {
        val device = this ?: return null
        val address = device.address ?: return null
        val safeName = try { scanRecordName ?: device.name } catch (_: SecurityException) { scanRecordName }
        return GlassesDevice(address = address, name = safeName, rssi = rssi, serviceUuids = serviceUuids, protocolHint = id)
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
        GlassesProtocolError(code = code, message = message ?: fallbackMessage, cause = this)

    private fun timeoutRejected(commandName: String): GlassesCommandResult =
        GlassesCommandResult.Rejected(
            GlassesProtocolError(
                code = "TIMEOUT",
                message = "$commandName timed out for HeyCyan protocol",
            )
        )

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
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
        return lower.contains("heycyan") || lower.contains("cyan") || name.startsWith("O_") || name.startsWith("Q_")
    }

    override fun create(): GlassesProtocol = HeyCyanGlassesProtocol(activity)
}
