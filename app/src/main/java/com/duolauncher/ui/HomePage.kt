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
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }      // 本页在根坐标系的原点
    val statusTopPad = if (DuoState.statusRight) DuoState.statusTop
    else maxOf(DuoState.statusTop, 64)
    // 时间 + 电量三合一再往上提：只挪绘制位置，网格 topGap 仍用 statusTopPad，不动图标行距。
    // 侧栏「靠下居中」的基准也用这个真实下沿（见下方 sbRegionTop），两者始终对齐。
    val statusDrawTop = (statusTopPad - 20f).coerceAtLeast(2f)
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
                        when {
                            // ① 无障碍服务已连上
                            ScreenOff.lock() -> Unit
                            // ② 备用：设备管理员 lockNow。覆盖安装后无障碍绑定会失效，
                            //    开了备用方式时这里仍能正常息屏，用户不必去设置里重开一次。
                            ScreenOff.lockByAdmin(ctx) -> Unit
                            // ③ 系统里显示已开启、但服务没连上（覆盖安装后的典型状态）。
                            //    旧逻辑在这里只说「正在启动，请稍后再试」，而它永远不会启动 ——
                            //    必须明确告诉用户「关一次再打开」，并直接跳到本服务的详情页。
                            ScreenOff.isEnabled(ctx) -> {
                                nav.toast(Lang.s(R.string.toast_sleep_rebind))
                                ScreenOff.guideToRebind(ctx)
                            }
                            // ④ 从未开启过
                            else -> {
                                ScreenOff.requestPermission(ctx)
                                nav.toast(Lang.s(R.string.toast_enable_accessibility))
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
        val edgeFlipPx = with(density) { 14.dp.toPx() }
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

        fun beginDrag(token: String, offsetInBox: Offset) {
            val r = cellRects[token] ?: Rect.Zero
            dragToken = token
            hoverCell = null
            dragFromRect = r
            dragDelta = Offset.Zero
            dragFinger = r.topLeft + offsetInBox
            edgeDwellStart = 0L
            DuoState.dragging = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        /** 落位后如果条目被挪到了别的屏，跟过去，别让用户觉得"拖了一下它不见了" */
        fun followToken(token: String) {
            val at = DuoState.layoutHome(homeCols, homeRows)
                .indexOfFirst { list -> list.any { it.token == token } }
            if (at >= 0 && at != homePageIndex) nav.goPage(at)
        }

        /** 松手落位：图标 / 文件夹 / 小组件共用一套（[onTap] = 没拖动时当作长按） */
        fun finishDrag(moved: Boolean, onTap: () -> Unit) {
            val token = dragToken ?: return
            val cell = hoverCell
            val fingerX = dragFinger.x
            val edgeHeld = edgeDwellStart != 0L &&
                    System.currentTimeMillis() - edgeDwellStart >= edgeDwellMs
            edgeDwellStart = 0L
            dragToken = null
            hoverCell = null
            dragDelta = Offset.Zero
            DuoState.dragging = false
            if (!moved) { onTap(); return }
            when {
                // 拖到边缘并停留够了：整块挪到相邻一屏；只是顺手划过就落到边格
                fingerX <= edgeFlipPx && edgeHeld -> {
                    if (!DuoState.moveTokenToPage(token, -1, homeCols, homeRows)) onTap()
                    else nav.goPage((homePageIndex - 1).coerceAtLeast(0))
                }
                fingerX >= screenWidthPx - edgeFlipPx && edgeHeld -> {
                    if (!DuoState.moveTokenToPage(token, 1, homeCols, homeRows)) onTap()
                    else nav.goPage(homePageIndex + 1)
                }
                // 落在某行某列（空白格也算）：直接放到那一格，目标格已有条目则交换
                cell != null -> {
                    // 落在文件夹上：把应用塞进文件夹（iOS 手感），不要再走「同格交换」
                    val targetToken = pagePlacements.firstOrNull {
                        it.row == cell.first && it.col == cell.second && it.token != token
                    }?.token
                    val folderToken = targetToken?.takeIf { it.startsWith("folder:") }
                    if (folderToken != null && token.startsWith("app:")) {
                        val folderId = folderToken.removePrefix("folder:")
                        val folderName = DuoState.folderOf(folderId)?.name.orEmpty()
                        DuoState.addAppToFolder(folderId, token.removePrefix("app:"))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        nav.toast(Lang.s(R.string.toast_app_in_folder, folderName))
                    } else {
                        val ok = DuoState.placeToken(
                            token, homePageIndex, cell.first, cell.second, homeCols, homeRows
                        )
                        if (ok) followToken(token) else onTap()
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
                                    scaleX = 1f; scaleY = 1f; alpha = 1f
                                }
                            }
                    ) {
                        when (entry) {
                            is HomeEntry.Widget -> {
                                val wId = entry.appWidgetId
                                HomeSystemWidget(
                                    appWidgetId = wId,
                                    dark = dark,
                                    modifier = Modifier.fillMaxSize(),
                                    // 编辑态按下就拿起，跟拖图标一样跟手（不用再等长按）
                                    immediateDrag = editMode,
                                    onLongPress = { lx, ly -> beginDrag(token, Offset(lx, ly)) },
                                    onDrag = { dx, dy ->
                                        dragDelta += Offset(dx, dy)
                                        dragFinger += Offset(dx, dy)
                                        hoverCell = cellForDrop(token)
                                        trackEdge(dragFinger.x)
                                    },
                                    onRelease = { moved -> finishDrag(moved) { widgetMenu = wId } },
                                    onCancel = {
                                        dragToken = null; hoverCell = null
                                        dragDelta = Offset.Zero
                                        DuoState.dragging = false
                                    }
                                )

                                // 编辑模式：盖一层 Compose 手势 + 删除角标。
                                // 这条路径完全不经过 AppWidgetHostView 的触摸分发
                                // （长按要跨 View/Compose 两套事件体系，实机上并不可靠），
                                // 按住直接就能拖，保证「总能移动、总能删」。
                                if (editMode) {
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
                                                        // 长按成立 → 拿起，之后可以拖到任意一格
                                                        beginDrag(token, down.position)
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
                                                                dragDelta += d
                                                                dragFinger += d
                                                                if (dragDelta.getDistance() > slop)
                                                                    moved = true
                                                                hoverCell = cellForDrop(token)
                                                                trackEdge(dragFinger.x)
                                                            }
                                                        } finally {
                                                            DuoState.dragging = false
                                                        }
                                                        finishDrag(moved) {
                                                            when (entry) {
                                                                is HomeEntry.App ->
                                                                    nav.showAppMenu(entry.app, "home")
                                                                // 长按文件夹 = 编辑（重命名 / 解散），
                                                                // 和 iOS 一致：打开看内容只是轻点
                                                                is HomeEntry.Folder ->
                                                                    folderMenu = entry.folder
                                                                is HomeEntry.Widget -> Unit
                                                            }
                                                        }
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
                                                    // 编辑态左上角显示 iOS 风格「-」，点它弹移除确认
                                                    onMinusClick = if (editMode)
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

                // 落点高亮：只有一个，按当前 hoverCell 定位（只重组它自己）
                DropHighlight(
                    hover = hoverCellState,
                    dragging = dragTokenState,
                    cellW = cellW, rowH = rowH, pitchY = pitchY, gap = rowGapDp.dp,
                    homeCols = homeCols, homeRows = homeRows
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
                    onPanel = { showPlayerPanel = true },
                    onPlayPause = { DuoNotificationListener.togglePlayPause() }
                )
            }
        }

        // ── 底部：只剩页面指示点 ──
        // 左下角的菜单键和右下角的搜索键已经去掉：菜单项全部并入「长按主屏空白」的菜单，
        // 搜索走「主屏右滑」（设置里可开关）或资源库。底部就干净了，也不挡壁纸。
        if (pageDots && homePageCount > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
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
    homeRows: Int
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
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
    )
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
