package com.duolauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.util.SystemPanel
import kotlin.math.roundToInt
import com.duolauncher.data.AppInfo
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoActions
import com.duolauncher.data.DuoState
import com.duolauncher.data.FolderItem
import com.duolauncher.data.IconPacks
import com.duolauncher.data.SidebarItem
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
    /** 这是 pager 最左一页（再往右没有页可翻）→ 允许「右滑打开搜索」 */
    allowSwipeRight: Boolean = false
) {
    val ctx = LocalContext.current
    var editMode by remember { mutableStateOf(false) }
    var sidebarMenu by remember { mutableStateOf<SidebarItem?>(null) }
    var showHomeMenu by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<FolderItem?>(null) }

    val packPkg = DuoState.iconPack

    val iconScale = DuoState.iconScale
    val homeIconSize = (54 * iconScale).dp          // 主屏图标：比侧栏大
    val sidebarIconSize = (54 * DuoState.sidebarIconScale).dp
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
    val statusScale = DuoState.statusScale.coerceIn(0.55f, 1.8f)
    // 状态块真实高度：时间(≈1.35×字号) + 6dp 间距 + 徽标 50dp
    val statusHeight = (18f * 1.35f + 6f + 50f) * statusScale
    val topGap = if (DuoState.showDuoStatus)
        maxOf(DuoState.cutoutPadding.coerceAtLeast(64) + 64f, statusTopPad + statusHeight + 10f).dp
    else (DuoState.cutoutPadding.coerceAtLeast(64) + 24f).dp
    val leftSide = DuoState.sidebarLeft
    // 侧栏顶部：左右完全同一套算法（与网格顶部对齐），切换左右时不会上下跳动；
    // 状态块同侧时 topGap 已含 statusTopPad + statusHeight，这里再上提 8dp ——
    // 让玻璃框紧贴徽标下方（此前离徽标太远，视觉上整条侧栏偏下）。
    // 侧栏与状态块同侧时贴着状态块下方；不同侧（默认左栏＋右上状态）则整体再上提 30dp，
    // 让 8 个图标的侧栏底部离底部搜索键更远，同时不越过挖孔避让高度
    val sidebarSameSideAsStatus =
        (DuoState.sidebarLeft && !DuoState.statusRight) || (!DuoState.sidebarLeft && DuoState.statusRight)
    val sidebarTopPad = if (DuoState.showDuoStatus) {
        if (sidebarSameSideAsStatus) (topGap.value - 12f).coerceAtLeast(DuoState.cutoutPadding + 12f)
        else (topGap.value - 30f).coerceAtLeast(DuoState.cutoutPadding + 12f)
    } else 10f
    val sidebarMaxHeight = 430.dp
    // 图标多（8 个以上）时收紧纵向间距，否则整条侧栏会顶到右下角搜索键上
    val sidebarItemCount = DuoState.sidebar.size.coerceAtMost(9)
    val sidebarItemGap = if (sidebarItemCount >= 8) 5.dp else 9.dp
    val gridStart = if (leftSide) lane else 14.dp
    val gridEnd = if (leftSide) 14.dp else lane

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
            // 搜索只走右下角的搜索按钮，桌面不再用下滑呼出搜索
            // 双击桌面空白处熄屏（无障碍服务 GLOBAL_ACTION_LOCK_SCREEN，不用设备管理员）：
            // 图标 / 文件夹自身消费点击事件，故只有点空白才触发
            .pointerInput(DuoState.doubleTapSleep) {
                if (!DuoState.doubleTapSleep) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    if (ScreenOff.lock()) {
                        // 已熄屏
                    } else if (ScreenOff.isEnabled(ctx)) {
                        nav.toast(Lang.s(R.string.toast_sleep_starting))
                    } else {
                        ScreenOff.requestPermission(ctx)
                        nav.toast(Lang.s(R.string.toast_enable_accessibility))
                    }
                })
            }
            // 下滑呼出系统面板（iOS 那套分区）：左半屏 → 通知栏，右半屏 → 控制中心。
            // 竖直手势和左右翻页互不干扰；图标长按拖拽会先消费事件，这里自然让位。
            .pointerInput(DuoState.gesturePanels) {
                if (!DuoState.gesturePanels) return@pointerInput
                val threshold = 96.dp.toPx()
                var acc = 0f
                var startX = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset -> acc = 0f; startX = offset.x },
                    onDragCancel = { acc = 0f },
                    onDragEnd = {
                        val travelled = acc
                        acc = 0f
                        if (travelled >= threshold) {
                            val leftHalf = startX < size.width / 2f
                            val ok =
                                if (leftHalf) SystemPanel.openNotifications(ctx)
                                else SystemPanel.openQuickSettings(ctx)
                            if (!ok) {
                                nav.toast(Lang.s(R.string.toast_enable_service))
                                ScreenOff.requestPermission(ctx)
                            }
                        }
                    }
                ) { change, dy ->
                    if (DuoState.dragging) {
                        acc = 0f
                    } else if (dy > 0f) {
                        acc += dy
                        if (acc > 20f) change.consume()
                    } else {
                        acc = 0f
                    }
                }
            }
            // 右滑打开搜索：跟手揭示搜索页（和横向翻到资源库一样有位移反馈），
            // 松手后再弹性补齐 / 回弹。只在这一排的最左页生效（再往右本来也翻不动页）。
            // 在 Initial pass 上判定并消费掉，pager 的横向拖动看到「已被消费」就会自己取消。
            .pointerInput(DuoState.gestureSearch, allowSwipeRight) {
                if (!DuoState.gestureSearch || !allowSwipeRight) return@pointerInput
                val startSlop = 14.dp.toPx()
                val commitPx = 76.dp.toPx()
                val fullPx = size.width * 0.5f
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
                        if (!engaged && dx > startSlop && dx > kotlin.math.abs(dy) * 1.15f) engaged = true
                        if (engaged) {
                            ch.consume()
                            nav.searchDrag(((dx - startSlop) / fullPx).coerceIn(0f, 1f))
                        }
                        if (!ch.pressed) break
                    }
                    if (engaged) nav.searchRelease(dx >= commitPx)
                }
            }
    ) {
        // ── 主屏：顶部状态指示让位 + 图标网格（5 行 × 4 列，不滚动，左右翻页）──
        val pageStart = homePageIndex * 20
        val pageEntries = entries.drop(pageStart).take(20)
        val pageRows = pageEntries.chunked(4)
        // 只在当前这页的成员变化时重建，避免残留上一轮（排序前）的旧矩形导致落点错位
        val cellRects = remember(pageEntries) { mutableStateMapOf<Int, Rect>() }
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = gridStart, end = gridEnd, top = topGap, bottom = 76.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(),
                // 满 5 行时均分铺满（顶部到底部）；不满页则从顶部顺排，避免图标被拉开
                verticalArrangement = if (pageRows.size >= 5) Arrangement.SpaceEvenly
                else Arrangement.spacedBy(16.dp * iconScale)
            ) {
                pageRows.forEachIndexed { rowIdx, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top
                    ) {
                        row.forEachIndexed { colIdx, entry ->
                            val idx = rowIdx * 4 + colIdx            // 页内下标
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
                                            onClick = {
                                                if (editMode) nav.showAppMenu(app, "home")
                                                else AppRepository.launch(ctx, app.packageName)
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
                        repeat(4 - row.size) { Spacer(Modifier.width(cellWidth)) }
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
        GlassCard(
            modifier = Modifier
                .align(if (leftSide) Alignment.TopStart else Alignment.TopEnd)
                .then(
                    if (leftSide) Modifier.padding(start = sidebarGap, top = sidebarTopPad.dp)
                    else Modifier.padding(end = sidebarGap, top = sidebarTopPad.dp)
                )
                .width(sidebarWidth),
            radius = 32.dp,
            level = DuoState.glassLevel,
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

        // ── iOS 风格状态指示：与侧边栏共用同一条纵向通道，水平完全居中对齐 ──
        if (DuoState.showDuoStatus) {
            DuoStatusBar(
                dark = dark,
                modifier = Modifier
                    .align(if (DuoState.statusRight) Alignment.TopEnd else Alignment.TopStart)
                    .padding(
                        top = statusTopPad.dp,
                        start = sidebarGap,
                        end = sidebarGap
                    )
                    .width(sidebarWidth)
            )
        }

        // ── 底部：页面指示点 + 编辑入口 + 搜索 ──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

    // 文件夹展开
    openFolder?.let { folder ->
        FolderOpenDialog(
            folder = folder,
            dark = dark,
            onDismiss = { openFolder = null },
            onLaunch = { app -> AppRepository.launch(ctx, app.packageName); openFolder = null },
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
                        AppRepository.appDetails(ctx, it.packageName); sidebarMenu = null
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
        onLongClick = { onRemoveApp(it.packageName) },
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
