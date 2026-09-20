package com.sky22333.netboot

import android.Manifest
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.UsbOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sky22333.netboot.data.BootMode
import com.sky22333.netboot.data.DownloadState
import com.sky22333.netboot.data.IsoArchitecture
import com.sky22333.netboot.data.IsoLanguage
import com.sky22333.netboot.data.IsoState
import com.sky22333.netboot.data.RuntimeEventEntity
import com.sky22333.netboot.data.WindowsVersion
import com.sky22333.netboot.data.UsbPreparationStage
import com.sky22333.netboot.data.isBootFileUsable
import com.sky22333.netboot.runtime.RuntimeState
import com.sky22333.netboot.runtime.NetworkAdapter
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NetBootApp(viewModel) }
    }
}

/** Match system bars and icon contrast to the active Miuix theme. */
@Composable
private fun SetSystemBarsToMiuixTheme() {
    val background = MiuixTheme.colorScheme.background
    val view = LocalView.current
    if (view.isInEditMode) return
    val darkBackground = background.luminance() < 0.5f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        // Required on API 26–34; deprecated on API 35+.
        @Suppress("DEPRECATION")
        window.navigationBarColor = background.toArgb()
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightNavigationBars = !darkBackground
        controller.isAppearanceLightStatusBars = !darkBackground
    }
}

@Composable
private fun NetBootApp(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mode = when (settings.themeMode) {
        1 -> ColorSchemeMode.MonetLight
        2 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    MiuixTheme(
        controller = remember(mode) {
            ThemeController(
                colorSchemeMode = mode,
                keyColor = Color(0xFF365F78),
                colorSpec = ThemeColorSpec.Spec2025,
                paletteStyle = ThemePaletteStyle.TonalSpot,
            )
        },
    ) {
        SetSystemBarsToMiuixTheme()
        MainShell(viewModel)
    }
}

@Composable
private fun MainShell(viewModel: MainViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var pxeForm by rememberSaveable(stateSaver = PxeFormState.Saver) { mutableStateOf(PxeFormState()) }
    val defaultScript by viewModel.defaultIpxeScript.collectAsStateWithLifecycle()
    LaunchedEffect(defaultScript) {
        if (pxeForm.script.isEmpty() && defaultScript.isNotEmpty()) pxeForm = pxeForm.copy(script = defaultScript)
    }
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val adapters by viewModel.networkAdapters.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    LaunchedEffect(profiles, adapters) { pxeForm = pxeForm.reconcile(profiles.firstOrNull(), adapters) }
    var editingScript by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val titles = listOf(R.string.home, R.string.images, R.string.pxe, R.string.settings)
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Image, Icons.Outlined.Lan, Icons.Outlined.Settings)
    val resources = LocalResources.current
    val pageState = rememberSaveableStateHolder()
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() >= 600.dp }
    RequestNotificationPermissionOnce()
    LaunchedEffect(Unit) {
        viewModel.messages.collect { code -> snackbar.showSnackbar(resources.getString(messageResource(code))) }
    }
    if (editingScript) {
        ScriptEditorScreen(pxeForm.script, { pxeForm = pxeForm.copy(script = it) }, { editingScript = false })
        return
    }
    Scaffold(
        topBar = { SmallTopAppBar(stringResource(if (selected == 0) R.string.app_name else titles[selected])) },
        bottomBar = {
            if (!wide) NavigationBar {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected == index, { selected = index }, icons[index], stringResource(title))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail {
                titles.forEachIndexed { index, title ->
                    NavigationRailItem(selected == index, { selected = index }, icons[index], stringResource(title))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    pageState.SaveableStateProvider(selected) {
                        when (selected) {
            0 -> HomeScreen(viewModel, padding, onNavigate = { selected = it })
            1 -> ImagesScreen(viewModel, padding)
            2 -> PxeScreen(viewModel, padding, pxeForm, adapters, { pxeForm = it }, { editingScript = true })
            else -> SettingsScreen(viewModel, padding)
                        }
                    }
                }
            }
        }
    }
}

/** Request notification permission on API 33+ to show service notifications. */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(requested) {
        if (!requested) {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ScriptEditorScreen(value: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(value) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun requestClose() { if (draft != value) confirmDiscard = true else onClose() }
    BackHandler(onBack = ::requestClose)
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.ipxe_script),
                navigationIcon = {
                    CompactIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cancel),
                        onClick = ::requestClose,
                    )
                },
                actions = {
                    CompactIconButton(
                        icon = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.save),
                        onClick = { onSave(draft); onClose() },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.script_draft_hint), fontSize = 12.sp)
        TextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = stringResource(R.string.ipxe_script),
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
        }
    }
    OverlayDialog(show = confirmDiscard, title = stringResource(R.string.discard_changes), summary = stringResource(R.string.discard_changes_detail), onDismissRequest = { confirmDiscard = false }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onClose) { Text(stringResource(R.string.confirm)) }
        }
    }
}

