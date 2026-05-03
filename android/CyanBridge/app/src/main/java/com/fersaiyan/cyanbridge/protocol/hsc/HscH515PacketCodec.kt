package com.fersaiyan.cyanbridge.protocol.hsc

import java.nio.charset.Charset
import java.util.UUID

/**
 * HSC / H5-15 BLE packet codec based on communication protocol v2.0.15.
 *
 * Outer BLE frame:
 *   A5 | dataLength LE16 | commandData | crc16 LE16
 *
 * commandData:
 *   commandId LE16 | type | seq | payloadLength LE16 | payload
 *
 * The v2.0.15 document defines the CRC as the CRC-16 of commandData. The outer
 * frame prefix and length bytes are not included in the checksum.
 */
object HscH515PacketCodec {
    val SERVICE_UUID: UUID = UUID.fromString("01000100-0000-2000-8000-009078563412")
    val READ_UUID: UUID = UUID.fromString("02000200-0000-2000-8000-009178563412")
    val WRITE_UUID: UUID = UUID.fromString("03000300-0000-2000-8000-009278563412")
    val SPP_UUID: UUID = UUID.fromString("48534300-0000-2000-8000-0058494F4E47")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TYPE_REQUEST: Int = 1
    const val TYPE_RESPONSE: Int = 2
    const val TYPE_NOTIFY: Int = 3

    const val CMD_PRODUCT_INFO: Int = 0x0001
    const val CMD_MODEL: Int = 0x0002
    const val CMD_VERSION: Int = 0x0003
    const val CMD_HARDWARE: Int = 0x0004
    const val CMD_SUPPORT_FEATURES: Int = 0x0005
    const val CMD_DEVICE_NAME: Int = 0x0006
    const val CMD_HEARTBEAT: Int = 0x0007

    const val CMD_GET_BATTERY: Int = 0x0101
    const val CMD_BATTERY_NOTIFY: Int = 0x0102

    const val CMD_SET_TIME: Int = 0x0903
    const val CMD_CONNECTION_STATE_NOTIFY: Int = 0x0904
    const val CMD_FILE_COUNT_NOTIFY: Int = 0x0905
    const val CMD_PHOTO_START_NOTIFY: Int = 0x0906
    const val CMD_PHOTO_FINISH_NOTIFY: Int = 0x0907
    const val CMD_DISK_CAPACITY: Int = 0x0909
    const val CMD_VIDEO_PREVIEW_CONTROL: Int = 0x090A
    const val CMD_WIFI_AP_CONTROL: Int = 0x090B
    const val CMD_WIFI_AP_SSID_NOTIFY: Int = 0x090C
    const val CMD_WIFI_AP_PASSWORD_NOTIFY: Int = 0x090D
    const val CMD_WIFI_OPERATION_NOTIFY: Int = 0x090E
    const val CMD_WIFI_RUNNING_STATE_NOTIFY: Int = 0x0913
    const val CMD_GET_FILE_COUNT: Int = 0x0916
    const val CMD_WIFI_AP_MAC_NOTIFY: Int = 0x0917
    const val CMD_WIFI_P2P_SUPPORT_NOTIFY: Int = 0x0918
    const val CMD_WIFI_P2P_CONTROL: Int = 0x0919
    const val CMD_WIFI_P2P_NAME_NOTIFY: Int = 0x091A
    const val CMD_WIFI_P2P_MAC_NOTIFY: Int = 0x091B

    const val CMD_DEVICE_CONTROL: Int = 0x0D01
    const val CMD_VIDEO_STATE_NOTIFY: Int = 0x0D02

    const val CMD_LOCAL_AUDIO_FILE_INFO: Int = 0x0E01
    const val CMD_LOCAL_AUDIO_DELETE_FILE: Int = 0x0E02
    const val CMD_LOCAL_AUDIO_DELETE_ALL: Int = 0x0E03
    const val CMD_LOCAL_AUDIO_CONTROL: Int = 0x0E04
    const val CMD_LOCAL_AUDIO_STATE_NOTIFY: Int = 0x0E05
    const val CMD_LOCAL_AUDIO_PROMPT_SETTING: Int = 0x0E06
    const val CMD_LOCAL_AUDIO_PROMPT_STATE: Int = 0x0E07
    const val CMD_LOCAL_AUDIO_FILE_COUNT_NOTIFY: Int = 0x0E08

