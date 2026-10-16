package com.wireturn.app.ui.screens

import com.wireturn.app.data.Subscription
import com.wireturn.app.domain.SubscriptionPolicyParser

internal fun routingSourceLabel(source: String, subscriptions: List<Subscription>): String {
    if (source.isBlank()) return "Свои правила — без подписки"
    val policy = runCatching { SubscriptionPolicyParser.parseHapp(source) }.getOrNull()
        ?: return "Ошибка сохранённой маршрутизации"
    val owners = subscriptions.filter { it.routingSource == source }
    return if (owners.isNotEmpty()) "Из подписки: ${owners.joinToString { it.name }} · ${policy.name}"
    else "Импортированные правила: ${policy.name} (сохранённая копия)"
}
