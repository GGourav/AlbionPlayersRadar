package com.albionplayersradar.parser

import android.util.Log
import com.albionplayersradar.data.Player

object EventRouter {
    interface PlayerCallback {
        fun onPlayerJoined(player: Player)
        fun onPlayerMoved(player: Player)
        fun onPlayerLeft(id: Long)
        fun onPlayerHealth(id: Long, current: Float, max: Float)
        fun onMountChanged(id: Long, mounted: Boolean)
        fun onFactionChanged(id: Long, faction: Int)
        fun onLocalPosition(x: Float, y: Float)
        fun onZoneChanged(zoneId: String)
    }

    var listener: PlayerCallback? = null

    private val players = mutableMapOf<Long, Player>()
    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f

    fun onPacket(data: ByteArray) {
        PhotonPacketParser.parse(data) { type, params ->
            try {
                when (type) {
                    "event" -> handleEvent(params)
                    "request" -> handleRequest(params)
                    "response" -> handleResponse(params)
                }
            } catch (e: Exception) {
                Log.e("EventRouter", "error: ${e.message}")
            }
        }
    }

    private fun handleEvent(params: Map<Byte, Any>) {
        val code = (params[252.toByte()] as? Number)?.toInt() ?: return

        when (code) {
            29 -> handleNewCharacter(params)
            3 -> handleMove(params)
            6 -> handleHealth(params)
            91 -> handleRegen(params)
            209 -> handleMount(params)
            359 -> handleFaction(params)
            1 -> handleLeave(params)
        }
    }

    private fun handleNewCharacter(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        if (id == localId) return
        val name = params[1.toByte()] as? String ?: return
        val guild = params[8.toByte()] as? String
        val alliance = params[51.toByte()] as? String
        val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0

        val loc = params[7.toByte()] as? List<*>
        val posX = (loc?.get(0) as? Number)?.toFloat() ?: 0f
        val posY = (loc?.get(1) as? Number)?.toFloat() ?: 0f

        val player = Player(
            id = id, name = name, guildName = guild, allianceName = alliance,
            faction = faction, posX = posX, posY = posY, posZ = 0f,
            currentHealth = 0, maxHealth = 0, isMounted = false
        )
        players[id] = player
        listener?.onPlayerJoined(player)
    }

    private fun handleMove(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val posX = (params[4.toByte()] as? Number)?.toFloat() ?: return
        val posY = (params[5.toByte()] as? Number)?.toFloat() ?: return

        val player = players[id] ?: return
        val updated = player.copy(posX = posX, posY = posY)
        players[id] = updated
        listener?.onPlayerMoved(updated)
    }

    private fun handleHealth(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val current = (params[2.toByte()] as? Number)?.toFloat() ?: return
        val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f

        val player = players[id]
        if (player != null) {
            players[id] = player.copy(currentHealth = current.toInt(), maxHealth = max.toInt())
        }
        listener?.onPlayerHealth(id, current, max)
    }

    private fun handleRegen(params: Map<Byte, Any>) {
        handleHealth(params)
    }

    private fun handleMount(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val mounted = params[11.toByte()] == true || params[11.toByte()] == "true"
        val player = players[id] ?: return
        players[id] = player.copy(isMounted = mounted)
        listener?.onMountChanged(id, mounted)
    }

    private fun handleFaction(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val faction = (params[1.toByte()] as? Number)?.toInt() ?: return
        val player = players[id] ?: return
        players[id] = player.copy(faction = faction)
        listener?.onFactionChanged(id, faction)
    }

    private fun handleLeave(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        players.remove(id)
        listener?.onPlayerLeft(id)
    }

    private fun handleRequest(params: Map<Byte, Any>) {
        val opCode = (params[253.toByte()] as? Number)?.toInt() ?: return

        if (opCode == 21 || opCode == 22) {
            val pos = params[1.toByte()]
            val list = pos as? List<*>
            if (list != null && list.size >= 2) {
                localX = (list[0] as? Number)?.toFloat() ?: 0f
                localY = (list[1] as? Number)?.toFloat() ?: 0f
                listener?.onLocalPosition(localX, localY)
            }
        }
    }

    private fun handleResponse(params: Map<Byte, Any>) {
        val opCode = (params[253.toByte()] as? Number)?.toInt() ?: return

        when (opCode) {
            2 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                localId = id
                val posArray = params[9.toByte()] as? ByteArray
                if (posArray != null && posArray.size >= 8) {
                    val buf = java.nio.ByteBuffer.wrap(posArray).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    localX = buf.float
                    localY = buf.float
                    listener?.onLocalPosition(localX, localY)
                }
                val zoneId = params[8.toByte()] as? String
                if (!zoneId.isNullOrEmpty()) {
                    listener?.onZoneChanged(zoneId)
                }
            }
            35, 41 -> {
                val zoneId = (params[0.toByte()] as? String) ?: return
                listener?.onZoneChanged(zoneId)
            }
        }
    }

    fun getPlayers() = players.values.toList()
    fun getLocalPosition() = localX to localY
}
