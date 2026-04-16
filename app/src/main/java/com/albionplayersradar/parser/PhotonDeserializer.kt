package com.albionplayersradar.parser

import android.util.Log

object PhotonDeserializer {

    // Protocol18 type codes
    private const val TYPE_NULL = 8
    private const val TYPE_BOOL_FALSE = 27
    private const val TYPE_BOOL_TRUE = 28
    private const val TYPE_INT_ZERO = 30
    private const val TYPE_BYTE_ZERO = 34
    private const val TYPE_SHORT_ZERO = 29
    private const val TYPE_FLOAT_ZERO = 32
    private const val TYPE_DOUBLE_ZERO = 33
    private const val TYPE_LONG_ZERO = 31
    private const val TYPE_BYTE = 3
    private const val TYPE_BOOL = 2
    private const val TYPE_SHORT = 4
    private const val TYPE_INT1 = 11
    private const val TYPE_INT1_NEG = 12
    private const val TYPE_INT2 = 13
    private const val TYPE_INT2_NEG = 14
    private const val TYPE_COMPRESSED_INT = 9
    private const val TYPE_COMPRESSED_LONG = 10
    private const val TYPE_FLOAT = 5
    private const val TYPE_DOUBLE = 6
    private const val TYPE_STRING = 7
    private const val TYPE_BYTE_ARRAY = 67
    private const val TYPE_OBJECT_ARRAY = 23
    private const val TYPE_HASHTABLE = 21
    private const val TYPE_DICTIONARY = 20

    // Event dispatch codes
    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3
    private const val MSG_EVENT = 4

    // Photon command types
    private const val CMD_SEND_RELIABLE = 6
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_FRAGMENT = 8

    data class PhotonEvent(
        val eventCode: Int,
        val params: Map<Int, Any?>
    )

    fun parseEventData(data: ByteArray): PhotonEvent? {
        return try {
            if (data.isEmpty()) return null

            var offset = 0

            // Event code (1 byte)
            val eventCode = data[offset++].toInt() and 0xFF

            // Parameter count (compressed uint32)
            val paramCount = readCompressedUInt32(data, offset).first.also { offset += it.second }

            val params = mutableMapOf<Int, Any?>()

            for (i in 0 until paramCount) {
                if (offset >= data.size) break

                // Key (1 byte)
                val key = data[offset++].toInt() and 0xFF

                // Type code (1 byte)
                val typeCode = data[offset++].toInt() and 0xFF

                // Value
                val result = readValue(data, offset, typeCode)
                params[key] = result.value
                offset = result.newOffset
            }

            PhotonEvent(eventCode, params)
        } catch (e: Exception) {
            Log.e("PhotonDeserializer", "Parse error: ${e.message}")
            null
        }
    }

    private data class ReadResult(val value: Any?, val newOffset: Int)

