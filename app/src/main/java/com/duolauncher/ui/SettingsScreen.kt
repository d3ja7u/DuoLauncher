package com.duolauncher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.*
import com.duolauncher.data.IconPacks
import com.duolauncher.service.DuoNotificationListener
import com.duolauncher.util.ApkUpdater
import com.duolauncher.util.BackupManager
import com.duolauncher.util.InstallStats
import com.duolauncher.util.ScreenOff
import com.duolauncher.util.SearchEngines
import com.duolauncher.util.UpdateChecker
import com.duolauncher.util.WallpaperRotator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    nav: Nav,
    dark: Boolean,
    wallpaper: Bitmap?,
    initialSub: String? = null,
    onClose: () -> Unit
) {
    var sub by remember(initialSub) { mutableStateOf(initialSub) }

    BackHandler(enabled = sub != null) { sub = null }
    Box(Modifier.fillMaxSize()) {
        // 设置页信息密度高，底板要比别处实：壁纸只留约一成透出，避免背后桌面图标透上来干扰文字和色板
        GlassBackdrop(wallpaper, DuoState.glassLevel, dark, extraDim = 0.42f)
        when (sub) {
            "sidebar" -> SidebarEditorScreen(dark, onBack = { sub = null })
            "cats" -> CategoryEditorScreen(dark, onBack = { sub = null })
            "hidden" -> HiddenEditorScreen(dark, onBack = { sub = null })
            "batch_add" -> BatchEditScreen(BatchMode.ADD, nav, dark, onBack = { sub = null })
            "batch_remove" -> BatchEditScreen(BatchMode.REMOVE, nav, dark, onBack = { sub = null })
            else -> MainSettings(nav, dark, onClose) { sub = it }
        }
    }
}

/** 语言选项对应的显示名资源 */
private fun languageLabelRes(tag: String): Int = when (tag) {
    Lang.ZH -> R.string.language_zh
    Lang.EN -> R.string.language_en
    Lang.JA -> R.string.language_ja
    else -> R.string.language_system
}

/** 壁纸切换间隔的显示文案 */
private fun wpIntervalLabel(min: Int): String = when {
    min >= 1440 -> Lang.s(R.string.wp_every_day)
    min >= 60 -> Lang.s(R.string.wp_every_hours, min / 60)
    else -> Lang.s(R.string.wp_every_minutes, min)
}

