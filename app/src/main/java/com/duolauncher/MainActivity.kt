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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.duolauncher.data.*
import com.duolauncher.ui.*
import kotlinx.coroutines.delay
import com.duolauncher.util.InstallStats
import com.duolauncher.util.Lang
import com.duolauncher.util.NetworkLabelResolver
import com.duolauncher.util.WallpaperRotator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Home 键回到主屏的信号 */
    private var homeSignal by mutableStateOf(0)
    private var packageReceiver: BroadcastReceiver? = null

    /** 蜂窝信号强度权限：只申请一次，拒绝就永久回退（不再反复弹） */
    private val phonePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响功能 */ }

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
            override fun onReceive(context: Context?, intent: Intent?) {
                // 只有「真正新装」才考虑自动加桌面：应用更新带 EXTRA_REPLACING，
                // 启动器自身升级更不算；卸载 / 启用状态变化只刷新列表。
                val pkg = intent?.data?.schemeSpecificPart
                val newlyInstalled = intent?.action == Intent.ACTION_PACKAGE_ADDED &&
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false).not() &&
                        pkg != null && pkg != this@MainActivity.packageName
                reloadApps(autoAddPkg = if (newlyInstalled) pkg else null)
            }
        }
        runCatching { packageReceiver?.let { registerReceiver(it, filter) } }

        setContent { DuoRoot() }

        // 状态徽标的蜂窝信号格 / 双卡双排需要 READ_PHONE_STATE：只在第一次启动问一次，
        // 拒绝后写标记不再打扰（回退成单卡满格）；用户可在「设置 → iOS 风格状态栏 → 电话权限」重新开启
        maybeAskPhonePermission()
    }

    /** 首次启动申请一次「电话状态」权限，用于显示真实蜂窝信号格与双卡双排；拒绝后可在设置页重新开启 */
    private fun maybeAskPhonePermission() {
        if (Build.VERSION.SDK_INT < 28) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        val sp = getSharedPreferences("duo_flags", MODE_PRIVATE)
        if (sp.getBoolean("phone_perm_asked", false)) return
        sp.edit().putBoolean("phone_perm_asked", true).apply()
        runCatching { phonePermLauncher.launch(android.Manifest.permission.READ_PHONE_STATE) }
    }

    override fun onResume() {
        super.onResume()
        reloadApps()
        // 5GA / 5GUW 需要 telephony 显示信息回调；权限可能在设置页补授，故每次回桌面都试注册
        NetworkLabelResolver.attach(this)
        // 壁纸自动切换：只在回到桌面时按间隔判断，解码与设壁纸放 IO 线程。
        // 裁切算法升级后先自动重绘当前这张，让修复立即可见（不推进轮播序号）。
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            WallpaperRotator.reapplyIfNeeded(applicationContext)
            WallpaperRotator.rotateIfDue(applicationContext)
        }
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

    /**
     * [autoAddPkg] 只在收到「新装应用」广播时传入该包名。
     *
     * 用广播带来的包名定位本次安装的应用，而不是比对新旧列表差集：onCreate / onResume /
     * 包广播会并发触发本方法，差集在协程交错时可能恰好看不到新应用。按包名过滤则与执行
     * 顺序无关，且不会把已经装好的其它应用补进桌面，也不会撤销用户手动移除的图标。
     */
    private fun reloadApps(autoAddPkg: String? = null) {
        lifecycleScope.launch {
            val list = AppRepository.loadApps(this@MainActivity)
            DuoState.apps.clear()
            DuoState.apps.addAll(list)
            DuoState.ensureDefaults()
            if (autoAddPkg != null && DuoState.autoAddNewApps) {
                val fresh = list.filter { app ->
                    app.packageName == autoAddPkg &&
                            !DuoState.homeApps.contains(app.id) &&
                            !DuoState.hidden.contains(app.id)
                }.map { it.id }
                if (fresh.isNotEmpty()) DuoState.addHomeBatch(fresh)
            }
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

        // 主屏分页：单页容量必须和 HomePage 完全一致（列数 × 行数）。
        // 之前写死 20，用户把列数调到 5 以上时，这里算出的页数比实际能放的多，
        // 第一屏明明还有空位却多出一个空白页。
        //
        // 搜索页**本身就是 pager 的一页**（贴在主屏第一页外侧），不再是盖在桌面上的
        // reveal 覆盖层：跟手拖动、fling 惯性、spring 吸附全部交给 HorizontalPager，
        // 与滑到 App 资源库完全是同一套物理和过渡，桌面整页平移也不会再露出图标/侧栏。
        val perPage = (DuoState.homeColumns * DuoState.homeRows).coerceAtLeast(1)
        val cellCount = DuoState.homeTokens().size
        val homePageCount = ((cellCount + perPage - 1) / perPage).coerceAtLeast(1)
        val totalPages = homePageCount + 2
        /** 「真实页序号」沿用旧语义：主屏 0..homePageCount-1，资源库 homePageCount */
        val libraryPage = homePageCount
        /** 搜索页固定在 pager 一端，始终挨着主屏第一页（倒序时换到另一端） */
        val searchPagerPage = if (DuoState.reversePages) totalPages - 1 else 0
        fun toPagerPage(actual: Int): Int {
            val a = actual.coerceIn(0, libraryPage)
            return if (DuoState.reversePages) libraryPage - a else a + 1
        }
        val libraryPagerPage = toPagerPage(libraryPage)
        val pagerState = rememberPagerState(initialPage = toPagerPage(0)) { totalPages }

        var overlay by remember {
            mutableStateOf(if (Lang.consumeReopenSettings(ctx)) "settings" else null)
        }
        var settingsSub by remember { mutableStateOf<String?>(null) }
        // 首次启动的一次性统计告知：未确认前 InstallStats 不会发任何请求
        var statsConsentVisible by remember {
            mutableStateOf(!DuoState.statsConsentShown && DuoState.statsEnabled)
        }

        /**
         * 搜索页只有「真正停稳」才算激活：滑动一开始 focusEnabled 立刻变 false，
         * SearchPanel 随即把真实 EditText 从组合里摘掉 → 输入法会话结束，
         * 桌面底部那条「输入状态 / 双拼 / 麦克风」工具栏不会残留（171 版验证过的路径）。
         * 直接由 pager 状态实时计算，不用异步收集，避免快速滑动时晚一帧。
         */
        val searchFocused = pagerState.currentPage == searchPagerPage &&
                !pagerState.isScrollInProgress &&
                kotlin.math.abs(pagerState.currentPageOffsetFraction) < 0.02f
        // 视口中心离搜索页 / 资源库页各有多远（页为单位）：1 = 整页可见，0 = 完全离开。
        // 根层级的虚化壁纸据此随翻页自然淡入淡出，两张页共用同一套做法；
        // 不铺不透明纯色底（173 版曾把系统壁纸盖成纯黑）。
        val pagerPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val searchVisibility = (1f - kotlin.math.abs(pagerPos - searchPagerPage)).coerceIn(0f, 1f)
        val libraryVisibility = (1f - kotlin.math.abs(pagerPos - libraryPagerPage)).coerceIn(0f, 1f)

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

        /** 打开设置等浮层前，若正停在搜索页就先回主屏，避免关掉浮层后键盘又弹出来 */
        fun leaveSearchIfNeeded() {
            if (pagerState.currentPage == searchPagerPage) {
                uiScope.launch { runCatching { pagerState.scrollToPage(toPagerPage(0)) } }
            }
        }

        val nav = remember(totalPages, libraryPage, searchPagerPage) {
            Nav(
                // 点右下角搜索键 / 侧栏动作：和 goLibrary 一样走 pager 的原生翻页动画
                openSearch = {
                    uiScope.launch { runCatching { pagerState.animateScrollToPage(searchPagerPage) } }
                },
                openSettings = { leaveSearchIfNeeded(); settingsSub = null; overlay = "settings" },
                openSidebarEditor = { leaveSearchIfNeeded(); settingsSub = "sidebar"; overlay = "settings" },
                openCategoryEditor = { leaveSearchIfNeeded(); settingsSub = "cats"; overlay = "settings" },
                openHiddenEditor = { leaveSearchIfNeeded(); settingsSub = "hidden"; overlay = "settings" },
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
                    uiScope.launch { runCatching { pagerState.animateScrollToPage(libraryPagerPage) } }
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
                runCatching { pagerState.animateScrollToPage(toPagerPage(0)) }
            }
            // 主屏页数变少（清空/删除应用）时，把越界的当前页拉回合法范围
            if (pagerState.currentPage > totalPages - 1) {
                runCatching { pagerState.scrollToPage(totalPages - 1) }
            }
        }

        // 离开搜索页后的最后一道保险：正常情况下什么都不做。
        //
        // 收键盘只有一条路径：SearchPanel 在「不再停稳」那一刻松开焦点 → 输入法自己收起 →
        // 输入框从组合摘掉 → 输入连接关闭 → 会话结束。这里再补一刀就会让收起动画播第二遍
        // （看着就是「跳两下」），所以改成**先看输入法还在不在**：只有极少数输入法赖着
        // 不走时，才补一次 hide。
        var searchEverOpened by remember { mutableStateOf(false) }
        LaunchedEffect(searchFocused) {
            if (searchFocused) {
                searchEverOpened = true
                return@LaunchedEffect
            }
            if (!searchEverOpened) return@LaunchedEffect
            // 等输入框摘掉、输入法自然收起的那一小段动画走完再判断
            delay(280)
            val root = window.decorView
            val insets = ViewCompat.getRootWindowInsets(root) ?: return@LaunchedEffect
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                runCatching {
                    WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                }
            }
        }

        BackHandler(enabled = overlay != null) { overlay = null; settingsSub = null }

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalWidgetBridge provides widgetBridge) {
                Box(Modifier.fillMaxSize()) {
                    // 搜索页 / 资源库页共用根层级虚化壁纸：放在 pager 之外渲染
                    // （pager 内大图在某些情况下不绘制，会导致清晰壁纸透出）。
                    // 按「视口离该页的距离」淡入淡出，翻页时与页面位移完全同步。
                    // 不铺不透明纯色底：窗口主题是 Theme.Wallpaper，壁纸由窗口本身透出，
                    // 173 版加过深色兜底，结果把系统壁纸整个盖成纯黑（齐老师实测）。
                    if (libraryVisibility > 0.01f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = libraryVisibility }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }
                    if (searchVisibility > 0.01f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = searchVisibility }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = overlay == null && !DuoState.dragging
                    ) { page ->
                        when {
                            page == searchPagerPage -> SearchPanel(
                                nav = nav,
                                dark = dark,
                                wallpaper = wallpaper,
                                // 停稳才挂输入框 / 弹键盘；pager 一开始滑动就立刻摘掉
                                focusEnabled = searchFocused,
                                closing = !searchFocused,
                                onClose = {
                                    uiScope.launch {
                                        runCatching { pagerState.animateScrollToPage(toPagerPage(0)) }
                                    }
                                }
                            )
                            page == libraryPagerPage -> LibraryPage(
                                nav = nav, dark = dark, wallpaper = wallpaper,
                                // 只有真正停在这一页（且没有浮层盖着）才算激活；
                                // 滑走时页面不会销毁，必须靠这个信号交出输入焦点
                                isActive = pagerState.currentPage == page && overlay == null
                            )
                            else -> {
                                // pager 下标 → 主屏真实序号（倒序时反过来）
                                val actual = if (DuoState.reversePages) libraryPage - page else page - 1
                                HomePage(
                                    nav = nav,
                                    dark = dark,
                                    wallpaper = wallpaper,
                                    pageDots = DuoState.showPageDots,
                                    homePageIndex = actual,
                                    homePageCount = homePageCount,
                                    libraryPage = libraryPage,
                                    // 只有紧挨搜索页的那一主屏页，才需要处理「关闭手势」的拦截
                                    allowSwipeRight = actual == 0
                                )
                            }
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
