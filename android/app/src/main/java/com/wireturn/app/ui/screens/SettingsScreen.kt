@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.R
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ExpandableSection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabeledButtonGroup
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.UpdateBlock
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.selectableButtonItem
import com.wireturn.app.viewmodel.MainViewModel
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    appearanceOnly: Boolean = false
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
    val privacyMode by viewModel.privacyMode.collectAsStateWithLifecycle()
    val profilesGestureEnabled by viewModel.profilesGestureEnabled.collectAsStateWithLifecycle()

    val showResetDialog = rememberSaveable { mutableStateOf(false) }
    val showListenHelp = remember { mutableStateOf(false) }
    val showSocksHelp = remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !com.wireturn.app.ui.theme.LocalOrbit.current
    val supportsSystemTheme = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }

    LaunchedEffect(supportsDynamicColor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && dynamicTheme) {
            viewModel.setDynamicTheme(false)
        }
    }

    LaunchedEffect(supportsSystemTheme) {
        if (!supportsSystemTheme && themeMode == ThemeMode.SYSTEM) {
            viewModel.setThemeMode(ThemeMode.DARK)
        }
    }


    val dash = stringResource(R.string.dash)
    val appVersion = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: dash
        } catch (_: Exception) {
            dash
        }
    }

    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopAppBar(
                title = stringResource(if (appearanceOnly) R.string.orbit_appearance else R.string.app_settings_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.info_24px),
                            contentDescription = stringResource(R.string.info_desc)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            // 1. Оформление
            if (!appearanceOnly) {
                DefaultFragmentationSection()
                TunMtuSection()
            }
            val themeModes = remember(supportsSystemTheme) {
                if (supportsSystemTheme) {
                    ThemeMode.entries
                } else {
                    ThemeMode.entries.filter { it != ThemeMode.SYSTEM }
                }
            }
            val themeModeLabels = themeModes.associateWith { mode ->
                stringResource(when (mode) {
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                    ThemeMode.SYSTEM -> R.string.theme_system
                })
            }

            SectionGroup(title = stringResource(R.string.app_appearance)) {
                SectionItem(
                    position = if (supportsDynamicColor) ItemPosition.Top else ItemPosition.Single
                ) {
                    SettingsChoice(
                        label = stringResource(R.string.theme_title),
                        selected = themeMode,
                        options = themeModes,
                        labels = themeModeLabels,
                        onSelect = { viewModel.setThemeMode(it) }
                    )
                }

                if (supportsDynamicColor) {
                    SectionItem(
                        position = ItemPosition.Bottom, 
                        onClick = {
                            val next = !dynamicTheme
                            HapticUtil.perform(
                                context, 
                                if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                            )
                            viewModel.setDynamicTheme(next)
                        }
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.dynamic_theme_title),
                            supportingText = stringResource(R.string.dynamic_theme_desc),
                            checked = dynamicTheme,
                            onCheckedChange = { viewModel.setDynamicTheme(it) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        val next = !privacyMode
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setPrivacyMode(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.privacy_mode_title),
                        supportingText = stringResource(R.string.privacy_mode_desc),
                        checked = privacyMode,
                        onCheckedChange = { viewModel.setPrivacyMode(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        val next = !profilesGestureEnabled
                        HapticUtil.perform(
                            context,
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setProfilesGestureEnabled(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.profiles_gesture_title),
                        supportingText = stringResource(R.string.profiles_gesture_desc),
                        checked = profilesGestureEnabled,
                        onCheckedChange = { viewModel.setProfilesGestureEnabled(it) }
                    )
                }
            }

            if (!appearanceOnly) {
            // 2.1 Сеть
            val waitForNetwork by viewModel.waitForNetwork.collectAsStateWithLifecycle()
            val restartOnNetworkChange by viewModel.restartOnNetworkChange.collectAsStateWithLifecycle()
            val pingUrl by viewModel.pingUrl.collectAsStateWithLifecycle()
            val countryDetectionMethod by viewModel.countryDetectionMethod.collectAsStateWithLifecycle()

            SectionGroup(title = stringResource(R.string.network_settings_title)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        val next = !waitForNetwork
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setWaitForNetwork(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.wait_for_network_title),
                        supportingText = stringResource(R.string.wait_for_network_desc),
                        checked = waitForNetwork,
                        onCheckedChange = { viewModel.setWaitForNetwork(it) }
                    )
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        val next = !restartOnNetworkChange
                        HapticUtil.perform(
                            context, 
                            if (next) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                        viewModel.setRestartOnNetworkChange(next)
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.restart_on_network_change_title),
                        supportingText = stringResource(R.string.restart_on_network_change_desc),
                        checked = restartOnNetworkChange,
                        onCheckedChange = { viewModel.setRestartOnNetworkChange(it) }
                    )
                }

                // Auto-launch remains implemented; its controls are not exposed.
            }

            // 2.2 Диагностика (пинг и определение страны выхода)
            var localPingUrl by rememberSaveable { mutableStateOf(pingUrl) }
            val isPingUrlValid = localPingUrl.isBlank() || runCatching {
                com.wireturn.app.domain.ConfigurationUrlPolicy.validate(java.net.URL(localPingUrl))
            }.isSuccess

            LaunchedEffect(pingUrl) {
                if (localPingUrl.isEmpty()) localPingUrl = pingUrl
            }

            LaunchedEffect(localPingUrl, isPingUrlValid) {
                if (isPingUrlValid && localPingUrl != pingUrl) {
                    delay(400.milliseconds)
                    if (localPingUrl != pingUrl) {
                        viewModel.setPingUrl(localPingUrl)
                    }
                }
            }

            val countryMethods = listOf("auto", "ipwhois", "ipsb", "ipapico", "ipinfo", "cloudflare")
            val countryMethodLabels = mapOf(
                "auto" to stringResource(R.string.country_detection_auto),
                "ipwhois" to stringResource(R.string.country_detection_ipwhois),
                "ipsb" to stringResource(R.string.country_detection_ipsb),
                "ipapico" to stringResource(R.string.country_detection_ipapico),
                "ipinfo" to stringResource(R.string.country_detection_ipinfo),
                "cloudflare" to stringResource(R.string.country_detection_cloudflare)
            )
            val showCountryMethodDialog = remember { mutableStateOf(false) }

            SectionGroup(title = stringResource(R.string.diagnostics_settings_title)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.settings_ping_url),
                        value = localPingUrl,
                        onValueChange = { localPingUrl = it },
                        isError = !isPingUrlValid,
                        placeholder = MainViewModel.DEFAULT_PING_URL,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5
                    )
                }
                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showCountryMethodDialog.value = true
                    }
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RowLabel(text = stringResource(R.string.settings_country_detection_title))
                        Spacer(Modifier.height(2.dp))
                        SupportingText(countryMethodLabels[countryDetectionMethod] ?: countryDetectionMethod)
                    }
                }
            }

            if (showCountryMethodDialog.value) {
                SelectionDialog(
                    title = stringResource(R.string.settings_country_detection_title),
                    items = countryMethods,
                    isSelected = { it == countryDetectionMethod },
                    onSelect = {
                        viewModel.setCountryDetectionMethod(it)
                        showCountryMethodDialog.value = false
                    },
                    onDismiss = { showCountryMethodDialog.value = false }
                ) { method, _ ->
                    Text(
                        text = countryMethodLabels[method] ?: method,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }


            // WebView appearance and updater remain implemented, but are not user settings.

            SectionGroup(title = stringResource(R.string.profile_probe_settings_title)) {
                val testPrefs = remember { context.getSharedPreferences("url_test", 0) }
                var parallelism by remember { mutableStateOf(testPrefs.getInt("parallelism", 4).toString()) }
                SectionItem(position = ItemPosition.Single) {
                    com.wireturn.app.ui.screens.kernel.VkChoice(stringResource(R.string.profile_probe_concurrency), parallelism,
                        listOf("1", "2", "4", "8").map { it to it }) {
                        parallelism = it
                        testPrefs.edit().putInt("parallelism", it.toInt()).apply()
                    }
                    Text(stringResource(R.string.profile_probe_concurrency_hint), style = MaterialTheme.typography.bodySmall)
                }
            }

            }
            // Язык
            val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            val languages = listOf("system", "en", "ru")
            val languageLabels = languages.associateWith { lang ->
                stringResource(when (lang) {
                    "en" -> R.string.lang_en
                    "ru" -> R.string.lang_ru
                    else -> R.string.lang_system
                })
            }

            var showLanguageDialog by remember { mutableStateOf(false) }
            SectionGroup(title = stringResource(R.string.localization_title)) {
                SectionItem(position = ItemPosition.Single, onClick = { showLanguageDialog = true }) {
                    RowLabel(text = stringResource(R.string.lang_title))
                    SupportingText(languageLabels[appLanguage] ?: languageLabels.getValue("system"))
                }
            }
            if (showLanguageDialog) SelectionDialog(
                title = stringResource(R.string.lang_title), items = languages,
                isSelected = { it == appLanguage },
                onSelect = { language ->
                    showLanguageDialog = false
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    viewModel.setAppLanguage(language)
                }, onDismiss = { showLanguageDialog = false }
            ) { language, _ ->
                Text(languageLabels.getValue(language), Modifier.padding(16.dp))
            }

            if (!appearanceOnly) {
            // 4. Сброс настроек
            Surface(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showResetDialog.value = true
                },
                shape = MaterialTheme.shapes.medium,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.reset_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            }
            // Версия
            Text(
                text = appVersion,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }

    if (showResetDialog.value) {
        AlertDialog(
            onDismissRequest = { showResetDialog.value = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog.value = false
                        viewModel.resetAllSettings(context)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { 
                    Text(stringResource(R.string.reset)) 
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog.value = false }) { 
                    Text(stringResource(R.string.cancel)) 
                }
            }
        )
    }

    if (showListenHelp.value) {
        AlertDialog(
            onDismissRequest = { showListenHelp.value = false },
            title = { Text(stringResource(R.string.local_listen_address)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.local_port_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.local_port_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showListenHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (showSocksHelp.value) {
        AlertDialog(
            onDismissRequest = { showSocksHelp.value = false },
            title = { Text(stringResource(R.string.socks5)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.client_socks_help_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.client_socks_help_secondary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSocksHelp.value = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (showBottomSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainer

        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet.value = false
            },
            sheetState = bottomSheetState,
            containerColor = sheetColor,
            dragHandle = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                ) {
                    androidx.compose.material3.BottomSheetDefaults.DragHandle()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.protocol_guide_components),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) { CoreVersionsContent() }
        }
    }
}

// --- Repo Links Sheet Content ---
@Composable
private fun RepoLinksContent(
    containerColor: Color
) {
    // --- State & Data ---
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val smartDismissNestedScroll = remember {
        object : NestedScrollConnection {
            private var hasScrolledDownInGesture = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (consumed.y > 0) {
                        hasScrolledDownInGesture = true
                    }
                    if (available.y > 0 && hasScrolledDownInGesture) {
                        return available
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity, 
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity {
                hasScrolledDownInGesture = false
                return super.onPostFling(consumed, available)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        LazyColumn(modifier = Modifier.nestedScroll(smartDismissNestedScroll)) {
            item {
                RepoLinkItem(title = "TUNA VPN", subtitle = "534188-create/TunaTURN", url = "https://github.com/534188-create/TunaTURN",
                    containerColor = containerColor, onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) }, onOpen = { uriHandler.openUri(it) })
            }
            item {
                RepoLinkItem(
                    title = stringResource(R.string.android_client),
                    subtitle = "Исходный Android-клиент · автор spkprsnts",
                    url = "https://github.com/spkprsnts/WireTurn",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.android_client_original),
                    subtitle = "samosvalishe/turn-proxy-android",
                    url = "https://github.com/samosvalishe/turn-proxy-android",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }





            item {
                RepoLinkItem(
                    title = stringResource(R.string.qwdtt_core),
                    subtitle = "SpaceNeuroX/proxy-turn-vk-android",
                    url = "https://github.com/SpaceNeuroX/proxy-turn-vk-android",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }






            item {
                RepoLinkItem(
                    title = stringResource(R.string.xray_core_name),
                    subtitle = "spkprsnts/vless-client",
                    url = "https://github.com/spkprsnts/vless-client",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.xray_core_original),
                    subtitle = "XTLS/Xray-core",
                    url = "https://github.com/XTLS/Xray-core",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item {
                RepoLinkItem(
                    title = stringResource(R.string.hev_socks5_tunnel),
                    subtitle = "heiher/hev-socks5-tunnel",
                    url = "https://github.com/heiher/hev-socks5-tunnel",
                    containerColor = containerColor,
                    onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                    onOpen = { uriHandler.openUri(it) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
internal fun CoreVersionsContent() {
    val context = LocalContext.current
    var sources by remember { mutableStateOf(false) }
    val components by androidx.compose.runtime.produceState<List<Pair<String, String>>>(emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.assets.open("native/components.json").bufferedReader().use { reader ->
                com.google.gson.JsonParser.parseReader(reader).asJsonArray.map {
                    val entry = it.asJsonObject
                    entry["name"].asString to (entry["version"].asString +
                        if (entry["tunaFork"].asBoolean) " · TUNA fork" else "")
                }
            } }.getOrDefault(emptyList())
        }
    }
    if (sources) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { sources = false }) { Text("Назад к версиям") }
            RepoLinksContent(MaterialTheme.colorScheme.surfaceContainer)
        }
    } else LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionItem(position = ItemPosition.Single, onClick = {
                context.startActivity(android.content.Intent(context, com.wireturn.app.ui.activities.ProtocolGuideActivity::class.java))
            }) {
                Text(stringResource(R.string.protocol_guide_title), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.protocol_guide_summary))
            }
        }
        items(components.size, key = { components[it].first }) { index ->
            val (name, version) = components[index]
            SectionItem(position = ItemPosition.Single) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { TextButton(onClick = { sources = true }) { Text("Лицензии и исходный код") } }
    }
}

// --- Repo Link Item Component ---
@Composable
private fun RepoLinkItem(
    title: String,
    subtitle: String,
    url: String,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onHaptic: () -> Unit,
    onOpen: (String) -> Unit
) {
    Surface(
        color = containerColor,
        onClick = {
            onHaptic()
            onOpen(url)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(R.drawable.open_in_new_24px),
                contentDescription = stringResource(R.string.btn_open),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun <T> SettingsChoice(label: String, selected: T, options: List<T>, labels: Map<T, String>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            RowLabel(label)
            SupportingText(labels[selected].orEmpty())
        }
    }
    if (expanded) SelectionDialog(title = label, items = options,
        isSelected = { it == selected }, onSelect = { expanded = false; onSelect(it) },
        onDismiss = { expanded = false }) { option, _ ->
        Text(labels[option].orEmpty(), Modifier.padding(16.dp))
    }
}
