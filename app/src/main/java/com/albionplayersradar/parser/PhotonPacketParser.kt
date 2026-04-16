package com.albionplayersradar.parser

import android.util.Log

class PhotonPacketParser {

    private val TAG = "PhotonPacketParser"

    data class PlayerInfo(
        val id: Int,
        val name: String,
        val guild: String?,
        val alliance: String?,
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val health: Float,
        val maxHealth: Float,
        val isMounted: Boolean,
        val faction: Int,
        val equipment: List<Int>?,
        val spells: List<Int>?,
        val attackRange: Float,
        val type: Int,
        val lastUpdate: Long = System.currentTimeMillis()
    )

    interface PlayerListener {
        fun onPlayerFound(player: PlayerInfo)
        fun onPlayerLeft(playerId: Int)
        fun onPlayerMoved(player: PlayerInfo)
        fun onPlayerHealthChanged(playerId: Int, health: Float, maxHealth: Float)
    }

    private val listeners = mutableListOf<PlayerListener>()
    private val players = mutableMapOf<Int, PlayerInfo>()
    private val pendingLeave = mutableSetOf<Int>()

    companion object {
        const val EVENT_LEAVE = 1
        const val EVENT_MOVE = 3
        const val EVENT_NEW_CHARACTER = 29
        const val EVENT_EQUIPMENT_CHANGED = 5
        const val EVENT_HEALTH_UPDATE = 6
        const val EVENT_REGEN_HEALTH = 91
        const val EVENT_MOUNTED = 209
        const val EVENT_FACTION_CHANGED = 359

        const val OP_JOIN_MAP = 2
        const val OP_CLUSTER_CHANGE = 35
        const val OP_CLUSTER_CHANGE_NEW = 41
        const val OP_MOVE_REQUEST = 22

        private const val KEY_ID = 0
        private const val KEY_NAME = 1
        private const val KEY_GUILD = 8
        private const val KEY_ALLIANCE = 51
        private const val KEY_EQUIPMENT = 40
        private const val KEY_SPELLS = 43
        private const val KEY_HEALTH_CURRENT = 2
        private const val KEY_HEALTH_MAX = 3
        private const val KEY_FACTION = 53
        private const val KEY_MOUNTED = 11
        private const val KEY_POSITION_X = 4
        private const val KEY_POSITION_Y = 5
        private const val KEY_POSITION_Z = 6
        private const val KEY_TYPE = 1
        private const val KEY_ENCHANT = 33
        private const val KEY_CHARACTER_STATS = 3
        private const val KEY_OR = 253
    }

    fun addListener(listener: PlayerListener) = listeners.add(listener)
    fun removeListener(listener: PlayerListener) = listeners.remove(listener)

    fun parseAndDispatch(data: ByteArray) {
        val result = PhotonDeserializer.parsePacket(data)

        for (event in result.events) {
            val eventCode = event.code
            val params = event.params

            when (eventCode) {
                EVENT_NEW_CHARACTER -> handleNewCharacter(params)
                EVENT_LEAVE -> handleLeave(params)
                EVENT_MOVE -> handleMove(params)
                EVENT_HEALTH_UPDATE -> handleHealthUpdate(params)
                EVENT_REGEN_HEALTH -> handleRegenHealth(params)
                EVENT_MOUNTED -> handleMounted(params)
                EVENT_FACTION_CHANGED -> handleFactionChanged(params)
            }
        }

        for (response in result.responses) {
            val opCode = response.opCode
            val params = response.params

            when (opCode) {
                OP_JOIN_MAP -> handleJoinMap(params)
                OP_CLUSTER_CHANGE, OP_CLUSTER_CHANGE_NEW -> handleClusterChange(params)
            }
        }

        for (request in result.requests) {
            val opCode = request.opCode
            val params = request.params

            if (opCode == OP_MOVE_REQUEST) {
                handleMoveRequest(params)
            }
        }
    }

