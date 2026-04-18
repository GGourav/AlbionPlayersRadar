package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun setPlayerListener(listener: PlayerListener?) {
        playerListener = listener
    }

    private var localPlayerId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var currentZone: String = ""

    fun onPhotonEvent(code: Int, params: Map<Byte, Any?>) {
        when (code) {
            1 -> {
                // Player left
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                playerListener?.onPlayerLeft(id)
            }
            3 -> {
                // Move event
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val posX = (params[4.toByte()] as? Number)?.toFloat() ?: return
                val posY = (params[5.toByte()] as? Number)?.toFloat() ?: return
                if (id == localPlayerId) {
                    localX = posX; localY = posY
                    playerListener?.onLocalPlayerMoved(posX, posY)
                } else {
                    playerListener?.onPlayerMoved(id, posX, posY)
                }
            }
            29 -> {
                // NewCharacter
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val name = params[1.toByte()] as? String ?: ""
                val guild = params[7.toByte()] as? String ?: ""
                val faction = (params[12.toByte()] as? Number)?.toInt() ?: 0
                localPlayerId = id
                playerListener?.onPlayerJoined(id, name, guild, 0f, 0f, faction)
            }
        }
    }
}
