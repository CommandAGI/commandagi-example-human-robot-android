package com.commandagi.humanrobot.drone

/**
 * `DL` USB-serial framing for the ESP32 relay bridge — a byte-for-byte port of the framing used by
 * the `drone-control` project (`drone_control/transport.py` and the ESP32 firmware). The phone is
 * the PC side of that link: it speaks framed messages over USB to one ESP32, which owns the Wi-Fi
 * association to one drone AP and forwards UDP control packets.
 *
 * Frame layout (little-endian):
 *   "DL" | version(1) | type(1) | seq(u16) | payloadLen(u16) | payload | crc16(u16)
 * CRC is CRC-16/CCITT (poly 0x1021, init 0xFFFF) over header+payload.
 */
object DroneFrames {
    val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 8
    const val MAX_PAYLOAD = 2048

    // PC -> ESP32
    const val MSG_CONFIG = 0x01
    const val MSG_SEND = 0x02
    const val MSG_SCAN = 0x03
    // ESP32 -> PC
    const val MSG_STATUS = 0x81
    const val MSG_ACK = 0x82
    const val MSG_ERROR = 0x83

    fun crc16Ccitt(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (b in 0 until 8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Encode one outbound frame ready to write to the serial port. */
    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size + 2)
        out[0] = MAGIC[0]
        out[1] = MAGIC[1]
        out[2] = VERSION.toByte()
        out[3] = (type and 0xFF).toByte()
        out[4] = (seq and 0xFF).toByte()
        out[5] = ((seq shr 8) and 0xFF).toByte()
        out[6] = (payload.size and 0xFF).toByte()
        out[7] = ((payload.size shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        val crc = crc16Ccitt(out, HEADER_SIZE + payload.size)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    data class Frame(val type: Int, val seq: Int, val payload: ByteArray)

    /**
     * Incremental frame parser. Feed raw serial bytes via [push]; pop complete, CRC-checked frames
     * with [poll]. Resyncs past USB reset/boot noise by scanning for the magic.
     */
    class Parser {
        private val buf = ArrayDeque<Byte>()

        fun push(data: ByteArray, len: Int = data.size) {
            for (i in 0 until len) buf.addLast(data[i])
        }

        fun poll(): Frame? {
            while (true) {
                // Find magic.
                while (buf.size >= 2 && !(buf.elementAt(0) == MAGIC[0] && buf.elementAt(1) == MAGIC[1])) {
                    buf.removeFirst()
                }
                if (buf.size < HEADER_SIZE) return null
                val version = buf.elementAt(2).toInt() and 0xFF
                if (version != VERSION) { buf.removeFirst(); buf.removeFirst(); continue }
                val type = buf.elementAt(3).toInt() and 0xFF
                val seq = (buf.elementAt(4).toInt() and 0xFF) or ((buf.elementAt(5).toInt() and 0xFF) shl 8)
                val payloadLen = (buf.elementAt(6).toInt() and 0xFF) or ((buf.elementAt(7).toInt() and 0xFF) shl 8)
                if (payloadLen > MAX_PAYLOAD) { buf.removeFirst(); buf.removeFirst(); continue }
                val frameLen = HEADER_SIZE + payloadLen + 2
                if (buf.size < frameLen) return null
                val frame = ByteArray(frameLen)
                for (i in 0 until frameLen) frame[i] = buf.elementAt(i)
                val expected = (frame[frameLen - 2].toInt() and 0xFF) or ((frame[frameLen - 1].toInt() and 0xFF) shl 8)
                val actual = crc16Ccitt(frame, frameLen - 2)
                repeat(frameLen) { buf.removeFirst() }
                if (expected != actual) continue
                val payload = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
                return Frame(type, seq, payload)
            }
        }
    }
}
