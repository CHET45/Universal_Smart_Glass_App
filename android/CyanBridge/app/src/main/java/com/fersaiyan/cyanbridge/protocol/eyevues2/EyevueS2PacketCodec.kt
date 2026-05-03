package com.fersaiyan.cyanbridge.protocol.eyevues2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal object EyevueS2PacketCodec {
    val SERVICE_UUID: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805F9B34FB")
    val CMD_WRITE_UUID: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805F9B34FB")
    val CMD_NOTIFY_UUID: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805F9B34FB")
    val PHOTO_NOTIFY_UUID: UUID = UUID.fromString("0000aa15-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_SET_LED_BRIGHTNESS = 0x01
    const val CMD_SET_RECORD_DURATION = 0x02
    const val CMD_SET_WEAR_DETECT = 0x04
    const val CMD_SET_VOICE_COMMAND = 0x06
    const val CMD_FACTORY_RESET = 0x14
    const val CMD_GET_BATTERY = 0x17
    const val CMD_TAKE_PHOTO = 0x22
    const val CMD_START_VIDEO = 0x23
    const val CMD_STOP_VIDEO = 0x24
    const val CMD_SWITCH_MUSIC = 0x30
    const val CMD_PLAY_PAUSE = 0x31
    const val CMD_VOLUME = 0x32
    const val CMD_CALL = 0x33
    const val CMD_AUDIO_RECORD = 0x34
    const val CMD_OPEN_WIFI = 0x39
    const val CMD_GET_THUMBNAIL_COUNT = 0x40
    const val CMD_FILE_DOWNLOAD_FINISHED = 0x44
    const val CMD_GET_DEVICE_STATE = 0x45
    const val CMD_GET_SWITCH_SETTINGS = 0x48
    const val CMD_GET_DEVICE_INFO = 0x55
    const val CMD_INTERRUPT_VOICE = 0x56
    const val CMD_RETRANSMIT_VOICE = 0x57
    const val CMD_SET_TIME = 0x59
    const val CMD_RESTART = 0x60
    const val CMD_CAPTURE_DIRECTION = 0x61
    const val CMD_OFFLINE_LANGUAGE = 0x62
    const val CMD_ENTER_UPGRADE_MODE = 0x63
    const val CMD_CUSTOMER_PROJECT = 0x64
    const val CMD_SEND_ISP_VERSION = 0x65
    const val CMD_QUERY_LIVE_SUPPORT = 0x66
    const val CMD_START_LIVE = 0x67
    const val CMD_QUERY_QUICK_VOLUME_SUPPORT = 0x68
    const val CMD_GET_VOLUME = 0x69

    // Present in the real Eyevue application source pack. v11 documents volume adjustment
    // as 0x32, while the production app uses 0x70 for setting and 0x69 for reading levels.
    const val CMD_SET_VOLUME_APP = 0x70

    const val UPLOAD_WIFI_NAME = 0x25
    const val UPLOAD_THUMBNAIL_COUNT = 0x42
    const val UPLOAD_ACTION_SYNC = 0x45
    const val UPLOAD_VOICE_DATA = 0x46
    const val UPLOAD_ABANDON_VOICE = 0x49
    const val UPLOAD_CANCEL_AI_ANNOUNCEMENT = 0x51
    const val UPLOAD_HD_IMAGE_FAILED = 0x52
    const val UPLOAD_BATTERY = 0x53
    const val UPLOAD_ISP_WORKING = 0x54
    const val UPLOAD_SUPPORT_FEATURES = 0x95
    const val UPLOAD_ISP_UPGRADE_COMPLETE = 0x96
    const val UPLOAD_VOICE_START = 0x97
    const val UPLOAD_VOICE_END = 0x99

    data class Packet(
        val command: Int,
        val payload: ByteArray,
        val raw: ByteArray,
        val crcValid: Boolean,
    )

    fun appCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val safeCommand = command and 0xFF
        val length = payload.size + 2 // command + payload + checksum
        val checksum = checksum(safeCommand, payload)

        return ByteBuffer.allocate(2 + 2 + 1 + payload.size + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0xAB.toByte())
            .put(0x55.toByte())
            .putShort(length.toShort())
            .put(safeCommand.toByte())
            .put(payload)
            .put(checksum.toByte())
            .array()
    }

    fun appCommandWithZero(command: Int): ByteArray = appCommand(command, byteArrayOf(0x00.toByte()))

    fun timeCommand(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val payload = byteArrayOf(
            ((calendar.get(java.util.Calendar.YEAR) - 2000) and 0xFF).toByte(),
            (calendar.get(java.util.Calendar.MONTH) + 1).toByte(),
            calendar.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(java.util.Calendar.MINUTE).toByte(),
            calendar.get(java.util.Calendar.SECOND).toByte(),
        )
        return appCommand(CMD_SET_TIME, payload)
    }

    fun decodeBlePacket(bytes: ByteArray): Packet? {
        if (bytes.size < 6) return null
        val b0 = bytes[0].u8()
        val b1 = bytes[1].u8()
        if (!((b0 == 0xAC && b1 == 0x55) || (b0 == 0xAB && b1 == 0x55))) return null

        val length = (bytes[2].u8() shl 8) or bytes[3].u8()
        val expectedTotal = 4 + length
        if (expectedTotal > bytes.size) return null

        val command = bytes[4].u8()
        val payloadEndExclusive = 4 + length - 1
        val payload = if (payloadEndExclusive > 5) {
            bytes.copyOfRange(5, payloadEndExclusive)
        } else {
            byteArrayOf()
        }
        val receivedCrc = bytes[payloadEndExclusive].u8()
        val computedCrc = checksum(command, payload)

        return Packet(
            command = command,
            payload = payload,
            raw = bytes.copyOfRange(0, expectedTotal),
            crcValid = receivedCrc == computedCrc,
        )
    }

    fun decodeRxFilePacket(bytes: ByteArray): Packet? {
        if (bytes.size < 8) return null
        if (bytes[0].u8() != 0x52 || bytes[1].u8() != 0x58) return null
        if (bytes[bytes.lastIndex - 1].u8() != 0x58 || bytes[bytes.lastIndex].u8() != 0x52) return null

        val length = (bytes[2].u8() shl 8) or bytes[3].u8()
        val expectedTotal = 2 + 2 + length + 2
        if (expectedTotal > bytes.size) return null

        val command = bytes[4].u8()
        val payloadEndExclusive = 4 + length - 1
        val payload = if (payloadEndExclusive > 5) {
            bytes.copyOfRange(5, payloadEndExclusive)
        } else {
            byteArrayOf()
        }
        val receivedCrc = bytes[payloadEndExclusive].u8()
        val computedCrc = checksum(command, payload)

        return Packet(
            command = command,
            payload = payload,
            raw = bytes.copyOfRange(0, expectedTotal),
            crcValid = receivedCrc == computedCrc,
        )
    }

    fun parseThumbnailCount(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        return (payload[0].u8() shl 8) or payload[1].u8()
    }

    fun parseLegacyBattery(payload: ByteArray): Pair<Int, Boolean?>? {
        if (payload.size < 2) return null
        val percent = ((payload[0].u8() and 0x0F) * 10 + (payload[1].u8() and 0x0F)).coerceIn(0, 100)
        val charging = payload.getOrNull(2)?.u8()?.let { it == 0x01 }
        return percent to charging
    }

    fun parseUploadBattery(payload: ByteArray): Pair<Int, Boolean>? {
        if (payload.size < 2) return null
        val charging = payload[0].u8() == 0x01
        val percent = payload[1].u8().coerceIn(0, 100)
        return percent to charging
    }

    fun parseDeviceInfo(payload: ByteArray): Triple<String, String, String>? {
        if (payload.size < 7) return null
        val bt = "${payload[0].u8()}.${payload[1].u8()}.${payload[2].u8()}"
        val isp = "${payload[3].u8()}.${payload[4].u8()}.${payload[5].u8()}"
        val hw = payload[6].u8().toString()
        return Triple(bt, isp, hw)
    }

    fun parseVolume(payload: ByteArray): Triple<Int, Int, Int>? {
        if (payload.size < 3) return null
        return Triple(payload[0].u8(), payload[1].u8(), payload[2].u8())
    }

    fun parseWifiSsid(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val ssid = payload.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        return ssid.takeIf { it.isNotBlank() }
    }

    fun fileDownloadFinished(clearThumbnailCount: Boolean, downloadedCount: Int = 0): ByteArray {
        val payload = if (clearThumbnailCount) {
            byteArrayOf(0x30.toByte(), 0x00.toByte())
        } else {
            byteArrayOf(0x31.toByte(), downloadedCount.coerceIn(0, 255).toByte())
        }
        return appCommand(CMD_FILE_DOWNLOAD_FINISHED, payload)
    }

    fun keepThumbnailCountAndCloseImportMode(): ByteArray =
        appCommand(CMD_FILE_DOWNLOAD_FINISHED, byteArrayOf(0x30.toByte(), 0x01.toByte()))

    private fun checksum(command: Int, payload: ByteArray): Int {
        var sum = command and 0xFF
        payload.forEach { sum += it.u8() }
        return sum and 0xFF
    }

    internal fun Byte.u8(): Int = toInt() and 0xFF
}
