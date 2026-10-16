package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.Profile

/** Presentation only: choosing a stopped group must not select or start a member. */
object HomeSelection {
    fun config(selected: ClientConfig, running: ClientConfig?, connected: Boolean,
               group: ConnectionGroup?): ClientConfig? = when {
        connected -> running ?: selected
        group != null -> null
        else -> selected
    }

    fun member(group: ConnectionGroup, current: Profile?, connected: Boolean): Profile? =
        current?.takeIf { connected && it.id in group.memberIds }
}
