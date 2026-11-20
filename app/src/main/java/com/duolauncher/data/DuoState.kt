package com.duolauncher.data

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duolauncher.R
import com.duolauncher.util.InstallStats
import com.duolauncher.util.Lang
import com.duolauncher.util.UpdateChecker
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 正在播放的媒体信息（由 [com.duolauncher.service.DuoNotificationListener] 维护）。
 * 只要媒体会话还在（哪怕处于暂停）就非空，胶囊据此常驻；播放器退出后台、会话消失才清空。
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val packageName: String,
    val sessionActivity: PendingIntent?,
    /** 当前是否在播放；暂停时为 false，胶囊仍在（长按可恢复） */
    val playing: Boolean = false
)

/**
 * 全局状态 + 轻量持久化。所有改动即时写盘，Compose 直接观察这些 State。
 */
object DuoState {

    private const val PREF = "duo_prefs_v1"
    /** 侧栏默认槽位数（含 1 个搜索动作） */
    const val SIDEBAR_SLOTS = 7
    /** iOS 系统红，角标默认色 */
    const val IOS_RED = 0xFFFF3B30.toInt()
    private lateinit var sp: SharedPreferences
    private var ready = false
    /**
     * 是否读到过存档。全新安装为 false，只有这时才铺默认侧栏；
     * 有存档（含备份导入）时，用户主动清空的侧栏 / 主屏都要保持空，不被 onResume 重新填满。
     */
    private var hasArchive = false

    var selfPackage = "com.duolauncher"
        private set

    val apps = mutableStateListOf<AppInfo>()
    val homeApps = mutableStateListOf<String>()
    /**
     * 主屏统一顺序（token 列表）：`app:包名` / `folder:文件夹id`。
     * 以前主屏是「应用全部在前 + 文件夹全部在后」，拖拽排序必须能混排，
     * 所以单独存一份 token 顺序；空列表时自动退回旧顺序（应用在前、文件夹在后）。
     * 该列表是"自愈"的：app 被隐藏/移除、folder 被删掉时对应 token 自动失效，不用手工清理。
     */
    val homeOrder = mutableStateListOf<String>()
    /**
     * 主屏上的系统小组件：元素是 AppWidget 的 id 字符串。
     * 小组件占主屏图标网格里的一块矩形（[widgetRowMap] / [widgetColMap]），
     * 坐标走 [homeSlots]，排序时以 `widget:<id>` 的形式进 [homeOrder]。
     */
    val homeWidgets = mutableStateListOf<String>()
    /**
     * 主屏小组件占几行（AppWidget id → 行数），添加时按 provider 声明的 minHeight 换算。
     *
     * 必须和 [layoutHome] 预留的行数是同一个值：4×4 的小组件如果只按 2 行预留，
     * 它自己画不下会顶出去，后面的图标也会被挤到看不见的地方。
     */
    val widgetRowMap = mutableStateMapOf<String, Int>()
    /** 小组件占几列（provider 的 minWidth 换算）。有了它小组件才能只占半行、左右挪动 */
    val widgetColMap = mutableStateMapOf<String, Int>()
    /**
     * 主屏自由摆放：token → `"页,行,列"`。
     *
     * 只有被手动拖动过的条目才在这里有坐标，其余条目按 [layoutHome] 的自动流式规则
     * （从第 0 页左上角起逐格填充）排布。这样：
     *  • 老存档没有坐标，布局与以前完全一致；
     *  • 用户把图标 / 小组件拖到任意一格（包括空白处）后，位置就被记住；
     *  • 坐标失效（列数改小、行数改小、条目被删）时该条目退回自动排布，不会丢。
     */
    val homeSlots = mutableStateMapOf<String, String>()
    val sidebar = mutableStateListOf<SidebarItem>()
    val hidden = mutableStateListOf<String>()
    val folders = mutableStateListOf<FolderItem>()
    val usage = mutableStateMapOf<String, UsageRec>()
    val categoryOverride = mutableStateMapOf<String, String>()
    /**
     * 用户自建分类（按添加顺序排在资源库末尾）。
     * 内置分类的「新增/删除」在 [CATEGORY_LIST] 之外另存两份：
     * [categoryRenames] 存改名，[hiddenCategories] 存被删掉的内置分类 id。
     */
    val customCategories = mutableStateListOf<CategoryDef>()
    /** 内置分类改名：id -> 新名字（没改过的走 strings.xml，切语言仍会跟着变） */
    val categoryRenames = mutableStateMapOf<String, String>()
    /** 被删掉的内置分类 id（软删除：应用不受影响，只是资源库里不再出这张卡） */
    val hiddenCategories = mutableStateListOf<String>()
    /**
     * 单个应用手动指定的图标：应用包名 -> `图标包包名|drawable 名`。
     * 图标包自动匹配（appfilter）常常收录不全，匹配不到的应用可以在这里手动挑一个。
     */
    val iconOverride = mutableStateMapOf<String, String>()
    /**
     * 未读通知数：包名 -> 数字。由 [com.duolauncher.service.DuoNotificationListener] 维护，
     * 属于进程内临时状态，不持久化（重启后由监听服务重新同步通知栏）。
     */
    val notifCounts = mutableStateMapOf<String, Int>()

    var showBadges by mutableStateOf(true)          // 图标右上角显示未读数字
    var badgeColor by mutableStateOf(IOS_RED)       // 角标底色（ARGB）

    // ── 搜索 ──
    /** 搜索页「最近常用」显示数量：5 / 10 / 15 / 20 */
    var recentCount by mutableStateOf(10)
    /** 网页搜索引擎 id：baidu / bing / google */
    var searchEngine by mutableStateOf("baidu")
    /** 是否在搜索结果里包含通讯录联系人（需要 READ_CONTACTS） */
    var searchContacts by mutableStateOf(true)
    /** 主屏是否显示玻璃侧边栏 */
    var showSidebar by mutableStateOf(true)
    /** App 资源库那一页是否显示（关闭后 pager 里只剩主屏页与搜索页） */
    var showLibrary by mutableStateOf(true)
    /** 主屏左下角「菜单」图标（点开含启动器设置）是否显示 */
    /** 主屏右下角「搜索」图标是否显示 */

    // ── GitHub Releases 更新检查 ──
    /** 更新源地址；可以改成自建镜像，空串会自动回落到默认地址 */
    var updateUrl by mutableStateOf(UpdateChecker.DEFAULT_URL)
    /** 进入设置页时静默检查，12 小时内只查一次；默认关闭，避免未经操作的联网 */
    var autoCheckUpdate by mutableStateOf(false)
    var lastUpdateCheck by mutableStateOf(0L)
    /** 上次查到的最新版本号，只用来在设置行上显示提示，不弹窗打扰 */
    var lastSeenVersion by mutableStateOf("")

    // ── 匿名使用统计 ──
    /** 是否启用匿名统计；可在「设置 → 隐私与统计」随时关闭，关闭即删除本地匿名 ID */
    var statsEnabled by mutableStateOf(true)
    /** 首次启动的告知弹窗是否已确认；未确认前绝不联网上报 */
    var statsConsentShown by mutableStateOf(false)
    /** 上报地址；留空 = 完全不上报，可改成自建服务 */
    var statsUrl by mutableStateOf(InstallStats.DEFAULT_URL)

    var smartCategory by mutableStateOf(true)
    var sidebarLeft by mutableStateOf(false)
    // 「顶部额外留白」：0 = 只按系统状态栏 / 挖孔自动避让（推荐）。
    // 老存档里的 88 是旧版"整块让位"的默认值，load() 里已迁移成 0；
    // 新装也必须是 0，否则第一行会被顶到 120dp 以下。
    var cutoutPadding by mutableStateOf(0)
    /**
     * 系统顶部占用高度（px）：状态栏高度与**挖孔下沿**的较大值，由 MainActivity 的
     * windowInsets 监听实时写入。挖孔常常开在左上角，`statusBars.top` 往往为 0 或很小，
     * 只按它避让会让第一行图标被那颗摄像头压住（Titan 2 Elite 实测）。
     * 取挖孔包围盒的 bottom 才能把整行推到孔下面。
     */
    var systemTopInsetPx by mutableStateOf(0)          // 临时状态，不持久化
    var insetDensity by mutableStateOf(1f)

