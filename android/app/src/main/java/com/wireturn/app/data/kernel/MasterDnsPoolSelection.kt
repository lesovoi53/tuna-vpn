package com.wireturn.app.data.kernel

/** Device-local membership only. Resolve current profile credentials immediately before launch. */
data class MasterDnsPoolSelection(
    val memberIds: List<String>,
    val strategy: String = "least_connections",
) {
    fun validate() {
        // Gson can construct a malformed value without Kotlin constructor null checks.
        val ids = requireNotNull(memberIds as List<String>?) { "Не задан состав пула Master DNS" }
        require(ids.size in 2..8) { "В пуле Master DNS должно быть от 2 до 8 серверов" }
        require(ids.all { !(it as String?).isNullOrBlank() } && ids.distinct().size == ids.size) {
            "В пуле Master DNS нужны уникальные идентификаторы серверов"
        }
        require(strategy in setOf("round_robin", "least_connections")) {
            "Неизвестная стратегия пула Master DNS"
        }
    }
}
