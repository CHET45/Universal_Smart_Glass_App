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
import com.fersaiyan.cyanbridge.protocol.GlassesAction
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

    override suspend fun beforeAction(action: GlassesAction): GlassesCommandResult {
        // HSC/H5-15 must not receive the legacy "stop audio" command before unrelated actions.
        // MainActivity calls this hook before Battery/Photo/Video/Version/Time; for HSC it is a no-op.
        return GlassesCommandResult.Accepted
    }

    override fun shouldStopAudioBeforeAction(action: GlassesAction): Boolean = false

    override fun supportsAction(action: GlassesAction): Boolean {
        return when (action) {
            GlassesAction.VOLUME -> false
            else -> true
        }
    }
    private var isLargeMtu = false
    private var heartbeatRunnable: Runnable? = null
    private var awaitingPong = false
    private var failedPing = 0

    private val writeLock = Any()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    private var writeWatchdog: Runnable? = null
    private val writeWatchdogDelayMs = 700L

    private val _connectionState =
        MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    override val connectionState: StateFlow<GlassesConnectionState> = _connectionState

    private val _events = MutableSharedFlow<GlassesEvent>(
        replay = 16,
        extraBufferCapacity = 64,
    )
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
    private var serviceDiscoveryStarted = false
    private var serviceDiscoveryAttempts = 0

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
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun finishCurrentWriteAndDrain(reason: String) {
        writeWatchdog?.let { mainHandler.removeCallbacks(it) }
        writeWatchdog = null

        synchronized(writeLock) {
            writeInProgress = false
        }

        Log.d(TAG, "TX complete/drain: $reason")

        mainHandler.post {
            drainWriteQueue()
        }
    }
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
                    "HSC/H5-15 BLE scan failed: $errorCode"
                )
                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                close(IllegalStateException(error.message))
            }
        }

        val filters = emptyList<ScanFilter>()
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
                "Failed to start HSC/H5-15 scan"
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

        // Android BLE keeps a lot of state inside BluetoothGatt. After status=62/133 or
        // a timeout, reusing the previous object often gives a fake "connected" state
        // with no working protocol traffic. Always start from a closed GATT.
        forceCloseGatt()
        clearGattState(keepGattReference = false)
        delay(CONNECT_RETRY_SETTLE_MS)

        lastDevice = device.copy(protocolHint = id)
        cachedDeviceName = device.name
        cachedModel = null
        cachedFirmware = null
        cachedHardware = null
        cachedProductInfo = null
        cachedSupportFeatures = null
        markedConnected = false
        initialQueriesScheduled = false
        serviceDiscoveryStarted = false
        serviceDiscoveryAttempts = 0
        frameDecoder.clear()
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
            BluetoothDevice.TRANSPORT_LE,
        )

        if (gatt == null) {
            connectDeferred = null
            throw IllegalStateException("connectGatt returned null for ${device.address}")
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
        } catch (error: Throwable) {
            val protocolError = error.toProtocolError(
                "CONNECT_TIMEOUT_OR_FAILED",
                "HSC/H5-15 connect timed out or failed"
            )
            Log.w(TAG, protocolError.message, error)
            forceCloseGatt()
            clearGattState(keepGattReference = false)
            setState(GlassesConnectionState.Failed(protocolError))
            throw error
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun disconnect() {
        setState(GlassesConnectionState.Disconnecting)
        forceCloseGatt()
        clearGattState(keepGattReference = false)
        setState(GlassesConnectionState.Disconnected("disconnect requested"))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    override suspend fun syncTime(): GlassesCommandResult = sendCommand(
        commandName = "syncTime",
        bytes = HscH515PacketCodec.timeRequest(sequence.next()),
    )


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun requestBattery(): Result<GlassesBattery> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesBattery>()
            pendingBattery = deferred
            val sent = write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_GET_BATTERY,
                    sequence.next()
                )
            )
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Battery command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestBattery timed out"))


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun requestDeviceInfo(): Result<GlassesDeviceInfo> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesDeviceInfo>()
            pendingDeviceInfo = deferred
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_DEVICE_NAME,
                    sequence.next()
                )
            )
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_MODEL,
                    sequence.next()
                )
            )
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_VERSION,
                    sequence.next()
                )
            )
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_HARDWARE,
                    sequence.next()
                )
            )
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_PRODUCT_INFO,
                    sequence.next()
                )
            )
            write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_SUPPORT_FEATURES,
                    sequence.next()
                )
            )
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestDeviceInfo timed out"))


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun requestMediaCounts(): Result<GlassesMediaCounts> =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val deferred = CompletableDeferred<GlassesMediaCounts>()
            pendingMediaCounts = deferred
            val sent = write(
                HscH515PacketCodec.request(
                    HscH515PacketCodec.CMD_GET_FILE_COUNT,
                    sequence.next()
                )
            )
            if (!sent) return@withTimeoutOrNull Result.failure(IllegalStateException("Media count command was not written"))
            Result.success(deferred.await())
        } ?: Result.failure(IllegalStateException("requestMediaCounts timed out"))

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    override suspend fun takePhoto(aiTransfer: Boolean): GlassesCommandResult = sendCommand(
        commandName = "takePhoto",
        bytes = HscH515PacketCodec.deviceControlRequest(
            sequence.next(),
            DEVICE_CONTROL_TAKE_PHOTO
        ),
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    override suspend fun setVideoRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startVideoRecording" else "stopVideoRecording",
        bytes = HscH515PacketCodec.deviceControlRequest(
            sequence.next(),
            if (enabled) DEVICE_CONTROL_START_VIDEO else DEVICE_CONTROL_STOP_VIDEO,
        ),
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    override suspend fun setAudioRecording(enabled: Boolean): GlassesCommandResult = sendCommand(
        commandName = if (enabled) "startAudioRecording" else "stopAudioRecording",
        bytes = HscH515PacketCodec.localAudioControlRequest(
            sequence.next(),
            enabled
        ),
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
        forceCloseGatt()
        clearGattState(keepGattReference = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val deferred = connectDeferred
                val error = GlassesProtocolError(
                    "GATT_STATUS_$status",
                    "HSC/H5-15 GATT status error: $status"
                )

                Log.w(TAG, "GATT state change failed: status=$status newState=$newState")

                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                clearGattState(keepGattReference = false)

                _events.tryEmit(GlassesEvent.Error(error))
                setState(GlassesConnectionState.Failed(error))
                deferred?.completeExceptionally(IllegalStateException(error.message))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(
                        TAG,
                        "GATT STATE_CONNECTED: ${gatt.device?.address}"
                    )
                    // Match aivox reference: request MTU 500 and HIGH connection priority
                    // first; service discovery starts in onMtuChanged.
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    val mtuRequested = runCatching { gatt.requestMtu(500) }.getOrDefault(false)
                    Log.i(TAG, "requestMtu(500) started=$mtuRequested")
                    if (!mtuRequested) {
                        // Fall back to the original delayed path if MTU request fails to start.
                        startServiceDiscovery(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val deferred = connectDeferred
                    val wasConnected = markedConnected

                    runCatching { gatt.close() }
                    clearGattState(keepGattReference = false)
                    setState(GlassesConnectionState.Disconnected("gatt disconnected"))

                    if (!wasConnected) {
                        deferred?.completeExceptionally(
                            IllegalStateException("HSC/H5-15 disconnected before GATT protocol was ready")
                        )
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 500) {
                isLargeMtu = true
                Log.i(TAG, "Large MTU ($mtu) negotiated successfully")
            }
            // Proceed to service discovery regardless of MTU outcome (matching aivox behaviour).
            startServiceDiscovery(gatt)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != HscH515PacketCodec.WRITE_UUID) {
                return
            }

            Log.d(
                TAG,
                "onCharacteristicWrite status=$status uuid=${characteristic.uuid}"
            )

            finishCurrentWriteAndDrain("onCharacteristicWrite status=$status")
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
                failProtocolReady(
                    "SERVICE_DISCOVERY_FAILED",
                    "HSC/H5-15 service discovery failed: $status"
                )
                return
            }

            logGattTree(gatt)

            val service = gatt.getService(HscH515PacketCodec.SERVICE_UUID)
            if (service == null) {
                failProtocolReady(
                    "SERVICE_NOT_FOUND",
                    "HSC/H5-15 service not found"
                )
                return
            }

            readCharacteristic = service.getCharacteristic(HscH515PacketCodec.READ_UUID)
            writeCharacteristic = service.getCharacteristic(HscH515PacketCodec.WRITE_UUID)

            if (readCharacteristic == null || writeCharacteristic == null) {
                failProtocolReady(
                    "CHARACTERISTICS_NOT_FOUND",
                    "HSC/H5-15 read/write characteristics not found"
                )
                return
            }

            if (!enableNotify(
                    gatt,
                    readCharacteristic!!
                )
            ) {
                failProtocolReady(
                    "ENABLE_NOTIFY_FAILED",
                    "Failed to enable HSC/H5-15 read notifications"
                )
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.characteristic.uuid != HscH515PacketCodec.READ_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                markConnected()
            } else {
                failProtocolReady(
                    "DESCRIPTOR_WRITE_FAILED",
                    "HSC/H5-15 descriptor write failed: $status"
                )
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startServiceDiscovery(
        gatt: BluetoothGatt,
        delayMillis: Long = 250L,
    ) {
        mainHandler.postDelayed(
            {
                if (!hasBleConnectPermission()) {
                    failProtocolReady(
                        "MISSING_PERMISSION",
                        "Missing BLE connect permission during HSC/H5-15 service discovery"
                    )
                    return@postDelayed
                }

                if (serviceDiscoveryStarted) return@postDelayed

                serviceDiscoveryAttempts += 1
                var failure: Throwable? = null
                val started = try {
                    gatt.discoverServices()
                } catch (error: Throwable) {
                    failure = error
                    false
                }

                Log.i(
                    TAG,
                    "discoverServices attempt=$serviceDiscoveryAttempts started=$started"
                )

                if (started) {
                    serviceDiscoveryStarted = true
                    return@postDelayed
                }

                if (serviceDiscoveryAttempts < SERVICE_DISCOVERY_MAX_ATTEMPTS) {
                    startServiceDiscovery(
                        gatt,
                        SERVICE_DISCOVERY_RETRY_DELAY_MS
                    )
                } else {
                    failProtocolReady(
                        "SERVICE_DISCOVERY_START_FAILED",
                        failure?.message ?: "HSC/H5-15 service discovery did not start",
                        failure,
                    )
                }
            },
            delayMillis,
        )
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
        connectDeferred = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun markConnected() {
        completeBleLinkConnected()
        scheduleInitialQueriesIfReady()
        scheduleHeartbeat()
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

        listOf(
            HscH515PacketCodec.timeRequest(sequence.next()),
            HscH515PacketCodec.request(
                HscH515PacketCodec.CMD_GET_BATTERY,
                sequence.next()
            ),
            HscH515PacketCodec.request(
                HscH515PacketCodec.CMD_GET_FILE_COUNT,
                sequence.next()
            ),
            HscH515PacketCodec.request(
                HscH515PacketCodec.CMD_DEVICE_NAME,
                sequence.next()
            ),
            HscH515PacketCodec.request(
                HscH515PacketCodec.CMD_VERSION,
                sequence.next()
            ),
            HscH515PacketCodec.request(
                HscH515PacketCodec.CMD_SUPPORT_FEATURES,
                sequence.next()
            ),
        ).forEachIndexed { index, bytes ->
            mainHandler.postDelayed(
                {
                    if (_connectionState.value is GlassesConnectionState.Connected) write(bytes)
                },
                500L + index * 120L,
            )
        }
    }

    private fun onCharacteristicBytes(
        uuid: String,
        bytes: ByteArray
    ) {
        Log.d(TAG, "RX $uuid: ${HscH515PacketCodec.toHex(bytes)}")
        _events.tryEmit(GlassesEvent.RawPacket(bytes))
        if (!uuid.equals(
                HscH515PacketCodec.READ_UUID.toString(),
                ignoreCase = true
            )
        ) return

        val packets = frameDecoder.append(bytes)
        if (packets.isEmpty()) {
            Log.d(
                TAG,
                "Buffered HSC/H5-15 chunk: ${HscH515PacketCodec.toHex(bytes)}"
            )
        }
        packets.forEach(::handlePacket)
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handlePacket(packet: HscH515PacketCodec.Packet) {
        Log.d(
            TAG,
            "RX packet cmd=0x${packet.commandId.toString(16)} type=${packet.type} seq=${packet.sequence} len=${packet.payload.size} crc=${packet.crcValid}"
        )

        if (!packet.crcValid) {
            Log.w(
                TAG,
                "Ignoring packet with bad CRC: cmd=0x${packet.commandId.toString(16)}"
            )
            return
        }

        if (packet.type == HscH515PacketCodec.TYPE_REQUEST || packet.type == HscH515PacketCodec.TYPE_NOTIFY) {
            write(HscH515PacketCodec.ackFor(packet))
        }

        when (packet.commandId) {
            HscH515PacketCodec.CMD_HEARTBEAT -> {
                // Pong received from device.
                awaitingPong = false
                failedPing = 0
            }

            HscH515PacketCodec.CMD_GET_BATTERY, HscH515PacketCodec.CMD_BATTERY_NOTIFY -> {
                val parsed = HscH515PacketCodec.parseBattery(packet.payload) ?: return
                val battery = GlassesBattery(
                    parsed.first,
                    parsed.second
                )
                pendingBattery?.complete(battery)
                pendingBattery = null
                _events.tryEmit(GlassesEvent.BatteryChanged(battery))
            }

            HscH515PacketCodec.CMD_GET_FILE_COUNT, HscH515PacketCodec.CMD_FILE_COUNT_NOTIFY -> {
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

            HscH515PacketCodec.CMD_DEVICE_CONTROL -> handleDeviceControlPacket(packet)

            HscH515PacketCodec.CMD_DEVICE_AI_MODE,
            HscH515PacketCodec.CMD_AI_MODE_EVENT_TRIGGER,
            HscH515PacketCodec.CMD_AI_MODE_VOICE_EVENT_TRIGGER -> {
                if (isIncomingDeviceEvent(packet) && HscH515PacketCodec.parseAiTrigger(packet.payload)) {
                    emitButton(GlassesEvent.Button.AI, packet.commandId)
                }
            }

            HscH515PacketCodec.CMD_VIDEO_STATE_NOTIFY -> {
                if (!isIncomingDeviceEvent(packet)) return
                HscH515PacketCodec.parseVideoState(packet.payload)
                    ?.takeIf { it }
                    ?.let { emitButton(GlassesEvent.Button.VIDEO, packet.commandId) }
            }

            HscH515PacketCodec.CMD_LOCAL_AUDIO_STATE_NOTIFY -> {
                if (!isIncomingDeviceEvent(packet)) return
                HscH515PacketCodec.parseAudioState(packet.payload)
                    ?.takeIf { it }
                    ?.let { emitButton(GlassesEvent.Button.AUDIO, packet.commandId) }
            }
        }
    }

    private fun handleDeviceControlPacket(packet: HscH515PacketCodec.Packet) {
        if (!isIncomingDeviceEvent(packet)) return
        when (HscH515PacketCodec.parseDeviceControl(packet.payload)) {
            DEVICE_CONTROL_TAKE_PHOTO -> emitButton(GlassesEvent.Button.PHOTO, packet.commandId)
            DEVICE_CONTROL_START_VIDEO -> emitButton(GlassesEvent.Button.VIDEO, packet.commandId)
            DEVICE_CONTROL_STOP_VIDEO -> Unit
            else -> Unit
        }
    }

    private fun isIncomingDeviceEvent(packet: HscH515PacketCodec.Packet): Boolean {
        return packet.type == HscH515PacketCodec.TYPE_REQUEST ||
                packet.type == HscH515PacketCodec.TYPE_NOTIFY
    }

    private fun emitButton(button: GlassesEvent.Button, sourceCommand: Int) {
        _events.tryEmit(
            GlassesEvent.ButtonPressed(
                button,
                sourceCommand,
            )
        )
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
                cachedDeviceName?.let {
                    put(
                        "deviceName",
                        it
                    )
                }
                cachedModel?.let {
                    put(
                        "model",
                        it
                    )
                }
                cachedFirmware?.let {
                    put(
                        "firmware",
                        it
                    )
                }
                cachedHardware?.let {
                    put(
                        "hardware",
                        it
                    )
                }
                productInfo?.let {
                    put(
                        "customerId",
                        it.customerId.toString()
                    )
                    put(
                        "productId",
                        it.productId.toString()
                    )
                    put(
                        "color",
                        it.color.toString()
                    )
                }
                cachedSupportFeatures?.forEach { (name, supported) ->
                    put(
                        "feature.$name",
                        supported.toString()
                    )
                }
            },
        )
        pending.complete(info)
        pendingDeviceInfo = null
        _events.tryEmit(GlassesEvent.DeviceInfoChanged(info))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun failProtocolReady(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) {
        val deferred = connectDeferred
        val error = GlassesProtocolError(
            code,
            message,
            cause
        )

        Log.w(TAG, "$code: $message", cause)

        forceCloseGatt()
        clearGattState(keepGattReference = false)

        _events.tryEmit(GlassesEvent.Error(error))
        setState(GlassesConnectionState.Failed(error))
        deferred?.completeExceptionally(
            IllegalStateException(
                message,
                cause
            )
        )
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
                    "$commandName write failed for HSC/H5-15"
                )
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun write(bytes: ByteArray): Boolean {
        if (!hasBleConnectPermission()) return false
        if (gatt == null || writeCharacteristic == null) return false

        Log.d(TAG, "TX enqueue: ${HscH515PacketCodec.toHex(bytes)}")

        synchronized(writeLock) {
            writeQueue.addLast(bytes)
        }

        mainHandler.post {
            drainWriteQueue()
        }

        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun drainWriteQueue() {
        if (!hasBleConnectPermission()) return

        val activeGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return

        val bytes = synchronized(writeLock) {
            if (writeInProgress) return
            if (writeQueue.isEmpty()) return
            val next = writeQueue.removeFirst()
            writeInProgress = true
            next
        }

        Log.d(TAG, "TX write: ${HscH515PacketCodec.toHex(bytes)}")

        val started = try {
            val props = characteristic.properties
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val canWriteNoResponse =
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

            val writeType = when {
                canWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                canWriteNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> {
                    Log.e(TAG, "WRITE characteristic has no write property: props=$props")
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            }

            characteristic.writeType = writeType

            Log.d(TAG, "TX writeType=$writeType props=$props")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    writeType,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.value = bytes

                @Suppress("DEPRECATION") activeGatt.writeCharacteristic(characteristic)
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "GATT write start failed",
                error
            )
            false
        }
        if (started) {
            val watchdog = Runnable {
                Log.w(TAG, "TX watchdog fired; advancing write queue without onCharacteristicWrite")
                finishCurrentWriteAndDrain("watchdog")
            }

            writeWatchdog = watchdog
            mainHandler.postDelayed(
                watchdog,
                writeWatchdogDelayMs
            )
        }
        if (!started) {
            synchronized(writeLock) {
                writeInProgress = false
                writeQueue.addFirst(bytes)
            }

            mainHandler.postDelayed(
                { drainWriteQueue() },
                WRITE_RETRY_DELAY_MS
            )
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotify(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasBleConnectPermission()) return false
        val localEnabled = gatt.setCharacteristicNotification(
            characteristic,
            true
        )
        if (!localEnabled) return false

        val descriptor = characteristic.getDescriptor(HscH515PacketCodec.CCCD_UUID) ?: return false
        val enableValue =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                enableValue
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") descriptor.value = enableValue
            @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun forceCloseGatt() {
        writeWatchdog?.let { mainHandler.removeCallbacks(it) }
        writeWatchdog = null

        val activeGatt = gatt
        if (activeGatt != null) {
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
    }

    private fun logGattTree(gatt: BluetoothGatt) {
        val services = gatt.services.orEmpty()
        if (services.isEmpty()) {
            Log.w(TAG, "GATT tree is empty")
            return
        }

        services.forEach { service ->
            Log.i(TAG, "GATT service=${service.uuid}")
            service.characteristics.orEmpty().forEach { characteristic ->
                Log.i(
                    TAG,
                    "  characteristic=${characteristic.uuid} props=${characteristic.properties} descriptors=${characteristic.descriptors?.map { it.uuid }}"
                )
            }
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
            protocolHint = id,
        )
    }

    private fun matchesFilter(
        device: GlassesDevice,
        filter: GlassesScanFilter
    ): Boolean {
        if (filter.protocolId != null && filter.protocolId != id) return false
        val actual = device.serviceUuids.map { it.lowercase() }
            .toSet()
        val hasHscService = HscH515PacketCodec.SERVICE_UUID.toString()
            .lowercase() in actual
        if (hasHscService) return true

        val lowerName = device.name?.lowercase()
            .orEmpty()
        return lowerName.contains("hsc") || lowerName.contains("h5") || lowerName.contains("h15") || lowerName.contains("hy15") || lowerName.contains("h5-15")
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
        cause = this,
    )

    private fun clearGattState(keepGattReference: Boolean = true) {
        if (!keepGattReference) gatt = null
        markedConnected = false
        initialQueriesScheduled = false
        serviceDiscoveryStarted = false
        serviceDiscoveryAttempts = 0
        connectDeferred = null
        pendingBattery = null
        pendingDeviceInfo = null
        pendingMediaCounts = null
        writeCharacteristic = null
        readCharacteristic = null
        frameDecoder.clear()
        writeWatchdog?.let { mainHandler.removeCallbacks(it) }
        writeWatchdog = null
        isLargeMtu = false
        awaitingPong = false
        failedPing = 0
        stopHeartbeat()
        synchronized(writeLock) {
            writeQueue.clear()
            writeInProgress = false
        }
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun run() {
                if (_connectionState.value !is GlassesConnectionState.Connected) return

                if (failedPing >= HEARTBEAT_MAX_MISSED) {
                    Log.w(TAG, "Heartbeat: $failedPing missed pongs — disconnecting")
                    forceCloseGatt()
                    clearGattState(keepGattReference = false)
                    setState(GlassesConnectionState.Disconnected("heartbeat timeout"))
                    return
                }

                if (awaitingPong) {
                    failedPing++
                    Log.w(TAG, "Heartbeat: missed pong ($failedPing/$HEARTBEAT_MAX_MISSED)")
                }

                awaitingPong = true
                write(HscH515PacketCodec.heartbeat(sequence.next()))
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        mainHandler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "HscH515Protocol"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val SERVICE_DISCOVERY_MAX_ATTEMPTS = 8
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 500L
        private const val CONNECT_RETRY_SETTLE_MS = 700L
        private const val WRITE_RETRY_DELAY_MS = 150L
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val HEARTBEAT_MAX_MISSED = 3

        private const val DEVICE_CONTROL_TAKE_PHOTO = 8
        private const val DEVICE_CONTROL_START_VIDEO = 9
        private const val DEVICE_CONTROL_STOP_VIDEO = 10
    }
}
