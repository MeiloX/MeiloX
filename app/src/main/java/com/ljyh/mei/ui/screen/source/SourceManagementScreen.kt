package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.source.script.PluginManifest
import com.ljyh.mei.source.update.SourceMarketItem
import com.ljyh.mei.source.update.SourceUpdateReviewReason
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.component.utils.rememberAlertContent
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.search.browsableProviders
import com.ljyh.mei.ui.screen.search.searchableProviders
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import java.util.Locale

@Composable
fun SourceManagementScreen(
    viewModel: SourceManagementViewModel = hiltViewModel(),
    pluginId: String? = null,
    market: Boolean = false,
) {
    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val startupError by viewModel.startupError.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val pendingUpdate by viewModel.pendingUpdate.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val compatibilityMarket by viewModel.compatibilityMarket.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val linkImportFailed by viewModel.linkImportFailed.collectAsState()
    val invalidImportDomains by viewModel.invalidImportDomains.collectAsState()
    val invalidCompatibilityDomains by viewModel.invalidCompatibilityDomains.collectAsState()
    val editing by viewModel.editing.collectAsState()
    val configuration by viewModel.configuration.collectAsState()
    val pendingMatch by viewModel.resolver.pendingMatch.collectAsState()
    val attempts by viewModel.resolver.lastAttempts.collectAsState()
    val displayedOrder by viewModel.fallbackOrder.collectAsState()
    val nav = LocalNavController.current
    val padding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspect(it, replacingId = pluginId) }
    }
    val ready = searchableProviders(providers)
    val browseReady = browsableProviders(providers)
    val providersByInstance = providers.associateBy { it.instanceId.value }
    val availablePlugin = plugins.firstOrNull { it.manifest.id == pluginId }
    val plugin = availablePlugin
    val configurationReady = editing?.manifest?.id == pluginId
    var removing by remember { mutableStateOf(false) }
    var showLinkImport by remember { mutableStateOf(false) }
    var importLink by remember { mutableStateOf(TextFieldValue()) }
    var editNetwork by remember(pluginId) { mutableStateOf(false) }
    var networkDraft by remember(pluginId) { mutableStateOf(TextFieldValue()) }
    val update = updateState.items.firstOrNull { it.entry.packageId == pluginId && it.updateAvailable }
    val working = busy || updateState.checking || updateState.busyPackageId != null
    val reviewHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.4f).coerceAtMost(360.dp)
    val openLinkImport = {
        importLink = TextFieldValue()
        viewModel.linkImportFailed.value = false
        showLinkImport = true
    }
    val marketTitle = stringResource(R.string.source_market_title)
    LaunchedEffect(pluginId, availablePlugin?.manifest?.id) {
        if (pluginId != null && availablePlugin != null) viewModel.edit(availablePlugin)
    }
    LaunchedEffect(market) { if (market) viewModel.refreshMarket() }
    LaunchedEffect(pending != null) { if (pending != null) showLinkImport = false }

    MeiloXPinnedListPage(
        title = if (market) marketTitle else plugin?.manifest?.name ?: "音源管理",
        onNavigateBack = nav::navigateUp,
        bottomPadding = padding.calculateBottomPadding(),
        actions = {
            if (pluginId == null) listOf(meiloxHeaderAction(
                key = if (market) "source-market-refresh" else "source-check-updates",
                contentDescription = if (market) stringResource(R.string.source_market_refresh) else "检查更新",
                onClick = if (market) viewModel::refreshMarket else viewModel::checkUpdates,
                enabled = !working,
            ) {
                if (updateState.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else SfIcon("arrow.clockwise", null)
            }) else emptyList()
        },
    ) {
        if (!market && pluginId == null && startupError != null) item("source-startup-error") {
            GlasmioGroupedList { GlasmioListRow(startupError!!, onClick = viewModel::reload) }
        }
        if (market) {
            val (official, thirdParty) = updateState.items.partition { it.entry.isOfficial }
            if (official.isEmpty() && thirdParty.isEmpty() && compatibilityMarket.isEmpty()) item("source-market-empty") {
                if (working) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } else GlasmioGroupedList {
                    GlasmioListRow(stringResource(R.string.source_market_empty), showDisclosure = false)
                }
            }
            if (official.isNotEmpty()) item("source-market-official") {
                SourceMarketGroup(stringResource(R.string.source_market_official)) {
                    official.forEach { item ->
                        PackagedSourceMarketRow(item, updateState.busyPackageId, working, viewModel)
                    }
                }
            }
            if (thirdParty.isNotEmpty() || compatibilityMarket.isNotEmpty()) item("source-market-third-party") {
                SourceMarketGroup(stringResource(R.string.source_market_third_party)) {
                    thirdParty.forEach { item ->
                        PackagedSourceMarketRow(item, updateState.busyPackageId, working, viewModel)
                    }
                    compatibilityMarket.forEach { item ->
                        val entry = item.entry
                        val installedId = item.installedPackageId
                        SourceMarketRow(
                            title = entry.name,
                            subtitle = listOfNotNull(compatibilityBrand(entry.format), entry.version).joinToString(" · "),
                            installed = installedId != null,
                            updateAvailable = item.updateAvailable,
                            enabled = !working,
                            onClick = {
                                if (item.updateAvailable || installedId == null) viewModel.prepareCompatibilityMarketInstall(entry.id)
                                else Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installedId)) }
                            },
                        )
                    }
                }
            }
        } else if (pluginId != null) {
            if (plugin != null && configurationReady) {
                item {
                    val isCompatible = plugin.manifest.compatibility != null
                    val isMusicFree = plugin.manifest.compatibility == "musicfree"
                    val configurationFields: @Composable () -> Unit = {
                        plugin.manifest.config.forEach { field ->
                            GlasmioTextField(
                                value = configuration[field.key].orEmpty(),
                                onValueChange = { viewModel.configuration.value = configuration + (field.key to it) },
                                modifier = Modifier.semantics { contentDescription = field.label },
                                placeholder = field.label,
                                enabled = !working,
                                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                                    autoCorrectEnabled = false,
                                ),
                            )
                        }
                        GlasmioListRow(
                            title = stringResource(R.string.source_plugin_save_configuration),
                            enabled = !working,
                            showDisclosure = !isCompatible,
                            onClick = { viewModel.saveConfiguration { if (!isCompatible) nav.navigateUp() } },
                        )
                    }
                    val packageActions: @Composable () -> Unit = {
                        GlasmioListRow(title = "从文件更新", enabled = !working, onClick = { importer.launch(arrayOf("*/*")) })
                        GlasmioListRow(title = stringResource(R.string.source_update_from_link), enabled = !working, onClick = openLinkImport)
                        GlasmioListRow(title = "移除音源", enabled = !working, onClick = { removing = true })
                    }
                    Column {
                        GlasmioGroupedList {
                            GlasmioListRow(title = "版本", subtitle = plugin.manifest.version, showDisclosure = false)
                            update?.let { available ->
                                GlasmioListRow(title = "更新至 ${available.entry.version}", enabled = !working,
                                    onClick = { viewModel.prepareUpdate(available.entry.packageId) })
                            }
                            GlasmioListRow(title = "状态", subtitle = if (plugin.status == SourceStatus.FAILED) "插件执行失败，可停用后重新启用" else statusLabel(plugin.status), showDisclosure = false)
                            GlasmioListRow(
                                title = "支持功能",
                                subtitle = compatibilityLabel(plugin.manifest) ?: capabilityLabel(plugin.manifest.capabilities),
                                showDisclosure = false,
                            )
                            val isLx = plugin.manifest.compatibility == "lx"
                            val enableLabel = stringResource(if (isLx) R.string.source_enable_lx else R.string.source_plugin_enable)
                            GlasmioListRow(
                                title = enableLabel,
                                subtitle = if (isLx && !settings.autoFallbackEnabled) stringResource(R.string.source_enable_lx_detail) else null,
                                showDisclosure = false,
                                trailing = {
                                    MeiloXSwitch(plugin.enabled, { viewModel.enabled(plugin, it) },
                                        Modifier.semantics { contentDescription = enableLabel }, enabled = !working)
                                },
                            )
                            if (isMusicFree && plugin.status == SourceStatus.READY) {
                                val isDefault = settings.preferredSearchProvider == plugin.manifest.providerId
                                GlasmioListRow(
                                    title = stringResource(if (isDefault) R.string.source_search_default_current else R.string.source_search_set_default),
                                    systemName = "magnifyingglass",
                                    enabled = !working,
                                    showDisclosure = false,
                                    onClick = if (isDefault) null else ({ viewModel.useMusicFreeForSearch(plugin) }),
                                )
                            }
                            if (isCompatible) {
                                GlasmioListRow(
                                    title = stringResource(R.string.source_network_permissions),
                                    subtitle = plugin.manifest.permissions.network.joinToString().ifEmpty {
                                        stringResource(R.string.source_network_domains_none)
                                    },
                                    systemName = "network",
                                    enabled = !working,
                                    onClick = {
                                        networkDraft = TextFieldValue(plugin.manifest.permissions.network.joinToString())
                                        viewModel.invalidCompatibilityDomains.value = false
                                        editNetwork = true
                                    },
                                )
                            }
                            val loginActions = plugin.manifest.features["login"].orEmpty()
                            val wechat = "wechatWebLogin" in loginActions
                            val loginEnabled = !working && plugin.status == SourceStatus.READY
                            if ("appQrLogin" in loginActions) {
                                val failureMessage = stringResource(R.string.qq_music_login_qr_failed)
                                GlasmioListRow(title = stringResource(R.string.source_login_qr), systemName = "qrcode", enabled = loginEnabled,
                                    onClick = { viewModel.login(plugin.manifest.providerId, "appQrLogin", failureMessage) })
                            }
                            if ("webLogin" in loginActions) {
                                GlasmioListRow(title = if (wechat) stringResource(R.string.source_login_qq) else "网页登录",
                                    systemName = "globe", enabled = loginEnabled,
                                    onClick = { viewModel.login(plugin.manifest.providerId, "webLogin") })
                            }
                            if (wechat) {
                                GlasmioListRow(title = stringResource(R.string.source_login_wechat),
                                    systemName = "qrcode", enabled = loginEnabled,
                                    onClick = { viewModel.login(plugin.manifest.providerId, "wechatWebLogin") })
                            }
                            if (!isCompatible) {
                                configurationFields()
                                packageActions()
                            }
                        }
                        if (isCompatible && plugin.manifest.config.isNotEmpty()) {
                            GlasmioSectionTitle(stringResource(R.string.source_plugin_configuration), Modifier.semantics { heading() })
                            GlasmioGroupedList { configurationFields() }
                        } else if (isMusicFree) {
                            GlasmioGroupedTableFooter(stringResource(R.string.source_musicfree_no_configuration))
                        }
                        plugin.manifest.description?.takeIf(String::isNotBlank)?.let { GlasmioGroupedTableFooter(it) }
                        if (isCompatible) {
                            Spacer(Modifier.height(20.dp))
                            GlasmioGroupedList { packageActions() }
                        }
                    }
                }
            } else {
                item {
                    if (busy) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else GlasmioGroupedList {
                        GlasmioListRow(
                            title = if (plugin == null) "音源已移除或未安装" else "重新加载配置",
                            showDisclosure = false,
                            onClick = plugin?.let { installed -> { viewModel.edit(installed) } },
                        )
                    }
                }
            }
        } else {
            item { GlasmioGroupedList {
                GlasmioListRow(title = marketTitle, systemName = "square.grid.2x2", enabled = !working,
                    onClick = { Screen.SourceMarket.navigate(nav) })
                GlasmioListRow(title = "从文件导入", systemName = "square.and.arrow.down", enabled = !working, onClick = { importer.launch(arrayOf("*/*")) })
                GlasmioListRow(title = stringResource(R.string.source_import_from_link), systemName = "link", enabled = !working, onClick = openLinkImport)
                plugins.forEach { installed ->
                    GlasmioListRow(
                        title = installed.manifest.name,
                        subtitle = listOfNotNull(compatibilityBrand(installed.manifest.compatibility), installed.manifest.version,
                            statusLabel(installed.status)).joinToString(" · "),
                        onClick = { Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installed.manifest.id)) } },
                    )
                }
            } }
            item { GlasmioGroupedList {
                ProviderSetting("默认浏览源", settings.preferredBrowseProvider, browseReady) { id -> viewModel.update { it.copy(preferredBrowseProvider = id) } }
                ProviderSetting("默认搜索源", settings.preferredSearchProvider, ready) { id -> viewModel.update { it.copy(preferredSearchProvider = id) } }
            } }
            item { GlasmioGroupedList {
                GlasmioListRow(title = "启动时自动更新", showDisclosure = false, trailing = {
                    MeiloXSwitch(updateState.autoUpdates, viewModel::setAutoUpdates, enabled = !working)
                })
                GlasmioListRow(title = "自动切换播放音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.autoFallbackEnabled, { checked -> viewModel.update { it.copy(autoFallbackEnabled = checked) } })
                })
                GlasmioListRow(title = "优先原始音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.preferOriginalProvider, { checked -> viewModel.update { it.copy(preferOriginalProvider = checked) } })
                })
                GlasmioListRow(
                    title = stringResource(R.string.source_allow_alternate_versions),
                    subtitle = stringResource(R.string.source_allow_alternate_versions_detail),
                    showDisclosure = false,
                    trailing = {
                        MeiloXSwitch(settings.allowAlternateVersions, { checked ->
                            viewModel.update { it.copy(allowAlternateVersions = checked) }
                        })
                    },
                )
            } }
            displayedOrder?.takeIf { it.isNotEmpty() }?.let { order ->
                item { Text("切换顺序", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item {
                    GlasmioReorderableGroup(
                        items = order,
                        onReorder = viewModel::reorderFallbackOrder,
                        dragHandleContentDescription = stringResource(R.string.reorder_drag_handle),
                        moveUpLabel = stringResource(R.string.move_up),
                        moveDownLabel = stringResource(R.string.move_down),
                    ) { instanceId ->
                        Text(providersByInstance[instanceId]?.displayName ?: instanceId, modifier = Modifier.weight(1f))
                    }
                }
            }
            pendingMatch?.let { match ->
                item { Text("确认歌曲匹配 · ${match.originTitle.ifBlank { match.origin.trackId }}", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item { GlasmioGroupedList {
                    match.candidates.forEach { candidate ->
                        GlasmioListRow(
                            title = candidate.track.title,
                            subtitle = candidate.track.artists.joinToString { it.name } + " · " + candidate.track.album.title,
                            onClick = { viewModel.chooseMatch(match, candidate) },
                        )
                    }
                    GlasmioListRow(title = "暂不选择", onClick = viewModel.resolver::dismissPendingMatch)
                } }
            }
            if (attempts.isNotEmpty()) {
                item { Text("最近解析", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item { GlasmioGroupedList {
                    attempts.forEach { attempt ->
                        GlasmioListRow(
                            title = providers.find { it.instanceId == attempt.instanceId }?.displayName ?: attempt.instanceId.value,
                            subtitle = attempt.error?.let(::sourceErrorMessage) ?: "可播放",
                            showDisclosure = false,
                        )
                    }
                } }
            }
        }
    }
    val displayedReview = rememberAlertContent(pending?.let { it to pendingUpdate })
    val showingReview = rememberAlertContent(if (pending != null) true else if (showLinkImport) false else null) == true
    val reviewing = pending != null
    val manifest = displayedReview?.first?.manifest?.takeIf { showingReview }
    val compatibility = manifest?.compatibility != null
    val updating = manifest != null && plugins.any { it.manifest.id == manifest.id }
    var domains by remember(manifest) { mutableStateOf(TextFieldValue(manifest?.permissions?.network.orEmpty().joinToString())) }
    val url = importLink.text.trim()
    val validLink = url.length in 9..8192 && url.startsWith("https://", ignoreCase = true)
    fun dismissImport() {
        if (reviewing && busy) return
        showLinkImport = false
        viewModel.cancelImport()
    }
    fun inspectLink() {
        if (showLinkImport && !working && validLink) viewModel.inspectLink(url, replacingId = pluginId)
    }
    GlasmioAlertDialog(
        visible = reviewing || showLinkImport,
        onDismissRequest = ::dismissImport,
        title = manifest?.name ?: stringResource(if (pluginId == null) R.string.source_import_from_link else R.string.source_update_from_link),
        message = if (!showingReview && linkImportFailed) stringResource(R.string.source_import_link_failed) else null,
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), role = GlasmioAlertButtonRole.Cancel, enabled = !reviewing || !busy, onClick = ::dismissImport),
            GlasmioAlertButtonSpec(
                label = stringResource(when {
                    working && !showingReview -> R.string.source_import_checking
                    !showingReview -> R.string.source_import_review
                    updating -> R.string.source_import_update
                    else -> R.string.source_import_install
                }),
                enabled = !working && (reviewing || showLinkImport && validLink),
                onClick = {
                    if (reviewing) {
                        viewModel.install(if (compatibility) parseCompatibilityDomains(domains.text) else null) { installed ->
                            val configure = installed.status == SourceStatus.NEEDS_CONFIGURATION ||
                                !updating && installed.manifest.compatibility != null
                            if (configure && pluginId != installed.manifest.id) {
                                Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installed.manifest.id)) }
                            }
                        }
                    } else inspectLink()
                },
            ),
        ),
    ) {
        val focus = LocalFocusManager.current
        LaunchedEffect(showingReview, busy) { focus.clearFocus() }
        if (manifest == null) {
            val linkFieldLabel = stringResource(R.string.source_import_link_field)
            GlasmioAlertFieldGroup {
                GlasmioAlertTextField(
                    value = importLink,
                    onValueChange = {
                        if (!working && it.text.length <= 8192) {
                            importLink = it
                            viewModel.linkImportFailed.value = false
                        }
                    },
                    placeholder = "https://",
                    modifier = Modifier.semantics { contentDescription = linkFieldLabel },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { if (validLink && !working) { focus.clearFocus(); inspectLink() } }),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = reviewHeight).verticalScroll(rememberScrollState())) {
                Text(
                    pluginReviewText(manifest, displayedReview?.second?.reviewReasons.orEmpty(),
                        displayedReview?.second?.entry?.repository ?: displayedReview?.first?.marketRepository,
                        compatibilityLabel(manifest)),
                    style = IosTypography.body,
                    color = LocalGlassColors.current.content,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                if (compatibility) {
                    Spacer(Modifier.height(12.dp))
                    SourceDomainField(domains, working, invalidImportDomains) {
                        domains = it
                        viewModel.invalidImportDomains.value = false
                    }
                } else Spacer(Modifier.height(24.dp))
            }
        }
    }
    val dismissNetwork = { if (!busy) editNetwork = false }
    GlasmioAlertDialog(
        visible = editNetwork && plugin != null,
        onDismissRequest = dismissNetwork,
        title = stringResource(R.string.source_network_permissions),
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), role = GlasmioAlertButtonRole.Cancel, enabled = !busy, onClick = dismissNetwork),
            GlasmioAlertButtonSpec(
                stringResource(R.string.source_network_save),
                enabled = !working,
                onClick = {
                    plugin?.let { installed ->
                        viewModel.saveCompatibilityDomains(installed, parseCompatibilityDomains(networkDraft.text)) { editNetwork = false }
                    }
                },
            ),
        ),
    ) {
        SourceDomainField(networkDraft, working, invalidCompatibilityDomains) {
            networkDraft = it
            viewModel.invalidCompatibilityDomains.value = false
        }
    }
    GlasmioAlertDialog(
        visible = removing && plugin != null,
        onDismissRequest = { removing = false },
        title = "移除音源？",
        message = "已保存的歌曲和下载文件会保留。",
        buttons = listOf(
            GlasmioAlertButtonSpec("取消", role = GlasmioAlertButtonRole.Cancel, onClick = { removing = false }),
            GlasmioAlertButtonSpec("移除", onClick = {
                removing = false
                plugin?.let { installed -> viewModel.uninstall(installed) { nav.navigateUp() } }
            }),
        ),
    )
    GlasmioAlertDialog(
        visible = message != null,
        onDismissRequest = { viewModel.message.value = null },
        title = "音源管理", message = message.orEmpty(),
        buttons = listOf(GlasmioAlertButtonSpec("好", onClick = { viewModel.message.value = null })),
    )
}

