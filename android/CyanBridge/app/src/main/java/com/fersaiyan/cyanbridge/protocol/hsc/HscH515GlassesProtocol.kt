package com.fersaiyan.cyanbridge.protocol.hsc

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

class HscH515GlassesProtocol(
    private val context: Context,
) : GlassesProtocol {

    override val id: GlassesProtocolId = GlassesProtocolId.HSC_H5_15

    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.BLE_SCAN,
        GlassesCapability.BLE_CONNECT,
        GlassesCapability.BATTERY,
        GlassesCapability.DEVICE_INFO,
        GlassesCapability.TIME_SYNC,
        GlassesCapability.PHOTO_CAPTURE,
        GlassesCapability.VIDEO_RECORDING,
        GlassesCapability.AUDIO_RECORDING,
        GlassesCapability.MEDIA_COUNT,
    )

    private val _connectionState = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = HscH515PacketCodec.SequenceGenerator()
    private val frameDecoder = HscH515PacketCodec.FrameDecoder()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var lastDevice: GlassesDevice? = null
    private var markedConnected = false
    private var initialQueriesScheduled = false

    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var pendingBattery: CompletableDeferred<GlassesBattery>? = null
    private var pendingDeviceInfo: CompletableDeferred<GlassesDeviceInfo>? = null
    private var pendingMediaCounts: CompletableDeferred<GlassesMediaCounts>? = null

    private var cachedDeviceName: String? = null
    private var cachedModel: String? = null
    private var cachedFirmware: String? = null
    private var cachedHardware: String? = null
    private var cachedProductInfo: HscH515PacketCodec.ProductInfo? = null
    private var cachedSupportFeatures: Map<String, Boolean>? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun scan(filter: GlassesScanFilter): Flow<GlassesDevice> = callbackFlow {
        if (!hasBleScanPermission()) {
            val error = GlassesProtocolError("MISSING_PERMISSION", "Missing BLE scan permission")
            _events.tryEmit(GlassesEvent.Error(error))
            close(SecurityException(error.message))
            return@callbackFlow
        }

        val adapter = bluetoothManager.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            val error = GlassesProtocolError("NO_BLE_SCANNER", "Bluetooth LE scanner is unavailable")
            _events.tryEmit(GlassesEvent.Error(error))
            close(IllegalStateException(error.message))
            return@callbackFlow
        }

        setState(GlassesConnectionState.Scanning)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
                val glassesDevice = device.toGlassesDevice(result.rssi, serviceUuids) ?: return
                if (!matchesFilter(glassesDevice, filter)) return
                trySend(glassesDevice)
            }

            override fun onScanFailed(errorCode: Int) {
                val error = GlassesProtocolError("SCAN_FAILED", "HSC/H5-15 BLE scan failed: $errorCode")
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                close(IllegalStateException(error.message))
            }
        }

        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { error ->
                val protocolError = error.toProtocolError("SCAN_START_FAILED", "Failed to start HSC/H5-15 scan")
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
        cachedDeviceName = device.name
        cachedModel = null
        cachedFirmware = null
        cachedHardware = null
        cachedProductInfo = null
        cachedSupportFeatures = null
        markedConnected = false
        initialQueriesScheduled = false
        frameDecoder.clear()
        setState(GlassesConnectionState.Connecting(lastDevice ?: device))

        val adapter = bluetoothManager.adapter ?: throw IllegalStateException("Bluetooth adapter is unavailable")
        val remoteDevice = adapter.getRemoteDevice(device.address)
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred

        gatt = remoteDevice.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )

        if (gatt == null) {
            connectDeferred = null
            throw IllegalStateException("connectGatt returned null for ${device.address}")
        }

        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun disconnect() {
        setState(GlassesConnectionState.Disconnecting)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        clearGattState()
        setState(GlassesConnectionState.Disconnected("disconnect requested"))
    }

    override suspend fun syncTime(): GlassesCommandResult = sendCommand(
        commandName = "syncTime",
        bytes = HscH515PacketCodec.timeRequest(sequence.next()),
    )

    override suspend fun requestBattery(): Result<GlassesBattery> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesBattery>()
            pendingBattery = deferred
            val sent = write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_GET_BATTERY, sequence.next()))
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Battery command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestBattery timed out"))

    override suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesDeviceInfo>()
            pendingDeviceInfo = deferred
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_DEVICE_NAME, sequence.next()))
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_MODEL, sequence.next()))
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_VERSION, sequence.next()))
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_HARDWARE, sequence.next()))
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_PRODUCT_INFO, sequence.next()))
            write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_SUPPORT_FEATURES, sequence.next()))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestDeviceInfo timed out"))

    override suspend fun requestMediaCounts(): Result<GlassesMediaCounts> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesMediaCounts>()
            pendingMediaCounts = deferred
            val sent = write(HscH515PacketCodec.request(HscH515PacketCodec.CMD_GET_FILE_COUNT, sequence.next()))
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Media count command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestMediaCounts timed out"))

    override suspend fun takePhoto(aiTransfer: Boolean): GlassesCommandResult = sendCommand(
        commandName = "takePhoto",
        bytes = HscH515PacketCodec.deviceControlRequest(sequence.next(), DEVICE_CONTROL_TAKE_PHOTO),
    )

    override suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startVideoRecording" else "stopVideoRecording",
        bytes = HscH515PacketCodec.deviceControlRequest(
            sequence.next(),
            if (enabled) DEVICE_CONTROL_START_VIDEO else DEVICE_CONTROL_STOP_VIDEO,
        ),
    )

    override suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startAudioRecording" else "stopAudioRecording",
        bytes = HscH515PacketCodec.localAudioControlRequest(sequence.next(), enabled),
    )

    override fun downloadMedia(options: MediaDownloadOptions): Flow<GlassesTransferEvent> = flowOf(
        GlassesTransferEvent.Failed(
            GlassesProtocolError(
                code = "NOT_IMPLEMENTED_YET",
                message = "HSC/H5-15 file import needs the Wi-Fi HTTP/upload layer from protocol v2.0.15.",
            )
        )
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        clearGattState()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = GlassesProtocolError("GATT_STATUS_$status", "HSC/H5-15 GATT status error: $status")
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT STATE_CONNECTED: ${gatt.device?.address}")
                    completeBleLinkConnected()
                    runCatching { gatt.requestMtu(247) }
                        .onFailure { Log.w(TAG, "requestMtu failed", it) }
                    mainHandler.postDelayed(
                        {
                            runCatching { gatt.discoverServices() }
                                .onFailure { error ->
                                    Log.e(TAG, "discoverServices failed", error)
                                    _events.tryEmit(
                                        GlassesEvent.Error(
                                            GlassesProtocolError(
                                                "SERVICE_DISCOVERY_START_FAILED",
                                                error.message ?: "HSC/H5-15 service discovery did not start",
                                                error,
                                            )
                                        )
                                    )
                                }
                        },
                        250L,
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    setState(GlassesConnectionState.Disconnected("gatt disconnected"))
                    clearGattState(keepGattReference = false)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status services=${gatt.services?.map { it.uuid }}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = GlassesProtocolError("SERVICE_DISCOVERY_FAILED", "HSC/H5-15 service discovery failed: $status")
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            val service = gatt.getService(HscH515PacketCodec.SERVICE_UUID)
            if (service == null) {
                val error = GlassesProtocolError("SERVICE_NOT_FOUND", "HSC/H5-15 service not found")
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            readCharacteristic = service.getCharacteristic(HscH515PacketCodec.READ_UUID)
            writeCharacteristic = service.getCharacteristic(HscH515PacketCodec.WRITE_UUID)

            if (readCharacteristic == null || writeCharacteristic == null) {
                val error = GlassesProtocolError("CHARACTERISTICS_NOT_FOUND", "HSC/H5-15 read/write characteristics not found")
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                connectDeferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            enableNotify(gatt, readCharacteristic!!)
            markConnected()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicBytes(characteristic.uuid.toString(), characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onCharacteristicBytes(characteristic.uuid.toString(), value)
        }
    }

    private fun completeBleLinkConnected() {
        if (markedConnected) return
        markedConnected = true
        val device = lastDevice ?: GlassesDevice(address = "", protocolHint = id)
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
            Log.w(TAG, "Initial queries skipped: write characteristic is not ready yet")
            return
        }
        initialQueriesScheduled = true

        listOf(
            HscH515PacketCodec.timeRequest(sequence.next()),
            HscH515PacketCodec.request(HscH515PacketCodec.CMD_GET_BATTERY, sequence.next()),
            HscH515PacketCodec.request(HscH515PacketCodec.CMD_GET_FILE_COUNT, sequence.next()),
            HscH515PacketCodec.request(HscH515PacketCodec.CMD_DEVICE_NAME, sequence.next()),
            HscH515PacketCodec.request(HscH515PacketCodec.CMD_VERSION, sequence.next()),
            HscH515PacketCodec.request(HscH515PacketCodec.CMD_SUPPORT_FEATURES, sequence.next()),
        ).forEachIndexed { index, bytes ->
            mainHandler.postDelayed(
                {
                    if (_connectionState.value is GlassesConnectionState.Connected) write(bytes)
                },
                500L + index * 120L,
            )
        }
    }

    private fun onCharacteristicBytes(uuid: String, bytes: ByteArray) {
        _events.tryEmit(GlassesEvent.RawPacket(bytes))
        if (!uuid.equals(HscH515PacketCodec.READ_UUID.toString(), ignoreCase = true)) return

        val packets = frameDecoder.append(bytes)
        if (packets.isEmpty()) {
            Log.d(TAG, "Buffered HSC/H5-15 chunk: ${HscH515PacketCodec.toHex(bytes)}")
        }
        packets.forEach(::handlePacket)
    }

    private fun handlePacket(packet: HscH515PacketCodec.Packet) {
        if (!packet.crcValid) {
            Log.w(TAG, "Ignoring packet with bad CRC: cmd=0x${packet.commandId.toString(16)}")
            return
        }

        if (packet.type == HscH515PacketCodec.TYPE_REQUEST || packet.type == HscH515PacketCodec.TYPE_NOTIFY) {
            write(HscH515PacketCodec.ackFor(packet))
        }

        when (packet.commandId) {
            HscH515PacketCodec.CMD_GET_BATTERY,
            HscH515PacketCodec.CMD_BATTERY_NOTIFY -> {
                val parsed = HscH515PacketCodec.parseBattery(packet.payload) ?: return
                val battery = GlassesBattery(parsed.first, parsed.second)
                pendingBattery?.complete(battery)
                pendingBattery = null
                _events.tryEmit(GlassesEvent.BatteryChanged(battery))
            }

            HscH515PacketCodec.CMD_GET_FILE_COUNT,
            HscH515PacketCodec.CMD_FILE_COUNT_NOTIFY -> {
                val count = HscH515PacketCodec.parseFileCount(packet.payload) ?: return
                val counts = GlassesMediaCounts(photos = count)
                pendingMediaCounts?.complete(counts)
                pendingMediaCounts = null
                _events.tryEmit(GlassesEvent.MediaCountsChanged(counts))
            }

            HscH515PacketCodec.CMD_PRODUCT_INFO -> {
                cachedProductInfo = HscH515PacketCodec.parseProductInfo(packet.payload)
                completePendingDeviceInfoIfPossible(force = false)
            }

            HscH515PacketCodec.CMD_SUPPORT_FEATURES -> {
                cachedSupportFeatures = HscH515PacketCodec.parseSupportFeatures(packet.payload)
                completePendingDeviceInfoIfPossible(force = false)
            }

            HscH515PacketCodec.CMD_MODEL -> {
                cachedModel = HscH515PacketCodec.parseStringPayload(packet.payload)
                completePendingDeviceInfoIfPossible(force = false)
            }

            HscH515PacketCodec.CMD_VERSION -> {
                cachedFirmware = HscH515PacketCodec.parseVersionPayload(packet.payload)
                completePendingDeviceInfoIfPossible(force = true)
            }

            HscH515PacketCodec.CMD_HARDWARE -> {
                cachedHardware = HscH515PacketCodec.parseStringPayload(packet.payload)
                completePendingDeviceInfoIfPossible(force = false)
            }

            HscH515PacketCodec.CMD_DEVICE_NAME -> {
                cachedDeviceName = HscH515PacketCodec.parseStringPayload(packet.payload)
                completePendingDeviceInfoIfPossible(force = false)
            }

            HscH515PacketCodec.CMD_VIDEO_STATE_NOTIFY -> {
                HscH515PacketCodec.parseVideoState(packet.payload)?.let { enabled ->
                    val button = if (enabled) GlassesEvent.Button.VIDEO else GlassesEvent.Button.UNKNOWN
                    _events.tryEmit(GlassesEvent.ButtonPressed(button, packet.commandId))
                }
            }

            HscH515PacketCodec.CMD_LOCAL_AUDIO_STATE_NOTIFY -> {
                HscH515PacketCodec.parseAudioState(packet.payload)?.let { enabled ->
                    if (enabled) _events.tryEmit(GlassesEvent.ButtonPressed(GlassesEvent.Button.AUDIO, packet.commandId))
                }
            }
        }
    }

    private fun completePendingDeviceInfoIfPossible(force: Boolean) {
        val pending = pendingDeviceInfo ?: return
        if (!force && cachedDeviceName == null && cachedModel == null && cachedFirmware == null && cachedHardware == null && cachedProductInfo == null && cachedSupportFeatures == null) {
            return
        }

        val productInfo = cachedProductInfo
        val info = GlassesDeviceInfo(
            hardwareVersion = cachedHardware,
            firmwareVersion = cachedFirmware,
            bluetoothFirmwareVersion = cachedFirmware,
            wifiFirmwareVersion = cachedFirmware,
            raw = linkedMapOf<String, String>().apply {
                cachedDeviceName?.let { put("deviceName", it) }
                cachedModel?.let { put("model", it) }
                cachedFirmware?.let { put("firmware", it) }
                cachedHardware?.let { put("hardware", it) }
                productInfo?.let {
                    put("customerId", it.customerId.toString())
                    put("productId", it.productId.toString())
                    put("color", it.color.toString())
                }
                cachedSupportFeatures?.forEach { (name, supported) ->
                    put("feature.$name", supported.toString())
                }
            },
        )
        pending.complete(info)
        pendingDeviceInfo = null
        _events.tryEmit(GlassesEvent.DeviceInfoChanged(info))
    }

    private suspend fun sendCommand(commandName: String, bytes: ByteArray): GlassesCommandResult {
        return if (write(bytes)) {
            GlassesCommandResult.Accepted
        } else {
            GlassesCommandResult.Rejected(
                GlassesProtocolError("WRITE_FAILED", "$commandName write failed for HSC/H5-15")
            )
        }
    }

    private fun write(bytes: ByteArray): Boolean {
        if (!hasBleConnectPermission()) return false
        val activeGatt = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false

        return try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.value = bytes
                @Suppress("DEPRECATION") activeGatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasBleConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(HscH515PacketCodec.CCCD_UUID) ?: return
        val enableValue = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, enableValue)
        } else {
            @Suppress("DEPRECATION") descriptor.value = enableValue
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    private fun setState(state: GlassesConnectionState) {
        _connectionState.value = state
        _events.tryEmit(GlassesEvent.ConnectionChanged(state))
    }

    private fun BluetoothDevice.toGlassesDevice(rssi: Int?, serviceUuids: List<String>): GlassesDevice? {
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
            protocolHint = id,
        )
    }

    private fun matchesFilter(device: GlassesDevice, filter: GlassesScanFilter): Boolean {
        if (filter.protocolId != null && filter.protocolId != id) return false
        val actual = device.serviceUuids.map { it.lowercase() }.toSet()
        val hasHscService = HscH515PacketCodec.SERVICE_UUID.toString().lowercase() in actual
        if (hasHscService) return true

        val lowerName = device.name?.lowercase().orEmpty()
        return lowerName.contains("hsc") ||
            lowerName.contains("h5") ||
            lowerName.contains("h15") ||
            lowerName.contains("hy15") ||
            lowerName.contains("h5-15")
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBleConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun Throwable.toProtocolError(code: String, fallbackMessage: String): GlassesProtocolError =
        GlassesProtocolError(
            code = code,
            message = message ?: fallbackMessage,
            cause = this,
        )

    private fun clearGattState(keepGattReference: Boolean = true) {
        if (!keepGattReference) gatt = null
        markedConnected = false
        initialQueriesScheduled = false
        connectDeferred = null
        pendingBattery = null
        pendingDeviceInfo = null
        pendingMediaCounts = null
        writeCharacteristic = null
        readCharacteristic = null
        frameDecoder.clear()
    }

    companion object {
        private const val TAG = "HscH515Protocol"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L

        private const val DEVICE_CONTROL_TAKE_PHOTO = 8
        private const val DEVICE_CONTROL_START_VIDEO = 9
        private const val DEVICE_CONTROL_STOP_VIDEO = 10
    }
}
