package com.duolauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.util.SystemPanel
import com.duolauncher.util.WallpaperRotator
import kotlin.math.roundToInt
import com.duolauncher.data.AppInfo
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoActions
import com.duolauncher.data.DuoState
import com.duolauncher.data.FolderItem
import com.duolauncher.data.IconPacks
import com.duolauncher.data.NowPlaying
import com.duolauncher.data.SidebarItem
import com.duolauncher.service.DuoNotificationListener
import com.duolauncher.util.ScreenOff

@Composable
fun HomePage(
    nav: Nav,
    dark: Boolean,
    wallpaper: Bitmap?,
    pageDots: Boolean,
    homePageIndex: Int = 0,
    homePageCount: Int = 1,
    libraryPage: Int = 2,
    /** 这是紧挨搜索页的主屏第一页（关闭「右滑进搜索」时，在这里拦住 pager） */
    allowSwipeRight: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editMode by remember { mutableStateOf(false) }
    var sidebarMenu by remember { mutableStateOf<SidebarItem?>(null) }
    var showHomeMenu by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<FolderItem?>(null) }
    /** 编辑态点「-」后待确认移除的应用 */
    var removeTarget by remember { mutableStateOf<AppInfo?>(null) }

    val packPkg = DuoState.iconPack

    val iconScale = DuoState.iconScale
    val homeIconSize = (54 * iconScale).dp          // 主屏图标：比侧栏大
    // 侧栏图标自动收敛尺寸：数量多或用户把图标放大时，保证整条侧栏不超 430dp、
    // 图标之间不重合（否则被裁切/挤压后会叠在一起）
    val sidebarItemN = DuoState.sidebar.size.coerceAtMost(9)
    val sidebarBaseSize = 54f * DuoState.sidebarIconScale
    val sbGapF = if (sidebarItemN >= 8) 5f else 9f
    val sbAvail = 430f - 14f - (sidebarItemN - 1).coerceAtLeast(0) * sbGapF
    val sbMaxByH = if (sidebarItemN > 0) sbAvail / sidebarItemN else sidebarBaseSize
    val sidebarIconSize = minOf(sidebarBaseSize, sbMaxByH).dp
    val cellWidth = homeIconSize + 14.dp
    val sidebarWidth = sidebarIconSize + 24.dp
    val sidebarGap = 10.dp
    val lane = sidebarGap + sidebarWidth
    // ── 主屏拖拽排序（长按拿起 → 拖动 → 松手落位）──
    val haptic = LocalHapticFeedback.current
    var dragIndex by remember { mutableStateOf<Int?>(null) }        // 当前页内的拖动下标
    var hoverIndex by remember { mutableStateOf<Int?>(null) }       // 当前页内的落点下标
    var dragFromRect by remember { mutableStateOf(Rect.Zero) }      // 拿起时图标在根坐标系的位置
    var dragDelta by remember { mutableStateOf(Offset.Zero) }       // 累计拖动位移
    var dragFinger by remember { mutableStateOf(Offset.Zero) }       // 手指在根坐标系的位置
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }      // 本页在根坐标系的原点
    val statusTopPad = if (DuoState.statusRight) DuoState.statusTop
    else maxOf(DuoState.statusTop, 64)
    // 时间 + 电量三合一再往上提：只挪绘制位置，网格 topGap 仍用 statusTopPad，不动图标行距。
    // 侧栏「靠下居中」的基准也用这个真实下沿（见下方 sbRegionTop），两者始终对齐。
    val statusDrawTop = (statusTopPad - 20f).coerceAtLeast(2f)
    val statusScale = DuoState.statusScale.coerceIn(0.55f, 1.8f)
    // 状态块真实高度：时间(≈1.35×字号) + 6dp 间距 + 徽标 50dp
    val statusHeight = (18f * 1.35f + 6f + 50f) * statusScale
    val topGap = if (DuoState.showDuoStatus)
        maxOf(DuoState.cutoutPadding.coerceAtLeast(64) + 64f, statusTopPad + statusHeight + 10f).dp
    else (DuoState.cutoutPadding.coerceAtLeast(64) + 24f).dp
    val leftSide = DuoState.sidebarLeft
    // 侧栏改为垂直居中（见下方 GlassCard 的 align）：框始终在屏幕竖直中线，
    // 应用数量少时整条偏短、居中；数量多时对称变长，不再贴顶或贴底。
    val sidebarMaxHeight = 430.dp
    // 纵向间距：图标多（8 个以上）时收紧，否则下方搜索键会顶到侧栏
    val sidebarItemGap = if (sidebarItemN >= 8) 5.dp else 9.dp
    // 侧边栏隐藏后，主屏网格不再为那条玻璃通道留白，左右都回到常规边距
    val showSidebar = DuoState.showSidebar
    val gridStart = if (!showSidebar) 14.dp else if (leftSide) lane else 14.dp
    val gridEnd = if (!showSidebar) 14.dp else if (leftSide) 14.dp else lane

    // 胶囊与侧栏共用同一条纵向通道：用实测高度判断两者是否撞上，
    // 撞上时把侧栏整体往下推（offset 只改摆放位置，不影响再次测量，不会死循环）
    var sbBoxHPx by remember { mutableStateOf(0) }
    var sbCardHPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // 主屏条目：按 DuoState.homeTokens() 的统一顺序（应用与文件夹可混排，支持拖拽排序）
    val entries: List<HomeEntry> = DuoState.homeTokens().mapNotNull { token ->
        when {
            token.startsWith("app:") -> DuoState.appOf(token.removePrefix("app:"))?.let { HomeEntry.App(it) }
            token.startsWith("folder:") -> DuoState.folderOf(token.removePrefix("folder:"))
                ?.let { HomeEntry.Folder(it) }
            else -> null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
            // 主屏空白处手势：双击息屏 + 长按进「启动器设置」，两个手势互不干扰
            // （双击在超时前等第二击；长按超过阈值即触发，并取消 pending taps）。
            // 图标自身的长按拖拽会先 consume 该 down 事件，所以长按图标不会误触发设置。
            .pointerInput(DuoState.doubleTapSleep) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!DuoState.doubleTapSleep) return@detectTapGestures
                        if (ScreenOff.lock()) {
                            // 已息屏
                        } else if (ScreenOff.isEnabled(ctx)) {
                            nav.toast(Lang.s(R.string.toast_sleep_starting))
                        } else {
                            ScreenOff.requestPermission(ctx)
                            nav.toast(Lang.s(R.string.toast_enable_accessibility))
                        }
                    },
                    onLongPress = {
                        if (!DuoState.dragging) nav.openSettings()
                    }
                )
            }
            // 竖直手势统一由一个检测器处理：下滑呼出系统面板（左半屏通知栏 / 右半屏控制中心），
            // 上滑切换壁纸。绝不能挂两个 detectVerticalDragGestures —— 它们会争抢同一批触摸
            // 事件，后挂的先拿到 down/slop，把下滑那个饿死（187 实测「左右下滑都失效」）。
            // 单个检测器按累计位移的正负分流，方向反转即清零，互不干扰。
            .pointerInput(DuoState.gesturePanels, DuoState.wallpaperSwipe) {
                val downEnabled = DuoState.gesturePanels
                val upEnabled = DuoState.wallpaperSwipe
                if (!downEnabled && !upEnabled) return@pointerInput
                // 96dp 在本机约 264px，几乎要划过大半屏；手指稍有回弹就功亏一篑，
                // 是「有时候上滑没反应」的主因之一。改取 72dp 与屏高 9% 的较小值。
                val threshold = minOf(72.dp.toPx(), size.height * 0.09f)
                var acc = 0f
                var startX = 0f
                var upFired = false
                fun switchWallpaper() {
                    if (WallpaperRotator.count(ctx) == 0) return
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = WallpaperRotator.rotateNow(ctx)
                        if (!ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            nav.toast(Lang.s(R.string.wp_toast_fail))
                        }
                    }
                }
                detectVerticalDragGestures(
                    onDragStart = { offset -> acc = 0f; startX = offset.x; upFired = false },
                    onDragCancel = {
                        // 手势被翻页 / 其它检测器取消时若已达阈值，仍补一次，避免偶发丢失
                        if (!upFired && upEnabled && acc <= -threshold) switchWallpaper()
                        acc = 0f
                    },
                    onDragEnd = {
                        val travelled = acc
                        acc = 0f
                        when {
                            travelled >= threshold && downEnabled -> {
                                val leftHalf = startX < size.width / 2f
                                val ok =
                                    if (leftHalf) SystemPanel.openNotifications(ctx)
                                    else SystemPanel.openQuickSettings(ctx)
                                if (!ok) {
                                    nav.toast(Lang.s(R.string.toast_enable_service))
                                    ScreenOff.requestPermission(ctx)
                                }
                            }
                            // 兜底：正常情况已在拖动过程中触发，这里防止极短促的一划漏掉
                            travelled <= -threshold && upEnabled && !upFired -> switchWallpaper()
                        }
                    }
                ) { change, dy ->
                    if (DuoState.dragging) {
                        acc = 0f
                    } else {
                        // 用净位移判定，不再「方向一反转就清零」：真人手指上滑常有轻微回弹，
                        // 旧逻辑一遇回弹就把累计值清掉，划够了距离也可能不触发。
                        // 净位移天然能区分「上滑后又下滑」（正负相抵），不会误判方向。
                        acc += dy
                        if (kotlin.math.abs(acc) > 16f) change.consume()
                        // 上滑一旦达到阈值就立即执行，不等松手：快速连续上滑时 up 事件
                        // 可能被后一次手势 / 翻页抢走，旧逻辑只在 onDragEnd 判定会偶发丢失。
                        // 下滑仍保持松手才弹面板，不改变原有手感。
                        if (upEnabled && !upFired && acc <= -threshold) {
                            upFired = true
                            switchWallpaper()
                        }
                    }
                }
            }
            // 「最左页右滑打开搜索」已经改由 HorizontalPager 原生翻页接管（搜索页本身就是
            // pager 的一页），过渡与滑到 App 资源库完全一致。这里只剩一个开关兜底：
            // 用户把该手势关掉时，在 Initial pass 消费掉「朝搜索页方向」的横向拖动，
            // pager 看到事件被消费就不会翻过去；点搜索键 / 侧栏动作仍可程序式进入。
            .pointerInput(DuoState.gestureSearch, DuoState.reversePages, allowSwipeRight) {
                if (DuoState.gestureSearch || !allowSwipeRight) return@pointerInput
                val startSlop = 14.dp.toPx()
                val towardSearch = if (DuoState.reversePages) -1f else 1f
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    var dx = 0f
                    var dy = 0f
                    var engaged = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        dx += ch.position.x - ch.previousPosition.x
                        dy += ch.position.y - ch.previousPosition.y
                        val towards = dx * towardSearch
                        if (!engaged && towards > startSlop && towards > kotlin.math.abs(dy) * 1.15f) {
                            engaged = true
                        }
                        if (engaged) ch.consume()
                        if (!ch.pressed) break
                    }
                }
            }
    ) {
        // ── 主屏：顶部状态指示让位 + 图标网格（N 行 × M 列，不滚动，左右翻页）──
        // 行数 / 列数均由「主屏图标列数 / 行数」设置决定，单页容量 = 列数 × 行数
        val homeCols = DuoState.homeColumns
        val homeRows = DuoState.homeRows
        val perPage = homeCols * homeRows
        val pageStart = homePageIndex * perPage
        val pageEntries = entries.drop(pageStart).take(perPage)
        val pageRows = pageEntries.chunked(homeCols)
        // 只在当前这页的成员变化时重建，避免残留上一轮（排序前）的旧矩形导致落点错位
        val cellRects = remember(pageEntries) { mutableStateMapOf<Int, Rect>() }
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = gridStart, end = gridEnd, top = topGap, bottom = 76.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(),
                // 满整页行数时均分铺满（顶部到底部）；不满页则从顶部顺排，避免图标被拉开
                verticalArrangement = if (pageRows.size >= homeRows) Arrangement.SpaceEvenly
                else Arrangement.spacedBy(16.dp * iconScale)
            ) {
                pageRows.forEachIndexed { rowIdx, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top
                    ) {
                        row.forEachIndexed { colIdx, entry ->
                            val idx = rowIdx * homeCols + colIdx            // 页内下标
                            val gIdx = pageStart + idx               // 全局下标
                            Box(
                                Modifier
                                    .onGloballyPositioned { cellRects[idx] = it.boundsInRoot() }
                                    .graphicsLayer { alpha = if (dragIndex == idx) 0.25f else 1f }
                                    .pointerInput(gIdx, entry) {
                                        // 自己实现「长按拿起 → 拖动 → 松手落位」：
                                        // 不能用 detectDragGesturesAfterLongPress —— 图标自带的
                                        // combinedClickable 会在 Main pass 消费掉 down，
                                        // 而系统的长按判定一看到"已被消费"就直接取消，永远拖不起来。
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val slop = viewConfiguration.touchSlop
                                            var lifted = false
                                            var escaped = false
                                            var done = false
                                            withTimeoutOrNull(
                                                viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes
                                                        .firstOrNull { it.id == down.id } ?: break
                                                    if (!ch.pressed) { lifted = true; break }
                                                    if ((ch.position - down.position)
                                                            .getDistance() > slop
                                                    ) { escaped = true; break }
                                                }
                                                done = true
                                            }
                                            if (!done && !lifted && !escaped) {
                                                // 长按成立 → 拿起
                                                val r = cellRects[idx] ?: Rect.Zero
                                                dragIndex = idx
                                                hoverIndex = idx
                                                dragFromRect = r
                                                dragDelta = Offset.Zero
                                                dragFinger = r.topLeft + down.position
                                                DuoState.dragging = true
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                                var moved = false
                                                try {
                                                    while (true) {
                                                        // 用 Initial pass 先拿到事件并立刻消费，
                                                        // 免得外面的翻页/下滑手势把拖拽抢走
                                                        val ev = awaitPointerEvent(
                                                            PointerEventPass.Initial
                                                        )
                                                        val ch = ev.changes
                                                            .firstOrNull { it.id == down.id } ?: break
                                                        if (!ch.pressed) { ch.consume(); break }
                                                        val d = ch.positionChangeIgnoreConsumed()
                                                        ch.consume()
                                                        if (d == Offset.Zero) continue
                                                        dragDelta += d
                                                        dragFinger += d
                                                        if (dragDelta.getDistance() > slop) moved = true
                                                        cellRects.entries
                                                            .firstOrNull {
                                                                it.value.contains(dragFinger)
                                                            }?.let { hoverIndex = it.key }
                                                    }
                                                } finally {
                                                    DuoState.dragging = false
                                                }
                                                val from = idx
                                                val to = hoverIndex
                                                dragIndex = null
                                                hoverIndex = null
                                                dragDelta = Offset.Zero
                                                if (moved) {
                                                    if (to != null && to != from) {
                                                        DuoState.moveHome(
                                                            pageStart + from, pageStart + to
                                                        )
                                                    }
                                                } else {
                                                    // 长按后没拖动就松手 → 当作长按，弹菜单
                                                    when (entry) {
                                                        is HomeEntry.App ->
                                                            nav.showAppMenu(entry.app, "home")
                                                        is HomeEntry.Folder ->
                                                            openFolder = entry.folder
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                // 落点高亮
                                if (dragIndex != null && dragIndex != idx && hoverIndex == idx) {
                                    Box(
                                        Modifier.matchParentSize().padding(2.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color.White.copy(alpha = 0.16f))
                                            .border(
                                                1.5.dp, Color.White.copy(alpha = 0.55f),
                                                RoundedCornerShape(18.dp)
                                            )
                                    )
                                }
                                when (entry) {
                                    is HomeEntry.App -> {
                                        val app = entry.app
                                        AppIconCell(
                                            app = app,
                                            size = homeIconSize,
                                            showLabel = true,
                                            onWallpaper = true,
                                            wobble = editMode,
                                            // 编辑态左上角显示 iOS 风格「-」，点它弹移除确认
                                            onMinusClick = if (editMode) ({ removeTarget = app }) else null,
                                            onClick = {
                                                if (editMode) nav.showAppMenu(app, "home")
                                                else AppRepository.launch(ctx, app.id)
                                            },
                                            // 长按交给上面的拖拽手势：拿起→拖动；长按不动→松手时弹菜单
                                            onLongClick = {}
                                        )
                                    }
                                    is HomeEntry.Folder -> {
                                        FolderCell(
                                            folder = entry.folder,
                                            size = homeIconSize,
                                            onWallpaper = true,
                                            wobble = editMode,
                                            onClick = { openFolder = entry.folder },
                                            onLongClick = {}
                                        )
                                    }
                                }
                            }
                        }
                        repeat(homeCols - row.size) { Spacer(Modifier.width(cellWidth)) }
                    }
                }
            }
        }

        // ── 拖动中的悬浮图标（画在网格之上）──
        val draggedIdx = dragIndex
        if (draggedIdx != null) {
            val dragged = pageEntries.getOrNull(draggedIdx)
            if (dragged != null) {
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (dragFromRect.left + dragDelta.x - pageOrigin.x).roundToInt(),
                                (dragFromRect.top + dragDelta.y - pageOrigin.y).roundToInt()
                            )
                        }
                        .zIndex(20f)
                        .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }
                ) {
                    when (dragged) {
                        is HomeEntry.App -> {
                            val app = dragged.app
                            AppIconCell(
                                app = app, size = homeIconSize, showLabel = true,
                                onWallpaper = true,
                                onClick = {}, onLongClick = {}
                            )
                        }
                        is HomeEntry.Folder -> FolderCell(
                            folder = dragged.folder, size = homeIconSize,
                            onWallpaper = true, onClick = {}, onLongClick = {}
                        )
                    }
                }
            }
        }

        // ── 玻璃侧边栏：与状态栏同处一条纵向通道，顶部与网格对齐 ──
        // 设置里可以整体隐藏；隐藏后状态指示器仍保留，但不再为侧栏留白
        if (showSidebar) {
        // 胶囊是否真的在屏幕上（不只是开关打开）
        val npVisible = DuoState.showNowPlaying && DuoState.nowPlaying != null
        // 「靠下居中」= 在状态块下方、直到屏幕底部这条区域里垂直居中：
        // 用一个撑满该区域的 Box 居中卡片，卡片中心 = 区域中心（因此落在屏幕中线偏下）。
        // 「屏幕居中」= 区域从屏幕顶端算起，也就是整屏居中。
        // 注意：不能写成对称 padding + align(Center)，那样卡片中心仍回到屏幕中线。
        //
        // 齐老师 2026-11-08：没开音乐胶囊时，区域上沿就取「三合一徽标真实下沿」；
        // 开了胶囊则让到胶囊下方，两种情况都不再拿 statusTopPad 当基准（徽标上移后
        // 那样会让侧栏看起来还挂在半空）。
        val badgeBottom = statusDrawTop + statusHeight + 8f
        val sbRegionTop = if (DuoState.sidebarAlign == "bottom") {
            if (npVisible) (badgeBottom + NpCapsuleHeight.value + 8f).dp else badgeBottom.dp
        } else 0.dp
        val sbShiftPx = if (npVisible && sbBoxHPx > 0 && sbCardHPx > 0) {
            val npBottomPx = with(density) { (statusDrawTop + statusHeight + 8).dp.toPx() } +
                with(density) { NpCapsuleHeight.toPx() }
            val cardTopPx = with(density) { sbRegionTop.toPx() } + (sbBoxHPx - sbCardHPx) / 2f
            (npBottomPx + with(density) { 8.dp.toPx() } - cardTopPx).coerceAtLeast(0f)
        } else 0f
        val sbShift by animateFloatAsState(sbShiftPx, tween(220), label = "sbAvoidCapsule")
        Box(
            Modifier
                .align(if (leftSide) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(
                    start = if (leftSide) sidebarGap else 0.dp,
                    end = if (leftSide) 0.dp else sidebarGap,
                    top = sbRegionTop
                )
                .onSizeChanged { sbBoxHPx = it.height },
            contentAlignment = Alignment.Center
        ) {
        GlassCard(
            modifier = Modifier
                .width(sidebarWidth)
                .onSizeChanged { sbCardHPx = it.height }
                .offset { IntOffset(0, sbShift.roundToInt()) },
            radius = 32.dp,
            level = DuoState.glassLevel,
            fill = DuoState.sidebarGlass,
            dark = dark
        ) {
            Column(
                Modifier
                    .padding(vertical = 7.dp, horizontal = 4.dp)
                    .widthIn(max = 120.dp)
                    .heightIn(max = sidebarMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sidebarItemGap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DuoState.sidebar.toList().take(9).forEach { item ->
                    if (item.type == "app") {
                        val app = DuoState.appOf(item.value)
                        if (app != null) {
                            AppIconCell(
                                app = app,
                                size = sidebarIconSize,
                                showLabel = false,
                                onWallpaper = true,
                                wobble = editMode,
                                onClick = {
                                    if (editMode) sidebarMenu = item
                                    else AppRepository.launch(ctx, item.value)
                                },
                                onLongClick = { sidebarMenu = item }
                            )
                        } else {
                            Box(Modifier.size(sidebarIconSize).clip(squircle(24)).padding(10.dp)) {
                                Icon(Icons.Filled.QuestionMark, null, tint = glassTextColor(dark))
                            }
                        }
                    } else {
                        val action = DuoActions.of(item.value)
                        ActionIconCell(
                            action = action,
                            size = sidebarIconSize,
                            showLabel = false,
                            onWallpaper = true,
                            wobble = editMode,
                            onClick = {
                                if (editMode) sidebarMenu = item
                                else DuoActions.perform(ctx, item.value) { key ->
                                    when (key) {
                                        "search" -> nav.openSearch()
                                        "library" -> nav.goLibrary()
                                        "today" -> nav.goPage(0)
                                        "settings" -> nav.openSettings()
                                        "recents" -> nav.openSearch()
                                    }
                                }
                            },
                            onLongClick = { sidebarMenu = item }
                        )
                    }
                }
            }
        }
        }
        }

        // ── iOS 风格状态指示：与侧边栏共用同一条纵向通道，水平完全居中对齐 ──
        if (DuoState.showDuoStatus) {
            DuoStatusBar(
                dark = dark,
                modifier = Modifier
                    .align(if (DuoState.statusRight) Alignment.TopEnd else Alignment.TopStart)
                    .padding(
                        top = statusDrawTop.dp,
                        start = sidebarGap,
                        end = sidebarGap
                    )
                    .width(sidebarWidth)
            )
        }

        // ── 正在播放胶囊：与「时间 + 电量」「侧边栏」共用同一条纵向通道：
        //    同宽度（sidebarWidth）、同边距（sidebarGap）、同左右侧，竖直方向落在状态块正下方，
        //    视觉上形成「状态 → 胶囊 → 侧栏」一列，不再偏到通道左边悬在壁纸上。──
        if (DuoState.showNowPlaying) {
            DuoState.nowPlaying?.let { np ->
                NowPlayingCapsule(
                    np = np,
                    dark = dark,
                    modifier = Modifier
                        .zIndex(6f)
                        .align(if (leftSide) Alignment.TopStart else Alignment.TopEnd)
                        .padding(
                            start = if (leftSide) sidebarGap else 0.dp,
                            end = if (leftSide) 0.dp else sidebarGap,
                            top = (statusDrawTop + statusHeight + 8).dp
                        )
                        .width(sidebarWidth),
                    onOpen = { DuoNotificationListener.openMediaApp(ctx) },
                    onToggle = { DuoNotificationListener.togglePlayPause() }
                )
            }
        }

        // ── 底部：页面指示点 + 编辑入口 + 搜索 ──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (DuoState.showBottomLeft) {
            GlassCard(modifier = Modifier.size(34.dp), radius = 17.dp, level = DuoState.glassLevel, dark = dark) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showHomeMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (editMode) Icons.Filled.Check else Icons.Filled.MoreHoriz,
                        Lang.s(R.string.home_menu), tint = glassTextColor(dark), modifier = Modifier.size(18.dp)
                    )
                }
            }
            }
            Spacer(Modifier.weight(1f))
            // 只有一页主屏时不画指示点（去掉负一屏后单页很常见）
            if (pageDots && homePageCount > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(homePageCount) { i ->
                        Box(
                            Modifier
                                .size(if (i == homePageIndex) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == homePageIndex) glassTextColor(dark).copy(alpha = 0.9f)
                                    else glassTextColor(dark).copy(alpha = 0.35f)
                                )
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (DuoState.showBottomRight) {
            GlassCard(modifier = Modifier.size(34.dp), radius = 17.dp, level = DuoState.glassLevel, dark = dark) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { nav.openSearch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Search, Lang.s(R.string.search), tint = glassTextColor(dark), modifier = Modifier.size(18.dp))
                }
            }
            }
        }
    }

    // 编辑态点「-」→ iOS 风格移除确认（从主屏幕移除 / 卸载应用 / 取消）
    removeTarget?.let { app ->
        RemoveAppDialog(
            appLabel = app.label,
            dark = dark,
            onDismiss = { removeTarget = null },
            onRemoveHome = { DuoState.removeHome(app.id) },
            onUninstall = { AppRepository.uninstall(ctx, app.id) }
        )
    }

    // 文件夹展开
    openFolder?.let { folder ->
        FolderOpenDialog(
            folder = folder,
            dark = dark,
            onDismiss = { openFolder = null },
            onLaunch = { app -> AppRepository.launch(ctx, app.id); openFolder = null },
            onRename = { name -> DuoState.renameFolder(folder.id, name) },
            onRemoveApp = { pkg -> DuoState.removeAppFromFolder(folder.id, pkg) },
            onDelete = { DuoState.deleteFolder(folder.id); openFolder = null }
        )
    }

    // 侧边栏条目菜单
    sidebarMenu?.let { item ->
        DuoDialog(
            title = if (item.type == "app") DuoState.appOf(item.value)?.label ?: Lang.s(R.string.app) else DuoActions.of(item.value).label,
            dark = dark,
            onDismiss = { sidebarMenu = null }
        ) {
            MenuRow(Icons.Filled.Edit, Lang.s(R.string.edit_sidebar), dark = dark) {
                sidebarMenu = null; nav.openSidebarEditor()
            }
            MenuRow(Icons.Filled.RemoveCircleOutline, Lang.s(R.string.remove_from_sidebar), dark = dark) {
                DuoState.setSidebar(DuoState.sidebar.filterNot { it.key == item.key })
                sidebarMenu = null
            }
            if (item.type == "app") {
                DuoState.appOf(item.value)?.let {
                    MenuRow(Icons.Filled.Info, Lang.s(R.string.app_info), dark = dark) {
                        AppRepository.appDetails(ctx, it.id); sidebarMenu = null
                    }
                }
            }
        }
    }

    // 主屏左下角菜单
    if (showHomeMenu) {
        DuoDialog(title = Lang.s(R.string.home_screen), dark = dark, onDismiss = { showHomeMenu = false }) {
            MenuRow(
                if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                if (editMode) Lang.s(R.string.done_edit) else Lang.s(R.string.edit_home), dark = dark
            ) { editMode = !editMode; showHomeMenu = false }
            MenuRow(Icons.Filled.AddToHomeScreen, Lang.s(R.string.add_app_to_home), dark = dark) {
                showHomeMenu = false; nav.goLibrary()
            }
            MenuRow(Icons.Filled.Tune, Lang.s(R.string.launcher_settings), dark = dark) {
                showHomeMenu = false; nav.openSettings()
            }
            MenuRow(Icons.Filled.Wallpaper, Lang.s(R.string.change_wallpaper), dark = dark) {
                AppRepository.wallpaper(ctx); showHomeMenu = false
            }
        }
    }
}