/** LocalContext 可能包了几层 ContextWrapper，逐层找到 Activity 才能 recreate() */
private fun Context.asActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 状态徽标读取蜂窝信号 / 逐张 SIM 信号所需的运行时权限 */
private fun phonePermissionGranted(ctx: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.READ_PHONE_STATE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** 跳到系统的应用权限页；少数 ROM 没有该页时回退到应用详情页 */
private fun openPhonePermissionSettings(ctx: Context) {
    val pkg = Uri.parse("package:" + ctx.packageName)
    val permissionPage = Intent("android.settings.APP_PERMISSION_SETTINGS")
        .setData(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val detailsPage = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        if (permissionPage.resolveActivity(ctx.packageManager) != null) ctx.startActivity(permissionPage)
        else ctx.startActivity(detailsPage)
    }.onFailure { runCatching { ctx.startActivity(detailsPage) } }
}

@Composable
private fun MainSettings(nav: Nav, dark: Boolean, onClose: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver = remember { UpdateChecker.currentVersion(ctx) }

    // 电话权限状态：从系统权限页返回时的刷新挂在下面已有的 onResume 观察者上
    var phoneGranted by remember { mutableStateOf(phonePermissionGranted(ctx)) }

    // 壁纸自动切换：图片导入后复制到私有目录，这里只记录数量用于设置页回显
    var wpCount by remember { mutableStateOf(WallpaperRotator.count(ctx)) }
    var showWpInterval by remember { mutableStateOf(false) }
    // 系统相册选择器（Android 13+ 原生照片选择器，旧版自动回退文件选择器），可多选
    val pickWallpapers = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val n = WallpaperRotator.importUris(ctx, uris)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                wpCount = WallpaperRotator.count(ctx)
                nav.toast(Lang.s(R.string.wp_toast_added, n))
            }
        }
    }

    var pickIconPack by remember { mutableStateOf(false) }
    var editUpdateUrl by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    var langChoice by remember { mutableStateOf(Lang.get(ctx)) }
    var urlDraft by remember(DuoState.updateUrl) { mutableStateOf(DuoState.updateUrl) }
    var editStatsUrl by remember { mutableStateOf(false) }
    var statsUrlDraft by remember(DuoState.statsUrl) { mutableStateOf(DuoState.statsUrl) }
    var statsIdPreview by remember { mutableStateOf(InstallStats.anonymousId(ctx)?.take(8) ?: "") }
    var checking by remember { mutableStateOf(false) }
    var showUpdateResult by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    /** 待确认的备份：先弹摘要让用户核对，避免误选文件直接覆盖当前配置 */
    var pendingImport by remember { mutableStateOf<BackupManager.Info?>(null) }

    /** 在线更新：下载状态、当前任务、等待安装授权时暂存的 Release */
    var dlState by remember { mutableStateOf<ApkUpdater.State>(ApkUpdater.State.Idle) }
    var dlJob by remember { mutableStateOf<Job?>(null) }
    var pendingRelease by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    // 备份走系统文件选择器（SAF），不需要任何存储权限，文件由用户自己决定放哪
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME)
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = BackupManager.writeTo(ctx, uri, BackupManager.buildJson(ctx, ver))
        nav.toast(if (ok) Lang.s(R.string.backup_export_ok) else Lang.s(R.string.backup_export_fail))
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val info = BackupManager.readFrom(ctx, uri)?.let { BackupManager.inspect(it) }
        if (info == null) nav.toast(Lang.s(R.string.backup_invalid))
        else pendingImport = info
    }

    /** silent=true 只更新设置行上的提示，不弹窗打扰 */
    fun doCheck(silent: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val r = UpdateChecker.fetch(DuoState.updateUrl, ver)
            checking = false
            r.onSuccess { info ->
                DuoState.lastSeenVersion = info.version
                DuoState.lastUpdateCheck = System.currentTimeMillis()
                DuoState.save()
                if (!silent) {
                    dlState = ApkUpdater.State.Idle
                    updateInfo = info
                    updateError = null
                    showUpdateResult = true
                }
            }.onFailure { e ->
                if (!silent) {
                    dlState = ApkUpdater.State.Idle
                    updateInfo = null
                    updateError = e.message ?: Lang.s(R.string.update_check_failed)
                    showUpdateResult = true
                }
            }
        }
    }

    /** 开始下载；若还没拿到「安装未知来源应用」授权，先跳系统设置，回来自动继续 */
    fun startDownload(rel: UpdateChecker.ReleaseInfo) {
        if (rel.apkUrl.isBlank()) {
            UpdateChecker.openInBrowser(ctx, rel.pageUrl.ifBlank { UpdateChecker.DEFAULT_URL })
            return
        }
        if (!ApkUpdater.canInstall(ctx)) {
            pendingRelease = rel
            runCatching { ctx.startActivity(ApkUpdater.permissionIntent(ctx)) }
                .onFailure {
                    dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_permission))
                }
            return
        }
        pendingRelease = null
        dlJob?.cancel()
        dlState = ApkUpdater.State.Downloading(0L, rel.apkSize)
        dlJob = scope.launch {
            val r = ApkUpdater.download(ctx, rel.apkUrl, rel.apkName, rel.apkSize) { done, total ->
                dlState = ApkUpdater.State.Downloading(done, total)
            }
            r.onSuccess { file ->
                dlState = ApkUpdater.State.Idle
                if (ApkUpdater.install(ctx, file)) showUpdateResult = false
                else dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_start))
            }.onFailure { e ->
                if (e is CancellationException) return@launch
                dlState = ApkUpdater.State.Failed(
                    e.message ?: Lang.s(R.string.update_download_failed)
                )
            }
        }
    }

    fun cancelDownload() {
        dlJob?.cancel()
        dlJob = null
        dlState = ApkUpdater.State.Idle
    }

    // 进设置页时静默检查一次，12 小时节流，不弹窗
    LaunchedEffect(Unit) {
        if (DuoState.autoCheckUpdate &&
            System.currentTimeMillis() - DuoState.lastUpdateCheck > UpdateChecker.CHECK_INTERVAL
        ) {
            doCheck(silent = true)
        }
    }
    // 无障碍服务状态：从系统设置页返回时刷新（否则开关文案一直停在“未开启”）
    var accReady by remember { mutableStateOf(ScreenOff.isEnabled(ctx)) }
    // 通知使用权状态，同样需要在从系统页返回时刷新
    var badgeAccess by remember { mutableStateOf(DuoNotificationListener.hasAccess(ctx)) }
    // 有些 ROM 会在后台自行回收第三方无障碍服务，用户并没有再进过系统设置页，
    // 只靠 ON_RESUME 采样会一直停在「已开启」。监听系统「已启用服务列表」的变化，
    // 服务被关掉 / 重新连上时立刻刷新文案，避免出现「开过又变成没开」的错觉。
    DisposableEffect(ctx) {
        val uri = android.provider.Settings.Secure.getUriFor(
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val obs = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                accReady = ScreenOff.isEnabled(ctx)
            }
        }
        runCatching { ctx.contentResolver.registerContentObserver(uri, false, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                phoneGranted = phonePermissionGranted(ctx)
                accReady = ScreenOff.isEnabled(ctx)
                badgeAccess = DuoNotificationListener.hasAccess(ctx)
                // 从「安装未知来源应用」设置页返回：授权通过就接着下载
                val waiting = pendingRelease
                if (waiting != null) {
                    pendingRelease = null
                    if (ApkUpdater.canInstall(ctx)) startDownload(waiting)
                    else dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_permission))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val packs = remember { IconPacks.listPacks(ctx) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height((DuoState.cutoutPadding.coerceAtLeast(56)).dp))
            GlassTopBar(Lang.s(R.string.settings_title), dark, onClose)
        }
        item {
            SectionTitle(Lang.s(R.string.sec_sidebar), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        Lang.s(R.string.show_sidebar),
                        Lang.s(R.string.show_sidebar_desc),
                        DuoState.showSidebar, dark
                    ) { DuoState.showSidebar = it; DuoState.save() }
                    SettingRow(Lang.s(R.string.sidebar_position), if (DuoState.sidebarLeft) Lang.s(R.string.sidebar_pos_left_long) else Lang.s(R.string.sidebar_pos_right), dark,
                        trailing = {
                            Row {
                                listOf(false to Lang.s(R.string.right_short), true to Lang.s(R.string.left_short)).forEach { (v, label) ->
                                    val on = DuoState.sidebarLeft == v
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        color = if (on) Color.White else glassTextColor(dark),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (on) glassBlue else glassTextColor(dark).copy(alpha = 0.10f))
                                            .clickable { DuoState.sidebarLeft = v; DuoState.save() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        })
                    SettingRow(Lang.s(R.string.edit_sidebar), Lang.s(R.string.sidebar_edit_desc), dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("sidebar") })
                    SettingSliderRow(Lang.s(R.string.cutout_padding), "${DuoState.cutoutPadding} dp", DuoState.cutoutPadding.toFloat(),
                        40f..180f, dark) { DuoState.cutoutPadding = it.toInt(); DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.home_icon_size), "${"%.1f".format(DuoState.iconScale)}×", DuoState.iconScale,
                        0.7f..1.4f, dark) { DuoState.iconScale = it; DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.sidebar_icon_size), "${"%.1f".format(DuoState.sidebarIconScale)}×", DuoState.sidebarIconScale,
                        0.6f..1.1f, dark) { DuoState.sidebarIconScale = it; DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.sidebar_glass), "${"%.1f".format(DuoState.sidebarGlass)}×", DuoState.sidebarGlass,
                        0.4f..2.4f, dark) { DuoState.sidebarGlass = it; DuoState.save() }
                    SettingRow(Lang.s(R.string.sidebar_align), Lang.s(R.string.sidebar_align), dark)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SegmentedChips(
                            options = listOf(
                                "center" to Lang.s(R.string.sidebar_align_center),
                                "bottom" to Lang.s(R.string.sidebar_align_bottom)
                            ),
                            selected = DuoState.sidebarAlign,
                            dark = dark
                        ) { DuoState.sidebarAlign = it; DuoState.save() }
                    }
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_home), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(Lang.s(R.string.home_columns), Lang.s(R.string.home_columns_desc), dark)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SegmentedChips(
                            options = listOf(3, 4, 5, 6, 7).map { it.toString() to it.toString() },
                            selected = DuoState.homeColumns.toString(),
                            dark = dark
                        ) { DuoState.homeColumns = it.toInt(); DuoState.save() }
                    }
                    SettingRow(Lang.s(R.string.home_rows), Lang.s(R.string.home_rows_desc), dark)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SegmentedChips(
                            options = listOf(4, 5, 6, 7).map { it.toString() to it.toString() },
                            selected = DuoState.homeRows.toString(),
                            dark = dark
                        ) { DuoState.homeRows = it.toInt(); DuoState.save() }
                    }
                    SettingRow(
                        Lang.s(R.string.batch_add_apps),
                        Lang.s(R.string.batch_add_apps_desc),
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) }
                    ) { open("batch_add") }
                    SettingRow(
                        Lang.s(R.string.batch_remove_apps),
                        Lang.s(R.string.batch_remove_apps_desc),
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) }
                    ) { open("batch_remove") }
                    SettingToggle(
                        Lang.s(R.string.auto_add_new_apps),
                        Lang.s(R.string.auto_add_new_apps_desc),
                        DuoState.autoAddNewApps, dark
                    ) { DuoState.autoAddNewApps = it; DuoState.save() }
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_now_playing), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        Lang.s(R.string.now_playing_switch),
                        Lang.s(R.string.now_playing_desc),
                        DuoState.showNowPlaying, dark
                    ) { on ->
                        DuoState.showNowPlaying = on; DuoState.save()
                        if (on && !badgeAccess) DuoNotificationListener.openSettings(ctx)
                    }
                    if (DuoState.showNowPlaying && !badgeAccess) {
                        SettingRow(
                            Lang.s(R.string.notif_access),
                            Lang.s(R.string.notif_access_off),
                            dark,
                            trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                            onClick = { DuoNotificationListener.openSettings(ctx) }
                        )
                    }
                }
            }
            Text(
                Lang.s(R.string.now_playing_note),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_search), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(
                        Lang.s(R.string.recent_count),
                        Lang.s(R.string.recent_count_value, DuoState.recentCount), dark
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SegmentedChips(
                            options = SearchEngines.RECENT_COUNTS.map { it.toString() to it.toString() },
                            selected = DuoState.recentCount.toString(),
                            dark = dark
                        ) { DuoState.recentCount = it.toInt(); DuoState.save() }
                    }
                    SettingRow(
                        Lang.s(R.string.search_engine),
                        SearchEngines.label(DuoState.searchEngine), dark
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SegmentedChips(
                            options = SearchEngines.ALL.map { it.id to Lang.s(it.labelRes) },
                            selected = DuoState.searchEngine,
                            dark = dark
                        ) { DuoState.searchEngine = it; DuoState.save() }
                    }
                    SettingToggle(
                        Lang.s(R.string.search_contacts),
                        Lang.s(R.string.search_contacts_desc),
                        DuoState.searchContacts, dark
                    ) { DuoState.searchContacts = it; DuoState.save() }
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_status_bar), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(Lang.s(R.string.status_time_signal), Lang.s(R.string.status_time_signal_desc),
                        DuoState.showDuoStatus, dark) { DuoState.showDuoStatus = it; DuoState.save() }
                    SettingRow(Lang.s(R.string.sidebar_position), if (DuoState.statusRight) Lang.s(R.string.status_pos_right) else Lang.s(R.string.status_pos_left), dark,
                        trailing = {
                            Row {
                                listOf(true to Lang.s(R.string.right_short), false to Lang.s(R.string.left_short)).forEach { (v, label) ->
                                    val on = DuoState.statusRight == v
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        color = if (on) Color.White else glassTextColor(dark),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (on) glassBlue else glassTextColor(dark).copy(alpha = 0.10f))
                                            .clickable { DuoState.statusRight = v; DuoState.save() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        })
                    SettingToggle(Lang.s(R.string.status_24h), Lang.s(R.string.status_24h_desc), DuoState.status24h, dark) {
                        DuoState.status24h = it; DuoState.save()
                    }
                    // 其余制式按真实网络自动显示；这一项只决定 4G 时写 LTE 还是 4G
                    SettingRow(
                        Lang.s(R.string.status_network_label),
                        Lang.s(R.string.status_network_label_desc),
                        dark,
                        trailing = {
                            Row {
                                listOf("LTE", "4G").forEach { v ->
                                    val on = DuoState.status4gLabel == v
                                    Text(
                                        v,
                                        fontSize = 13.sp,
                                        color = if (on) Color.White else glassTextColor(dark),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (on) glassBlue else glassTextColor(dark).copy(alpha = 0.10f))
                                            .clickable { DuoState.status4gLabel = v; DuoState.save() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        })
                    SettingToggle(
                        Lang.s(R.string.status_battery_number),
                        Lang.s(R.string.status_battery_number_desc),
                        DuoState.showBatteryNumber, dark
                    ) { DuoState.showBatteryNumber = it; DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.status_top_padding), "${DuoState.statusTop} dp", DuoState.statusTop.toFloat(),
                        8f..140f, dark) { DuoState.statusTop = it.toInt(); DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.status_scale), "${"%.1f".format(DuoState.statusScale)}×", DuoState.statusScale,
                        0.7f..1.6f, dark) { DuoState.statusScale = it; DuoState.save() }
                    // 双卡信号依赖「电话」权限。首次启动拒绝过授权的用户，在这里可以重新开启。
                    val openPhonePerm = { openPhonePermissionSettings(ctx) }
                    SettingRow(
                        Lang.s(R.string.status_phone_permission),
                        Lang.s(R.string.status_phone_permission_desc),
                        dark,
                        trailing = {
                            Text(
                                if (phoneGranted) Lang.s(R.string.status_phone_granted)
                                else Lang.s(R.string.status_phone_open),
                                fontSize = 13.sp,
                                color = if (phoneGranted) glassTextColor(dark) else Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (phoneGranted) glassTextColor(dark).copy(alpha = 0.10f)
                                        else glassBlue
                                    )
                                    .clickable(enabled = !phoneGranted) { openPhonePerm() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        },
                        onClick = if (phoneGranted) null else openPhonePerm
                    )
                    val st = rememberDuoStatus(DuoState.status24h).value
                    val simInfo = if (st.simLevels.size >= 2)
                        " · " + Lang.s(R.string.status_dual_sim, st.simLevels[0], st.simLevels[1]) else ""
                    // 非 WiFi 时把自动识别出的制式也显示出来，方便核对 5GA / 5GUW 等
                    val netInfo = if (st.wifi) "" else " · " + st.netLabel
                    SettingRow(
                        Lang.s(R.string.live_status),
                        "${st.time} · " + when {
                            st.wifi -> Lang.s(R.string.status_wifi, st.level)
                            st.cellular -> Lang.s(R.string.status_cellular, st.level)
                            else -> Lang.s(R.string.status_no_network)
                        } + simInfo + netInfo + Lang.s(R.string.status_battery, st.battery) + if (st.charging) " " + Lang.s(R.string.charging) else "",
                        dark
                    )
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_home_gesture), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(Lang.s(R.string.show_sys_status), Lang.s(R.string.show_sys_status_desc),
                        !DuoState.hideStatusBar, dark) {
                        DuoState.hideStatusBar = !it; DuoState.save(); nav.toast(Lang.s(R.string.toast_restart_launcher))
                    }
                    SettingToggle(Lang.s(R.string.show_page_dots), null, DuoState.showPageDots, dark) { DuoState.showPageDots = it; DuoState.save() }
                    SettingToggle(Lang.s(R.string.reverse_pages), Lang.s(R.string.reverse_pages_desc),
                        DuoState.reversePages, dark) { DuoState.reversePages = it; DuoState.save() }
                    SettingToggle(
                        Lang.s(R.string.double_tap_lock),
                        if (accReady) Lang.s(R.string.double_tap_lock_on)
                        else Lang.s(R.string.double_tap_lock_off),
                        DuoState.doubleTapSleep, dark
                    ) { on ->
                        DuoState.doubleTapSleep = on; DuoState.save()
                        if (on && !accReady) ScreenOff.requestPermission(ctx)
                    }
                    SettingToggle(
                        Lang.s(R.string.swipe_down_panels),
                        Lang.s(R.string.swipe_down_panels_desc),
                        DuoState.gesturePanels, dark
                    ) { on -> DuoState.gesturePanels = on; DuoState.save() }
                    SettingToggle(
                        Lang.s(R.string.swipe_right_search),
                        Lang.s(R.string.swipe_right_search_desc),
                        DuoState.gestureSearch, dark
                    ) { on -> DuoState.gestureSearch = on; DuoState.save() }
                    SettingToggle(
                        Lang.s(R.string.show_bottom_left),
                        Lang.s(R.string.show_bottom_left_desc),
                        DuoState.showBottomLeft, dark
                    ) { DuoState.showBottomLeft = it; DuoState.save() }
                    SettingToggle(
                        Lang.s(R.string.show_bottom_right),
                        Lang.s(R.string.show_bottom_right_desc),
                        DuoState.showBottomRight, dark
                    ) { DuoState.showBottomRight = it; DuoState.save() }
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.app_library), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(Lang.s(R.string.smart_category_switch), Lang.s(R.string.smart_category_desc),
                        DuoState.smartCategory, dark) { DuoState.smartCategory = it; DuoState.save() }
                    SettingRow(Lang.s(R.string.edit_app_category), Lang.s(R.string.edit_category_desc), dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("cats") })
                    SettingRow(Lang.s(R.string.hide_app), Lang.s(R.string.hidden_manage, DuoState.hidden.size), dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("hidden") })
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_appearance), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingSliderRow(Lang.s(R.string.glass_blur), "${(DuoState.glassLevel * 100).toInt()}%",
                        DuoState.glassLevel, 0f..1f, dark) { DuoState.glassLevel = it; DuoState.save() }
                    SettingToggle(Lang.s(R.string.dark_glass), Lang.s(R.string.dark_glass_desc), DuoState.darkGlass, dark) {
                        DuoState.darkGlass = it; DuoState.save()
                    }
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_wallpaper_rotate), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        Lang.s(R.string.wp_rotate), Lang.s(R.string.wp_rotate_desc),
                        DuoState.wallpaperRotate, dark
                    ) {
                        DuoState.wallpaperRotate = it; DuoState.save()
                        // 开启且已有图片时立刻换一张，让效果马上可见，并从现在开始计时
                        if (it && wpCount > 0) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) { WallpaperRotator.rotateNow(ctx) }
                        }
                    }
                    SettingToggle(
                        Lang.s(R.string.wp_swipe), Lang.s(R.string.wp_swipe_desc),
                        DuoState.wallpaperSwipe, dark
                    ) {
                        DuoState.wallpaperSwipe = it; DuoState.save()
                    }
                    SettingToggle(
                        Lang.s(R.string.wp_sync_ls), Lang.s(R.string.wp_sync_ls_desc),
                        DuoState.wallpaperSyncLockscreen, dark
                    ) {
                        DuoState.wallpaperSyncLockscreen = it; DuoState.save()
                        // 开启时立即按当前设置重设一次，锁屏马上生效（含同步关闭→只换主屏的回退）
                        if (wpCount > 0) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                WallpaperRotator.rotateNow(ctx)
                            }
                        }
                    }
                    SettingRow(
                        Lang.s(R.string.wp_pick),
                        Lang.s(R.string.wp_pick_desc, wpCount),
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = {
                            runCatching {
                                pickWallpapers.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        }
                    )
                    SettingRow(
                        Lang.s(R.string.wp_interval),
                        wpIntervalLabel(DuoState.wallpaperIntervalMin),
                        dark,
                        onClick = { showWpInterval = true }
                    )
                    SettingToggle(
                        Lang.s(R.string.wp_random), Lang.s(R.string.wp_random_desc),
                        DuoState.wallpaperRandom, dark
                    ) { DuoState.wallpaperRandom = it; DuoState.save() }
                    SettingRow(
                        Lang.s(R.string.wp_now), Lang.s(R.string.wp_now_desc), dark,
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val ok = WallpaperRotator.rotateNow(ctx)
                                if (!ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    nav.toast(Lang.s(R.string.wp_toast_fail))
                                }
                            }
                        }
                    )
                    SettingRow(
                        Lang.s(R.string.wp_clear), Lang.s(R.string.wp_clear_desc), dark,
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                WallpaperRotator.clear(ctx)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { wpCount = 0 }
                            }
                        }
                    )
                }
            }
        }
        item {
            SectionTitle(Lang.s(R.string.sec_icon_pack), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(
                        Lang.s(R.string.current_icon_pack),
                        if (DuoState.iconPack.isBlank()) Lang.s(R.string.system_default) else packs.firstOrNull { it.pkg == DuoState.iconPack }?.label ?: DuoState.iconPack,
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { pickIconPack = true }
                    )
                }
            }
            Text(
                Lang.s(R.string.icon_pack_hint),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_badges), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        Lang.s(R.string.show_badge),
                        if (badgeAccess) Lang.s(R.string.show_badge_on)
                        else Lang.s(R.string.show_badge_off),
                        DuoState.showBadges, dark
                    ) { on ->
                        DuoState.showBadges = on; DuoState.save()
                        if (on && !badgeAccess) DuoNotificationListener.openSettings(ctx)
                    }
                    BadgeColorRow(dark, DuoState.badgeColor) { argb ->
                        DuoState.badgeColor = argb; DuoState.save()
                    }
                    SettingRow(
                        Lang.s(R.string.notif_access),
                        if (badgeAccess) Lang.s(R.string.notif_access_on) else Lang.s(R.string.notif_access_off),
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { DuoNotificationListener.openSettings(ctx) }
                    )
                    SettingRow(
                        Lang.s(R.string.clear_all_badges),
                        Lang.s(R.string.clear_all_badges_desc),
                        dark
                    ) { DuoNotificationListener.dismissAll() }
                }
            }
            Text(
                Lang.s(R.string.badge_note),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_update), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    val hasNew = DuoState.lastSeenVersion.isNotBlank() &&
                            UpdateChecker.isNewer(DuoState.lastSeenVersion, ver)
                    SettingRow(
                        Lang.s(R.string.check_update),
                        when {
                            checking -> Lang.s(R.string.checking_update)
                            hasNew -> Lang.s(R.string.update_new_found, DuoState.lastSeenVersion)
                            DuoState.lastSeenVersion.isNotBlank() -> Lang.s(R.string.update_latest, ver)
                            else -> Lang.s(R.string.update_current, ver)
                        },
                        dark,
                        trailing = {
                            if (checking) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (hasNew) Icons.Filled.NewReleases else Icons.Filled.Refresh,
                                    null,
                                    tint = if (hasNew) glassBlue else glassSubTextColor(dark),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClick = { doCheck(silent = false) }
                    )
                    SettingToggle(
                        Lang.s(R.string.auto_check_update), Lang.s(R.string.auto_check_desc),
                        DuoState.autoCheckUpdate, dark
                    ) { on -> DuoState.autoCheckUpdate = on; DuoState.save() }
                    val shownUrl = if (DuoState.updateUrl.length > 48)
                        DuoState.updateUrl.take(48) + "…" else DuoState.updateUrl
                    SettingRow(Lang.s(R.string.update_source), shownUrl, dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { urlDraft = DuoState.updateUrl; editUpdateUrl = true })
                    SettingRow(Lang.s(R.string.open_project), "github.com/d3ja7u/DuoLauncher", dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { UpdateChecker.openInBrowser(ctx, "https://github.com/d3ja7u/DuoLauncher") })
                }
            }
            Text(
                Lang.s(R.string.update_note),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_privacy), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        Lang.s(R.string.stats_toggle),
                        Lang.s(R.string.stats_toggle_desc),
                        DuoState.statsEnabled, dark
                    ) { on ->
                        DuoState.statsEnabled = on
                        if (on) {
                            // 在设置里主动打开即视为已知情，不必再弹首次告知
                            DuoState.statsConsentShown = true
                            statsIdPreview = InstallStats.ensureId(ctx).take(8)
                            InstallStats.reportIfAllowed(ctx, force = true)
                        } else {
                            InstallStats.clear(ctx)
                            statsIdPreview = ""
                        }
                        DuoState.save()
                    }
                    SettingRow(
                        Lang.s(R.string.stats_anon_id),
                        if (statsIdPreview.isBlank()) Lang.s(R.string.stats_anon_id_none) else "$statsIdPreview…",
                        dark
                    )
                    val shownStatsUrl = when {
                        DuoState.statsUrl.isBlank() -> Lang.s(R.string.stats_endpoint_empty)
                        DuoState.statsUrl.length > 40 -> DuoState.statsUrl.take(40) + "…"
                        else -> DuoState.statsUrl
                    }
                    SettingRow(Lang.s(R.string.stats_endpoint), shownStatsUrl, dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { statsUrlDraft = DuoState.statsUrl; editStatsUrl = true })
                    SettingRow(Lang.s(R.string.stats_view), Lang.s(R.string.stats_view_desc), dark,
                        trailing = { Icon(Icons.Filled.OpenInNew, null, tint = glassSubTextColor(dark)) },
                        onClick = { UpdateChecker.openInBrowser(ctx, InstallStats.STATS_PAGE) })
                }
            }
            Text(
                Lang.s(R.string.stats_note),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_backup), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(
                        Lang.s(R.string.backup_export), Lang.s(R.string.backup_export_desc), dark,
                        trailing = { Icon(Icons.Filled.ArrowUpward, null, tint = glassSubTextColor(dark)) },
                        onClick = {
                            runCatching { exportLauncher.launch(BackupManager.fileName()) }
                                .onFailure { nav.toast(Lang.s(R.string.backup_export_fail)) }
                        }
                    )
                    SettingRow(
                        Lang.s(R.string.backup_import), Lang.s(R.string.backup_import_desc), dark,
                        trailing = { Icon(Icons.Filled.ArrowDownward, null, tint = glassSubTextColor(dark)) },
                        onClick = {
                            runCatching {
                                importLauncher.launch(arrayOf(BackupManager.MIME, "text/plain", "*/*"))
                            }.onFailure { nav.toast(Lang.s(R.string.backup_invalid)) }
                        }
                    )
                }
            }
            Text(
                Lang.s(R.string.backup_note),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle(Lang.s(R.string.sec_system), dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(
                        Lang.s(R.string.language),
                        Lang.s(languageLabelRes(langChoice)),
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { showLangPicker = true }
                    )
                    SettingRow(Lang.s(R.string.set_default_launcher), Lang.s(R.string.set_default_desc), dark) {
                        AppRepository.setDefaultLauncher(ctx)
                    }
                    SettingRow(Lang.s(R.string.change_wallpaper), dark = dark) { AppRepository.wallpaper(ctx) }
                    SettingRow(Lang.s(R.string.app_info_self), dark = dark) {
                        AppRepository.appDetails(ctx, ctx.packageName)
                    }
                    // 版本号直接从包信息读，避免每次发版忘了改这里的硬编码
                    SettingRow(Lang.s(R.string.about), Lang.s(R.string.about_subtitle, ver), dark = dark)
                }
            }
            Text(
                Lang.s(R.string.language_desc),
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
    }

    if (showLangPicker) {
        DuoDialog(Lang.s(R.string.language), dark, opaque = true, onDismiss = { showLangPicker = false }) {
            Lang.CHOICES.forEach { tag ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val act = ctx.asActivity()
                            if (act != null) {
                                Lang.markReopenSettings(ctx)
                                Lang.set(ctx, tag)
                                langChoice = tag
                                showLangPicker = false
                                act.recreate()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Lang.s(languageLabelRes(tag)),
                        fontSize = 15.sp,
                        color = glassTextColor(dark),
                        modifier = Modifier.weight(1f)
                    )
                    if (tag == langChoice) {
                        Icon(Icons.Filled.Check, null, tint = glassBlue, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (showWpInterval) {
        Dialog(onDismissRequest = { showWpInterval = false }) {
            GlassCard(Modifier.fillMaxWidth(), radius = 26.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(18.dp)) {
                    Text(Lang.s(R.string.wp_interval), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
                    Spacer(Modifier.height(12.dp))
                    listOf(15, 30, 60, 180, 360, 1440).forEach { min ->
                        val on = DuoState.wallpaperIntervalMin == min
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) glassBlue.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    DuoState.wallpaperIntervalMin = min
                                    DuoState.save()
                                    showWpInterval = false
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(wpIntervalLabel(min), fontSize = 15.sp,
                                color = glassTextColor(dark), modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, null, tint = glassBlue, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (pickIconPack) {
        Dialog(onDismissRequest = { pickIconPack = false }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 540.dp), radius = 26.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(18.dp)) {
                    Text(Lang.s(R.string.choose_icon_pack), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (DuoState.iconPack.isBlank()) glassBlue.copy(alpha = 0.18f) else glassTextColor(dark).copy(alpha = 0.08f))
                                .clickable { DuoState.iconPack = ""; DuoState.save() }
                                .padding(vertical = 12.dp, horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Android, null, tint = glassTextColor(dark), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(Lang.s(R.string.system_default), fontSize = 15.sp, color = glassTextColor(dark))
                        }
                        Spacer(Modifier.height(6.dp))
                        if (packs.isEmpty()) {
                            Text(Lang.s(R.string.no_icon_pack_installed), fontSize = 13.sp, color = glassSubTextColor(dark),
                                modifier = Modifier.padding(8.dp))
                        }
                        packs.forEach { p ->
                            val on = DuoState.iconPack == p.pkg
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (on) glassBlue.copy(alpha = 0.18f) else glassTextColor(dark).copy(alpha = 0.08f))
                                    .clickable { DuoState.iconPack = p.pkg; DuoState.save() }
                                    .padding(vertical = 12.dp, horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Palette, null, tint = glassTextColor(dark), modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, fontSize = 15.sp, color = glassTextColor(dark), maxLines = 1)
                                    Text(p.pkg, fontSize = 10.sp, color = glassSubTextColor(dark), maxLines = 1)
                                }
                                if (on) Icon(Icons.Filled.Check, null, tint = glassBlue, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }

    if (editUpdateUrl) {
        DuoDialog(Lang.s(R.string.update_source_title), dark, opaque = true, onDismiss = { editUpdateUrl = false }) {
            Text(
                Lang.s(R.string.update_source_hint),
                fontSize = 12.sp, color = glassSubTextColor(dark)
            )
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth().heightIn(min = 60.dp), radius = 12.dp, level = 0.8f, dark = dark) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (urlDraft.isEmpty()) Text("https://…", fontSize = 12.sp, color = glassSubTextColor(dark))
                    BasicTextField(
                        urlDraft, { urlDraft = it }, maxLines = 3,
                        textStyle = TextStyle(fontSize = 12.sp, color = glassTextColor(dark)),
                        cursorBrush = SolidColor(glassBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { urlDraft = UpdateChecker.DEFAULT_URL }) {
                    Text(Lang.s(R.string.restore_default), fontSize = 13.sp, color = glassSubTextColor(dark))
                }
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.save), dark, onClick = {
                    // 多行输入框里可能混进换行/空格，存盘前一并去掉
                    val t = urlDraft.replace(Regex("\\s+"), "")
                    if (t.isBlank()) {
                        nav.toast(Lang.s(R.string.toast_url_empty))
                    } else {
                        DuoState.updateUrl = t
                        DuoState.lastUpdateCheck = 0L
                        DuoState.lastSeenVersion = ""
                        DuoState.save()
                        editUpdateUrl = false
                        nav.toast(Lang.s(R.string.toast_url_saved))
                    }
                })
            }
        }
    }

    if (editStatsUrl) {
        DuoDialog(
            Lang.s(R.string.stats_endpoint_title), dark, opaque = true,
            onDismiss = { editStatsUrl = false }
        ) {
            Text(
                Lang.s(R.string.stats_endpoint_hint),
                fontSize = 12.sp, color = glassSubTextColor(dark)
            )
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth().heightIn(min = 60.dp), radius = 12.dp, level = 0.8f, dark = dark) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (statsUrlDraft.isEmpty()) {
                        Text(
                            Lang.s(R.string.stats_endpoint_placeholder),
                            fontSize = 12.sp, color = glassSubTextColor(dark)
                        )
                    }
                    BasicTextField(
                        statsUrlDraft, { statsUrlDraft = it }, maxLines = 3,
                        textStyle = TextStyle(fontSize = 12.sp, color = glassTextColor(dark)),
                        cursorBrush = SolidColor(glassBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { statsUrlDraft = "" }) {
                    Text(Lang.s(R.string.stats_endpoint_clear), fontSize = 13.sp, color = glassSubTextColor(dark))
                }
                TextButton(onClick = { statsUrlDraft = InstallStats.DEFAULT_URL }) {
                    Text(Lang.s(R.string.restore_default), fontSize = 13.sp, color = glassSubTextColor(dark))
                }
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.save), dark, onClick = {
                    // 留空是合法的：表示彻底不上报
                    val t = statsUrlDraft.replace(Regex("\\s+"), "")
                    DuoState.statsUrl = t
                    DuoState.save()
                    editStatsUrl = false
                    nav.toast(
                        if (t.isBlank()) Lang.s(R.string.stats_endpoint_cleared)
                        else Lang.s(R.string.toast_url_saved)
                    )
                })
            }
        }
    }

    if (showUpdateResult) {
        UpdateResultDialog(
            dark = dark,
            localVersion = ver,
            info = updateInfo,
            error = updateError,
            dlState = dlState,
            onDismiss = { cancelDownload(); showUpdateResult = false },
            onRetry = { showUpdateResult = false; doCheck(silent = false) },
            onDownload = {
                updateInfo?.let { rel ->
                    dlState = ApkUpdater.State.Idle
                    startDownload(rel)
                }
            },
            onCancelDownload = { cancelDownload() }
        )
    }

    pendingImport?.let { info ->
        DuoDialog(
            Lang.s(R.string.backup_import_confirm_title), dark, opaque = true,
            onDismiss = { pendingImport = null }
        ) {
            Text(
                Lang.s(R.string.backup_import_from, info.exportedAt, info.versionName),
                fontSize = 13.sp, color = glassTextColor(dark)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                Lang.s(
                    R.string.backup_import_summary, info.homeCount, info.sidebarCount,
                    info.folderCount, info.hiddenCount, info.widgetCount
                ),
                fontSize = 12.sp, color = glassSubTextColor(dark)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                Lang.s(R.string.backup_import_warn),
                fontSize = 12.sp, color = glassTextColor(dark)
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pendingImport = null }) {
                    Text(Lang.s(R.string.cancel), fontSize = 13.sp, color = glassSubTextColor(dark))
                }
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.backup_import_confirm), dark, onClick = {
                    val ok = BackupManager.apply(ctx, info)
                    pendingImport = null
                    if (ok) {
                        nav.toast(Lang.s(R.string.backup_import_ok))
                        ctx.asActivity()?.let { act ->
                            // 重建后仍停留在设置页，方便核对恢复结果；语言变化也随之生效
                            Lang.markReopenSettings(ctx)
                            act.recreate()
                        }
                    } else {
                        nav.toast(Lang.s(R.string.backup_import_fail))
                    }
                })
            }
        }
    }

}

