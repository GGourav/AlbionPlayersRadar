package com.albionplayersradar.data

data class Player(
    val id: Long,
    val name: String,
    val guildName: String?,
    val allianceName: String?,
    val faction: Int,
    var posX: Float,
    var posY: Float,
    var posZ: Float,
    var currentHealth: Int,
    var maxHealth: Int,
    var isMounted: Boolean,
    var detectedAt: Long = System.currentTimeMillis()
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

    fun touch() {
        detectedAt = System.currentTimeMillis()
    }
}

enum class ThreatLevel { PASSIVE, FACTION, HOSTILE }