private sealed class HomeEntry {
    data class App(val app: AppInfo) : HomeEntry()
    data class Folder(val folder: FolderItem) : HomeEntry()
}

/** 音乐胶囊高度：顶部定位与「侧栏避让胶囊」共用这一个值，改一处两处同步 */
private val NpCapsuleHeight = 34.dp

/**
 * 右上角「正在播放」胶囊（紧凑版，对齐参考图）：
 * 左侧圆形专辑封面 + 右侧 iOS 风格频谱（5 条、中间高两端低、随律动起伏）。
 * 单击打开播放器；长按暂停 / 继续——胶囊很小，不拆热区，避免点不中或误触暂停把胶囊弄没。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingCapsule(
    np: NowPlaying,
    dark: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    GlassCard(
        modifier = modifier
            .height(NpCapsuleHeight)
            .combinedClickable(onClick = onOpen, onLongClick = onToggle),
        radius = NpCapsuleHeight / 2,          // = 高度一半：iOS 胶囊那种全圆端头（连续曲率观感）
        level = DuoState.glassLevel,
        fill = 0.9f,
        dark = dark
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 圆形专辑封面
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(glassTextColor(dark).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (np.art != null) {
                    Image(
                        bitmap = np.art.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = glassSubTextColor(dark), modifier = Modifier.size(15.dp))
                }
            }
            // iOS 风格频谱：中间最高、两端渐低，播放时随律动起伏；暂停定格变暗
            MusicSpectrum(playing = np.playing, color = glassTextColor(dark))
        }
    }
}

/**
 * 胶囊右侧的迷你频谱（对齐 iOS 灵动岛那种「中间高、两边低」的 5 条）。
 * 系统媒体会话拿不到音频采样，这里用不同相位/周期的起伏模拟律动，
 * 播放时持续跳动，暂停时定格成一个低矮的静态形状。
 */