    /** 系统顶部占用（dp）；还没收到 inset 时为 0，调用方按平台经验值兜底 */
    val systemTopDp: Float
        get() = if (insetDensity > 0f) systemTopInsetPx / insetDensity else 0f

    fun setSystemTopInset(px: Int, density: Float) {
        if (density <= 0f) return
        val p = if (px < 0) 0 else px
        if (p != systemTopInsetPx || density != insetDensity) {
            systemTopInsetPx = p
            insetDensity = density
        }
    }

    var glassLevel by mutableStateOf(0.55f)
    var darkGlass by mutableStateOf(true)   // 新装默认深色玻璃；已有配置仍按用户保存的值
    var hideStatusBar by mutableStateOf(true)
    var showPageDots by mutableStateOf(true)
    var reversePages by mutableStateOf(false)
    var doubleTapSleep by mutableStateOf(true)      // 双击桌面空白处息屏
    var gesturePanels by mutableStateOf(true)       // 下滑呼出通知栏 / 控制中心（左半屏=通知，右半屏=控制中心）
    var gestureSearch by mutableStateOf(true)       // 桌面最左页右滑打开搜索
    var iconScale by mutableStateOf(1.0f)            // 主屏图标大小
    var sidebarIconScale by mutableStateOf(0.82f)   // 侧边栏图标相对主屏更小（参考 iPhone Duo）
    /** 主屏图标每行列数：手机 4–5、平板 6–7 */
    var homeColumns by mutableStateOf(4)
    /** 主屏图标每页行数：手机 5–6、平板可更多；每页容量 = 列数 × 行数 */
    var homeRows by mutableStateOf(5)
    /**
     * 新安装的应用是否自动放到主屏幕。
     * 默认关闭 = 桌面只放用户自己挑的应用；打开后仅对「本次运行期间新装的应用」生效，
     * 不会把已经装好的应用补进桌面，也不会撤销用户手动移除的图标。
     */
    var autoAddNewApps by mutableStateOf(false)
    /** 侧边栏玻璃浓度（底色倍率）；值越大越实、越小越透（壁纸透出更多） */
    var sidebarGlass by mutableStateOf(1.2f)
    /** 侧边栏垂直对齐：center = 屏幕居中，bottom = 靠下居中（避开顶部状态与底部按钮） */
    var sidebarAlign by mutableStateOf("center")
    /** 是否显示右上角「正在播放」音乐胶囊 */
    var showNowPlaying by mutableStateOf(true)
    /** 当前正在播放的媒体（非播放态为 null） */
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    var iconPack by mutableStateOf("")              // 选中的图标包包名，空=系统默认
    var appLoadDone by mutableStateOf(false)
    /** 主屏图标正在被拖动（临时状态，不持久化）：拖拽期间要锁住翻页和下滑手势 */
    var dragging by mutableStateOf(false)
    var sidebarVer by mutableStateOf(0)             // 侧栏默认槽位版本：<7 表示还是老的 5 槽布局
    /**
     * 主屏是否已经初始化过。
     * 早期版本用 "homeApps 为空" 判断是否首次启动，导致用户把主屏清空后，
     * 每次 onResume（reloadApps → ensureDefaults）都会把 16 个应用重新填回去。
     * 现在用一次性标记；且新版本默认就是空主屏，该标记主要用于兼容老存档。
     */
    var homeSeeded by mutableStateOf(false)
    // ── iOS 风格状态指示器（时间 + 弧形信号）──
    var showDuoStatus by mutableStateOf(true)      // 默认显示，替代安卓原生状态栏
    var statusRight by mutableStateOf(true)        // 默认右上，避开 Titan 2 Elite 左上挖孔
    var status24h by mutableStateOf(true)
    // 信号格一律按真实强度绘制，设置里不再提供开关
    var statusTop by mutableStateOf(26)            // 距顶部 dp（默认比旧版更靠上）
    /** 状态栏位置版本：<1 表示还是旧默认 40dp，升级时统一上提到 26dp */
    var statusTopVer by mutableStateOf(0)
    var statusScale by mutableStateOf(1.0f)
    /**
     * 4G 网络时徽标底部显示的文字：LTE / 4G。
     * 其余制式（2G / 3G / 5G / 5GA / 5GUW）按真实注册网络自动显示，不由此开关控制。
     */
    var status4gLabel by mutableStateOf("LTE")
    /** 是否显示电量数字；关闭后顶部圆环连成完整一段 */
    var showBatteryNumber by mutableStateOf(true)

