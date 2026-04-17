package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object PhotonPacketParser {

    private const val TAG = "PhotonParser"
    private const val PHOTON_HEADER = 12
    private const val CMD_HEADER = 12
    private const val FRAG_HEADER = 20
    private const val MAX_PENDING = 64
    private const val MAX_ARRAY_SIZE = 65536

    private const val CMD_DISCONNECT: Byte = 4
    private const val CMD_SEND_RELIABLE: Byte = 6
    private const val CMD_SEND_UNRELIABLE: Byte = 7
    private const val CMD_SEND_FRAGMENT: Byte = 8

    private const val MSG_REQUEST: Byte = 2
    private const val MSG_RESPONSE: Byte = 3
    private const val MSG_EVENT: Byte = 4
    private const val MSG_ENCRYPTED: Byte = -125 // 131

    private data class FragmentState(
        val totalLen: Int,
        val payload: ByteArray,
        var written: Int = 0,
        val seen: MutableSet<Int> = mutableSetOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val fragmentMap = LinkedHashMap<Int, FragmentState>()

    fun parse(data: ByteArray, cb: (String, Map<Byte, Any>) -> Unit) {
        if (data.size < PHOTON_HEADER) return
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            // Skip peerId (2), read flags (1), commandCount (1), skip timestamp+challenge (8)
            buf.position(2)
            val flags = buf.get().toInt() and 0xFF
            val commandCount = buf.get().toInt() and 0xFF
            buf.position(PHOTON_HEADER) // skip to commands

            if (flags and 1 != 0) return // encrypted packet

            repeat(commandCount) {
                if (buf.remaining() < CMD_HEADER) return@repeat
                val startPos = buf.position()
                val cmdType = buf.get()
                buf.get(); buf.get(); buf.get() // channelId, flags, reserved
                val cmdLen = buf.int          // big-endian total length
                buf.int                        // reliableSeqNum
                val payloadLen = cmdLen - CMD_HEADER
                if (payloadLen <= 0 || payloadLen > buf.remaining()) {
                    buf.position(min(startPos + cmdLen, buf.limit()))
                    return@repeat
                }

                when (cmdType) {
                    CMD_DISCONNECT -> buf.position(buf.position() + payloadLen)
                    CMD_SEND_RELIABLE -> {
                        val signalByte = buf.get()  // signal byte
                        val msgType = buf.get()
                        val remaining = payloadLen - 2
                        if (msgType != MSG_ENCRYPTED) {
                            parseMessage(buf, remaining, msgType, cb)
                        } else {
                            buf.position(buf.position() + remaining)
                        }
                    }
                    CMD_SEND_UNRELIABLE -> {
                        buf.get(); buf.get(); buf.get(); buf.get() // unreliable seq (4 bytes)
                        val signalByte = buf.get()
                        val msgType = buf.get()
                        val remaining = payloadLen - 6
                        if (msgType != MSG_ENCRYPTED) {
                            parseMessage(buf, remaining, msgType, cb)
                        } else {
                            buf.position(buf.position() + remaining)
                        }
                    }
                    CMD_SEND_FRAGMENT -> parseFragment(buf, payloadLen, cb)
                    else -> buf.position(buf.position() + payloadLen)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse: ${e.message}")
        }
    }

    private fun parseFragment(buf: ByteBuffer, len: Int, cb: (String, Map<Byte, Any>) -> Unit) {
        if (len < FRAG_HEADER) { buf.position(buf.position() + len); return }
        val startSeq = buf.int   // startSeq
        buf.int                   // fragmentCount
        buf.int                   // fragmentNumber
        val totalLen = buf.int   // totalLength
        val fragOffset = buf.int // fragmentOffset
        val fragLen = len - FRAG_HEADER

        if (totalLen <= 0 || totalLen > MAX_ARRAY_SIZE * 16 ||
            fragLen <= 0 || fragLen > buf.remaining()) {
            buf.position(buf.position() + minOf(fragLen, buf.remaining()))
            return
        }

        // Evict oldest if full
        if (fragmentMap.size >= MAX_PENDING) {
            val oldest = fragmentMap.entries.minByOrNull { it.value.createdAt }
            if (oldest != null) fragmentMap.remove(oldest.key)
        }

        val state = fragmentMap.getOrPut(startSeq) {
            FragmentState(totalLen, ByteArray(totalLen))
        }

        if (fragOffset >= 0 && fragOffset + fragLen <= state.payload.size &&
            !state.seen.contains(fragOffset)) {
            buf.get(state.payload, fragOffset, fragLen)
            state.written += fragLen
            state.seen.add(fragOffset)
        } else {
            buf.position(buf.position() + fragLen)
        }

        if (state.written >= state.totalLen) {
            fragmentMap.remove(startSeq)
            val fb = ByteBuffer.wrap(state.payload).order(ByteOrder.BIG_ENDIAN)
            fb.get() // signal byte
            val msgType = fb.get()
            parseMessage(fb, state.payload.size - 2, msgType, cb)
        }
    }

    private fun parseMessage(buf: ByteBuffer, len: Int, msgType: Byte, cb: (String, Map<Byte, Any>) -> Unit) {
        if (len <= 0) return
        val endPos = buf.position() + len
        try {
            when (msgType) {
                MSG_EVENT -> {
                    val code = buf.get().toInt() and 0xFF
                    val params = readParamTable(buf).toMutableMap()
                    // Inject real event code into params[252] if not present
                    if (!params.containsKey(252.toByte())) {
                        params[252.toByte()] = code
                    }
                    // PostProcess: extract Move positions from ByteArray
                    if (code == 3) {
                        extractMovePositions(params)
                    }
                    params[252.toByte()] = params[252.toByte()] ?: code
                    cb("event", params)
                }
                MSG_REQUEST -> {
                    val opCode = buf.get().toInt() and 0xFF
                    val params = readParamTable(buf).toMutableMap()
                    params[253.toByte()] = opCode
                    cb("request", params)
                }
                MSG_RESPONSE -> {
                    val opCode = buf.get().toInt() and 0xFF
                    buf.short // returnCode (LE int16)
                    // debug message slot
                    if (buf.hasRemaining()) {
                        val debugTc = buf.get()
                        readValue(buf, debugTc) // consume but discard debug string
                    }
                    val params = readParamTable(buf).toMutableMap()
                    params[253.toByte()] = opCode
                    cb("response", params)
                }
                else -> { /* skip */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseMessage: ${e.message}")
        }
        // Ensure we advance to end even on partial parse
        if (buf.position() < endPos && endPos <= buf.limit()) {
            buf.position(endPos)
        }
    }

    private fun extractMovePositions(params: MutableMap<Byte, Any>) {
        val raw = params[1.toByte()] as? ByteArray ?: return
        if (raw.size < 17) return
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(9)
        params[4.toByte()] = bb.float  // posX
        bb.position(13)
        params[5.toByte()] = bb.float  // posY
    }

    private fun readParamTable(buf: ByteBuffer): Map<Byte, Any> {
        val params = mutableMapOf<Byte, Any>()
        if (!buf.hasRemaining()) return params
        val count = buf.get().toInt() and 0xFF
        repeat(count) {
            if (buf.remaining() < 2) return@repeat
            val key = buf.get()
            val typeCode = buf.get()
            val value = readValue(buf, typeCode)
            if (value != null) params[key] = value
        }
        return params
    }

    private fun readUVarint(buf: ByteBuffer): Int {
        var r = 0; var s = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            r = r or ((b and 0x7F) shl s)
            s += 7
            if (b and 0x80 == 0 || s >= 35) break
        }
        return r
    }

    private fun readZigZag32(buf: ByteBuffer): Int {
        val v = readUVarint(buf).toLong() and 0xFFFFFFFFL
        return ((v shr 1) xor -(v and 1)).toInt()
    }

    private fun readZigZag64(buf: ByteBuffer): Long {
        var r = 0L; var s = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            r = r or ((b and 0x7F).toLong() shl s)
            s += 7
            if (b and 0x80 == 0 || s >= 70) break
        }
        return (r shr 1) xor -(r and 1)
    }

    private fun readString(buf: ByteBuffer): String {
        val len = readUVarint(buf)
        if (len <= 0 || len > buf.remaining()) return ""
        val b = ByteArray(len); buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any, Any> {
        val map = mutableMapOf<Any, Any>()
        val count = readUVarint(buf)
        repeat(count) {
            val k = readValue(buf, buf.get())
            val v = readValue(buf, buf.get())
            if (k != null && v != null) map[k] = v
        }
        return map
    }

    private fun readObjectArray(buf: ByteBuffer): List<Any?> {
        val count = readUVarint(buf)
        return List(count) { if (buf.hasRemaining()) readValue(buf, buf.get()) else null }
    }

    private fun readTypedArray(buf: ByteBuffer, elemType: Byte): List<Any?> {
        val count = readUVarint(buf)
        if (count > MAX_ARRAY_SIZE) return emptyList()
        return List(count) { if (buf.hasRemaining()) readValue(buf, elemType) else null }
    }

    private fun readByteArray(buf: ByteBuffer): ByteArray {
        val len = readUVarint(buf)
        if (len <= 0 || len > buf.remaining()) return ByteArray(0)
        val b = ByteArray(len); buf.get(b)
        return b
    }

    private fun readCustom(buf: ByteBuffer, slim: Boolean): ByteArray {
        if (!slim) buf.get() // consume customId byte
        return readByteArray(buf)
    }

    private fun readValue(buf: ByteBuffer, typeCode: Byte): Any? {
        if (!buf.hasRemaining()) return null
        return try {
            when (typeCode.toInt() and 0xFF) {
                0, 8 -> null          // Unknown / Null
                2 -> buf.get().toInt() != 0   // Boolean
                3 -> buf.get()                // Byte
                4 -> buf.short                // Short LE
                5 -> buf.float                // Float LE
                6 -> buf.double               // Double LE
                7 -> readString(buf)          // String
                9 -> readZigZag32(buf)        // CompressedInt
                10 -> readZigZag64(buf)       // CompressedLong
                11 -> buf.get().toInt() and 0xFF          // Int1 positive
                12 -> -(buf.get().toInt() and 0xFF)       // Int1 negative
                13 -> buf.short.toInt() and 0xFFFF        // Int2 positive
                14 -> -(buf.short.toInt() and 0xFFFF)     // Int2 negative
                15 -> buf.get().toLong() and 0xFF         // L1 positive
                16 -> -(buf.get().toLong() and 0xFF)      // L1 negative
                17 -> buf.short.toLong() and 0xFFFF       // L2 positive
                18 -> -(buf.short.toLong() and 0xFFFF)    // L2 negative
                19 -> readCustom(buf, slim = false)       // Custom
                20 -> readHashtable(buf)                  // Dictionary (same wire as Hashtable)
                21 -> readHashtable(buf)                  // Hashtable
                23 -> readObjectArray(buf)                // ObjectArray
                27 -> false                               // BooleanFalse
                28 -> true                                // BooleanTrue
                29 -> 0.toShort()                         // ShortZero
                30 -> 0                                   // IntZero
                31 -> 0L                                  // LongZero
                32 -> 0f                                  // FloatZero
                33 -> 0.0                                 // DoubleZero
                34 -> 0.toByte()                          // ByteZero
                67 -> readByteArray(buf)                  // ByteArray
                else -> when {
                    (typeCode.toInt() and 0xFF) >= 0x80 -> readCustom(buf, slim = true)  // CustomTypeSlim
                    (typeCode.toInt() and 0x40) != 0 -> {                                // Typed Array
                        val elemType = (typeCode.toInt() and 0x3F).toByte()
                        readTypedArray(buf, elemType)
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
