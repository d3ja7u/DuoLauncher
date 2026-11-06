package com.duolauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * 图标包支持：枚举已安装图标包，按 appfilter.xml 把目标应用映射到包内 drawable；
 * 另外支持「手动指定」——用户在图标包内自行挑一个图标（存在 DuoState.iconOverride）。
 * 仅做尽力匹配，任何异常都安全降级为 null（调用方继续用系统图标）。
 */
object IconPacks {

    data class PackInfo(val pkg: String, val label: String)

    private val filterCache = mutableMapOf<String, Map<String, String>>()
    private val iconCache = mutableMapOf<String, Drawable?>()
    private val drawablesCache = mutableMapOf<String, List<String>>()
    private val ownerCache = mutableMapOf<String, Map<String, String>>()

    /** 枚举本机已安装的图标包（覆盖 nova / adw / go / 等常见 intent 声明） */
    fun listPacks(ctx: Context): List<PackInfo> {
        val pm = ctx.packageManager
        val actions = listOf(
            "com.novalauncher.THEME",
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.zeromus.gb.launcher.THEMES",
            "com.spock.launcher.THEME",
            "com.dcss.launcher.THEMES",
            "com.anddoes.launcher.THEME"
        )
        val set = LinkedHashSet<PackInfo>()
        for (action in actions) {
            runCatching {
                val intent = Intent(action)
                pm.queryIntentActivities(intent, 0).forEach { ri ->
                    val pkg = ri.activityInfo.packageName
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                    set.add(PackInfo(pkg, label))
                }
            }
        }
        return set.toList()
    }

