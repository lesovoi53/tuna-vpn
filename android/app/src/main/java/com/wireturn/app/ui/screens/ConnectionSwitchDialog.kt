package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.GroupTestType
import com.wireturn.app.data.Profile
import com.wireturn.app.data.listCategory
import com.wireturn.app.domain.ManualGroupPolicy
import com.wireturn.app.domain.ProbeKind
import com.wireturn.app.domain.ProfileSections
import com.wireturn.app.domain.SavedProbeResult
import com.wireturn.app.domain.latestProbe
import com.wireturn.app.viewmodel.MainViewModel

private data class SwitchTarget(val profileId: String? = null, val group: ConnectionGroup? = null)

/** Browsing and selecting a row only updates the local draft; connect requires the bottom button. */
@Composable
internal fun ConnectionSwitchDialog(viewModel: MainViewModel, source: Profile? = null, onDismiss: () -> Unit) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val groups by viewModel.connectionGroups.collectAsStateWithLifecycle()
    val history by viewModel.probeHistory.collectAsStateWithLifecycle()
    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val activeGroupId by viewModel.activeConnectionGroupId.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requestedTarget = remember { mutableStateOf<SwitchTarget?>(null) }
    val now = rememberVisibleProbeClock()
    val connect = rememberVpnConnectAction(viewModel) {
        // Freeze the explicitly confirmed target, including across Android's VPN permission dialog.
        requestedTarget.value?.let { target ->
            target.group?.let { viewModel.connectGroup(it, onDismiss) }
                ?: target.profileId?.let { viewModel.connectProfile(it, onDismiss) }
        }
    }
    val query = search.trim()
    val matchingProfiles = remember(profiles, query, source) {
        profiles.filter { (source == null || com.wireturn.app.domain.ProbeEligibility.sameSwitchType(source, it)) &&
            (query.isEmpty() || it.name.contains(query, ignoreCase = true) ||
            ProfileSections.section(it).title.contains(query, ignoreCase = true)) }
    }
    val balancers = remember(matchingProfiles) { ProfileSections.pinnedBalancers(matchingProfiles) }
    val ordinaryProfiles = remember(matchingProfiles) { matchingProfiles.filterNot(ProfileSections::isPinnedBalancer) }
    val matchingGroups = remember(groups, profiles, query, source) {
        groups.filter { it.type == GroupTestType.URL_TEST && (source == null || com.wireturn.app.domain.ProbeEligibility.sameSwitchGroup(source, it, profiles)) && ManualGroupPolicy.allows(it) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
    }
    val selectedProfile = matchingProfiles.firstOrNull { pendingKey == "profile:${it.id}" }
    val selectedGroup = matchingGroups.firstOrNull { pendingKey == "group:${it.id}" && ManualGroupPolicy.allows(it) }
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сменить подключение", style = MaterialTheme.typography.titleLarge)
                Text("Выберите строку и нажмите «Подключиться». Просмотр не запускает тест и не меняет подключение.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                    label = { Text("Поиск профиля или группы") }, singleLine = true)
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).weight(1f, fill = false).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (balancers.isNotEmpty()) {
                        item(key = "balancer-title") { SwitchSectionTitle("Балансировщики Xray") }
                        items(balancers, key = { "profile:${it.id}" }) { profile ->
                            SwitchProfileRow(profile, history, now, currentProfileId, pendingKey) { pendingKey = "profile:${profile.id}" }
                        }
                    }
                    if (matchingGroups.isNotEmpty()) {
                        item(key = "group-title") { SwitchSectionTitle("Группы") }
                        items(matchingGroups, key = { "group:${it.id}" }) { group ->
                            val summary = remember(group, profilesById, history, now) {
                                val latest = group.memberIds.mapNotNull { id -> profilesById[id]?.let { profile ->
                                    history.latestProbe(profile, ProbeKind.URL, group.id)?.let { profile to it }
                                } }.maxByOrNull { it.second.measuredAt }
                                if (latest == null) "URL-test: нет результата"
                                else "${latest.first.name} · ${switchProbeBrief(latest.first, latest.second, now)}"
                            }
                            SwitchRow(group.name,
                                "Группа · URL-test · ${group.memberIds.size} серверов",
                                summary, pendingKey == "group:${group.id}",
                                if (activeGroupId == group.id) "✓ Текущая группа" else null,
                                group.repairRequired) { pendingKey = "group:${group.id}" }
                        }
                    }
                    if (ordinaryProfiles.isNotEmpty()) {
                        item(key = "profile-title") { SwitchSectionTitle("Профили") }
                        items(ordinaryProfiles, key = { "profile:${it.id}" }) { profile ->
                            SwitchProfileRow(profile, history, now, currentProfileId, pendingKey) { pendingKey = "profile:${profile.id}" }
                        }
                    }
                    if (balancers.isEmpty() && matchingGroups.isEmpty() && ordinaryProfiles.isEmpty()) {
                        item { Text("Ничего не найдено", Modifier.padding(vertical = 12.dp)) }
                    }
                }
                val selectedName = selectedGroup?.name ?: selectedProfile?.name
                if (selectedName != null) Text("Выбрано: $selectedName", style = MaterialTheme.typography.bodySmall)
                Button(modifier = Modifier.fillMaxWidth(), enabled = selectedProfile != null || (selectedGroup != null && !selectedGroup.repairRequired),
                    onClick = {
                        requestedTarget.value = SwitchTarget(selectedProfile?.id, selectedGroup)
                        connect()
                    }) { Text("Подключиться") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun SwitchSectionTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchProfileRow(profile: Profile, history: List<SavedProbeResult>, now: Long,
    currentProfileId: String, pendingKey: String?, onClick: () -> Unit) {
    val summary = remember(profile, history, now) {
        switchProbeBrief(profile, history.latestProbe(profile, ProbeKind.URL), now)
    }
    SwitchRow(profile.name, profileSummaryParts(profile).primary, summary, pendingKey == "profile:${profile.id}",
        if (profile.id == currentProfileId) "✓ Текущий сервер" else null, false, onClick)
}

@Composable
private fun SwitchRow(name: String, detail: String, summary: String, selected: Boolean,
    currentLabel: String?, needsRepair: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (currentLabel != null) Text(currentLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (needsRepair) Text("Сначала проверьте состав группы в редакторе", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun switchProbeBrief(profile: Profile, result: SavedProbeResult?, now: Long): String {
    val label = "URL-test"
    if (result == null) return "$label: нет результата"
    val value = when {
        result.error != null -> "ошибка"
        else -> result.latencyMs?.takeIf { it >= 0 }?.let { "$it мс" } ?: "нет результата"
    }
    return "$label: $value · ${probeRelativeAge(result.measuredAt, now)}" +
        if (result.isStale(profile)) " · устарел: настройки изменились" else ""
}
