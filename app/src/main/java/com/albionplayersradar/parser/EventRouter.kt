package com.albionplayersradar.parser

import android.util.Log
import com.albionplayersradar.data.Player

object EventRouter {

    interface PlayerCallback {
        fun onPlayerJoined(player: Player)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(player: Player)
        fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float)
        fun onMountChanged(id: Long, isMounted: Boolean)
        fun onFactionChanged(id: Long, faction: Int)
        fun onZoneChanged(zoneId: String)
    }

    private var cb: PlayerCallback? = null
    private val players = mutableMapOf<Long, Player>()
    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var currentZone: String = ""

    fun setCallback(c: PlayerCallback) { cb = c }

    fun route(type: String, params: Map<Byte, Any>) {
        when (type) {
            "event" -> handleEvent(params)
            "request" -> handleRequest(params)
            "response" -> handleResponse(params)
        }
    }

    private fun handleEvent(params: Map<Byte, Any>) {
        val code = (params[252.toByte()] as? Number)?.toInt() ?: return

        when (code) {
            1 -> { // Leave
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                players.remove(id)
                cb?.onPlayerLeft(id)
            }
            3 -> { // Move
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val player = players[id] ?: return
                val posX = (params[4.toByte()] as? Number)?.toFloat() ?: player.posX
                val posY = (params[5.toByte()] as? Number)?.toFloat() ?: player.posY
                val updated = player.copy(posX = posX, posY = posY)
                players[id] = updated
                cb?.onPlayerMoved(updated)
            }
            6 -> { // HealthUpdate
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val cur = (params[2.toByte()] as? Number)?.toFloat() ?: return
                val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f
                cb?.onPlayerHealthChanged(id, cur, max)
            }
            29 -> { // NewCharacter
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (id == localId) return
                val name = params[1.toByte()] as? String ?: return
                val guild = params[8.toByte()] as? String
                val alliance = params[51.toByte()] as? String
                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
                val loc = params[7.toByte()] as? List<*>
                val posX = (loc?.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val posY = (loc?.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val player = Player(
                    id = id, name = name, guildName = guild, allianceName = alliance,
                    faction = faction, posX = posX, posY = posY, posZ = 0f,
                    currentHealth = 0, maxHealth = 0, isMounted = false
                )
                players[id] = player
                cb?.onPlayerJoined(player)
            }
            91 -> { // RegenHealth
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val cur = (params[2.toByte()] as? Number)?.toFloat() ?: return
                val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f
                cb?.onPlayerHealthChanged(id, cur, max)
            }
            209 -> { // Mounted
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val mounted = params[11.toByte()] == true || params[11.toByte()] == "true"
                players[id]?.let { cb?.onMountChanged(id, mounted) }
            }
            359 -> { // FlagChange
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val faction = (params[1.toByte()] as? Number)?.toInt() ?: return
                players[id]?.let { cb?.onFactionChanged(id, faction) }
            }
        }
    }

    private fun handleRequest(params: Map<Byte, Any>) {
        val opCode = (params[253.toByte()] as? Number)?.toInt() ?: return
        when (opCode) {
            21, 22 -> { // GetTeleportInfo / ChangeMap
                val pos = params[1.toByte()]
                val list = pos as? List<*>
                if (list != null && list.size >= 2) {
                    localX = (list[0] as? Number)?.toFloat() ?: 0f
                    localY = (list[1] as? Number)?.toFloat() ?: 0f
                }
            }
        }
    }

    private fun handleResponse(params: Map<Byte, Any>) {
        val code = (params[253.toByte()] as? Number)?.toInt() ?: return
        when (code) {
            2 -> { // JoinMap
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                localId = id
                val zone = params[8.toByte()] as? String ?: ""
                if (zone != currentZone && zone.isNotEmpty()) {
                    currentZone = zone
                    players.clear()
                    cb?.onZoneChanged(zone)
                }
                val posData = params[9.toByte()] as? ByteArray
                if (posData != null && posData.size >= 8) {
                    val buf = java.nio.ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    localX = buf.float
                    localY = buf.float
                }
            }
            35, 41 -> { // ClusterChange
                val zone = params[0.toByte()] as? String ?: return
                if (zone != currentZone) {
                    currentZone = zone
                    players.clear()
                    cb?.onZoneChanged(zone)
                }
            }
        }
    }

    fun getPlayers() = players.values.toList()
    fun getLocalPosition() = localX to localY
    fun getCurrentZone() = currentZone
}