    /** 解析图标包内的 appfilter.xml，返回 key(ComponentInfo{包/类} 或 包名) -> drawable 名 */
    private fun loadFilter(pm: PackageManager, packPkg: String): Map<String, String> {
        filterCache[packPkg]?.let { return it }
        val map = mutableMapOf<String, String>()
        runCatching {
            val pRes: Resources = pm.getResourcesForApplication(packPkg)
            val id = pRes.getIdentifier("appfilter", "xml", packPkg)
            if (id != 0) {
                val xml = pRes.getXml(id)
                var event = xml.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "item") {
                        val comp = xml.getAttributeValue(null, "component")
                        val pkgAttr = xml.getAttributeValue(null, "pkg")
                            ?: xml.getAttributeValue(null, "package")
                        val draw = xml.getAttributeValue(null, "drawable")
                        if (draw != null) {
                            if (comp != null) map[comp] = draw
                            if (pkgAttr != null) map[pkgAttr] = draw
                        }
                    }
                    event = xml.next()
                }
            }
        }.onFailure { Log.w("IconPacks", "loadFilter failed: $packPkg", it) }
        filterCache[packPkg] = map
        return map
    }

    /**
     * 列出图标包里所有可用的图标名（供手动挑选）。
     *
     * 优先反射包内 `R$drawable` 的字段（最全，覆盖 appfilter 未收录的图标）；
     * 反射失败（R 类被混淆 / 非标准资源引用）时退化为 appfilter.xml 里出现过的 drawable 名。
     */
    fun listDrawables(ctx: Context, packPkg: String): List<String> {
        if (packPkg.isBlank()) return emptyList()
        drawablesCache[packPkg]?.let { return it }
        val names = linkedSetOf<String>()
        runCatching {
            val cls = Class.forName("$packPkg.R\$drawable")
            cls.fields.forEach { f ->
                // 只取 R.drawable 里的静态 int 常量
                if (f.type == Int::class.javaPrimitiveType) names.add(f.name)
            }
        }.onFailure { Log.w("IconPacks", "reflect R.drawable failed: $packPkg", it) }

        if (names.isEmpty()) {
            runCatching { names.addAll(loadFilter(ctx.packageManager, packPkg).values) }
        }
        // 过滤掉明显不是应用图标的资源（壁纸/背景/预览图等），减少干扰
        val filtered = names
            .filterNot { n ->
                val l = n.lowercase()
                l.startsWith("wallpaper") || l.startsWith("bg_") || l.contains("_bg") ||
                    l.startsWith("preview") || l.startsWith("screenshot") ||
                    l.startsWith("ic_launcher") || l == "icon" || l.startsWith("abc_")
            }
            .distinct()
            .sorted()
            .take(4000)
        drawablesCache[packPkg] = filtered
        return filtered
    }

    /** 按名字取图标包里的 drawable；取不到返回 null */
    fun drawableOf(ctx: Context, packPkg: String, name: String): Drawable? = runCatching {
        val pRes = ctx.packageManager.getResourcesForApplication(packPkg)
        val id = pRes.getIdentifier(name, "drawable", packPkg)
        if (id == 0) null else pRes.getDrawable(id)?.mutate()
    }.getOrNull()

    /**
     * 返回应用应显示的图标：
     * 1. 用户手动指定的图标（跨图标包也生效）
     * 2. 当前图标包 appfilter 的自动匹配
     * 都取不到返回 null（调用方用系统图标）。
     */
    fun resolveIcon(ctx: Context, packPkg: String, app: AppInfo): Drawable? {
        val ov = DuoState.iconOverride[app.packageName]
        if (packPkg.isBlank() && ov == null) return null
        val cacheKey = "$packPkg|${ov ?: ""}|${app.packageName}|${app.activityClass}"
        iconCache[cacheKey]?.let { return it }

        val pm = ctx.packageManager
        val result = runCatching {
            // ── 1. 手动指定 ──
            if (ov != null) {
                val cut = ov.indexOf('|')
                if (cut > 0) {
                    val ovPack = ov.substring(0, cut)
                    val ovName = ov.substring(cut + 1)
                    if (ovPack.isNotBlank() && ovName.isNotBlank()) {
                        val manual = drawableOf(ctx, ovPack, ovName)
                        if (manual != null) return@runCatching manual
                    }
                }
            }
            if (packPkg.isBlank()) return@runCatching null

            // ── 2. 图标包自动匹配 ──
            val pRes = pm.getResourcesForApplication(packPkg)
            val filter = loadFilter(pm, packPkg)
            val short = when {
                app.activityClass.startsWith(".") -> app.activityClass
                app.activityClass.startsWith(app.packageName) ->
                    "." + app.activityClass.removePrefix(app.packageName)
                else -> app.activityClass
            }
            val comp = "ComponentInfo{${app.packageName}/$short}"
            val fullComp = "ComponentInfo{${app.packageName}/${app.activityClass}}"
            var drawName = filter[comp] ?: filter[fullComp] ?: filter[app.packageName]
            var id = if (drawName != null) pRes.getIdentifier(drawName, "drawable", packPkg) else 0

            // appfilter 没收录时，按常见命名习惯再猜一轮（很多包直接用包名当资源名）：
            //   com.tencent.mm -> com_tencent_mm / mm / wechat(label)
            if (id == 0) {
                for (c in guessNames(app)) {
                    val cid = pRes.getIdentifier(c, "drawable", packPkg)
                    if (cid != 0) { id = cid; drawName = c; break }
                }
            }
            if (id == 0) return@runCatching null
            pRes.getDrawable(id)?.mutate()
        }.getOrNull()

        iconCache[cacheKey] = result
        return result
    }

    /** 按常见命名习惯，为应用猜测它在图标包里可能的 drawable 名 */
    private fun guessNames(app: AppInfo): List<String> {
        val pkg = app.packageName
        val label = app.label.lowercase().replace(" ", "_")
        return buildList {
            add(pkg.replace('.', '_'))
            add(pkg.replace('.', '_').lowercase())
            add(pkg.substringAfterLast('.'))
            add(pkg.substringAfterLast('.').lowercase())
            add(pkg.lowercase())
            if (label.isNotBlank()) { add(label); add("ic_$label") }
        }.distinct().filter { it.length >= 3 }
    }

    /**
     * drawable 名 -> 它「原本对应」的本机应用名。
     * 图标包里的资源名常常是一串无意义的 icon00a1b2c3，手动挑图标时根本认不出来，
     * 有了这张反查表就能在格子里标出「微信 / 支付宝」这类提示。
     */
    fun drawableOwners(ctx: Context, packPkg: String): Map<String, String> {
        if (packPkg.isBlank()) return emptyMap()
        ownerCache[packPkg]?.let { return it }
        val out = HashMap<String, String>()
        runCatching {
            val pm = ctx.packageManager
            val pRes = pm.getResourcesForApplication(packPkg)
            val filter = loadFilter(pm, packPkg)
            DuoState.apps.forEach { app ->
                val short = when {
                    app.activityClass.startsWith(".") -> app.activityClass
                    app.activityClass.startsWith(app.packageName) ->
                        "." + app.activityClass.removePrefix(app.packageName)
                    else -> app.activityClass
                }
                val direct = filter["ComponentInfo{${app.packageName}/$short}"]
                    ?: filter["ComponentInfo{${app.packageName}/${app.activityClass}}"]
                    ?: filter[app.packageName]
                if (direct != null) {
                    if (!out.containsKey(direct)) out[direct] = app.label
                    return@forEach
                }
                for (c in guessNames(app)) {
                    val hit = runCatching {
                        pRes.getIdentifier(c, "drawable", packPkg) != 0
                    }.getOrDefault(false)
                    if (hit) {
                        if (!out.containsKey(c)) out[c] = app.label
                        break
                    }
                }
            }
        }
        ownerCache[packPkg] = out
        return out
    }
}
