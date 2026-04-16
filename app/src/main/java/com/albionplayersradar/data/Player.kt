package com.albionplayersradar.data

data class Player(
    val id: Long,
    val name: String,
    val guildName: String?,
    val allianceName: String?,
    val faction: Int,
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val currentHealth: Int,
    val maxHealth: Int,
    val isMounted: Boolean,
    val detectedAt: Long = System.currentTimeMillis()
) {
    val healthPercent: Float
        get() = if (maxHealth > 0) currentHealth.toFloat() / maxHealth else 0f

    val isHostile: Boolean
        get() = faction == 255

    val isPassive: Boolean
        get() = faction == 0

    val isFactionPlayer: Boolean
        get() = faction in 1..6

    val threatLevel: ThreatLevel
        get() = when {
            isHostile -> ThreatLevel.HOSTILE
            isFactionPlayer -> ThreatLevel.FACTION
            else -> ThreatLevel.PASSIVE
        }
}

enum class ThreatLevel { PASSIVE, FACTION, HOSTILE }