@Composable
private fun SourceMarketGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        GlasmioSectionTitle(title, Modifier.semantics { heading() })
        GlasmioGroupedList(content = content)
    }
}

@Composable
private fun PackagedSourceMarketRow(
    item: SourceMarketItem,
    busyPackageId: String?,
    working: Boolean,
    viewModel: SourceManagementViewModel,
) {
    val nav = LocalNavController.current
    val installed = item.installedVersion != null
    val packageId = item.entry.packageId
    SourceMarketRow(
        title = item.entry.name,
        subtitle = item.entry.version,
        installed = installed,
        updateAvailable = item.updateAvailable,
        enabled = !working,
        processing = busyPackageId == packageId,
        onClick = {
            when {
                item.updateAvailable -> viewModel.prepareUpdate(packageId)
                !installed -> viewModel.prepareMarketInstall(packageId)
                else -> Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(packageId)) }
            }
        },
    )
}

@Composable
private fun SourceMarketRow(
    title: String,
    subtitle: String,
    installed: Boolean,
    updateAvailable: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    processing: Boolean = false,
) {
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        trailing = {
            GlasmioListRowText(stringResource(when {
                processing -> R.string.source_import_checking
                updateAvailable -> R.string.source_import_update
                installed -> R.string.source_market_installed
                else -> R.string.source_import_install
            }), secondary = true)
        },
        onClick = onClick,
    )
}

