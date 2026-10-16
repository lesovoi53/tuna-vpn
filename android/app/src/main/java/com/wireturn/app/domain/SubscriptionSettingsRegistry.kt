package com.wireturn.app.domain

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Only explicitly mapped user preferences may be proposed by a subscription. */
object SubscriptionSettingsRegistry {
    val localOnly = setOf("vpn.filteringEnabled", "vpn.bypassMode", "vpn.excludedApps")
    val hidden = setOf("connection.autoLaunch.enabled", "connection.autoLaunch.checkUrl", "connection.autoLaunch.intervalMinutes")
    fun isApplicable(path: String) = path in fields && path !in localOnly && path !in hidden
    data class Field(val storageKey: String, val type: String, val label: String, val options: Set<String> = emptySet(), val min: Int = 0, val max: Int = 0)
    val fields = linkedMapOf(
        "appearance.themeMode" to Field("theme_mode", "enum", "Тема", setOf("SYSTEM", "LIGHT", "DARK")),
        "appearance.dynamicTheme" to Field("dynamic_theme", "boolean", "Динамические цвета"),
        "appearance.language" to Field("app_language", "enum", "Язык", setOf("system", "ru", "en")),
        "appearance.privacyMode" to Field("privacy_mode", "boolean", "Скрывать реквизиты"),
        "appearance.profileGestures" to Field("profiles_gesture_enabled", "boolean", "Жесты профилей"),
        "connection.waitForNetwork" to Field("wait_for_network", "boolean", "Ожидать сеть"),
        "connection.restartOnNetworkChange" to Field("restart_on_network_change", "boolean", "Переподключаться при смене сети"),
        "connection.autoLaunch.enabled" to Field("auto_launch_enabled", "boolean", "Автозапуск"),
        "connection.autoLaunch.checkUrl" to Field("auto_launch_url", "url", "URL проверки автозапуска"),
        "connection.autoLaunch.intervalMinutes" to Field("auto_launch_interval", "integer", "Интервал автозапуска, минуты", min = 1, max = 1440),
        "connection.pingUrl" to Field("ping_url", "url", "URL проверки соединения"),
        "vpn.hideSystemApps" to Field("vpn_hide_system_apps", "boolean", "Скрывать системные приложения в списке"),
        "vpn.groupAppsByLetter" to Field("vpn_group_apps_by_letter", "boolean", "Группировать приложения по буквам"),
        "vpn.filteringEnabled" to Field("vpn_filtering_enabled", "boolean", "Фильтрация приложений"),
        "vpn.bypassMode" to Field("vpn_bypass_mode", "boolean", "Режим исключения приложений"),
        "vpn.excludedApps" to Field("proxy_excluded_apps", "packages", "Список приложений (package names)"),
        "connection.dns" to Field("client_dns", "dns", "DNS транспорта"),
        "connection.goDnsGo" to Field("go_dns_go", "boolean", "Go DNS"),
        "routing.localDns" to Field("xray_dns", "dns", "DNS локальной маршрутизации")
    )

    fun validate(path: String, value: JsonElement) {
        val f = requireNotNull(fields[path]) { "Unsupported setting" }
        require(!value.isJsonNull) { "Null is not a reset" }
        when (f.type) {
            "boolean" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            "integer" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("[0-9]+")) && value.asString.toLong() in f.min.toLong()..f.max.toLong())
            "packages" -> {
                require(value.isJsonArray && value.asJsonArray.size() <= 1000)
                value.asJsonArray.forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))) }
            }
            else -> {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 8192)
                when (f.type) {
                    "enum" -> require(value.asString in f.options)
                    "url" -> {
                        val u = java.net.URI(value.asString)
                        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null)
                    }
                    "dns" -> {
                        val addresses = com.wireturn.app.data.DnsPresets.normalize(value.asString).split(',')
                        require(addresses.size in 1..16)
                        addresses.forEach { address ->
                            val u = java.net.URI(if (address.contains("://")) address else "tcp://$address")
                            require(u.scheme in setOf("https", "tcp", "udp") && !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null && (u.port == -1 || u.port in 1..65535))
                        }
                    }
                }
            }
        }
    }

    fun values(document: JsonObject): Map<String, JsonElement> = document.getAsJsonObject("settings")?.getAsJsonObject("values")?.entrySet()?.associate { it.key to it.value }.orEmpty()
}
