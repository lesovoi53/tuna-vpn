@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.wireturn.app.ui.screens

import android.app.Activity
import kotlinx.coroutines.flow.first
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.VpnEnginePolicy
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import com.wireturn.app.domain.ProfileSections
import com.wireturn.app.domain.ProfileSection
import com.wireturn.app.domain.ProfileListOrder
import com.wireturn.app.domain.ProfileSortOrder
import com.wireturn.app.domain.ProbeKind
import com.wireturn.app.domain.ProbeEligibility
import com.wireturn.app.domain.latestProbe
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.ConnectionCategory
import com.wireturn.app.data.listCategory
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.domain.ImportStatus
import com.wireturn.app.domain.isLocalNetworkHost
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroupDefaults
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.TappableRowIndicator
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.activities.SubscriptionConfigActivity
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.trackGestureStartedAtBoundary
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** How many profiles to keep visible above the selected one when the sheet first opens. */
private const val PROFILES_ABOVE_SELECTED_ON_OPEN = 2

/** Small flat rectangular flag for an ISO-3166 alpha-2 country code, fetched from flagcdn.com. */
@Composable
private fun FlagImage(countryCode: String, modifier: Modifier = Modifier) {
    coil.compose.AsyncImage(
        model = "https://flagcdn.com/w80/${countryCode.lowercase()}.png",
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(2.dp))
    )
}

/**
 * [primary] is the kernel description shown as plain text; [kernelTags] and [xrayTags] are the
 * rest of what [ProfileSummary] used to join with " • ", now shown as separate chip clusters -
 * kernel tags on the profile block, Xray tags on the Xray row (see HomeScreen), both combined in
 * the profiles list where there's no separate Xray row to split them onto.
 */
internal val LocalProfilePrivacy = androidx.compose.runtime.staticCompositionLocalOf { false }

internal class ProfileSummaryParts(
    val primary: String,
    val kernelTags: List<String>,
    val xrayTags: List<String>
) {
    val extraTags: List<String> get() = kernelTags + xrayTags
}

@Composable
internal fun profileSummaryParts(profile: Profile): ProfileSummaryParts {
    val context = LocalContext.current

    val kernel = KernelRegistry.get(profile.kernelVariant)
    val vpn = (profile.kernelConfig as? KernelConfig.Vpn)?.config?.let(VpnEnginePolicy::apply)
    val primary = if (vpn?.isRawXray == true) {
        if (vpn.isXrayBalancer) "Xray · JSON-балансировщик" else "Xray · полная JSON-конфигурация"
    } else if (vpn != null)
        "${vpn.protocol.uppercase()} · ${vpn.engineLabel}" + if (LocalProfilePrivacy.current) "" else " · ${vpn.serverLabel}"
    else kernel.description(context, profile.kernelConfig)
    val kernelTags = kernel.profileSummaryExtra(context, profile.kernelConfig)

    val xrayTags = mutableListOf<String>()
    if (profile.xrayEnabled) {
        val isValid = when (profile.xrayProtocol) {
            XrayConfiguration.VLESS -> profile.vlessConfig.isValid()
            XrayConfiguration.WIREGUARD -> profile.wgConfig.isValid()
        }

        if (isValid) {
            xrayTags.add(
                when (profile.xrayProtocol) {
                    XrayConfiguration.VLESS -> stringResource(ValidatorUtils.uriProtocolStringRes(profile.vlessConfig.vlessLink))
                    XrayConfiguration.WIREGUARD -> stringResource(R.string.wg_short)
                }
            )
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isDualRoute) {
                xrayTags.add(stringResource(R.string.xray_uri_dual_route_short))
            }
            if (profile.xrayProtocol == XrayConfiguration.VLESS && profile.vlessConfig.isSocks5Chain) {
                xrayTags.add(stringResource(R.string.xray_uri_socks5_chain_short))
            }
        }
    }

    return ProfileSummaryParts(primary, kernelTags, xrayTags)
}