@Composable
private fun SourceDomainField(value: TextFieldValue, working: Boolean, invalid: Boolean, onChange: (TextFieldValue) -> Unit) {
    val label = stringResource(R.string.source_import_domains)
    Text(label, style = IosTypography.footnote, color = LocalGlassColors.current.secondaryContent,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    GlasmioAlertFieldGroup {
        GlasmioAlertTextField(
            value = value,
            onValueChange = { if (!working && it.text.length <= 8192) onChange(it) },
            placeholder = "api.example.com, audio.example.com",
            modifier = Modifier.semantics { contentDescription = label },
            singleLine = false,
            maxLines = 2,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
    }
    if (invalid) Text(
        stringResource(R.string.source_import_domains_invalid), style = IosTypography.footnote,
        color = LocalGlassColors.current.destructive, modifier = Modifier.padding(horizontal = 8.dp),
    )
}

private fun compatibilityBrand(format: String?): String? = when (format) {
    "lx" -> "LX"
    "musicfree" -> "MusicFree"
    else -> null
}

private fun parseCompatibilityDomains(text: String): List<String> = text.split(Regex("[\\s,，、;；]+"))
    .filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) }.distinct()

@Composable
private fun compatibilityLabel(manifest: PluginManifest): String? = when (manifest.compatibility) {
    "lx" -> stringResource(R.string.source_format_lx)
    "musicfree" -> stringResource(R.string.source_format_musicfree)
    else -> null
}

