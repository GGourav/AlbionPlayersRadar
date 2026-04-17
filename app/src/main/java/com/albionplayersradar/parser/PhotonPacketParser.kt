package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

object PhotonPacketParser {
    private const val TAG = "PhotonParser"
    private const val HDR = 12
    private const val CMD_HDR = 12

    private const val CMD_DISCONNECT: Byte = 4
    private const val CMD_SEND_RELIABLE: Byte = 6
    private const val CMD_SEND_UNRELIABLE: Byte = 7
    private const val CMD_SEND_FRAGMENT: Byte = 8
    private const val MSG_REQUEST: Byte = 2
    private const val MSG_RESPONSE: Byte = 3
    private const val MSG_EVENT: Byte = 4

    fun parse(data: ByteArray, cb: (String, Map<Byte, Any>) -> Unit) {
        if (data.size < HDR) return
        try {
            val buf = ByteBuffer.wrap(data)
            buf.position(HDR)
            while (buf.hasRemaining()) {
                if (buf.remaining() < 4) break
                val cmdType = buf.get()
                buf.get(); buf.get(); buf.get()
                val cmdLen = buf.int
                buf.int
                val payLen = cmdLen - CMD_HDR
                if (payLen <= 0 || payLen > buf.remaining()) break
                when (cmdType.toInt()) {
                    4 -> buf.position(buf.position() + payLen)
                    6 -> { buf.get(); parseReliable(buf, payLen - 1, cb) }
                    7 -> { buf.int; parseReliable(buf, payLen - 4, cb) }
                    8 -> parseFragment(buf, payLen, cb)
                    else -> buf.position(buf.position() + payLen)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
        }
    }

    private data class Frag(val totalLen: Int, val payload: ByteArray, var written: Int = 0, val seen: MutableSet<Int> = mutableSetOf())
    private val fragMap = mutableMapOf<Int, Frag>()

    private fun parseFragment(buf: ByteBuffer, len: Int, cb: (String, Map<Byte, Any>) -> Unit) {
        if (len < 20) return
        val key = buf.int
        buf.int
        val totalLen = buf.int
        val fragOff = buf.int
        val fragLen = len - 20
        val state = fragMap.getOrPut(key) { Frag(totalLen, ByteArray(totalLen)) }
        if (fragOff >= 0 && fragOff + fragLen <= totalLen && !state.seen.contains(fragOff)) {
            val arr = ByteArray(fragLen)
            buf.get(arr)
            System.arraycopy(arr, 0, state.payload, fragOff, fragLen)
            state.written += fragLen
            state.seen.add(fragOff)
        }
        if (state.written >= state.totalLen) {
            fragMap.remove(key)
            parseReliable(ByteBuffer.wrap(state.payload), state.payload.size, cb)
        }
    }

    private fun parseReliable(buf: ByteBuffer, len: Int, cb: (String, Map<Byte, Any>) -> Unit) {
        if (len < 1) return
        buf.get()
        val msgType = buf.get()
        when (msgType.toInt()) {
            4 -> {
                val code = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVar(buf)
                repeat(count) {
                    val key = buf.get()
                    val tc = buf.get()
                    params[key] = readVal(buf, tc)!!
                }
                params[252.toByte()] = code
                cb("event", params)
            }
            2 -> {
                val op = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVar(buf)
                repeat(count) {
                    val key = buf.get()
                    val tc = buf.get()
                    params[key] = readVal(buf, tc)!!
                }
                params[253.toByte()] = op
                cb("request", params)
            }
            3 -> {
                val op = buf.get().toInt() and 0xFF
                buf.short
                val params = mutableMapOf<Byte, Any>()
                val count = readUVar(buf)
                repeat(count) {
                    val key = buf.get()
                    val tc = buf.get()
                    params[key] = readVal(buf, tc)!!
                }
                params[253.toByte()] = op
                cb("response", params)
            }
        }
    }

    private fun readUVar(buf: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
            if (b and 0x80 == 0 || shift >= 35) break
        }
        return result
    }

    private fun readVal(buf: ByteBuffer, tc: Byte): Any? {
        return when (tc.toInt()) {
            0, 8 -> null
            2 -> buf.get().toInt() != 0
            3 -> buf.get()
            4 -> buf.short
            5 -> buf.float
            6 -> buf.double
            7 -> readStr(buf)
            9 -> readZZ32(buf)
            10 -> readZZ64(buf)
            11 -> buf.get().toInt() and 0xFF
            12 -> -(buf.get().toInt() and 0xFF)
            13 -> buf.short.toInt() and 0xFFFF
            14 -> -(buf.short.toInt() and 0xFFFF)
            15 -> buf.get().toLong() and 0xFF
            16 -> -(buf.get().toLong() and 0xFF)
            17 -> buf.short.toLong() and 0xFFFF
            18 -> -(buf.short.toLong() and 0xFFFF)
            21 -> readHT(buf)
            23 -> readObjArr(buf)
            27 -> false
            28 -> true
            29 -> 0.toShort()
            30 -> 0
            31 -> 0L
            32 -> 0f
            33 -> 0.0
            34 -> 0.toByte()
            else -> {
                if (tc.toInt() and 0x40 != 0) readTArr(buf, (tc.toInt() and 0x3F).toByte())
                else if (tc.toInt() and 0x80 != 0) readCustom(buf)
                else null
            }
        }
    }

    private fun readZZ32(buf: ByteBuffer): Int {
        val v = readUVar(buf).toLong()
        return ((v shr 1) xor -(v and 1)).toInt()
    }

    private fun readZZ64(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0 || shift >= 70) break
        }
        return (result shr 1) xor -(result and 1)
    }

    private fun readStr(buf: ByteBuffer): String {
        val len = readUVar(buf)
        if (len <= 0 || len > buf.remaining()) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readHT(buf: ByteBuffer): Map<Any, Any> {
        val count = readUVar(buf)
        val map = mutableMapOf<Any, Any>()
        for (i in 0 until count) {
            val kt = buf.get()
            val key = readVal(buf, kt)!!
            val vt = buf.get()
            val value = readVal(buf, vt)!!
            map[key] = value
        }
        return map
    }

    private fun readObjArr(buf: ByteBuffer): List<Any?> {
        val count = readUVar(buf)
        val list = mutableListOf<Any?>()
        for (i in 0 until count) { list.add(readVal(buf, buf.get())) }
        return list
    }

    private fun readTArr(buf: ByteBuffer, et: Byte): List<Any?> {
        val count = readUVar(buf)
        val list = mutableListOf<Any?>()
        repeat(count) { list.add(readVal(buf, et)) }
        return list
    }

    private fun readCustom(buf: ByteBuffer): ByteArray {
        val len = readUVar(buf)
        if (len <= 0 || len > buf.remaining()) return ByteArray(0)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return bytes
    }
}
