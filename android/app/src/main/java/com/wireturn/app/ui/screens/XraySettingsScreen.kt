@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.LaunchedEffect
import com.wireturn.app.data.RoutingPolicy
import com.wireturn.app.domain.GeoCategoryIndex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.GeoResourceSet
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ExpandableSection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.SwitchRow
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact
import com.wireturn.app.viewmodel.GeoAssetsState
import java.text.DateFormat
import java.util.Date

@Composable
fun XraySettingsScreen(
    initialXraySettings: XraySettings,
    privacyMode: Boolean,
    subscriptions: List<com.wireturn.app.data.Subscription> = emptyList(),
    activeSubscription: com.wireturn.app.data.Subscription? = null,
    routingEnabled: Boolean = true,
    onEnableRouting: () -> Unit = {},
    onDisableRouting: (com.wireturn.app.data.RoutingDisableConsent) -> Unit = {},
    profileName: String? = null,
    onBack: () -> Unit,
    onSave: (XraySettings) -> Unit,
    modifier: Modifier = Modifier,
    geoAssetsState: GeoAssetsState = GeoAssetsState.Idle,
    geoAssetsProgress: Int = 0,
    geoAssetsInstalled: Boolean = false,
    geoAssetsInstalledAt: Long = 0L,
    onDownloadGeoAssets: (GeoResourceSet) -> Unit = {},
    onCancelGeoAssetsDownload: () -> Unit = {},
    onDeleteGeoAssets: () -> Unit = {}
) {
    val context = LocalContext.current

    var xraySocks by remember { mutableStateOf(initialXraySettings.socksBindAddress) }
    var xrayHttp by remember { mutableStateOf(initialXraySettings.httpBindAddress) }
    var xrayAuth by remember { mutableStateOf(initialXraySettings.isProxyAuthEnabled) }
    var confirmNoAuth by remember { mutableStateOf(false) }
    if (confirmNoAuth) LocalProxyAuthWarning(
        onConfirm = { xrayAuth = false; confirmNoAuth = false },
        onDismiss = { confirmNoAuth = false })
    var xrayUser by remember { mutableStateOf(initialXraySettings.proxyUser) }
    var xrayPass by remember { mutableStateOf(initialXraySettings.proxyPass) }

    val networkRouter = com.wireturn.app.data.NetworkRouter.XRAY
    val usingSubscription = activeSubscription?.let(com.wireturn.app.domain.ProfileRouting::enabled) == true
    val subscriptionPolicy = remember(activeSubscription) { runCatching {
        activeSubscription?.routingSource?.takeIf(String::isNotBlank)?.let(com.wireturn.app.domain.SubscriptionPolicyParser::parseHapp)
    }.getOrNull() }
    var bootstrapDns by remember { mutableStateOf(initialXraySettings.bootstrapDns) }
    var customBootstrap by remember { mutableStateOf(false) }
    val bootstrapError = runCatching { com.wireturn.app.data.BootstrapDns.parse(bootstrapDns) }.exceptionOrNull()?.message.orEmpty()
    var dns by remember { mutableStateOf(initialXraySettings.dns) }
    var happSource by remember { mutableStateOf(initialXraySettings.happRoutingSource) }
    var splitEnabled by remember { mutableStateOf(initialXraySettings.splitDnsJson != "disabled" && (initialXraySettings.splitDnsJson.isNotBlank() || initialXraySettings.happRoutingSource.isNotBlank())) }
    var splitDns by remember { mutableStateOf(runCatching {
        if (initialXraySettings.splitDnsJson.isNotBlank()) com.wireturn.app.domain.SplitDnsSettings.parse(initialXraySettings.splitDnsJson).let { saved ->
            if (saved.hosts == null && initialXraySettings.happRoutingSource.isNotBlank()) saved.copy(hosts = com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(initialXraySettings.happRoutingSource).hosts) else saved
        }
        else com.wireturn.app.domain.SplitDnsSettings.fromPolicy(com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(initialXraySettings.happRoutingSource))
    }.getOrDefault(com.wireturn.app.domain.SplitDnsSettings())) }
    val dnsError = if (splitEnabled) runCatching { splitDns.validateForRouter(networkRouter) }.exceptionOrNull()?.message.orEmpty() else ""
    var customDns by remember { mutableStateOf(com.wireturn.app.data.DnsPresets.matching(initialXraySettings.dns) == null) }
    var policy by remember { mutableStateOf(RoutingPolicy.load(initialXraySettings.routingPolicyJson, initialXraySettings.routeDirect, initialXraySettings.routeBlock)) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var categoryError by remember { mutableStateOf("") }
    LaunchedEffect(geoAssetsInstalled, geoAssetsInstalledAt) {
        val result = withContext(Dispatchers.IO) { runCatching {
            val dir = com.wireturn.app.domain.GeoAssetsManager.assetsDir(context)
            GeoCategoryIndex.read(java.io.File(dir, "geosite.dat"), "geosite:") + GeoCategoryIndex.read(java.io.File(dir, "geoip.dat"), "geoip:")
        } }
        categories = result.getOrDefault(emptyList())
        categoryError = result.exceptionOrNull()?.message.orEmpty()
    }
    val geoRules = policy.activeRules().map { it.match }.filter { it.startsWith("geosite:") || it.startsWith("geoip:") }
    var bypassLan by remember { mutableStateOf(initialXraySettings.bypassLan) }
    val lanError = runCatching {
        val candidate = if (happSource.isBlank()) policy else com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(happSource).routing
        com.wireturn.app.domain.RoutingOverrides.withLan(candidate, bypassLan)
    }.exceptionOrNull()?.message.orEmpty()
    val routingError = when {
        networkRouter == com.wireturn.app.data.NetworkRouter.SING_BOX_EXTENDED && !splitEnabled -> "Для extended включите отдельные DNS через VPN и напрямую"
        networkRouter == com.wireturn.app.data.NetworkRouter.SING_BOX_EXTENDED && (!xrayAuth || xrayHttp.isNotBlank()) -> "Extended требует авторизацию SOCKS и пустое поле HTTP-входа"
        bootstrapError.isNotBlank() -> bootstrapError
        lanError.isNotBlank() -> lanError
        dnsError.isNotBlank() -> dnsError
        happSource.isNotBlank() -> ""
        geoRules.isNotEmpty() && categoryError.isNotBlank() -> categoryError
        geoRules.isNotEmpty() && !geoAssetsInstalled -> "Сначала скачайте базы категорий ниже."
        geoRules.isNotEmpty() && policy.geoSource.isNotBlank() && policy.geoSource != initialXraySettings.geoVariant -> "Выберите и скачайте базу ${policy.geoSource} ниже."
        categories.isNotEmpty() && geoRules.any { GeoCategoryIndex.categoryOf(it) !in categories } -> "В этой базе нет категорий: " + geoRules.filter { GeoCategoryIndex.categoryOf(it) !in categories }.joinToString()
        else -> ""
    }
    var fakeDns by remember { mutableStateOf(initialXraySettings.fakeDns) }

    val showGeoDialog = remember { mutableStateOf(false) }
    val showDeleteGeoConfirm = remember { mutableStateOf(false) }

    val installedVariant = GeoResourceSet.fromId(initialXraySettings.geoVariant)
    // While a download is in flight, reflect its target rather than the (possibly stale, or
    // just-deleted) previously installed variant - otherwise the label lies about what's
    // actually happening (e.g. still saying "v2fly" while RunetFreedom is being fetched).
    val displayedVariant = (geoAssetsState as? GeoAssetsState.Downloading)?.variant ?: installedVariant
    val installedVariantLabel = stringResource(geoVariantLabelRes(displayedVariant))
    val geoDateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val geoStatusText = when {
        geoAssetsState is GeoAssetsState.Downloading -> stringResource(R.string.geo_resources_downloading)
        geoAssetsInstalled -> stringResource(R.string.geo_resources_updated_at, geoDateFormat.format(Date(geoAssetsInstalledAt)))
        else -> stringResource(R.string.geo_resources_not_downloaded)
    }

    val scrollState = rememberScrollState()

    val currentXraySettings = remember(
        xraySocks, xrayHttp, xrayAuth, xrayUser, xrayPass,
        dns, policy, fakeDns, initialXraySettings, happSource, splitEnabled, splitDns, bypassLan, bootstrapDns, networkRouter
    ) {
        initialXraySettings.copy(
            socksBindAddress = xraySocks,
            httpBindAddress = xrayHttp,
            isProxyAuthEnabled = xrayAuth,
            proxyUser = xrayUser,
            proxyPass = xrayPass,
            dns = dns,
            bootstrapDns = bootstrapDns.trim(),
            networkRouter = networkRouter,
            bypassLan = bypassLan,
            routingPolicyJson = policy.toJson(),
            happRoutingSource = happSource,
            splitDnsJson = if (splitEnabled) splitDns.toJson() else if (happSource.isNotBlank()) "disabled" else "",
            fakeDns = fakeDns && !splitEnabled
        )
    }

    val isModified = remember(currentXraySettings, initialXraySettings) {
        currentXraySettings.socksBindAddress != initialXraySettings.socksBindAddress ||
        currentXraySettings.httpBindAddress != initialXraySettings.httpBindAddress ||
        currentXraySettings.isProxyAuthEnabled != initialXraySettings.isProxyAuthEnabled ||
        currentXraySettings.proxyUser != initialXraySettings.proxyUser ||
        currentXraySettings.proxyPass != initialXraySettings.proxyPass ||
        currentXraySettings.networkRouter != initialXraySettings.networkRouter ||
        currentXraySettings.bootstrapDns != initialXraySettings.bootstrapDns ||
        currentXraySettings.dns != initialXraySettings.dns ||
        currentXraySettings.happRoutingSource != initialXraySettings.happRoutingSource ||
        currentXraySettings.splitDnsJson != initialXraySettings.splitDnsJson ||
        currentXraySettings.routingPolicyJson != RoutingPolicy.load(initialXraySettings.routingPolicyJson, initialXraySettings.routeDirect, initialXraySettings.routeBlock).toJson() ||
        currentXraySettings.fakeDns != initialXraySettings.fakeDns || currentXraySettings.bypassLan != initialXraySettings.bypassLan
    }

    var showRoutingError by remember { mutableStateOf(false) }
    if (showRoutingError) AlertDialog(onDismissRequest = { showRoutingError = false }, title = { Text("Проверьте маршрутизацию и DNS") }, text = { Text(routingError) }, confirmButton = { TextButton(onClick = { showRoutingError = false }) { Text("Понятно") } })
    val showExitDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isModified) {
            showExitDialog.value = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isModified, onBack = handleBack)

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        flingAnimationSpec = null
    )

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    if (routingError.isBlank()) {
                        onSave(currentXraySettings)
                    } else showRoutingError = true
                }) {
                    Text(if (routingError.isBlank()) stringResource(R.string.btn_save) else "Проверьте базы категорий")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog.value = false
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_discard))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.noFlingExpandConnection()),
        topBar = {
            AppTopAppBar(
                title = "Маршрутизация",
                scrollBehavior = scrollBehavior,
                onBack = handleBack
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isModified,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        if (routingError.isBlank()) {
                        onSave(currentXraySettings)
                    } else showRoutingError = true
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(painterResource(R.drawable.save_24px), null) },
                    text = { Text(if (routingError.isBlank()) stringResource(R.string.btn_save) else "Проверьте базы категорий") }
                )
            }
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
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            if (profileName != null) Text("Профиль: $profileName", style = MaterialTheme.typography.bodyMedium)
            RoutingControlSection(routingEnabled, onEnableRouting, onDisableRouting)
            SubscriptionRouteChoice(activeSubscription, !privacyMode)
            AppliedRoutingSummary(privacyMode)
            Text("Движок правил и DNS: Xray", color = MaterialTheme.colorScheme.primary)
            if (usingSubscription) {
                Text("Правила подписки: ${activeSubscription?.name}", style = MaterialTheme.typography.titleMedium)
                if (subscriptionPolicy == null) Text("Правила отсутствуют или повреждены. Подключение с ними недоступно.", color = MaterialTheme.colorScheme.error)
                subscriptionPolicy?.let { imported ->
                    activeSubscription?.let { SubscriptionRulesEditor(it, imported, !privacyMode) }
                    Text("Правила обновляются из подписки. Общие правила приложения сохранены отдельно.")
                    SectionGroup(title = "DNS подписки") {
                        SectionItem(position = ItemPosition.Single) {
                            Column {
                                val remoteDns = imported.dns?.effectiveRemoteServers ?: listOf(imported.remote)
                                val directDns = imported.dns?.effectiveDomesticServers ?: listOf(imported.domestic)
                                val remoteMode = if (imported.dns?.remoteSelectionMode == "priority") "приоритет" else "авто"
                                val directMode = if (imported.dns?.domesticSelectionMode == "priority") "приоритет" else "авто"
                                Text("Через VPN: ${remoteDns.size} · $remoteMode")
                                remoteDns.forEachIndexed { index, resolver ->
                                    Text("${index + 1}. ${resolver.transport} · ${if (privacyMode) "Скрыто" else resolver.endpoint}")
                                }
                                Text("Напрямую: ${directDns.size} · $directMode")
                                directDns.forEachIndexed { index, resolver ->
                                    Text("${index + 1}. ${resolver.transport} · ${if (privacyMode) "Скрыто" else resolver.endpoint}")
                                }
                                Text("Общий тайм-аут запроса: ${imported.dns?.timeoutMs ?: 5000} мс")
                                Text("DoH без явного IP использует общий Bootstrap DNS только для имени DNS-сервера.")
                                Text("Источник: ${activeSubscription?.name}")
                            }
                        }
                    }
                }
            } else if (happSource.isBlank()) {
                RoutingEditor(policy, categories, if (geoAssetsInstalled) initialXraySettings.geoVariant else "", !privacyMode) { policy = it }
            } else {
                Text("Сохранённый общий набор правил")
                runCatching { com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(happSource) }.getOrNull()?.routing?.rules?.forEach { rule ->
                    Text("${rule.action.title}: ${rule.match}")
                }
            }
            Text(if (isModified) "Есть несохранённые изменения общих настроек" else "Настройки сохранены. Применение к соединению проверяется ниже.", style = MaterialTheme.typography.bodySmall)
            if (happSource.isBlank()) SectionGroup(title = "Определение домена") {
                SectionItem(position = ItemPosition.Single) {
                    SwitchRow(label = "Распознавать HTTP / TLS / QUIC", checked = policy.sniffing,
                        supportingText = "Использовать имя из открытых метаданных для правил. Зашифрованное содержимое не расшифровывается. При ECH имя может быть недоступно.",
                        onCheckedChange = { policy = policy.copy(sniffing = it) }, enabled = !privacyMode)
                }
            }
            SwitchRow(label = "Локальная сеть напрямую", checked = bypassLan,
                supportingText = "Частные IPv4/IPv6-подсети обходят VPN, если не совпало пользовательское правило. Применяется и к правилам подписки.",
                onCheckedChange = { bypassLan = it }, enabled = !privacyMode)
            RoutingSettingsGroup("DNS приложения", if (usingSubscription) "Сохранены; сейчас используются DNS подписки" else if (splitEnabled) "Через VPN и напрямую" else "Один общий DNS", initiallyExpanded = false) {
            SectionGroup(title = "Bootstrap DNS · адреса серверов") {
                SectionItem(position = ItemPosition.Single) {
                    Column {
                    val bootstrapPresets = listOf("" to "По умолчанию ядра", "tcp://77.88.8.8:53" to "Яндекс", "tcp://1.1.1.1:53" to "Cloudflare", "tcp://8.8.8.8:53" to "Google")
                    com.wireturn.app.ui.screens.kernel.VkChoice("Bootstrap DNS", if (customBootstrap) "custom" else bootstrapDns.takeIf { value -> bootstrapPresets.any { it.first == value } } ?: "custom",
                        bootstrapPresets + ("custom" to "Ввести вручную"), enabled = !privacyMode) { customBootstrap = it == "custom"; if (!customBootstrap) bootstrapDns = it }
                    TextFieldRow(label = "DNS для соединения с сервером", value = bootstrapDns,
                        onValueChange = { if (!privacyMode) { bootstrapDns = it; customBootstrap = true } },
                        placeholder = "tcp://1.1.1.1:53",
                        supportingText = "Для профилей Xray и sing-box. IP и TCP; этот запрос идёт напрямую до установления VPN. Пусто — прежние DNS каждого ядра. Mieru и DNS-туннель используют свои настройки.",
                        readOnly = privacyMode, isModified = bootstrapDns != initialXraySettings.bootstrapDns,
                        privacyMode = privacyMode)
                    }
                }
            }
            SplitDnsSection(splitEnabled, splitDns, !privacyMode,
                onEnabled = { splitEnabled = it; if (it) fakeDns = false },
                onChange = { splitDns = it }, extended = networkRouter == com.wireturn.app.data.NetworkRouter.SING_BOX_EXTENDED)
            if (!splitEnabled) SectionGroup(title = "Общий DNS · разделение выключено") {
                SectionItem(position = ItemPosition.Top) {
                    Column {
                        val preset = if (customDns) null else com.wireturn.app.data.DnsPresets.matching(dns)
                        com.wireturn.app.ui.screens.kernel.VkChoice(
                            "DNS для маршрутизации", preset?.id ?: "custom",
                            com.wireturn.app.data.DnsPresets.all.map { it.id to it.name } + ("custom" to "Свои DNS"),
                            enabled = !privacyMode
                        ) { id ->
                            customDns = id == "custom"
                            com.wireturn.app.data.DnsPresets.all.find { it.id == id }?.let { dns = it.servers }
                        }
                        preset?.let { Text(it.description) }
                        DnsCheckButton(com.wireturn.app.data.DnsPresets.normalize(dns).split(',').filter { it.isNotBlank() }.map { com.wireturn.app.data.DnsPresets.resolver(it) }, !privacyMode)
                        Text("Можно изменить адреса ниже. Настройка применяется к DNS маршрутизации при включённом Xray; после сохранения переподключите VPN.")
                    }
                }
                SectionItem(position = ItemPosition.Middle) {
                    TextFieldRow(
                        label = "Свои DNS / адреса выбранного сервиса",
                        value = dns.redact(privacyMode),
                        onValueChange = { if (!privacyMode) { dns = it; customDns = true } },
                        placeholder = XraySettings.DEFAULT_DNS,
                        supportingText = "IP-адреса или ссылки https://…/dns-query. Несколько серверов — через запятую или с новой строки. Пресет можно отредактировать.",
                        readOnly = privacyMode,
                        isModified = dns != initialXraySettings.dns,
                        privacyMode = privacyMode,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5
                    )
                }

                SectionItem(
                    position = ItemPosition.Bottom,
                    onClick = {
                        fakeDns = !fakeDns
                        HapticUtil.perform(
                            context,
                            if (fakeDns) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_settings_fakedns_label),
                        supportingText = stringResource(R.string.xray_settings_fakedns_desc),
                        checked = fakeDns,
                        onCheckedChange = { fakeDns = it },
                        isModified = fakeDns != initialXraySettings.fakeDns
                    )
                }
            }

            if (dnsError.isNotBlank()) Text(dnsError, color = MaterialTheme.colorScheme.error)
            }
            RoutingSettingsGroup("Параметры подписки", "Просмотр и применение дополнительных настроек") {
                SubscriptionSettingsSection(subscriptions)
            }

            val inspectedSource = if (usingSubscription) activeSubscription?.routingSource.orEmpty() else happSource
            val inspectedSettings = if (usingSubscription) currentXraySettings.copy(happRoutingSource = inspectedSource, splitDnsJson = "", fakeDns = false) else currentXraySettings
            RoutingSettingsGroup("Проверка правил", "Предварительная проверка сохранённых и редактируемых настроек") {
                RouteExplanationSection(inspectedSettings, !privacyMode)
                RouteInspectorSection(policy, inspectedSource, splitDns.takeIf { splitEnabled && !usingSubscription }, bypassLan, routingEnabled)
            }
            RoutingUpdateSettings(!privacyMode)
            if (routingError.isNotBlank()) Text(routingError, color = MaterialTheme.colorScheme.error)
            RoutingSettingsGroup("Дополнительно: локальный прокси", "SOCKS5, HTTP, авторизация") {
            SectionGroup(title = stringResource(R.string.xray_settings_group_connection)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.socks5),
                        value = xraySocks.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xraySocks = it },
                        placeholder = XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
                        isError = !ValidatorUtils.isValidHostPort(xraySocks),
                        readOnly = privacyMode,
                        isModified = xraySocks != initialXraySettings.socksBindAddress,
                        privacyMode = privacyMode
                    )
                }

                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.xray_http),
                        value = xrayHttp.redact(privacyMode),
                        onValueChange = { if (!privacyMode) xrayHttp = it },
                        placeholder = XraySettings.DEFAULT_HTTP_BIND_ADDRESS,
                        isError = xrayHttp.isNotEmpty() && !ValidatorUtils.isValidHostPort(xrayHttp),
                        readOnly = privacyMode,
                        isModified = xrayHttp != initialXraySettings.httpBindAddress,
                        privacyMode = privacyMode
                    )
                }

                SectionItem(
                    position = if (xrayAuth) ItemPosition.Middle else ItemPosition.Bottom,
                    onClick = {
                        if (xrayAuth) confirmNoAuth = true else xrayAuth = true
                        HapticUtil.perform(
                            context,
                            if (xrayAuth) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                        )
                    }
                ) {
                    SwitchRow(
                        label = stringResource(R.string.xray_proxy_auth),
                        supportingText = stringResource(R.string.xray_proxy_auth_desc),
                        checked = xrayAuth,
                        onCheckedChange = { if (it) xrayAuth = true else confirmNoAuth = true },
                        isModified = xrayAuth != initialXraySettings.isProxyAuthEnabled,
                    )
                }

                ExpandableSection(visible = xrayAuth) {
                    SectionGroup {
                        SectionItem {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_user),
                                value = xrayUser.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayUser = it },
                                placeholder = stringResource(R.string.proxy_user_placeholder),
                                isError = !ValidatorUtils.isValidProxyUser(xrayUser),
                                readOnly = privacyMode,
                                isModified = xrayUser != initialXraySettings.proxyUser,
                                privacyMode = privacyMode
                            )
                        }
                        SectionItem(position = ItemPosition.Bottom) {
                            TextFieldRow(
                                label = stringResource(R.string.xray_proxy_pass),
                                value = xrayPass.redact(privacyMode),
                                onValueChange = { if (!privacyMode) xrayPass = it },
                                placeholder = stringResource(R.string.proxy_pass_placeholder),
                                isError = !ValidatorUtils.isValidProxyPass(xrayPass),
                                readOnly = privacyMode,
                                isModified = xrayPass != initialXraySettings.proxyPass,
                                privacyMode = privacyMode,
                                isSecret = true
                            )
                        }
                    }
                }
            }

            }
            SectionGroup(title = stringResource(R.string.xray_settings_group_geo)) {
                SectionItem(
                    position = ItemPosition.Single,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showGeoDialog.value = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LargeLeadingIcon {
                            if (geoAssetsState is GeoAssetsState.Downloading) {
                                LoadingIndicator(modifier = Modifier.size(32.dp))
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.globe_book_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(text = installedVariantLabel)
                            Spacer(Modifier.height(2.dp))
                            SupportingText(geoStatusText)
                        }
                    }
                }
            }
        }
    }

    if (showGeoDialog.value) {
        GeoResourcesDialog(
            selected = installedVariant,
            state = geoAssetsState,
            progress = geoAssetsProgress,
            installed = geoAssetsInstalled,
            installedAt = geoAssetsInstalledAt,
            onDownload = onDownloadGeoAssets,
            onCancelDownload = onCancelGeoAssetsDownload,
            onRequestDelete = { showDeleteGeoConfirm.value = true },
            onDismiss = { showGeoDialog.value = false }
        )
    }

    if (showDeleteGeoConfirm.value) {
        AlertDialog(
            onDismissRequest = { showDeleteGeoConfirm.value = false },
            title = { Text(stringResource(R.string.geo_resources_delete_confirm_title)) },
            text = { Text(stringResource(R.string.geo_resources_delete_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onDeleteGeoAssets()
                        showDeleteGeoConfirm.value = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.geo_resources_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGeoConfirm.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun GeoResourcesDialog(
    selected: GeoResourceSet,
    state: GeoAssetsState,
    progress: Int,
    installed: Boolean,
    installedAt: Long,
    onDownload: (GeoResourceSet) -> Unit,
    onCancelDownload: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf((state as? GeoAssetsState.Downloading)?.variant ?: selected) }
    val isDownloading = state is GeoAssetsState.Downloading
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    SelectionDialog(
        title = stringResource(R.string.xray_settings_group_geo),
        description = stringResource(R.string.geo_resources_dialog_desc),
        items = GeoResourceSet.entries,
        isSelected = { it == pending },
        onSelect = { if (!isDownloading) pending = it },
        onDismiss = onDismiss,
        dismissOnSelect = false,
        footer = {
            Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                val statusText = when {
                    isDownloading -> stringResource(R.string.geo_resources_downloading)
                    state is GeoAssetsState.Error -> stringResource(R.string.geo_resources_download_failed, state.message)
                    installed && pending == selected -> stringResource(
                        R.string.geo_resources_updated_at,
                        dateFormat.format(Date(installedAt))
                    )
                    else -> stringResource(R.string.geo_resources_not_downloaded)
                }
                SupportingText(
                    text = statusText,
                    color = if (state is GeoAssetsState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDownloading) {
                            CircularWavyProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onCancelDownload()
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.stop_24px),
                                    contentDescription = stringResource(R.string.cancel),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            FilledTonalIconButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    onDownload(pending)
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_downward_24px),
                                    contentDescription = stringResource(R.string.geo_resources_download)
                                )
                            }
                        }
                    }

                    if (installed && !isDownloading) {
                        IconButton(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onRequestDelete()
                            },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.delete_24px),
                                contentDescription = stringResource(R.string.geo_resources_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    ) { variant, _ ->
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(geoVariantLabelRes(variant)))
                SupportingText(
                    text = stringResource(geoVariantDescRes(variant)),
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
        }
    }
}

private fun geoVariantLabelRes(variant: GeoResourceSet): Int = when (variant) {
    GeoResourceSet.RUNETFREEDOM -> R.string.geo_variant_runetfreedom
    GeoResourceSet.LOYALSOLDIER -> R.string.geo_variant_loyalsoldier
    GeoResourceSet.V2FLY -> R.string.geo_variant_v2fly
    GeoResourceSet.ROSCOMVPN -> R.string.geo_variant_roscomvpn
}

private fun geoVariantDescRes(variant: GeoResourceSet): Int = when (variant) {
    GeoResourceSet.RUNETFREEDOM -> R.string.geo_variant_runetfreedom_desc
    GeoResourceSet.LOYALSOLDIER -> R.string.geo_variant_loyalsoldier_desc
    GeoResourceSet.V2FLY -> R.string.geo_variant_v2fly_desc
    GeoResourceSet.ROSCOMVPN -> R.string.geo_variant_roscomvpn_desc
}
