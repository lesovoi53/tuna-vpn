package com.wireturn.app.domain

/** Presentation of Android permission and power-management checks on the home screen. */
data class HomePermissionPrompts(
    val showBatteryHint: Boolean,
    val showNotificationsHint: Boolean,
) {
    val visible: Boolean get() = showBatteryHint || showNotificationsHint
    // The Android power allowlist is not a runtime permission, nor does it
    // describe manufacturer-specific "No restrictions" settings.
    val isError: Boolean get() = showNotificationsHint

    companion object {
        fun resolve(
            hasNotificationPermission: Boolean,
            isIgnoringBatteryOptimizations: Boolean,
            batteryHintDismissed: Boolean,
            notificationsHintDismissed: Boolean = false,
        ) = HomePermissionPrompts(
            showBatteryHint = !isIgnoringBatteryOptimizations && !batteryHintDismissed,
            showNotificationsHint = !hasNotificationPermission && !notificationsHintDismissed,
        )
    }
}