/** 把 Release 说明里的 markdown 标记去掉，只留能读的正文 */
private fun cleanReleaseNotes(src: String): String = src
    .lineSequence()
    .map { it.trim() }
    .filter {
        it.isNotBlank() && it != "---" && !it.startsWith(">") &&
                !it.startsWith("**Full Changelog")
    }
    .map { line ->
        line.removePrefix("## ").removePrefix("# ")
            .replace(Regex("^[-*]\\s+"), "· ")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
    }
    .take(16)
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .take(700)

/** 检查结果弹窗：新版本 / 已是最新 / 检查失败三种形态 */
@Composable
private fun UpdateResultDialog(
    dark: Boolean,
    localVersion: String,
    info: UpdateChecker.ReleaseInfo?,
    error: String?,
    dlState: ApkUpdater.State,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val ctx = LocalContext.current
    val hasNew = info != null && UpdateChecker.isNewer(info.version, localVersion)
    DuoDialog(
        title = when {
            error != null -> Lang.s(R.string.update_check_failed)
            hasNew -> Lang.s(R.string.update_new_version, info?.version ?: "")
            else -> Lang.s(R.string.update_up_to_date)
        },
        dark = dark,
        level = 0.95f,
        opaque = true,
        onDismiss = onDismiss
    ) {
        if (error != null) {
            Text(error, fontSize = 13.sp, color = glassTextColor(dark))
            Spacer(Modifier.height(6.dp))
            Text(Lang.s(R.string.update_error_hint), fontSize = 12.sp, color = glassSubTextColor(dark))
            Spacer(Modifier.height(14.dp))
            Row {
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.retry), dark, onRetry)
            }
        } else if (info != null) {
            val rel = info
            Text(Lang.s(R.string.update_versions, localVersion, rel.version), fontSize = 13.sp, color = glassTextColor(dark))
            if (rel.publishedAt.isNotBlank()) {
                Text(Lang.s(R.string.update_published, rel.publishedAt), fontSize = 11.sp, color = glassSubTextColor(dark))
            }
            if (rel.apkName.isNotBlank()) {
                Text(
                    Lang.s(R.string.update_apk, rel.apkName) +
                            if (rel.sizeText.isNotBlank()) Lang.s(R.string.update_apk_size, rel.sizeText) else "",
                    fontSize = 11.sp, color = glassSubTextColor(dark)
                )
            }
            val notes = remember(rel.notes) { cleanReleaseNotes(rel.notes) }
            if (notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(notes, fontSize = 12.sp, lineHeight = 17.sp, color = glassTextColor(dark))
                }
            }
            val openPage = {
                val target = rel.pageUrl.ifBlank { rel.apkUrl }
                if (!UpdateChecker.openInBrowser(ctx, target)) {
                    UpdateChecker.copy(ctx, Lang.s(R.string.clip_page_link), target)
                }
                onDismiss()
            }

            Spacer(Modifier.height(14.dp))
            val st = dlState
            when (st) {
                is ApkUpdater.State.Downloading -> {
                    Text(
                        Lang.s(R.string.update_downloading),
                        fontSize = 12.sp, color = glassTextColor(dark)
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (st.total > 0) Lang.s(
                            R.string.update_download_progress,
                            "%.1f".format(st.downloaded / 1024f / 1024f),
                            "%.1f".format(st.total / 1024f / 1024f),
                            "${(st.fraction * 100).toInt()}%"
                        ) else Lang.s(
                            R.string.update_download_unknown,
                            "%.1f".format(st.downloaded / 1024f / 1024f)
                        ),
                        fontSize = 11.sp, color = glassSubTextColor(dark)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is ApkUpdater.State.Failed -> {
                    Text(st.reason, fontSize = 12.sp, color = Color(0xFFD65A5A))
                    Spacer(Modifier.height(8.dp))
                }
                else -> {
                    if (hasNew && rel.apkUrl.isNotBlank()) {
                        Text(
                            Lang.s(R.string.update_install_hint),
                            fontSize = 11.sp, color = glassSubTextColor(dark)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rel.apkUrl.isNotBlank()) {
                    TextButton(onClick = { UpdateChecker.copy(ctx, Lang.s(R.string.clip_apk_link), rel.apkUrl) }) {
                        Text(Lang.s(R.string.copy_link), fontSize = 12.sp, color = glassSubTextColor(dark))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (st is ApkUpdater.State.Downloading) {
                    TextButton(onClick = onCancelDownload) {
                        Text(Lang.s(R.string.update_download_cancel), fontSize = 12.sp, color = glassSubTextColor(dark))
                    }
                } else {
                    val canDownload = hasNew && rel.apkUrl.isNotBlank()
                    if (canDownload) {
                        TextButton(onClick = openPage) {
                            Text(Lang.s(R.string.open_download_page), fontSize = 12.sp, color = glassSubTextColor(dark))
                        }
                        GlassPrimaryButton(
                            if (st is ApkUpdater.State.Failed) Lang.s(R.string.retry)
                            else Lang.s(R.string.update_download_install),
                            dark, onDownload
                        )
                    } else {
                        GlassPrimaryButton(Lang.s(R.string.open_download_page), dark, onClick = openPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    dark: Boolean,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(title, fontSize = 15.sp, color = glassTextColor(dark), modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = glassSubTextColor(dark))
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = glassBlue,
                inactiveTrackColor = glassTextColor(dark).copy(alpha = 0.18f)
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

@Composable
private fun SubPageFrame(title: String, dark: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height((DuoState.cutoutPadding.coerceAtLeast(56)).dp))
        GlassTopBar(title, dark, onBack)
        content()
    }
}

/** 侧边栏编辑：条目排序 + 添加应用 / 添加操作 */
@Composable
private fun SidebarEditorScreen(dark: Boolean, onBack: () -> Unit) {
    var pickApp by remember { mutableStateOf(false) }
    var pickAction by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val items = DuoState.sidebar.toList()
    val darkFlag = dark

    SubPageFrame(Lang.s(R.string.edit_sidebar), dark, onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GlassPrimaryButton(Lang.s(R.string.add_app), dark, { pickApp = true }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            GlassPrimaryButton(Lang.s(R.string.add_action), dark, { pickAction = true }, Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.key + items.indexOf(it) }) { item ->
                val index = items.indexOf(item)
                val label = if (item.type == "app") DuoState.appOf(item.value)?.label ?: item.value
                else DuoActions.of(item.value).label
                GlassCard(Modifier.fillMaxWidth().height(62.dp), radius = 18.dp, level = 0.85f, dark = darkFlag) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", fontSize = 13.sp, color = glassSubTextColor(darkFlag), modifier = Modifier.width(24.dp))
                        Text(label, fontSize = 15.sp, color = glassTextColor(darkFlag), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (index > 0) {
                                val l = DuoState.sidebar.toMutableList()
                                java.util.Collections.swap(l, index, index - 1); DuoState.setSidebar(l)
                            }
                        }) { Icon(Icons.Filled.ArrowUpward, Lang.s(R.string.move_up), tint = glassSubTextColor(darkFlag), modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            if (index < DuoState.sidebar.size - 1) {
                                val l = DuoState.sidebar.toMutableList()
                                java.util.Collections.swap(l, index, index + 1); DuoState.setSidebar(l)
                            }
                        }) { Icon(Icons.Filled.ArrowDownward, Lang.s(R.string.move_down), tint = glassSubTextColor(darkFlag), modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            DuoState.setSidebar(DuoState.sidebar.filterNot { it.key == item.key })
                        }) { Icon(Icons.Filled.Delete, Lang.s(R.string.remove), tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }

    if (pickApp) {
        AppPickerDialog(
            title = Lang.s(R.string.add_app_to_sidebar), dark = dark, query = query, onQuery = { query = it },
            onDismiss = { pickApp = false; query = "" },
            onPick = { pkg ->
                DuoState.sidebar.add(SidebarItem("app", pkg)); DuoState.save(); pickApp = false; query = ""
            }
        )
    }
    if (pickAction) {
        DuoDialog(Lang.s(R.string.add_action_button), dark, onDismiss = { pickAction = false }) {
            DuoActions.ALL.forEach { a ->
                MenuRow(a.icon, a.label, dark = dark) {
                    DuoState.sidebar.add(SidebarItem("action", a.id)); DuoState.save(); pickAction = false
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    dark: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val apps = DuoState.visibleApps().filter {
        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
    }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), radius = 26.dp, level = 0.9f, dark = dark) {
            Column(Modifier.padding(18.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth().height(40.dp), radius = 12.dp, level = 0.8f, dark = dark) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(Lang.s(R.string.search_apps), fontSize = 13.sp, color = glassSubTextColor(dark))
                        BasicTextField(
                            query, onQuery, singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = glassTextColor(dark)),
                            cursorBrush = SolidColor(glassBlue), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(app.id) }
                                .padding(vertical = 9.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = rememberDrawablePainter(rememberAppIcon(app)), contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(squircle(24))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 14.sp, color = glassTextColor(dark), maxLines = 1)
                                Text(app.packageName, fontSize = 10.sp, color = glassSubTextColor(dark), maxLines = 1)
                            }
                            Icon(Icons.Filled.Add, null, tint = glassSubTextColor(dark), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 分类管理：逐应用指定分类 */
@Composable
private fun CategoryEditorScreen(dark: Boolean, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppInfo?>(null) }
    val apps = DuoState.apps.filter { it.packageName != DuoState.selfPackage }
        .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }

    SubPageFrame(Lang.s(R.string.edit_app_category), dark, onBack) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp), radius = 14.dp, level = 0.85f, dark = dark) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(Lang.s(R.string.search_apps), fontSize = 13.sp, color = glassSubTextColor(dark))
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = glassTextColor(dark)),
                    cursorBrush = SolidColor(glassBlue), modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            items(apps, key = { it.id }) { app ->
                GlassCard(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 5.dp), radius = 16.dp, level = 0.8f, dark = dark) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(rememberDrawablePainter(rememberAppIcon(app)), null, Modifier.size(34.dp).clip(squircle(24)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, color = glassTextColor(dark), maxLines = 1)
                            Text(
                                if (DuoState.categoryOverride.containsKey(app.id)) Lang.s(R.string.manual_category)
                                else if (DuoState.smartCategory) Lang.s(R.string.smart_category_switch) else Lang.s(R.string.uncategorized),
                                fontSize = 10.sp, color = glassSubTextColor(dark)
                            )
                        }
                        Text(categoryName(DuoState.categoryOf(app)), fontSize = 12.sp, color = glassBlue)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark), modifier = Modifier.size(18.dp)
                            .clickable { editing = app })
                    }
                }
            }
        }
    }

    editing?.let { app ->
        DuoDialog(Lang.s(R.string.cat_of_app, app.label), dark, onDismiss = { editing = null }) {
            MenuRow(Icons.Filled.AutoAwesome, Lang.s(R.string.follow_smart_category), dark = dark) {
                DuoState.setCategory(app.id, null); editing = null
            }
            CATEGORY_LIST.forEach { (id, nameRes) ->
                MenuRow(Icons.Filled.Category, Lang.s(nameRes), dark = dark) {
                    DuoState.setCategory(app.id, id); editing = null
                }
            }
        }
    }
}

/** 隐藏应用管理 */
@Composable
private fun HiddenEditorScreen(dark: Boolean, onBack: () -> Unit) {
    var pickApp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    SubPageFrame(Lang.s(R.string.hide_app), dark, onBack) {
        GlassPrimaryButton(Lang.s(R.string.hide_one_app), dark, { pickApp = true },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        if (DuoState.hidden.isEmpty()) {
            Text(Lang.s(R.string.no_hidden_apps), fontSize = 13.sp, color = glassSubTextColor(dark),
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DuoState.hidden.toList(), key = { it }) { pkg ->
                val app = DuoState.appOf(pkg)
                GlassCard(Modifier.fillMaxWidth().height(58.dp), radius = 16.dp, level = 0.8f, dark = dark) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (app != null) Image(rememberDrawablePainter(rememberAppIcon(app)), null, Modifier.size(32.dp).clip(squircle(24)))
                        Spacer(Modifier.width(12.dp))
                        Text(app?.label ?: pkg, fontSize = 14.sp, color = glassTextColor(dark), modifier = Modifier.weight(1f))
                        Text(Lang.s(R.string.unhide), fontSize = 12.sp, color = glassBlue,
                            modifier = Modifier.clickable { DuoState.unhideApp(pkg) })
                    }
                }
            }
        }
    }
    if (pickApp) {
        AppPickerDialog(Lang.s(R.string.hide_app), dark, query, { query = it }, { pickApp = false; query = "" }) { pkg ->
            DuoState.hideApp(pkg); pickApp = false; query = ""
        }
    }
}

/**
 * 桌面图标批量编辑：添加与清除共用一套 UI，只差候选项、文案和落盘动作。
 *
 * 刻意**不放搜索输入框** —— 设置浮层里挂输入框会重新引入第三方输入法会话残留
 * 的问题（搜索页 / 资源库那轮刚修好），这里靠「全选 / 反选 + 滚动列表」足够。
 *
 * 清除属于破坏性操作（虽然不卸载应用），比添加多一步确认弹窗。
 */
private enum class BatchMode { ADD, REMOVE }

@Composable
private fun BatchEditScreen(mode: BatchMode, nav: Nav, dark: Boolean, onBack: () -> Unit) {
    val adding = mode == BatchMode.ADD
    // 直接读 state list：应用列表异步加载完成、或主屏改动后会自动重组
    val candidates = if (adding) {
        DuoState.visibleApps()
            .filter { !DuoState.homeApps.contains(it.id) }
            .sortedBy { it.label.lowercase() }
    } else {
        DuoState.homeApps.mapNotNull { DuoState.appOf(it) }
            .filter { !DuoState.hidden.contains(it.id) }
            .sortedBy { it.label.lowercase() }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var askConfirm by remember { mutableStateOf(false) }
    val allIds = candidates.map { it.id }.toSet()
    // 候选列表变了（例如应用被卸载）时，把已失效的勾选项自动过滤掉
    val active = selected.intersect(allIds)

    SubPageFrame(
        Lang.s(if (adding) R.string.batch_add_title else R.string.batch_remove_title),
        dark, onBack
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Lang.s(R.string.batch_add_selected, active.size),
                fontSize = 13.sp, color = glassSubTextColor(dark),
                modifier = Modifier.weight(1f)
            )
            Text(
                Lang.s(R.string.batch_add_select_all), fontSize = 13.sp, color = glassBlue,
                modifier = Modifier.clickable { selected = allIds }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                Lang.s(R.string.batch_add_invert), fontSize = 13.sp, color = glassBlue,
                modifier = Modifier.clickable { selected = allIds - active }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        if (!adding) {
            Text(
                Lang.s(R.string.batch_remove_note),
                fontSize = 11.sp, color = glassSubTextColor(dark),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }
        if (candidates.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    Lang.s(if (adding) R.string.batch_add_empty else R.string.batch_remove_empty),
                    fontSize = 14.sp, color = glassSubTextColor(dark)
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(candidates, key = { it.id }) { app ->
                    val checked = active.contains(app.id)
                    GlassCard(
                        Modifier.fillMaxWidth().height(60.dp),
                        radius = 16.dp, level = 0.8f, dark = dark
                    ) {
                        Row(
                            Modifier.fillMaxSize()
                                .clickable {
                                    selected = if (checked) active - app.id
                                    else active + setOf(app.id)
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                rememberDrawablePainter(rememberAppIcon(app)), null,
                                Modifier.size(32.dp).clip(squircle(24))
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                app.label, fontSize = 14.sp, color = glassTextColor(dark),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    selected = if (on) active + setOf(app.id) else active - app.id
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = glassBlue,
                                    uncheckedColor = glassSubTextColor(dark),
                                    checkmarkColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }
        GlassPrimaryButton(
            Lang.s(
                if (adding) R.string.batch_add_confirm else R.string.batch_remove_confirm,
                active.size
            ),
            dark,
            {
                if (active.isEmpty()) return@GlassPrimaryButton
                if (adding) {
                    val n = active.size
                    DuoState.addHomeBatch(active)
                    nav.toast(Lang.s(R.string.batch_add_done, n))
                    onBack()
                } else {
                    askConfirm = true
                }
            },
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (active.isEmpty()) 0.5f else 1f)
        )
    }

    // 清除前确认一次：避免误触「全选 → 移除」把桌面清空
    if (askConfirm) {
        DuoDialog(
            Lang.s(R.string.batch_remove_dialog_title), dark, opaque = true,
            onDismiss = { askConfirm = false }
        ) {
            Text(
                Lang.s(R.string.batch_remove_dialog_body, active.size),
                fontSize = 13.sp, color = glassTextColor(dark)
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { askConfirm = false }) {
                    Text(Lang.s(R.string.cancel), fontSize = 13.sp, color = glassSubTextColor(dark))
                }
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.batch_remove_ok), dark, onClick = {
                    val n = active.size
                    DuoState.removeHomeBatch(active)
                    askConfirm = false
                    nav.toast(Lang.s(R.string.batch_remove_done, n))
                    onBack()
                })
            }
        }
    }
}

/** 角标可选颜色：iOS 系统色为主，够用且互不撞色 */
private val BADGE_COLORS = listOf(
    R.string.badge_red to DuoState.IOS_RED,
    R.string.badge_pink to 0xFFFF2D55.toInt(),
    R.string.badge_orange to 0xFFFF9500.toInt(),
    R.string.badge_green to 0xFF34C759.toInt(),
    R.string.badge_blue to 0xFF007AFF.toInt(),
    R.string.badge_purple to 0xFFAF52DE.toInt(),
    R.string.badge_graphite to 0xFF48484A.toInt(),
    R.string.badge_black to 0xFF1C1C1E.toInt()
)

/** 角标颜色选择：一排圆形色块，选中的显示白勾 */
@Composable
private fun BadgeColorRow(dark: Boolean, current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Lang.s(R.string.badge_color), fontSize = 15.sp, color = glassTextColor(dark), modifier = Modifier.weight(1f))
            Text(
                BADGE_COLORS.firstOrNull { it.second == current }?.first?.let { Lang.s(it) }
                    ?: Lang.s(R.string.color_custom),
                fontSize = 12.sp, color = glassSubTextColor(dark)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BADGE_COLORS.forEach { (nameRes, argb) ->
                val on = argb == current
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (on) 2.5.dp else 1.dp,
                            color = glassTextColor(dark).copy(alpha = if (on) 0.55f else 0.15f),
                            shape = CircleShape
                        )
                        .clickable { onPick(argb) },
                    contentAlignment = Alignment.Center
                ) {
                    if (on) Icon(Icons.Filled.Check, Lang.s(nameRes), tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
