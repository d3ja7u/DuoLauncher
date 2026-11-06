package com.duolauncher.ui

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
import com.duolauncher.data.*
import com.duolauncher.data.IconPacks
import com.duolauncher.service.DuoNotificationListener
import com.duolauncher.util.ScreenOff

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

@Composable
private fun MainSettings(nav: Nav, dark: Boolean, onClose: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var pickIconPack by remember { mutableStateOf(false) }
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
            GlassTopBar("DuoLauncher 设置", dark, onClose)
        }
        item {
            SectionTitle("侧边栏", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow("显示位置", if (DuoState.sidebarLeft) "左侧（自动避开摄像头挖孔）" else "右侧", dark,
                        trailing = {
                            Row {
                                listOf(false to "右", true to "左").forEach { (v, label) ->
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
                    SettingRow("编辑侧边栏", "添加常用应用或操作按键，调整顺序", dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("sidebar") })
                    SettingSliderRow("挖孔避让高度", "${DuoState.cutoutPadding} dp", DuoState.cutoutPadding.toFloat(),
                        40f..180f, dark) { DuoState.cutoutPadding = it.toInt(); DuoState.save() }
                    SettingSliderRow("主屏图标大小", "${"%.1f".format(DuoState.iconScale)}×", DuoState.iconScale,
                        0.7f..1.4f, dark) { DuoState.iconScale = it; DuoState.save() }
                    SettingSliderRow("侧栏图标大小", "${"%.1f".format(DuoState.sidebarIconScale)}×", DuoState.sidebarIconScale,
                        0.6f..1.1f, dark) { DuoState.sidebarIconScale = it; DuoState.save() }
                }
            }
        }
        item {
            SectionTitle("iOS 风格状态栏", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle("显示时间与弧形信号", "09:41 + 圆弧 WiFi / 信号点，替代安卓原生状态栏",
                        DuoState.showDuoStatus, dark) { DuoState.showDuoStatus = it; DuoState.save() }
                    SettingRow("显示位置", if (DuoState.statusRight) "右上（避开左上挖孔）" else "左上（侧栏同侧时自动下移避让）", dark,
                        trailing = {
                            Row {
                                listOf(true to "右", false to "左").forEach { (v, label) ->
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
                    SettingToggle("24 小时制", "关闭后按 12 小时制显示", DuoState.status24h, dark) {
                        DuoState.status24h = it; DuoState.save()
                    }
                    SettingToggle("信号点跟随真实强度", "关闭时 4 点常亮，与概念图一致",
                        DuoState.statusRealSignal, dark) { DuoState.statusRealSignal = it; DuoState.save() }
                    SettingSliderRow("距顶部高度", "${DuoState.statusTop} dp", DuoState.statusTop.toFloat(),
                        8f..140f, dark) { DuoState.statusTop = it.toInt(); DuoState.save() }
                    SettingSliderRow("状态栏大小", "${"%.1f".format(DuoState.statusScale)}×", DuoState.statusScale,
                        0.7f..1.6f, dark) { DuoState.statusScale = it; DuoState.save() }
                    val st = rememberDuoStatus(DuoState.status24h).value
                    SettingRow(
                        "实时状态",
                        "${st.time} · " + when {
                            st.wifi -> "Wi-Fi（信号 ${st.level}/4）"
                            st.cellular -> "移动网络（${st.level}/4）"
                            else -> "无网络"
                        } + " · 电量 ${st.battery}%${if (st.charging) " 充电中" else ""}",
                        dark
                    )
                }
            }
        }
        item {
            SectionTitle("主屏幕与手势", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle("显示安卓原生状态栏", "默认关闭；开启会与自定义状态栏叠加",
                        !DuoState.hideStatusBar, dark) {
                        DuoState.hideStatusBar = !it; DuoState.save(); nav.toast("重启启动器后完全生效")
                    }
                    SettingToggle("显示页面指示点", null, DuoState.showPageDots, dark) { DuoState.showPageDots = it; DuoState.save() }
                    SettingToggle("反转左右滑页方向", "开启后资源库出现在左边",
                        DuoState.reversePages, dark) { DuoState.reversePages = it; DuoState.save() }
                    SettingToggle(
                        "双击桌面熄屏",
                        if (accReady) "已开启无障碍熄屏服务"
                        else "首次使用需在「无障碍」中开启 DuoLauncher 熄屏服务（仅锁屏）",
                        DuoState.doubleTapSleep, dark
                    ) { on ->
                        DuoState.doubleTapSleep = on; DuoState.save()
                        if (on && !accReady) ScreenOff.requestPermission(ctx)
                    }
                    SettingToggle(
                        "下滑打开通知栏 / 控制中心",
                        "左半屏下滑出通知栏，右半屏下滑出控制中心；与左右翻页互不影响",
                        DuoState.gesturePanels, dark
                    ) { on -> DuoState.gesturePanels = on; DuoState.save() }
                    SettingToggle(
                        "右滑打开搜索",
                        "第一页桌面右滑呼出搜索；其它页右滑仍是翻回上一页",
                        DuoState.gestureSearch, dark
                    ) { on -> DuoState.gestureSearch = on; DuoState.save() }
                }
            }
        }
        item {
            SectionTitle("App 资源库", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle("智能分类", "按应用类型自动归类；关闭后统一放入全部应用",
                        DuoState.smartCategory, dark) { DuoState.smartCategory = it; DuoState.save() }
                    SettingRow("调整应用分类", "手动为每个应用指定分类", dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("cats") })
                    SettingRow("隐藏应用", "管理已隐藏的应用（${DuoState.hidden.size}）", dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { open("hidden") })
                }
            }
        }
        item {
            SectionTitle("外观", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingSliderRow("玻璃模糊强度", "${(DuoState.glassLevel * 100).toInt()}%",
                        DuoState.glassLevel, 0f..1f, dark) { DuoState.glassLevel = it; DuoState.save() }
                    SettingToggle("深色玻璃", "深色壁纸上更清晰", DuoState.darkGlass, dark) {
                        DuoState.darkGlass = it; DuoState.save()
                    }
                }
            }
        }
        item {
            SectionTitle("图标包", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow(
                        "当前图标包",
                        if (DuoState.iconPack.isBlank()) "系统默认" else packs.firstOrNull { it.pkg == DuoState.iconPack }?.label ?: DuoState.iconPack,
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { pickIconPack = true }
                    )
                }
            }
            Text(
                "图标包没匹配上的应用，长按桌面图标 → 更换图标，可从包里手动挑一个",
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle("通知角标", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingToggle(
                        "显示未读角标",
                        if (badgeAccess) "图标右上角显示未读数字，打开应用后自动清除"
                        else "需先授予「通知使用权」，否则角标不会出现",
                        DuoState.showBadges, dark
                    ) { on ->
                        DuoState.showBadges = on; DuoState.save()
                        if (on && !badgeAccess) DuoNotificationListener.openSettings(ctx)
                    }
                    BadgeColorRow(dark, DuoState.badgeColor) { argb ->
                        DuoState.badgeColor = argb; DuoState.save()
                    }
                    SettingRow(
                        "通知使用权",
                        if (badgeAccess) "已授权 · 通知栏有未读时会显示数字" else "未授权 · 点此前往系统设置开启",
                        dark,
                        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = glassSubTextColor(dark)) },
                        onClick = { DuoNotificationListener.openSettings(ctx) }
                    )
                }
            }
            Text(
                "角标数字按通知条数统计；微信、QQ 这类会合并通知的应用，取通知里自带的消息条数",
                fontSize = 11.sp,
                color = glassSubTextColor(dark),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
            )
        }
        item {
            SectionTitle("系统", dark)
            GlassCard(Modifier.fillMaxWidth().wrapContentHeight(), radius = 24.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingRow("设为默认桌面", "在系统设置中选择 DuoLauncher", dark) {
                        AppRepository.setDefaultLauncher(ctx)
                    }
                    SettingRow("更换壁纸", dark = dark) { AppRepository.wallpaper(ctx) }
                    SettingRow("DuoLauncher 应用信息", dark = dark) {
                        AppRepository.appDetails(ctx, ctx.packageName)
                    }
                    // 版本号直接从包信息读，避免每次发版忘了改这里的硬编码
                    val ver = remember {
                        runCatching {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    SettingRow("关于", "DuoLauncher $ver · 为 Titan 2 Elite 打造", dark = dark)
                }
            }
        }
    }

    if (pickIconPack) {
        Dialog(onDismissRequest = { pickIconPack = false }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 540.dp), radius = 26.dp, level = 0.9f, dark = dark) {
                Column(Modifier.padding(18.dp)) {
                    Text("选择图标包", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
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
                            Text("系统默认", fontSize = 15.sp, color = glassTextColor(dark))
                        }
                        Spacer(Modifier.height(6.dp))
                        if (packs.isEmpty()) {
                            Text("未检测到已安装的图标包", fontSize = 13.sp, color = glassSubTextColor(dark),
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

    SubPageFrame("编辑侧边栏", dark, onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GlassPrimaryButton("添加应用", dark, { pickApp = true }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            GlassPrimaryButton("添加操作", dark, { pickAction = true }, Modifier.weight(1f))
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
                        }) { Icon(Icons.Filled.ArrowUpward, "上移", tint = glassSubTextColor(darkFlag), modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            if (index < DuoState.sidebar.size - 1) {
                                val l = DuoState.sidebar.toMutableList()
                                java.util.Collections.swap(l, index, index + 1); DuoState.setSidebar(l)
                            }
                        }) { Icon(Icons.Filled.ArrowDownward, "下移", tint = glassSubTextColor(darkFlag), modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            DuoState.setSidebar(DuoState.sidebar.filterNot { it.key == item.key })
                        }) { Icon(Icons.Filled.Delete, "移除", tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }

    if (pickApp) {
        AppPickerDialog(
            title = "添加应用到侧边栏", dark = dark, query = query, onQuery = { query = it },
            onDismiss = { pickApp = false; query = "" },
            onPick = { pkg ->
                DuoState.sidebar.add(SidebarItem("app", pkg)); DuoState.save(); pickApp = false; query = ""
            }
        )
    }
    if (pickAction) {
        DuoDialog("添加操作按键", dark, onDismiss = { pickAction = false }) {
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
                        if (query.isEmpty()) Text("搜索应用", fontSize = 13.sp, color = glassSubTextColor(dark))
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

    SubPageFrame("调整应用分类", dark, onBack) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp), radius = 14.dp, level = 0.85f, dark = dark) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("搜索应用", fontSize = 13.sp, color = glassSubTextColor(dark))
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
                                if (DuoState.categoryOverride.containsKey(app.packageName)) "手动指定"
                                else if (DuoState.smartCategory) "智能分类" else "未分类",
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
        DuoDialog("${app.label} 的分类", dark, onDismiss = { editing = null }) {
            MenuRow(Icons.Filled.AutoAwesome, "跟随智能分类", dark = dark) {
                DuoState.setCategory(app.packageName, null); editing = null
            }
            CATEGORY_LIST.forEach { (id, name) ->
                MenuRow(Icons.Filled.Category, name, dark = dark) {
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
    SubPageFrame("隐藏应用", dark, onBack) {
        GlassPrimaryButton("选择一个应用隐藏", dark, { pickApp = true },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        if (DuoState.hidden.isEmpty()) {
            Text("当前没有隐藏的应用", fontSize = 13.sp, color = glassSubTextColor(dark),
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
                        Text("取消隐藏", fontSize = 12.sp, color = glassBlue,
                            modifier = Modifier.clickable { DuoState.unhideApp(pkg) })
                    }
                }
            }
        }
    }
    if (pickApp) {
        AppPickerDialog("隐藏应用", dark, query, { query = it }, { pickApp = false; query = "" }) { pkg ->
            DuoState.hideApp(pkg); pickApp = false; query = ""
        }
    }
}

/** 角标可选颜色：iOS 系统色为主，够用且互不撞色 */
private val BADGE_COLORS = listOf(
    "红色" to DuoState.IOS_RED,
    "粉色" to 0xFFFF2D55.toInt(),
    "橙色" to 0xFFFF9500.toInt(),
    "绿色" to 0xFF34C759.toInt(),
    "蓝色" to 0xFF007AFF.toInt(),
    "紫色" to 0xFFAF52DE.toInt(),
    "石墨" to 0xFF48484A.toInt(),
    "黑色" to 0xFF1C1C1E.toInt()
)

/** 角标颜色选择：一排圆形色块，选中的显示白勾 */
@Composable
private fun BadgeColorRow(dark: Boolean, current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("角标颜色", fontSize = 15.sp, color = glassTextColor(dark), modifier = Modifier.weight(1f))
            Text(
                BADGE_COLORS.firstOrNull { it.second == current }?.first ?: "自定义",
                fontSize = 12.sp, color = glassSubTextColor(dark)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BADGE_COLORS.forEach { (name, argb) ->
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
                    if (on) Icon(Icons.Filled.Check, name, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
