package com.albionplayersradar.parser

import android.util.Log

object EventRouter {
    private const val TAG = "EventRouter"

    private var playerListener: PlayerListener? = null

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerMountChanged(id: Long, isMounted: Boolean)
        fun onMapChanged(zoneId: String)
        fun onLocalPlayerMoved(posX: Float, posY: Float)
    }

    fun setPlayerListener(listener: PlayerListener) {
        playerListener = listener
    }

    private var localPlayerId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f

    fun setLocalPlayer(id: Long, x: Float, y: Float) {
        localPlayerId = id; localX = x; localY = y
    }

    fun onUdpPacketReceived(data: ByteArray) {
        PhotonPacketParser.parse(data) { type, params ->
            try {
                when (type) {
                    "event" -> {
                        val code = (params[252.toByte()] as? Number)?.toInt() ?: return@parse
                        when (code) {
                            29 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                if (id == localPlayerId) return@parse
                                val name = params[1.toByte()] as? String ?: return@parse
                                val guild = params[8.toByte()] as? String ?: ""
                                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
                                val loc = params[7.toByte()] as? List<*> ?: return@parse
                                val posX = (loc[0] as? Number)?.toFloat() ?: 0f
                                val posY = (loc[1] as? Number)?.toFloat() ?: 0f
                                playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
                            }
                            3 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
                                val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
                                playerListener?.onPlayerMoved(id, posX, posY)
                            }
                            209 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                val isMounted = params[11.toByte()] == true
                                playerListener?.onPlayerMountChanged(id, isMounted)
                            }
                        }
                    }
                    "response" -> {
                        val code = (params[253.toByte()] as? Number)?.toInt() ?: return@parse
                        when (code) {
                            2 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                val posArray = params[9.toByte()] as? ByteArray
                                if (posArray != null && posArray.size >= 8) {
                                    val buf = java.nio.ByteBuffer.wrap(posArray).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    val posX = buf.float
                                    val posY = buf.float
                                    setLocalPlayer(id, posX, posY)
                                    playerListener?.onLocalPlayerMoved(posX, posY)
                                }
                            }
                            35, 41 -> {
                                val zone = params[0.toByte()] as? String ?: return@parse
                                playerListener?.onMapChanged(zone)
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "error", e) }
        }
    }
}