    private fun readValue(data: ByteArray, offset: Int, typeCode: Int): ReadResult {
        if (offset >= data.size) return ReadResult(null, offset)

        return when (typeCode) {
            TYPE_NULL -> ReadResult(null, offset)
            TYPE_BOOL_FALSE -> ReadResult(false, offset)
            TYPE_BOOL_TRUE -> ReadResult(true, offset)
            TYPE_INT_ZERO, TYPE_BYTE_ZERO, TYPE_SHORT_ZERO -> ReadResult(0, offset)
            TYPE_LONG_ZERO -> ReadResult(0L, offset)
            TYPE_FLOAT_ZERO -> ReadResult(0f, offset)
            TYPE_DOUBLE_ZERO -> ReadResult(0.0, offset)

            TYPE_BOOL -> ReadResult(data[offset].toInt() != 0, offset + 1)
            TYPE_BYTE -> ReadResult(data[offset].toInt() and 0xFF, offset + 1)

            TYPE_INT1 -> ReadResult(data[offset].toInt() and 0xFF, offset + 1)
            TYPE_INT1_NEG -> ReadResult(-(data[offset].toInt() and 0xFF), offset + 1)

            TYPE_INT2 -> {
                val v = (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8)
                ReadResult(v, offset + 2)
            }
            TYPE_INT2_NEG -> {
                val v = (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8)
                ReadResult(-v, offset + 2)
            }

            TYPE_SHORT -> {
                val v = (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() shl 8)
                ReadResult(v.toShort(), offset + 2)
            }

            TYPE_COMPRESSED_INT -> {
                val (v, bytes) = readCompressedUInt32(data, offset)
                ReadResult(zigzagDecode32(v), offset + bytes)
            }

            TYPE_COMPRESSED_LONG -> {
                val (v, bytes) = readCompressedUInt64(data, offset)
                ReadResult(zigzagDecode64(v), offset + bytes)
            }

            TYPE_FLOAT -> {
                val bits = (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        ((data[offset + 2].toInt() and 0xFF) shl 16) or
                        ((data[offset + 3].toInt() and 0xFF) shl 24)
                ReadResult(Float.fromBits(bits), offset + 4)
            }

            TYPE_DOUBLE -> {
                val bits = (data[offset].toInt() and 0xFF).toLong() or
                        ((data[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                        ((data[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                        ((data[offset + 3].toInt() and 0xFF).toLong() shl 24) or
                        ((data[offset + 4].toInt() and 0xFF).toLong() shl 32) or
                        ((data[offset + 5].toInt() and 0xFF).toLong() shl 40) or
                        ((data[offset + 6].toInt() and 0xFF).toLong() shl 48) or
                        ((data[offset + 7].toInt() and 0xFF).toLong() shl 56)
                ReadResult(Double.fromBits(bits), offset + 8)
            }

            TYPE_STRING -> {
                val (len, lenBytes) = readCompressedUInt32(data, offset)
                val strOffset = offset + lenBytes
                if (strOffset + len > data.size) {
                    ReadResult("", data.size)
                } else {
                    val str = String(data, strOffset, len, Charsets.UTF_8)
                    ReadResult(str, strOffset + len)
                }
            }

            TYPE_BYTE_ARRAY -> {
                val (len, lenBytes) = readCompressedUInt32(data, offset)
                val arrOffset = offset + lenBytes
                if (arrOffset + len > data.size) {
                    ReadResult(byteArrayOf(), data.size)
                } else {
                    ReadResult(data.copyOfRange(arrOffset, arrOffset + len), arrOffset + len)
                }
            }

            TYPE_HASHTABLE, TYPE_DICTIONARY -> {
                val (count, bytes) = readCompressedUInt32(data, offset)
                var pos = offset + bytes
                val map = mutableMapOf<Any?, Any?>()
                repeat(count.toInt()) {
                    if (pos >= data.size) return@repeat
                    val kvResult = readValue(data, pos, data[pos++].toInt())
                    val k = kvResult.value
                    if (pos < data.size) {
                        val vvResult = readValue(data, pos, data[pos++].toInt())
                        map[k] = vvResult.value
                        pos = vvResult.newOffset
                    }
                }
                // Try to extract known keys
                val result = mutableMapOf<Int, Any?>()
                map.entries.forEach { (k, v) ->
                    if (k is Number) result[k.toInt()] = v
                }
                ReadResult(result, pos)
            }

            TYPE_OBJECT_ARRAY -> {
                val (count, bytes) = readCompressedUInt32(data, offset)
                var pos = offset + bytes
                val list = mutableListOf<Any?>()
                repeat(count.toInt()) {
                    if (pos >= data.size) return@repeat
                    val typeCode2 = data[pos++].toInt() and 0xFF
                    val r = readValue(data, pos, typeCode2)
                    list.add(r.value)
                    pos = r.newOffset
                }
                ReadResult(list.toTypedArray(), pos)
            }

            else -> {
                // Custom type slim (0x80+)
                if (typeCode >= 128 && typeCode <= 228) {
                    val (size, sb) = readCompressedUInt32(data, offset)
                    ReadResult(byteArrayOf(), offset + sb + size.toInt())
                } else {
                    ReadResult(null, offset)
                }
            }
        }
    }

    private fun readCompressedUInt32(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift >= 35) break
        }
        return Pair(result, pos - offset)
    }

    private fun readCompressedUInt64(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift >= 70) break
        }
        return Pair(result, pos - offset)
    }

    private fun zigzagDecode32(v: Long): Int = ((v shr 1) xor -(v and 1)).toInt()
    private fun zigzagDecode64(v: Long): Long = (v shr 1) xor -(v and 1)
                         }