    const val DEVICE_CONTROL_PLAY_MUSIC: Int = 1
    const val DEVICE_CONTROL_PAUSE_MUSIC: Int = 2
    const val DEVICE_CONTROL_PREVIOUS_TRACK: Int = 3
    const val DEVICE_CONTROL_NEXT_TRACK: Int = 4
    const val DEVICE_CONTROL_VOLUME_UP: Int = 5
    const val DEVICE_CONTROL_VOLUME_DOWN: Int = 6
    const val DEVICE_CONTROL_STOP_AI: Int = 7
    const val DEVICE_CONTROL_TAKE_PHOTO: Int = 8
    const val DEVICE_CONTROL_START_VIDEO: Int = 9
    const val DEVICE_CONTROL_STOP_VIDEO: Int = 10

    private const val FRAME_MAGIC: Int = 0xA5
    private const val MIN_COMMAND_DATA_LENGTH: Int = 6
    private const val MAX_COMMAND_DATA_LENGTH: Int = 4096
    private val UTF8: Charset = Charsets.UTF_8

    data class Packet(
        val commandId: Int,
        val type: Int,
        val sequence: Int,
        val payload: ByteArray,
        val crcValid: Boolean,
        val raw: ByteArray,
    )

    data class ProductInfo(
        val customerId: Int,
        val productId: Int,
        val color: Int,
    )

    data class BatteryLevel(
        val percent: Int,
        val charging: Boolean,
    )

    data class BatterySnapshot(
        val displayType: Int,
        val primary: BatteryLevel?,
        val right: BatteryLevel?,
        val caseBattery: BatteryLevel?,
    )

    data class ConnectionState(
        val connected: Boolean,
        val ipAddress: String?,
        val ssid: String?,
    )

    class FrameDecoder {
        private var buffer: ByteArray = ByteArray(0)

        fun append(chunk: ByteArray): List<Packet> {
            if (chunk.isEmpty()) return emptyList()
            buffer += chunk
            val packets = mutableListOf<Packet>()

            while (buffer.isNotEmpty()) {
                val magicIndex = buffer.indexOfFirst { (it.toInt() and 0xFF) == FRAME_MAGIC }
                if (magicIndex < 0) {
                    buffer = ByteArray(0)
                    break
                }
                if (magicIndex > 0) buffer = buffer.copyOfRange(magicIndex, buffer.size)
                if (buffer.size < 3) break

                val dataLength = readLe16(buffer, 1)
                if (dataLength < MIN_COMMAND_DATA_LENGTH || dataLength > MAX_COMMAND_DATA_LENGTH) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }

                val frameSize = 1 + 2 + dataLength + 2
                if (buffer.size < frameSize) break

                decodeFrame(buffer.copyOfRange(0, frameSize))?.let(packets::add)
                buffer = buffer.copyOfRange(frameSize, buffer.size)
            }

            return packets
        }

