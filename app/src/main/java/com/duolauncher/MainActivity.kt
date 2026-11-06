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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.duolauncher.data.*
import com.duolauncher.ui.*
import com.duolauncher.util.Lang
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
        // 真实页序号 ↔ pager 下标（开启"倒序翻页"时两者相反）
        fun toPagerPage(actual: Int): Int {
            val a = actual.coerceIn(0, totalPages - 1)
            return if (DuoState.reversePages) totalPages - 1 - a else a
        }

        val nav = remember(totalPages, libraryPage) {
            Nav(
                openSearch = { overlay = "search" },
                openSettings = { settingsSub = null; overlay = "settings" },
                openSidebarEditor = { settingsSub = "sidebar"; overlay = "settings" },
                openCategoryEditor = { settingsSub = "cats"; overlay = "settings" },
                openHiddenEditor = { settingsSub = "hidden"; overlay = "settings" },
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
                runCatching { pagerState.animateScrollToPage(toPagerPage(0)) }
            }
            // 主屏页数变少（清空/删除应用）时，把越界的当前页拉回合法范围
            if (pagerState.currentPage > totalPages - 1) {
                runCatching { pagerState.scrollToPage(totalPages - 1) }
            }
        }

        // 搜索面板关掉后兜底收一次软键盘：只摘掉面板时，接实体键盘的 IME
        // 那条「输入状态」快捷栏偶尔会留在桌面底部
        LaunchedEffect(overlay) {
            if (overlay == null) {
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
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = overlay == null && !DuoState.dragging
                    )                     { page ->
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

                    when (overlay) {
                        "search" -> SearchPanel(nav, dark, wallpaper) { overlay = null }
                        "settings" -> SettingsScreen(nav, dark, wallpaper, initialSub = settingsSub) {
                            overlay = null; settingsSub = null
                        }
                    }

                    menuApp?.let { app ->
                        AppContextMenu(
                            app = app,
                            dark = dark,
                            onDismiss = { menuApp = null },
                            onUninstall = { AppRepository.uninstall(ctx, app.packageName) },
                            onRemoveHome = if (menuSource == "home")
                                ({ DuoState.removeHome(app.packageName) }) else null,
                            onHide = { DuoState.hideApp(app.packageName) },
                            onInfo = { AppRepository.appDetails(ctx, app.packageName) },
                            onCategory = { categoryTarget = app },
                            onAddSidebar = {
                                DuoState.sidebar.add(SidebarItem("app", app.packageName))
                                DuoState.save()
                            },
                            onAddToFolder = { folderTarget = app },
                            onAddHome = if (menuSource != "home")
                                ({ DuoState.addHome(app.packageName) }) else null,
                            onChangeIcon = { iconTarget = app }
                        )
                    }

                    iconTarget?.let { app ->
                        IconPickerDialog(app = app, dark = dark, onDismiss = { iconTarget = null })
                    }

                    categoryTarget?.let { app ->
                        DuoDialog(Lang.s(R.string.cat_of_app, app.label), dark, onDismiss = { categoryTarget = null }) {
                            MenuRow(Icons.Filled.AutoAwesome, Lang.s(R.string.follow_smart_category), dark = dark) {
                                DuoState.setCategory(app.packageName, null); categoryTarget = null
                            }
                            CATEGORY_LIST.forEach { (id, nameRes) ->
                                MenuRow(Icons.Filled.Category, Lang.s(nameRes), dark = dark) {
                                    DuoState.setCategory(app.packageName, id); categoryTarget = null
                                }
                            }
                        }
                    }

                    folderTarget?.let { app ->
                        FolderPickerDialog(app, dark, onDismiss = { folderTarget = null }) { folderId, newName ->
                            if (folderId != null) DuoState.addAppToFolder(folderId, app.packageName)
                            else DuoState.createFolder(newName ?: Lang.s(R.string.folder), listOf(app.packageName))
                            folderTarget = null
                        }
                    }
                }
            }
        }
    }
}
