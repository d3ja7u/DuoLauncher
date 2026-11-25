package com.duolauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    /** 长按主屏空白弹出的菜单（编辑主屏幕 / 设置） */
    var sidebarMenu by remember { mutableStateOf<SidebarItem?>(null) }
    var showHomeMenu by remember { mutableStateOf(false) }
    /** 长按音乐胶囊弹出的灵动岛式播放面板 */
    var showPlayerPanel by remember { mutableStateOf(false) }
    /** 左下角菜单「添加应用到主屏幕」打开的应用选择器 */
    var appPickerOpen by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<FolderItem?>(null) }
    /** 长按文件夹图标弹出的编辑菜单（重命名 / 解散） */
    var folderMenu by remember { mutableStateOf<FolderItem?>(null) }
    /** 文件夹改名对话框 */
    var folderRename by remember { mutableStateOf<FolderItem?>(null) }
    /** 长按主屏系统小组件后弹出的菜单（值为该小组件的 AppWidget id） */
    var widgetMenu by remember { mutableStateOf<Int?>(null) }
    val widgetBridge = LocalWidgetBridge.current
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
    val sidebarWidth = sidebarIconSize + 24.dp
    val sidebarGap = 10.dp
    val lane = sidebarGap + sidebarWidth
    // ── 主屏拖拽（长按拿起 → 拖到任意一格 → 松手落位）──
    val haptic = LocalHapticFeedback.current
    /** 正在拖动的条目 token（app:xxx / folder:xxx / widget:id），null = 没在拖 */
    val dragTokenState = remember { mutableStateOf<String?>(null) }
    var dragToken by dragTokenState
    /** 拖动中的落点（行, 列）；允许是空白格 —— 这就是"拖到任意位置" */
    // 用 State 对象暴露给落点高亮，让它在绘制/局部重组阶段才读：
    // 以前 hoverCell 是在网格作用域里读的，每跨一格就把几十个图标全部重组一遍。
    val hoverCellState = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var hoverCell by hoverCellState
    var dragFromRect by remember { mutableStateOf(Rect.Zero) }      // 拿起时条目在根坐标系的位置
    var dragDelta by remember { mutableStateOf(Offset.Zero) }       // 累计拖动位移
    var dragFinger by remember { mutableStateOf(Offset.Zero) }       // 手指在根坐标系的位置
    // 手指在左右边缘带里「停留」的起点（毫秒）；0 表示不在边缘带里。
    // 只有真的停一会儿再松手才算「拖到边缘翻屏」，顺手划过时仍然正常落格 ——
    // 否则想把图标/小组件放到第一列或最后一列，手指刚贴边就被判成翻屏了。
    var edgeDwellStart by remember { mutableStateOf(0L) }
    // ── 两段式长按（对齐系统启动器）──
    // 长按先弹快捷菜单，手指不松继续滑动才进入拖拽；直接抬手就只留菜单。
    /** 已弹菜单、正在等「继续滑动」的条目 */
    val pendingMenuTokenState = remember { mutableStateOf<String?>(null) }
    var pendingMenuToken by pendingMenuTokenState
    /** 本次手势已经弹过菜单：抬手时不能再走一次 onTap 重复弹 */
    var menuShownForDrag by remember { mutableStateOf(false) }
    /** 拖到屏幕顶部「移除」目标上方并松手 = 从桌面移除（不卸载） */
    var dropRemoveArmed by remember { mutableStateOf(false) }
    var dropRemoveRect by remember { mutableStateOf(Rect.Zero) }
    /** 拖到右侧「新页面」框里并松手 = 新开一页把它放进去（最后一页才出现） */
    var newPageArmed by remember { mutableStateOf(false) }
    var newPageRect by remember { mutableStateOf(Rect.Zero) }
    /** 当前是不是最后一页：只有最后一页右侧才让用户拖出新页 */
    val onLastHomePage = homePageIndex >= homePageCount - 1
    /** 菜单阶段手指相对图标的起点 / 已滑动距离（小组件的回调只给增量，用它判定转拖拽） */
    var pendingDownOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingTravelPx by remember { mutableStateOf(0f) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }      // 本页在根坐标系的原点
    val statusTopPad = if (DuoState.statusRight) DuoState.statusTop
    else maxOf(DuoState.statusTop, 64)
    // 时间 + 电量三合一再往上提：只挪绘制位置，网格 topGap 仍用 statusTopPad，不动图标行距。
    // 侧栏「靠下居中」的基准也用这个真实下沿（见下方 sbRegionTop），两者始终对齐。
    //
    // 但右上角是圆角时（TT2 Elite 约 46dp，TT2 的角小得多）不能贴到 2dp：
    // 贴顶的时间最后一位会被圆角削掉一块，同一套设置在两台机器上表现不一样。
    // 按角半径算一个最小下移量，角越圆让得越多；无圆角或圆角很小基本等于原来的位置。
    val cornerDropDp = if (DuoState.statusRight) {
        (DuoState.cornerTopRightDp * 0.22f).coerceIn(2f, 14f)
    } else 2f
    val statusDrawTop = (statusTopPad - 20f).coerceAtLeast(cornerDropDp)
    // 状态块真实高度（时间 / 三合一徽标可分别关闭）走 DuoState 的唯一口径，
    // 关掉其中一项后，胶囊与侧栏会跟着上移，不会留下空档。
    val statusHeight = DuoState.statusBlockHeightDp()
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    // 顶部留白走 DuoState 的唯一口径：状态块和侧栏同侧时不再为它整块让位；
    // 系统状态栏 / 挖孔高度由 MainActivity 的 windowInsets 监听写入 DuoState
    // （取「状态栏高度」与「挖孔包围盒下沿」的较大值，角部挖孔也不会压住第一行），
    // 这样第一行图标贴着屏幕顶部、只避开挖孔，不再白空一大截。
    val topGap = DuoState.gridTopGapDp(landscape).dp
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

    // 主屏条目：按 DuoState.homeTokens() 的统一顺序（应用 / 文件夹 / 系统小组件可混排）
    fun entryOf(token: String): HomeEntry? = when {
        token.startsWith("app:") -> DuoState.appOf(token.removePrefix("app:"))?.let { HomeEntry.App(it) }
        token.startsWith("folder:") -> DuoState.folderOf(token.removePrefix("folder:"))
            ?.let { HomeEntry.Folder(it) }
        token.startsWith("widget:") -> token.removePrefix("widget:").toIntOrNull()
            ?.let { HomeEntry.Widget(it) }
        else -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            // 播放面板弹出时把身后的桌面（含底部）虚化，关闭即恢复清晰
            .then(if (showPlayerPanel) Modifier.blur(8.dp) else Modifier)
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
            // 主屏空白处手势：双击息屏 + 长按进「启动器设置」，两个手势互不干扰
            // （双击在超时前等第二击；长按超过阈值即触发，并取消 pending taps）。
            // 图标自身的长按拖拽会先 consume 该 down 事件，所以长按图标不会误触发设置。
            .pointerInput(DuoState.doubleTapSleep) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!DuoState.doubleTapSleep) return@detectTapGestures
                        when (ScreenOff.lockAction()) {
                            // ① 一切正常
                            com.duolauncher.service.DuoLockService.Action.OK -> Unit
                            // ② 服务连上了，但系统没执行（系统侧的锁屏动作失效，
                            //    logcat 里是 PendingIntent$CanceledException，重启手机可恢复）。
                            //    这时候千万别再往无障碍页送 —— 服务本身是好的，怎么重新开关都没用。
                            com.duolauncher.service.DuoLockService.Action.FAILED -> {
                                if (ScreenOff.shouldTellActionFailed()) {
                                    nav.toast(Lang.s(R.string.toast_system_action_failed))
                                }
                            }
                            // ③ 服务没连上：系统里显示已开启 → 引导关一次再打开；否则引导去开启
                            com.duolauncher.service.DuoLockService.Action.NOT_CONNECTED -> {
                                if (ScreenOff.isEnabled(ctx)) {
                                    nav.toast(Lang.s(R.string.toast_sleep_rebind))
                                    ScreenOff.guideToRebind(ctx)
                                } else {
                                    ScreenOff.requestPermission(ctx)
                                    nav.toast(Lang.s(R.string.toast_enable_accessibility))
                                }
                            }
                        }
                    },
                    onLongPress = {
                        // 主屏菜单的唯一入口：长按空白处。左下角那个菜单键已经去掉，
                        // 但菜单内容一项没少（编辑、添加应用、加小组件、搜索、设置、壁纸）。
                        // 延后一拍再判：长按图标会先把 DuoState.dragging 置为 true，
                        // 这里等一下就不会把菜单和图标拖拽同时触发。
                        scope.launch {
                            kotlinx.coroutines.delay(80)
                            if (!DuoState.dragging) showHomeMenu = true
                        }
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
                                    // 服务在、系统没执行 → 说清原因；服务没开 → 才引导去开
                                    if (com.duolauncher.service.DuoLockService.isRunning()) {
                                        if (ScreenOff.shouldTellActionFailed()) {
                                            nav.toast(Lang.s(R.string.toast_system_action_failed))
                                        }
                                    } else {
                                        nav.toast(Lang.s(R.string.toast_enable_service))
                                        ScreenOff.requestPermission(ctx)
                                    }
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
        // ── 主屏：绝对格子网格（N 行 × M 列，不滚动，左右翻页）──
        // 行数 / 列数由设置决定；落位走 DuoState.layoutHome（与 MainActivity 同一口径）。
        // 每个条目都落在具体的一格上，拖到任意一格（**包括原本空着的格**）就放在那里；
        // 没有手动坐标的条目按顺序自动补空位，老存档的观感与以前一致。
        val homeCols = DuoState.homeColumns.coerceAtLeast(1)
        val homeRows = DuoState.effectiveHomeRows(cfg.screenHeightDp, landscape)
        // 版本号做键：拖动时 hoverCell 变化、翻页、状态栏刷新都不会重算排版
        val layoutPages = remember(DuoState.homeLayoutVersion, homeCols, homeRows) {
            DuoState.layoutHome(homeCols, homeRows)
        }
        val pagePlacements = layoutPages.getOrElse(homePageIndex) { emptyList() }
        val rowGapDp = 16f * iconScale
        // 拖动时手指进入左右边缘这一带、并**停留**一下再松手，才整块挪到相邻一屏。
        // 带子收窄到 14dp（只有真拖出屏幕边缘才会碰到），外加 450ms 停留判定：
        // 以前只要在 28dp 内松手就算翻屏，于是「想放到第一列/最边上」永远被判成翻屏，
        // 表现就是条目怎么都贴不到屏幕边上、小组件还会莫名跑到前一屏。
        val screenWidthPx = with(density) { cfg.screenWidthDp.dp.toPx() }
        // 40dp 而不是 14dp：dragFinger 是按格子左上角 + 局部偏移近似的手指位置，
        // 跟真实手指有几十像素偏差；太窄的热区会让「拖到屏幕边缘」永远差一点点触发。
        // 450ms 停留判定保证顺手划到边格不会被误判成翻页。
        val edgeFlipPx = with(density) { 40.dp.toPx() }
        val edgeDwellMs = 450L

        /** 手指移动时维护「是否在边缘带里、从何时开始」 */
        fun trackEdge(x: Float) {
            val inEdge = x <= edgeFlipPx || x >= screenWidthPx - edgeFlipPx
            if (inEdge) {
                if (edgeDwellStart == 0L) edgeDwellStart = System.currentTimeMillis()
            } else edgeDwellStart = 0L
        }
        // token → 该条目在屏幕上的矩形（拿起位置 / 悬浮层定位）
        val cellRects = remember(homePageIndex) { HashMap<String, Rect>() }
        // 网格几何：由渲染层实测后写回，用来把手指位置换算成「第几行第几列」。
        // 现在图标和小组件都按**绝对格子坐标**摆放（小组件还能只占半行），
        // 所以落点必须用统一的格子宽 / 行距算，不能再靠"逐行矩形"反推。
        var gridOrigin by remember { mutableStateOf(Offset.Zero) }
        var cellWpx by remember { mutableStateOf(0f) }
        var rowPitchPx by remember { mutableStateOf(0f) }

        /** 手指位置 → (行, 列)，都夹在网格范围内 */
        fun cellAt(pos: Offset): Pair<Int, Int>? {
            if (cellWpx <= 0f || rowPitchPx <= 0f) return null
            val lx = pos.x - gridOrigin.x
            val ly = pos.y - gridOrigin.y
            val col = (lx / cellWpx).toInt().coerceIn(0, homeCols - 1)
            val row = (ly / rowPitchPx).toInt().coerceIn(0, homeRows - 1)
            return row to col
        }

        /**
         * 拖动落点：按条目**可视左上角**的位置换算，并夹在网格内。
         *
         * 光按手指位置算对小组件很不友好：4×2 的组件，手指通常停在它中间，
         * 结果落点被算到手指那一行，组件就"跳"下去半截；再加上夹取边界，
         * 往最下面拖时经常落不到位。改成跟左上角走以后，拖到哪就落在哪，
         * 和拖图标的手感一致。
         */
        fun cellForDrop(token: String): Pair<Int, Int>? {
            if (cellWpx <= 0f || rowPitchPx <= 0f) return null
            val rs = DuoState.tokenRowSpan(token, homeRows)
            val cs = DuoState.tokenColSpan(token, homeCols)
            // 稍微往内收 0.35 格：小图标≈手指位置，大组件≈它自己的左上角
            val anchor = dragFromRect.topLeft + dragDelta +
                    Offset(cellWpx * 0.35f, rowPitchPx * 0.35f)
            val lx = anchor.x - gridOrigin.x
            val ly = anchor.y - gridOrigin.y
            val col = (lx / cellWpx).toInt().coerceIn(0, (homeCols - cs).coerceAtLeast(0))
            val row = (ly / rowPitchPx).toInt().coerceIn(0, (homeRows - rs).coerceAtLeast(0))
            return row to col
        }

        /**
         * 长按成立的第一段：先弹快捷菜单，手指继续按住不动时只留菜单。
         * 这是系统启动器的两段式手感，而不是「长按即拿起」——
         * 用户常在图标上按一下想看快捷操作，直接拖走会误改桌面。
         */
        fun showTokenMenu(token: String, entry: HomeEntry?, downOffset: Offset) {
            // 锁定布局：图标不可移动 / 移除，长按也不弹带「移除」的菜单
            if (DuoState.lockLayout) {
                nav.toast(Lang.s(R.string.lock_layout_toast))
                return
            }
            pendingMenuToken = token
            pendingDownOffset = downOffset
            pendingTravelPx = 0f
            menuShownForDrag = true
            // 菜单弹出后同一串触摸仍要由这里接住；置位可让外层的翻页 / 下滑手势让路。
            DuoState.dragging = true
            when (entry) {
                is HomeEntry.App -> nav.showAppMenu(entry.app, "home")
                is HomeEntry.Folder -> folderMenu = entry.folder
                is HomeEntry.Widget -> widgetMenu = entry.appWidgetId
                else -> Unit
            }
        }

        /** 菜单显示期间手指又滑走了：收起菜单，转入拖拽 */
        fun dismissTokenMenu() {
            nav.dismissAppMenu()
            folderMenu = null
            widgetMenu = null
            pendingMenuToken = null
        }

        fun beginDrag(token: String, offsetInBox: Offset) {
            if (DuoState.lockLayout) return
            val r = cellRects[token] ?: Rect.Zero
            dragToken = token
            pendingMenuToken = null
            hoverCell = null
            dragFromRect = r
            dragDelta = Offset.Zero
            dragFinger = r.topLeft + offsetInBox
            edgeDwellStart = 0L
            dropRemoveArmed = false
            DuoState.dragging = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        /** 拖动中为同一串触摸更新所有派生状态（图标 / 文件夹 / 小组件共用） */
        fun updateDrag(token: String, d: Offset) {
            dragDelta += d
            dragFinger += d
            // 手指进了右侧「新页面」框：这一次落位不算格子里，腾出来建新页
            newPageArmed = onLastHomePage && newPageRect != Rect.Zero &&
                    newPageRect.contains(dragFinger)
            hoverCell = if (newPageArmed) null else cellForDrop(token)
            trackEdge(dragFinger.x)
            dropRemoveArmed = !newPageArmed && dropRemoveRect != Rect.Zero &&
                    dropRemoveRect.contains(dragFinger)
            // 贴边停够就立刻翻到相邻页：用户能看见页面真的滑过去、图标继续跟手，
            // 而不是等松手才突然跳到另一屏（那正是系统桌面预览框的手感）。
            val held = edgeDwellStart != 0L &&
                    System.currentTimeMillis() - edgeDwellStart >= edgeDwellMs
            if (held) {
                val dir = when {
                    dragFinger.x <= edgeFlipPx -> -1
                    // 最后一页的右边缘留给「新页面」框，不再走贴边翻页
                    dragFinger.x >= screenWidthPx - edgeFlipPx && !onLastHomePage -> 1
                    else -> 0
                }
                if (dir != 0 && DuoState.moveTokenToPage(token, dir, homeCols, homeRows)) {
                    edgeDwellStart = 0L
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    nav.goPage(homePageIndex + dir)
                    // 翻页后当前页的拖拽状态已经随 pager 重建，别把全局手势锁留着
                    DuoState.dragging = false
                }
            }
        }

        /**
         * 第二段：菜单弹出后手指继续滑过阈值 → 收菜单、拿起条目。
         * [total] 是相对按下点的累计位移，[delta] 是这一帧的增量。
         */
        fun continueFromMenu(token: String, total: Float, delta: Offset): Boolean {
            if (pendingMenuToken != token || total < 12f) return false
            dismissTokenMenu()
            beginDrag(token, pendingDownOffset)
            updateDrag(token, delta)
            return true
        }

        /** 拖到顶部「移除」：只从桌面拿掉，不卸载、不解散文件夹里的数据 */
        fun removeDropped(token: String) {
            when {
                token.startsWith("app:") -> DuoState.removeHome(token.removePrefix("app:"))
                token.startsWith("folder:") -> DuoState.deleteFolder(token.removePrefix("folder:"))
                token.startsWith("widget:") -> {
                    val id = token.removePrefix("widget:").toIntOrNull() ?: return
                    runCatching { widgetBridge?.host?.deleteAppWidgetId(id) }
                    DuoState.removeHomeWidget(id)
                }
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        // 贴边停够就翻页。不能只在 MOVE 回调里判定：手指一旦停在边缘不动，
        // 系统就不再发新事件，旧实现于是"停多久都不翻"。这里补一个定时触发，
        // 时间到了仍贴边（edgeDwellStart 没被 trackEdge 清零）才真正翻。
        LaunchedEffect(edgeDwellStart) {
            val start = edgeDwellStart
            if (start == 0L) return@LaunchedEffect
            kotlinx.coroutines.delay(edgeDwellMs + 60L)
            if (edgeDwellStart != start) return@LaunchedEffect
            val t = dragToken ?: return@LaunchedEffect
            val dir = when {
                dragFinger.x <= edgeFlipPx -> -1
                dragFinger.x >= screenWidthPx - edgeFlipPx && !onLastHomePage -> 1
                else -> 0
            }
            if (dir != 0 && DuoState.moveTokenToPage(t, dir, homeCols, homeRows)) {
                edgeDwellStart = 0L
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                nav.goPage(homePageIndex + dir)
                DuoState.dragging = false
            }
        }

        // 边缘翻页后新的一页刚组合出来：把「拿起时的矩形」换成新页里的位置，
        // 并按手指当前位置重算位移，悬浮图标才不会弹回上一屏。
        LaunchedEffect(homePageIndex) {
            val t = dragToken ?: return@LaunchedEffect
            kotlinx.coroutines.delay(90)
            val r = cellRects[t] ?: return@LaunchedEffect
            dragFromRect = r
            dragDelta = dragFinger - r.topLeft
            hoverCell = cellForDrop(t)
        }

        /** 落位后如果条目被挪到了别的屏，跟过去，别让用户觉得"拖了一下它不见了" */
        fun followToken(token: String) {
            val at = DuoState.layoutHome(homeCols, homeRows)
                .indexOfFirst { list -> list.any { it.token == token } }
            if (at >= 0 && at != homePageIndex) nav.goPage(at)
        }

        /** 松手落位：图标 / 文件夹 / 小组件共用一套（[onTap] = 既没拖也没弹菜单时的兜底） */
        fun finishDrag(moved: Boolean, onTap: () -> Unit) {
            val token = dragToken
            if (token == null) {
                // 两段式第一段：长按只弹了菜单，手指没继续滑 —— 菜单保持打开。
                DuoState.dragging = false
                pendingMenuToken = null
                menuShownForDrag = false
                return
            }
            val cell = hoverCell
            val remove = dropRemoveArmed
            val toNewPage = newPageArmed
            dropRemoveArmed = false
            newPageArmed = false
            edgeDwellStart = 0L
            dragToken = null
            hoverCell = null
            dragDelta = Offset.Zero
            pendingMenuToken = null
            DuoState.dragging = false
            if (!moved) {
                // 菜单已在长按时弹过，别再弹一次
                if (!menuShownForDrag) onTap()
                menuShownForDrag = false
                return
            }
            menuShownForDrag = false
            when {
                // 顶部「移除」目标：只从桌面移除
                remove -> removeDropped(token)
                // 拖进最后一页右侧的「新页面」框：新开一页放在左上角，并跟过去
                toNewPage -> {
                    val target = homePageCount
                    if (DuoState.placeToken(token, target, 0, 0, homeCols, homeRows)) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        nav.goPage(target)
                    } else onTap()
                }
                // 落在某行某列（空白格也算）：直接放到那一格，目标格已有条目则交换。
                // 跨页已在拖动过程中完成，这里一律落到当前页。
                cell != null -> {
                    // 落在文件夹上：把应用塞进文件夹（iOS 手感），不要再走「同格交换」
                    //
                    // 这里必须**实时**取布局，不能用组合里的 pagePlacements：
                    // 图标的 pointerInput(token) 只在 token 变化时才重建，长按拖动的事件
                    // 走的是图标第一次组合时的那份闭包，里面捕获的 pagePlacements 已经过期。
                    // 后果就是「刚建好文件夹，再拖一个应用上去」时，读到的还是文件夹之前的
                    // 那个应用，于是又建了一个新文件夹（2026-11-25 齐老师反馈）。
                    val live = DuoState.placements(homeCols, homeRows)
                        .filter { it.page == homePageIndex }
                    val targetToken = live.firstOrNull {
                        it.row == cell.first && it.col == cell.second && it.token != token
                    }?.token
                    val folderToken = targetToken?.takeIf { it.startsWith("folder:") }
                    when {
                        folderToken != null && token.startsWith("app:") -> {
                            val folderId = folderToken.removePrefix("folder:")
                            val folderName = DuoState.folderOf(folderId)?.name.orEmpty()
                            DuoState.addAppToFolder(folderId, token.removePrefix("app:"))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            nav.toast(Lang.s(R.string.toast_app_in_folder, folderName))
                        }
                        // 两个应用叠在一起 → 现场建一个文件夹（iOS / 系统桌面手感），
                        // 文件夹落在被压住那一格，别把用户拖来的图标又弹回去
                        targetToken != null && token.startsWith("app:") &&
                                targetToken.startsWith("app:") -> {
                            val draggedPkg = token.removePrefix("app:")
                            val targetPkg = targetToken.removePrefix("app:")
                            val folderId = DuoState.createFolder("", listOf(draggedPkg, targetPkg))
                            DuoState.placeToken(
                                "folder:$folderId", homePageIndex,
                                cell.first, cell.second, homeCols, homeRows
                            )
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            nav.toast(Lang.s(R.string.folder_created))
                        }
                        else -> {
                            val ok = DuoState.placeToken(
                                token, homePageIndex, cell.first, cell.second, homeCols, homeRows
                            )
                            if (ok) followToken(token) else onTap()
                        }
                    }
                }
                else -> onTap()
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = gridStart, end = gridEnd, top = topGap,
                    bottom = DuoState.GRID_BOTTOM_PAD_DP.dp
                )
        ) {
            // ── 绝对格子坐标排版 ──
            // 每个条目自己算左上角 + 宽高：图标 1×1，小组件按 provider 声明的「几行几列」。
            // 以前是按行走 Row、小组件独占整行，于是 4×2 的组件被拉成一整行、左右拖不动；
            // 现在列坐标是真实生效的，第一行贴顶、最后一行贴底，半行宽的小组件也能左右挪。
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { gridOrigin = it.positionInRoot() }
            ) {
                val cellW = maxWidth / homeCols
                val rowH = ((maxHeight - rowGapDp.dp * (homeRows - 1)) / homeRows)
                    .coerceAtLeast(1.dp)
                val pitchY = rowH + rowGapDp.dp
                SideEffect {
                    cellWpx = with(density) { cellW.toPx() }
                    rowPitchPx = with(density) { pitchY.toPx() }
                }

                // 拖动时桌面收成一块半透明预览框（只改观感、不改布局尺寸，
                // 落点换算仍用同一套格子几何）。配合底部页码点就是系统那种预览手感。
                if (dragToken != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.Black.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(22.dp))
                    )
                }

                pagePlacements.forEach { pl ->
                    val token = pl.token
                    val entry = entryOf(token) ?: return@forEach
                    // 注意：这里**不要**读 hoverCell / dragDelta 这类逐帧变化的 State，
                    // 否则拖动时整个网格（几十个图标 + 小组件）会不停重组。
                    // 落点提示抽到了 DropHighlight 里，只重组它自己。
                    val isDragging = dragToken == token

                    Box(
                        Modifier
                            .offset(cellW * pl.col, pitchY * pl.row)
                            .size(
                                cellW * pl.colSpan,
                                rowH * pl.rowSpan + rowGapDp.dp * (pl.rowSpan - 1)
                            )
                            .zIndex(if (isDragging) 20f else 0f)
                            .onGloballyPositioned { cellRects[token] = it.boundsInRoot() }
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragDelta.x
                                    translationY = dragDelta.y
                                    val s = if (entry is HomeEntry.Widget) 1.02f else 1.06f
                                    scaleX = s; scaleY = s
                                    alpha = 0.94f
                                } else {
                                    translationX = 0f; translationY = 0f
                                    // 跟系统桌面一致：拿起一个图标后，本页其余图标缩小、变淡
                                    if (dragToken != null) {
                                        scaleX = 0.90f; scaleY = 0.90f; alpha = 0.68f
                                    } else {
                                        scaleX = 1f; scaleY = 1f; alpha = 1f
                                    }
                                }
                            }
                    ) {
                        // 源位置留下一个白色高亮空框，明确"从这里拿起来的"
                        if (isDragging) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .border(
                                        1.5.dp, Color.White.copy(alpha = 0.65f),
                                        RoundedCornerShape(18.dp)
                                    )
                            )
                        }
                        when (entry) {
                            is HomeEntry.Widget -> {
                                val wId = entry.appWidgetId
                                HomeSystemWidget(
                                    appWidgetId = wId,
                                    dark = dark,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = if (isDragging) 0.20f else 1f },
                                    // 编辑态按下就拿起，跟拖图标一样跟手（不用再等长按）
                                    immediateDrag = editMode,
                                    onLongPress = { lx, ly ->
                                        // 编辑态按住就走（按下即拿起）；常态沿用两段式
                                        if (editMode) beginDrag(token, Offset(lx, ly))
                                        else showTokenMenu(token, entry, Offset(lx, ly))
                                    },
                                    onDrag = { dx, dy ->
                                        val d = Offset(dx, dy)
                                        when {
                                            dragToken == token -> updateDrag(token, d)
                                            pendingMenuToken == token -> {
                                                pendingTravelPx += d.getDistance()
                                                continueFromMenu(token, pendingTravelPx, d)
                                            }
                                        }
                                    },
                                    onRelease = { moved -> finishDrag(moved) { widgetMenu = wId } },
                                    onCancel = {
                                        dragToken = null; hoverCell = null
                                        dragDelta = Offset.Zero
                                        pendingMenuToken = null
                                        menuShownForDrag = false
                                        dropRemoveArmed = false
                                        newPageArmed = false
                                        DuoState.dragging = false
                                    }
                                )

                                // 编辑模式：盖一层 Compose 手势 + 删除角标。
                                // 这条路径完全不经过 AppWidgetHostView 的触摸分发
                                // （长按要跨 View/Compose 两套事件体系，实机上并不可靠），
                                // 按住直接就能拖，保证「总能移动、总能删」。
                                if (editMode && !DuoState.lockLayout) {
                                    // 只画边框：拖动交给下面的 WidgetGestureBox（immediateDrag），
                                    // 这里再加一层 pointerInput 会把触摸全吃掉，反而拖不动。
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .border(
                                                1.dp, Color.White.copy(alpha = 0.35f),
                                                RoundedCornerShape(18.dp)
                                            )
                                    )
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xD91C1C1E))
                                            .clickable {
                                                runCatching { widgetBridge?.host?.deleteAppWidgetId(wId) }
                                                DuoState.removeHomeWidget(wId)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.Close, Lang.s(R.string.remove),
                                            tint = Color.White, modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }

                            else -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    Box(
                                        Modifier
                                            .graphicsLayer { alpha = if (isDragging) 0.25f else 1f }
                                            .pointerInput(token) {
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
                                                            val ch = ev.changes.firstOrNull {
                                                                it.id == down.id
                                                            } ?: break
                                                            if (!ch.pressed) { lifted = true; break }
                                                            if ((ch.position - down.position)
                                                                    .getDistance() > slop
                                                            ) { escaped = true; break }
                                                        }
                                                        done = true
                                                    }
                                                    if (!done && !lifted && !escaped) {
                                                        // 第一段：长按只弹快捷菜单（应用菜单 / 文件夹编辑 / 小组件菜单）
                                                        showTokenMenu(token, entry, down.position)
                                                        var moved = false
                                                        try {
                                                            while (true) {
                                                                // Initial pass 先取事件并立刻消费，
                                                                // 免得外面翻页 / 下滑手势把拖拽抢走
                                                                val ev = awaitPointerEvent(
                                                                    PointerEventPass.Initial
                                                                )
                                                                val ch = ev.changes.firstOrNull {
                                                                    it.id == down.id
                                                                } ?: break
                                                                if (!ch.pressed) { ch.consume(); break }
                                                                val d = ch.positionChangeIgnoreConsumed()
                                                                ch.consume()
                                                                if (d == Offset.Zero) continue
                                                                val total =
                                                                    (ch.position - down.position).getDistance()
                                                                if (dragToken != token) {
                                                                    // 菜单显示期间继续滑 → 收菜单、真正拿起
                                                                    if (continueFromMenu(token, total, d))
                                                                        moved = true
                                                                } else {
                                                                    updateDrag(token, d)
                                                                    if (dragDelta.getDistance() > slop)
                                                                        moved = true
                                                                }
                                                            }
                                                        } finally {
                                                            DuoState.dragging = false
                                                        }
                                                        // 菜单已在长按时弹过，这里不再重复弹
                                                        finishDrag(moved) {}
                                                    }
                                                }
                                            }
                                    ) {
                                        when (entry) {
                                            is HomeEntry.App -> {
                                                val app = entry.app
                                                AppIconCell(
                                                    app = app,
                                                    size = homeIconSize,
                                                    showLabel = true,
                                                    onWallpaper = true,
                                                    wobble = editMode,
                                                // 锁定布局时不给移除角标（设置里的批量清除入口仍可用）
                                                onMinusClick = if (editMode && !DuoState.lockLayout)
                                                    ({ removeTarget = app }) else null,
                                                    onClick = {
                                                        if (editMode)
                                                            nav.showAppMenu(app, "home")
                                                        else AppRepository.launch(ctx, app.id)
                                                    },
                                                    onLongClick = {}
                                                )
                                            }
                                            is HomeEntry.Folder -> FolderCell(
                                                folder = entry.folder,
                                                size = homeIconSize,
                                                onWallpaper = true,
                                                wobble = editMode,
                                                // 编辑态轻点也进编辑菜单（此时用户想改的是文件夹本身）
                                                onClick = {
                                                    if (editMode) folderMenu = entry.folder
                                                    else openFolder = entry.folder
                                                },
                                                onLongClick = {}
                                            )
                                            is HomeEntry.Widget -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 最后一页拖动时，右侧露出「新页面」框：拖进去松手就新开一页。
                // 比"贴边停一会儿等它翻页"直观得多，也不用先猜能不能新建。
                if (dragToken != null && onLastHomePage) {
                    // 命中区向左放宽一格的一半：窄条本身太细，手指很难恰好停在框里；
                    // 松手位置仍在最后一列时不算新页（见 finishDrag 的 cell 判定）。
                    val hitPad = with(LocalDensity.current) { 30.dp.toPx() }
                    NewPageTarget(
                        armed = newPageArmed,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(vertical = 6.dp)
                            .onGloballyPositioned {
                                val r = it.boundsInRoot()
                                newPageRect = Rect(r.left - hitPad, r.top, r.right, r.bottom)
                            }
                    )
                }

                // 落点高亮：只有一个，按当前 hoverCell 定位（只重组它自己）
                DropHighlight(
                    hover = hoverCellState,
                    dragging = dragTokenState,
                    cellW = cellW, rowH = rowH, pitchY = pitchY, gap = rowGapDp.dp,
                    homeCols = homeCols, homeRows = homeRows,
                    // 拖到另一个应用图标上：落点提示换成两个小方块，预告「会建文件夹」
                    isFolderTarget = { cell ->
                        dragTokenState.value?.startsWith("app:") == true && pagePlacements.any {
                            it.row == cell.first && it.col == cell.second &&
                                    it.token.startsWith("app:")
                        }
                    }
                )
            }
        }

        // ── 拖动中的悬浮图标（画在网格之上）──
        val draggedEntry = dragToken?.let { entryOf(it) }
        if (draggedEntry != null && draggedEntry !is HomeEntry.Widget) {
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
                when (draggedEntry) {
                    is HomeEntry.App -> AppIconCell(
                        app = draggedEntry.app, size = homeIconSize, showLabel = true,
                        onWallpaper = true,
                        onClick = {}, onLongClick = {}
                    )
                    is HomeEntry.Folder -> FolderCell(
                        folder = draggedEntry.folder, size = homeIconSize,
                        onWallpaper = true, onClick = {}, onLongClick = {}
                    )
                    is HomeEntry.Widget -> Unit
                }
            }
        }

        // ── 拖拽时的顶部「移除」目标（只从桌面拿掉，不卸载）──
        if (dragToken != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .zIndex(30f)
                    .onGloballyPositioned { dropRemoveRect = it.boundsInRoot() }
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (dropRemoveArmed) Color(0xFFD93A32) else Color(0xCC2C2C32),
                        RoundedCornerShape(22.dp)
                    )
                    .border(
                        1.dp, Color.White.copy(alpha = if (dropRemoveArmed) 0.9f else 0.45f),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Delete, null, tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        Lang.s(R.string.remove), color = Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
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

        // ── 顶部未读通知图标：紧挨「时间 + 三合一」横向排开（时间靠边、图标在它内侧），
        //    只占网格上方那一条空白，不挤压任何一行图标。系统状态栏显示时自动让位，
        //    避免和系统自己的通知图标重复叠在一起。──
        if (DuoState.showDuoStatus) {
            DuoNotifIcons(
                modifier = Modifier
                    .align(if (DuoState.statusRight) Alignment.TopEnd else Alignment.TopStart)
                    .padding(
                        top = statusDrawTop.dp,
                        // 多让 5dp：图标右上角的未读数字是向外探出来的，
                        // 紧贴 lane 会正好压到时间的第一位数字上
                        start = if (DuoState.statusRight) 0.dp else lane + 5.dp,
                        end = if (DuoState.statusRight) lane + 5.dp else 0.dp
                    )
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
                    onPanel = { showPlayerPanel = true },
                    onPlayPause = { DuoNotificationListener.togglePlayPause() }
                )
            }
        }

        // ── 底部：只剩页面指示点 ──
        // 左下角的菜单键和右下角的搜索键已经去掉：菜单项全部并入「长按主屏空白」的菜单，
        // 搜索走「主屏右滑」（设置里可开关）或资源库。底部就干净了，也不挡壁纸。
        if ((pageDots || dragToken != null) && homePageCount > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 18.dp)
                    .then(
                        if (dragToken != null) Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                        else Modifier
                    ),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
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
    }

    // 长按音乐胶囊 → 灵动岛式播放面板（收藏 / 进度 / 音量 / 上一首 / 播放暂停 / 下一首）
    if (showPlayerPanel) {
        PlayerPanel(dark = dark, onDismiss = { showPlayerPanel = false })
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

    // 文件夹展开（iOS 风格：只是「看一眼里面有什么」，改名/解散在长按菜单里）
    openFolder?.let { cached ->
        // 用 id 重新取一次：openFolder 存的是打开那一刻的实例，
        // 窗口开着时又移出了应用的话，直接用旧实例会导致列表不刷新
        val folder = DuoState.folderOf(cached.id) ?: cached
        FolderOpenDialog(
            folder = folder,
            dark = dark,
            onDismiss = { openFolder = null },
            onLaunch = { app -> AppRepository.launch(ctx, app.id); openFolder = null },
            onRemoveApp = { pkg -> DuoState.removeAppFromFolder(folder.id, pkg) }
        )
    }

    // 长按文件夹图标：编辑菜单
    folderMenu?.let { folder ->
        DuoDialog(
            title = folder.name,
            dark = dark,
            onDismiss = { folderMenu = null }
        ) {
            MenuRow(Icons.Filled.Edit, Lang.s(R.string.rename_folder), dark = dark) {
                folderMenu = null
                folderRename = folder
            }
            MenuRow(Icons.Filled.Delete, Lang.s(R.string.disband_folder), danger = true, dark = dark) {
                folderMenu = null
                DuoState.deleteFolder(folder.id)
            }
        }
    }

    folderRename?.let { folder ->
        FolderRenameDialog(
            initial = folder.name,
            dark = dark,
            onDismiss = { folderRename = null },
            onConfirm = { name ->
                DuoState.renameFolder(folder.id, name)
                folderRename = null
            }
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

    // 长按主屏空白：编辑主屏幕 / 打开设置

    // 主屏系统小组件：长按后弹菜单。位置可以直接拖动调整；跨屏拖不动时
    // （拖拽只在当前屏内有效）用这两个菜单项挪到相邻一屏。
    widgetMenu?.let { wId ->
        val wToken = "widget:$wId"
        // 与网格排版用同一口径算页（含手动坐标 + 自动补行），否则「上一屏/下一屏」会跳错
        val gridCols = DuoState.homeColumns
        val gridRows = DuoState.effectiveHomeRows(cfg.screenHeightDp, landscape)
        val allPages = DuoState.layoutHome(gridCols, gridRows)
        val wPage = allPages.indexOfFirst { list -> list.any { it.token == wToken } }
        DuoDialog(
            title = Lang.s(R.string.system_widget),
            dark = dark,
            onDismiss = { widgetMenu = null }
        ) {
            if (wPage > 0) {
                MenuRow(Icons.Filled.KeyboardArrowLeft, Lang.s(R.string.move_prev_screen), dark = dark) {
                    DuoState.moveHomeWidgetPage(wId, -1, gridCols, gridRows)
                    widgetMenu = null
                }
            }
            // 往右总是可以：目标屏放不下就自动新建一屏（坐标模型下不会"挤不进去"）
            if (wPage >= 0) {
                MenuRow(Icons.Filled.KeyboardArrowRight, Lang.s(R.string.move_next_screen), dark = dark) {
                    DuoState.moveHomeWidgetPage(wId, 1, gridCols, gridRows)
                    widgetMenu = null
                }
            }
            MenuRow(Icons.Filled.Delete, Lang.s(R.string.remove), danger = true, dark = dark) {
                runCatching { widgetBridge?.host?.deleteAppWidgetId(wId) }
                DuoState.removeHomeWidget(wId)
                widgetMenu = null
            }
        }
    }

    // 主屏菜单（长按空白处弹出）
    if (showHomeMenu) {
        DuoDialog(title = Lang.s(R.string.home_screen), dark = dark, onDismiss = { showHomeMenu = false }) {
            MenuRow(
                if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                if (editMode) Lang.s(R.string.done_edit) else Lang.s(R.string.edit_home), dark = dark
            ) { editMode = !editMode; showHomeMenu = false }
            // 以前只是跳到 App 资源库，用户还得再长按图标找「添加到主屏幕」，很容易以为没生效。
            // 现在直接弹一个可搜索的应用选择器，点一下就加到桌面。
            MenuRow(Icons.Filled.AddToHomeScreen, Lang.s(R.string.add_app_to_home), dark = dark) {
                showHomeMenu = false; appPickerOpen = true
            }
            MenuRow(Icons.Filled.Widgets, Lang.s(R.string.add_system_widget), dark = dark) {
                showHomeMenu = false; nav.addHomeWidget()
            }
            MenuRow(Icons.Filled.Tune, Lang.s(R.string.launcher_settings), dark = dark) {
                showHomeMenu = false; nav.openSettings()
            }
            MenuRow(Icons.Filled.Wallpaper, Lang.s(R.string.change_wallpaper), dark = dark) {
                AppRepository.wallpaper(ctx); showHomeMenu = false
            }
        }
    }

    // 「添加应用到主屏幕」：直接选应用，点一下加到桌面（不再跳资源库）
    if (appPickerOpen) {
        HomeAppPickerDialog(
            dark = dark,
            onDismiss = { appPickerOpen = false },
            onPick = { app ->
                DuoState.addHome(app.id)
                nav.toast(Lang.s(R.string.toast_app_added_home, app.label))
            }
        )
    }
}

private sealed class HomeEntry {
    data class App(val app: AppInfo) : HomeEntry()
    data class Folder(val folder: FolderItem) : HomeEntry()
    /** 主屏上的安卓系统小组件（AppWidget），独占一整行 */
    data class Widget(val appWidgetId: Int) : HomeEntry()
}

/**
 * 主屏系统小组件：宽度吃满整行，高度**严格等于它占的格子行数**。
 *
 * 以前高度取 provider 的 minHeight（还夹到 120~340dp），跟分页里预留的行数各算一套，
 * 于是出现「占了两行、实际画出来一大一小」的错位：小组件比格子高就顶出去，
 * 比格子矮就留一大片空白。现在高度由行数算出，并把真实尺寸通过
 * `updateAppWidgetSize` 告知 provider，自适应的小组件才会按整行排版。
 */
@Composable
private fun HomeSystemWidget(
    appWidgetId: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    immediateDrag: Boolean = false,
    onLongPress: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onRelease: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    // 宽高不再自己算：父容器已经按「占几行」给了精确的行槽，这里量出来报给 provider，
    // 保证「看到的大小 == 告诉系统的大小」，自适应小组件（日历、天气）才会正确排版。
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = maxWidth.value.toInt().coerceAtLeast(1)
        val h = maxHeight.value.toInt().coerceAtLeast(1)
        SystemWidgetCardDraggable(
            appWidgetId = appWidgetId,
            dark = dark,
            modifier = Modifier.fillMaxSize(),
            sizeDp = IntSize(w, h),
            immediateDrag = immediateDrag,
            onLongPress = onLongPress,
            onDrag = onDrag,
            onRelease = onRelease,
            onCancel = onCancel
        )
    }
}

/**
 * 落点提示（半透明方框）。
 *
 * 参数收的是 State 而不是值：拖动时 hoverCell 每跨一格都会变，
 * 如果让调用方（网格作用域）读值，整个主屏几十个图标都会重组一遍。
 * 在这里读，重组范围就只有这一个方框。
 */
@Composable
private fun DropHighlight(
    hover: androidx.compose.runtime.State<Pair<Int, Int>?>,
    dragging: androidx.compose.runtime.State<String?>,
    cellW: Dp,
    rowH: Dp,
    pitchY: Dp,
    gap: Dp,
    homeCols: Int,
    homeRows: Int,
    isFolderTarget: (Pair<Int, Int>) -> Boolean = { false }
) {
    val hc = hover.value ?: return
    val token = dragging.value ?: return
    val rs = DuoState.tokenRowSpan(token, homeRows)
    val cs = DuoState.tokenColSpan(token, homeCols)
    Box(
        Modifier
            .offset(cellW * hc.second, pitchY * hc.first)
            .size(cellW * cs, rowH * rs + gap * (rs - 1))
            .padding(2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        // 目标是另一个应用：画两个小方块，预告松手会合成文件夹
        if (isFolderTarget(hc)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.62f))
                    )
                }
            }
        }
    }
}