        fun clear() {
            buffer = ByteArray(0)
        }
    }

    class SequenceGenerator {
        private var nextSequence: Int = 0

        @Synchronized
        fun next(): Int {
            val value = nextSequence and 0xFF
            nextSequence = (nextSequence + 1) and 0xFF
            return value
        }
    }

    fun request(commandId: Int, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        frame(commandId, TYPE_REQUEST, sequence, payload)

    fun response(commandId: Int, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        frame(commandId, TYPE_RESPONSE, sequence, payload)

    fun heartbeat(sequence: Int): ByteArray = frame(CMD_HEARTBEAT, TYPE_NOTIFY, sequence, ByteArray(0))

    fun timeRequest(sequence: Int): ByteArray {
        val rtcSeconds = (System.currentTimeMillis() / 1000L).toInt()
        return request(CMD_SET_TIME, sequence, le32(rtcSeconds))
    }

    fun deviceControlRequest(sequence: Int, command: Int): ByteArray =
        request(CMD_DEVICE_CONTROL, sequence, byteArrayOf(command.toByte()))

    fun localAudioControlRequest(sequence: Int, enabled: Boolean): ByteArray =
        request(CMD_LOCAL_AUDIO_CONTROL, sequence, byteArrayOf(if (enabled) 1 else 0))

    fun wifiApControlRequest(sequence: Int, enabled: Boolean): ByteArray =
        request(CMD_WIFI_AP_CONTROL, sequence, byteArrayOf(if (enabled) 1 else 0))

    fun wifiP2pControlRequest(sequence: Int, enabled: Boolean): ByteArray =
        request(CMD_WIFI_P2P_CONTROL, sequence, byteArrayOf(if (enabled) 1 else 0))

    fun videoPreviewControlRequest(sequence: Int, enabled: Boolean): ByteArray =
        request(CMD_VIDEO_PREVIEW_CONTROL, sequence, byteArrayOf(if (enabled) 1 else 0))

    fun decodeFrame(raw: ByteArray): Packet? {
        if (raw.size < 11 || (raw[0].toInt() and 0xFF) != FRAME_MAGIC) return null
        val dataLength = readLe16(raw, 1)
        val expectedSize = 1 + 2 + dataLength + 2
        if (raw.size < expectedSize || dataLength < MIN_COMMAND_DATA_LENGTH) return null

        val dataStart = 3
        val dataEnd = dataStart + dataLength
        val data = raw.copyOfRange(dataStart, dataEnd)
        val expectedCrc = readLe16(raw, dataEnd)
        val actualCrc = crc16(data)

        val commandId = readLe16(data, 0)
        val type = data[2].toInt() and 0xFF
        val sequence = data[3].toInt() and 0xFF
        val payloadLength = readLe16(data, 4)
        if (6 + payloadLength > data.size) return null

        return Packet(
            commandId = commandId,
            type = type,
            sequence = sequence,
            payload = data.copyOfRange(6, 6 + payloadLength),
            crcValid = expectedCrc == actualCrc,
            raw = raw.copyOf(expectedSize),
        )
    }

    fun ackFor(packet: Packet): ByteArray = response(packet.commandId, packet.sequence)

    fun parseBattery(payload: ByteArray): BatterySnapshot? {
        if (payload.isEmpty()) return null
        return BatterySnapshot(
            displayType = payload[0].toInt() and 0xFF,
            primary = parseBatteryLevel(payload.getOrNull(1)),
            right = parseBatteryLevel(payload.getOrNull(2)),
            caseBattery = parseBatteryLevel(payload.getOrNull(3)),
        )
    }

    fun parseFileCount(payload: ByteArray): Int? = if (payload.size >= 2) readLe16(payload, 0) else null

    fun parseProductInfo(payload: ByteArray): ProductInfo? {
        if (payload.size < 5) return null
        return ProductInfo(readLe16(payload, 0), readLe16(payload, 2), payload[4].toInt() and 0xFF)
    }

    fun parseSupportFeatures(payload: ByteArray): Map<String, Boolean> {
        val names = listOf(
            "noiseCancellation",
            "wearDetection",
            "gameMode",
            "eq",
            "buttonSettings",
            "findDevice",
            "aiConversation",
            "wifiGlasses",
            "bleAudio",
            "otaUpgrade",
            "deviceControl",
            "localAudioRecording",
            "voiceWakeup",
        )
        return names.mapIndexedNotNull { index, name ->
            payload.getOrNull(index)?.let { name to ((it.toInt() and 0xFF) == 1) }
        }.toMap()
    }

    fun parseStringPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val end = payload.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: payload.size
        return payload.copyOfRange(0, end).toString(UTF8).trim().takeIf { it.isNotEmpty() }
    }

    fun parseVersionPayload(payload: ByteArray): String? =
        parseStringPayload(payload) ?: payload.takeIf { it.isNotEmpty() }
            ?.joinToString(".") { (it.toInt() and 0xFF).toString() }

    fun parseVideoState(payload: ByteArray): Boolean? = when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
        0 -> false
        1 -> true
        else -> null
    }

    fun parseAudioState(payload: ByteArray): Boolean? = parseVideoState(payload)

    fun parseConnectionState(payload: ByteArray): ConnectionState? {
        if (payload.isEmpty()) return null
        val connected = (payload[0].toInt() and 0xFF) == 1
        val ip = if (payload.size >= 5) {
            payload.copyOfRange(1, 5).joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else null
        val ssid = if (payload.size > 5) parseStringPayload(payload.copyOfRange(5, payload.size)) else null
        return ConnectionState(connected, ip, ssid)
    }

    fun parseMacAddress(payload: ByteArray): String? = payload.takeIf { it.size >= 6 }
        ?.take(6)
        ?.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = " ") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    private fun frame(commandId: Int, type: Int, sequence: Int, payload: ByteArray): ByteArray {
        val dataLength = 2 + 1 + 1 + 2 + payload.size
        val data = ByteArray(dataLength)
        writeLe16(data, 0, commandId)
        data[2] = type.toByte()
        data[3] = sequence.toByte()
        writeLe16(data, 4, payload.size)
        payload.copyInto(data, 6)

        return ByteArray(1 + 2 + dataLength + 2).also { out ->
            out[0] = FRAME_MAGIC.toByte()
            writeLe16(out, 1, dataLength)
            data.copyInto(out, 3)
            writeLe16(out, 3 + dataLength, crc16(data))
        }
    }

    private fun parseBatteryLevel(raw: Byte?): BatteryLevel? {
        val value = raw?.toInt()?.and(0xFF) ?: return null
        if (value == 0xFF) return null
        return BatteryLevel((value and 0x7F).coerceIn(0, 100), (value and 0x80) != 0)
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun readLe16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0x0000
        for (raw in data) {
            crc = crc xor (raw.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
