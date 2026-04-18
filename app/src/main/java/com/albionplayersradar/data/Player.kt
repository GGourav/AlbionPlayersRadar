package com.albionplayersradar.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Player(
    val id: Long,
    val name: String,
    val guild: String,
    var posX: Float,
    var posY: Float,
    var deltaX: Float = 0f,
    var deltaY: Float = 0f,
    var lastUpdate: Long = System.currentTimeMillis(),
    var threat: ThreatLevel = ThreatLevel.PASSIVE,
    var isMounted: Boolean = false,
    var currentHealth: Float = 1f,
    var maxHealth: Float = 1f
) {
    fun updatePosition(newX: Float, newY: Float) {
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdate) / 1000f
        if (dt > 0) {
            deltaX = (newX - posX) / dt
            deltaY = (newY - posY) / dt
        }
        posX = newX
        posY = newY
        lastUpdate = now
    }

    fun distanceTo(other: Player): Float {
        val dx = posX - other.posX
        val dy = posY - other.posY
        return sqrt(dx * dx + dy * dy)
    }

    fun renderAngle(): Float {
        // Angle from movement delta — negated deltaY because screen Y is inverted
        return if (deltaX != 0f || deltaY != 0f) {
            atan2(-deltaY, deltaX).toFloat()
        } else {
            0f
        }
    }

    companion object {
        fun fromSpawnEvent(
            id: Long, name: String, guild: String,
            posX: Float, posY: Float, faction: Int
        ): Player {
            val threat = when {
                faction == 1 -> ThreatLevel.FACTION
                guild.isNotEmpty() -> ThreatLevel.HOSTILE
                else -> ThreatLevel.PASSIVE
            }
            return Player(id, name, guild, posX, posY, threat = threat)
        }
    }
}