@Composable
private fun HomeScreen(viewModel: MainViewModel, padding: PaddingValues, onNavigate: (Int) -> Unit) {
    val state by viewModel.runtime.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val metricColumns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 3
    val activeDownloads = downloads.count { it.state in listOf(DownloadState.Queued, DownloadState.Running, DownloadState.Verifying) }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                insideMargin = PaddingValues(16.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.secondaryContainer,
                    contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(stringResource(R.string.dashboard_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(if (state.networkRunning || state.usbAttached) R.string.dashboard_active else R.string.dashboard_ready),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item { SectionTitle(stringResource(R.string.overview)) }
        item {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = metricColumns, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric(stringResource(R.string.root_access), when (state.rootAvailable) { true -> stringResource(R.string.available); false -> stringResource(R.string.unavailable); null -> stringResource(R.string.not_checked) }, state.rootAvailable == true, Modifier.weight(1f))
                DashboardMetric(stringResource(R.string.pxe_service), if (state.networkRunning) stringResource(R.string.active) else stringResource(R.string.stopped), state.networkRunning, Modifier.weight(1f))
                DashboardMetric(stringResource(R.string.usb_installer), usbInstallerSummary(state.usbAttached, state.usbHostConnected, state.usbUnsupported), state.usbAttached, Modifier.weight(1f))
            }
        }
        if (state.usbUnsupported) {
            item {
                HintCard(
                    stringResource(R.string.usb_unsupported_summary, usbCapabilityReason(state.usbCapability?.reason)),
                    warning = true,
                )
            }
        }
        if (state.usbAttached) {
            item { HintCard(stringResource(R.string.usb_attached_warning), warning = true) }
        }
        if (state.usbPreparing || state.usbAttached || state.usbRecoveryRequired || state.networkRunning) item { RuntimeControls(viewModel) }
        item { SectionTitle(stringResource(R.string.quick_actions)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardAction(stringResource(R.string.images), stringResource(R.string.image_dashboard_summary, assets.count { it.state == IsoState.Ready }, activeDownloads), Modifier.weight(1f)) { onNavigate(1) }
                DashboardAction(stringResource(R.string.pxe), stringResource(if (state.networkRunning) R.string.active else R.string.ready_to_configure), Modifier.weight(1f)) { onNavigate(2) }
            }
        }
        state.errorCode?.let { error -> item { HintCard(runtimeErrorText(error), warning = true) } }
        item { CompactButton(viewModel::refreshCapabilities, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(stringResource(R.string.refresh_capabilities)) } }
        item { Text(stringResource(R.string.home_tip), color = MiuixTheme.colorScheme.onBackgroundVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun RuntimeControls(viewModel: MainViewModel) {
    val state by viewModel.runtime.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    CompactCard(Modifier.fillMaxWidth()) {
        assets.firstOrNull { it.id == state.activeIsoId }?.let { Text(it.fileName, fontWeight = FontWeight.Medium) }
        if (state.usbPreparing) {
            PreparationProgress(state)
            CompactIconButton(Icons.Outlined.Close, stringResource(R.string.cancel), viewModel::cancelUsbPreparation)
        }
        if (state.usbRecoveryRequired) Text(runtimeErrorText("usb_restore_failed"), fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.usbAttached || state.usbRecoveryRequired) Button(onClick = viewModel::detachIso, enabled = !state.busy) {
                Text(stringResource(if (state.usbRecoveryRequired) R.string.retry_restore else R.string.detach_usb))
            }
            if (state.networkRunning) Button(onClick = viewModel::stopNetwork, enabled = !state.busy) { Text(stringResource(R.string.stop_pxe)) }
        }
    }
}

@Composable
private fun DashboardMetric(title: String, value: String, active: Boolean, modifier: Modifier = Modifier) {
    CompactCard(modifier, insideMargin = PaddingValues(12.dp)) {
        Box(
            Modifier.size(7.dp).background(
                if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                CircleShape,
            ),
        )
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp))
        Text(title, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun DashboardAction(title: String, summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.heightIn(min = 82.dp),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(14.dp),
        onClick = onClick,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.tertiaryContainer,
            contentColor = MiuixTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(summary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ImagesScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val downloadCreationBusy by viewModel.downloadCreationBusy.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    var version by rememberSaveable { mutableStateOf(WindowsVersion.Windows11) }
    var language by rememberSaveable { mutableStateOf(IsoLanguage.Chinese) }
    var architecture by rememberSaveable { mutableStateOf(IsoArchitecture.X64) }
    var pendingAttach by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingCancel by remember { mutableStateOf<String?>(null) }
    var detailAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importIso) }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.download_official))
                Icon(Icons.Outlined.Image, null, tint = MiuixTheme.colorScheme.primary)
            }
        }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                OverlayDropdownPreference(items = WindowsVersion.entries.map { it.product }, selectedIndex = version.ordinal, title = stringResource(R.string.windows_version), onSelectedIndexChange = {
                    version = WindowsVersion.entries[it]
                    if (version == WindowsVersion.Windows10) architecture = IsoArchitecture.X64
                })
                OverlayDropdownPreference(items = listOf(stringResource(R.string.iso_chinese), stringResource(R.string.iso_english)), selectedIndex = language.ordinal, title = stringResource(R.string.language), onSelectedIndexChange = { language = IsoLanguage.entries[it] })
                OverlayDropdownPreference(items = IsoArchitecture.entries.map { it.name }, selectedIndex = architecture.ordinal, title = stringResource(R.string.architecture), enabled = version == WindowsVersion.Windows11, onSelectedIndexChange = {
                    architecture = IsoArchitecture.entries[it]
                })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactButton({ viewModel.download(version, language, architecture) }, Modifier.weight(1f), enabled = !downloadCreationBusy) { Text(stringResource(if (downloadCreationBusy) R.string.resolving_link else R.string.download_official)) }
                CompactButton({ importLauncher.launch(arrayOf("application/x-iso9660-image", "application/octet-stream")) }, Modifier.weight(1f)) { Text(stringResource(R.string.import_iso)) }
            }
        }
        if (downloads.isNotEmpty()) item { SectionTitle(stringResource(R.string.download_tasks)) }
        items(downloads, key = { "download:${it.id}" }) { task ->
            CompactCard(Modifier.fillMaxWidth()) {
                Text(assets.firstOrNull { it.id == task.isoAssetId }?.fileName.orEmpty(), fontWeight = FontWeight.Medium)
                Text(downloadStateText(task.state), fontSize = 13.sp)
                if (task.totalBytes > 0) {
                    LinearProgressIndicator(progress = (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f))
                    Text(stringResource(R.string.download_progress, formatBytes(task.downloadedBytes), formatBytes(task.totalBytes)), fontSize = 12.sp)
                }
                task.errorCode?.let { Text(runtimeErrorText(it), fontSize = 12.sp, color = MiuixTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (task.state == DownloadState.Running || task.state == DownloadState.Verifying) {
                        CompactIconButton(Icons.Outlined.Pause, stringResource(R.string.pause), { viewModel.pauseDownload(task.id) })
                    }
                    if (task.state in listOf(DownloadState.Paused, DownloadState.Failed, DownloadState.Queued)) {
                        if (task.errorCode != "remote_file_changed") CompactIconButton(Icons.Outlined.PlayArrow, stringResource(R.string.resume), { viewModel.resumeDownload(task.id) })
                    }
                    if (task.state !in listOf(DownloadState.Completed, DownloadState.Cancelled)) {
                        CompactIconButton(Icons.Outlined.Close, stringResource(R.string.cancel), { pendingCancel = task.id })
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.managed_images)) }
        runtime.errorCode?.let { error -> item { HintCard(runtimeErrorText(error), warning = true) } }
        if (runtime.usbUnsupported) {
            item {
                HintCard(
                    stringResource(R.string.usb_unsupported_summary, usbCapabilityReason(runtime.usbCapability?.reason)),
                    warning = true,
                )
            }
        }
        if (runtime.usbAttached) {
            item { HintCard(usbHostConnectedText(runtime.usbHostConnected), warning = true) }
        }
        if (runtime.usbRecoveryRequired) item { RuntimeControls(viewModel) }
        if (assets.isEmpty()) item { Text(stringResource(R.string.no_images)) }
        items(assets.filter { asset -> downloads.none { it.isoAssetId == asset.id } }, key = { "asset:${it.id}" }) { asset ->
            CompactCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(asset.fileName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(stringResource(R.string.image_summary, formatBytes(asset.fileSize), imageStateText(asset.state)), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (asset.sha256.isNotBlank()) CompactIconButton(Icons.Outlined.Info, stringResource(R.string.image_details), { detailAssetId = asset.id })
                }
                importProgress[asset.id]?.let { progress ->
                    Text(stringResource(if (progress.verifying) R.string.state_verifying else R.string.state_importing), fontSize = 13.sp)
                    if (progress.total > 0) {
                        LinearProgressIndicator(progress = (progress.bytes.toFloat() / progress.total).coerceIn(0f, 1f))
                        Text(stringResource(R.string.download_progress, formatBytes(progress.bytes), formatBytes(progress.total)), fontSize = 12.sp)
                    } else Text(formatBytes(progress.bytes), fontSize = 12.sp)
                    CompactIconButton(Icons.Outlined.Close, stringResource(R.string.cancel), { viewModel.cancelImport(asset.id) })
                }
                if (asset.source == "import" && asset.state == IsoState.Failed) {
                    Text(stringResource(R.string.import_retry_detail), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    CompactIconButton(Icons.Outlined.FolderOpen, stringResource(R.string.import_again), { importLauncher.launch(arrayOf("application/x-iso9660-image", "application/octet-stream")) })
                }
                if (runtime.activeIsoId == asset.id && runtime.usbPreparing) {
                    PreparationProgress(runtime)
                    CompactIconButton(Icons.Outlined.Close, stringResource(R.string.cancel), viewModel::cancelUsbPreparation)
                }
                if (runtime.activeIsoId == asset.id && runtime.usbAttached) Text(stringResource(if (runtime.usbDiskMode) R.string.usb_disk_mode else R.string.usb_optical_mode), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (asset.state == IsoState.Ready && runtime.activeIsoId != asset.id) CompactIconButton(Icons.Outlined.Usb, stringResource(R.string.attach_usb), { pendingAttach = asset.id }, enabled = !runtime.usbUnsupported && !runtime.busy && !runtime.usbAttached && !runtime.usbRecoveryRequired)
                    if (runtime.activeIsoId == asset.id && runtime.usbAttached) CompactIconButton(Icons.Outlined.UsbOff, stringResource(R.string.detach_usb), viewModel::detachIso, enabled = !runtime.busy)
                    if (runtime.activeIsoId != asset.id) CompactIconButton(Icons.Outlined.Delete, stringResource(R.string.delete), { pendingDelete = asset.id }, enabled = !runtime.busy && !runtime.usbRecoveryRequired && asset.state !in listOf(IsoState.Downloading, IsoState.Verifying, IsoState.Importing))
                }
            }
        }
    }
    val detailAsset = assets.firstOrNull { it.id == detailAssetId }
    OverlayDialog(show = detailAsset != null, title = stringResource(R.string.image_details), onDismissRequest = { detailAssetId = null }) {
        detailAsset?.let { asset ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(asset.fileName, fontSize = 13.sp)
                SelectionContainer { Text("SHA-256\n${asset.sha256}", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                CompactButton({ detailAssetId = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
            }
        }
    }
    OverlayDialog(
        show = pendingAttach != null,
        title = stringResource(R.string.usb_confirmation_title),
        summary = stringResource(R.string.usb_confirmation_detail),
        onDismissRequest = { pendingAttach = null },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton({ pendingAttach = null }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            CompactButton({ pendingAttach?.let(viewModel::attachIso); pendingAttach = null }, Modifier.weight(1f)) { Text(stringResource(R.string.confirm)) }
        }
    }
    OverlayDialog(
        show = pendingCancel != null,
        title = stringResource(R.string.cancel_download_title),
        summary = stringResource(R.string.cancel_download_detail),
        onDismissRequest = { pendingCancel = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactButton({ pendingCancel?.let { viewModel.cancelDownload(it, false) }; pendingCancel = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_partial)) }
            CompactButton({ pendingCancel?.let { viewModel.cancelDownload(it, true) }; pendingCancel = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_partial)) }
        }
    }
    OverlayDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.delete_image_title),
        summary = stringResource(R.string.delete_image_detail),
        onDismissRequest = { pendingDelete = null },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton({ pendingDelete = null }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            CompactButton({ pendingDelete?.let(viewModel::deleteIso); pendingDelete = null }, Modifier.weight(1f)) { Text(stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun PreparationProgress(state: RuntimeState) {
    val label = when (state.preparationStage) {
        UsbPreparationStage.CopyFiles -> R.string.media_stage_copy
        UsbPreparationStage.ExtractWim -> R.string.media_stage_extract
        UsbPreparationStage.NormalizeWim -> R.string.media_stage_normalize
        UsbPreparationStage.SplitWim -> R.string.media_stage_split
        UsbPreparationStage.CopyParts -> R.string.media_stage_parts
        UsbPreparationStage.Verify -> R.string.media_stage_verify
        UsbPreparationStage.Finalize -> R.string.media_stage_finalize
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(label), fontSize = 13.sp)
        if (state.preparationTotal > 0) {
            LinearProgressIndicator(progress = (state.preparedBytes.toFloat() / state.preparationTotal).coerceIn(0f, 1f))
            Text(stringResource(R.string.download_progress, formatBytes(state.preparedBytes), formatBytes(state.preparationTotal)), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PxeScreen(
    viewModel: MainViewModel,
    padding: PaddingValues,
    form: PxeFormState,
    adapters: List<NetworkAdapter>,
    onFormChanged: (PxeFormState) -> Unit,
    onEditScript: () -> Unit,
) {
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val pxeFiles by viewModel.pxeFiles.collectAsStateWithLifecycle()
    var pendingMode by remember { mutableStateOf<BootMode?>(null) }
    val adapter = form.adapter?.takeIf { it in adapters }
    val adapterIndex = adapters.indexOf(adapter).coerceAtLeast(0)
    val selectedMode = form.mode
    val portValue = form.port.toIntOrNull()
    val bootFileValid = isBootFileUsable(form.bootFile, pxeFiles)
    val configurationValid = adapter != null && form.portValid && form.scriptValid && bootFileValid &&
        (selectedMode != BootMode.Dhcp || form.poolValid)
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importPxeFiles(uris)
    }

    fun launchStart(target: BootMode) {
        if (!configurationValid || runtime.busy) return
        val current = adapter
        val selectedPort = portValue ?: return
        viewModel.startNetwork(target, current, selectedPort, form.bootFile, form.script, form.poolStart, form.poolEnd)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HintCard(stringResource(R.string.pxe_recommendation)) }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.network_interface),
                    items = adapters.map { "${it.name} · ${it.address}/${it.prefixLength}" }.ifEmpty { listOf(stringResource(R.string.no_network_interface)) },
                    selectedIndex = adapterIndex.coerceIn(0, (adapters.size - 1).coerceAtLeast(0)),
                    enabled = adapters.isNotEmpty(),
                    onSelectedIndexChange = { onFormChanged(form.selectAdapter(adapters.getOrNull(it))) },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.dhcp_mode),
                    items = listOf(stringResource(R.string.proxy_dhcp), stringResource(R.string.full_dhcp)),
                    selectedIndex = if (selectedMode == BootMode.Proxy) 0 else 1,
                    onSelectedIndexChange = { onFormChanged(form.copy(mode = if (it == 0) BootMode.Proxy else BootMode.Dhcp)) },
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.port, { onFormChanged(form.copy(port = it.filter(Char::isDigit).take(5))) }, Modifier.weight(1f), label = stringResource(R.string.http_port), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                CompactButton({ fileLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text(stringResource(R.string.import_boot_files)) }
            }
        }
        if (!form.portValid) item { HintCard(stringResource(R.string.http_port_invalid), warning = true) }
        item { TextField(form.bootFile, { onFormChanged(form.copy(bootFile = it)) }, Modifier.fillMaxWidth(), label = stringResource(R.string.boot_file), singleLine = true) }
        item { Text(stringResource(R.string.boot_file_hint), fontSize = 12.sp) }
        if (!bootFileValid) item { HintCard(stringResource(R.string.boot_file_not_imported), warning = true) }
        if (selectedMode == BootMode.Dhcp) {
            item { SectionTitle(stringResource(R.string.dhcp_pool)) }
            item { TextField(form.poolStart, { onFormChanged(form.copy(poolStart = it.filter { char -> char.isDigit() || char == '.' })) }, Modifier.fillMaxWidth(), label = stringResource(R.string.dhcp_pool_start), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            item { TextField(form.poolEnd, { onFormChanged(form.copy(poolEnd = it.filter { char -> char.isDigit() || char == '.' })) }, Modifier.fillMaxWidth(), label = stringResource(R.string.dhcp_pool_end), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
        }
        item {
            CompactCard(Modifier.fillMaxWidth(), onClick = onEditScript) {
                Text(stringResource(R.string.ipxe_script), fontWeight = FontWeight.Medium)
                Text(form.script.lineSequence().take(3).joinToString("\n"), fontSize = 12.sp)
            }
        }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                pxeFiles.forEach { file ->
                    CompactComponent(
                        title = file.name,
                        summary = if (file.builtIn) stringResource(R.string.built_in_file_summary, formatBytes(file.size)) else formatBytes(file.size),
                        onClick = { onFormChanged(form.copy(bootFile = file.name)) },
                        endActions = { if (!file.builtIn) CompactIconButton(icon = Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete), onClick = { viewModel.deletePxeFile(file.name) }) },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactButton({ adapter?.let { viewModel.saveProfile(selectedMode, it, portValue ?: 0, form.bootFile, form.script, form.poolStart, form.poolEnd) } }, Modifier.weight(1f), enabled = !runtime.busy && configurationValid) { Text(stringResource(R.string.save_configuration)) }
                CompactButton(
                    onClick = {
                        if (runtime.networkRunning) {
                            viewModel.stopNetwork()
                        } else if (selectedMode == BootMode.Dhcp) {
                            // A second address server can disrupt the LAN; require explicit consent.
                            pendingMode = BootMode.Dhcp
                        } else {
                            launchStart(BootMode.Proxy)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !runtime.busy && (runtime.networkRunning || configurationValid),
                    primary = !runtime.networkRunning,
                ) { Text(stringResource(if (runtime.networkRunning) R.string.stop_pxe else R.string.start_pxe)) }
            }
        }
        runtime.errorCode?.let { error -> item { HintCard(runtimeErrorText(error), warning = true) } }
        item { Text(stringResource(R.string.pxe_files_hint), fontSize = 12.sp) }
    }

    OverlayDialog(
        show = pendingMode != null,
        title = stringResource(R.string.full_dhcp_confirm_title),
        summary = stringResource(R.string.full_dhcp_confirm_detail),
        onDismissRequest = { pendingMode = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactButton({ pendingMode = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            CompactButton(
                {
                    val target = pendingMode
                    pendingMode = null
                    if (target != null) launchStart(target)
                },
                Modifier.fillMaxWidth(),
                enabled = configurationValid && !runtime.busy,
            ) { Text(stringResource(R.string.full_dhcp_confirm_action)) }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel, padding: PaddingValues) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showClearLogs by remember { mutableStateOf(false) }
    var followLogs by remember { mutableStateOf(true) }
    val logListState = rememberLazyListState()
    val chronologicalEvents = remember(events) { events.asReversed() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(viewModel::exportLogs)
    }
    LaunchedEffect(showLogs, chronologicalEvents.lastOrNull()?.id, followLogs) {
        if (showLogs && followLogs && chronologicalEvents.isNotEmpty()) {
            logListState.scrollToItem(chronologicalEvents.lastIndex)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle(stringResource(R.string.appearance)) }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                OverlayDropdownPreference(items = listOf(R.string.system_theme, R.string.light_theme, R.string.dark_theme).map { stringResource(it) }, selectedIndex = settings.themeMode, title = stringResource(R.string.theme), onSelectedIndexChange = viewModel::setTheme)
                OverlayDropdownPreference(items = listOf("1", "2", "4", "8"), selectedIndex = listOf(1, 2, 4, 8).indexOf(settings.downloadConnections), title = stringResource(R.string.connections), onSelectedIndexChange = { viewModel.setConnections(listOf(1, 2, 4, 8)[it]) })
            }
        }
        item { SectionTitle(stringResource(R.string.diagnostics)) }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                CompactComponent(
                    title = stringResource(R.string.runtime_logs),
                    summary = stringResource(R.string.runtime_logs_summary, events.size),
                    onClick = { showLogs = true },
                )
            }
        }
        item {
            CompactCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                CompactComponent(title = stringResource(R.string.about), summary = stringResource(R.string.about_summary), onClick = { showAbout = true })
            }
        }
    }
    OverlayDialog(
        show = showLogs,
        title = stringResource(R.string.runtime_logs),
        summary = stringResource(R.string.runtime_logs_live),
        onDismissRequest = { showLogs = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactIconButton(
                    icon = if (followLogs) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(if (followLogs) R.string.pause_follow else R.string.follow_latest),
                    onClick = { followLogs = !followLogs },
                )
                CompactIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.copy_logs),
                    onClick = viewModel::copyLogs,
                    enabled = events.isNotEmpty(),
                )
                CompactIconButton(
                    icon = Icons.Outlined.SaveAlt,
                    contentDescription = stringResource(R.string.export_logs),
                    onClick = { exportLauncher.launch("netboot-${System.currentTimeMillis()}.log") },
                )
                CompactIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear_logs),
                    onClick = { showClearLogs = true },
                    enabled = events.isNotEmpty(),
                )
                Spacer(Modifier.weight(1f))
                CompactIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close),
                    onClick = { showLogs = false },
                )
            }
            Card(
                // Miuix does not cap dialog height on phones; bound it so logs can scroll.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.55f }),
                cornerRadius = 12.dp,
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
            ) {
                if (chronologicalEvents.isEmpty()) {
                    Text(
                        stringResource(R.string.no_logs),
                        modifier = Modifier.padding(14.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    LazyColumn(state = logListState, modifier = Modifier.fillMaxWidth()) {
                        items(chronologicalEvents, key = { "log:${it.id}" }) { event -> LogLine(event) }
                    }
                }
            }
        }
    }
    OverlayDialog(
        show = showClearLogs,
        title = stringResource(R.string.clear_logs_title),
        summary = stringResource(R.string.clear_logs_detail),
        onDismissRequest = { showClearLogs = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton({ showClearLogs = false }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            CompactButton({ viewModel.clearLogs(); showClearLogs = false }, Modifier.weight(1f)) { Text(stringResource(R.string.clear_logs)) }
        }
    }
    OverlayDialog(
        show = showAbout,
        title = stringResource(R.string.about),
        summary = stringResource(R.string.about_summary),
        onDismissRequest = { showAbout = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_safety), fontSize = 13.sp)
            CompactButton({ showAbout = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
private fun LogLine(event: RuntimeEventEntity) {
    val scheme = MiuixTheme.colorScheme
    val arguments = remember(event.argumentsJson) {
        runCatching {
            Json.parseToJsonElement(event.argumentsJson).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }
    val color = when (event.severity) {
        "error" -> scheme.error
        "warning" -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            android.text.format.DateFormat.format("HH:mm:ss", event.timestamp).toString(),
            modifier = Modifier.width(57.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = scheme.onSurfaceVariantSummary,
        )
        Column(Modifier.weight(1f)) {
            Text("${event.source.uppercase()} · ${logEventTitle(event.eventCode)}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
            logEventDetail(event.eventCode, arguments).takeIf(String::isNotBlank)?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = scheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun logEventTitle(code: String): String = stringResource(
    when (code) {
        "netboot_started" -> R.string.log_service_started
        "netboot_stopped" -> R.string.log_service_stopped
        "http_failed" -> R.string.log_service_failed
        "dhcp_response" -> R.string.log_dhcp_response
        "boot_file_unsupported" -> R.string.log_dhcp_boot_file_unsupported
        "dhcp_response_failed" -> R.string.log_dhcp_failed
        "request_rejected" -> R.string.log_request_rejected
        "file_unavailable" -> R.string.log_file_unavailable
        "transfer_started" -> R.string.log_transfer_started
        "transfer_timeout" -> R.string.log_transfer_timeout
        "transfer_failed" -> R.string.log_transfer_failed
        "transfer_cancelled" -> R.string.log_transfer_cancelled
        "transfer_peer_stopped" -> R.string.log_transfer_peer_stopped
        "file_served" -> R.string.log_transfer_completed
        "http_request" -> R.string.log_http_request
        "operation_failed" -> R.string.log_operation_failed
        "usb_restored" -> R.string.log_usb_restored
        "usb_restore_failed" -> R.string.log_usb_restore_failed
        else -> R.string.log_event
    },
)

private fun logEventDetail(code: String, values: Map<String, String>): String = when (code) {
    "dhcp_response" -> listOfNotNull(values["client"], values["architecture"], values["bootFile"], values["remote"]).joinToString(" · ")
    "transfer_started", "file_served", "transfer_timeout", "transfer_failed", "transfer_cancelled", "transfer_peer_stopped", "file_unavailable" -> (listOfNotNull(
        values["client"], values["path"], values["bytes"]?.toLongOrNull()?.let(::formatBytes),
        values["duration"]?.let { "${it}ms" },
    ) + values.filterKeys { it !in setOf("client", "path", "bytes", "duration") }
        .toSortedMap().map { (key, value) -> "$key=$value" }).joinToString(" · ")
    "http_request" -> listOfNotNull(
        values["client"], listOfNotNull(values["method"], values["path"]).joinToString(" ").takeIf(String::isNotBlank),
        values["status"], values["bytes"]?.toLongOrNull()?.let(::formatBytes), values["duration"]?.let { "${it}ms" },
        values["range"]?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    "usb_restore_failed" -> values["steps"].orEmpty()
    else -> values.toSortedMap().entries.joinToString(" · ") { "${it.key}=${it.value}" }
}

@Composable
private fun CompactCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Card(
    modifier = modifier,
    cornerRadius = 12.dp,
    insideMargin = insideMargin,
    onClick = onClick,
    content = content,
)

@Composable
private fun HintCard(text: String, warning: Boolean = false) {
    val scheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(
            color = if (warning) scheme.tertiaryContainer else scheme.secondaryContainer,
            contentColor = if (warning) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
        ),
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    cornerRadius = 12.dp,
    minHeight = 48.dp,
    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content = content,
)

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = IconButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    minWidth = 48.dp,
    minHeight = 48.dp,
    cornerRadius = 12.dp,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(24.dp),
        tint = if (enabled) {
            MiuixTheme.colorScheme.onSurface
        } else {
            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        },
    )
}

@Composable
private fun CompactComponent(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
) = BasicComponent(
    title = title,
    summary = summary,
    enabled = enabled,
    onClick = onClick,
    endActions = endActions,
    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
)

@Composable
private fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)

@Composable
private fun downloadStateText(state: String): String = stringResource(
    when (state) {
        DownloadState.Queued -> R.string.state_queued
        DownloadState.Running -> R.string.state_running
        DownloadState.Paused -> R.string.state_paused
        DownloadState.Verifying -> R.string.state_verifying
        DownloadState.Completed -> R.string.state_completed
        DownloadState.Failed -> R.string.state_failed
        else -> R.string.state_cancelled
    },
)

@Composable
private fun imageStateText(state: String): String = stringResource(
    when (state) {
        IsoState.Importing -> R.string.state_importing
        IsoState.Downloading -> R.string.state_running
        IsoState.Verifying -> R.string.state_verifying
        IsoState.Ready -> R.string.state_ready
        else -> R.string.state_failed
    },
)

@Composable
private fun runtimeErrorText(code: String): String = stringResource(
    when (code) {
        "root_unavailable" -> R.string.runtime_error_root
        "remote_file_changed" -> R.string.remote_file_changed
        "range_mismatch", "invalid_content_range", "size_mismatch" -> R.string.download_integrity_failed
        "network_or_storage_error" -> R.string.download_connection_failed
        "broker_start_failed" -> R.string.runtime_error_broker
        "network_interface_changed" -> R.string.runtime_error_network_changed
        "network_already_running" -> R.string.runtime_error_already_running
        "http_port_unavailable" -> R.string.runtime_error_http_port
        "tftp_port_unavailable" -> R.string.runtime_error_tftp_port
        "dhcp_port_unavailable" -> R.string.runtime_error_dhcp_port
        "usb_restore_failed" -> R.string.runtime_error_usb_restore
        // Reuse probe messages for attach failures with the same reason code.
        "configfs_unavailable", "configfs_not_writable", "udc_unavailable",
        "active_gadget_unavailable", "gadget_read_only", "mass_storage_unsupported", "usb_unsupported",
        -> R.string.runtime_error_usb_unsupported
        "lun_node_missing", "cdrom_attribute_missing", "function_create_failed" -> R.string.runtime_error_usb_lun
        "function_link_failed", "usb_attach_failed", "usb_unbind_failed" -> R.string.runtime_error_usb_link
        "backing_file_rejected", "backing_file_mismatch" -> R.string.runtime_error_usb_backing
        "media_large_nonhybrid", "media_optical_limit" -> R.string.media_large_nonhybrid
        "media_storage_required", "insufficient_storage" -> R.string.media_storage_required
        "media_invalid_iso" -> R.string.media_invalid_download
        "media_udf_unreadable", "media_windows_layout" -> R.string.media_windows_layout
        "media_invalid_disk", "media_not_regular", "media_source_changed", "media_not_windows" -> R.string.media_invalid
        "media_file_too_large" -> R.string.media_file_too_large
        "media_io_failed", "media_read_failed", "media_write_failed", "media_format_failed", "media_wim_failed", "media_memory_failed", "media_verify_failed", "media_cleanup_failed", "media_root_forbidden" -> R.string.media_failed
        "iso_outside_private_storage", "iso_symlink_rejected", "iso_not_regular_file", "not_iso" -> R.string.runtime_error_iso_rejected
        "invalid_dhcp_pool" -> R.string.runtime_error_dhcp_pool
        else -> R.string.runtime_error_generic
    },
)

@Composable
private fun usbCapabilityReason(code: String?): String = stringResource(
    when (code) {
        "configfs_unavailable" -> R.string.usb_reason_configfs_missing
        "configfs_not_writable" -> R.string.usb_reason_configfs_readonly
        "udc_unavailable" -> R.string.usb_reason_udc
        "active_gadget_unavailable" -> R.string.usb_reason_no_active_gadget
        "gadget_read_only" -> R.string.usb_reason_gadget_readonly
        "mass_storage_unsupported" -> R.string.usb_reason_mass_storage
        else -> R.string.runtime_error_usb_unsupported
    },
)

@Composable
private fun usbInstallerSummary(attached: Boolean, hostConnected: Boolean, unsupported: Boolean): String = when {
    unsupported -> stringResource(R.string.unavailable)
    attached && hostConnected -> stringResource(R.string.usb_state_host_connected)
    attached -> stringResource(R.string.usb_state_mapped)
    else -> stringResource(R.string.stopped)
}

@Composable
private fun usbHostConnectedText(hostConnected: Boolean): String = stringResource(
    if (hostConnected) R.string.usb_attached_connected_warning else R.string.usb_attached_warning,
)

private fun messageResource(code: String): Int = when (code) {
    "import_complete" -> R.string.import_complete
    "media_invalid_iso", "empty_source", "not_iso" -> R.string.media_invalid_download
    "source_unavailable" -> R.string.source_unavailable
    "size_mismatch" -> R.string.download_integrity_failed
    "invalid_dhcp_pool" -> R.string.runtime_error_dhcp_pool
    "configuration_saved" -> R.string.configuration_saved
    "boot_file_not_imported" -> R.string.boot_file_not_imported
    "root_unavailable" -> R.string.root_unavailable
    "image_in_use" -> R.string.image_in_use
    "reserved_file_name" -> R.string.reserved_file_name
    "network_interface_changed" -> R.string.network_interface_changed
    "invalid_ipxe_script" -> R.string.invalid_ipxe_script
    "insufficient_storage" -> R.string.insufficient_storage
    "logs_exported" -> R.string.logs_exported
    "logs_copied" -> R.string.logs_copied
    "logs_copy_truncated" -> R.string.logs_copy_truncated
    "logs_cleared" -> R.string.logs_cleared
    "log_export_failed" -> R.string.log_export_failed
    "usb_restore_failed" -> R.string.runtime_error_usb_restore
    else -> R.string.operation_failed
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    // Locale.US keeps the decimal separator stable regardless of the system language.
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