    // ── 壁纸自动切换：默认关闭，不改变用户原有壁纸习惯 ──
    var wallpaperRotate by mutableStateOf(false)
    /** 切换间隔（分钟） */
    var wallpaperIntervalMin by mutableStateOf(60)
    var wallpaperRandom by mutableStateOf(false)
    var wallpaperLastAt by mutableStateOf(0L)
    var wallpaperIndex by mutableStateOf(0)
    /** 每次换壁纸 +1，让毛玻璃底图缓存失效、重新取系统壁纸 */
    var wallpaperVersion by mutableStateOf(0)
    /** 主屏上滑切换壁纸（与下滑呼出系统面板互不干扰）；默认关闭，不影响原有手势 */
    var wallpaperSwipe by mutableStateOf(false)
    /** 锁屏与主屏壁纸同步：开启时切换主屏壁纸一并更换锁屏；默认关闭，不影响原有习惯 */
    var wallpaperSyncLockscreen by mutableStateOf(false)
    /** 已应用的壁纸裁切算法版本；低于当前版本时回桌面自动重绘当前壁纸一次 */
    var wallpaperRenderVer by mutableStateOf(0)
    /**
     * 当前系统壁纸是否由本应用设置。状态栏图标取色时据此选数据源：
     * true → 直接对自有图片按屏幕比例采样（最精确、不受系统壁纸缓存影响）；
     * 用户从图库手动换壁纸时由 OnColorsChangedListener 置 false，改采系统壁纸。
     */
    var wallpaperOwned by mutableStateOf(true)

    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        selfPackage = app.packageName
        sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        ready = true
        load()
    }

    private fun load() {
        val raw = sp.getString("state", null) ?: return
        hasArchive = true
        runCatching {
            val o = JSONObject(raw)
            homeApps.clear(); homeApps.addAll(o.strArray("home"))
            homeOrder.clear(); homeOrder.addAll(o.strArray("homeOrder"))
            homeWidgets.clear(); homeWidgets.addAll(o.strArray("homeWg"))
            widgetRowMap.clear()
            o.optJSONObject("wgRows")?.let { obj ->
                obj.keys().forEach { k -> widgetRowMap[k] = obj.optInt(k, 2) }
            }
            widgetColMap.clear()
            o.optJSONObject("wgCols")?.let { obj ->
                obj.keys().forEach { k -> widgetColMap[k] = obj.optInt(k, 4) }
            }
            homeSlots.clear()
            o.optJSONObject("homeSlots")?.let { obj ->
                obj.keys().forEach { k ->
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) homeSlots[k] = v
                }
            }
            sidebar.clear(); sidebar.addAll(o.strArray("sidebar").map { SidebarItem.decode(it) })
            hidden.clear(); hidden.addAll(o.strArray("hidden"))
            folders.clear(); folders.addAll(o.optJSONArray("folders")
                ?.let { a -> (0 until a.length()).mapNotNull { FolderItem.decode(a.optString(it)) } } ?: emptyList())
            iconPack = o.optString("iconPack", "")
            sidebarIconScale = o.optDouble("sidebarIconScale", 0.82).toFloat()
            categoryOverride.clear()
            o.optJSONObject("cats")?.let { obj ->
                obj.keys().forEach { k -> categoryOverride[k] = obj.optString(k) }
            }
            customCategories.clear()
            o.optJSONArray("userCats")?.let { a ->
                (0 until a.length()).mapNotNull { CategoryDef.decode(a.optString(it)) }
                    .forEach { customCategories.add(it) }
            }
            categoryRenames.clear()
            o.optJSONObject("catRen")?.let { obj ->
                obj.keys().forEach { k ->
                    val v = obj.optString(k)
                    if (v.isNotBlank()) categoryRenames[k] = v
                }
            }
            hiddenCategories.clear()
            o.strArray("catHide").forEach { if (it.isNotBlank()) hiddenCategories.add(it) }
            iconOverride.clear()
            o.optJSONObject("iconOv")?.let { obj ->
                obj.keys().forEach { k ->
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) iconOverride[k] = v
                }
            }
            usage.clear()
            o.optJSONObject("usage")?.let { obj ->
                obj.keys().forEach { k ->
                    obj.optJSONObject(k)?.let { u ->
                        usage[k] = UsageRec(u.optInt("c", 0), u.optLong("t", 0L))
                    }
                }
            }
            smartCategory = o.optBoolean("smart", true)
            sidebarLeft = o.optBoolean("left", false)
            // 「挖孔避让高度」语义变更：顶部改为按真实系统 inset 自动避让，
            // 这个值只剩「额外再加多少」。旧存档里的 88（老默认，没人真调过）迁移成 0，
            // 否则第一行图标会比系统状态栏低出一大截。cutoutV2 保证迁移只做一次。
            cutoutPadding = if (o.optBoolean("cutoutV2", false)) {
                o.optInt("cutout", 0).coerceIn(0, 120)
            } else {
                val old = o.optInt("cutout", 88)
                if (old <= 88) 0 else (old - 88).coerceIn(0, 120)
            }
            glassLevel = o.optDouble("glass", 0.55).toFloat()
            darkGlass = o.optBoolean("dark", true)
            hideStatusBar = o.optBoolean("hideStatus", true)
            showPageDots = o.optBoolean("dots", true)
            reversePages = o.optBoolean("reverse", false)
            doubleTapSleep = o.optBoolean("dblSleep", true)
            gesturePanels = o.optBoolean("gsPanels", true)
            gestureSearch = o.optBoolean("gsSearch", true)
            iconScale = o.optDouble("iconScale", 1.0).toFloat()
            homeColumns = o.optInt("homeCols", 4).let { n -> if (n in 3..7) n else 4 }
            homeRows = o.optInt("homeRows", 5).let { n -> if (n in 4..7) n else 5 }
            autoAddNewApps = o.optBoolean("autoNew", false)
            sidebarGlass = o.optDouble("sbGlass", 1.2).toFloat().coerceIn(0.4f, 2.4f)
            sidebarAlign = o.optString("sbAlign", "center").let { if (it == "bottom") "bottom" else "center" }
            showNowPlaying = o.optBoolean("npOn", true)
            sidebarVer = o.optInt("sbVer", 0)
            // 有存档 = 早就初始化过主屏了；缺省 true，避免"清空后被重新填满"
            homeSeeded = o.optBoolean("homeSeeded", true)
            showDuoStatus = o.optBoolean("duoStatus", true)
            statusRight = o.optBoolean("statusRight", true)
            status24h = o.optBoolean("status24h", true)
            statusTop = o.optInt("statusTop", 26)
            statusTopVer = o.optInt("statusTopVer", 0)
            statusScale = o.optDouble("statusScale", 1.0).toFloat()
            // 旧存档 netLabel 是「5G / LTE」二选一；现在 5G 家族由真实制式自动识别，
            // 该开关只管 4G 显示 LTE 还是 4G。旧值 5G 归到默认 LTE（5G 文字仍会自动出现）。
            status4gLabel = if (o.optString("net4g", "") == "4G" ||
                o.optString("netLabel", "") == "4G") "4G" else "LTE"
            showBatteryNumber = o.optBoolean("battNum", true)
            wallpaperRotate = o.optBoolean("wpRotate", false)
            wallpaperIntervalMin = o.optInt("wpInterval", 60).let { if (it > 0) it else 60 }
            wallpaperRandom = o.optBoolean("wpRandom", false)
            wallpaperLastAt = o.optLong("wpLastAt", 0L)
            wallpaperIndex = o.optInt("wpIndex", 0)
            wallpaperSwipe = o.optBoolean("wpSwipe", false)
            wallpaperSyncLockscreen = o.optBoolean("wpSyncLs", false)
            wallpaperRenderVer = o.optInt("wpRenderVer", 0)
            wallpaperOwned = o.optBoolean("wpOwned", true)
            showBadges = o.optBoolean("badgeOn", true)
            badgeColor = o.optInt("badgeColor", IOS_RED)
            recentCount = o.optInt("recentCnt", 10).let { n ->
                if (n in listOf(5, 10, 15, 20)) n else 10
            }
            searchEngine = o.optString("searchEng", "baidu").ifBlank { "baidu" }
            searchContacts = o.optBoolean("searchContacts", true)
            showSidebar = o.optBoolean("showSb", true)
            showLibrary = o.optBoolean("libOn", true)
            updateUrl = o.optString("updUrl", UpdateChecker.DEFAULT_URL)
                .ifBlank { UpdateChecker.DEFAULT_URL }
            autoCheckUpdate = o.optBoolean("updAuto", true)
            lastUpdateCheck = o.optLong("updAt", 0L)
            lastSeenVersion = o.optString("updVer", "")
            statsEnabled = o.optBoolean("statOn", true)
            statsConsentShown = o.optBoolean("statConsent", false)
            statsUrl = o.optString("statUrl", InstallStats.DEFAULT_URL)
        }
    }

    /**
     * 主屏排版版本号：主屏相关数据（顺序 / 坐标 / 小组件尺寸）变化时自增。
     *
     * UI 侧拿它当 `remember` 的键，就不会在每次无关重组（换深色、翻页、状态栏刷新…）
     * 时把整页排版重算一遍 —— layoutHome 每次都要分配每页的占用矩阵。
     */
    var homeLayoutVersion by mutableIntStateOf(0)
        private set

    /** 合并窗口：250ms 内的连续改动只落盘一次（设置页拖滑块是每帧一次 save） */
    private const val SAVE_DEBOUNCE_MS = 250L

    /** 写盘线程：只做 SharedPreferences 写，主线程不碰磁盘 */
    private val saveIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "duo-state-save").apply { isDaemon = true }
    }
    private val saveHandler = Handler(Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    /**
     * 保存配置。
     *
     * 旧实现每次都把小一百个字段重新打包成 JSON 再写盘；而设置页的滑块
     * （图标大小、玻璃强度、状态栏位置…）在 `onValueChange` 里每帧调一次 save，
     * 拖一下就是几十次全量序列化 + 几十次磁盘写 —— 拖动越顺、写盘越密，
     * 既卡主线程又白白耗电。
     *
     * 现在：主线程只做「版本号 +1 + 清坐标」这类必需的轻量工作，真正的
     * 序列化与写盘合并到停手 250ms 后执行一次；离开桌面时 [flush] 强制落盘，
     * 不会因为合并窗口没到而丢改动。
     */
    fun save() {
        homeLayoutVersion++
        if (!ready) return
        pruneHomeSlots()
        pendingSave?.let { saveHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingSave = null
            writeNow()
        }
        pendingSave = task
        saveHandler.postDelayed(task, SAVE_DEBOUNCE_MS)
    }

    /** 立即落盘：Activity 进后台 / 导出备份 / 关统计等场景，别等合并窗口 */
    fun flush() {
        pendingSave?.let { saveHandler.removeCallbacks(it) }
        pendingSave = null
        if (ready) writeNow()
    }

    private fun writeNow() {
        // 快照在主线程取（Compose State 只在主线程安全读），落盘丢给 IO 线程
        val json = runCatching {
            val o = JSONObject()
            o.put("home", JSONArray(homeApps))
            o.put("homeOrder", JSONArray(homeOrder))
            o.put("homeWg", JSONArray(homeWidgets))
            val wgRows = JSONObject(); widgetRowMap.forEach { (k, v) -> wgRows.put(k, v) }
            o.put("wgRows", wgRows)
            val wgCols = JSONObject(); widgetColMap.forEach { (k, v) -> wgCols.put(k, v) }
            o.put("wgCols", wgCols)
            val slots = JSONObject(); homeSlots.forEach { (k, v) -> slots.put(k, v) }
            o.put("homeSlots", slots)
            o.put("sidebar", JSONArray(sidebar.map { it.encode() }))
            o.put("hidden", JSONArray(hidden))
            o.put("folders", JSONArray(folders.map { it.encode() }))
            o.put("iconPack", iconPack)
            o.put("sidebarIconScale", sidebarIconScale.toDouble())
            val cats = JSONObject(); categoryOverride.forEach { (k, v) -> cats.put(k, v) }
            o.put("cats", cats)
            o.put("userCats", JSONArray(customCategories.map { it.encode() }))
            val catRen = JSONObject(); categoryRenames.forEach { (k, v) -> catRen.put(k, v) }
            o.put("catRen", catRen)
            o.put("catHide", JSONArray(hiddenCategories))
            val iconOv = JSONObject(); iconOverride.forEach { (k, v) -> iconOv.put(k, v) }
            o.put("iconOv", iconOv)
            val usg = JSONObject(); usage.forEach { (k, v) ->
                usg.put(k, JSONObject().put("c", v.count).put("t", v.last))
            }
            o.put("usage", usg)
            o.put("smart", smartCategory)
            o.put("left", sidebarLeft)
            o.put("cutout", cutoutPadding)
            o.put("cutoutV2", true)
            o.put("glass", glassLevel.toDouble())
            o.put("dark", darkGlass)
            o.put("hideStatus", hideStatusBar)
            o.put("dots", showPageDots)
            o.put("reverse", reversePages)
            o.put("dblSleep", doubleTapSleep)
            o.put("gsPanels", gesturePanels)
            o.put("gsSearch", gestureSearch)
            o.put("iconScale", iconScale.toDouble())
            o.put("homeCols", homeColumns)
            o.put("homeRows", homeRows)
            o.put("autoNew", autoAddNewApps)
            o.put("sbGlass", sidebarGlass.toDouble())
            o.put("sbAlign", sidebarAlign)
            o.put("npOn", showNowPlaying)
            o.put("sbVer", sidebarVer)
            o.put("homeSeeded", homeSeeded)
            o.put("duoStatus", showDuoStatus)
            o.put("statusRight", statusRight)
            o.put("status24h", status24h)
            o.put("statusTop", statusTop)
            o.put("statusTopVer", statusTopVer)
            o.put("statusScale", statusScale.toDouble())
            o.put("net4g", status4gLabel)
            o.put("battNum", showBatteryNumber)
            o.put("wpRotate", wallpaperRotate)
            o.put("wpInterval", wallpaperIntervalMin)
            o.put("wpRandom", wallpaperRandom)
            o.put("wpLastAt", wallpaperLastAt)
            o.put("wpIndex", wallpaperIndex)
            o.put("wpSwipe", wallpaperSwipe)
            o.put("wpSyncLs", wallpaperSyncLockscreen)
            o.put("wpRenderVer", wallpaperRenderVer)
            o.put("wpOwned", wallpaperOwned)
            o.put("badgeOn", showBadges)
            o.put("badgeColor", badgeColor)
            o.put("recentCnt", recentCount)
            o.put("searchEng", searchEngine)
            o.put("searchContacts", searchContacts)
            o.put("showSb", showSidebar)
            o.put("libOn", showLibrary)
            o.put("updUrl", updateUrl)
            o.put("updAuto", autoCheckUpdate)
            o.put("updAt", lastUpdateCheck)
            o.put("updVer", lastSeenVersion)
            o.put("statOn", statsEnabled)
            o.put("statConsent", statsConsentShown)
            o.put("statUrl", statsUrl)
            o.toString()
        }.getOrNull() ?: return
        saveIo.execute { runCatching { sp.edit().putString("state", json).apply() } }
    }

    /**
     * 备份导入后重新读取存档，把新配置灌回这些 Compose State。
     * [load] 会给每个字段都赋值（缺失的走缺省），所以直接复用即可，不用先手动清空。
     */
    fun reload() {
        if (ready) load()
    }

    private fun JSONObject.strArray(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, null) }
    }

    /** 首次启动构造默认主屏 / 侧边栏 */
    fun ensureDefaults() {
        if (!ready) return
        // 顶部时间/徽标上提：旧默认 40dp 在真机上偏下，一次性迁移到 26dp
        if (statusTopVer < 1) {
            if (statusTop == 40) statusTop = 26
            statusTopVer = 1
        }
        val installed = apps.filter { it.packageName != selfPackage }
        // 新安装默认「空主屏」：不再自动塞 16 个应用图标，桌面放什么完全由用户决定。
        // 想加图标：App 资源库里长按应用 → 添加到主屏幕（或长按主屏空白处）。
        // homeSeeded 仍置 true：用户主动清空主屏后不会在 onResume 被重新填满。
        homeSeeded = true
        // 只有全新安装才铺默认侧栏；有存档时（含备份导入 / 用户主动清空）保持现状，
        // 否则清空侧栏后每次回到桌面都会被自动塞回去。
        if (!hasArchive && sidebar.isEmpty()) {
            sidebar.addAll(defaultSidebar(installed))
            sidebarVer = SIDEBAR_SLOTS
        } else if (sidebarVer < SIDEBAR_SLOTS && sidebar.size in 4 until SIDEBAR_SLOTS) {
            // 老版本默认只有 4 应用 + 搜索 = 5 个；这里补齐到 7 个。
            // 只追加缺失的应用，不动用户已有的条目（用户手动改过数量的不强行改）
            val exist = sidebar.filter { it.type == "app" }.map { it.value }.toMutableSet()
            val add = defaultSidebar(installed)
                .filter { it.type == "app" && exist.add(it.value) }
                .take(SIDEBAR_SLOTS - sidebar.size)
            sidebar.addAll(add)
            sidebarVer = SIDEBAR_SLOTS
        }
        save()
    }

    /**
     * 默认侧栏：6 个常用应用 + 1 个搜索动作 = [SIDEBAR_SLOTS] 个。
     * 先按关键字挑常用应用，不够再用已安装应用补齐。
     */
    private fun defaultSidebar(installed: List<AppInfo>): List<SidebarItem> {
        val preferred = listOf(
            "phone", "dialer", "contacts", "mms", "sms", "messaging", "camera",
            "wechat", "mm", "settings", "browser", "chrome", "calendar", "clock",
            "music", "file", "note", "map"
        )
        val picked = linkedSetOf<String>()
        preferred.forEach { key ->
            installed.firstOrNull { a ->
                a.packageName.lowercase().contains(key) && !picked.contains(a.id)
            }?.let { picked.add(it.id) }
        }
        installed.forEach { if (picked.size < SIDEBAR_SLOTS - 1) picked.add(it.id) }
        val items = picked.take(SIDEBAR_SLOTS - 1).map { SidebarItem("app", it) }.toMutableList()
        items.add(SidebarItem("action", "search"))
        return items
    }

    fun visibleApps(): List<AppInfo> =
        apps.filter { it.packageName != selfPackage && !hidden.contains(it.id) }

    // guessCategory 每个应用要做上百次字符串 contains + 一次 lowercase 分配，
    // 而资源库 / 设置里的分类统计每帧都会对全部应用各算一两遍（上百个应用 = 几万次扫描）。
    // 这里按「id + 当前覆盖值 + 是否智能分类」缓存结果，只在真正变化时重算。
    private val categoryCache = HashMap<String, String>(256)

    fun categoryOf(app: AppInfo): String {
        val ov = categoryOverride[app.id]
        val key = app.id + "|" + (ov ?: "") + "|" + smartCategory
        categoryCache[key]?.let { return it }
        if (categoryCache.size > 1024) categoryCache.clear()
        val v = ov ?: if (smartCategory) {
            guessCategory(app.label, app.packageName, app.systemCategory)
        } else "system"
        categoryCache[key] = v
        return v
    }

    // ── 分类表：内置（可改名 / 可删）+ 用户自建 ──

    /** 分类显示名：自定义名 > 自建分类名 > strings.xml > 原样返回 */
    fun categoryDisplayName(id: String): String {
        if (id == CATEGORY_OTHER) return Lang.s(R.string.cat_other)
        categoryRenames[id]?.takeIf { it.isNotBlank() }?.let { return it }
        customCategories.firstOrNull { it.id == id }?.let { return it.name }
        CATEGORY_LIST.firstOrNull { it.first == id }?.let { return Lang.s(it.second) }
        return id
    }

    /** 当前生效的分类表（id → 显示名）：内置去掉被删的，自建追加在末尾 */
    fun categoryList(): List<Pair<String, String>> {
        val builtin = CATEGORY_LIST
            .filter { it.first !in hiddenCategories }
            .map { it.first to categoryDisplayName(it.first) }
        val custom = customCategories.map { it.id to it.name }
        return builtin + custom
    }

    /** 指定分类下的应用数（资源库里能看到的那些） */
    fun categoryAppCount(id: String): Int =
        visibleApps().count { categoryOf(it) == id }

    /** 名称是否已被占用（「其他」是兜底卡的名字，也算占用；[exceptId] 用于改名时排除自己） */
    fun isDuplicateCategoryName(name: String, exceptId: String? = null): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (n.equals(Lang.s(R.string.cat_other), true)) return true
        return categoryList().any { it.first != exceptId && it.second.equals(n, true) }
    }

    /** 新建分类，返回新 id；名称重复或为空时返回 null */
    fun addCategory(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (isDuplicateCategoryName(n)) return null
        val id = "u${System.currentTimeMillis()}"
        customCategories.add(CategoryDef(id, n))
        save()
        return id
    }

    /** 改名：内置分类写 [categoryRenames]，自建分类直接改自己的名字 */
    fun renameCategory(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || isDuplicateCategoryName(n, id)) return
        val self = customCategories.firstOrNull { it.id == id }
        if (self != null) {
            val idx = customCategories.indexOf(self)
            customCategories[idx] = self.copy(name = n)
        } else {
            categoryRenames[id] = n
        }
        save()
    }

    /**
     * 删除分类。
     *
     * 内置分类是**软删除**（只进 [hiddenCategories]）：应用本身不动，也不会被卸载，
     * 只是资源库里不再出这张卡；这些应用会落到「其他」卡（[CATEGORY_OTHER]）里。
     * 自建分类直接从表里移走，同时把指向它的手动归类清掉，免得留下悬空 id。
     */
    fun deleteCategory(id: String) {
        if (id == CATEGORY_OTHER) return
        val self = customCategories.firstOrNull { it.id == id }
        if (self != null) {
            customCategories.remove(self)
            categoryOverride.entries.filter { it.value == id }.map { it.key }
                .forEach { categoryOverride.remove(it) }
        } else {
            if (!hiddenCategories.contains(id)) hiddenCategories.add(id)
        }
        categoryRenames.remove(id)
        save()
    }

    /** 已删除/已不存在的分类 id 归到「其他」 */
    fun normalizeCategory(id: String): String =
        if (categoryList().any { it.first == id }) id else CATEGORY_OTHER

    /** 把内置分类的删除记录清空（自建分类不受影响） */
    fun restoreBuiltinCategories() {
        hiddenCategories.clear()
        save()
    }

    // ── 应用查找索引 ──
    // 以前每次都是 `apps.firstOrNull {...}` 线性扫一遍：主屏几十个图标 × 上百个应用，
    // 每次重组就上万次字符串比较（homeTokens 里又是嵌套调用，量级再翻一倍），
    // 是中低端机上「回桌面 / 拖动图标卡一下」的主要来源之一。改成一次性建表 O(1) 查。
    private val appIndex = HashMap<String, AppInfo>(256)
    private var appIndexDirty = true

    /** 应用列表被整表替换（扫包完成后调用），索引随之失效 */
    fun replaceApps(list: List<AppInfo>) {
        apps.clear()
        apps.addAll(list)
        appIndexDirty = true
    }

    /** 按 AppInfo.id 查找。主用户 id 仍是纯包名，旧存档无需迁移 */
    fun appOf(key: String): AppInfo? {
        // size 变化也要重建：addHome / 首次扫描都会往列表里追加
        if (appIndexDirty || appIndex.size != apps.size) {
            appIndex.clear()
            apps.forEach { appIndex[it.id] = it }
            appIndexDirty = false
        }
        return appIndex[key]
    }

    // ── 通知角标 ──
    /** 图标上要显示的数字；关闭角标或无未读时为 0 */
    fun badgeOf(pkg: String): Int = if (!showBadges) 0 else (notifCounts[pkg] ?: 0)

    /** 监听服务聚合后的整份未读数写回（差值相同就不动，避免无意义重组） */
    fun setNotifCounts(map: Map<String, Int>) {
        // 逐 key 更新：旧实现 clear() + putAll() 会让所有观察角标的图标一起重组，
        // 一条新消息就重绘整片桌面。SnapshotStateMap 支持按 key 精确失效，
        // 只有数字真正变化的那个应用图标会刷新。
        val next = map.filterValues { it > 0 }
        if (notifCounts.size == next.size && next.all { notifCounts[it.key] == it.value }) return
        notifCounts.keys.toList().forEach { if (it !in next) notifCounts.remove(it) }
        next.forEach { (k, v) -> if (notifCounts[k] != v) notifCounts[k] = v }
    }

    /** 打开某个应用后清掉它的角标（iOS 行为：进过一次就算已读） */
    fun clearBadge(pkg: String) {
        if (notifCounts.containsKey(pkg)) notifCounts.remove(pkg)
    }

    fun recordUsage(pkg: String) {
        val r = usage[pkg] ?: UsageRec()
        r.count += 1
        r.last = System.currentTimeMillis()
        usage[pkg] = r
        save()
    }

    fun recentApps(count: Int = 10): List<AppInfo> {
        // 先建 id→AppInfo 的表。旧实现是「每条使用记录都把全部应用扫一遍」，
        // 上百条记录 × 几百个应用 = 每算一次几万次字符串比较；而搜索页每次
        // 重组（每敲一个字）都会调它一次，输入手感就是这么被拖慢的。
        val available = visibleApps()
        val byId = HashMap<String, AppInfo>(available.size * 2)
        available.forEach { byId[it.id] = it }
        val out = ArrayList<AppInfo>(count)
        val seen = HashSet<String>(count * 2)
        val ordered = usage.entries.sortedWith(
            compareByDescending<Map.Entry<String, UsageRec>> { it.value.last }
                .thenByDescending { it.value.count }
        )
        for (e in ordered) {
            if (out.size >= count) break
            val app = byId[e.key] ?: continue
            if (seen.add(app.id)) out.add(app)
        }
        if (out.size < count) {
            available.sortedBy { it.label }.forEach {
                if (out.size < count && seen.add(it.id)) out.add(it)
            }
        }
        return out
    }

    // ── 主屏顺序 ──
    /**
     * 当前主屏应有的 token 顺序（纯读取，不改盘）：
     * 先按 homeOrder 排，再把没排过的应用/文件夹补到末尾。
     */
    fun homeTokens(): List<String> {
        // 用 Set 判断「是否还活着 / 是否已排过」：以前是 List 上的 contains，
        // 应用多的时候这里是 O(n²)，而它每次排版（layoutHome）都会被调用。
        // 注意 appTokens 必须保持顺序（列表本身是 LinkedHashSet 的插入序来源）。
        val appTokens = homeApps
            .filter { !hidden.contains(it) && appOf(it) != null }
            .map { "app:$it" }
        val folderTokens = folders.map { "folder:${it.id}" }
        val widgetTokens = homeWidgets.map { "widget:$it" }
        val alive = HashSet<String>(appTokens.size + folderTokens.size + widgetTokens.size)
        alive.addAll(appTokens)
        alive.addAll(folderTokens)
        alive.addAll(widgetTokens)
        val out = LinkedHashSet<String>(alive.size * 2)
        homeOrder.forEach { if (alive.contains(it)) out.add(it) }
        appTokens.forEach { out.add(it) }
        folderTokens.forEach { out.add(it) }
        widgetTokens.forEach { out.add(it) }
        return out.toList()
    }

    /**
     * 主屏小组件占几行。Android 侧一格按 70dp 算（provider 的 minHeight = 70×行数 − 30），
     * 所以 4×2 的小组件 minHeight≈110 → 2 行，4×4 的≈250 → 4 行。
     * 换算结果夹在 1~4 行：太小放不下内容，太大会把整页占满。
     */
    fun rowsFromMinHeight(minHeightDp: Int): Int {
        val raw = ((minHeightDp + 30) / 70f).toInt()
        return raw.coerceIn(1, 4)
    }

    /** 主屏某个小组件实际占几行（添加时写入，缺失则按 2 行兜底） */
    fun widgetRowsOf(key: String): Int = (widgetRowMap[key] ?: 2).coerceIn(1, 4)

    /**
     * 主屏小组件占几列。4×2 的 provider minWidth≈250 → 4 列，4×1 的≈110 → 2 列。
     *
     * 以前小组件一律「吃满整行」，于是 4×2 的组件被拉成一整行、内容挤在中间，
     * 而且左右怎么拖都在原地（列坐标被强制成 0）。按声明列数摆放之后，
     * 半行宽的小组件就能左右移动了。
     */
    fun colsFromMinWidth(minWidthDp: Int): Int {
        val raw = ((minWidthDp + 30) / 70f).toInt()
        return raw.coerceIn(2, 6)
    }

    fun widgetColsOf(key: String): Int = (widgetColMap[key] ?: 4).coerceIn(2, 6)

    /** 主屏网格底部固定留白（底部页码 / 搜索那一条） */
    const val GRID_BOTTOM_PAD_DP = 76f

    /**
     * 主屏网格顶部要空出的高度（dp）。**唯一口径**：MainActivity 算页数、
     * HomePage 排版都调这里，不能各算一套。
     *
     * 两种情况不需要为「时间 + 电量」整块让位：
     *  • 状态块和侧栏在同一条纵向通道上（同一侧）——网格横向本来就避开了那条通道；
     *  • 横屏时挖孔在侧边，顶部只要一点点呼吸位。
     *
     * 旧逻辑恒取 `cutoutPadding + 64`（默认 152dp），在平板横屏上能吃掉近三成屏幕，
     * 表现出来就是「最上面白白空着一排，图标和小组件都放不进去」。
     */
    fun gridTopGapDp(landscape: Boolean, systemTopDp: Float = this.systemTopDp): Float {
        val statusTopPad = if (statusRight) statusTop else maxOf(statusTop, 64)
        val scale = statusScale.coerceIn(0.55f, 1.8f)
        val statusH = (18f * 1.35f + 6f + 50f) * scale
        val statusDrawTop = (statusTopPad - 20f).coerceAtLeast(2f)
        // 状态块画在侧栏通道里，且与侧栏同侧
        val statusInLane = showSidebar && (sidebarLeft != statusRight)
        // 顶部只避开真实的状态栏 / 挖孔：拿得到系统 inset 就用它，拿不到（隐藏状态栏、
        // 极老设备）才退回经验值。以前这里写死 88+24≈112dp，竖屏设备光这一段就吃掉
        // 小半排高度，第一行图标看着明显靠下。
        val inset = if (systemTopDp > 0.5f) systemTopDp else if (landscape) 16f else 40f
        // [cutoutPadding] 语义已改为「在自动避让之外再额外留多少」，默认 0。
        // 末尾这 12dp 是给「左上角挖孔」留的：有些设备 displayCutout 只报个很小的矩形，
        // 甚至完全不报，图标上沿贴着算出来的高度时仍会被孔压住一点（Titan 2 Elite 实测），
        // 所以再固定下移 12dp —— 视觉上只差一点点，但正好躲开孔。
        val safeTop = inset + cutoutPadding.coerceIn(0, 120) + 12f
        if (!showDuoStatus) return safeTop
        // 状态块和侧栏同侧：网格横向本来就避开了那条通道，顶部与状态块顶端对齐即可
        if (statusInLane) return maxOf(safeTop, statusDrawTop)
        // 状态块横在网格上方（与侧栏不同侧）：不让位就会压住第一行，必须让开
        return maxOf(safeTop, statusDrawTop + statusH + 6f)
    }

    /**
     * 每页实际行数：设置值为主，**屏幕还空得下一整行时自动补 1 行**（最多补 1 行、总数封顶 8）。
     * 补 1 行是为了吃掉「顶部留白收窄后多出来的那一排」，而不是把设置彻底接管：
     * 设置调大仍然有效，用户想更松散随时可以调回去。
     */
    fun effectiveHomeRows(screenHeightDp: Int, landscape: Boolean): Int {
        val avail = screenHeightDp - gridTopGapDp(landscape) - GRID_BOTTOM_PAD_DP
        // 单行高度：图标 + 文字标签 + 最小行距（宁可算紧一点，也别让最后一行被裁掉）
        val rowUnit = 54f * iconScale.coerceIn(0.7f, 1.4f) + 18f + 6f
        if (avail <= 0f || rowUnit <= 0f) return homeRows
        val fit = (avail / rowUnit).toInt()
        return fit.coerceIn(homeRows, minOf(homeRows + 1, 8))
    }

    // ── 主屏自由摆放（绝对格子坐标）──

    /**
     * 主屏上一个条目的落位：第 [page] 页、第 [row] 行、第 [col] 列，
     * 占 [rowSpan] 行 × [colSpan] 列。图标 / 文件夹恒为 1×1，小组件按 provider 声明。
     */
    data class HomePlacement(
        val token: String,
        val page: Int,
        val row: Int,
        val col: Int,
        val rowSpan: Int,
        val colSpan: Int
    )

    /** 某个 token 占几行：小组件按 provider 声明换算，其余恒为 1 行 */
    fun tokenRowSpan(token: String, rows: Int = homeRows): Int =
        if (token.startsWith("widget:"))
            widgetRowsOf(token.removePrefix("widget:")).coerceAtMost(rows.coerceAtLeast(1))
        else 1

    /** 某个 token 占几列：小组件按 provider 声明换算，其余恒为 1 列 */
    fun tokenColSpan(token: String, cols: Int = homeColumns): Int =
        if (token.startsWith("widget:"))
            widgetColsOf(token.removePrefix("widget:")).coerceAtMost(cols.coerceAtLeast(1))
        else 1

    /** 兼容旧调用：等价于 [tokenRowSpan] */
    fun tokenSpan(token: String, rows: Int = homeRows): Int = tokenRowSpan(token, rows)

    /**
     * 主屏实际排版：**唯一口径**（MainActivity 算页数、HomePage 画格子都必须调它）。
     *
     * 两段式：
     *  ① 先落 [homeSlots] 里有手动坐标的条目（校验越界 / 与已落位的重叠，不合法就退回 ②）；
     *  ② 其余条目按 homeOrder 顺序"流式"填进最靠前的空位。
     *
     * 占用按**矩形**算（行 × 列）：图标 1×1，小组件 = provider 声明的 4×2 之类。
     * 这样小组件只占半行，左右也能挪；不再像以前那样强制吃满整行、列坐标恒为 0。
     */
    fun layoutHome(cols: Int = homeColumns, rows: Int = homeRows): List<List<HomePlacement>> {
        val c = cols.coerceIn(1, 12)
        val r = rows.coerceIn(1, 12)
        val tokens = homeTokens()
        val occ = HashMap<Int, Array<BooleanArray>>()

        fun grid(p: Int): Array<BooleanArray> = occ.getOrPut(p) { Array(r) { BooleanArray(c) } }

        fun fitsAt(p: Int, row: Int, col: Int, rowSpan: Int, colSpan: Int): Boolean {
            if (p < 0 || row < 0 || col < 0) return false
            if (row + rowSpan > r || col + colSpan > c) return false
            val g = grid(p)
            for (dr in 0 until rowSpan) for (dc in 0 until colSpan) {
                if (g[row + dr][col + dc]) return false
            }
            return true
        }

        fun take(p: Int, row: Int, col: Int, rowSpan: Int, colSpan: Int) {
            val g = grid(p)
            for (dr in 0 until rowSpan) for (dc in 0 until colSpan) g[row + dr][col + dc] = true
        }

        fun firstFree(p: Int, rowSpan: Int, colSpan: Int): Pair<Int, Int>? {
            for (row in 0..(r - rowSpan)) for (col in 0..(c - colSpan)) {
                if (fitsAt(p, row, col, rowSpan, colSpan)) return row to col
            }
            return null
        }

        val placed = LinkedHashMap<String, HomePlacement>()

        // ① 显式坐标
        tokens.forEach { t ->
            val raw = homeSlots[t] ?: return@forEach
            val parts = raw.split(",")
            if (parts.size < 3) return@forEach
            val p = parts[0].toIntOrNull() ?: return@forEach
            val row = parts[1].toIntOrNull() ?: return@forEach
            val col = parts[2].toIntOrNull() ?: return@forEach
            if (p < 0) return@forEach
            val rs = tokenRowSpan(t, r)
            val cs = tokenColSpan(t, c)
            val rr = row.coerceIn(0, (r - rs).coerceAtLeast(0))
            val cc = col.coerceIn(0, (c - cs).coerceAtLeast(0))
            if (!fitsAt(p, rr, cc, rs, cs)) return@forEach
            take(p, rr, cc, rs, cs)
            placed[t] = HomePlacement(t, p, rr, cc, rs, cs)
        }

        // ② 流式补空位
        var maxPage = placed.values.maxOfOrNull { it.page } ?: 0
        tokens.forEach { t ->
            if (placed.containsKey(t)) return@forEach
            val rs = tokenRowSpan(t, r)
            val cs = tokenColSpan(t, c)
            var pg = 0
            var spot: Pair<Int, Int>? = null
            while (pg <= maxPage + 1) {
                spot = firstFree(pg, rs, cs)
                if (spot != null) break
                pg++
            }
            if (spot == null) { pg = maxPage + 1; spot = 0 to 0 }
            take(pg, spot.first, spot.second, rs, cs)
            placed[t] = HomePlacement(t, pg, spot.first, spot.second, rs, cs)
            if (pg > maxPage) maxPage = pg
        }

        val pages = MutableList(maxPage + 1) { mutableListOf<HomePlacement>() }
        placed.values.forEach { pl -> if (pl.page in pages.indices) pages[pl.page].add(pl) }
        pages.forEach { list -> list.sortWith(compareBy({ it.row }, { it.col })) }
        return pages
    }

    /** 当前所有落位（便于按 token 查坐标） */
    fun placements(cols: Int = homeColumns, rows: Int = homeRows): List<HomePlacement> =
        layoutHome(cols, rows).flatten()

    /**
     * 把 [token] 放到第 [page] 页第 [row] 行第 [col] 列（左上角对齐，按它自己的行×列跨度占位）。
     *
     * 落位以手指为准：目标矩形里原有的条目要么跟它**交换**（1×1 对 1×1 时），
     * 要么让位（清坐标、交给流式重排，不会丢）。小组件以前只认「整行都空」，
     * 所以经常拖不到指定位置；现在按矩形判定，落到哪一行就是哪一行。
     */
    fun placeToken(
        token: String,
        page: Int,
        row: Int,
        col: Int,
        cols: Int = homeColumns,
        rows: Int = homeRows
    ): Boolean {
        val c = cols.coerceIn(1, 12)
        val r = rows.coerceIn(1, 12)
        val all = placements(c, r)
        val moving = all.firstOrNull { it.token == token } ?: return false
        if (page < 0) return false
        val rs = tokenRowSpan(token, r)
        val cs = tokenColSpan(token, c)
        val rr = row.coerceIn(0, (r - rs).coerceAtLeast(0))
        val cc = col.coerceIn(0, (c - cs).coerceAtLeast(0))
        if (page == moving.page && rr == moving.row && cc == moving.col) return false

        val blocked = all.filter {
            it.token != token && it.page == page &&
                    it.row < rr + rs && it.row + it.rowSpan > rr &&
                    it.col < cc + cs && it.col + it.colSpan > cc
        }

        // 单格换单格：直接互换，观感最自然
        val single = blocked.size == 1 && rs == 1 && cs == 1 &&
                blocked[0].rowSpan == 1 && blocked[0].colSpan == 1
        if (single) {
            val oc = blocked[0]
            val backRow = moving.row
            val backCol = moving.col.coerceIn(0, c - 1)
            homeSlots[oc.token] = "${moving.page},$backRow,$backCol"
        } else {
            blocked.forEach { homeSlots.remove(it.token) }
        }

        // 老坐标写着在目标区域里、实际被挤到别处的条目也要清掉，
        // 否则 layoutHome 的显式坐标阶段会先一步把目标格占回去（小组件尤其明显）。
        homeSlots.entries.toList().forEach { (t, raw) ->
            if (t == token) return@forEach
            val parts = raw.split(",")
            if (parts.size < 3) return@forEach
            val p = parts[0].toIntOrNull() ?: return@forEach
            val rw = parts[1].toIntOrNull() ?: return@forEach
            val cl = parts[2].toIntOrNull() ?: return@forEach
            val trs = tokenRowSpan(t, r)
            val tcs = tokenColSpan(t, c)
            if (p == page && rw < rr + rs && rw + trs > rr && cl < cc + cs && cl + tcs > cc) {
                homeSlots.remove(t)
            }
        }

        homeSlots[token] = "$page,$rr,$cc"
        save()
        return true
    }

    /** 清掉所有手动坐标，主屏回到自动流式排列（列数 / 行数大改后可用） */
    fun clearHomeSlots() {
        if (homeSlots.isEmpty()) return
        homeSlots.clear()
        save()
    }

    /** 存档瘦身：条目已不在主屏（被移除 / 隐藏 / 卸载）时，顺手删掉它的坐标 */
    private fun pruneHomeSlots() {
        if (homeSlots.isEmpty()) return
        // 应用列表还没扫描完时 homeTokens() 里一个 app token 都没有，
        // 这时做"清理"会把所有坐标当成失效删光（重装/冷启动阶段尤其危险）。
        if (!appLoadDone || apps.isEmpty()) return
        val alive = homeTokens().toSet()
        homeSlots.keys.toList().forEach { if (it !in alive) homeSlots.remove(it) }
    }

    fun addHome(pkg: String) {
        if (!homeApps.contains(pkg)) homeApps.add(pkg)
        // 重新加回主屏时放到末尾（不要让 homeOrder 里的老位置把它"拽"回中间）
        val token = "app:$pkg"
        if (homeOrder.contains(token)) { homeOrder.remove(token); homeOrder.add(token) }
        homeSeeded = true
        save()
    }

    /**
     * 把系统小组件加到主屏**第一屏左上角**（第 0 页第 0 行）。
     *
     * 齐老师 2026-11-18：新加的小组件必须在左上角、一眼看得见，加完还可以拖到任意位置。
     * 所以这里：① 把 token 排到 homeOrder 最前；② 给它写死坐标 (0,0)；
     * ③ 原来占着它那几行的条目，能往下挪就整体下移，挪不动就清掉坐标交给自动排布
     * （不会丢，只是重新流式排一次）。
     */
    fun addHomeWidget(appWidgetId: Int, minWidthDp: Int = 0, minHeightDp: Int = 0) {
        val key = appWidgetId.toString()
        if (!homeWidgets.contains(key)) homeWidgets.add(key)
        if (minHeightDp > 0) widgetRowMap[key] = rowsFromMinHeight(minHeightDp)
        if (minWidthDp > 0) widgetColMap[key] = colsFromMinWidth(minWidthDp)
        val token = "widget:$key"
        val base = homeTokens().filter { it != token }
        homeOrder.clear()
        homeOrder.add(token)
        homeOrder.addAll(base)
        // 新小组件按自己的行×列跨度钉在第一屏 (0,0)
        val rs = tokenRowSpan(token, homeRows)
        val cs = tokenColSpan(token, homeColumns)
        homeSlots.entries.toList().forEach { (t, raw) ->
            if (t == token) return@forEach
            val parts = raw.split(",")
            if (parts.size < 3) { homeSlots.remove(t); return@forEach }
            val p = parts[0].toIntOrNull() ?: 0
            val row = parts[1].toIntOrNull() ?: 0
            val col = parts[2].toIntOrNull() ?: 0
            if (p != 0 || row >= rs || col >= cs) return@forEach
            // 原来压在这块区域里的条目：能整体下移就下移，放不下就交给自动排布
            val trs = tokenRowSpan(t, homeRows)
            val nr = row + rs
            if (nr + trs <= homeRows) homeSlots[t] = "0,$nr,$col" else homeSlots.remove(t)
        }
        homeSlots[token] = "0,0,0"
        homeSeeded = true
        save()
    }

    /** 补齐历史数据里没有记行数的小组件（老版本加的），避免一直按 2 行占位 */
    fun ensureWidgetRows(appWidgetId: Int, minHeightDp: Int) {
        val key = appWidgetId.toString()
        if (!homeWidgets.contains(key)) return
        val want = rowsFromMinHeight(minHeightDp)
        if (widgetRowMap[key] != want) { widgetRowMap[key] = want; save() }
    }

    /** 同 [ensureWidgetRows]，顺便把列跨度也补上（老存档只有行数） */
    fun ensureWidgetSize(appWidgetId: Int, minWidthDp: Int, minHeightDp: Int) {
        val key = appWidgetId.toString()
        if (!homeWidgets.contains(key)) return
        var dirty = false
        val wantRows = rowsFromMinHeight(minHeightDp)
        if (widgetRowMap[key] != wantRows) { widgetRowMap[key] = wantRows; dirty = true }
        val wantCols = colsFromMinWidth(minWidthDp)
        if (widgetColMap[key] != wantCols) { widgetColMap[key] = wantCols; dirty = true }
        if (dirty) save()
    }

    fun removeHomeWidget(appWidgetId: Int) {
        val key = appWidgetId.toString()
        homeWidgets.remove(key)
        homeOrder.remove("widget:$key")
        widgetRowMap.remove(key)
        widgetColMap.remove(key)
        save()
    }

    fun isHomeWidget(appWidgetId: Int): Boolean = homeWidgets.contains(appWidgetId.toString())

    /**
     * 把主屏小组件整体挪到相邻一屏（[dir] = -1 上一屏 / +1 下一屏）。
     * 拖拽手势只在当前一屏内起作用，跨屏靠这个补上。
     *
     * 坐标模型下不需要再猜插入点：把页号改掉、在目标页找一个空位即可。
     */
    fun moveHomeWidgetPage(
        appWidgetId: Int,
        dir: Int,
        cols: Int = homeColumns,
        rows: Int = homeRows
    ): Boolean = moveTokenToPage("widget:$appWidgetId", dir, cols, rows)

    /**
     * 把任意主屏条目（图标 / 文件夹 / 小组件）整块挪到相邻一屏。
     *
     * 坐标模型下这件事变得直接：把它的页号 +1 / −1，在该页找一个空位放进去。
     * 拖动只在当前页内生效，跨屏靠边缘松手或菜单调用这里补上。
     */
    fun moveTokenToPage(
        token: String,
        dir: Int,
        cols: Int = homeColumns,
        rows: Int = homeRows
    ): Boolean {
        if (dir == 0) return false
        val c = cols.coerceIn(1, 12)
        val r = rows.coerceIn(1, 12)
        val pages = layoutHome(c, r)
        val from = pages.indexOfFirst { list -> list.any { it.token == token } }
        if (from < 0) return false
        val target = from + dir
        // 往左不能小于 0；往右最多新建一页（否则会一路建出一堆空页）
        if (target < 0 || target > pages.size) return false
        val rs = tokenRowSpan(token, r)
        val cs = tokenColSpan(token, c)

        fun freeSpot(): Pair<Int, Int>? {
            val list = pages.getOrNull(target) ?: return 0 to 0
            val used = Array(r) { BooleanArray(c) }
            list.forEach { p ->
                for (dr in 0 until p.rowSpan) for (dc in 0 until p.colSpan) {
                    val rr = p.row + dr
                    val cc = p.col + dc
                    if (rr in 0 until r && cc in 0 until c) used[rr][cc] = true
                }
            }
            for (row in 0..(r - rs)) for (col in 0..(c - cs)) {
                var free = true
                for (dr in 0 until rs) for (dc in 0 until cs) {
                    if (used[row + dr][col + dc]) { free = false }
                }
                if (free) return row to col
            }
            return null
        }

        val spot = freeSpot() ?: return false
        homeSlots[token] = "$target,${spot.first},${spot.second}"
        save()
        return true
    }

    /** 设置页「批量添加」专用：一次改完再写盘，避免逐个 addHome 反复序列化整份配置 */
    fun addHomeBatch(pkgs: Collection<String>) {
        var changed = false
        pkgs.forEach { pkg ->
            if (appOf(pkg) == null || hidden.contains(pkg)) return@forEach
            if (!homeApps.contains(pkg)) {
                homeApps.add(pkg)
                changed = true
                // 新加入的一律排到末尾（曾被移出主屏的老位置不要把它拽回中间）；
                // 已经在桌面的应用保持原位，自动添加时才不会打乱用户排好的布局。
                val token = "app:$pkg"
                homeOrder.remove(token)
                homeOrder.add(token)
            }
        }
        if (!changed) return
        homeSeeded = true
        save()
    }

    /**
     * 设置页「批量清除」专用：一次移除多个桌面应用图标。
     * 同时删掉 homeOrder 里对应的 token，避免存档里堆越来越多失效条目；
     * 文件夹、其余图标及其顺序都不动。只移除图标，不卸载应用。
     */
    fun removeHomeBatch(pkgs: Collection<String>) {
        val targets = pkgs.filter { homeApps.contains(it) }.toSet()
        if (targets.isEmpty()) return
        homeApps.removeAll(targets)
        val tokens = targets.map { "app:$it" }.toSet()
        homeOrder.removeAll { tokens.contains(it) }
        homeSeeded = true
        save()
    }

    fun removeHome(pkg: String) { homeApps.remove(pkg); homeSeeded = true; save() }

    fun hideApp(pkg: String) {
        if (!hidden.contains(pkg)) hidden.add(pkg)
        homeApps.remove(pkg)
        sidebar.removeAll { it.type == "app" && it.value == pkg }
        save()
    }

    fun unhideApp(pkg: String) { hidden.remove(pkg); save() }

    fun setCategory(pkg: String, category: String?) {
        if (category == null) categoryOverride.remove(pkg) else categoryOverride[pkg] = category
        save()
    }

    fun setSidebar(items: List<SidebarItem>) {
        sidebar.clear(); sidebar.addAll(items); save()
    }

    // ── 文件夹 ──
    fun folderOf(id: String): FolderItem? = folders.firstOrNull { it.id == id }

    /** 新建文件夹并放入若干应用（这些应用会从主屏独立图标中移除） */
    fun createFolder(name: String, pkgs: List<String>): String {
        val id = "f${System.currentTimeMillis()}"
        // 文件夹尽量落在"被收进去的第一个应用"原来的位置，而不是永远排到最后
        val before = homeTokens().toMutableList()
        val at = before.indexOfFirst { t -> pkgs.any { t == "app:$it" } }
        folders.add(FolderItem(id, name.ifBlank { Lang.s(R.string.folder) }, pkgs.distinct()))
        pkgs.forEach { homeApps.remove(it) }
        if (at >= 0) {
            before[at] = "folder:$id"
            homeOrder.clear(); homeOrder.addAll(before)
        }
        save()
        return id
    }

    fun renameFolder(id: String, name: String) {
        val f = folderOf(id) ?: return
        val idx = folders.indexOf(f)
        folders[idx] = f.copy(name = name.ifBlank { Lang.s(R.string.folder) })
        save()
    }

    /** 把应用加入文件夹（若已在其它文件夹则移出，并从主屏独立图标移除） */
    fun addAppToFolder(id: String, pkg: String) {
        val f = folderOf(id) ?: return
        val idx = folders.indexOf(f)
        val others = folders.filter { it.id != id }
        others.forEach { of ->
            val oi = folders.indexOf(of)
            if (of.apps.contains(pkg)) folders[oi] = of.copy(apps = of.apps - pkg)
        }
        folders[idx] = f.copy(apps = (f.apps + pkg).distinct())
        homeApps.remove(pkg)
        save()
    }

    /** 把应用移出文件夹；若文件夹变空则删除；应用回到主屏独立图标 */
    fun removeAppFromFolder(id: String, pkg: String) {
        val f = folderOf(id) ?: return
        val idx = folders.indexOf(f)
        val newApps = f.apps - pkg
        if (newApps.isEmpty()) folders.removeAt(idx)
        else folders[idx] = f.copy(apps = newApps)
        if (!homeApps.contains(pkg)) homeApps.add(pkg)
        save()
    }

    fun deleteFolder(id: String) {
        val f = folderOf(id) ?: return
        f.apps.forEach { if (!homeApps.contains(it)) homeApps.add(it) }
        folders.remove(f)
        save()
    }
}
