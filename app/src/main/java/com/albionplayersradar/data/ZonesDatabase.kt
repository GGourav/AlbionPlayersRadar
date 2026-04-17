package com.albionplayersradar.data

object ZonesDatabase {
    data class ZoneInfo(
        val name: String,
        val pvpType: String,
        val tier: Int
    )

    private val zones = mapOf(
        "THETFORD" to ZoneInfo("Thetford", "safe", 4),
        "LYMHURST" to ZoneInfo("Lymhurst", "safe", 3),
        "FORTSTERLING" to ZoneInfo("Fort Sterling", "safe", 2),
        "MARTLOCK" to ZoneInfo("Martlock", "safe", 5),
        "BRIDGEPWATCH" to ZoneInfo("Bridgewatch", "safe", 6),
        "CARLEON" to ZoneInfo("Caerleon", "black", 8),
        "THESTONE" to ZoneInfo("The Stone", "safe", 1),
        "MERLYN" to ZoneInfo("Merlyn", "safe", 3),
        "BLACKROCK" to ZoneInfo("Blackrock", "red", 6),
        "CAERLEON" to ZoneInfo("Caerleon Roads", "black", 8),
        "ROAD" to ZoneInfo("Royal Roads", "red", 7)
    )

    fun getZone(id: String): ZoneInfo? = zones[id]

    fun getPvpType(id: String): String = zones[id]?.pvpType ?: "safe"

    fun isDangerous(id: String): Boolean {
        val pvp = getPvpType(id)
        return pvp == "black" || pvp == "red"
    }
}
