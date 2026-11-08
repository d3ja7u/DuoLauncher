package com.duolauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.duolauncher.data.*
import com.duolauncher.ui.*
import com.duolauncher.util.InstallStats
import com.duolauncher.util.Lang
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Home 键回到主屏的信号 */
    private var homeSignal by mutableStateOf(0)
    private var packageReceiver: BroadcastReceiver? = null

    /** 手动指定的语言在 attachBaseContext 阶段就要生效，否则首帧仍是旧语言 */
    override fun attachBaseContext(newBase: Context) {
        val wrapped = Lang.wrap(newBase)
        // 用 Activity 的配置刷新文案 Context，兼容系统设置里的「按应用设定语言」
        Lang.init(wrapped)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DuoState.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        reloadApps()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { reloadApps() }
        }
        runCatching { packageReceiver?.let { registerReceiver(it, filter) } }

        setContent { DuoRoot() }
    }

    override fun onResume() {
        super.onResume()
        reloadApps()
        // 从系统「通知使用权」授权页返回、或监听服务被回收后回到桌面，都重新对齐一次角标
        runCatching { com.duolauncher.service.DuoNotificationListener.ensureBound(this) }
        // 匿名统计：只有已确认告知且开关打开才会联网，内部有 24 小时心跳节流
        InstallStats.reportIfAllowed(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            homeSignal++
            reloadApps()
        }
    }

    override fun onDestroy() {
        runCatching { packageReceiver?.let { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun reloadApps() {
        lifecycleScope.launch {
            val list = AppRepository.loadApps(this@MainActivity)
            DuoState.apps.clear()
            DuoState.apps.addAll(list)
            DuoState.ensureDefaults()
            DuoState.appLoadDone = true
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DuoRoot() {
        val ctx = this@MainActivity
        val dark = DuoState.darkGlass
        val wallpaper = rememberWallpaperBitmap()

        // 主屏分页：5×4 = 每页 20 个（含文件夹）。整页序列 = 主屏N页 + 资源库
        // 负一屏已下线（先去掉），最左边就是主屏第一页
        val cellCount = DuoState.homeApps.count { !DuoState.hidden.contains(it) } + DuoState.folders.size
        val homePageCount = ((cellCount + 19) / 20).coerceAtLeast(1)
        val totalPages = homePageCount + 1
        val libraryPage = homePageCount
        val pagerState = rememberPagerState(initialPage = 0) { totalPages }

        var overlay by remember {
            mutableStateOf(if (Lang.consumeReopenSettings(ctx)) "settings" else null)
        }
        var settingsSub by remember { mutableStateOf<String?>(null) }
        // 首次启动的一次性统计告知：未确认前 InstallStats 不会发任何请求
        var statsConsentVisible by remember {
            mutableStateOf(!DuoState.statsConsentShown && DuoState.statsEnabled)
        }

        // 搜索页揭示进度：0 = 桌面，1 = 搜索页完全覆盖。
        // 右滑手势逐帧直接写 reveal（跟手），松手 / 点击时由 spring 逐帧写回，
        // 观感与 HorizontalPager 翻到资源库一致。
        var reveal by remember { mutableFloatStateOf(0f) }
        // 用固定时长的缓动曲线，不用 spring：spring 在这个位移量下 ~120ms 就收敛完，
        // 非跟手的进入 / 关闭（点搜索键、上滑、返回键）会看起来像瞬移。
        val revealSpec = remember {
            tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
        }
        var revealJob by remember { mutableStateOf<Job?>(null) }
        var searchMounted by remember { mutableStateOf(false) }
        var searchFocused by remember { mutableStateOf(false) }
        var rootWidth by remember { mutableFloatStateOf(0f) }
        // 只有搜索页真正停稳才申请输入焦点，否则手势刚起手 IME 就弹出来了
        LaunchedEffect(Unit) {
            snapshotFlow { reveal >= 0.98f }
                .distinctUntilChanged()
                .collect { searchFocused = it }
        }
        var menuApp by remember { mutableStateOf<AppInfo?>(null) }
        var menuSource by remember { mutableStateOf("home") }
        var categoryTarget by remember { mutableStateOf<AppInfo?>(null) }
        var iconTarget by remember { mutableStateOf<AppInfo?>(null) }
        var folderTarget by remember { mutableStateOf<AppInfo?>(null) }

        val widgetBridge = remember {
            WidgetBridge(
                AppWidgetHost(ctx, 1024),
                AppWidgetManager.getInstance(ctx),
                ctx
            )
        }
        DisposableEffect(Unit) {
            runCatching { widgetBridge.host.startListening() }
            onDispose { runCatching { widgetBridge.host.stopListening() } }
        }

        var pendingWidgetId by remember { mutableIntStateOf(-1) }
        val widgetManager = widgetBridge.manager

        fun finishBind(id: Int) {
            val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
            if (info != null) {
                DuoState.widgets.add(WidgetItem("system", id.toString()))
                DuoState.save()
                toast(Lang.s(R.string.toast_widget_added))
            } else toast(Lang.s(R.string.toast_widget_bind_fail))
        }

        val configLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishBind(pendingWidgetId)
        }
        val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishBind(pendingWidgetId)
        }

        fun bindAndSave(id: Int, info: android.appwidget.AppWidgetProviderInfo) {
            val allowed = runCatching { widgetManager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
            if (allowed) finishBind(id)
            else {
                runCatching {
                    bindLauncher.launch(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    )
                }
            }
        }

        val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
            pendingWidgetId = id
            val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
            if (info == null) {
                toast(Lang.s(R.string.toast_widget_none))
            } else if (info.configure != null) {
                runCatching {
                    configLauncher.launch(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                            .setComponent(info.configure)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    )
                }
            } else bindAndSave(id, info)
        }

        fun startPickWidget() {
            runCatching {
                val id = widgetBridge.host.allocateAppWidgetId()
                pendingWidgetId = id
                pickLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                )
            }.onFailure { toast(Lang.s(R.string.toast_widget_picker_fail)) }
        }

        // 注意：必须用 Compose 的 rememberCoroutineScope。
        // Activity.lifecycleScope 的 CoroutineContext 里没有 MonotonicFrameClock，
        // 调 animateScrollToPage 会抛 IllegalStateException（点「添加应用到主屏幕」就崩）。
        val uiScope = rememberCoroutineScope()

        /** 从当前进度弹性动画到目标值，逐帧写回 reveal；手势再次开始时取消上一段动画 */
        fun animateReveal(target: Float, onEnd: () -> Unit = {}) {
            revealJob?.cancel()
            if (target > 0f) searchMounted = true
            revealJob = uiScope.launch {
                AnimationState(reveal).animateTo(target, revealSpec) { reveal = value }
                onEnd()
            }
        }

        fun dismissSearchInstant() {
            revealJob?.cancel()
            reveal = 0f
            searchMounted = false
            searchFocused = false
        }

        /** 关闭搜索：先滑出去，动画结束后再摘掉面板，避免「硬切」 */
        fun dismissSearchAnimated() {
            animateReveal(0f) {
                searchMounted = false
                searchFocused = false
            }
        }

        // 真实页序号 ↔ pager 下标（开启"倒序翻页"时两者相反）
        fun toPagerPage(actual: Int): Int {
            val a = actual.coerceIn(0, totalPages - 1)
            return if (DuoState.reversePages) totalPages - 1 - a else a
        }

        val nav = remember(totalPages, libraryPage) {
            Nav(
                openSearch = {
                    // 点右下角搜索键 / 动作触发：同样走滑入，不再硬切
                    animateReveal(1f)
                },
                searchDrag = { p ->
                    revealJob?.cancel()
                    searchMounted = true
                    reveal = p
                },
                searchRelease = { commit ->
                    // 跟 pager 一样：过半就翻过去，否则弹回；快速短滑也算翻过去
                    val finish = reveal >= 0.45f || (commit && reveal >= 0.12f)
                    animateReveal(if (finish) 1f else 0f) {
                        if (!finish) {
                            searchMounted = false
                            searchFocused = false
                        }
                    }
                },
                searchCloseDrag = { p ->
                    // 左滑时搜索页跟着手指往左退，桌面同步回位
                    revealJob?.cancel()
                    reveal = p
                },
                searchCloseRelease = { commit ->
                    // 过半或快速短滑 → 关回桌面；否则弹回搜索页，两边都走 spring
                    val close = commit || reveal < 0.55f
                    animateReveal(if (close) 0f else 1f) {
                        if (close) {
                            searchMounted = false
                            searchFocused = false
                        }
                    }
                },
                openSettings = { dismissSearchInstant(); settingsSub = null; overlay = "settings" },
                openSidebarEditor = { dismissSearchInstant(); settingsSub = "sidebar"; overlay = "settings" },
                openCategoryEditor = { dismissSearchInstant(); settingsSub = "cats"; overlay = "settings" },
                openHiddenEditor = { dismissSearchInstant(); settingsSub = "hidden"; overlay = "settings" },
                openWidgetPicker = { startPickWidget() },
                showAppMenu = { app, src -> menuApp = app; menuSource = src },
                showCategoryPicker = { app -> categoryTarget = app },
                goPage = { page ->
                    overlay = null
                    val target = toPagerPage(page)
                    uiScope.launch { runCatching { pagerState.animateScrollToPage(target) } }
                },
                goLibrary = {
                    overlay = null
                    val target = toPagerPage(libraryPage)
                    uiScope.launch { runCatching { pagerState.animateScrollToPage(target) } }
                },
                toast = { msg -> ctx.toast(msg) }
            )
        }

        LaunchedEffect(DuoState.hideStatusBar) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (DuoState.hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
            else controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        LaunchedEffect(homeSignal, totalPages) {
            if (homeSignal > 0) {
                overlay = null
                settingsSub = null
                searchMounted = false
                searchFocused = false
                revealJob?.cancel()
                reveal = 0f
                runCatching { pagerState.animateScrollToPage(toPagerPage(0)) }
            }
            // 主屏页数变少（清空/删除应用）时，把越界的当前页拉回合法范围
            if (pagerState.currentPage > totalPages - 1) {
                runCatching { pagerState.scrollToPage(totalPages - 1) }
            }
        }

        // 搜索面板关掉后兜底收一次软键盘：只摘掉面板时，接实体键盘的 IME
        // 那条「输入状态」快捷栏偶尔会留在桌面底部
        LaunchedEffect(overlay, searchMounted) {
            if (overlay == null && !searchMounted) {
                runCatching {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                }
            }
        }

        BackHandler(enabled = overlay != null) { overlay = null; settingsSub = null }

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalWidgetBridge provides widgetBridge) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { rootWidth = it.width.toFloat() }
                ) {
                    // 资源库页虚化背景：放在 pager 之外的根层级渲染（pager 内大图在某些情况下不绘制，
                    // 会导致清晰壁纸透出）。仅停在资源库页时显示，滑动时按偏移淡入淡出，不影响主屏清晰壁纸。
                    val off = pagerState.currentPageOffsetFraction
                    val libAlpha = when {
                        pagerState.currentPage == libraryPage -> (1f - off).coerceIn(0f, 1f)
                        pagerState.currentPage == libraryPage - 1 -> off.coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (libAlpha > 0.01f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = libAlpha }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 搜索页推进时桌面轻微右移 + 压暗，做出与翻页一致的层次
                                translationX = rootWidth * 0.16f * reveal
                                alpha = 1f - 0.10f * reveal
                            },
                        userScrollEnabled = overlay == null && !DuoState.dragging && !searchMounted
                    ) { page ->
                        val actual = if (DuoState.reversePages) totalPages - 1 - page else page
                        when {
                            actual == totalPages - 1 -> LibraryPage(nav, dark, wallpaper)
                            else -> HomePage(
                                nav = nav,
                                dark = dark,
                                wallpaper = wallpaper,
                                pageDots = DuoState.showPageDots,
                                homePageIndex = actual,
                                homePageCount = homePageCount,
                                libraryPage = libraryPage,
                                allowSwipeRight = page == 0
                            )
                        }
                    }

                    // 搜索页背景：与 App 资源库同一套根层级虚化壁纸（放在 pager 与平移层之外，
                    // 避免「pager 内大图不绘制」导致清晰壁纸透出、再被色调层压成一片暗蒙版）。
                    // 随 reveal 淡入淡出，观感与资源库完全一致。
                    if (searchMounted) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }

                    // 搜索页：右滑时从左侧跟手滑入，方向与手指一致；
                    // 位置完全由 reveal 驱动，松手后 spring 补齐，和 HorizontalPager 同一套观感。
                    if (searchMounted) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = -rootWidth * (1f - reveal)
                                }
                        ) {
                            SearchPanel(
                                nav = nav,
                                dark = dark,
                                wallpaper = wallpaper,
                                focusEnabled = searchFocused,
                                onClose = { dismissSearchAnimated() }
                            )
                        }
                    }

                    when (overlay) {
                        "settings" -> SettingsScreen(nav, dark, wallpaper, initialSub = settingsSub) {
                            overlay = null; settingsSub = null
                        }
                    }

                    menuApp?.let { app ->
                        AppContextMenu(
                            app = app,
                            dark = dark,
                            onDismiss = { menuApp = null },
                            onUninstall = { AppRepository.uninstall(ctx, app.id) },
                            onRemoveHome = if (menuSource == "home")
                                ({ DuoState.removeHome(app.id) }) else null,
                            onHide = { DuoState.hideApp(app.id) },
                            onInfo = { AppRepository.appDetails(ctx, app.id) },
                            onCategory = { categoryTarget = app },
                            onAddSidebar = {
                                DuoState.sidebar.add(SidebarItem("app", app.id))
                                DuoState.save()
                            },
                            onAddToFolder = { folderTarget = app },
                            onAddHome = if (menuSource != "home")
                                ({ DuoState.addHome(app.id) }) else null,
                            onChangeIcon = { iconTarget = app }
                        )
                    }

                    iconTarget?.let { app ->
                        IconPickerDialog(app = app, dark = dark, onDismiss = { iconTarget = null })
                    }

                    categoryTarget?.let { app ->
                        DuoDialog(Lang.s(R.string.cat_of_app, app.label), dark, onDismiss = { categoryTarget = null }) {
                            MenuRow(Icons.Filled.AutoAwesome, Lang.s(R.string.follow_smart_category), dark = dark) {
                                DuoState.setCategory(app.id, null); categoryTarget = null
                            }
                            CATEGORY_LIST.forEach { (id, nameRes) ->
                                MenuRow(Icons.Filled.Category, Lang.s(nameRes), dark = dark) {
                                    DuoState.setCategory(app.id, id); categoryTarget = null
                                }
                            }
                        }
                    }

                    folderTarget?.let { app ->
                        FolderPickerDialog(app, dark, onDismiss = { folderTarget = null }) { folderId, newName ->
                            if (folderId != null) DuoState.addAppToFolder(folderId, app.id)
                            else DuoState.createFolder(newName ?: Lang.s(R.string.folder), listOf(app.id))
                            folderTarget = null
                        }
                    }

                    StatsConsentDialog(
                        dark = dark,
                        visible = statsConsentVisible,
                        onDecideLater = { statsConsentVisible = false },
                        onKeep = {
                            DuoState.statsConsentShown = true
                            DuoState.save()
                            InstallStats.reportIfAllowed(ctx, force = true)
                            statsConsentVisible = false
                        },
                        onDisable = {
                            DuoState.statsEnabled = false
                            DuoState.statsConsentShown = true
                            DuoState.save()
                            InstallStats.clear(ctx)
                            statsConsentVisible = false
                        }
                    )
                }
            }
        }
    }
}
