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
 */
object HscH515PacketCodec {
    val SERVICE_UUID: UUID = UUID.fromString("01000100-0000-2000-8000-009078563412")
    val READ_UUID: UUID = UUID.fromString("02000200-0000-2000-8000-009178563412")
    val WRITE_UUID: UUID = UUID.fromString("03000300-0000-2000-8000-009278563412")
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
    const val CMD_FILE_COUNT_NOTIFY: Int = 0x0905
    const val CMD_GET_FILE_COUNT: Int = 0x0916

    const val CMD_DEVICE_CONTROL: Int = 0x0D01
    const val CMD_VIDEO_STATE_NOTIFY: Int = 0x0D02

    const val CMD_LOCAL_AUDIO_CONTROL: Int = 0x0E04
    const val CMD_LOCAL_AUDIO_STATE_NOTIFY: Int = 0x0E05

    private const val FRAME_MAGIC: Int = 0xA5
    private val UTF8: Charset = Charsets.UTF_8

    data class Packet(
        val commandId: Int,
        val type: Int,
        val sequence: Int,
        val payload: ByteArray,
        val crcValid: Boolean,
        val raw: ByteArray,
    )

    class SequenceGenerator {
        private var nextSequence: Int = 0

        @Synchronized
        fun next(): Int {
            val value = nextSequence and 0xFF
            nextSequence = (nextSequence + 1) and 0xFF
            return value
        }
    }

    fun request(
        commandId: Int,
        sequence: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = frame(
        commandId = commandId,
        type = TYPE_REQUEST,
        sequence = sequence,
        payload = payload,
    )

    fun response(
        commandId: Int,
        sequence: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = frame(
        commandId = commandId,
        type = TYPE_RESPONSE,
        sequence = sequence,
        payload = payload,
    )

    fun heartbeat(sequence: Int): ByteArray = frame(
        commandId = CMD_HEARTBEAT,
        type = TYPE_NOTIFY,
        sequence = sequence,
        payload = ByteArray(0),
    )

    fun timeRequest(sequence: Int): ByteArray {
        val unixSeconds = (System.currentTimeMillis() / 1000L).toInt()
        return request(
            CMD_SET_TIME,
            sequence,
            le32(unixSeconds),
        )
    }

    fun deviceControlRequest(
        sequence: Int,
        command: Int,
    ): ByteArray = request(
        CMD_DEVICE_CONTROL,
        sequence,
        byteArrayOf(command.toByte()),
    )

    fun localAudioControlRequest(
        sequence: Int,
        enabled: Boolean,
    ): ByteArray = request(
        CMD_LOCAL_AUDIO_CONTROL,
        sequence,
        byteArrayOf(if (enabled) 1 else 0),
    )

    fun decodeFrame(raw: ByteArray): Packet? {
        if (raw.size < 11) return null
        if ((raw[0].toInt() and 0xFF) != FRAME_MAGIC) return null

        val dataLength = readLe16(raw, 1)
        val expectedSize = 1 + 2 + dataLength + 2
        if (raw.size < expectedSize || dataLength < 6) return null

        val dataStart = 3
        val dataEnd = dataStart + dataLength
        val data = raw.copyOfRange(dataStart, dataEnd)
        val expectedCrc = readLe16(raw, dataEnd)
        val actualCrc = crc16(data)
        val crcValid = expectedCrc == actualCrc

        val commandId = readLe16(data, 0)
        val type = data[2].toInt() and 0xFF
        val sequence = data[3].toInt() and 0xFF
        val payloadLength = readLe16(data, 4)
        if (6 + payloadLength > data.size) return null

        val payload = data.copyOfRange(6, 6 + payloadLength)
        return Packet(
            commandId = commandId,
            type = type,
            sequence = sequence,
            payload = payload,
            crcValid = crcValid,
            raw = raw.copyOf(expectedSize),
        )
    }

    fun ackFor(packet: Packet): ByteArray = response(packet.commandId, packet.sequence)

    fun parseBattery(payload: ByteArray): Pair<Int, Boolean?>? {
        if (payload.isEmpty()) return null

        // Format: displayType + one or more battery bytes. A battery byte uses bit7 as charging flag.
        val candidates = payload.drop(1)
            .ifEmpty { payload.toList() }
            .map { it.toInt() and 0xFF }
            .filter { it != 0xFF }

        val first = candidates.firstOrNull() ?: return null
        val percent = (first and 0x7F).coerceIn(0, 100)
        val charging = if ((first and 0x80) != 0) true else false
        return percent to charging
    }

    fun parseFileCount(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        return readLe16(payload, 0)
    }

    fun parseStringPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val end = payload.indexOfFirst { it == 0.toByte() }
            .takeIf { it >= 0 }
            ?: payload.size
        return payload.copyOfRange(0, end)
            .toString(UTF8)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun parseVersionPayload(payload: ByteArray): String? {
        parseStringPayload(payload)?.let { return it }
        if (payload.isEmpty()) return null
        return payload.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun parseVideoState(payload: ByteArray): Boolean? = when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
        0 -> false
        1 -> true
        else -> null
    }

    fun parseAudioState(payload: ByteArray): Boolean? = parseVideoState(payload)

    private fun frame(
        commandId: Int,
        type: Int,
        sequence: Int,
        payload: ByteArray,
    ): ByteArray {
        val dataLength = 2 + 1 + 1 + 2 + payload.size
        val data = ByteArray(dataLength)
        writeLe16(data, 0, commandId)
        data[2] = type.toByte()
        data[3] = sequence.toByte()
        writeLe16(data, 4, payload.size)
        payload.copyInto(data, 6)

        val crc = crc16(data)
        return ByteArray(1 + 2 + dataLength + 2).also { out ->
            out[0] = FRAME_MAGIC.toByte()
            writeLe16(out, 1, dataLength)
            data.copyInto(out, 3)
            writeLe16(out, 3 + dataLength, crc)
        }
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun readLe16(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeLe16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (raw in data) {
            crc = crc xor (raw.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
