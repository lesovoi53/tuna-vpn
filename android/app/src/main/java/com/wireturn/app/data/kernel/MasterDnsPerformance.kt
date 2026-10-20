package com.wireturn.app.data.kernel

/**
 * Local requests layered over the original profile. The server may clamp packet
 * duplication during session negotiation; these values do not claim measured speed.
 */
enum class MasterDnsPerformanceMode(
    val wireValue: String,
    val title: String,
    val balancingStrategy: Int,
    val packetDuplication: Int,
    val setupPacketDuplication: Int
) {
    SPEED("speed", "Скорость", 6, 1, 2),
    BALANCED("balanced", "Баланс", 6, 2, 3),
    STABLE("stable", "Устойчивость", 8, 3, 4);

    val overrides: Map<String, String>
        get() = mapOf(
            "RESOLVER_BALANCING_STRATEGY" to balancingStrategy.toString(),
            "PACKET_DUPLICATION_COUNT" to packetDuplication.toString(),
            "SETUP_PACKET_DUPLICATION_COUNT" to setupPacketDuplication.toString()
        )

    companion object {
        fun fromWire(value: String): MasterDnsPerformanceMode =
            requireNotNull(entries.find { it.wireValue == value }) { "Неизвестный режим MasterDnsVPN" }
    }
}