/**
 * 最后一页拖动时的「新页面」投放框（右侧竖条）。
 * 手指进去就加宽高亮，松手新建一页并把条目放进去——比贴边等待翻页直接。
 */
@Composable
private fun NewPageTarget(armed: Boolean, modifier: Modifier) {
    val w by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (armed) 68.dp else 46.dp,
        animationSpec = tween(160),
        label = "newPageW"
    )
    Column(
        modifier
            .width(w)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (armed) 0.26f else 0.11f))
            .border(
                if (armed) 2.dp else 1.5.dp,
                Color.White.copy(alpha = if (armed) 0.88f else 0.45f),
                RoundedCornerShape(20.dp)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Add, null,
            tint = Color.White.copy(alpha = if (armed) 1f else 0.8f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            Lang.s(R.string.new_page),
            color = Color.White.copy(alpha = if (armed) 1f else 0.85f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 音乐胶囊高度：顶部定位与「侧栏避让胶囊」共用这一个值，改一处两处同步 */
private val NpCapsuleHeight = 34.dp

/**
 * 右上角「正在播放」胶囊（紧凑版，对齐参考图）：
 * 左侧圆形专辑封面 + 右侧 iOS 风格频谱（5 条、中间高两端低、随律动起伏）。
 * 单击展开播放面板；长按暂停 / 继续——胶囊很小，不拆热区，避免点不中或误触。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingCapsule(
    np: NowPlaying,
    dark: Boolean,
    modifier: Modifier,
    onPanel: () -> Unit,
    onPlayPause: () -> Unit
) {
    GlassCard(
        modifier = modifier
            .height(NpCapsuleHeight)
            .combinedClickable(onClick = onPanel, onLongClick = onPlayPause),
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
            // iOS 风格频谱：与上面的时间同色（按壁纸明暗自适应黑 / 白），
            // 状态栏整体关闭、还拿不到明暗结论时回退到玻璃文字色
            MusicSpectrum(
                playing = np.playing,
                color = rememberStatusInkColor(glassTextColor(dark))
            )
        }
    }
}

/**
 * 点胶囊弹出的「灵动岛」播放面板：大封面 + 歌名 + 可拖动进度条 +
 * 收藏 / 上一首 / 暂停 / 下一首 / 歌词（播放器支持时）。
 *
 * 进度用 [DuoNotificationListener.snapshot] 每 500ms 取一次，
 * 只重组这一个 Dialog，不碰 DuoState.nowPlaying（否则整个主屏每秒重画）。
 */
@Composable
private fun PlayerPanel(
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val np = DuoState.nowPlaying
    if (np == null) {
        onDismiss()
        return
    }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(500L)
        }
    }
    // tick 只用于触发重组；进度从会话实时快照读取，不写回 DuoState
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val snap = DuoNotificationListener.snapshot()
    val duration = snap?.duration ?: 0L
    val canSeek = duration > 0 && DuoNotificationListener.canSeek()
    var dragPos by remember { mutableFloatStateOf(-1f) }
    val shownPos = if (dragPos >= 0f) dragPos.toLong() else (snap?.positionNow() ?: 0L)
    val favKey = DuoNotificationListener.favoriteKey(np)
    var fav by remember(favKey) { mutableStateOf(DuoState.mediaFavorites[favKey] == true) }
    // 歌词按钮是否可见：设置里开启，且当前播放器提供了歌词入口
    val showLyric = DuoState.showLyricButton && DuoNotificationListener.hasLyricAction()
    // 封面 / 歌名点击都回到播放器，并收起面板
    val openApp: () -> Unit = {
        DuoNotificationListener.openMediaApp(ctx)
        onDismiss()
    }

    // 位置由设置决定（默认居中，避免贴顶时压住右上角时间和三合一状态）。
    // 贴顶时从左上角挖孔右侧开始；靠底时留出系统手势条高度。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val position = DuoState.playerPanelPosition
        // 左右用同一套边距，三个位置卡片都落在同一条水平中心线上；
        // 80dp 左边距同时避开左上角挖孔，卡片最大宽度固定，平板也不会被拉宽。
        val sideAvoid = 80.dp
        val alignment: Alignment = when (position) {
            "top" -> Alignment.TopCenter
            "bottom" -> Alignment.BottomCenter
            else -> Alignment.Center
        }
        val padding = when (position) {
            "top" -> PaddingValues(start = sideAvoid, end = sideAvoid, top = 10.dp)
            "bottom" -> PaddingValues(start = sideAvoid, end = sideAvoid, bottom = 28.dp)
            else -> PaddingValues(horizontal = sideAvoid)
        }
        // 卡片外空白点击即关闭面板（Dialog 内容铺满屏幕，onDismissRequest 不会因空白处触发）
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(padding)
        ) {
            GlassCard(
                // 消费卡片范围内的点击，避免卡片内空白区误触关闭
                modifier = Modifier
                    .align(alignment)
                    .fillMaxWidth()
                    .widthIn(max = 350.dp)
                    .wrapContentHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                radius = 28.dp, level = 0.9f, fill = 1f, dark = dark
            ) {
                Column(Modifier.padding(20.dp)) {
                // 标题行：封面 + 歌名歌手 + 频谱
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(11.dp))
                            .background(glassTextColor(dark).copy(alpha = 0.14f))
                            .clickable(onClick = openApp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (np.art != null) {
                            Image(
                                bitmap = np.art.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Filled.MusicNote, null, tint = glassSubTextColor(dark), modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f).clickable(onClick = openApp)) {
                        Text(np.title.ifBlank { Lang.s(R.string.unknown_title) },
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = glassTextColor(dark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(np.artist, fontSize = 12.sp, color = glassSubTextColor(dark),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    val panelAccent = remember(np.artKey, np.art, dark) {
                        coverAccentColor(np.art, if (dark) onGlassDark else onGlassLight)
                    }
                    MusicSpectrum(playing = np.playing, color = panelAccent)
                }

                Spacer(Modifier.height(20.dp))

                // 进度条：拖动时本地预览，松手才真正 seek
                val positionText = remember(shownPos) { fmtTime(shownPos) }
                val remainText = remember(shownPos, duration) {
                    if (duration > 0) "-${fmtTime((duration - shownPos).coerceAtLeast(0))}" else "--:--"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(positionText, fontSize = 11.sp, color = glassSubTextColor(dark))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Slider(
                            value = if (duration > 0) shownPos.coerceIn(0, duration).toFloat() else 0f,
                            onValueChange = { if (canSeek) dragPos = it.toDouble().toLong().toFloat() },
                            onValueChangeFinished = {
                                if (canSeek && dragPos >= 0f) DuoNotificationListener.seekTo(dragPos.toLong())
                                dragPos = -1f
                            },
                            valueRange = 0f..(duration.coerceAtLeast(1).toFloat()),
                            enabled = canSeek,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = glassTextColor(dark),
                                activeTrackColor = glassTextColor(dark),
                                inactiveTrackColor = glassTextColor(dark).copy(alpha = 0.22f),
                                disabledThumbColor = glassTextColor(dark).copy(alpha = 0.4f),
                                disabledActiveTrackColor = glassTextColor(dark).copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(remainText, fontSize = 11.sp, color = glassSubTextColor(dark))
                }
                if (!canSeek) {
                    Text(Lang.s(R.string.np_no_seek), fontSize = 10.sp, color = glassSubTextColor(dark))
                }

                Spacer(Modifier.height(12.dp))

                // 控制行：收藏 / 上一首 / 播放暂停 / 下一首 / 歌词（支持时显示）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        DuoNotificationListener.toggleFavorite(ctx)
                        fav = DuoState.mediaFavorites[favKey] == true
                    }) {
                        Icon(
                            if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (fav) Color(0xFFFFD34E) else glassSubTextColor(dark),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { DuoNotificationListener.skipPrevious() }) {
                        Icon(Icons.Filled.SkipPrevious, null, tint = glassTextColor(dark), modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { DuoNotificationListener.togglePlayPause() }) {
                        Icon(
                            if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null, tint = glassTextColor(dark), modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = { DuoNotificationListener.skipNext() }) {
                        Icon(Icons.Filled.SkipNext, null, tint = glassTextColor(dark), modifier = Modifier.size(28.dp))
                    }
                    // 歌词：调用播放器自带的歌词入口（网易云为 lyric 自定义 action，
                    // 实际是开/关桌面歌词）。播放器不向系统暴露歌词开关的真实状态，
                    // 所以这里只做纯切换、不显示对勾，避免出现状态不一致。
                    // 不支持时留同宽占位，保持一行 5 个槽位的间距不变。
                    if (showLyric) {
                        IconButton(onClick = { DuoNotificationListener.openLyrics(ctx) }) {
                            LyricIcon(dark = dark)
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
        }
    }

}

/** mm:ss（超过一小时按 h:mm:ss） */
private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

/**
 * 从专辑封面提取主色，给频谱着色，让胶囊跟当前歌曲的封面颜色一致。
 * 降采样到 20×20 做颜色分桶，跳过近白/近黑/低饱和像素，取数量最多的色桶；
 * 最后把亮度归一到中等偏亮，保证在深/浅两种玻璃底上都看得清。取不到就回退到 [fallback]。
 */
private fun coverAccentColor(bitmap: Bitmap?, fallback: Color): Color {
    if (bitmap == null || bitmap.isRecycled) return fallback
    return try {
        val w = 20
        val h = 20
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val buckets = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val c = small.getPixel(x, y)
            if (((c ushr 24) and 0xFF) < 180) continue     // 跳过透明
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx - mn < 24 || mx < 40 || mn > 220) continue   // 跳过近灰/近黑/近白
            val key = ((r / 32) shl 10) or ((g / 32) shl 5) or (b / 32)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val best = buckets.maxByOrNull { it.value }?.key ?: return fallback
        var r = ((best shr 10) and 0x7) * 32 + 16
        var g = ((best shr 5) and 0x7) * 32 + 16
        var b = (best and 0x7) * 32 + 16
        val lum = r * 0.299f + g * 0.587f + b * 0.114f
        if (lum in 1f..255f) {
            val k = (190f / lum).coerceIn(0.6f, 1.8f)
            r = (r * k).toInt().coerceIn(0, 255)
            g = (g * k).toInt().coerceIn(0, 255)
            b = (b * k).toInt().coerceIn(0, 255)
        }
        Color(r, g, b)
    } catch (e: Throwable) {
        fallback
    }
}

/**
 * 胶囊右侧的迷你频谱（对齐 iOS 灵动岛那种「中间高、两边低」的 5 条）。
 * 系统媒体会话拿不到音频采样，这里用不同周期的三角波模拟律动。
 *
 * 性能：旧实现每帧改 5 个 Box 的 height —— 高度是布局参数，等于每帧重排整颗胶囊，
 * 120Hz 机型实测播放时占用 >1 个核（主线程 69% + RenderThread 58%）。
 * 现在整条频谱是一个 [Canvas]：高度变化只在「绘制」阶段算，相位时钟只在 draw
 * 里读取（不进组合），并把刷新率压到 24fps —— 小频谱本来也用不上 120fps。
 * 暂停时不挂任何动画协程，直接画静态矮条，桌面回到 0 帧空闲。
 */
@Composable
private fun MusicSpectrum(playing: Boolean, color: Color) {
    val base = remember { floatArrayOf(5f, 10f, 15f, 10f, 5f) }   // 各条满格高度（dp）
    val barW = 2.5.dp
    val gap = 2.5.dp
    val maxH = 15.dp
    // 相位时钟：只在绘制阶段被读取，所以更新它只会让这块重绘，不会重组/重排
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (!playing) {
            clock.longValue = 0L
            return@LaunchedEffect
        }
        // 用 delay 而不是 withFrameNanos：后者会让 Choreographer 每个 vsync（120Hz）
        // 都回调一次主线程，纯属为了等一个 15fps 的节拍。
        while (true) {
            clock.longValue = System.nanoTime()
            kotlinx.coroutines.delay(66L)
        }
    }
    Canvas(
        Modifier
            .width(barW * base.size + gap * (base.size - 1))
            .height(maxH)
            // 单独一层 RenderNode：这样每帧只重录这几条小竖条，
            // 不会连带把整屏（壁纸 + 图标 + 文字）重录一遍
            .graphicsLayer()
    ) {
        val t = clock.longValue / 1_000_000_000f
        val w = barW.toPx()
        val step = w + gap.toPx()
        val full = size.height
        base.forEachIndexed { i, peak ->
            val ratio = if (playing) {
                // 周期 / 相位各条不同，凑出「各跳各的」观感
                val period = 0.42f + i * 0.075f
                val phase = (t / (period * 2f) + i * 0.13f) % 1f
                val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                0.30f + 0.70f * tri
            } else 0.45f
            val h = (full * (peak / 15f) * ratio).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = color.copy(alpha = if (playing) 0.92f else 0.32f),
                topLeft = Offset(i * step, (full - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f)
            )
        }
    }
}

/**
 * 歌词按钮：仿网易云通知中心的「词」字样式，字色与通知文字一致。
 * 播放器不暴露歌词开关状态，所以不加对勾，只作切换动作。
 * 用文字直接绘制，避免图标资源在不同密度下糊掉。
 */
@Composable
private fun LyricIcon(dark: Boolean) {
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Text(
            Lang.s(R.string.np_lyric_char),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = glassTextColor(dark),
            maxLines = 1
        )
    }
}

/**
 * 点击文件夹后展开的「窗口」——**只用于查看**（iOS 那种：看一眼里面有什么，点图标直接启动）。
 *
 * 改名 / 解散属于编辑操作，放在「长按文件夹图标」弹出的菜单里，
 * 不再挤在这个窗口底部，免得每次打开文件夹都像进了编辑页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderOpenDialog(
    folder: FolderItem,
    dark: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onRemoveApp: (String) -> Unit
) {
    /** 长按文件夹内的图标 → 待确认「移出文件夹」 */
    var removeTarget by remember { mutableStateOf<AppInfo?>(null) }
    val apps = folder.apps.mapNotNull { DuoState.appOf(it) }
    // iOS 风格：名字在框外左上角、不显示应用数、框内 4 列分页（满了右滑下一页）
    FolderIosWindow(
        name = folder.name,
        apps = apps,
        onDismiss = onDismiss,
        onLaunch = onLaunch,
        onLongClick = { removeTarget = it }
    )

    removeTarget?.let { app ->
        DuoDialog(
            title = Lang.s(R.string.remove_from_folder),
            dark = dark,
            onDismiss = { removeTarget = null }
        ) {
            Text(
                Lang.s(R.string.remove_from_folder_confirm, app.label),
                fontSize = 13.sp, color = glassSubTextColor(dark)
            )
            Spacer(Modifier.height(12.dp))
            MenuRow(Icons.Filled.Delete, Lang.s(R.string.remove_from_folder), danger = true, dark = dark) {
                onRemoveApp(app.id)
                removeTarget = null
            }
            MenuRow(Icons.Filled.Close, Lang.s(R.string.cancel), dark = dark) { removeTarget = null }
        }
    }
}

/** 文件夹改名：只弹一个输入框，保存即改名 */
@Composable
private fun FolderRenameDialog(
    initial: String,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    DuoDialog(title = Lang.s(R.string.rename_folder), dark = dark, onDismiss = onDismiss) {
        BasicTextField(
            name, { name = it }, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = glassTextColor(dark)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(glassTextColor(dark)),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(glassTextColor(dark).copy(alpha = 0.12f)).padding(12.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPrimaryButton(Lang.s(R.string.save_name), dark, {
                val n = name.trim()
                if (n.isNotEmpty()) onConfirm(n) else onDismiss()
            })
            GlassPrimaryButton(Lang.s(R.string.cancel), dark, onDismiss)
        }
    }
}
