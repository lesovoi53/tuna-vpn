package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.GroupTestType
import com.wireturn.app.data.Profile
import com.wireturn.app.data.groupFamily
import com.wireturn.app.data.listCategory

/** Local availability only. Subscription parsing and stored group data remain compatible. */
object ManualGroupPolicy {
    fun allows(candidate: ConnectionGroup): Boolean = candidate.category == ConnectionCategory.VPN &&
        candidate.type == GroupTestType.URL_TEST

    fun canSelectMember(group: ConnectionGroup, profile: Profile): Boolean = allows(group) &&
        profile.listCategory == ConnectionCategory.VPN && profile.groupFamily != null &&
        (group.family == null || group.family == profile.groupFamily)

    fun activeGroupToClear(activeId: String?, groups: List<ConnectionGroup>): String? =
        activeId?.takeIf { id -> groups.any { it.id == id && !allows(it) } }
}