@Composable
fun ProfileSummary(
    profile: Profile,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    useAnimation: Boolean = false,
    countryCode: String? = null,
    maxParts: Int? = null
) {
    val summary = profileSummaryParts(profile)
    val allParts = listOf(summary.primary) + summary.extraTags
    val parts = if (maxParts != null) allParts.take(maxParts) else allParts

    if (parts.isNotEmpty()) {
        val text = parts.joinToString(" • ")
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (countryCode != null) {
                val flagWidth = if (useAnimation) 14.dp else 12.dp
                val flagHeight = if (useAnimation) 10.5.dp else 9.dp
                FlagImage(
                    countryCode = countryCode,
                    modifier = Modifier
                        .padding(end = if (useAnimation) 5.dp else 4.dp)
                        .size(width = flagWidth, height = flagHeight)
                )
            }
            if (useAnimation) {
                VerticalAnimatedText(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                    color = color,
                    maxLines = 2,
                    softWrap = true,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 2,
                    softWrap = true,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/**
 * Small tonal pill for a single summary fragment - see [ProfileTagChipRow]. Defaults to
 * secondaryContainer, but callers can override both colors - the profiles list's selected row is
 * already secondaryContainer, so it passes the inverted pair instead of letting chips disappear
 * into it.
 */
@Composable
private fun ProfileTagChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Row of [ProfileTagChip]s for extra summary fragments - used both as a corner cluster
 * overlapping a card's top edge (home screen's profile block and Xray row) and, contained within
 * its own row's bounds, in the profiles list. [modifier] controls the positioning/offset in each
 * case; this composable itself only lays the chips out horizontally.
 */
@Composable
internal fun ProfileTagChipRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { label ->
            ProfileTagChip(text = label, containerColor = containerColor, contentColor = contentColor)
        }
    }
}

@Composable
fun ProfilesBlock(
    viewModel: MainViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val currentProfile = profiles.find { it.id == currentId } ?: profiles.firstOrNull()
    val context = LocalContext.current
    val profileCountries by viewModel.profileCountries.collectAsStateWithLifecycle()
    val groupId by viewModel.activeConnectionGroupId.collectAsStateWithLifecycle()
    val dnsGroupId by viewModel.activeMasterDnsGroupId.collectAsStateWithLifecycle()
    val dnsGroups by viewModel.masterDnsGroups.collectAsStateWithLifecycle()
    val groups by viewModel.connectionGroups.collectAsStateWithLifecycle()
    val groupState by viewModel.groupRunState.collectAsStateWithLifecycle()
    val group = groups.find { it.id == groupId && it.type == com.wireturn.app.data.GroupTestType.URL_TEST && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }
    val coreStatus by com.wireturn.app.CoreServiceState.status.collectAsStateWithLifecycle()
    val runningSession by com.wireturn.app.CoreServiceState.session.collectAsStateWithLifecycle()
    val displayedDnsGroupId = if (coreStatus == com.wireturn.app.CoreStatus.Connected ||
        coreStatus == com.wireturn.app.CoreStatus.Suppressed) runningSession?.masterDnsGroupId else dnsGroupId
    val dnsGroup = dnsGroups.find { it.id == displayedDnsGroupId }
    if (dnsGroup != null) {
        SectionItem(position = ItemPosition.Single, onClick = onClick, modifier = modifier) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dnsGroup.name, style = MaterialTheme.typography.titleMedium)
                Text("DNS-туннель · ${dnsGroup.memberIds.size} серверов")
                Text(if (dnsGroup.strategy == "round_robin") "По кругу" else "Меньше активных соединений",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    if (group != null) {
        val member = com.wireturn.app.domain.HomeSelection.member(group, profiles.find { it.id == runningSession?.profileId },
            coreStatus == com.wireturn.app.CoreStatus.Connected || coreStatus == com.wireturn.app.CoreStatus.Suppressed)
        SectionItem(position = ItemPosition.Single, onClick = onClick, modifier = modifier) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.profile_group_type, "URL-test"))
                if (groupState.groupId == group.id && groupState.busy) {
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(groupState.message)
                } else Text(stringResource(R.string.profile_group_member, member?.name ?: stringResource(R.string.profile_group_pending)))
            }
        }
        return
    }

    SectionItem(
        position = ItemPosition.Single,
        onClick = onClick,
        modifier = modifier
    ) {
        if (currentProfile != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        LargeLeadingIcon {
                            Icon(
                                painter = painterResource(getProfileIcon(currentProfile, outlined = false)),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            VerticalAnimatedText(
                                text = currentProfile.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                softWrap = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    
                            )
                            Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                            // Only the first summary fragment (kernel description) stays here as
                            // text - the rest (protocol, dual-route, chain, ...) show as a chip
                            // row on their own third line below, see kernelTags below.
                            ProfileSummary(
                                profile = currentProfile,
                                modifier = Modifier.fillMaxWidth(),
                                useAnimation = true,
                                countryCode = profileCountries[currentProfile.id],
                                maxParts = 1
                            )
                        }
                        // Same indicator SwitchRow's isSplit uses for "tap this row to open a
                        // list" - hints that the row itself (not just the edit button) opens it.
                        TappableRowIndicator()
                        FilledTonalIconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            val intent = Intent(context, configActivityClassFor(currentProfile.kernelVariant))
                            intent.putExtra("EXTRA_EDIT_MODE", true)
                            intent.putExtra("EXTRA_PROFILE_NAME", currentProfile.name)
                            context.startActivity(intent)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.edit_square_24px),
                                contentDescription = null
                            )
                        }
                    }
                }
                val kernelTags = profileSummaryParts(currentProfile).kernelTags
                if (kernelTags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ProfileTagChipRow(tags = kernelTags, modifier = Modifier.padding(start = 52.dp))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LargeLeadingIcon {
                    Icon(
                        painter = painterResource(R.drawable.mobile_outlined_24px),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_none_selected),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        context.startActivity(
                            Intent(
                                context,
                                com.wireturn.app.ui.activities.AddProfileActivity::class.java
                            )
                        )
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.add_24px),
                            contentDescription = stringResource(R.string.profile_create)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileListItem(
    profile: Profile,
    testResult: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    isDragged: Boolean = false,
    isHighlighted: Boolean = false,
    isFavorite: Boolean = false,
    countryCode: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "profile_item_bg"
    )

    val summary = profileSummaryParts(profile)
    val summaryColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.outline

    Surface(
        onClick = onClick,
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingContent != null) {
                    LargeLeadingIcon(content = leadingContent)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFavorite) Icon(painterResource(R.drawable.profile_star_24px),
                        contentDescription = stringResource(R.string.profile_favorite),
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                    Text(
                        text = profile.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    }
                    Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (countryCode != null) {
                            FlagImage(
                                countryCode = countryCode,
                                modifier = Modifier
                                    .padding(end = 5.dp)
                                    .size(width = 14.dp, height = 10.5.dp)
                            )
                        }
                        Text(
                            text = summary.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                            color = summaryColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (trailingContent != null) {
                    Spacer(Modifier.width(12.dp))
                    trailingContent()
                }
            }
            if (testResult != null) Text(localizedProfileProbeLabel(testResult), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
            if (profile.retainedFromSubscription != null) Text("Сохранён локально · удалён из подписки",
                modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            if (summary.extraTags.isNotEmpty()) {
                Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (leadingContent != null) 52.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    summary.extraTags.forEach { label ->
                        ProfileTagChip(
                            text = label,
                            containerColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfilesDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    visible: Boolean = true,
    embedded: Boolean = false
) {
    val profilesSource by viewModel.profiles.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteProfiles.collectAsStateWithLifecycle()
    val sortOrder by viewModel.profileSort.collectAsStateWithLifecycle()
    var onlyFavorites by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var latencyOrder by rememberSaveable { mutableStateOf(false) }
    var successfulOnly by rememberSaveable { mutableStateOf(false) }
    val probeHistory by viewModel.probeHistory.collectAsStateWithLifecycle()
    val groupTest by viewModel.groupRunState.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val updatingSubIds by viewModel.updatingSubIds.collectAsStateWithLifecycle()
    val urlTests by viewModel.profileUrlTestState.collectAsStateWithLifecycle()
    val realCurrentId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.activeConnectionGroupId.collectAsStateWithLifecycle()
    val selectedDnsGroupId by viewModel.activeMasterDnsGroupId.collectAsStateWithLifecycle()
    val dnsGroups by viewModel.masterDnsGroups.collectAsStateWithLifecycle()
    val storedGroups by viewModel.connectionGroups.collectAsStateWithLifecycle()
    val groups = remember(storedGroups) { storedGroups.filter { it.type == com.wireturn.app.data.GroupTestType.URL_TEST } }
    val currentId = if (selectedGroupId == null && selectedDnsGroupId == null) realCurrentId else ""
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Distance (in px) over which a subscription header morphs into its stuck appearance -
    // scrubbed directly by scroll position rather than snapping once fully pinned. Kept fairly
    // long so the fold reads as gradual rather than a quick snap over a couple of scroll ticks.
    val headerStuckTransitionPx = with(density) { 96.dp.toPx() }

    var draggedItemId by remember { mutableStateOf<String?>(null) }

    // Was the list already at rest at the top when the current touch began? Distinguishes a
    // swipe that merely scrolls up to the top (never dismiss) from a deliberate second swipe
    // starting with nothing left to scroll (dismiss normally).
    var gestureStartedAtTop by remember { mutableStateOf(false) }

    // Passed to the LazyColumn below so noDismissNestedScroll can also manually drive it for
    // top-boundary overscroll (see there) - same native stretch effect as the bottom edge.
    val topOverscroll = rememberOverscrollEffect()

    val scope = rememberCoroutineScope()

    val errorConnection = stringResource(R.string.import_error_connection)
    val errorEmpty = stringResource(R.string.import_error_empty)
    val errorInvalidProfile = stringResource(R.string.import_error_invalid_profile)
    val errorServerFormat = stringResource(R.string.import_error_server)
    var rejectedImport by remember { mutableStateOf<com.wireturn.app.data.SubscriptionImportReport?>(null) }
    rejectedImport?.let { report ->
        SubscriptionImportIssuesDialog(report.rejected) { rejectedImport = null }
    }

    fun showSubUpdateError(status: ImportStatus) {
        val message = when (status) {
            is ImportStatus.NetworkError -> errorConnection
            is ImportStatus.ServerError -> errorServerFormat.format(status.code)
            is ImportStatus.EmptyResponse -> errorEmpty
            is ImportStatus.InvalidFormat -> errorInvalidProfile
            is ImportStatus.Rejected -> {
                rejectedImport = status.report
                return
            }
            is ImportStatus.Success, is ImportStatus.KernelConfigDetected -> return
        }
        HapticUtil.perform(context, HapticUtil.Pattern.ERROR)
        context.showExclusiveToast(message)
    }

    suspend fun doRefreshSubscription(url: String, allowDirectUpdate: Boolean = false) {
        val status = viewModel.refreshSubscription(url, allowDirectUpdate)
        if (status is ImportStatus.Success) {
            status.summary?.let { showUpdateToast(context, it) }
        } else {
            showSubUpdateError(status)
        }
    }

    var pendingSubUpdateUrl by remember { mutableStateOf<String?>(null) }
    val showManualUpdateTunnelWarning = remember { mutableStateOf<Subscription?>(null) }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val url = pendingSubUpdateUrl
        pendingSubUpdateUrl = null
        if (url != null) {
            scope.launch { doRefreshSubscription(url) }
        }
    }

    fun refreshSubscription(sub: Subscription) {
        val isTunnelActive = com.wireturn.app.domain.activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
        if (sub.requireTunnelForUpdate && !isTunnelActive) {
            showManualUpdateTunnelWarning.value = sub
            return
        }

        val url = sub.url
        val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
            isLocalNetworkHost(url) &&
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.ACCESS_LOCAL_NETWORK"
            ) != PackageManager.PERMISSION_GRANTED

        if (needsLocalNetworkPermission) {
            pendingSubUpdateUrl = url
            localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            return
        }

        scope.launch { doRefreshSubscription(url) }
    }

    // Local state for reordering
    val profiles = remember { mutableStateListOf<Profile>() }
    var selectedCategory by rememberSaveable { mutableStateOf(groups.find { it.id == selectedGroupId && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }?.category ?: profilesSource.find { it.id == currentId }?.listCategory ?: ConnectionCategory.BYPASS) }
    var showSubscriptions by rememberSaveable { mutableStateOf(
        dnsGroups.find { it.id == selectedDnsGroupId }?.subscriptionId != null ||
            profilesSource.find { it.id == currentId }?.subscriptionId != null) }
    var expandedSection by remember(visible) { mutableStateOf<ProfileSection?>(if (selectedDnsGroupId != null) ProfileSection.DNS else null) }
    var manageSubscriptions by remember(visible) { mutableStateOf(false) }
    val pinnedBalancers by remember { derivedStateOf { ProfileSections.pinnedBalancers(profiles) } }
    val pinnedItemCount by remember { derivedStateOf { ProfileSections.pinnedBalancerItemCount(profiles) } }
    val sectionGroups by remember { derivedStateOf {
        ProfileSections.groups(profiles, selectedCategory, showSubscriptions, favorites, sortOrder, searchQuery)
            .map { (section, members) -> section to filterAndSortProbedProfiles(members, probeHistory, successfulOnly, latencyOrder) }
            .let { sections ->
                if (selectedCategory == ConnectionCategory.BYPASS && sections.none { it.first == ProfileSection.DNS })
                    (sections + (ProfileSection.DNS to emptyList())).sortedBy { it.first.ordinal }
                else sections
            }
    } }
    val visibleProfiles by remember { derivedStateOf { profiles.filter { ProfileSections.isPinnedBalancer(it) ||
        (it.listCategory == selectedCategory && (it.subscriptionId != null) == showSubscriptions &&
        (embedded || !onlyFavorites || ProfileListOrder.isFavorite(it, favorites))) } } }

    // Grouping derived from `profiles`/`subscriptions` - derivedStateOf so it only recomputes when
    // those actually change, not on every recomposition of this dialog (e.g. drag auto-scroll frames).
    val standaloneProfiles by remember { derivedStateOf { filterAndSortProbedProfiles(ProfileListOrder.sorted(profiles.filter { !ProfileSections.isPinnedBalancer(it) && !showSubscriptions && it.subscriptionId == null && it.listCategory == selectedCategory &&
        (embedded || !onlyFavorites || ProfileListOrder.isFavorite(it, favorites)) }, sortOrder, favorites), probeHistory, successfulOnly, latencyOrder) } }
    val subscriptionGroups by remember {
        derivedStateOf { (if (showSubscriptions) subscriptions else emptyList()).map { sub -> sub to
            filterAndSortProbedProfiles(ProfileListOrder.sorted(profiles.filter { !ProfileSections.isPinnedBalancer(it) && it.subscriptionId == sub.id && it.listCategory == selectedCategory &&
                (embedded || !onlyFavorites || ProfileListOrder.isFavorite(it, favorites)) }, sortOrder, favorites), probeHistory, successfulOnly, latencyOrder)
        }.filter { embedded || !onlyFavorites || it.second.isNotEmpty() } }
    }
    val expandedSubscriptionIds = remember { mutableStateListOf<String>() }
    LaunchedEffect(visible) {
        if (!visible) expandedSubscriptionIds.clear()
    }
    // Single source of truth for the visual order: standalone profiles as one implicit group
    // (null subscription), then each subscription's own group, in list order. findLazyIndex and
    // the open-time scroll anchor (visualOrderIds, below) both walk this same list instead of
    // separately re-deriving the grouping/order from standaloneProfiles/subscriptionGroups by
    // hand, so the two can't silently disagree if the grouping rules change later.
    val visualGroups by remember {
        derivedStateOf { listOf<Pair<Subscription?, List<Profile>>>(null to standaloneProfiles) +
            subscriptionGroups.map { (sub, members) -> sub to if (sub.id in expandedSubscriptionIds) members else emptyList() } }
    }

    fun findLazyIndex(targetId: String): Int {
        ProfileSections.pinnedBalancerLazyIndex(profiles, targetId).takeIf { it >= 0 }?.let { return it }
        if (embedded) {
            return com.wireturn.app.domain.ProfileReveal.embeddedLazyIndex(targetId, sectionGroups,
                expandedSection, searchQuery.isNotBlank(),
                2 + pinnedItemCount + groups.count { it.category == selectedCategory && com.wireturn.app.domain.ManualGroupPolicy.allows(it) },
                showSubscriptions, manageSubscriptions, subscriptionGroups.map { it.first.id })
        }
        var runningIndex = 2 + pinnedItemCount + groups.count { it.category == selectedCategory && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }
        for ((sub, groupProfiles) in visualGroups) {
            if (sub == null) {
                // Standalone "group" only gets its 1-item top guard once it's non-empty.
                val hasGuard = groupProfiles.isNotEmpty()
                val idx = groupProfiles.indexOfFirst { it.id == targetId }
                if (idx != -1) return runningIndex + (if (hasGuard) idx + 1 else idx)
                runningIndex += if (hasGuard) groupProfiles.size + 1 else groupProfiles.size
            } else {
                if (sub.id == targetId) return runningIndex
                if (sub.id !in expandedSubscriptionIds && profiles.any { it.id == targetId && it.subscriptionId == sub.id }) return runningIndex
                val idx = groupProfiles.indexOfFirst { it.id == targetId }
                if (idx != -1) return runningIndex + 1 + idx
                runningIndex += 1 // header
                if (sub.id in expandedSubscriptionIds) runningIndex += if (groupProfiles.isEmpty()) 1 else groupProfiles.size
            }
        }
        return -1
    }

    val lazyListState = rememberLazyListState()
    // Suppresses the drag-reorder swap animation while actively scrolling, so the continuous
    // layout change from the sticky header's stuck transition (see headerStuckTransitionPx)
    // doesn't get misread as "this profile moved in the list" and chased with a lagging spring -
    // only a genuine reorder while the list is at rest (e.g. after a subscription refresh) still
    // animates. Excludes an active drag (draggedItemId != null) because the drag's own auto-scroll
    // loop below also drives lazyListState.scrollBy(...), which would otherwise make
    // isScrollInProgress true and kill the swap animation during the most common way to reorder a
    // profile that isn't already on screen - dragging it to the edge to trigger auto-scroll.
    val profileItemPlacementSpec = if (lazyListState.isScrollInProgress && draggedItemId == null) {
        null
    } else {
        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
    }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var fingerAbsoluteY by remember { mutableFloatStateOf(0f) }
    var dragAnchorOffset by remember { mutableFloatStateOf(0f) }
    var optimisticSelectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentId) {
        optimisticSelectedId = null
    }

    var exportFormatMenuExpanded by remember { mutableStateOf(false) }
    var exportActionMenuExpanded by remember { mutableStateOf(false) }
    var exportTargetIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportAsJson by remember { mutableStateOf(true) }

    val showRenameDialog = remember { mutableStateOf<Profile?>(null) }
    val showDeleteConfirm = remember { mutableStateOf<Profile?>(null) }
    val showBulkDeleteConfirm = remember { mutableStateOf<List<String>?>(null) }
    val showCloneDialog = remember { mutableStateOf<Profile?>(null) }

    val selectedIds = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedIds.isNotEmpty()

    val exportError = stringResource(R.string.profile_export_error)
    val jsonToExport = remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = jsonToExport.value
        jsonToExport.value = null
        if (uri != null && json != null) scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(json.toByteArray()) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { context.showExclusiveToast(exportError) }
        }
    }

    val zipToExport = remember { mutableStateOf<ByteArray?>(null) }
    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val bytes = zipToExport.value
        zipToExport.value = null
        if (uri != null && bytes != null) scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { context.showExclusiveToast(exportError) }
        }
    }

    val highlightedIds = remember { mutableStateListOf<String>() }

    // Clear temporary UI states when returning to foreground to ensure sync with DataStore
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                optimisticSelectedId = null
                draggedItemId = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pendingScrollRequest by com.wireturn.app.ui.UIEventBus.scrollRequest.collectAsStateWithLifecycle()
    var lastRevealSequence by remember { mutableLongStateOf(0L) }
    val revealPlan = pendingScrollRequest?.let { request ->
        com.wireturn.app.domain.ProfileReveal.resolve(request.targetId, profiles.toList(),
            subscriptions.map { it.id }.toSet(), favorites, selectedCategory, embedded)
    }
    // Requests persist across activity navigation and wait for the local list to receive the import.
    LaunchedEffect(pendingScrollRequest, revealPlan, visible) {
        val request = pendingScrollRequest ?: return@LaunchedEffect
        val plan = revealPlan ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        lastRevealSequence = request.sequence
        selectedIds.clear()
        searchQuery = ""
        onlyFavorites = false
        successfulOnly = false
        selectedCategory = plan.category
        showSubscriptions = plan.subscriptions
        expandedSection = plan.section
        manageSubscriptions = plan.manageSubscriptions
        plan.expandSubscriptionId?.let { if (it !in expandedSubscriptionIds) expandedSubscriptionIds.add(it) }
    }
    // Run only after filters and expansion have recomposed; then acknowledge the actual visible key.
    LaunchedEffect(pendingScrollRequest, revealPlan, visible, selectedCategory, showSubscriptions,
        searchQuery, onlyFavorites, successfulOnly, expandedSection, manageSubscriptions,
        expandedSubscriptionIds.toList(), sectionGroups, visualGroups) {
        val request = pendingScrollRequest ?: return@LaunchedEffect
        val plan = revealPlan ?: return@LaunchedEffect
        if (!visible || selectedCategory != plan.category || showSubscriptions != plan.subscriptions ||
            searchQuery.isNotBlank() || onlyFavorites || successfulOnly || expandedSection != plan.section ||
            manageSubscriptions != plan.manageSubscriptions ||
            (!embedded && plan.expandSubscriptionId != null && plan.expandSubscriptionId !in expandedSubscriptionIds)) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val targetIndex = findLazyIndex(plan.targetId)
        if (targetIndex < 0) return@LaunchedEffect
        lazyListState.scrollToItem(targetIndex)
        androidx.compose.runtime.snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo.any { it.key == plan.lazyKey } }
            .first { it }
        highlightedIds.addAll(plan.highlightedIds.filter { it !in highlightedIds })
        com.wireturn.app.ui.UIEventBus.completeScroll(request)
        scope.launch {
            delay(1_500.milliseconds)
            highlightedIds.removeAll(plan.highlightedIds)
        }
    }

    // Sync local list with source of truth only when NOT dragging
    LaunchedEffect(profilesSource) {
        if (optimisticSelectedId != null && profilesSource.none { it.id == optimisticSelectedId }) {
            optimisticSelectedId = null
        }

        val oldSize = profiles.size
        val oldIds = profiles.map { it.id }.toSet()
        val oldSubIds = profiles.mapNotNull { it.subscriptionId }.toSet()
        val isFirstLoad = oldSize == 0 && profilesSource.isNotEmpty()

        if (draggedItemId == null && (profiles.size != profilesSource.size || profiles.toList() != profilesSource)) {
            val updatedOrNewIds = if (oldSize > 0) {
                profilesSource.filter { newP ->
                    val oldP = profiles.find { it.id == newP.id }
                    oldP == null || oldP != newP
                }.map { it.id }
            } else emptyList()

            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                profiles.clear()
                profiles.addAll(profilesSource)
            }

            if (updatedOrNewIds.isNotEmpty()) {
                // Если профили принадлежат подписке, подсвечиваем и её заголовок (даже если подписка не новая).
                val subIdsToHighlight = updatedOrNewIds.mapNotNull { id ->
                    profilesSource.find { it.id == id }?.subscriptionId
                }.distinct()
                
                val allToHighlight = (updatedOrNewIds + subIdsToHighlight).distinct()
                highlightedIds.addAll(allToHighlight)
                scope.launch {
                    delay(1_000.milliseconds)
                    highlightedIds.removeAll(allToHighlight)
                }
            }

            // Scroll logic
            val revealSequenceAtSync = lastRevealSequence
            if (pendingScrollRequest == null) scope.launch {
                delay(150.milliseconds) // Wait for layout update
                if (pendingScrollRequest != null || lastRevealSequence != revealSequenceAtSync) return@launch

                if (isFirstLoad && (showSubscriptions || embedded)) {
                    lazyListState.scrollToItem(0)
                } else if (isFirstLoad) {
                    // Land with a couple of profiles already visible above the selected one
                    // instead of pinning it to the very top edge, so it reads less abruptly.
                    // Subscription headers count as a block here too (findLazyIndex resolves a
                    // subscription id to its header row) - otherwise a profile that's first in
                    // its subscription would land right at the top with its own header scrolled
                    // out of view above it.
                    val visualOrderIds = pinnedBalancers.map { it.id } + visualGroups.flatMap { (sub, groupProfiles) ->
                        (if (sub != null) listOf(sub.id) else emptyList()) + groupProfiles.map { it.id }
                    }
                    val currentVisualIdx = visualOrderIds.indexOf(currentId)
                    val anchorId = if (currentVisualIdx >= 0) {
                        visualOrderIds[(currentVisualIdx - PROFILES_ABOVE_SELECTED_ON_OPEN).coerceAtLeast(0)]
                    } else {
                        currentId
                    }
                    val targetLazyIndex = findLazyIndex(anchorId)
                    if (targetLazyIndex != -1) {
                        lazyListState.scrollToItem(targetLazyIndex)
                    }
                } else if (profilesSource.size > oldSize && oldSize > 0) {
                    val newIds = profilesSource.map { it.id }.toSet() - oldIds
                    val firstNewId = newIds.firstOrNull()
                    
                    if (firstNewId != null) {
                        val newProfile = profilesSource.find { it.id == firstNewId }
                        val subId = newProfile?.subscriptionId
                        
                        // Scroll ONLY if it's a NEW subscription (not previously seen)
                        // or if it's a new standalone profile.
                        val isExistingSub = subId != null && oldSubIds.contains(subId)
                        
                        if (!isExistingSub) {
                            val targetLazyIndex = findLazyIndex(firstNewId)
                            if (targetLazyIndex != -1) {
                                lazyListState.animateScrollToItem(targetLazyIndex)
                            }
                        }
                    }
                }
            }
        }
    }

    fun desiredAbsoluteY(): Float = fingerAbsoluteY + dragAnchorOffset

    fun clampedAbsoluteY(): Float? {
        val currentId = draggedItemId ?: return null
        val layoutInfo = lazyListState.layoutInfo
        val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == currentId } ?: return null
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val slack = with(density) { 32.dp.toPx() }
        val minY = -slack
        val maxY = (viewportHeight - draggedItem.size) + slack
        return if (maxY >= minY) desiredAbsoluteY().coerceIn(minY, maxY) else desiredAbsoluteY()
    }

    fun clampedDragOffset(): Float {
        val currentId = draggedItemId ?: return 0f
        val draggedItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.key == currentId } ?: return 0f
        val absY = clampedAbsoluteY() ?: return 0f
        return absY - draggedItem.offset
    }

    fun updateAutoScrollSpeed() {
        val currentId = draggedItemId ?: return
        val layoutInfo = lazyListState.layoutInfo
        val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == currentId }

        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val threshold = with(density) { 60.dp.toPx() }

        if (draggedItem != null) {
            val slack = with(density) { 32.dp.toPx() }
            val minY = -slack
            val maxY = (viewportHeight - draggedItem.size) + slack
            val visualTop = if (maxY >= minY) desiredAbsoluteY().coerceIn(minY, maxY) else desiredAbsoluteY()
            val visualBottom = visualTop + draggedItem.size

            autoScrollSpeed = when {
                visualTop < threshold && lazyListState.canScrollBackward -> {
                    // Quadratic acceleration for smoother control
                    val dist = (threshold - visualTop).coerceAtLeast(0f)
                    val ratio = dist / threshold
                    -(ratio * ratio * 45f).coerceIn(3f, 45f)
                }

                visualBottom > viewportHeight - threshold && lazyListState.canScrollForward -> {
                    val dist = (visualBottom - (viewportHeight - threshold)).coerceAtLeast(0f)
                    val ratio = dist / threshold
                    (ratio * ratio * 45f).coerceIn(3f, 45f)
                }

                else -> 0f
            }
        } else {
            autoScrollSpeed = 0f
        }
    }

    fun checkAndPerformReorder() {
        if (sortOrder != ProfileSortOrder.ORIGINAL || latencyOrder || successfulOnly) return
        val currentId = draggedItemId ?: return

        val layoutInfo = lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val initialInfo = visibleItems.find { it.key == currentId } ?: return
        val absY = clampedAbsoluteY() ?: return

        val draggedCenter = absY + initialInfo.size / 2
        val originalCenter = initialInfo.offset + initialInfo.size / 2
        val goingDown = draggedCenter > originalCenter

        var virtualIndex = initialInfo.index
        var didSwap = false

        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            while (true) {
                val currentDraggedIdx =
                    profiles.indexOfFirst { it.id == currentId }.takeIf { it != -1 } ?: break

                val targetItem = if (goingDown) {
                    visibleItems.find { item ->
                        item.index > virtualIndex &&
                        (item.offset + item.size / 2) < draggedCenter &&
                        item.key is String && !(item.key as String).startsWith("sub_header_") &&
                        item.key != "standalone_top_guard"
                    }
                } else {
                    visibleItems.findLast { item ->
                        item.index < virtualIndex &&
                        (item.offset + item.size / 2) > draggedCenter &&
                        item.key is String && !(item.key as String).startsWith("sub_header_") &&
                        item.key != "standalone_top_guard"
                    }
                }

                if (targetItem == null) break

                val targetProfileId = targetItem.key as String
                val targetProfileIdx = profiles.indexOfFirst { it.id == targetProfileId }.takeIf { it != -1 } ?: break

                // Allow reorder ONLY within the same group (standalone or specific subscription)
                val draggedProfile = profiles[currentDraggedIdx]
                val targetProfile = profiles[targetProfileIdx]
                if (draggedProfile.subscriptionId != targetProfile.subscriptionId) break
                if (ProfileListOrder.isFavorite(draggedProfile, favorites) != ProfileListOrder.isFavorite(targetProfile, favorites)) break

                val item = profiles.removeAt(currentDraggedIdx)
                profiles.add(targetProfileIdx, item)

                virtualIndex = targetItem.index
                didSwap = true
            }
        }

        if (didSwap) {
            updateAutoScrollSpeed()
            scope.launch { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) }
        }
    }

    fun onProfileDrag(delta: Float) {
        fingerAbsoluteY += delta
    }

    LaunchedEffect(draggedItemId) {
        if (draggedItemId != null) {
            while (true) {
                withFrameNanos { }
                try {
                    if (autoScrollSpeed != 0f) {
                        lazyListState.scrollBy(autoScrollSpeed)
                    }
                    checkAndPerformReorder()
                    updateAutoScrollSpeed()

                    val currentId = draggedItemId
                    if (currentId != null &&
                        lazyListState.layoutInfo.visibleItemsInfo.none { it.key == currentId }
                    ) {
                        val trueIdx = profiles.indexOfFirst { it.id == currentId }
                        if (trueIdx != -1) {
                            val firstVisible = lazyListState.firstVisibleItemIndex
                            if (desiredAbsoluteY() < 0f) {
                                lazyListState.scrollToItem((firstVisible - 1).coerceAtLeast(0))
                            } else {
                                lazyListState.scrollToItem(firstVisible + 1)
                            }
                            checkAndPerformReorder()
                            updateAutoScrollSpeed()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProfileDrag", "autoscroll tick failed, draggedItemId=$draggedItemId", e)
                }
            }
        }
    }

    val noDismissNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Если мы перетаскиваем профиль, ПОЛНОСТЬЮ блокируем скролл шторки (родителя)
                if (draggedItemId != null && source == NestedScrollSource.UserInput) {
                    return available
                }
                
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Если мы перетаскиваем профиль (reorder), полностью блокируем шторку
                if (draggedItemId != null && source == NestedScrollSource.UserInput) {
                    return available
                }

                // Второй жест, начатый уже с упора - однозначный драг шторки, не мешаем.
                if (gestureStartedAtTop) {
                    return Offset.Zero
                }

                // Упёрлись в верх ВНУТРИ жеста - в шторку не пускаем (иначе она поедет),
                // рисуем вместо этого нативный stretch через topOverscroll (performScroll
                // ничего не отдаёт дальше, поэтому весь остаток уходит в растяжку).
                if (available.y > 0) {
                    return topOverscroll?.applyToScroll(available, source) { Offset.Zero } ?: available
                }

                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (gestureStartedAtTop) {
                    return Velocity.Zero
                }
                if (available.y > 0 && !lazyListState.canScrollBackward) {
                    topOverscroll?.applyToFling(available) { Velocity.Zero }
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (gestureStartedAtTop) {
                    return Velocity.Zero
                }
                if (available.y > 0) {
                    topOverscroll?.applyToFling(available) { Velocity.Zero }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val exportTitle = stringResource(R.string.profile_export)
    var exportPreparing by remember { mutableStateOf(false) }
    val onExportActionSelected: (Boolean) -> Unit = { save ->
        exportActionMenuExpanded = false
        if (!exportPreparing) {
            // Capture the selection before yielding; a later selection must not change this export.
            val targetIds = exportTargetIds.toList()
            val asJson = exportAsJson
            val title = exportTitle
            val safeName = profiles.find { it.id == targetIds.singleOrNull() }?.name
                ?.replace(Regex("[\\\\/:*?\"<>| ]"), "_") ?: "profile"
            val baseName = if (targetIds.size == 1) "tuna_$safeName" else "tuna_profiles_${targetIds.size}"
            exportPreparing = true
            scope.launch {
                try {
                    if (save && asJson) {
                        jsonToExport.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            viewModel.getProfilesJson(targetIds)
                        }
                        exportLauncher.launch("$baseName.json")
                    } else if (save) {
                        zipToExport.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            viewModel.exportProfilesToZip(targetIds)
                        }
                        zipExportLauncher.launch("$baseName.zip")
                    } else if (asJson) {
                        val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            com.wireturn.app.domain.ProfileEncoder.encodeForSharing(viewModel.getProfilesJson(targetIds))
                        }
                        if (encoded != null) shareText(context, "tuna://$encoded", title)
                        else {
                            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                viewModel.exportProfilesToZip(targetIds)
                            }
                            android.widget.Toast.makeText(context, R.string.profile_share_large_archive, android.widget.Toast.LENGTH_LONG).show()
                            shareFile(context, bytes, "$baseName.zip")
                        }
                    } else {
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            viewModel.exportProfilesToZip(targetIds)
                        }
                        shareFile(context, bytes, "$baseName.zip")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { context.showExclusiveToast(exportError) }
                finally { exportPreparing = false }
            }
        }
    }

    val onExportClick = { targets: List<String>, showMenu: Boolean ->
        if (showMenu) HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        exportTargetIds = targets
        if (targets.size > 1) {
            if (showMenu) exportFormatMenuExpanded = true
        } else {
            exportAsJson = true
            if (showMenu) exportActionMenuExpanded = true
        }
    }

    if (sortMenu) AlertDialog(onDismissRequest = { sortMenu = false },
        title = { Text(stringResource(R.string.profile_sort_title)) },
        text = { Column {
            ProfileSortOrder.entries.forEach { order ->
                Row(Modifier.fillMaxWidth().clickable { latencyOrder = false; viewModel.setProfileSort(order); sortMenu = false },
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = !latencyOrder && sortOrder == order, onClick = { latencyOrder = false; viewModel.setProfileSort(order); sortMenu = false })
                    Text(stringResource(when (order) {
                        ProfileSortOrder.ORIGINAL -> R.string.profile_sort_original
                        ProfileSortOrder.NAME_ASC -> R.string.profile_sort_name_asc
                        ProfileSortOrder.NAME_DESC -> R.string.profile_sort_name_desc
                        ProfileSortOrder.PROTOCOL -> R.string.profile_sort_protocol
                    }))
                }
            }
            Row(Modifier.fillMaxWidth().clickable { latencyOrder = true; sortMenu = false }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = latencyOrder, onClick = { latencyOrder = true; sortMenu = false })
                Text("По задержке URL-test")
            }
            Text(stringResource(R.string.profile_favorites_note), style = MaterialTheme.typography.bodySmall)
            if (sortOrder != ProfileSortOrder.ORIGINAL) Text(stringResource(R.string.profile_sorted_note), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = {})

    RetainedProfilesPanel(
        visible = visible,
        embedded = embedded,
        onDismissRequest = { if (draggedItemId == null) onDismiss() },
        containerColor = if (embedded) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            ) {
                if (!embedded) androidx.compose.material3.BottomSheetDefaults.DragHandle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSelectionMode) stringResource(
                            R.string.profile_selected_count,
                            selectedIds.size
                        )
                        else stringResource(if (embedded) R.string.orbit_servers else R.string.profiles_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSelectionMode) {
                            Box {
                                FilledTonalIconButton(onClick = { onExportClick(selectedIds.toList(), true) }) {
                                    Icon(
                                        painterResource(R.drawable.ios_share_24px),
                                        contentDescription = stringResource(R.string.profile_export)
                                    )
                                }
                                ExportDropdownMenus(
                                    expandedFormat = exportFormatMenuExpanded,
                                    onDismissFormat = { exportFormatMenuExpanded = false },
                                    expandedAction = exportActionMenuExpanded,
                                    onDismissAction = { exportActionMenuExpanded = false },
                                    onFormatSelected = { asJson ->
                                        exportAsJson = asJson
                                        exportFormatMenuExpanded = false
                                        exportActionMenuExpanded = true
                                    },
                                    onActionSelected = onExportActionSelected
                                )
                            }
                            FilledTonalIconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                showBulkDeleteConfirm.value = selectedIds.toList()
                            }) {
                                Icon(
                                    painterResource(R.drawable.delete_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            FilledTonalIconButton(onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val isAllSelected = visibleProfiles.all { it.id in selectedIds }
                                if (isAllSelected) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(visibleProfiles.map { it.id })
                                }
                            }) {
                                Icon(
                                    painterResource(
                                        if (visibleProfiles.all { it.id in selectedIds }) R.drawable.indeterminate_check_box_24px
                                        else R.drawable.check_box_24px
                                    ),
                                    contentDescription = null
                                )
                            }
                        } else {
                            if (profiles.isNotEmpty()) {
                                Box {
                                    FilledTonalIconButton(onClick = { onExportClick(profiles.map { it.id }, true) }) {
                                        Icon(
                                            painterResource(R.drawable.ios_share_24px),
                                            contentDescription = stringResource(R.string.profile_export_all)
                                        )
                                    }
                                    ExportDropdownMenus(
                                        expandedFormat = exportFormatMenuExpanded,
                                        onDismissFormat = { exportFormatMenuExpanded = false },
                                        expandedAction = exportActionMenuExpanded,
                                        onDismissAction = { exportActionMenuExpanded = false },
                                        onFormatSelected = { asJson ->
                                            exportAsJson = asJson
                                            exportFormatMenuExpanded = false
                                            exportActionMenuExpanded = true
                                        },
                                        onActionSelected = onExportActionSelected
                                    )
                                }
                            }
                            Box {
                                FilledTonalIconButton(onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    context.startActivity(
                                        Intent(
                                            context,
                                            com.wireturn.app.ui.activities.AddProfileActivity::class.java
                                        )
                                    )
                                }) {
                                    Icon(
                                        painterResource(R.drawable.add_24px),
                                        contentDescription = stringResource(R.string.profile_create)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(if (embedded) Modifier.fillMaxHeight() else Modifier)
        ) {
            val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                state = lazyListState,
                userScrollEnabled = draggedItemId == null,
                overscrollEffect = topOverscroll,
                modifier = Modifier
                    .then(if (embedded) Modifier.weight(1f) else Modifier.heightIn(max = 640.dp))
                    .fillMaxWidth()
                    .then(if (embedded) Modifier else Modifier.nestedScroll(noDismissNestedScroll))
                    .trackGestureStartedAtBoundary(
                        atBoundary = { !lazyListState.canScrollBackward },
                        onGestureStart = { gestureStartedAtTop = it }
                    )
                    .pointerInput(isSelectionMode, sortOrder, favorites, onlyFavorites, searchQuery, latencyOrder, successfulOnly) {
                        if (embedded || isSelectionMode || searchQuery.isNotBlank() || sortOrder != ProfileSortOrder.ORIGINAL || onlyFavorites || latencyOrder || successfulOnly) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                            // A stuck header is rendered pinned at the top, covering whatever
                            // item is really scrolled underneath it there - that item's true
                            // (unclamped) offset can still be within touch range, so without this
                            // guard a long press on the visible header could hit-test straight
                            // through to the hidden row and start dragging it.
                            val pinnedHeaderBottom = lazyListState.layoutInfo.visibleItemsInfo
                                .filter { (it.key as? String)?.startsWith("sub_header_") == true && it.offset <= 0 }
                                .maxOfOrNull { it.offset + it.size } ?: 0
                            if (longPress.position.y < pinnedHeaderBottom) return@awaitEachGesture

                            val hitItem = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                val key = item.key
                                key is String &&
                                    !key.startsWith("sub_header_") &&
                                    key != "standalone_top_guard" && key != "profile_controls" && !key.startsWith("search:") && !key.startsWith("group:") &&
                                    longPress.position.y >= item.offset &&
                                    longPress.position.y < item.offset + item.size
                            } ?: return@awaitEachGesture
                            val targetId = hitItem.key as String

                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            draggedItemId = targetId
                            fingerAbsoluteY = longPress.position.y
                            dragAnchorOffset = hitItem.offset - longPress.position.y

                            drag(longPress.id) { change ->
                                val delta = change.positionChange().y
                                change.consume()
                                onProfileDrag(delta)
                            }
                            if (draggedItemId == targetId) {
                                draggedItemId = null
                                autoScrollSpeed = 0f
                                viewModel.reorderProfiles(profiles.toList())
                            }
                        }
                    }
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + bottomPadding + 24.dp
                )
            ) {
                item(key = "profile_controls") { Column {
            if (embedded) androidx.compose.material3.OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(stringResource(R.string.orbit_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.search_24px), null) },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (embedded) listOf(ConnectionCategory.VPN, ConnectionCategory.BYPASS) else ConnectionCategory.entries).forEach { category ->
                    androidx.compose.material3.FilterChip(selected = selectedCategory == category,
                        onClick = { selectedIds.clear(); expandedSection = null; manageSubscriptions = false; selectedCategory = category },
                        label = { Text(if (category == ConnectionCategory.BYPASS) stringResource(R.string.profiles_bypass_tab) else "VPN") })
                }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (if (embedded) listOf(true, false) else listOf(false, true)).forEach { subscriptionTab ->
                    val title = stringResource(if (subscriptionTab) R.string.profiles_subscriptions_tab else R.string.profiles_local_tab)
                    androidx.compose.material3.FilterChip(selected = showSubscriptions == subscriptionTab,
                        onClick = { selectedIds.clear(); expandedSection = null; manageSubscriptions = false; showSubscriptions = subscriptionTab }, label = { Text(title) })
                }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = draggedItemId == null, onClick = { sortMenu = true }) { Text(stringResource(R.string.profile_sort_title)) }
                TextButton(enabled = urlTests.busy || (!groupTest.busy && !groupTest.automatic && profilesSource.any(ProbeEligibility::allows)),
                    onClick = {
                        if (urlTests.busy) viewModel.cancelProfileUrlTests()
                        else viewModel.testProfileUrls("all", profilesSource)
                    }) { Text(if (urlTests.busy)
                        stringResource(R.string.profile_probe_stop, urlTests.completed, urlTests.total) else stringResource(R.string.profile_probe_all)) }
                if (!embedded) androidx.compose.material3.FilterChip(selected = onlyFavorites, enabled = draggedItemId == null,
                    onClick = { selectedIds.clear(); onlyFavorites = !onlyFavorites }, label = { Text(stringResource(R.string.profile_favorites_only)) })
                androidx.compose.material3.FilterChip(selected = successfulOnly, enabled = draggedItemId == null,
                    onClick = { selectedIds.clear(); successfulOnly = !successfulOnly }, label = { Text("Успешно проверенные") })
            }
            AutomaticProbeSelectionNotice(groupTest.automatic) { viewModel.cancelGroupTest() }

                } }
                if (pinnedBalancers.isNotEmpty()) {
                    item(key = "pinned-xray-balancers-title") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Балансировщики Xray", style = MaterialTheme.typography.titleMedium)
                            Text("Всегда сверху · все источники · фильтры списка не скрывают этот блок",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(pinnedBalancers, key = { _, profile -> "pinned-balancer:${profile.id}" }) { index, profile ->
                        ProfileItemRow(profile = profile, index = index, totalInGroup = pinnedBalancers.size,
                            viewModel = viewModel, currentId = currentId, optimisticSelectedId = optimisticSelectedId,
                            selectedIds = selectedIds, highlightedIds = highlightedIds, draggedItemId = null, dragOffset = 0f,
                            isSelectionMode = isSelectionMode, onDismiss = onDismiss, scope = scope, context = context,
                            onDragEnd = {}, onExportClick = onExportClick, onExportActionSelected = onExportActionSelected,
                            onClone = { showCloneDialog.value = it }, onRename = { showRenameDialog.value = it },
                            onDelete = { showDeleteConfirm.value = it }, onOptimisticSelect = { optimisticSelectedId = it },
                            isFavorite = ProfileListOrder.isFavorite(profile, favorites), showFavoriteButton = true,
                            modifier = Modifier.animateItem(placementSpec = profileItemPlacementSpec))
                    }
                }
                item(key = "section-group-controls") { ConnectionGroupsButton(viewModel, selectedCategory) }
                if (!embedded) itemsIndexed(groups.filter { it.category == selectedCategory && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }, key = { _, g -> "group:${g.id}" }) { _, group ->
                    ConnectionGroupRow(viewModel, group, onDismiss)
                }
                if (embedded) {
                    itemsIndexed(groups.filter { it.category == selectedCategory && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }, key = { _, g -> "group:${g.id}" }) { _, group ->
                        ConnectionGroupRow(viewModel, group, onDismiss)
                    }
                    sectionGroups.forEach { (section, members) ->
                        val expanded = expandedSection == section || searchQuery.isNotBlank()
                        item(key = "section:${section.name}") {
                            Column {
                            ProfileSectionHeader(profileSectionTitle(section), members.size, expanded,
                                selected = members.any { it.id == (optimisticSelectedId ?: currentId) } ||
                                    (section == ProfileSection.DNS && selectedDnsGroupId != null),
                                favorite = section == ProfileSection.FAVORITES,
                                onClick = { expandedSection = if (expandedSection == section) null else section })
                                if (expanded && section == ProfileSection.DNS) {
                                    MasterDnsGroupsBlock(viewModel, showSubscriptions, onDismiss)
                                }
                            }
                        }
                        if (expanded && members.isEmpty()) item(key = "section-empty:${section.name}") {
                            Text(stringResource(if (searchQuery.isNotBlank()) R.string.orbit_search_empty else R.string.server_sections_empty_favorites),
                                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (expanded) itemsIndexed(members, key = { _, p -> "section-profile:${p.id}" }) { index, profile ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (index == 0 || members[index - 1].subscriptionId != profile.subscriptionId) {
                                    Text(subscriptions.find { it.id == profile.subscriptionId }?.name
                                        ?: stringResource(R.string.profiles_local_tab), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                        ProfileItemRow(
                            profile = profile,
                            isFavorite = ProfileListOrder.isFavorite(profile, favorites),
                            showFavoriteButton = true,
                            index = index,
                            totalInGroup = members.size,
                            viewModel = viewModel,
                            currentId = currentId,
                            optimisticSelectedId = optimisticSelectedId,
                            selectedIds = selectedIds,
                            highlightedIds = highlightedIds,
                            draggedItemId = null,
                            dragOffset = 0f,
                            isSelectionMode = isSelectionMode,
                            onDismiss = onDismiss,
                            scope = scope,
                            context = context,
                            onDragEnd = { endedId ->
                                if (draggedItemId == endedId) {
                                    draggedItemId = null; autoScrollSpeed = 0f
                                    viewModel.reorderProfiles(profiles.toList())
                                }
                            },
                            onExportClick = onExportClick,
                            onExportActionSelected = onExportActionSelected,
                            onClone = { showCloneDialog.value = it },
                            onRename = { showRenameDialog.value = it },
                            onDelete = { showDeleteConfirm.value = it },
                            onOptimisticSelect = { optimisticSelectedId = it },
                            modifier = Modifier.animateItem(placementSpec = profileItemPlacementSpec)
                        )
                            }
                        }
                    }
                    if (showSubscriptions) {
                        item(key = "section-subscriptions") {
                            TextButton(onClick = { manageSubscriptions = !manageSubscriptions }) {
                                Text(stringResource(R.string.server_sections_manage) + if (manageSubscriptions) " −" else " +")
                            }
                        }
                        if (manageSubscriptions) subscriptionGroups.forEach { (sub, subProfiles) ->
                            val isAnySelected = subProfiles.any { it.id == (optimisticSelectedId ?: currentId) }
                            val isUpdating = updatingSubIds.contains(sub.id)
                            item(key = "section-sub:${sub.id}") {
                            SubscriptionHeaderRow(
                                sub = sub,
                                isExpanded = false,
                                profileCount = subProfiles.size,
                                isAnyChildSelected = isAnySelected,
                                isSelectionMode = isSelectionMode,
                                isAllSubSelected = subProfiles.isNotEmpty() && subProfiles.all { selectedIds.contains(it.id) },
                                isUpdating = isUpdating,
                                stuckProgress = 0f,
                                onUpdate = { refreshSubscription(sub) },
                                showUrlTest = (urlTests.busy && urlTests.owner == sub.id) || subProfiles.any(ProbeEligibility::allows),
                                testLabel = if (urlTests.busy && urlTests.owner == sub.id)
                                    stringResource(R.string.profile_probe_stop, urlTests.completed, urlTests.total) else stringResource(R.string.profile_probe_label),
                                testEnabled = (urlTests.busy && urlTests.owner == sub.id) || (!urlTests.busy && !groupTest.busy && !groupTest.automatic && subProfiles.any(ProbeEligibility::allows)),
                                onTest = {
                                    if (urlTests.busy) viewModel.cancelProfileUrlTests()
                                    else viewModel.testProfileUrls(sub.id, subProfiles)
                                },
                                onSettings = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    val intent = Intent(context, SubscriptionConfigActivity::class.java).apply {
                                        putExtra("EXTRA_SUB_ID", sub.id)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                onSelect = {
                                    context.startActivity(Intent(context, SubscriptionConfigActivity::class.java)
                                        .putExtra("EXTRA_SUB_ID", sub.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                                onToggleSelection = {
                                    val allInSub = subProfiles.map { it.id }
                                    val isAllInSubSelected = allInSub.all { selectedIds.contains(it) }
                                    if (isAllInSubSelected) {
                                        selectedIds.removeAll(allInSub)
                                    } else {
                                        selectedIds.addAll(allInSub.filter { it !in selectedIds })
                                    }
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                },
                                isHighlighted = highlightedIds.contains(sub.id)
                            )
                            }
                        }
                    }
                } else if (standaloneProfiles.isEmpty() && subscriptionGroups.isEmpty() && pinnedBalancers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.mobile_outlined_24px),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.profiles_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    if (standaloneProfiles.isNotEmpty()) {
                        item(key = "standalone_top_guard") {
                            Spacer(Modifier.height(1.dp))
                        }
                    }

                    // Standalone Profiles
                    itemsIndexed(standaloneProfiles, key = { _, it -> it.id }) { index, profile ->
                        ProfileItemRow(
                            profile = profile,
                            isFavorite = ProfileListOrder.isFavorite(profile, favorites),
                            index = index,
                            totalInGroup = standaloneProfiles.size,
                            viewModel = viewModel,
                            currentId = currentId,
                            optimisticSelectedId = optimisticSelectedId,
                            selectedIds = selectedIds,
                            highlightedIds = highlightedIds,
                            draggedItemId = draggedItemId,
                            dragOffset = clampedDragOffset(),
                            isSelectionMode = isSelectionMode,
                            onDismiss = onDismiss,
                            scope = scope,
                            context = context,
                            onDragEnd = { endedId ->
                                if (draggedItemId == endedId) {
                                    draggedItemId = null; autoScrollSpeed = 0f
                                    viewModel.reorderProfiles(profiles.toList())
                                }
                            },
                            onExportClick = onExportClick,
                            onExportActionSelected = onExportActionSelected,
                            onClone = { showCloneDialog.value = it },
                            onRename = { showRenameDialog.value = it },
                            onDelete = { showDeleteConfirm.value = it },
                            onOptimisticSelect = { optimisticSelectedId = it },
                            modifier = Modifier.animateItem(placementSpec = profileItemPlacementSpec)
                        )
                    }

                    // Subscription Groups
                    subscriptionGroups.forEach { (sub, subProfiles) ->
                        val isAnySelected = subProfiles.any { it.id == (optimisticSelectedId ?: currentId) }
                        val isUpdating = updatingSubIds.contains(sub.id)
                        val isExpanded = sub.id in expandedSubscriptionIds

                        item(key = "sub_header_${sub.id}") {
                            SubscriptionHeaderRow(
                                sub = sub,
                                isExpanded = isExpanded,
                                profileCount = subProfiles.size,
                                isAnyChildSelected = isAnySelected,
                                isSelectionMode = isSelectionMode,
                                isAllSubSelected = subProfiles.isNotEmpty() && subProfiles.all { selectedIds.contains(it.id) },
                                isUpdating = isUpdating,
                                stuckProgress = 0f,
                                onUpdate = { refreshSubscription(sub) },
                                showUrlTest = (urlTests.busy && urlTests.owner == sub.id) || subProfiles.any(ProbeEligibility::allows),
                                testLabel = if (urlTests.busy && urlTests.owner == sub.id)
                                    stringResource(R.string.profile_probe_stop, urlTests.completed, urlTests.total) else stringResource(R.string.profile_probe_label),
                                testEnabled = (urlTests.busy && urlTests.owner == sub.id) || (!urlTests.busy && !groupTest.busy && !groupTest.automatic && subProfiles.any(ProbeEligibility::allows)),
                                onTest = {
                                    if (urlTests.busy) viewModel.cancelProfileUrlTests()
                                    else viewModel.testProfileUrls(sub.id, subProfiles)
                                },
                                onSettings = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    val intent = Intent(context, SubscriptionConfigActivity::class.java).apply {
                                        putExtra("EXTRA_SUB_ID", sub.id)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                onSelect = {
                                    if (isExpanded) expandedSubscriptionIds.remove(sub.id)
                                    else expandedSubscriptionIds.add(sub.id)
                                },
                                onToggleSelection = {
                                    val allInSub = subProfiles.map { it.id }
                                    val isAllInSubSelected = allInSub.all { selectedIds.contains(it) }
                                    if (isAllInSubSelected) {
                                        selectedIds.removeAll(allInSub)
                                    } else {
                                        selectedIds.addAll(allInSub.filter { it !in selectedIds })
                                    }
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                },
                                isHighlighted = highlightedIds.contains(sub.id)
                            )
                        }

                        if (isExpanded) itemsIndexed(subProfiles, key = { _, it -> it.id }) { index, profile ->
                            ProfileItemRow(
                                profile = profile,
                                isFavorite = ProfileListOrder.isFavorite(profile, favorites),
                                index = index,
                                totalInGroup = subProfiles.size,
                                isInsideSubscription = true,
                                viewModel = viewModel,
                                currentId = currentId,
                                optimisticSelectedId = optimisticSelectedId,
                                selectedIds = selectedIds,
                                highlightedIds = highlightedIds,
                                draggedItemId = draggedItemId,
                                dragOffset = clampedDragOffset(),
                                isSelectionMode = isSelectionMode,
                                onDismiss = onDismiss,
                                    scope = scope,
                                context = context,
                                onDragEnd = { endedId ->
                                    if (draggedItemId == endedId) {
                                        draggedItemId = null; autoScrollSpeed = 0f
                                        viewModel.reorderProfiles(profiles.toList())
                                    }
                                },
                                onExportClick = onExportClick,
                                onExportActionSelected = onExportActionSelected,
                                onClone = { showCloneDialog.value = it },
                                onRename = { showRenameDialog.value = it },
                                onDelete = { showDeleteConfirm.value = it },
                                onOptimisticSelect = { optimisticSelectedId = it },
                                modifier = Modifier.animateItem(placementSpec = profileItemPlacementSpec)
                            )
                        }

                        if (isExpanded && subProfiles.isEmpty()) {
                            item(key = "sub_empty_${sub.id}") {
                                SubscriptionEmptyRow()
                            }
                        }
                    }
                }
            }
        }
    }

    showRenameDialog.value?.let { profile ->
        ProfileNameDialog(
            title = stringResource(R.string.profile_rename),
            initialName = profile.name,
            onDismiss = { showRenameDialog.value = null },
            onConfirm = { name ->
                viewModel.renameProfile(profile.id, name)
                showRenameDialog.value = null
            }
        )
    }

    showCloneDialog.value?.let { profile ->
        ProfileNameDialog(
            title = stringResource(R.string.profile_clone),
            initialName = profile.name + " (Copy)",
            onDismiss = { showCloneDialog.value = null },
            onConfirm = { name ->
                viewModel.cloneProfile(profile.id, name)
                showCloneDialog.value = null
            }
        )
    }

    showDeleteConfirm.value?.let { profile ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = null },
            title = { Text(stringResource(R.string.profile_delete_confirm, profile.name)) },
            text = { Text(stringResource(R.string.profile_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        showDeleteConfirm.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    showBulkDeleteConfirm.value?.let { ids ->
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm.value = null },
            title = { Text(stringResource(R.string.profile_delete_selected_confirm, ids.size)) },
            text = { Text(stringResource(R.string.profile_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfiles(ids)
                        selectedIds.clear()
                        showBulkDeleteConfirm.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBulkDeleteConfirm.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    showManualUpdateTunnelWarning.value?.let { sub ->
        AlertDialog(
            onDismissRequest = { showManualUpdateTunnelWarning.value = null },
            title = { Text(stringResource(R.string.update_manual_no_tunnel_title)) },
            text = { Text(stringResource(R.string.update_manual_no_tunnel_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = sub.url
                        showManualUpdateTunnelWarning.value = null
                        // This one request was explicitly approved; keep the saved preference.
                        scope.launch { doRefreshSubscription(url, allowDirectUpdate = true) }
                    }
                ) { Text(stringResource(R.string.btn_proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManualUpdateTunnelWarning.value = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun ProfileItemRow(
    profile: Profile,
    index: Int,
    totalInGroup: Int,
    viewModel: MainViewModel,
    currentId: String,
    optimisticSelectedId: String?,
    selectedIds: SnapshotStateList<String>,
    highlightedIds: List<String>,
    draggedItemId: String?,
    dragOffset: Float,
    isSelectionMode: Boolean,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    onDragEnd: (String) -> Unit,
    onExportClick: (List<String>, Boolean) -> Unit,
    onExportActionSelected: (Boolean) -> Unit,
    onClone: (Profile) -> Unit,
    onRename: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onOptimisticSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInsideSubscription: Boolean = false,
    isFavorite: Boolean = false,
    showFavoriteButton: Boolean = false
) {
    val isDragged = draggedItemId == profile.id
    val profileCountries by viewModel.profileCountries.collectAsStateWithLifecycle()
    val urlTests by viewModel.profileUrlTestState.collectAsStateWithLifecycle()
    val history by viewModel.probeHistory.collectAsStateWithLifecycle()
    var details by remember(profile.id) { mutableStateOf(false) }

    val isDraggedState = rememberUpdatedState(isDragged)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    DisposableEffect(profile.id) {
        onDispose {
            if (isDraggedState.value) {
                Log.e("ProfileDrag", "composable disposed mid-drag for profile=${profile.id}, forcing reset")
                onDragEndState.value(profile.id)
            }
        }
    }

    val isSelected = profile.id == (optimisticSelectedId ?: currentId)
    val isSelectedInMode = selectedIds.contains(profile.id)
    var menuExpanded by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    var itemExportActionMenuExpanded by remember { mutableStateOf(false) }
    val connect = rememberVpnConnectAction(viewModel) {
        viewModel.connectProfile(profile.id) { onOptimisticSelect(profile.id); onDismiss() }
    }

    val itemShape = RoundedCornerShape(16.dp)

    val offsetAnim by animateFloatAsState(
        targetValue = if (isDragged) dragOffset else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = if (isDragged) Spring.StiffnessHigh else Spring.StiffnessMedium),
        label = "drag_offset"
    )

    ProfileListItem(
        profile = profile,
        isSelected = isSelected,
        isHighlighted = highlightedIds.contains(profile.id),
        isFavorite = isFavorite,
        countryCode = profileCountries[profile.id],
        testResult = urlTests.labelFor(profile) ?: history.latestProbe(profile, ProbeKind.URL)?.let { result ->
            (result.error ?: result.latencyMs?.let { "$it мс" } ?: "Нет результата") +
                if (result.isStale(profile)) " · устарел" else ""
        },
        shape = itemShape,
        isDragged = isDragged,
        onClick = {
            if (isSelectionMode) {
                if (isSelectedInMode) selectedIds.remove(profile.id)
                else selectedIds.add(profile.id)
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
            } else if (draggedItemId == null) {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                details = true
            }
        },
        leadingContent = {
            if (showFavoriteButton && !isSelectionMode) IconButton(onClick = { viewModel.toggleFavoriteProfile(profile) }) {
                Icon(painterResource(R.drawable.profile_star_24px),
                    stringResource(if (isFavorite) R.string.profile_favorite_remove else R.string.profile_favorite_add),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            } else Icon(
                painter = painterResource(getProfileIcon(profile, outlined = !isSelected)),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 10f else 0f)
            .graphicsLayer {
                translationY = if (isDragged) dragOffset else offsetAnim
                scaleX = if (isDragged) 1.02f else 1f
                scaleY = if (isDragged) 1.02f else 1f
                shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                shape = itemShape
                clip = isDragged
            },
        trailingContent = {
            if (isSelectionMode) {
                Checkbox(checked = isSelectedInMode, onCheckedChange = null)
            } else {
                IconButton(onClick = connect) { Icon(painterResource(R.drawable.power_24px), "Подключить") }
                Box {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        menuExpanded = true
                    }) {
                        Icon(painterResource(R.drawable.more_vert_24px), contentDescription = stringResource(R.string.profile_actions))
                    }
                    AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, title = stringResource(R.string.profile_actions)) {
                        DropdownMenuItem(text = { Text(stringResource(if (isFavorite) R.string.profile_favorite_remove else R.string.profile_favorite_add)) },
                            leadingIcon = { Icon(painterResource(R.drawable.profile_star_24px), null, Modifier.size(20.dp)) },
                            onClick = { menuExpanded = false; viewModel.toggleFavoriteProfile(profile) })
                        if (profile.kernelConfig is com.wireturn.app.data.KernelConfig.MasterDns) {
                            DropdownMenuItem(text = { Text("DNS-резолверы") }, onClick = {
                                menuExpanded = false
                                context.startActivity(Intent(context, com.wireturn.app.ui.activities.MasterDnsResolversActivity::class.java)
                                    .putExtra("EXTRA_PROFILE_ID", profile.id))
                            })
                        }
                        if (com.wireturn.app.domain.CloudflareProfile.tagged(profile.name)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.cf_find)) }, onClick = {
                                menuExpanded = false
                                context.startActivity(Intent(context, com.wireturn.app.ui.activities.CloudflareIpActivity::class.java)
                                    .putExtra("EXTRA_PROFILE_ID", profile.id))
                            })
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_select)) },
                            leadingIcon = { Icon(painterResource(R.drawable.check_circle_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                selectedIds.add(profile.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_clone)) },
                            leadingIcon = { Icon(painterResource(R.drawable.content_copy_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onClone(profile)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_edit)) },
                            leadingIcon = { Icon(painterResource(R.drawable.edit_square_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                editMenuExpanded = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_rename)) },
                            leadingIcon = { Icon(painterResource(R.drawable.edit_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onRename(profile)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_export)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ios_share_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                itemExportActionMenuExpanded = true
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_delete)) },
                            leadingIcon = { Icon(painterResource(R.drawable.delete_24px), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onDelete(profile)
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    AppDropdownMenu(
                        expanded = editMenuExpanded,
                        onDismissRequest = { editMenuExpanded = false },
                        title = stringResource(R.string.profile_edit)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_edit_config)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.edit_square_24px),
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                editMenuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val intent = Intent(context, configActivityClassFor(profile.kernelVariant))
                                intent.putExtra("EXTRA_EDIT_MODE", true)
                                intent.putExtra("EXTRA_PROFILE_NAME", profile.name)
                                intent.putExtra("EXTRA_PROFILE_ID", profile.id)
                                context.startActivity(intent)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_routing_menu)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_xray_24px),
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                editMenuExpanded = false
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                val intent = Intent(context, com.wireturn.app.ui.activities.XraySettingsActivity::class.java)
                                intent.putExtra("EXTRA_PROFILE_ID", profile.id)
                                context.startActivity(intent)
                            }
                        )
                    }
                    ProfileLinkExportMenu(
                        profile = profile,
                        expanded = itemExportActionMenuExpanded,
                        onDismiss = { itemExportActionMenuExpanded = false },
                        profileJson = { viewModel.getProfilesJson(listOf(profile.id)) },
                        onSave = {
                            itemExportActionMenuExpanded = false
                            onExportClick(listOf(profile.id), false)
                            onExportActionSelected(true)
                        },
                        onShareTuna = {
                            itemExportActionMenuExpanded = false
                            onExportClick(listOf(profile.id), false)
                            onExportActionSelected(false)
                        },
                        onShareOriginal = { uri ->
                            shareText(context, uri, context.getString(R.string.profile_link_original))
                        }
                    )
                }
            }
        }
    )
    if (details) ProfileProbeDetailsDialog(profile, viewModel, { details = false }, {
        viewModel.connectProfile(profile.id) { details = false; onOptimisticSelect(profile.id); onDismiss() }
    })
}

@Composable
private fun SubscriptionHeaderRow(
    sub: Subscription,
    isExpanded: Boolean,
    profileCount: Int,
    isAnyChildSelected: Boolean,
    isSelectionMode: Boolean,
    isAllSubSelected: Boolean,
    isUpdating: Boolean,
    stuckProgress: Float,
    onUpdate: () -> Unit,
    showUrlTest: Boolean,
    testLabel: String,
    testEnabled: Boolean,
    onTest: () -> Unit,
    onSettings: () -> Unit,
    onSelect: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false
) {
    val expansionDescription = stringResource(if (isExpanded) R.string.subscription_expanded else R.string.subscription_collapsed)
    var showDuplicateReport by remember(sub.id) { mutableStateOf<Boolean?>(null) }
    val rejected = sub.importReport?.rejected.orEmpty()
    val duplicates = sub.importReport?.duplicates.orEmpty()
    showDuplicateReport?.let { duplicateReport ->
        val entries = if (duplicateReport) duplicates else rejected
        SubscriptionImportIssuesDialog(entries, duplicates = duplicateReport) { showDuplicateReport = null }
    }
    val arrowRotation by animateFloatAsState(if (isExpanded) 90f else 0f, label = "subscription_arrow")
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            isAnyChildSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sub_header_bg"
    )

    // Pinned at the top, the header is visually detached from the item now scrolled underneath
    // it, so it reads as a standalone rounded chip (bottom corners matching the top ones, plus a
    // shadow to sell that it's floating above the content) instead of the "attached to what's
    // below" look it has at its natural, unpinned position. Driven directly off stuckProgress -
    // no animate*AsState here - so it scrubs in lockstep with the scroll instead of playing its
    // own timed animation once some threshold is crossed.
    val bottomCorner = if (isExpanded) lerp(4.dp, 16.dp, stuckProgress) else 16.dp
    val shadowElevation = lerp(0.dp, 6.dp, stuckProgress)
    // M3(E)'s own collapsing app bars shrink toward a smaller/compact bar on scroll (never grow),
    // and the standard "more compact" motion cue elsewhere in the system is a scale-down, not up
    // (e.g. press states settle to ~0.95). So pinned reads as a slightly inset, compact chip -
    // smaller than the list around it - rather than wider.
    val stuckInset = lerp(0.dp, 4.dp, stuckProgress)

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = bottomCorner, bottomEnd = bottomCorner),
        shadowElevation = shadowElevation,
        onClick = {
            if (isSelectionMode) onToggleSelection()
            else onSelect()
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = expansionDescription }
            .padding(top = 8.dp)
            .padding(horizontal = stuckInset)
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isAllSubSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sub.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(painterResource(R.drawable.arrow_forward_ios_24px), contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = arrowRotation })
                }
                Text(stringResource(R.string.subscription_server_count, profileCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!sub.description.isNullOrBlank()) {
                    SubscriptionDescriptionText(
                        text = sub.description,
                        color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                        stuckProgress = stuckProgress
                    )
                }
            }
            if (!isSelectionMode && (rejected.isNotEmpty() || duplicates.isNotEmpty())) {
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
                    if (rejected.isNotEmpty()) TextButton(onClick = { showDuplicateReport = false }) {
                        Text("Не добавлены: ${rejected.size}")
                    }
                    if (duplicates.isNotEmpty()) TextButton(onClick = { showDuplicateReport = true }) {
                        Text("Объединены: ${duplicates.size}")
                    }
                }
            }
            if (!isSelectionMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (showUrlTest) TextButton(onClick = onTest, enabled = testEnabled) { Text(testLabel) }
                IconButton(onClick = onSettings) {
                    Icon(
                        painter = painterResource(R.drawable.settings_24px),
                        contentDescription = stringResource(R.string.subscription_settings),
                        tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onUpdate, enabled = !isUpdating) {
                    if (isUpdating) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px),
                            contentDescription = stringResource(R.string.subscription_refresh_action),
                            modifier = Modifier.size(20.dp),
                            tint = if (isAnyChildSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Subscription header's description line - clamps to 2 lines with an ellipsis once fully stuck,
 * but scrubs the height continuously in between instead of snapping, so it folds in lockstep
 * with the same scroll that drives the header's corner/shadow/scale. An invisible probe copy
 * (laid out at zero height so it doesn't affect the row) measures the description's natural
 * height and where its second line ends; those two numbers are then interpolated by
 * [stuckProgress] to clip the visible copy. The ellipsis itself only ever appears at the fully
 * stuck endpoint - mid-scroll it's a plain vertical crop, since animating the glyph in/out isn't
 * possible and a raw crop mid-transition reads fine for something passed through quickly.
 *
 * This does change the header item's measured height every scroll frame, which would normally
 * make profileItemPlacementSpec (see its declaration) chase it with a lagging spring - that's
 * why profile rows suppress their placement animation while lazyListState.isScrollInProgress.
 */
@Composable
private fun SubscriptionDescriptionText(
    text: String,
    color: Color,
    stuckProgress: Float
) {
    var naturalHeightPx by remember(text) { mutableFloatStateOf(0f) }
    var twoLineHeightPx by remember(text) { mutableFloatStateOf(0f) }
    // Same size/weight as the kernel description in ProfileListItem/ProfilesBlock.
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)

    Text(
        text = text,
        style = style,
        color = Color.Transparent,
        maxLines = Int.MAX_VALUE,
        onTextLayout = { result ->
            naturalHeightPx = result.size.height.toFloat()
            twoLineHeightPx = if (result.lineCount > 2) result.getLineBottom(1) else naturalHeightPx
        },
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) {}
            }
            .clearAndSetSemantics {}
    )

    when {
        // Not yet measured (e.g. this header was just freshly composed already mid-transition,
        // such as scrolling back into a recycled one) - show unclamped rather than clip to a
        // stale/zero height for a frame before the probe's onTextLayout above corrects it. text
        // is always non-blank here (see call site), so naturalHeightPx settles above 0f once
        // measured and this branch only ever applies on that first, pre-measurement frame.
        naturalHeightPx <= 0f -> {
            Text(text = text, style = style, color = color)
        }
        stuckProgress <= 0f -> {
            Text(text = text, style = style, color = color)
        }
        stuckProgress >= 1f -> {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        else -> {
            val clampedHeightPx = minOf(naturalHeightPx, twoLineHeightPx)
            val heightPx = naturalHeightPx + (clampedHeightPx - naturalHeightPx) * stuckProgress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(androidx.compose.ui.platform.LocalDensity.current) { heightPx.toDp() })
                    .clipToBounds()
            ) {
                Text(text = text, style = style, color = color, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

@Composable
private fun SubscriptionEmptyRow() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.profiles_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ExportDropdownMenus(
    expandedFormat: Boolean,
    onDismissFormat: () -> Unit,
    expandedAction: Boolean,
    onDismissAction: () -> Unit,
    onFormatSelected: (Boolean) -> Unit,
    onActionSelected: (Boolean) -> Unit // true = save, false = share
) {
    AppDropdownMenu(
        expanded = expandedFormat,
        onDismissRequest = onDismissFormat,
        title = stringResource(R.string.profile_export)
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_json)) },
            onClick = { onFormatSelected(true) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.data_array_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_zip)) },
            onClick = { onFormatSelected(false) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.abc_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
    }

    AppDropdownMenu(
        expanded = expandedAction,
        onDismissRequest = onDismissAction,
        title = stringResource(R.string.profile_export)
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_save)) },
            onClick = { onActionSelected(true) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.save_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.profile_export_share)) },
            onClick = { onActionSelected(false) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.share_24px),
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
    }
}