@Composable
private fun ProviderSetting(title: String, selected: String, providers: List<MusicProvider>, onSelected: (String) -> Unit) {
    GlasmioListRow(title = title, showDisclosure = false, trailing = {
        GlasmioPopUpButton(selected, providers.map { it.id }, onSelected, label = { id -> providers.find { it.id == id }?.displayName ?: "来源不可用" })
    })
}
private fun pluginReviewText(manifest: PluginManifest, reasons: List<SourceUpdateReviewReason>, repository: String?, compatibilityLabel: String?): String = buildList {
    add("${manifest.author} · ${manifest.version}")
    repository?.let { add("发布仓库：$it") }
    add(compatibilityLabel ?: capabilityLabel(manifest.capabilities))
    if (reasons.isNotEmpty()) add("需要确认：" + reasons.distinct().joinToString("、") { reason -> when (reason) {
        SourceUpdateReviewReason.UNKNOWN_ORIGIN -> "来源未知"
        SourceUpdateReviewReason.REPOSITORY_CHANGED -> "发布仓库变更"
        SourceUpdateReviewReason.NETWORK -> "网络权限变更"
        SourceUpdateReviewReason.STORAGE -> "存储权限变更"
        SourceUpdateReviewReason.NATIVE_SDK -> "底层能力变更"
        SourceUpdateReviewReason.EFFECTS -> "系统权限变更"
        SourceUpdateReviewReason.LINKS -> "支持链接变更"
        SourceUpdateReviewReason.SESSION -> "账号会话变更"
        SourceUpdateReviewReason.CAPABILITIES -> "支持功能变更"
        SourceUpdateReviewReason.REQUIRED_CONFIGURATION -> "需要补充配置"
    } })
    if (compatibilityLabel == null) add(if (manifest.permissions.network.isEmpty()) "无网络权限" else "网络：${manifest.permissions.network.joinToString()}")
    if (manifest.permissions.storage) add("允许独立存储")
    if (manifest.permissions.nativeSdks.isNotEmpty()) add("底层能力：" + manifest.permissions.nativeSdks.map { when (it) {
        "netease.transport" -> "网易云安全通信"
        "netease.urs" -> "网易云账号登录"
        "netease.securityHeaders" -> "网易云设备安全"
        "netease.ursPuzzle", "netease.ursCaptcha" -> "网易云登录验证"
        "netease.history" -> "历史播放上报"
        "qqmusic.qrLogin" -> "QQ 音乐扫码登录"
        else -> it
    } }.distinct().joinToString("、"))
    if (manifest.permissions.effects.isNotEmpty()) add("系统能力：" + manifest.permissions.effects.map { when (it) {
        "files.pick" -> "选择文件"
        "files.upload" -> "上传所选文件"
        "sdk.call" -> "使用底层服务"
        "web.auth" -> "网页登录"
        "web.page" -> "打开网页"
        "qr.scan" -> "扫描二维码"
        "audio.capture" -> "录音"
        "audio.fingerprint" -> "识别音乐"
        "sms.compose" -> "编写短信"
        else -> it
    } }.distinct().joinToString("、"))
    if (manifest.linkHosts.isNotEmpty()) add("支持链接：${manifest.linkHosts.joinToString()}")
    if (manifest.sessionKeys.isNotEmpty()) add("允许保存本音源账号会话")
    if (manifest.sessionCookieNames.isNotEmpty()) add("Cookie 会话：${manifest.sessionCookieNames.values.flatten().distinct().joinToString()}")
    manifest.config.filter { it.required }.takeIf { it.isNotEmpty() }?.let { fields -> add("必填配置：${fields.joinToString { it.label }}") }
    add("仅安装你信任的插件")
}.filter(String::isNotBlank).joinToString("\n")

