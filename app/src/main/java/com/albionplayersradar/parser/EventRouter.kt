package com.albionplayersradar.parser

import android.util.Log

object EventRouter {
    interface Callback {
        fun onEvent(code: Int, params: Map<Byte, Any>)
        fun onRequest(code: Int, params: Map<Byte, Any>)
        fun onResponse(code: Int, params: Map<Byte, Any>)
    }

    private var callback: Callback? = null
    private var playerListener: PlayerListener? = null

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float)
    }

    fun setPlayerListener(listener: PlayerListener) {
        playerListener = listener
    }

    fun setCallback(cb: Callback) {
        callback = cb
    }

    fun routeEvent(code: Int, params: Map<Byte, Any>) {
        when (code) {
            29 -> routeNewCharacter(params)
            3 -> routeMove(params)
            1 -> routeLeave(params)
            6 -> routeHealthUpdate(params)
            91 -> routeRegenHealth(params)
        }
        callback?.onEvent(code, params)
    }

    private fun routeNewCharacter(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val name = params[1.toByte()]?.toString() ?: ""
            val guild = params[8.toByte()]?.toString() ?: ""
            val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
            val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
            val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
            playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
        } catch (e: Exception) {
            Log.e("EventRouter", "NewCharacter error: ${e.message}")
        }
    }

    private fun routeMove(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val posX = (params[4.toByte()] as? Number)?.toFloat() ?: return
            val posY = (params[5.toByte()] as? Number)?.toFloat() ?: return
            playerListener?.onPlayerMoved(id, posX, posY)
        } catch (_: Exception) {}
    }

    private fun routeLeave(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            playerListener?.onPlayerLeft(id)
        } catch (_: Exception) {}
    }

    private fun routeHealthUpdate(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val currentHp = (params[2.toByte()] as? Number)?.toFloat() ?: return
            val maxHp = (params[3.toByte()] as? Number)?.toFloat() ?: return
            playerListener?.onPlayerHealthChanged(id, currentHp, maxHp)
        } catch (_: Exception) {}
    }

    private fun routeRegenHealth(params: Map<Byte, Any>) {
        routeHealthUpdate(params)
    }
}
