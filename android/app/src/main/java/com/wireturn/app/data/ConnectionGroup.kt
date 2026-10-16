package com.wireturn.app.data

enum class ConnectionCategory { BYPASS, VPN }
// SPEEDTEST is retained only to deserialize legacy groups without converting or executing them.
enum class GroupTestType { URL_TEST, SPEEDTEST }
enum class GroupSelectionMode { BEST, PRIORITY }
enum class GroupSpeedSelectionPolicy { SPEED_ONLY, BALANCED }
enum class GroupProbeMethod { GET, HEAD }
enum class GroupFamily(val label: String) {
    VPN("VPN"), QWDTT("qWDTT"), WEBDAV("WebDAV")
}
val Profile.groupFamily: GroupFamily? get() = when (kernelConfig) {
    is KernelConfig.Vpn -> GroupFamily.VPN
    is KernelConfig.Qwdtt -> GroupFamily.QWDTT
    is KernelConfig.Webdav -> GroupFamily.WEBDAV
    else -> null
}

val Profile.category: ConnectionCategory get() = if (kernelConfig is KernelConfig.Vpn) ConnectionCategory.VPN else ConnectionCategory.BYPASS

private val bypassName = Regex("обход[\\s\\p{Z}]+бс", RegexOption.IGNORE_CASE)

/** List placement is independent of the protocol family used by subscription group validation. */
val Profile.listCategory: ConnectionCategory get() =
    if (bypassName.containsMatchIn(name)) ConnectionCategory.BYPASS else category

data class ConnectionGroup(
    val id: String,
    val name: String,
    val category: ConnectionCategory,
    val type: GroupTestType,
    val memberIds: List<String>,
    val intervalSeconds: Int = 300,
    val timeoutSeconds: Int = 8,
    val holdSeconds: Int = 120,
    val confirmations: Int = 2,
    val improvementMs: Int = 50,
    // Legacy serialized speed fields are retained for reading/export only; no speed tests execute.
    val improvementPercent: Int = 20,
    val maxBytesPerCandidate: Long = 10L * 1024 * 1024,
    val automatic: Boolean = false,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val expectedStatus: Int = 204,
    val samples: Int = 3,
    val durationSeconds: Int = 5,
    val allowMobile: Boolean = false,
    val testOnConnect: Boolean = type == GroupTestType.URL_TEST,
    val freshnessSeconds: Int = 600,
    val routingProfileId: String = memberIds.firstOrNull().orEmpty(),
    val probeMethod: GroupProbeMethod? = null,
    val selectionMode: GroupSelectionMode? = null,
    val family: GroupFamily? = null,
    val repairRequired: Boolean = false,
    // Legacy serialized speed policy fields; ignored by URL-test and all execution paths.
    val speedSelectionPolicy: GroupSpeedSelectionPolicy? = null,
    val balancedMinSpeedPercent: Int? = null,
    val balancedLatencyImprovementMs: Int? = null,
    val balancedLatencyImprovementPercent: Int? = null,
    // Legacy serialized endpoint fields; never resolved or contacted.
    val speedEndpointMode: String? = null,
    val speedEndpointId: String? = null,
    val speedEndpointCustomUrl: String? = null
) {
    val effectiveSelectionMode: GroupSelectionMode get() = selectionMode ?: GroupSelectionMode.BEST
    // Nullable for existing Gson records without this field.
    val effectiveProbeMethod: GroupProbeMethod
        get() = probeMethod ?: GroupProbeMethod.GET
    /** Retain legacy BYPASS groups for repair/export, but never execute their probes. */
    fun validateForTesting(profiles: List<Profile>) {
        require(type == GroupTestType.URL_TEST) { "Поддерживается только URL-test" }
        validate(profiles)
        require(category == ConnectionCategory.VPN && memberIds.all { id ->
            profiles.single { it.id == id }.listCategory == ConnectionCategory.VPN
        }) { "URL-test недоступен для «Обход БС». Проверьте состав группы." }
    }

    /** Structural validation is shared by import and execution. */
    fun validate(profiles: List<Profile>, allowRepair: Boolean = false) {
        val storedRepair = repairRequired && allowRepair
        require(!repairRequired || storedRepair) { "Состав группы изменился после обновления. Проверьте участников и профиль маршрутизации, затем сохраните группу." }
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 200)
        require(memberIds.size in (if (storedRepair) 0 else 2)..1000 && memberIds.distinct().size == memberIds.size) { "Выберите от 2 до 1000 разных серверов" }
        val byId = profiles.associateBy { it.id }
        require(byId.size == profiles.size) { "Duplicate profile IDs" }
        memberIds.forEach { member ->
            val profile = requireNotNull(byId[member]) { "Unknown group member" }
            require(profile.kernelConfig !is KernelConfig.MasterDns) { "MasterDnsVPN нельзя добавлять в группы" }
            require(profile.category == category) { "Cannot mix VPN and bypass profiles" }
        }
        val families = memberIds.map { requireNotNull(byId[it]?.groupFamily) { "Протокол не поддерживает группы" } }.toSet()
        require((storedRepair && families.isEmpty()) || (families.size == 1 && (family == null || family in families))) {
            "Используйте отдельные группы VPN, qWDTT и WebDAV"
        }
        require(intervalSeconds in 30..86400 && timeoutSeconds in 1..120 && holdSeconds in 0..86400) { "Интервал: 30–86400 с; таймаут: 1–120 с; удержание: 0–86400 с" }
        require(confirmations in 1..10 && improvementMs >= 0) { "Подтверждений: 1–10; порог задержки неотрицательный" }
        val url = java.net.URI(testUrl)
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null) { "Нужен HTTPS URL без пароля и фрагмента" }
        require(url.port == -1 || url.port in 1..65535) { "Порт тестового адреса: 1–65535" }
        require(expectedStatus in 200..299 && samples in 1..5)
        require(freshnessSeconds in 30..86400 && (routingProfileId in memberIds || (storedRepair && routingProfileId.isEmpty())))
    }
}
