package com.duolauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.duolauncher.util.UpdateChecker
import com.duolauncher.util.WallpaperRotator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** pager 页语义标记：与主屏真实页序号（0..N-1）不冲突的负数 */
private const val PAGER_SEARCH = -1
private const val PAGER_LIBRARY = -2

class MainActivity : ComponentActivity() {

    /** Home 键回到主屏的信号 */
    private var homeSignal by mutableStateOf(0)
    private var packageReceiver: BroadcastReceiver? = null

    /** 应用列表全量扫描的节流状态：避免回桌面时反复枚举应用、解码图标 */
    private var lastAppsReloadAt = 0L
    private var appsReloadJob: kotlinx.coroutines.Job? = null
    private companion object { const val APPS_RELOAD_MIN_GAP_MS = 5_000L }

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
        // 顶部占用高度交给 DuoState 统一记录：取「状态栏高度」与「挖孔包围盒下沿」的较大值。
        // 有的机型（Titan 2 Elite）挖孔开在左上角，statusBars.top 很小甚至为 0，
        // 只按状态栏避让会让第一行图标被摄像头压住；取包围盒 bottom 才能整行让到孔下面。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val barsTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val holeBottom = insets.displayCutout?.boundingRects?.maxOfOrNull { it.bottom } ?: 0
            val cutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            DuoState.setSystemTopInset(
                maxOf(barsTop, holeBottom, cutTop),
                resources.displayMetrics.density
            )
            insets
        }
        ViewCompat.requestApplyInsets(window.decorView)
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
                reloadApps(autoAddPkg = if (newlyInstalled) pkg else null, force = true)
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
        // 自动检查更新：回到桌面就查一次（间隔可设 12/24/48/72 小时），不必先进设置页。
        // 查不到静默；只有真的更高版本才提示一次，同一版本不重复打扰。
        lifecycleScope.launch(Dispatchers.IO) {
            if (!DuoState.autoCheckUpdate) return@launch
            val interval = UpdateChecker.intervalMillis(DuoState.updateIntervalHours)
            if (System.currentTimeMillis() - DuoState.lastUpdateCheck < interval) return@launch
            val local = UpdateChecker.currentVersion(this@MainActivity)
            val r = UpdateChecker.fetch(UpdateChecker.urlFor(DuoState.updateSource), local)
            // 失败也记一次时间：断网时否则每次回桌面都要重试一遍超时
            DuoState.lastUpdateCheck = System.currentTimeMillis()
            r.onSuccess { info ->
                DuoState.lastSeenVersion = info.version
                if (UpdateChecker.isNewer(info.version, local) && DuoState.notifiedVersion != info.version) {
                    DuoState.notifiedVersion = info.version
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            Lang.s(R.string.update_found_toast, info.version),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            DuoState.save()
        }
        // 无障碍服务只在桌面拦字母键，离开桌面时字母照常输入
        DuoState.homeForeground = true
        // 从系统快捷键页回到桌面时，把配置用的「分身 N」临时入口收掉（已被按键引用的保留）
        runCatching { com.duolauncher.util.ShortcutManager.collapseIfPending(this) }
        // 自检「息屏及快捷键服务」：应用被覆盖安装、或被系统强行停止之后，系统会把无障碍
        // 授权收回（设置项里直接消失），此时开关仍是开的、双击却毫无反应。这属于系统侧的
        // 安全行为，App 没有任何权限替用户重新开启，只能主动说清楚，别让人以为功能坏了。
        runCatching {
            if (DuoState.doubleTapSleep) {
                if (com.duolauncher.util.ScreenOff.isReady()) {
                    // 真的连上了：记下「开过」，掉线后才知道该提示重新开启而不是「首次开启」
                    if (!DuoState.sleepGrantedOnce || DuoState.sleepRevokedNotified) {
                        DuoState.sleepGrantedOnce = true
                        DuoState.sleepRevokedNotified = false
                        DuoState.save()
                    }
                } else if (com.duolauncher.util.ScreenOff.isEnabled(this)) {
                    // 已授权、正在重绑：正常过程，不打扰
                    if (!DuoState.sleepGrantedOnce) {
                        DuoState.sleepGrantedOnce = true
                        DuoState.save()
                    }
                } else if (DuoState.sleepGrantedOnce && !DuoState.sleepRevokedNotified) {
                    DuoState.sleepRevokedNotified = true
                    DuoState.save()
                    android.widget.Toast.makeText(
                        this,
                        com.duolauncher.util.Lang.s(R.string.toast_sleep_revoked),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPause() {
        DuoState.homeForeground = false
        // 配置写盘做了 250ms 合并（避免拖滑块时连续写盘），离开桌面时强制落一次，
        // 保证用户切走 / 熄屏后改动不会停在合并窗口里。
        com.duolauncher.data.DuoState.flush()
        super.onPause()
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
    private fun reloadApps(autoAddPkg: String? = null, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        // 每次回桌面都全量枚举应用并解码图标（LauncherApps + 每个 app 的 Drawable）代价很高，
        // onCreate / onResume / onNewIntent 往往接连触发。短时间内合并为一次；
        // 包变化广播（force）与首次加载一定执行，保证列表不会漏更新。
        // 已有一次扫描在跑时也不再叠加，避免两个协程交错写 DuoState.apps。
        if (!force && autoAddPkg == null &&
            (appsReloadJob?.isActive == true ||
                (DuoState.appLoadDone && lastAppsReloadAt > 0L &&
                    now - lastAppsReloadAt < APPS_RELOAD_MIN_GAP_MS))) return
        lastAppsReloadAt = now
        appsReloadJob?.cancel()
        appsReloadJob = lifecycleScope.launch {
            // 只有包变化（force）才真正重扫并解码图标；普通回桌面直接复用应用列表快照，
            // 避免每次 onResume 都枚举全部 profile 并解码上百个图标 Drawable。
            val list = AppRepository.loadApps(this@MainActivity, refresh = force)
            // 内容没变就别 clear+addAll：那会让所有观察 apps 的组合整页重组，回桌面明显顿一下。
            // 但包变化（force）必须整表替换 —— 应用更新后图标 Drawable 会变，稳定键却完全相同。
            val unchanged = !force && DuoState.apps.size == list.size &&
                DuoState.apps.indices.all { i ->
                    val a = DuoState.apps[i]
                    val b = list[i]
                    a.id == b.id && a.label == b.label && a.activityClass == b.activityClass
                }
            if (!unchanged) {
                // 整表替换并让 appOf() 的查找索引失效（换新前是线性扫描，太贵）
                DuoState.replaceApps(list)
            }
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

        // 主屏分页：页数必须和 HomePage 用**同一个**分页函数算（含系统小组件占整行的情况），
        // 否则会出现「第一屏没放满却多出空白页」。
        //
        // 搜索页**本身就是 pager 的一页**（贴在主屏第一页外侧），不再是盖在桌面上的
        // reveal 覆盖层：跟手拖动、fling 惯性、spring 吸附全部交给 HorizontalPager，
        // 与滑到 App 资源库完全是同一套物理和过渡，桌面整页平移也不会再露出图标/侧栏。
        // 每页行数取「设置值」与「屏幕实际排得下的行数」的较大者（见 effectiveHomeRows）：
        // 平板横屏顶部留白收窄后能多排一行，这里必须和 HomePage 用完全相同的算法
        val cfg = LocalConfiguration.current
        val landscape = cfg.screenWidthDp > cfg.screenHeightDp
        val gridRows = DuoState.effectiveHomeRows(cfg.screenHeightDp, landscape)
        // 页数与 HomePage 同源：都用 layoutHome（含手动摆放坐标 + 自动流式排布）。
        // 用版本号做键缓存，避免无关重组时反复重算整页占用矩阵。
        val homePageCount = remember(DuoState.homeLayoutVersion, DuoState.homeColumns, gridRows) {
            DuoState.layoutHome(DuoState.homeColumns, gridRows).size
        }
        // 「设置 → 主屏幕 → 显示 App 资源库」关掉时，pager 里不再有资源库这一页
        // 「设置 → 搜索 → 显示搜索页面」关掉时，pager 里不再有搜索页
        // 用一张显式的「页语义表」统一维护顺序，避免各开关组合下出现下标错位：
        //   PAGER_SEARCH = 搜索页，PAGER_LIBRARY = 资源库页，0..homePageCount-1 = 主屏页
        val showLib = DuoState.showLibrary
        val showSearch = DuoState.showSearchPage
        /** 「真实页序号」沿用旧语义：主屏 0..homePageCount-1，资源库 homePageCount */
        val libraryPage = homePageCount
        val pagerPages = remember(homePageCount, showLib, showSearch, DuoState.reversePages) {
            buildList {
                if (DuoState.reversePages) {
                    if (showLib) add(PAGER_LIBRARY)
                    for (a in homePageCount - 1 downTo 0) add(a)
                    if (showSearch) add(PAGER_SEARCH)
                } else {
                    if (showSearch) add(PAGER_SEARCH)
                    for (a in 0 until homePageCount) add(a)
                    if (showLib) add(PAGER_LIBRARY)
                }
            }
        }
        val totalPages = pagerPages.size
        /** 搜索页 / 资源库页的 pager 下标；关闭显示时为 -1（永不匹配） */
        val searchPagerPage = pagerPages.indexOf(PAGER_SEARCH)
        val libraryPagerPage = pagerPages.indexOf(PAGER_LIBRARY)
        fun toPagerPage(actual: Int): Int {
            val at = pagerPages.indexOf(actual.coerceIn(0, libraryPage))
            return if (at >= 0) at else 0
        }
        val pagerState = rememberPagerState(initialPage = toPagerPage(0)) { totalPages }

        /**
         * 刚加到主屏的小组件：数据上它排在第一页第一个，但用户添加时往往停在别的页
         * （比如刚从设置/资源库出来），不跟过去就会觉得「加完了屏幕上什么也没有 / 不在左上角」。
         */
        var jumpToWidgetKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(jumpToWidgetKey, totalPages) {
            val key = jumpToWidgetKey ?: return@LaunchedEffect
            delay(120)   // 等分页/布局吃到新的 homeTokens
            val pages = DuoState.layoutHome(
                DuoState.homeColumns,
                DuoState.effectiveHomeRows(cfg.screenHeightDp, landscape)
            )
            val at = pages.indexOfFirst { page -> page.any { it.token == "widget:$key" } }
            if (at >= 0) runCatching { pagerState.animateScrollToPage(toPagerPage(at)) }
            jumpToWidgetKey = null
        }

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
        // offsetFraction 每帧都在变，直接在组合里读会让整个 DuoRoot 每帧重组（连带所有
        // lambda / 子页参数重算）。用 derivedStateOf 后只有布尔结果翻转才通知重组：
        // 滑动过程中它一直是 false，停稳那一刻才变 true，正是我们要的语义。
        val searchFocused by remember(searchPagerPage) {
            derivedStateOf {
                searchPagerPage >= 0 &&
                        pagerState.currentPage == searchPagerPage &&
                        !pagerState.isScrollInProgress &&
                        kotlin.math.abs(pagerState.currentPageOffsetFraction) < 0.02f
            }
        }
        // 视口中心离搜索页 / 资源库页各有多远（页为单位）：1 = 整页可见，0 = 完全离开。
        // 根层级的虚化壁纸据此随翻页自然淡入淡出；不铺不透明纯色底
        // （173 版曾把系统壁纸盖成纯黑）。
        // 「是否该出现在组合里」也走 derivedStateOf：滑动时只在跨越可见阈值时重组一次，
        // 逐帧的 alpha 变化放到 graphicsLayer 的 draw 阶段读取，不触发任何重组。
        val showSearchBackdrop by remember(searchPagerPage) {
            derivedStateOf {
                // 搜索页被关掉时这一页根本不存在，不做任何计算
                if (searchPagerPage < 0) return@derivedStateOf false
                val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                (1f - kotlin.math.abs(pos - searchPagerPage)) > 0.01f
            }
        }
        val showLibraryBackdrop by remember(libraryPagerPage) {
            derivedStateOf {
                // 资源库被隐藏时这一页根本不存在，不做任何计算
                if (libraryPagerPage < 0) return@derivedStateOf false
                val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                (1f - kotlin.math.abs(pos - libraryPagerPage)) > 0.01f
            }
        }
        /** 逐帧的可见度；只在 graphicsLayer 的 draw lambda 里调用，不参与组合 */
        fun backdropAlpha(page: Int): Float {
            val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
            return (1f - kotlin.math.abs(pos - page)).coerceIn(0f, 1f)
        }

        var menuApp by remember { mutableStateOf<AppInfo?>(null) }
        var menuSource by remember { mutableStateOf("home") }
        var categoryTarget by remember { mutableStateOf<AppInfo?>(null) }
        var iconTarget by remember { mutableStateOf<AppInfo?>(null) }
        var folderTarget by remember { mutableStateOf<AppInfo?>(null) }
        /** 主屏「添加小组件」：打开自带选择器（带预览和格子尺寸），不再走系统列表 */
        var widgetPickerOpen by remember { mutableStateOf(false) }

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

        /** 绑定/配置流程走完：系统小组件一律落到主屏幕 */
        fun finishBind(id: Int) {
            val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
            if (info != null) {
                DuoState.addHomeWidget(id, info.minWidth, info.minHeight)
                jumpToWidgetKey = id.toString()
                toast(Lang.s(R.string.toast_widget_added_home))
            } else toast(Lang.s(R.string.toast_widget_bind_fail))
        }

        val configLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishBind(pendingWidgetId)
        }
        val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishBind(pendingWidgetId)
        }

        /**
         * 从自带选择器里直接添加：自己分配 id + 绑定，绕开系统的 ACTION_APPWIDGET_PICK。
         * （系统那个列表没有尺寸/预览，加完才知道占多大，所以主屏改用自己的选择器。）
         */
        fun addWidgetFromPicker(info: AppWidgetProviderInfo) {
            val id = runCatching { widgetBridge.host.allocateAppWidgetId() }.getOrNull()
            if (id == null) { toast(Lang.s(R.string.toast_widget_picker_fail)); return }
            pendingWidgetId = id
            val allowed = runCatching {
                widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
            }.getOrDefault(false)
            if (!allowed) {
                // 没有绑定权限时退回系统授权流程（授权后会走 bindLauncher 的 finishBind）
                runCatching {
                    bindLauncher.launch(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    )
                }.onFailure { toast(Lang.s(R.string.toast_widget_picker_fail)) }
                return
            }
            if (info.configure != null) {
                runCatching {
                    configLauncher.launch(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                            .setComponent(info.configure)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    )
                }.onFailure { finishBind(id) }
            } else finishBind(id)
        }

        // 注意：必须用 Compose 的 rememberCoroutineScope。
        // Activity.lifecycleScope 的 CoroutineContext 里没有 MonotonicFrameClock，
        // 调 animateScrollToPage 会抛 IllegalStateException（点「添加应用到主屏幕」就崩）。
        val uiScope = rememberCoroutineScope()

        /** 打开设置等浮层前，若正停在搜索页就先回主屏，避免关掉浮层后键盘又弹出来 */
        fun leaveSearchIfNeeded() {
            if (searchPagerPage >= 0 && pagerState.currentPage == searchPagerPage) {
                uiScope.launch { runCatching { pagerState.scrollToPage(toPagerPage(0)) } }
            }
        }

        /**
         * pager 下标由「当前页语义表」算出：主屏页个数可能因拖动临时变化，
         * 这里按当下页数重建一份顺序再查下标，避免关掉搜索页后整体错位。
         */
        fun pagerIndexOfHome(actual: Int, count: Int): Int {
            val lib = DuoState.showLibrary
            val search = DuoState.showSearchPage
            val list = buildList {
                if (DuoState.reversePages) {
                    if (lib) add(PAGER_LIBRARY)
                    for (a in count - 1 downTo 0) add(a)
                    if (search) add(PAGER_SEARCH)
                } else {
                    if (search) add(PAGER_SEARCH)
                    for (a in 0 until count) add(a)
                    if (lib) add(PAGER_LIBRARY)
                }
            }
            return list.indexOf(actual.coerceIn(0, count - 1)).coerceAtLeast(0)
        }

        val nav = remember(totalPages, libraryPage, searchPagerPage) {
            Nav(
                // 侧栏「搜索」动作：和 goLibrary 一样走 pager 的原生翻页动画。
                // 搜索页被关掉时给提示，不让点击看起来没反应。
                openSearch = {
                    if (DuoState.showSearchPage && searchPagerPage >= 0) {
                        uiScope.launch { runCatching { pagerState.animateScrollToPage(searchPagerPage) } }
                    } else {
                        ctx.toast(Lang.s(R.string.toast_search_hidden))
                    }
                },
                openSettings = { leaveSearchIfNeeded(); settingsSub = null; overlay = "settings" },
                openSidebarEditor = { leaveSearchIfNeeded(); settingsSub = "sidebar"; overlay = "settings" },
                openCategoryEditor = { leaveSearchIfNeeded(); settingsSub = "cats"; overlay = "settings" },
                openHiddenEditor = { leaveSearchIfNeeded(); settingsSub = "hidden"; overlay = "settings" },
                addHomeWidget = { widgetPickerOpen = true },
                showAppMenu = { app, src -> menuApp = app; menuSource = src },
                dismissAppMenu = { menuApp = null },
                showCategoryPicker = { app -> categoryTarget = app },
                goPage = { page ->
                    overlay = null
                    // 拖动落位可能刚刚新增了一屏，pager 的页数要等组合更新后才准；
                    // 这里延后一拍并按"当下"的主屏页数重新算下标，避免被旧页数夹回到资源库页。
                    uiScope.launch {
                        delay(80)
                        val count = DuoState.layoutHome(
                            DuoState.homeColumns,
                            DuoState.effectiveHomeRows(cfg.screenHeightDp, landscape)
                        ).size.coerceAtLeast(1)
                        val idx = pagerIndexOfHome(page, count)
                        runCatching { pagerState.animateScrollToPage(idx) }
                    }
                },
                goLibrary = {
                    if (DuoState.showLibrary) {
                        overlay = null
                        uiScope.launch { runCatching { pagerState.animateScrollToPage(libraryPagerPage) } }
                    } else {
                        // 资源库已在设置里关掉：直接告诉用户，别让点击看起来没反应
                        ctx.toast(Lang.s(R.string.toast_library_hidden))
                    }
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

        // 老版本加的小组件没有记录行数 / 列数，启动时按 provider 声明的尺寸补一次，
        // 否则 4×4 的小组件仍会按 2 行占位、4×2 的仍会吃满整行（画不下、也挪不动）
        LaunchedEffect(DuoState.homeWidgets.size) {
            DuoState.homeWidgets.forEach { key ->
                val id = key.toIntOrNull() ?: return@forEach
                val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull() ?: return@forEach
                DuoState.ensureWidgetSize(id, info.minWidth, info.minHeight)
            }
        }

        // 关掉「显示搜索页面」时搜索页会从 pager 里消失：留在原下标会落到资源库页，
        // 所以统一回到主屏第一页，观感上就是「搜索页没了，人还在桌面上」。
        LaunchedEffect(DuoState.showSearchPage) {
            if (!DuoState.showSearchPage) {
                runCatching { pagerState.scrollToPage(toPagerPage(0)) }
            }
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
                    if (showLibraryBackdrop) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha(libraryPagerPage) }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }
                    if (showSearchBackdrop) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha(searchPagerPage) }) {
                            GlassBackdrop(wallpaper, DuoState.glassLevel, dark)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // 不设 beyondBoundsPageCount：实测预组合相邻页会让冷启动从
                        // 320ms 涨到 450ms（每页都要跑 layoutHome + 手势），
                        // 代价比翻页少掉的那 0.3% 卡顿大得多。
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

                    if (widgetPickerOpen) {
                        WidgetPickerDialog(
                            dark = dark,
                            onDismiss = { widgetPickerOpen = false },
                            onPick = { info ->
                                widgetPickerOpen = false
                                addWidgetFromPicker(info)
                            }
                        )
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
                            DuoState.categoryList().forEach { (id, name) ->
                                MenuRow(Icons.Filled.Category, name, dark = dark) {
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