    private fun handleNewCharacter(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val name = params[KEY_NAME] as? String ?: return
        val guild = params[KEY_GUILD] as? String
        val alliance = params[KEY_ALLIANCE] as? String
        val faction = (params[KEY_FACTION] as? Number)?.toInt() ?: 0
        val equipment = (params[KEY_EQUIPMENT] as? List<*>?)?.mapNotNull { (it as? Number)?.toInt() }
        val spells = (params[KEY_SPELLS] as? List<*>?)?.mapNotNull { (it as? Number)?.toInt() }

        val health = 0f
        val maxHealth = 0f
        val isMounted = false

        val player = PlayerInfo(
            id = id,
            name = name,
            guild = guild,
            alliance = alliance,
            posX = 0f,
            posY = 0f,
            posZ = 0f,
            health = health,
            maxHealth = maxHealth,
            isMounted = isMounted,
            faction = faction,
            equipment = equipment,
            spells = spells,
            attackRange = 0f,
            type = 0
        )

        players[id] = player
        pendingLeave.remove(id)

        for (listener in listeners) {
            try { listener.onPlayerFound(player) } catch (e: Exception) { }
        }
    }

    private fun handleLeave(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        pendingLeave.add(id)

        players.remove(id)

        for (listener in listeners) {
            try { listener.onPlayerLeft(id) } catch (e: Exception) { }
        }
    }

    private fun handleMove(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val player = players[id] ?: return

        val posX = (params[KEY_POSITION_X] as? Number)?.toFloat() ?: player.posX
        val posY = (params[KEY_POSITION_Y] as? Number)?.toFloat() ?: player.posY
        val posZ = (params[KEY_POSITION_Z] as? Number)?.toFloat() ?: player.posZ

        val updated = player.copy(
            posX = posX,
            posY = posY,
            posZ = posZ,
            lastUpdate = System.currentTimeMillis()
        )

        players[id] = updated

        for (listener in listeners) {
            try { listener.onPlayerMoved(updated) } catch (e: Exception) { }
        }
    }

    private fun handleHealthUpdate(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val player = players[id] ?: return

        val health = (params[KEY_HEALTH_CURRENT] as? Number)?.toFloat() ?: return
        val maxHealth = (params[KEY_HEALTH_MAX] as? Number)?.toFloat() ?: return

        val updated = player.copy(
            health = health,
            maxHealth = maxHealth,
            lastUpdate = System.currentTimeMillis()
        )

        players[id] = updated

        for (listener in listeners) {
            try { listener.onPlayerHealthChanged(id, health, maxHealth) } catch (e: Exception) { }
        }
    }

    private fun handleRegenHealth(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val player = players[id] ?: return

        val health = (params[KEY_HEALTH_CURRENT] as? Number)?.toFloat() ?: return
        val maxHealth = (params[KEY_HEALTH_MAX] as? Number)?.toFloat() ?: return

        val updated = player.copy(
            health = health,
            maxHealth = maxHealth,
            lastUpdate = System.currentTimeMillis()
        )

        players[id] = updated

        for (listener in listeners) {
            try { listener.onPlayerHealthChanged(id, health, maxHealth) } catch (e: Exception) { }
        }
    }

    private fun handleMounted(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val player = players[id] ?: return

        val mountedVal = params[KEY_MOUNTED]
        val isMounted = when (mountedVal) {
            is Boolean -> mountedVal
            is String -> mountedVal == "true"
            is Number -> mountedVal.toInt() != 0
            else -> false
        }

        val updated = player.copy(isMounted = isMounted, lastUpdate = System.currentTimeMillis())
        players[id] = updated
    }

    private fun handleFactionChanged(params: Map<Int, Any>) {
        val id = (params[KEY_ID] as? Number)?.toInt() ?: return
        val player = players[id] ?: return

        val faction = (params[KEY_FACTION] as? Number)?.toInt() ?: return

        val updated = player.copy(faction = faction, lastUpdate = System.currentTimeMillis())
        players[id] = updated
    }

    private fun handleJoinMap(params: Map<Int, Any>) {
        players.clear()
        pendingLeave.clear()
        Log.d(TAG, "Map joined - cleared all tracked players")
    }

    private fun handleClusterChange(params: Map<Int, Any>) {
        players.clear()
        pendingLeave.clear()
        Log.d(TAG, "Cluster changed - cleared all tracked players")
    }

    private fun handleMoveRequest(params: Map<Int, Any>) {
        // Player movement requests tracked for local player position
    }

    fun getPlayers(): List<PlayerInfo> = players.values.toList()

    fun getPlayer(id: Int): PlayerInfo? = players[id]
}
