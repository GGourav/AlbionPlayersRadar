package com.albionplayersradar.parser

import android.util.Log
import com.albionplayersradar.data.Player
import com.albionplayersradar.ui.MainActivity

object EventRouter {
    private val players = mutableMapOf<Long, Player>()
    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f

    fun onPacket(data: ByteArray) {
        PhotonPacketParser.parsePacket(data)
    }

    fun setLocalPlayer(id: Long, x: Float, y: Float) {
        localId = id; localX = x; localY = y
    }

    fun getPlayers() = players.values.toList()
    fun getLocalPosition() = localX to localY

    init {
        PhotonPacketParser.listener = object : PhotonPacketParser.PacketListener {
            override fun onPlayerSpawned(id: Long, name: String, guild: String, alliance: String, faction: Int, posX: Float, posY: Float) {
                if (id == localId) return
                players[id] = Player(id, name, guild, alliance, faction, posX, posY, 0f, 0f, 0f, false, System.currentTimeMillis())
                MainActivity.broadcastPlayerJoined(id, name, guild, alliance, faction, posX, posY)
            }

            override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
                players[id]?.let {
                    val p = it.copy(posX = posX, posY = posY, detectedAt = System.currentTimeMillis())
                    players[id] = p
                    MainActivity.broadcastPlayerMove(id, posX, posY)
                }
            }

            override fun onPlayerLeft(id: Long) {
                if (players.remove(id) != null) {
                    MainActivity.broadcastPlayerLeave(id)
                }
            }

            override fun onHealthChanged(id: Long, health: Float, maxHealth: Float) {
                players[id]?.let {
                    players[id] = it.copy(currentHealth = health, maxHealth = maxHealth)
                    MainActivity.broadcastHealthChanged(id, health, maxHealth)
                }
            }

            override fun onFactionChanged(id: Long, faction: Int) {
                players[id]?.let {
                    players[id] = it.copy(faction = faction)
                }
            }

            override fun onMountChanged(id: Long, isMounted: Boolean) {
                players[id]?.let {
                    players[id] = it.copy(isMounted = isMounted)
                }
            }

            override fun onLocalMoved(posX: Float, posY: Float) {
                localX = posX; localY = posY
                MainActivity.broadcastLocalMove(posX, posY)
            }

            override fun onZoneChanged(zoneId: String) {
                players.clear()
                MainActivity.broadcastZone(zoneId)
            }
        }
    }
}
