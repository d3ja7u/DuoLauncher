package com.duolauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duolauncher.R
import com.duolauncher.util.InstallStats
import com.duolauncher.util.Lang
import com.duolauncher.util.UpdateChecker
import org.json.JSONArray
import org.json.JSONObject

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
    val sidebar = mutableStateListOf<SidebarItem>()
    val hidden = mutableStateListOf<String>()
    val widgets = mutableStateListOf<WidgetItem>()
    val folders = mutableStateListOf<FolderItem>()
    val usage = mutableStateMapOf<String, UsageRec>()
    val categoryOverride = mutableStateMapOf<String, String>()
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
    /** 主屏幕是否显示玻璃侧边栏 */
    var showSidebar by mutableStateOf(true)
    /** 主屏左下角「菜单」图标（点开含启动器设置）是否显示 */
    var showBottomLeft by mutableStateOf(true)
    /** 主屏右下角「搜索」图标是否显示 */
    var showBottomRight by mutableStateOf(true)

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
    var cutoutPadding by mutableStateOf(88)
    var glassLevel by mutableStateOf(0.55f)
    var darkGlass by mutableStateOf(false)
    var hideStatusBar by mutableStateOf(true)
    var showPageDots by mutableStateOf(true)
    var reversePages by mutableStateOf(false)
    var doubleTapSleep by mutableStateOf(true)      // 双击桌面空白处息屏
    var gesturePanels by mutableStateOf(true)       // 下滑呼出通知栏 / 控制中心（左半屏=通知，右半屏=控制中心）
    var gestureSearch by mutableStateOf(true)       // 桌面最左页右滑打开搜索
    var iconScale by mutableStateOf(1.0f)            // 主屏图标大小
    var sidebarIconScale by mutableStateOf(0.82f)   // 侧边栏图标相对主屏更小（参考 iPhone Duo）
    var iconPack by mutableStateOf("")              // 选中的图标包包名，空=系统默认
    var notes by mutableStateOf("")
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
    /**
     * 负一屏小组件是否已经生成过默认集合。
     * 原因同上：以前用 "widgets 为空" 判断首次，用户把负一屏删空后，
     * 每次 onResume（reloadApps → ensureDefaults）都会把默认 4 个塞回去。
     * 现在也是一次性标记，删空就永远保持空。
     */
    var widgetsSeeded by mutableStateOf(false)

    // ── iOS 风格状态指示器（时间 + 弧形信号）──
    var showDuoStatus by mutableStateOf(true)      // 默认显示，替代安卓原生状态栏
    var statusRight by mutableStateOf(true)        // 默认右上，避开 Titan 2 Elite 左上挖孔
    var status24h by mutableStateOf(true)
    var statusRealSignal by mutableStateOf(false)  // 默认 4 点常亮，与概念图一致
    var statusTop by mutableStateOf(26)            // 距顶部 dp（默认比旧版更靠上）
    /** 状态栏位置版本：<1 表示还是旧默认 40dp，升级时统一上提到 26dp */
    var statusTopVer by mutableStateOf(0)
    var statusScale by mutableStateOf(1.0f)

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
            sidebar.clear(); sidebar.addAll(o.strArray("sidebar").map { SidebarItem.decode(it) })
            hidden.clear(); hidden.addAll(o.strArray("hidden"))
            widgets.clear(); widgets.addAll(o.strArray("widgets").map { WidgetItem.decode(it) })
            folders.clear(); folders.addAll(o.optJSONArray("folders")
                ?.let { a -> (0 until a.length()).mapNotNull { FolderItem.decode(a.optString(it)) } } ?: emptyList())
            iconPack = o.optString("iconPack", "")
            sidebarIconScale = o.optDouble("sidebarIconScale", 0.82).toFloat()
            categoryOverride.clear()
            o.optJSONObject("cats")?.let { obj ->
                obj.keys().forEach { k -> categoryOverride[k] = obj.optString(k) }
            }
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
            cutoutPadding = o.optInt("cutout", 88)
            glassLevel = o.optDouble("glass", 0.55).toFloat()
            darkGlass = o.optBoolean("dark", false)
            hideStatusBar = o.optBoolean("hideStatus", true)
            showPageDots = o.optBoolean("dots", true)
            reversePages = o.optBoolean("reverse", false)
            doubleTapSleep = o.optBoolean("dblSleep", true)
            gesturePanels = o.optBoolean("gsPanels", true)
            gestureSearch = o.optBoolean("gsSearch", true)
            iconScale = o.optDouble("iconScale", 1.0).toFloat()
            notes = o.optString("notes", "")
            sidebarVer = o.optInt("sbVer", 0)
            // 有存档 = 早就初始化过主屏了；缺省 true，避免"清空后被重新填满"
            homeSeeded = o.optBoolean("homeSeeded", true)
            widgetsSeeded = o.optBoolean("wgSeeded", true)
            showDuoStatus = o.optBoolean("duoStatus", true)
            statusRight = o.optBoolean("statusRight", true)
            status24h = o.optBoolean("status24h", true)
            statusRealSignal = o.optBoolean("realSignal", false)
            statusTop = o.optInt("statusTop", 26)
            statusTopVer = o.optInt("statusTopVer", 0)
            statusScale = o.optDouble("statusScale", 1.0).toFloat()
            showBadges = o.optBoolean("badgeOn", true)
            badgeColor = o.optInt("badgeColor", IOS_RED)
            recentCount = o.optInt("recentCnt", 10).let { n ->
                if (n in listOf(5, 10, 15, 20)) n else 10
            }
            searchEngine = o.optString("searchEng", "baidu").ifBlank { "baidu" }
            searchContacts = o.optBoolean("searchContacts", true)
            showSidebar = o.optBoolean("showSb", true)
            showBottomLeft = o.optBoolean("bl", true)
            showBottomRight = o.optBoolean("br", true)
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

    fun save() {
        if (!ready) return
        runCatching {
            val o = JSONObject()
            o.put("home", JSONArray(homeApps))
            o.put("homeOrder", JSONArray(homeOrder))
            o.put("sidebar", JSONArray(sidebar.map { it.encode() }))
            o.put("hidden", JSONArray(hidden))
            o.put("widgets", JSONArray(widgets.map { it.encode() }))
            o.put("folders", JSONArray(folders.map { it.encode() }))
            o.put("iconPack", iconPack)
            o.put("sidebarIconScale", sidebarIconScale.toDouble())
            val cats = JSONObject(); categoryOverride.forEach { (k, v) -> cats.put(k, v) }
            o.put("cats", cats)
            val iconOv = JSONObject(); iconOverride.forEach { (k, v) -> iconOv.put(k, v) }
            o.put("iconOv", iconOv)
            val usg = JSONObject(); usage.forEach { (k, v) ->
                usg.put(k, JSONObject().put("c", v.count).put("t", v.last))
            }
            o.put("usage", usg)
            o.put("smart", smartCategory)
            o.put("left", sidebarLeft)
            o.put("cutout", cutoutPadding)
            o.put("glass", glassLevel.toDouble())
            o.put("dark", darkGlass)
            o.put("hideStatus", hideStatusBar)
            o.put("dots", showPageDots)
            o.put("reverse", reversePages)
            o.put("dblSleep", doubleTapSleep)
            o.put("gsPanels", gesturePanels)
            o.put("gsSearch", gestureSearch)
            o.put("iconScale", iconScale.toDouble())
            o.put("notes", notes)
            o.put("sbVer", sidebarVer)
            o.put("homeSeeded", homeSeeded)
            o.put("wgSeeded", widgetsSeeded)
            o.put("duoStatus", showDuoStatus)
            o.put("statusRight", statusRight)
            o.put("status24h", status24h)
            o.put("realSignal", statusRealSignal)
            o.put("statusTop", statusTop)
            o.put("statusTopVer", statusTopVer)
            o.put("statusScale", statusScale.toDouble())
            o.put("badgeOn", showBadges)
            o.put("badgeColor", badgeColor)
            o.put("recentCnt", recentCount)
            o.put("searchEng", searchEngine)
            o.put("searchContacts", searchContacts)
            o.put("showSb", showSidebar)
            o.put("bl", showBottomLeft)
            o.put("br", showBottomRight)
            o.put("updUrl", updateUrl)
            o.put("updAuto", autoCheckUpdate)
            o.put("updAt", lastUpdateCheck)
            o.put("updVer", lastSeenVersion)
            o.put("statOn", statsEnabled)
            o.put("statConsent", statsConsentShown)
            o.put("statUrl", statsUrl)
            sp.edit().putString("state", o.toString()).apply()
        }
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

    /** 首次启动构造默认主屏 / 侧边栏 / 负一屏 */
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
        // 负一屏默认小组件同样只填一次；删空后保持空，不再自动回来
        if (!widgetsSeeded) {
            if (widgets.isEmpty()) {
                widgets.addAll(
                    listOf(
                        WidgetItem("clock"),
                        WidgetItem("battery"),
                        WidgetItem("recents"),
                        WidgetItem("quick")
                    )
                )
            }
            widgetsSeeded = true
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
                a.packageName.lowercase().contains(key) && !picked.contains(a.packageName)
            }?.let { picked.add(it.packageName) }
        }
        installed.forEach { if (picked.size < SIDEBAR_SLOTS - 1) picked.add(it.packageName) }
        val items = picked.take(SIDEBAR_SLOTS - 1).map { SidebarItem("app", it) }.toMutableList()
        items.add(SidebarItem("action", "search"))
        return items
    }

    fun visibleApps(): List<AppInfo> =
        apps.filter { it.packageName != selfPackage && !hidden.contains(it.packageName) }

    fun categoryOf(app: AppInfo): String =
        categoryOverride[app.packageName]
            ?: if (smartCategory) guessCategory(app.label, app.packageName, app.systemCategory) else "system"

    fun appOf(pkg: String): AppInfo? = apps.firstOrNull { it.packageName == pkg }

    // ── 通知角标 ──
    /** 图标上要显示的数字；关闭角标或无未读时为 0 */
    fun badgeOf(pkg: String): Int = if (!showBadges) 0 else (notifCounts[pkg] ?: 0)

    /** 监听服务聚合后的整份未读数写回（差值相同就不动，避免无意义重组） */
    fun setNotifCounts(map: Map<String, Int>) {
        if (notifCounts.size == map.size && map.all { notifCounts[it.key] == it.value }) return
        notifCounts.clear()
        notifCounts.putAll(map.filterValues { it > 0 })
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
        val available = visibleApps()
        val sorted = usage.entries
            .sortedWith(compareByDescending<Map.Entry<String, UsageRec>> { it.value.last }
                .thenByDescending { it.value.count })
            .mapNotNull { e -> available.firstOrNull { it.packageName == e.key } }
            .distinctBy { it.packageName }
            .take(count)
            .toMutableList()
        if (sorted.size < count) {
            available.sortedBy { it.label }.forEach {
                if (sorted.size < count && sorted.none { a -> a.packageName == it.packageName }) sorted.add(it)
            }
        }
        return sorted
    }

    // ── 主屏顺序 ──
    /**
     * 当前主屏应有的 token 顺序（纯读取，不改盘）：
     * 先按 homeOrder 排，再把没排过的应用/文件夹补到末尾。
     */
    fun homeTokens(): List<String> {
        val apps = homeApps
            .filter { !hidden.contains(it) && appOf(it) != null }
            .map { "app:$it" }
        val folderTokens = folders.map { "folder:${it.id}" }
        val alive = (apps + folderTokens).toSet()
        val out = homeOrder.filter { alive.contains(it) }.toMutableList()
        apps.forEach { if (!out.contains(it)) out.add(it) }
        folderTokens.forEach { if (!out.contains(it)) out.add(it) }
        return out
    }

    /** 拖拽排序：把主屏第 [from] 个条目移动到第 [to] 个位置 */
    fun moveHome(from: Int, to: Int) {
        if (from == to) return
        val list = homeTokens().toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val token = list.removeAt(from)
        list.add(to, token)
        homeOrder.clear(); homeOrder.addAll(list)
        save()
    }

    fun addHome(pkg: String) {
        if (!homeApps.contains(pkg)) homeApps.add(pkg)
        // 重新加回主屏时放到末尾（不要让 homeOrder 里的老位置把它"拽"回中间）
        val token = "app:$pkg"
        if (homeOrder.contains(token)) { homeOrder.remove(token); homeOrder.add(token) }
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

    fun setWidgets(items: List<WidgetItem>) {
        widgets.clear(); widgets.addAll(items); save()
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
