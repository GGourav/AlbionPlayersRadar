package com.albionplayersradar.parser

import android.util.Log

object EventRouter {

    private val TAG = "EventRouter"

    interface Callback {
        fun onEvent(code: Int, params: Map<Byte, Any>)
        fun onRequest(code: Int, params: Map<Byte, Any>)
        fun onResponse(code: Int, params: Map<Byte, Any>)
    }

    private var callback: Callback? = null
    private var playerListener: PhotonPacketParser.PlayerListener? = null

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    fun setPlayerListener(listener: PhotonPacketParser.PlayerListener?) {
        playerListener = listener
    }

    fun routeEvent(code: Int, params: Map<Byte, Any>) {
        try {
            callback?.onEvent(code, params)

            when (code) {
                PhotonPacketParser.EVENT_LEAVE -> {
                    val id = (params[0] as? Number)?.toInt() ?: return
                    playerListener?.onPlayerLeft(id)
                }
                PhotonPacketParser.EVENT_MOVE -> {
                    val id = (params[0] as? Number)?.toInt() ?: return
                    val posX = (params[4] as? Number)?.toFloat() ?: 0f
                    val posY = (params[5] as? Number)?.toFloat() ?: 0f
                    val existing = findPlayer(id)
                    if (existing != null) {
                        val updated = PhotonPacketParser.PlayerInfo(
                            id = existing.id,
                            name = existing.name,
                            guild = existing.guild,
                            alliance = existing.alliance,
                            posX = posX,
                            posY = posY,
                            posZ = existing.posZ,
                            health = existing.health,
                            maxHealth = existing.maxHealth,
                            isMounted = existing.isMounted,
                            faction = existing.faction,
                            equipment = existing.equipment,
                            spells = existing.spells,
                            attackRange = existing.attackRange,
                            type = existing.type
                        )
                        playerListener?.onPlayerMoved(updated)
                    }
                }
                PhotonPacketParser.EVENT_NEW_CHARACTER -> {
                    val id = (params[0] as? Number)?.toInt() ?: return
                    val name = params[1] as? String ?: "Unknown"
                    val guild = params[8] as? String
                    val alliance = params[51] as? String
                    val posX = (params[9] as? Number)?.toFloat() ?: 0f
                    val posY = (params[10] as? Number)?.toFloat() ?: 0f
                    val faction = (params[53] as? Number)?.toInt() ?: 0

                    val player = PhotonPacketParser.PlayerInfo(
                        id = id,
                        name = name,
                        guild = guild,
                        alliance = alliance,
                        posX = posX,
                        posY = posY,
                        posZ = 0f,
                        health = 100f,
                        maxHealth = 100f,
                        isMounted = false,
                        faction = faction,
                        equipment = null,
                        spells = null,
                        attackRange = 0f,
                        type = 0
                    )
                    playerListener?.onPlayerFound(player)
                }
                PhotonPacketParser.EVENT_HEALTH_UPDATE -> {
                    val id = (params[0] as? Number)?.toInt() ?: return
                    val health = (params[2] as? Number)?.toFloat() ?: 0f
                    val maxHealth = (params[3] as? Number)?.toFloat() ?: 100f
                    playerListener?.onPlayerHealthChanged(id, health, maxHealth)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing event $code: ${e.message}")
        }
    }

    fun routeRequest(code: Int, params: Map<Byte, Any>) {
        callback?.onRequest(code, params)
    }

    fun routeResponse(code: Int, params: Map<Byte, Any>) {
        callback?.onResponse(code, params)
    }

    private fun findPlayer(id: Int): PhotonPacketParser.PlayerInfo? {
        return null
    }
}
