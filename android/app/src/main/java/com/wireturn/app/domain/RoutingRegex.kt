package com.wireturn.app.domain

import com.google.re2j.Pattern

/** Linear-time RE2 syntax, shared by input validation and the local route preview.
 * The generated complete policy is still validated by the pinned Xray core.
 */
object RoutingRegex {
    fun isValid(expression: String): Boolean = expression.isNotEmpty() &&
        runCatching { compile(expression) }.isSuccess

    fun matches(expression: String, host: String): Boolean? =
        runCatching { compile(expression).matcher(host).find() }.getOrNull()

    private fun compile(expression: String): Pattern {
        // RE2/J accepts \C (one byte), but Go regexp, used by Xray, does not.
        var escaped = false
        var quoted = false
        for (c in expression) {
            if (escaped) {
                if (c == 'Q' && !quoted) quoted = true
                else if (c == 'E' && quoted) quoted = false
                else require(quoted || c != 'C') { "Go regexp does not support byte escape" }
                escaped = false
            } else if (c == '\\') escaped = true
        }
        return Pattern.compile(expression)
    }
}
