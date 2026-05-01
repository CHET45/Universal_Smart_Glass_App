package com.fersaiyan.cyanbridge.protocol.eyevues2

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
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
import com.fersaiyan.cyanbridge.protocol.GlassesScanFilter
import com.fersaiyan.cyanbridge.protocol.GlassesTransferEvent
import com.fersaiyan.cyanbridge.protocol.MediaDownloadOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class EyevueS2GlassesProtocol(
    private val context: Context,
) : GlassesProtocol {

    override val id: GlassesProtocolId = GlassesProtocolId.S100

    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.BLE_SCAN,
        GlassesCapability.BLE_CONNECT,
        GlassesCapability.BATTERY,
        GlassesCapability.DEVICE_INFO,
        GlassesCapability.TIME_SYNC,
        GlassesCapability.PHOTO_CAPTURE,
        GlassesCapability.AI_PHOTO_CAPTURE,
        GlassesCapability.VIDEO_RECORDING,
        GlassesCapability.AUDIO_RECORDING,
        GlassesCapability.VOLUME_CONTROL,
        GlassesCapability.MEDIA_COUNT,
        GlassesCapability.WIFI_P2P_DOWNLOAD,
        GlassesCapability.OTA,
    )

    private val _connectionState =
        MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var cmdNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var photoNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var lastDevice: GlassesDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var markedConnected = false
    private var initialQueriesScheduled = false

    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var pendingBattery: CompletableDeferred<GlassesBattery>? = null
    private var pendingDeviceInfo: CompletableDeferred<GlassesDeviceInfo>? = null
    private var pendingMediaCounts: CompletableDeferred<GlassesMediaCounts>? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun scan(filter: GlassesScanFilter): Flow<GlassesDevice> = callbackFlow {
        if (!hasBleScanPermission()) {
            val error = GlassesProtocolError(
                "MISSING_PERMISSION",
                "Missing BLE scan permission"
            )
            _events.tryEmit(GlassesEvent.Error(error))
            close(SecurityException(error.message))
            return@callbackFlow
        }

        val adapter = bluetoothManager.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            val error = GlassesProtocolError(
                "NO_BLE_SCANNER",
                "Bluetooth LE scanner is unavailable"
            )
            _events.tryEmit(GlassesEvent.Error(error))
            close(IllegalStateException(error.message))
            return@callbackFlow
        }

        setState(GlassesConnectionState.Scanning)

        val callback = object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                val device = result.device ?: return
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }
                    .orEmpty()
                val glassesDevice = device.toGlassesDevice(
                    result.rssi,
                    serviceUuids
                ) ?: return
                if (!matchesFilter(
                        glassesDevice,
                        filter
                    )
                ) return
                trySend(glassesDevice)
            }

            override fun onScanFailed(errorCode: Int) {
                val error = GlassesProtocolError(
                    "SCAN_FAILED",
                    "Eyevue S2 BLE scan failed: $errorCode"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                close(IllegalStateException(error.message))
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(EyevueS2PacketCodec.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(
                filters,
                settings,
                callback
            )
        }.onFailure { error ->
                val protocolError = error.toProtocolError(
                    "SCAN_START_FAILED",
                    "Failed to start Eyevue S2 scan"
                )
                _events.tryEmit(GlassesEvent.Error(protocolError))
                setState(GlassesConnectionState.Failed(protocolError))
                close(error)
            }

        val timeoutJob = launch {
            delay(filter.timeoutMillis)
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            runCatching { scanner.stopScan(callback) }
            if (_connectionState.value is GlassesConnectionState.Scanning) {
                setState(GlassesConnectionState.Idle)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override suspend fun connect(device: GlassesDevice) {
        if (!hasBleConnectPermission()) throw SecurityException("Missing BLE connect permission")

        lastDevice = device.copy(protocolHint = id)
        markedConnected = false
        initialQueriesScheduled = false
        setState(GlassesConnectionState.Connecting(lastDevice ?: device))

        val adapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Bluetooth adapter is unavailable")
        val remoteDevice = adapter.getRemoteDevice(device.address)
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred

        gatt = remoteDevice.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )

        if (gatt == null) {
            val error = IllegalStateException("connectGatt returned null for ${device.address}")
            connectDeferred = null
            throw error
        }

        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun disconnect() {
        setState(GlassesConnectionState.Disconnecting)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        markedConnected = false
        initialQueriesScheduled = false
        writeCharacteristic = null
        cmdNotifyCharacteristic = null
        photoNotifyCharacteristic = null
        setState(GlassesConnectionState.Disconnected("disconnect requested"))
    }

    override suspend fun syncTime(): GlassesCommandResult = sendCommand(
        "syncTime",
        EyevueS2PacketCodec.timeCommand()
    )

    override suspend fun requestBattery(): Result<GlassesBattery> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesBattery>()
            pendingBattery = deferred
            val sent =
                write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_BATTERY))
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Battery command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestBattery timed out"))

    override suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesDeviceInfo>()
            pendingDeviceInfo = deferred
            val sent =
                write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_DEVICE_INFO))
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Device info command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestDeviceInfo timed out"))

    override suspend fun requestMediaCounts(): Result<GlassesMediaCounts> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesMediaCounts>()
            pendingMediaCounts = deferred
            val sent =
                write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_THUMBNAIL_COUNT))
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Media count command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestMediaCounts timed out"))

    override suspend fun takePhoto(aiTransfer: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (aiTransfer) "takePhotoAiTransfer" else "takePhoto",
        bytes = EyevueS2PacketCodec.appCommand(
            EyevueS2PacketCodec.CMD_TAKE_PHOTO,
            byteArrayOf((if (aiTransfer) 0x31 else 0x30).toByte()),
        ),
    )

    override suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startVideoRecording" else "stopVideoRecording",
        bytes = EyevueS2PacketCodec.appCommand(
            if (enabled) EyevueS2PacketCodec.CMD_START_VIDEO else EyevueS2PacketCodec.CMD_STOP_VIDEO,
            byteArrayOf(0x00.toByte()),
        ),
    )

    override suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startAudioRecording" else "stopAudioRecording",
        bytes = EyevueS2PacketCodec.appCommand(
            EyevueS2PacketCodec.CMD_AUDIO_RECORD,
            byteArrayOf((if (enabled) 0x01 else 0x00).toByte()),
        ),
    )

    fun openWifiP2p(): GlassesCommandResult {
        return if (write(
                EyevueS2PacketCodec.appCommand(
                    EyevueS2PacketCodec.CMD_OPEN_WIFI,
                    byteArrayOf(0x31.toByte())
                )
            )
        ) {
            GlassesCommandResult.Accepted
        } else {
            GlassesCommandResult.Rejected(
                GlassesProtocolError(
                    "WRITE_FAILED",
                    "Failed to open Eyevue S2 P2P Wi-Fi"
                )
            )
        }
    }

    override fun downloadMedia(options: MediaDownloadOptions): Flow<GlassesTransferEvent> = flowOf(
        GlassesTransferEvent.Failed(
            GlassesProtocolError(
                code = "NOT_IMPLEMENTED_YET",
                message = "Eyevue S2 media download needs the Wi-Fi/P2P HTTP layer from the source pack: 192.168.49.207 file-list/download/delete endpoints.",
            )
        )

    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        markedConnected = false
        initialQueriesScheduled = false
        connectDeferred = null
        pendingBattery = null
        pendingDeviceInfo = null
        pendingMediaCounts = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = GlassesProtocolError(
                    "GATT_STATUS_$status",
                    "Eyevue S2 GATT status error: $status"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(
                        TAG,
                        "GATT STATE_CONNECTED: ${gatt.device?.address}"
                    )

                    // Do not block the pairing screen on service discovery. Some ESP32/NimBLE
                    // peripherals report the central connection immediately, but Android can delay
                    // or miss onServicesDiscovered(). For the UI, the BLE link itself is enough.
                    completeBleLinkConnected()

                    runCatching { gatt.requestMtu(247) }.onFailure {
                            Log.w(
                                TAG,
                                "requestMtu failed",
                                it
                            )
                        }

                    mainHandler.postDelayed(
                        {
                            runCatching { gatt.discoverServices() }.onFailure { error ->
                                    Log.e(
                                        TAG,
                                        "discoverServices failed",
                                        error
                                    )
                                    _events.tryEmit(
                                        GlassesEvent.Error(
                                            GlassesProtocolError(
                                                "SERVICE_DISCOVERY_START_FAILED",
                                                error.message
                                                    ?: "Eyevue S2 service discovery did not start",
                                                error,
                                            )
                                        )
                                    )
                                }
                        },
                        250L
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    setState(GlassesConnectionState.Disconnected("gatt disconnected"))
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            Log.i(
                TAG,
                "onServicesDiscovered status=$status services=${gatt.services?.map { it.uuid }}"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = GlassesProtocolError(
                    "SERVICE_DISCOVERY_FAILED",
                    "Eyevue S2 service discovery failed: $status"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            val service = gatt.getService(EyevueS2PacketCodec.SERVICE_UUID)
            if (service == null) {
                val error = GlassesProtocolError(
                    "SERVICE_NOT_FOUND",
                    "Eyevue S2 service aa12 not found"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            writeCharacteristic = service.getCharacteristic(EyevueS2PacketCodec.CMD_WRITE_UUID)
            cmdNotifyCharacteristic = service.getCharacteristic(EyevueS2PacketCodec.CMD_NOTIFY_UUID)
            photoNotifyCharacteristic =
                service.getCharacteristic(EyevueS2PacketCodec.PHOTO_NOTIFY_UUID)

            if (writeCharacteristic == null || cmdNotifyCharacteristic == null) {
                val error = GlassesProtocolError(
                    "CHARACTERISTICS_NOT_FOUND",
                    "Eyevue S2 write/notify characteristics not found"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            // From this point the GATT link is usable for commands. Do not depend on the
            // legacy HeyCyan EventBus here; Eyevue/S100 is a direct GATT protocol.
            enableNotify(
                gatt,
                cmdNotifyCharacteristic!!
            )
            photoNotifyCharacteristic?.let {
                enableNotify(
                    gatt,
                    it
                )
            }
            markConnected()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "Descriptor write failed: $status"
                )
            }

            if (descriptor.characteristic.uuid == EyevueS2PacketCodec.CMD_NOTIFY_UUID) {
                val photo = photoNotifyCharacteristic
                if (photo != null) {
                    enableNotify(
                        gatt,
                        photo
                    )
                } else {
                    markConnected()
                }
                return
            }

            if (descriptor.characteristic.uuid == EyevueS2PacketCodec.PHOTO_NOTIFY_UUID) {
                markConnected()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION") onCharacteristicBytes(
                characteristic.uuid.toString(),
                characteristic.value ?: return
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onCharacteristicBytes(
                characteristic.uuid.toString(),
                value
            )
        }
    }

    private fun completeBleLinkConnected() {
        if (markedConnected) return
        markedConnected = true

        val device = lastDevice ?: GlassesDevice(
            address = "",
            protocolHint = id
        )
        setState(GlassesConnectionState.Connected(device))
        connectDeferred?.complete(Unit)
    }

    private fun markConnected() {
        completeBleLinkConnected()
        scheduleInitialQueriesIfReady()
    }

    private fun scheduleInitialQueriesIfReady() {
        if (initialQueriesScheduled) return
        if (writeCharacteristic == null) {
            Log.w(
                TAG,
                "Initial queries skipped: write characteristic is not ready yet"
            )
            return
        }
        initialQueriesScheduled = true

        // Official flow queries basic state after connecting. Delay it slightly so it does not
        // race Android's async CCCD descriptor writes.
        mainHandler.postDelayed(
            {
                if (_connectionState.value is GlassesConnectionState.Connected) {
                    write(EyevueS2PacketCodec.timeCommand())
                    write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_BATTERY))
                    write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_THUMBNAIL_COUNT))
                    write(EyevueS2PacketCodec.appCommandWithZero(EyevueS2PacketCodec.CMD_GET_DEVICE_INFO))
                }
            },
            500L
        )
    }

    private fun onCharacteristicBytes(
        uuid: String,
        bytes: ByteArray
    ) {
        _events.tryEmit(GlassesEvent.RawPacket(bytes))

        val packet = when (uuid.lowercase()) {
            EyevueS2PacketCodec.CMD_NOTIFY_UUID.toString()
                .lowercase() -> EyevueS2PacketCodec.decodeBlePacket(bytes)

            EyevueS2PacketCodec.PHOTO_NOTIFY_UUID.toString()
                .lowercase() -> EyevueS2PacketCodec.decodeRxFilePacket(bytes)

            else -> null
        } ?: return

        if (!packet.crcValid) {
            Log.w(
                TAG,
                "Ignoring packet with bad CRC: cmd=${packet.command}"
            )
            return
        }

        when (packet.command) {
            EyevueS2PacketCodec.CMD_GET_BATTERY -> {
                val parsed = EyevueS2PacketCodec.parseLegacyBattery(packet.payload) ?: return
                val battery = GlassesBattery(
                    parsed.first,
                    parsed.second
                )
                pendingBattery?.complete(battery)
                _events.tryEmit(GlassesEvent.BatteryChanged(battery))
            }

            EyevueS2PacketCodec.UPLOAD_BATTERY -> {
                val parsed = EyevueS2PacketCodec.parseUploadBattery(packet.payload) ?: return
                val battery = GlassesBattery(
                    parsed.first,
                    parsed.second
                )
                pendingBattery?.complete(battery)
                _events.tryEmit(GlassesEvent.BatteryChanged(battery))
            }

            EyevueS2PacketCodec.CMD_GET_DEVICE_INFO -> {
                val parsed = EyevueS2PacketCodec.parseDeviceInfo(packet.payload) ?: return
                val info = GlassesDeviceInfo(
                    hardwareVersion = parsed.third,
                    firmwareVersion = parsed.second,
                    bluetoothFirmwareVersion = parsed.first,
                    wifiFirmwareVersion = parsed.second,
                    raw = mapOf(
                        "btVersion" to parsed.first,
                        "ispVersion" to parsed.second,
                        "hardwareVersion" to parsed.third,
                    ),
                )
                pendingDeviceInfo?.complete(info)
                _events.tryEmit(GlassesEvent.DeviceInfoChanged(info))
            }

            EyevueS2PacketCodec.CMD_GET_THUMBNAIL_COUNT, EyevueS2PacketCodec.UPLOAD_THUMBNAIL_COUNT -> {
                val count = EyevueS2PacketCodec.parseThumbnailCount(packet.payload) ?: return
                val counts = GlassesMediaCounts(photos = count)
                pendingMediaCounts?.complete(counts)
                _events.tryEmit(GlassesEvent.MediaCountsChanged(counts))
            }

            EyevueS2PacketCodec.UPLOAD_ACTION_SYNC -> handleActionSync(packet.payload)
            EyevueS2PacketCodec.UPLOAD_HD_IMAGE_FAILED -> _events.tryEmit(
                GlassesEvent.Error(
                    GlassesProtocolError(
                        "HD_IMAGE_FAILED",
                        "Eyevue S2 reported HD image transfer failure"
                    )
                )
            )
        }
    }

    private fun handleActionSync(payload: ByteArray) {
        if (payload.getOrNull(0)
                ?.toInt() == 1
        ) _events.tryEmit(
            GlassesEvent.ButtonPressed(
                GlassesEvent.Button.PHOTO,
                EyevueS2PacketCodec.UPLOAD_ACTION_SYNC
            )
        )
        if (payload.getOrNull(1)
                ?.toInt() == 1
        ) _events.tryEmit(
            GlassesEvent.ButtonPressed(
                GlassesEvent.Button.AUDIO,
                EyevueS2PacketCodec.UPLOAD_ACTION_SYNC
            )
        )
        if (payload.getOrNull(2)
                ?.toInt() == 1
        ) _events.tryEmit(
            GlassesEvent.ButtonPressed(
                GlassesEvent.Button.VIDEO,
                EyevueS2PacketCodec.UPLOAD_ACTION_SYNC
            )
        )
        if (payload.getOrNull(3)
                ?.toInt() == 1
        ) _events.tryEmit(
            GlassesEvent.ButtonPressed(
                GlassesEvent.Button.VOLUME_UP,
                EyevueS2PacketCodec.UPLOAD_ACTION_SYNC
            )
        )
        if (payload.getOrNull(4)
                ?.toInt() == 1
        ) _events.tryEmit(
            GlassesEvent.ButtonPressed(
                GlassesEvent.Button.VOLUME_DOWN,
                EyevueS2PacketCodec.UPLOAD_ACTION_SYNC
            )
        )
    }

    private suspend fun sendCommand(
        commandName: String,
        bytes: ByteArray
    ): GlassesCommandResult {
        return if (write(bytes)) {
            GlassesCommandResult.Accepted
        } else {
            GlassesCommandResult.Rejected(
                GlassesProtocolError(
                    "WRITE_FAILED",
                    "$commandName write failed for Eyevue S2"
                )
            )
        }
    }

    private fun write(bytes: ByteArray): Boolean {
        if (!hasBleConnectPermission()) return false
        val gatt = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false

        return try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.value = bytes
                @Suppress("DEPRECATION") gatt.writeCharacteristic(characteristic)
            }
        } catch (security: SecurityException) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotify(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (!hasBleConnectPermission()) return
        gatt.setCharacteristicNotification(
            characteristic,
            true
        )
        val descriptor = characteristic.getDescriptor(EyevueS2PacketCodec.CCCD_UUID) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            @Suppress("DEPRECATION") descriptor.value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    private fun setState(state: GlassesConnectionState) {
        _connectionState.value = state
        _events.tryEmit(GlassesEvent.ConnectionChanged(state))
    }

    private fun BluetoothDevice.toGlassesDevice(
        rssi: Int?,
        serviceUuids: List<String>
    ): GlassesDevice? {
        val macAddress = this.address ?: return null
        val safeName = try {
            this.name
        } catch (_: SecurityException) {
            null
        }
        return GlassesDevice(
            address = macAddress,
            name = safeName,
            rssi = rssi,
            serviceUuids = serviceUuids,
            protocolHint = id
        )
    }

    private fun matchesFilter(
        device: GlassesDevice,
        filter: GlassesScanFilter
    ): Boolean {
        if (filter.protocolId != null && filter.protocolId != id) return false
        val actual = device.serviceUuids.map { it.lowercase() }
            .toSet()
        val hasS2Service = EyevueS2PacketCodec.SERVICE_UUID.toString()
            .lowercase() in actual
        if (hasS2Service) return true

        val lowerName = device.name?.lowercase()
            .orEmpty()
        return lowerName.contains("eyevue") || lowerName.contains("s2") || lowerName.contains("lensiq")
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBleConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun Throwable.toProtocolError(
        code: String,
        fallbackMessage: String
    ): GlassesProtocolError = GlassesProtocolError(
        code = code,
        message = message ?: fallbackMessage,
        cause = this
    )

    companion object {
        private const val TAG = "EyevueS2Protocol"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
