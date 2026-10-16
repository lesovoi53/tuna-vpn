package com.wireturn.app.domain

import androidx.annotation.StringRes

/** Labels and fixed values are resources; only explicitly sensitive values are masked. */
data class ConnectionFact(
    @param:StringRes val label: Int,
    val value: String = "",
    @param:StringRes val valueResource: Int? = null,
    val sensitive: Boolean = false,
    val labelText: String? = null,
) {
    fun masked(privacy: Boolean): Boolean = privacy && sensitive
}
