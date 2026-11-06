package com.duolauncher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.duolauncher.util.ScreenOff
import com.duolauncher.util.UpdateChecker
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

/** LocalContext 可能包了几层 ContextWrapper，逐层找到 Activity 才能 recreate() */
private fun Context.asActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun MainSettings(nav: Nav, dark: Boolean, onClose: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver = remember { UpdateChecker.currentVersion(ctx) }

    var pickIconPack by remember { mutableStateOf(false) }
    var editUpdateUrl by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    var langChoice by remember { mutableStateOf(Lang.get(ctx)) }
    var urlDraft by remember(DuoState.updateUrl) { mutableStateOf(DuoState.updateUrl) }
    var checking by remember { mutableStateOf(false) }
    var showUpdateResult by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

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
                    updateInfo = info
                    updateError = null
                    showUpdateResult = true
                }
            }.onFailure { e ->
                if (!silent) {
                    updateInfo = null
                    updateError = e.message ?: Lang.s(R.string.update_check_failed)
                    showUpdateResult = true
                }
            }
        }
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
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accReady = ScreenOff.isEnabled(ctx)
                badgeAccess = DuoNotificationListener.hasAccess(ctx)
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
                    SettingToggle(Lang.s(R.string.status_real_signal), Lang.s(R.string.status_real_signal_desc),
                        DuoState.statusRealSignal, dark) { DuoState.statusRealSignal = it; DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.status_top_padding), "${DuoState.statusTop} dp", DuoState.statusTop.toFloat(),
                        8f..140f, dark) { DuoState.statusTop = it.toInt(); DuoState.save() }
                    SettingSliderRow(Lang.s(R.string.status_scale), "${"%.1f".format(DuoState.statusScale)}×", DuoState.statusScale,
                        0.7f..1.6f, dark) { DuoState.statusScale = it; DuoState.save() }
                    val st = rememberDuoStatus(DuoState.status24h).value
                    SettingRow(
                        Lang.s(R.string.live_status),
                        "${st.time} · " + when {
                            st.wifi -> Lang.s(R.string.status_wifi, st.level)
                            st.cellular -> Lang.s(R.string.status_cellular, st.level)
                            else -> Lang.s(R.string.status_no_network)
                        } + Lang.s(R.string.status_battery, st.battery) + if (st.charging) " " + Lang.s(R.string.charging) else "",
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

    if (showUpdateResult) {
        UpdateResultDialog(
            dark = dark,
            localVersion = ver,
            info = updateInfo,
            error = updateError,
            onDismiss = { showUpdateResult = false },
            onRetry = { showUpdateResult = false; doCheck(silent = false) }
        )
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
    onDismiss: () -> Unit,
    onRetry: () -> Unit
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
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rel.apkUrl.isNotBlank()) {
                    TextButton(onClick = { UpdateChecker.copy(ctx, Lang.s(R.string.clip_apk_link), rel.apkUrl) }) {
                        Text(Lang.s(R.string.copy_link), fontSize = 12.sp, color = glassSubTextColor(dark))
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassPrimaryButton(Lang.s(R.string.open_download_page), dark, onClick = {
                    val target = rel.pageUrl.ifBlank { rel.apkUrl }
                    if (!UpdateChecker.openInBrowser(ctx, target)) {
                        UpdateChecker.copy(ctx, Lang.s(R.string.clip_page_link), target)
                    }
                    onDismiss()
                })
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
                                .clickable { onPick(app.packageName) }
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
            items(apps, key = { it.packageName }) { app ->
                GlassCard(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 5.dp), radius = 16.dp, level = 0.8f, dark = dark) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(rememberDrawablePainter(rememberAppIcon(app)), null, Modifier.size(34.dp).clip(squircle(24)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, color = glassTextColor(dark), maxLines = 1)
                            Text(
                                if (DuoState.categoryOverride.containsKey(app.packageName)) Lang.s(R.string.manual_category)
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
                DuoState.setCategory(app.packageName, null); editing = null
            }
            CATEGORY_LIST.forEach { (id, nameRes) ->
                MenuRow(Icons.Filled.Category, Lang.s(nameRes), dark = dark) {
                    DuoState.setCategory(app.packageName, id); editing = null
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
