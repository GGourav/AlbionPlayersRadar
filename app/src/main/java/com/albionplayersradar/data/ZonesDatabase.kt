package com.albionplayersradar.data

object ZonesDatabase {

    private val zones = mapOf(
        "THESTONE-1" to ZoneInfo("The Stone", "safe", 0),
        "THESTONE-2" to ZoneInfo("The Stone", "safe", 0),
        "CAERLEON" to ZoneInfo("Caerleon", "safe", 0),
        "BRITTAIN" to ZoneInfo("Brittain", "safe", 0),
        "MERLYN" to ZoneInfo("Merlyn", "safe", 0),
        "MARCH" to ZoneInfo("March", "safe", 0),
        "BLACKROCK" to ZoneInfo("Blackrock", "red", 0),
        // Add more zones as needed
    )

    fun getZone(zoneId: String): ZoneInfo? = zones[zoneId]

    fun getPvpType(zoneId: String): String = zones[zoneId]?.pvpType ?: "safe"

    fun isDangerous(zoneId: String): Boolean {
        val pvp = getPvpType(zoneId)
        return pvp == "black" || pvp == "red"
    }
}
