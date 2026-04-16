package com.albionplayersradar.parser

import android.util.Log
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ZoneInfo
import com.albionplayersradar.data.ZonesDatabase

class EventRouter(
    private val onPlayerSpawn: (Player) -> Unit,
    private val onPlayerLeave: (Long) -> Unit,
    private val onPlayerMove: (Long, Float, Float) -> Unit,
    private val onPlayerHealth: (Long, Int, Int) -> Unit,
    private val onZoneChange: (String, ZoneInfo) -> Unit,
    private val onLocalPlayerJoined: (Long, Float, Float, String?) -> Unit
) : PhotonPacketParser.PacketCallback {

    private val TAG = "EventRouter"
    private val players = mutableMapOf<Long, Player>()
    private var localPlayerId: Long = -1
    private var currentZoneId: String = ""

    companion object {
        const val EVENT_LEAVE = 1
        const val EVENT_JOIN_FINISHED = 2
        const val EVENT_MOVE = 3
        const val EVENT_NEW_CHARACTER = 29
        const val EVENT_HEALTH_UPDATE = 6
        const val EVENT_REGEN_HEALTH = 91
        const val EVENT_MOUNTED = 209
        const val EVENT_EQUIP_CHANGED = 90
        const val OP_MOVE_REQUEST = 21
        const val OP_MOVE_REQUEST_V2 = 22
    }

    override fun onEvent(eventCode: Int, params: Map<Int, Any?>) {
        when (eventCode) {
            EVENT_LEAVE -> handleLeave(params)
            EVENT_MOVE -> handleMove(params)
            EVENT_NEW_CHARACTER -> handleNewCharacter(params)
            EVENT_HEALTH_UPDATE, EVENT_REGEN_HEALTH -> handleHealthUpdate(params)
            EVENT_MOUNTED -> handleMounted(params)
        }
    }

    override fun onRequest(opCode: Int, params: Map<Int, Any?>) {
        if (opCode == OP_MOVE_REQUEST || opCode == OP_MOVE_REQUEST_V2) {
            handleLocalMove(params)
        }
    }

    override fun onResponse(opCode: Int, params: Map<Int, Any?>) {
        when (opCode) {
            2 -> handleJoinFinished(params)
            41 -> handleChangeCluster(params)
        }
    }

    private fun handleLeave(params: Map<Int, Any?>) {
        val id = params[0] as? Long ?: return
        players.remove(id)
        onPlayerLeave(id)
    }

    private fun handleMove(params: Map<Int, Any?>) {
        val id = params[0] as? Long ?: return
        if (id == localPlayerId) return

        val posX = (params[4] as? Number)?.toFloat() ?: return
        val posY = (params[5] as? Number)?.toFloat() ?: return

        players[id]?.let { p ->
            players[id] = p.copy(posX = posX, posY = posY)
        }
        onPlayerMove(id, posX, posY)
    }

    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val id = params[0] as? Long ?: params[1] as? Long ?: return
        val name = params[1] as? String ?: return

        val guild = params[8] as? String
        val alliance = params[51] as? String
        val faction = (params[53] as? Number)?.toInt() ?: 0
        val posX = (params[4] as? Number)?.toFloat() ?: 0f
        val posY = (params[5] as? Number)?.toFloat() ?: 0f
        val health = (params[2] as? Number)?.toInt() ?: 0
        val maxHealth = (params[3] as? Number)?.toInt() ?: 0

        val player = Player(
            id = id,
            name = name,
            guildName = guild,
            allianceName = alliance,
            faction = faction,
            posX = posX,
            posY = posY,
            posZ = 0f,
            currentHealth = health,
            maxHealth = maxHealth,
            isMounted = false
        )

        players[id] = player
        onPlayerSpawn(player)
        Log.d(TAG, "Spawn: ${player.name} guild=${guild ?: "-"} faction=$faction")
    }

    private fun handleHealthUpdate(params: Map<Int, Any?>) {
        val id = params[0] as? Long ?: return
        val health = (params[2] as? Number)?.toInt() ?: return
        val maxHealth = (params[3] as? Number)?.toInt() ?: return

        players[id]?.let { p ->
            players[id] = p.copy(currentHealth = health, maxHealth = maxHealth)
        }
        onPlayerHealth(id, health, maxHealth)
    }

    private fun handleMounted(params: Map<Int, Any?>) {
        val id = params[0] as? Long ?: return
        val mounted = params[11] == true || params[11] == "true"

        players[id]?.let { p ->
            players[id] = p.copy(isMounted = mounted)
        }
    }

    private fun handleLocalMove(params: Map<Int, Any?>) {
        val posData = params[1] ?: return

        val posX: Float
        val posY: Float

        when (posData) {
            is FloatArray -> {
                posX = posData.getOrNull(0) ?: 0f
                posY = posData.getOrNull(1) ?: 0f
            }
            is List<*> -> {
                posX = (posData[0] as? Number)?.toFloat() ?: 0f
                posY = (posData[1] as? Number)?.toFloat() ?: 0f
            }
            is ByteArray -> {
                if (posData.size >= 8) {
                    posX = Float.fromBits(
                        (posData[0].toInt() and 0xFF) or
                        ((posData[1].toInt() and 0xFF) shl 8) or
                        ((posData[2].toInt() and 0xFF) shl 16) or
                        ((posData[3].toInt() and 0xFF) shl 24)
                    )
                    posY = Float.fromBits(
                        (posData[4].toInt() and 0xFF) or
                        ((posData[5].toInt() and 0xFF) shl 8) or
                        ((posData[6].toInt() and 0xFF) shl 16) or
                        ((posData[7].toInt() and 0xFF) shl 24)
                    )
                } else return
            }
            else -> return
        }

        onLocalPlayerJoined(localPlayerId, posX, posY, currentZoneId)
    }

    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val zoneId = params[8] as? String ?: return
        val zone = ZonesDatabase.getZone(zoneId)

        // Extract local player position
        var posX = 0f
        var posY = 0f

        (params[9] as? FloatArray)?.let {
            posX = it.getOrNull(0) ?: 0f
            posY = it.getOrNull(1) ?: 0f
        }

        localPlayerId = (params[0] as? Long) ?: -1
        currentZoneId = zoneId

        players.clear()
        onZoneChange(zoneId, zone ?: ZoneInfo(zoneId, "safe", 0))
        onLocalPlayerJoined(localPlayerId, posX, posY, zoneId)
        Log.d(TAG, "Joined zone: $zoneId (${zone?.pvpType ?: "unknown"})")
    }

    private fun handleChangeCluster(params: Map<Int, Any?>) {
        val newZoneId = params[0] as? String ?: return
        val zone = ZonesDatabase.getZone(newZoneId)
        currentZoneId = newZoneId

        players.clear()
        onZoneChange(newZoneId, zone ?: ZoneInfo(newZoneId, "black", 0))
        Log.d(TAG, "Cluster changed: $newZoneId")
    }

    fun getAllPlayers() = players.values.toList()
}
