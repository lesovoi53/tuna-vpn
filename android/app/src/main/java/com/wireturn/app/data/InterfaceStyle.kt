package com.wireturn.app.data

/** Presentation only: switching styles never rewrites connection or subscription settings. */
enum class InterfaceStyle {
    ORBIT, CLASSIC;

    companion object {
        fun fromStored(value: String?): InterfaceStyle = entries.firstOrNull { it.name == value } ?: ORBIT
    }
}
