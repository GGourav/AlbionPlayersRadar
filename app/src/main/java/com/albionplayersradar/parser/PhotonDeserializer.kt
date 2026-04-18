package com.albionplayersradar.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PhotonDeserializer {

    private const val TYPE_BOOL = 2
    private const val TYPE_BYTE = 3
    private const val TYPE_SHORT = 4
    private const val TYPE_INT = 5
    private const val TYPE_LONG = 6
    private const val TYPE_FLOAT = 7
    private const val TYPE_DOUBLE = 8
    private const val TYPE_STRING = 9
    private const val TYPE_BYTEARRAY = 10
    private const val TYPE_HASHTABLE = 12
    private const val TYPE_ARRAY = 13

    fun deserialize(buffer: ByteArray): Map<Byte, Any?> {
        val buf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableMapOf<Byte, Any?>()

        while (buf.hasRemaining()) {
            val key = buf.get().toByte()
            val type = buf.get()

            result[key] = when (type.toInt()) {
                TYPE_BOOL -> buf.get().toInt() != 0
                TYPE_BYTE -> buf.get().toInt()
                TYPE_SHORT -> buf.short.toInt()
                TYPE_INT -> buf.int
                TYPE_LONG -> buf.long
                TYPE_FLOAT -> buf.float
                TYPE_DOUBLE -> buf.double
                TYPE_STRING -> {
                    val len = buf.short.toInt().and(0xFFFF)
                    if (len <= 0) "" else {
                        val str = ByteArray(len)
                        buf.get(str)
                        String(str, Charsets.UTF_8)
                    }
                }
                TYPE_BYTEARRAY -> {
                    val len = buf.int
                    val arr = ByteArray(len)
                    buf.get(arr)
                    arr
                }
                TYPE_HASHTABLE -> {
                    val count = buf.short.toInt().and(0xFFFF)
                    deserializeHashtable(buf, count)
                }
                TYPE_ARRAY -> deserializeArray(buf)
                else -> null
            }
        }
        return result
    }

    private fun deserializeHashtable(buf: ByteBuffer, count: Int): Map<Any?, Any?> {
        val map = mutableMapOf<Any?, Any?>()
        for (i in 0 until count) {
            val keyType = buf.get()
            val key = when (keyType.toInt()) {
                TYPE_BYTE -> buf.get().toByte()
                TYPE_SHORT -> buf.short.toInt()
                TYPE_INT -> buf.int
                else -> buf.get().toByte()
            }
            val valType = buf.get()
            map[key] = when (valType.toInt()) {
                TYPE_BYTE -> buf.get().toInt()
                TYPE_SHORT -> buf.short.toInt()
                TYPE_INT -> buf.int
                TYPE_LONG -> buf.long
                TYPE_STRING -> {
                    val len = buf.short.toInt().and(0xFFFF)
                    if (len <= 0) "" else {
                        val str = ByteArray(len)
                        buf.get(str)
                        String(str, Charsets.UTF_8)
                    }
                }
                else -> null
            }
        }
        return map
    }

    private fun deserializeArray(buf: ByteBuffer): Any? {
        val arrayType = buf.get()
        val count = buf.int
        return when (arrayType.toInt()) {
            TYPE_BYTE -> {
                val arr = ByteArray(count)
                buf.get(arr)
                arr
            }
            TYPE_INT -> {
                val arr = IntArray(count)
                for (i in 0 until count) arr[i] = buf.int
                arr
            }
            TYPE_FLOAT -> {
                val arr = FloatArray(count)
                for (i in 0 until count) arr[i] = buf.float
                arr
            }
            TYPE_STRING -> {
                val arr = Array(count) {
                    val len = buf.short.toInt().and(0xFFFF)
                    if (len <= 0) "" else {
                        val str = ByteArray(len)
                        buf.get(str)
                        String(str, Charsets.UTF_8)
                    }
                }
                arr
            }
            else -> null
        }
    }
}
