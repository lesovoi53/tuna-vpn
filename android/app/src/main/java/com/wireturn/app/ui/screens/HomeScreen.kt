@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.CoreServiceState
import com.wireturn.app.R
import com.wireturn.app.VpnServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.XrayConfiguration
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.ui.AppExclusionTooltip
import com.wireturn.app.ui.CompactItem
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.LabelGroupDefaults
import com.wireturn.app.ui.ModifiedIndicator
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.VerticalAnimatedText
import com.wireturn.app.ui.components.CoreToggleButton
import com.wireturn.app.ui.privacySpoiler
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.ui.theme.ContentAlpha
import com.wireturn.app.ui.trackGestureStartedAtBoundary
import com.wireturn.app.viewmodel.CoreState
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.UpdateState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.isImportant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToExclusions: () -> Unit,
    onNavigateToXrayConfig: () -> Unit,
    onNavigateToConnectionSettings: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onToggleProxy: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProfiles: (() -> Unit)? = null
) {
    // --- State & Data ---
    val context = LocalContext.current
    val orbit = com.wireturn.app.ui.theme.LocalOrbit.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val proxyState by viewModel.coreState.collectAsStateWithLifecycle()
    val connectionActive = proxyState is CoreState.Connected || proxyState is CoreState.Suppressed
    val exitObservation by viewModel.exitObservation.collectAsStateWithLifecycle()
    var showConnectionDetails by rememberSaveable { mutableStateOf(false) }
    var showSavedTests by rememberSaveable { mutableStateOf(false) }
    val probeHistory by viewModel.probeHistory.collectAsStateWithLifecycle()
    LaunchedEffect(connectionActive) { if (!connectionActive) showConnectionDetails = false }
    val xrayState by XrayServiceState.state.collectAsStateWithLifecycle()
    val vpnServiceState by VpnServiceState.state.collectAsStateWithLifecycle()

    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    val xrayConfig by viewModel.xrayConfig.collectAsStateWithLifecycle()
    val xraySettings by viewModel.xraySettings.collectAsStateWithLifecycle()
    val vlessConfig by viewModel.vlessConfig.collectAsStateWithLifecycle()
    val wgConfig by viewModel.wgConfig.collectAsStateWithLifecycle()
    val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()

    val proxySession by CoreServiceState.session.collectAsStateWithLifecycle()
    val xraySession by XrayServiceState.session.collectAsStateWithLifecycle()

    val batteryNotificationDismissed by viewModel.batteryNotificationDismissed.collectAsStateWithLifecycle()
    val vpnEnabled = vpnSettings.enabled
    val appsExclusionHintShown by viewModel.appsExclusionHintShown.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val restartAttempt by CoreServiceState.restartAttempt.collectAsStateWithLifecycle()
    val isRestarting = restartAttempt != null

    val currentProfileId by viewModel.currentProfileId.collectAsStateWithLifecycle()
    val routingSubscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val autoLaunchSettings by viewModel.autoLaunchSettings.collectAsStateWithLifecycle()

    val selectedGroupId by viewModel.activeConnectionGroupId.collectAsStateWithLifecycle()
    val connectionGroups by viewModel.connectionGroups.collectAsStateWithLifecycle()
    val selectedGroup = connectionGroups.find { it.id == selectedGroupId && com.wireturn.app.domain.ManualGroupPolicy.allows(it) }
    if (showSavedTests) {
        val shownProfile = profiles.find { it.id == (if (connectionActive) proxySession?.profileId else currentProfileId) }
            ?: profiles.find { it.id == currentProfileId }
        if (selectedGroup != null) GroupSavedResultsDialog(selectedGroup, profiles, viewModel, { showSavedTests = false })
        else if (shownProfile != null) ProfileProbeDetailsDialog(shownProfile, viewModel, { showSavedTests = false })
    }
    val displayedConfig = com.wireturn.app.domain.HomeSelection.config(
        clientConfig, proxySession?.clientConfig, connectionActive, selectedGroup)
    val activeConfig = displayedConfig ?: clientConfig
    val activeXrayConfig = xraySession?.xray ?: xrayConfig
    val activeXraySettings = xraySession?.settings ?: xraySettings
    val activeWgConfig = xraySession?.wg ?: wgConfig
    val activeVlessConfig = xraySession?.vless ?: vlessConfig

    val isArchitectureSupported = viewModel.isArchitectureSupported
    val deviceArchitecture = viewModel.deviceArchitecture
    val showArchWarning = rememberSaveable { mutableStateOf(!isArchitectureSupported) }

    if (showArchWarning.value) {
        AlertDialog(
            onDismissRequest = { showArchWarning.value = false },
            title = { Text(stringResource(R.string.warn_unsupported_arch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.warn_unsupported_arch_desc,
                        deviceArchitecture
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showArchWarning.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    val proxyPing by viewModel.proxyPing.collectAsStateWithLifecycle()
    var lastSuccessPing by remember { mutableStateOf<MainViewModel.PingResult.Success?>(null) }
    var isControlPingScheduled by rememberSaveable { mutableStateOf(value = false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Effects & Lifecycle ---
    LaunchedEffect(proxyPing, proxyState) {
        if (proxyPing is MainViewModel.PingResult.Success) {
            lastSuccessPing = proxyPing as MainViewModel.PingResult.Success
        }

        val isProxyActive =
            proxyState is CoreState.Connected || proxyState is CoreState.Suppressed
        if (isProxyActive) {
            if (proxyPing is MainViewModel.PingResult.Success || proxyPing is MainViewModel.PingResult.Error) {
                if (!isControlPingScheduled) {
                    isControlPingScheduled = true
                    delay(1_000.milliseconds)
                } else {
                    delay(15_000.milliseconds)
                }

                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.checkProxyPing()
                }
            } else if (proxyPing == null) {
                viewModel.checkProxyPing(delayFirst = true)
            }
        } else {
            isControlPingScheduled = false
        }
    }

    LaunchedEffect(xraySession?.xray) {
        lastSuccessPing = null
    }

    val proxyTransfer by viewModel.proxyTransfer.collectAsStateWithLifecycle()
    val profilesGestureEnabled by viewModel.profilesGestureEnabled.collectAsStateWithLifecycle()
    val homeScrollState = rememberScrollState()
    val showProfilesDialog = rememberSaveable { mutableStateOf(false) }

    // --- Swipe-up-to-open profiles shade ---
    // The real ModalBottomSheet lives in its own Android Window, so a single continuous touch
    // starting on this screen can never be handed off to drive it directly - there's no public
    // API to feed it raw drag deltas either. So this is a threshold trigger, not a live drag:
    // once an upward swipe past the point where home content can't scroll any further crosses
    // profilesRevealThresholdPx (or is a fast enough flick), it opens the sheet with its normal
    // entrance animation - the same as tapping the profile row.
    //
    // Gated to gestures that already started with nothing left to scroll - otherwise a single
    // swipe that merely scrolls long content down to its end would also fire the shade in the
    // same motion. Reaching the bottom mid-gesture doesn't count; only the *next* swipe does.
    var homeGestureStartedAtBottom by remember { mutableStateOf(false) }
    var profilesRevealDragPx by remember { mutableFloatStateOf(0f) }
    var profilesRevealTriggered by remember { mutableStateOf(false) }
    val profilesRevealThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val openProfilesFromGesture by rememberUpdatedState(onOpenProfiles)
    val profilesRevealConnection = remember(profilesGestureEnabled, profilesRevealThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!profilesGestureEnabled ||
                    source != NestedScrollSource.UserInput ||
                    !homeGestureStartedAtBottom ||
                    profilesRevealTriggered ||
                    showProfilesDialog.value
                ) {
                    return Offset.Zero
                }
                if (available.y >= 0f || homeScrollState.canScrollForward) {
                    profilesRevealDragPx = 0f
                    return Offset.Zero
                }
                profilesRevealDragPx -= available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val revealDistance = profilesRevealDragPx
                profilesRevealDragPx = 0f
                if (profilesGestureEnabled &&
                    homeGestureStartedAtBottom &&
                    !profilesRevealTriggered &&
                    !showProfilesDialog.value &&
                    revealDistance >= profilesRevealThresholdPx
                ) {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    openProfilesFromGesture?.invoke() ?: run { showProfilesDialog.value = true }
                }
                profilesRevealTriggered = false
                return Velocity.Zero
            }
        }
    }

    // --- Launchers ---
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var notificationsHintDismissed by rememberSaveable { mutableStateOf(false) }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Settings activities need not return RESULT_OK; read the actual state.
        isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        // Composition can start after ON_RESUME (for example after preferences load).
        viewModel.setHomeScreenActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setHomeScreenActive(true)
                    isIgnoringBatteryOptimizations =
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    hasNotificationPermission =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                context,
                                "android.permission.POST_NOTIFICATIONS"
                            ) == PackageManager.PERMISSION_GRANTED
                        } else true
                }

                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setHomeScreenActive(false)
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setHomeScreenActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // --- Business Logic Logic (Mismatches & Alerts) ---
    val warnVpnRequiresXray = stringResource(R.string.warn_vpn_requires_xray)

    val showVpnWarning = {
        if (profiles.isNotEmpty()) {
            context.showExclusiveToast(warnVpnRequiresXray)
        }
    }


    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.setVpnEnabled(true)
        }
    }

    // --- UI Layout ---
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.turn_proxy_title),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToLogs()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.terminal_24px),
                            contentDescription = stringResource(R.string.logs_title)
                        )
                    }
                    IconButton(
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            onNavigateToSettings()
                        }
                    ) {
                        BadgedBox(
                            badge = {

                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.settings_24px),
                                contentDescription = stringResource(R.string.app_settings_title)
                            )
                        }
                    }
                },
                expandedHeight = 56.dp
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 600.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .pointerInput(profilesGestureEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        homeGestureStartedAtBottom = profilesGestureEnabled && !homeScrollState.canScrollForward &&
                            down.position.y >= size.height - 96.dp.toPx()
                        profilesRevealDragPx = 0f
                    }
                }
                .nestedScroll(profilesRevealConnection)
                .verticalScroll(homeScrollState)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (orbit) Arrangement.Top else Arrangement.Center
        ) {
            TunnelHealthCard(connectionActive, activeConfig)

            // --- Update Banner ---
            // App updater remains available in code, hidden in this release.


            val permissionPrompts = com.wireturn.app.domain.HomePermissionPrompts.resolve(
                hasNotificationPermission, isIgnoringBatteryOptimizations, batteryNotificationDismissed,
                notificationsHintDismissed,
            )
            val permissionHintColor = if (permissionPrompts.isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
            // --- Permissions & Optimization Banner ---
            AnimatedVisibility(
                visible = permissionPrompts.visible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SectionItem(
                    position = ItemPosition.Single,
                    containerColor = if (permissionPrompts.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StandardLeadingIcon {
                                    Icon(
                                        painter = painterResource(if (permissionPrompts.isError) R.drawable.error_24px else R.drawable.info_24px),
                                        contentDescription = null,
                                        tint = permissionHintColor
                                    )
                                }
                                Column {
                                    RowLabel(stringResource(if (permissionPrompts.showNotificationsHint) R.string.permission_notifications else R.string.battery_hint_title))
                                    Spacer(Modifier.height(2.dp))
                                    SupportingText(
                                        stringResource(if (permissionPrompts.showNotificationsHint) R.string.notifications_hint_description else R.string.battery_hint_description),
                                        color = permissionHintColor
                                    )
                                    if (permissionPrompts.showNotificationsHint && permissionPrompts.showBatteryHint) {
                                        Spacer(Modifier.height(8.dp))
                                        SupportingText(stringResource(R.string.battery_hint_description), color = permissionHintColor)
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                    if (permissionPrompts.showNotificationsHint) {
                                        Button(
                                            onClick = {
                                                HapticUtil.perform(
                                                    context,
                                                    HapticUtil.Pattern.CLICK
                                                )
                                                notificationLauncher.launch("android.permission.POST_NOTIFICATIONS")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            modifier = Modifier.heightIn(min = 40.dp)
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.info_24px),
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.permission_notifications),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                    if (permissionPrompts.showBatteryHint) {
                                        Button(
                                            onClick = {
                                                HapticUtil.perform(
                                                    context,
                                                    HapticUtil.Pattern.CLICK
                                                )
                                                batteryOptLauncher.launch(
                                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                        data =
                                                            "package:${context.packageName}".toUri()
                                                    }
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            modifier = Modifier.heightIn(min = 40.dp)
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.battery_android_frame_5_24px),
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.permission_battery),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                TextButton(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        if (permissionPrompts.showBatteryHint) viewModel.setBatteryNotificationDismissed(true)
                                        if (permissionPrompts.showNotificationsHint) notificationsHintDismissed = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.heightIn(min = 40.dp)
                                ) {
                                    Text(
                                        stringResource(if (permissionPrompts.showBatteryHint) R.string.battery_hint_dismiss else R.string.btn_close),
                                        color = permissionHintColor,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- Proxy Toggle ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CoreToggleButton(
                    viewModel = viewModel,
                    onClick = {
                        if (profiles.isEmpty()) {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            context.startActivity(
                                Intent(
                                    context,
                                    com.wireturn.app.ui.activities.AddProfileActivity::class.java
                                )
                            )
                        } else {
                            onToggleProxy()
                        }
                    }
                )
            }

            if (connectionActive) {
                Spacer(Modifier.height(16.dp))
                ExitAddressCard(exitObservation, privacyMode, viewModel::refreshExitObservation)
            }
            Spacer(Modifier.height(22.dp))

            if (orbit) {
                OrbitSectionTitle(R.string.orbit_current)
                Spacer(Modifier.height(10.dp))
                ProfilesBlock(viewModel = viewModel, onClick = {
                    if (profiles.isNotEmpty() || selectedGroup != null) showSavedTests = true
                    else onOpenProfiles?.invoke() ?: run { showProfilesDialog.value = true }
                })
                Spacer(Modifier.height(12.dp))
            }

            SectionItem(position = ItemPosition.Single, onClick = onNavigateToXrayConfig) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StandardLeadingIcon { Icon(painterResource(R.drawable.route_24px), null, tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f)) {
                        val routingSub = routingSubscriptions.find { it.id == profiles.find { p -> p.id == currentProfileId }?.subscriptionId }
                        val applied = connectionActive && xraySession?.xray?.routingEnabled == true &&
                            (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute)
                        Text(when {
                            !xrayConfig.routingEnabled -> stringResource(R.string.cd_routing_off)
                            applied -> stringResource(R.string.cd_routing_applied)
                            connectionActive -> stringResource(R.string.cd_routing_starting)
                            else -> stringResource(R.string.cd_routing_on)
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(if (routingSub != null && com.wireturn.app.domain.ProfileRouting.enabled(routingSub)) {
                            if (orbit) stringResource(R.string.orbit_subscription_rules) else stringResource(R.string.cd_subscription_rules, routingSub.name)
                        } else stringResource(R.string.cd_app_rules), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(18.dp))
                }
            }
            if (!xrayConfig.routingEnabled) {
                TextButton(onClick = viewModel::enableRouting) { Text(stringResource(R.string.cd_enable_routing)) }
            }
            // --- Ping & Transfer Stats ---
            // Works off Xray when it's running, or off VPN mode's own hev-socks5-tunnel counters
            // when VPN is up without Xray (e.g. straight OLCRTC/WEBDAV) - see MainViewModel.
            AnimatedVisibility(
                visible = (xrayState == XrayState.Running || xrayState == XrayState.DirectRoute || vpnServiceState == VpnState.Running) &&
                    (proxyState is CoreState.Connected || proxyState is CoreState.Suppressed),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(
                            10.dp,
                            Alignment.CenterHorizontally
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Stats Block
                        CompactItem(
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val transfer = proxyTransfer
                                // Download
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_downward_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .offset(y = 1.dp)
                                            .widthIn(min = 60.dp)
                                    ) {
                                        Text(
                                            text = formatBytes(transfer?.rx ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatSpeed(transfer?.rxSpeed ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }
                                }

                                // Upload
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_upward_24px),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ContentAlpha.secondary),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .offset(y = 1.dp)
                                            .widthIn(min = 60.dp)
                                    ) {
                                        Text(
                                            text = formatBytes(transfer?.tx ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatSpeed(transfer?.txSpeed ?: 0L),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Ping Block
                        CompactItem(
                            modifier = Modifier.fillMaxHeight(),
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                viewModel.checkProxyPing()
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.wifi_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 52.dp)
                                        .offset(y = 1.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val currentPing = proxyPing
                                    val infiniteTransition =
                                        rememberInfiniteTransition(label = "ping_pulse")
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 0.9f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulse_alpha"
                                    )

                                    when (currentPing) {
                                        is MainViewModel.PingResult.Loading -> {
                                            if (lastSuccessPing != null) {
                                                VerticalAnimatedText(
                                                    text = stringResource(
                                                        R.string.ping_ms,
                                                        lastSuccessPing!!.ms
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = (if (lastSuccessPing!!.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error).copy(
                                                        alpha = pulseAlpha
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    contentAlignment = Alignment.Center
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(width = 36.dp, height = 12.dp)
                                                        .graphicsLayer { alpha = pulseAlpha }
                                                        .background(
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                                alpha = 0.2f
                                                            ),
                                                            shape = CircleShape
                                                        )
                                                )
                                            }
                                        }

                                        is MainViewModel.PingResult.Success -> {
                                            VerticalAnimatedText(
                                                text = stringResource(
                                                    R.string.ping_ms,
                                                    currentPing.ms
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (currentPing.ms < 1100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold,
                                                contentAlignment = Alignment.Center
                                            )
                                        }

                                        is MainViewModel.PingResult.Error -> {
                                            VerticalAnimatedText(
                                                text = stringResource(R.string.ping_error),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                contentAlignment = Alignment.Center
                                            )
                                        }

                                        null -> {
                                            VerticalAnimatedText(
                                                text = stringResource(R.string.ping_unknown),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                contentAlignment = Alignment.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // --- Profiles Section ---
            if (!orbit) ProfilesBlock(
                viewModel = viewModel,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showProfilesDialog.value = true
                }
            )

            Spacer(Modifier.height(24.dp))



            // --- wireturn:// / wt:// deep link import ---
            // MainActivity forwards ACTION_VIEW links here via DeepLinkBus instead of importing
            // directly, since the link may have been opened from outside the app with no prior
            // user action inside WireTurn. The confirmation dialog stands alone (nothing else
            // competes for the window) - the profile list itself only opens *after* a successful
            // add, scrolled to and briefly highlighting whatever was just added, so opening it
            // shows a result rather than an empty backdrop behind the confirmation prompt.
            var pendingDeepLink by remember { mutableStateOf<com.wireturn.app.ui.DeepLinkRequest?>(null) }
            var isImportingDeepLink by remember { mutableStateOf(false) }
            var deepLinkImportJob by remember { mutableStateOf<Job?>(null) }

            val deepLinkPreviewState = remember(pendingDeepLink) {
                mutableStateOf<com.wireturn.app.domain.DeepLinkPreview?>(null)
            }
            LaunchedEffect(pendingDeepLink) {
                val request = pendingDeepLink ?: return@LaunchedEffect
                deepLinkPreviewState.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.wireturn.app.domain.previewDeepLink(request.link)
                }
            }
            val deepLinkPreview = deepLinkPreviewState.value

            LaunchedEffect(Unit) {
                com.wireturn.app.ui.DeepLinkBus.pendingLink.collect { request ->
                    pendingDeepLink = request
                }
            }

            val importProfilesSuccessMsg = stringResource(R.string.import_profiles_success)
            val importSubscriptionSuccessMsg = stringResource(R.string.import_subscription_success)
            val importErrorMsg = stringResource(R.string.import_error)

            fun dismissDeepLink() {
                deepLinkImportJob?.cancel()
                deepLinkImportJob = null
                isImportingDeepLink = false
                pendingDeepLink = null
            }

            fun revealInProfilesList(scrollTargetId: String?) {
                showProfilesDialog.value = true
                if (scrollTargetId != null) {
                    scope.launch {
                        delay(300.milliseconds) // let ProfilesDialog compose and its scroll-request collector attach
                        com.wireturn.app.ui.UIEventBus.requestScroll(scrollTargetId)
                    }
                }
            }

            var pendingSubscriptionUrl by remember { mutableStateOf<String?>(null) }
            val deepLinkLocalNetworkPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                val url = pendingSubscriptionUrl
                pendingSubscriptionUrl = null
                if (isGranted && url != null) {
                    isImportingDeepLink = true
                    deepLinkImportJob = scope.launch {
                        try {
                            val status = viewModel.smartImport(url)
                            if (status is com.wireturn.app.domain.ImportStatus.Success) {
                                context.showExclusiveToast(status.message ?: importSubscriptionSuccessMsg)
                                if (status.message == null) revealInProfilesList(status.id)
                            } else {
                                context.showExclusiveToast(importErrorMsg)
                            }
                        } finally {
                            isImportingDeepLink = false
                            deepLinkImportJob = null
                            pendingDeepLink = null
                        }
                    }
                } else if (url != null) {
                    pendingDeepLink = null
                }
            }

            fun confirmDeepLinkSubscription(url: String) {
                val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37 &&
                    com.wireturn.app.domain.isLocalNetworkHost(url) &&
                    ContextCompat.checkSelfPermission(
                        context,
                        "android.permission.ACCESS_LOCAL_NETWORK"
                    ) != PackageManager.PERMISSION_GRANTED

                if (needsLocalNetworkPermission) {
                    pendingSubscriptionUrl = url
                    deepLinkLocalNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    return
                }

                isImportingDeepLink = true
                deepLinkImportJob = scope.launch {
                    try {
                        val status = viewModel.smartImport(url)
                        if (status is com.wireturn.app.domain.ImportStatus.Success) {
                            context.showExclusiveToast(status.message ?: importSubscriptionSuccessMsg)
                            if (status.message == null) revealInProfilesList(status.id)
                        } else {
                            context.showExclusiveToast(importErrorMsg)
                        }
                    } finally {
                        isImportingDeepLink = false
                        deepLinkImportJob = null
                        pendingDeepLink = null
                    }
                }
            }

            when (deepLinkPreview) {
                is com.wireturn.app.domain.DeepLinkPreview.Routing -> {
                    val command = deepLinkPreview.command
                    AlertDialog(
                        onDismissRequest = { if (!isImportingDeepLink) dismissDeepLink() },
                        title = { Text("Импорт маршрутизации: ${command.policy.name}") },
                        text = { Text("Правил: ${command.policy.routing.rules.size}. Приложений: ${command.apps?.packages?.size ?: 0}. " +
                            "Общий маршрут по умолчанию: ${command.policy.routing.fallback}. DNS и общая политика будут заменены; личные правила сохранят приоритет. " +
                            (command.apps?.let { "Список приложений «${if (it.bypass) "Без VPN" else "Через VPN"}» будет дополнен. " } ?: "") +
                            "Правила подписки могут иметь приоритет. Перед сохранением проверяются DNS, правила и базы; возможна загрузка баз. Для активного VPN потребуется переподключение.") },
                        confirmButton = { TextButton(enabled = !isImportingDeepLink, onClick = {
                            val link = pendingDeepLink?.link ?: return@TextButton
                            isImportingDeepLink = true
                            deepLinkImportJob = scope.launch {
                                try {
                                    val status = viewModel.smartImport(link)
                                    context.showExclusiveToast((status as? com.wireturn.app.domain.ImportStatus.Success)?.message ?: importErrorMsg,
                                        android.widget.Toast.LENGTH_LONG)
                                } finally { dismissDeepLink() }
                            }
                        }) { Text(if (isImportingDeepLink) "Проверка…" else "Применить") } },
                        dismissButton = { TextButton(enabled = !isImportingDeepLink, onClick = { dismissDeepLink() }) { Text(stringResource(R.string.cancel)) } }
                    )
                }
                is com.wireturn.app.domain.DeepLinkPreview.AppExclusions -> {
                    LaunchedEffect(pendingDeepLink) {
                        val request = pendingDeepLink ?: return@LaunchedEffect
                        try {
                            val status = viewModel.smartImport(request.link)
                            context.showExclusiveToast(
                                (status as? com.wireturn.app.domain.ImportStatus.Success)?.message ?: importErrorMsg,
                                android.widget.Toast.LENGTH_LONG
                            )
                        } finally {
                            pendingDeepLink = null
                        }
                    }
                }
                is com.wireturn.app.domain.DeepLinkPreview.Profiles -> {
                    val referrerHost = pendingDeepLink?.referrerHost
                    val countPhrase = androidx.compose.ui.res.pluralStringResource(
                        R.plurals.deep_link_import_profiles_count,
                        deepLinkPreview.count,
                        deepLinkPreview.count
                    )
                    val title = if (referrerHost != null) {
                        stringResource(R.string.deep_link_import_profiles_title_with_site, countPhrase, referrerHost)
                    } else {
                        stringResource(R.string.deep_link_import_profiles_title_no_site, countPhrase)
                    }
                    val defaultName = stringResource(R.string.profile_default_name)
                    AlertDialog(
                        onDismissRequest = { dismissDeepLink() },
                        title = { Text(title) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.deep_link_import_profiles_desc,
                                    deepLinkPreview.names.joinToString("\n") { "• ${it.ifBlank { defaultName }}" }
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    isImportingDeepLink = true
                                    deepLinkImportJob = scope.launch {
                                        try {
                                            val (result, imported) = viewModel.importProfilesWithResult(listOf(null to deepLinkPreview.json))
                                            if (result.total > 0) {
                                                context.showExclusiveToast(importProfilesSuccessMsg)
                                                revealInProfilesList(imported.firstOrNull()?.id)
                                            } else {
                                                context.showExclusiveToast(importErrorMsg)
                                            }
                                        } finally {
                                            dismissDeepLink()
                                        }
                                    }
                                },
                                enabled = !isImportingDeepLink
                            ) {
                                if (isImportingDeepLink) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.btn_add))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { dismissDeepLink() }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
                is com.wireturn.app.domain.DeepLinkPreview.SubscriptionLink -> {
                    val host = remember(deepLinkPreview.url) {
                        try {
                            deepLinkPreview.url.toUri().host } catch (_: Exception) { null } ?: deepLinkPreview.url
                    }
                    AlertDialog(
                        onDismissRequest = { dismissDeepLink() },
                        title = { Text(stringResource(R.string.deep_link_import_subscription_title, host)) },
                        text = { Text(stringResource(R.string.deep_link_import_subscription_desc)) },
                        confirmButton = {
                            TextButton(
                                onClick = { confirmDeepLinkSubscription(deepLinkPreview.url) },
                                enabled = !isImportingDeepLink
                            ) {
                                if (isImportingDeepLink) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.btn_add))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { dismissDeepLink() }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
                com.wireturn.app.domain.DeepLinkPreview.Invalid -> {
                    AlertDialog(
                        onDismissRequest = { pendingDeepLink = null },
                        title = { Text(stringResource(R.string.deep_link_import_invalid_title)) },
                        text = { Text(stringResource(R.string.deep_link_import_invalid)) },
                        confirmButton = {
                            TextButton(onClick = { pendingDeepLink = null }) { Text(stringResource(R.string.btn_close)) }
                        }
                    )
                }
                null -> {}
            }

            val isSocks5Native = activeConfig.kernelVariant.isSocks5Native

            RoutingSettingsGroup(stringResource(R.string.cd_global_vpn), stringResource(R.string.cd_global_vpn_note)) {
            SectionGroup {
                val toggleVpnAction = { next: Boolean ->
                    HapticUtil.perform(
                        context,
                        if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                    )

                    if (next && !xrayConfig.isRequired && !isSocks5Native) {
                        showVpnWarning()
                    }

                    if (next) {
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            viewModel.setVpnEnabled(true)
                        }
                    } else {
                        viewModel.setVpnEnabled(false)
                    }
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = { toggleVpnAction(!vpnEnabled) }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.vpn_mode),
                        checked = vpnEnabled,
                        onCheckedChange = toggleVpnAction,
                        isModified = xraySession?.wg != null && vpnEnabled != (vpnServiceState == VpnState.Running),
                        supportingText = when {
                            vpnEnabled && !xrayConfig.isRequired && !isSocks5Native -> stringResource(R.string.vpn_status_xray_needed)
                            vpnServiceState == VpnState.Starting -> stringResource(R.string.starting)
                            vpnServiceState == VpnState.Running -> stringResource(R.string.running)
                            vpnServiceState is VpnState.Error -> (vpnServiceState as VpnState.Error).message
                            else -> stringResource(R.string.idle)
                        },
                        supportingSingleLine = true,
                        useLargeIcon = true,
                        leadingIcon = {
                            when (vpnServiceState) {
                                VpnState.Idle, VpnState.Running, is VpnState.Error -> Icon(
                                    painter = painterResource(R.drawable.vpn_key_24px),
                                    contentDescription = null,
                                    tint = if (vpnServiceState == VpnState.Running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                VpnState.Starting -> LoadingIndicator()
                            }
                        },
                        trailingContent = {
                            AppExclusionTooltip(
                                hintShown = appsExclusionHintShown,
                                onHintShown = { viewModel.setAppsExclusionHintShown(true) }
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        if (!appsExclusionHintShown) {
                                            viewModel.setAppsExclusionHintShown(true)
                                        }
                                        onNavigateToExclusions()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.route_24px),
                                        contentDescription = stringResource(R.string.vpn_apps_exceptions),
                                        modifier = Modifier.size(24.dp),
                                        tint = if (vpnSettings.filteringEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
            }

            }
            if (connectionActive) {
            SectionItem(position = ItemPosition.Single, onClick = { showConnectionDetails = true }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    StandardLeadingIcon { Icon(painterResource(R.drawable.info_24px), null, tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cd_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.cd_subtitle), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(18.dp))
                }
            }
            if (showConnectionDetails) {
            val runningProfile = profiles.find { it.id == proxySession?.profileId }
            val runningSub = routingSubscriptions.find { it.id == runningProfile?.subscriptionId }
            ConnectionDetailsDialog(proxySession, activeConfig, activeXraySettings, activeXrayConfig.routingEnabled,
                if (runningSub != null && com.wireturn.app.domain.ProfileRouting.enabled(runningSub)) stringResource(R.string.cd_subscription_rules, runningSub.name) else stringResource(R.string.cd_app_rules),
                vpnSettings, exitObservation, viewModel::refreshExitObservation, proxyTransfer, proxyPing, privacyMode,
                onDismiss = { showConnectionDetails = false },
                testResults = { if (runningProfile != null) SavedProbeResultsContent(runningProfile, probeHistory) }) {
            RoutingSettingsGroup(stringResource(R.string.cd_local_proxies), stringResource(R.string.cd_proxy_addresses)) {
            val showXray = xrayConfig.isRequired

            fun formatProxyAddr(
                addr: String,
                user: String,
                pass: String,
                authEnabled: Boolean
            ): String {
                if (addr.isBlank()) return ""
                return if (authEnabled && user.isNotBlank()) {
                    "$user:$pass@$addr"
                } else {
                    addr
                }
            }

            val displaySocksAddr = when {
                showXray -> activeXraySettings.socksBindAddress
                isSocks5Native -> activeConfig.socksAddr
                else -> activeXraySettings.socksBindAddress
            }

            val copySocksAddr = when {
                showXray -> formatProxyAddr(
                    activeXraySettings.socksBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )

                isSocks5Native -> formatProxyAddr(
                    activeConfig.socksAddr,
                    activeConfig.socksUser,
                    activeConfig.socksPass,
                    // Some kernels' embedded SOCKS5 server has no auth support upstream (see
                    // Kernel.socks5SupportsAuth / XrayService's own guard) - never show credentials
                    // that would make an external client attempt an auth handshake it doesn't understand.
                    activeConfig.isSocksAuthEnabled && KernelRegistry.get(activeConfig.kernelVariant).socks5SupportsAuth
                )

                else -> formatProxyAddr(
                    activeXraySettings.socksBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )
            }

            val displayHttpAddr = when {
                showXray -> activeXraySettings.httpBindAddress
                isSocks5Native -> ""
                else -> activeXraySettings.httpBindAddress
            }

            val copyHttpAddr = when {
                showXray -> formatProxyAddr(
                    activeXraySettings.httpBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )

                isSocks5Native -> ""
                else -> formatProxyAddr(
                    activeXraySettings.httpBindAddress,
                    activeXraySettings.proxyUser,
                    activeXraySettings.proxyPass,
                    activeXraySettings.isProxyAuthEnabled
                )
            }

            val isSocksModified = when {
                showXray -> xraySession != null && activeXraySettings.socksBindAddress != xraySession?.settings?.socksBindAddress
                isSocks5Native -> proxySession != null && activeConfig.socksAddr != proxySession?.clientConfig?.socksAddr
                else -> xraySession != null && activeXraySettings.socksBindAddress != xraySession?.settings?.socksBindAddress
            }

            val isHttpModified = when {
                showXray -> xraySession != null && activeXraySettings.httpBindAddress != xraySession?.settings?.httpBindAddress
                else -> false
            }

            SectionGroup(
                modifier = Modifier.graphicsLayer {
                    alpha = if (showXray || isSocks5Native) 1f else 0.38f
                }
            ) {
                val clipboard = LocalClipboard.current
                val socks5Label = stringResource(R.string.clipboard_label_socks5)
                val httpLabel = stringResource(R.string.clipboard_label_http)

                var socksCopied by remember { mutableStateOf(false) }
                LaunchedEffect(socksCopied) {
                    if (socksCopied) {
                        delay(1_500.milliseconds)
                        socksCopied = false
                    }
                }

                val showHttpBlock = displayHttpAddr.isNotBlank() || isHttpModified

                SectionItem(
                    position = ItemPosition.Top,
                    enabled = showXray || isSocks5Native,
                    onClick = {
                        if (privacyMode) return@SectionItem
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipData.newPlainText(socks5Label, copySocksAddr).toClipEntry()
                            )
                            socksCopied = true
                        }
                    }
                ) {
                    ProxyAddressRow(
                        label = stringResource(R.string.socks5),
                        address = displaySocksAddr,
                        isModified = isSocksModified,
                        isCopied = socksCopied,
                        privacyMode = privacyMode,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.lan_24px),
                                contentDescription = null,
                                tint = if (showXray || isSocks5Native) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.38f
                                )
                            )
                        }
                    )
                }

                AnimatedVisibility(
                    visible = showHttpBlock,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    var httpCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(httpCopied) {
                        if (httpCopied) {
                            delay(1_500.milliseconds)
                            httpCopied = false
                        }
                    }

                    SectionItem(
                        enabled = showXray,
                        onClick = {
                            if (privacyMode) return@SectionItem
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText(
                                        httpLabel,
                                        copyHttpAddr
                                    ).toClipEntry()
                                )
                                httpCopied = true
                            }
                        }
                    ) {
                        ProxyAddressRow(
                            label = stringResource(R.string.xray_http),
                            address = displayHttpAddr,
                            isModified = isHttpModified,
                            isCopied = httpCopied,
                            privacyMode = privacyMode,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.lan_24px),
                                    contentDescription = null,
                                    tint = if (showXray) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.38f
                                    )
                                )
                            }
                        )
                    }
                }
            }

            }
            }
            }
            }
            Spacer(Modifier.height(2.dp))
            SectionItem(
                position = ItemPosition.Bottom,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    onNavigateToConnectionSettings()
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StandardLeadingIcon {
                        Icon(
                            painter = painterResource(R.drawable.settings_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    LabelGroup(
                        label = stringResource(R.string.connection_settings_title),
                        supportingText = stringResource(R.string.connection_settings_desc),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
        }
    }
    ProfilesDialog(viewModel = viewModel, visible = showProfilesDialog.value, onDismiss = { showProfilesDialog.value = false })
    }
}

// --- Update Banner Component ---
@Composable
private fun UpdateBanner(
    state: UpdateState,
    progress: Int,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    AnimatedVisibility(
        visible = state.isImportant,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        SectionItem(
            position = ItemPosition.Single,
            containerColor = containerColor,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            UpdateBlock(
                state = state,
                progress = progress,
                showChangelog = false,
                onDownload = onDownload,
                onInstall = onInstall,
                onCheck = onCheck
            )
        }
    }
}

// --- Utils ---
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %siB", bytes / 1024.0.pow(exp.toDouble()), pre)
}


private fun formatSpeed(bytesPerSecond: Long): String {
    return formatBytes(bytesPerSecond) + "/s"
}

@Composable
private fun ProxyAddressRow(
    label: String,
    address: String,
    isModified: Boolean,
    isCopied: Boolean,
    leadingIcon: @Composable (() -> Unit)? = null,
    privacyMode: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            StandardLeadingIcon(content = leadingIcon)
        }

        Column(modifier = Modifier.weight(1f)) {
            val displayText = if (privacyMode) {
                if (address.isBlank()) " ".repeat(25) else address.redact(true)
            } else {
                address.ifBlank { stringResource(R.string.dash) }
            }
            Text(
                displayText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (address.isBlank() && !privacyMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = 0.5f
                )
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.privacySpoiler(privacyMode)
            )
            Spacer(Modifier.height(LabelGroupDefaults.SupportingGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModifiedIndicator(isModified)
            }
        }
        Icon(
            painter = painterResource(if (isCopied) R.drawable.check_circle_24px else R.drawable.content_copy_24px),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