@Composable
private fun MusicSpectrum(playing: Boolean, color: Color) {
    val base = listOf(5f, 10f, 15f, 10f, 5f)          // 各条满格高度（dp）
    // 只在播放时创建无限动画。暂停 / 无媒体时若仍挂着 5 个 transition，
    // 它们会持续向 Choreographer 请求帧，桌面常驻白白耗电。
    val ratios: List<State<Float>>? = if (playing) {
        val transition = rememberInfiniteTransition()
        base.indices.map { i ->
            transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420 + i * 75, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 95)
                )
            )
        }
    } else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        base.forEachIndexed { i, full ->
            val k = ratios?.get(i)?.value ?: 0.45f
            Box(
                Modifier
                    .width(2.5.dp)
                    .height((full * k).coerceAtLeast(2f).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color.copy(alpha = if (playing) 0.92f else 0.32f))
            )
        }
    }
}

/** 点击文件夹后展开的「窗口」：网格展示应用，底部支持重命名 / 解散 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderOpenDialog(
    folder: FolderItem,
    dark: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onRename: (String) -> Unit,
    onRemoveApp: (String) -> Unit,
    onDelete: () -> Unit
) {
    var editingName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(folder.name) }
    val apps = folder.apps.mapNotNull { DuoState.appOf(it) }
    FolderWindow(
        title = folder.name,
        subtitle = Lang.s(R.string.folder_apps_count, apps.size),
        apps = apps,
        dark = dark,
        onDismiss = onDismiss,
        onLaunch = onLaunch,
        onLongClick = { onRemoveApp(it.id) },
        footer = {
            if (editingName) {
                BasicTextField(
                    name, { name = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = glassTextColor(dark)),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(glassTextColor(dark).copy(alpha = 0.12f)).padding(10.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPrimaryButton(Lang.s(R.string.save_name), dark, { onRename(name); editingName = false })
                    GlassPrimaryButton(Lang.s(R.string.cancel), dark, { name = folder.name; editingName = false })
                }
            } else {
                MenuRow(Icons.Filled.Edit, Lang.s(R.string.rename_folder), dark = dark) { editingName = true }
                MenuRow(Icons.Filled.Delete, Lang.s(R.string.disband_folder), danger = true, dark = dark) { onDelete() }
            }
        }
    )
}