private suspend fun shareFile(context: Context, bytes: ByteArray, fileName: String) {
    val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val exportDir = java.io.File(context.cacheDir, "exports").apply { check(isDirectory || mkdirs()) }
        val file = java.io.File(exportDir, java.io.File(fileName).name)
        file.writeBytes(bytes)
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

/** The config screen Activity for editing a profile of this core type. */
private fun configActivityClassFor(variant: KernelVariant): Class<out Activity> =
    KernelRegistry.get(variant).configActivityClass

private fun getProfileIcon(profile: Profile, outlined: Boolean): Int =
    KernelRegistry.get(profile.kernelVariant).iconRes(profile.kernelConfig, outlined)

@Composable
fun ProfileNameDialog(
    title: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text(stringResource(R.string.profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun showUpdateToast(context: Context, summary: com.wireturn.app.domain.ImportResult) {
    val appContext = context.applicationContext
    val message = if (summary.added == 0 && summary.updated == 0 && summary.removed == 0) {
        appContext.getString(R.string.subscription_update_no_changes)
    } else {
        appContext.getString(R.string.subscription_update_summary, summary.added, summary.removed, summary.total)
    }
    appContext.showExclusiveToast(message + if (summary.skipped > 0) appContext.getString(R.string.profile_import_skipped_suffix, summary.skipped) else "")
}