private fun statusLabel(status: SourceStatus): String = when (status) {
    SourceStatus.READY -> "已启用"
    SourceStatus.DISABLED -> "已禁用"
    SourceStatus.NEEDS_CONFIGURATION -> "待配置"
    SourceStatus.INCOMPATIBLE -> "版本不兼容"
    SourceStatus.FAILED -> "加载失败"
}
private fun capabilityLabel(capabilities: Set<String>): String = buildSet {
    if ("feed.home" in capabilities) add("首页")
    if ("feed.discover" in capabilities) add("发现")
    if ("catalog.search" in capabilities) add("搜索")
    if ("catalog.track" in capabilities) add("歌曲")
    if ("album.get" in capabilities) add("专辑")
    if ("artist.get" in capabilities) add("歌手")
    if ("playlist.get" in capabilities) add("歌单")
    if ("account.get" in capabilities) add("账号")
    if (capabilities.any { it.startsWith("library.") }) add("媒体库")
    if ("track.like" in capabilities || "collection.save" in capabilities) add("收藏")
    if (capabilities.any { it in PlaylistEditAction.entries.map(PlaylistEditAction::operation) }) add("歌单管理")
    if ("playback.resolve" in capabilities) add("播放")
    if ("lyrics.get" in capabilities) add("歌词")
    if ("feature.invoke" in capabilities) add("扩展功能")
}.joinToString(" · ")
