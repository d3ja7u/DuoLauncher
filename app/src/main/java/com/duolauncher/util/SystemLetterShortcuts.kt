package com.duolauncher.util

import android.view.accessibility.AccessibilityNodeInfo
import com.duolauncher.data.DuoState

/**
 * 系统原生「字母键」快捷方式的只读抓取。
 *
 * Titan 2 把字母键绑定写在 com.agui.keyboard 的私有数据里，提供器虽然导出，
 * 但要求 signature 级权限（READ_LETTER_SHORTCUTS），第三方应用读不到。这里不碰那份数据，
 * 改为从系统快捷键页键盘图上的「A，长按 安大师」这类无障碍描述里读，
 * 缓存到 [DuoState.systemLetterShortcuts] 供设置页展示。
 *
 * 抓取时机：无障碍服务常驻只监听 com.agui.shortcutsettings / com.agui.keyboard 两个包的
 * 窗口事件（包过滤在系统侧完成，平时不会有唤醒），所以只要用户在系统页里改了绑定——
 * 不管是从启动器点进去，还是从系统设置进去——缓存都会自动更新，不需要额外操作。
 */
object SystemLetterShortcuts {
    private const val SYSTEM_PACKAGE_NAME = "com.agui.shortcutsettings"
    private const val KEYBOARD_PACKAGE_NAME = "com.agui.keyboard"

    data class Item(val letter: String, val press: String, val title: String)

    /** 键盘图上的一个字母格：`A，长按 安大师` / `A，未设置` */
    private val LETTER_LINE = Regex("^([A-Z])[，,]\\s*(.*)$")

    /**
     * 「键盘快捷方式」列表页的条目：`按键C, 启动秒表` / `按键A, 未设置`。
     *
     * 这一页按短按 / 长按分两个 tab，但 tab 的选中态是自绘的，无障碍读不到 selected，
     * 所以解析时必须由调用方给一个 press 提示（见 [capture] 的 listPress 参数）。
     */
    private val LIST_LINE = Regex("^按键([A-Z])[，,]\\s*(.*)$")

    fun key(letter: String, press: String): String = "$letter/$press"

    /** 把一条无障碍描述解析成 0～2 条绑定；非字母键一律忽略 */
    fun parse(text: String?): List<Item> {
        val cell = cell(text) ?: return emptyList()
        return cell.second
    }

    /**
     * 解析一个字母格。
     *
     * 返回的 second 为空列表表示「该字母在页面里出现但未设置」——这个信息很重要：
     * 用它可以把该字母的历史缓存清掉，否则用户删掉绑定后旧条目会一直留着。
     */
    fun cell(text: String?): Pair<String, List<Item>>? {
        if (text.isNullOrBlank()) return null
        val match = LETTER_LINE.find(text.trim()) ?: return null
        val letter = match.groupValues[1]
        val body = match.groupValues[2].trim()
        if (body.isEmpty() || body.contains("未设置") || body.contains("Not set", true)) {
            return letter to emptyList()
        }

        val out = ArrayList<Item>(2)
        body.split('·').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seg ->
            val (press, title) = when {
                seg.startsWith("短按") -> "short" to seg.removePrefix("短按").trim()
                seg.startsWith("长按") -> "long" to seg.removePrefix("长按").trim()
                seg.startsWith("双击") -> return@forEach // 系统字母键只有短按 / 长按
                seg.startsWith("Short", true) -> "short" to seg.removePrefix("Short").trim().trimStart('-', ' ', ':', '：')
                seg.startsWith("Long", true) -> "long" to seg.removePrefix("Long").trim().trimStart('-', ' ', ':', '：')
                else -> "long" to seg // 系统页多数只显示「长按 应用名」
            }
            if (title.isNotEmpty()) out += Item(letter, press, title)
        }
        return letter to out
    }

    /** 列表页条目 → 一条绑定；[press] 由调用方给出（当前停留在哪个 tab） */
    fun listCell(text: String?, press: String): Pair<String, List<Item>>? {
        if (text.isNullOrBlank()) return null
        val match = LIST_LINE.find(text.trim()) ?: return null
        val letter = match.groupValues[1]
        val body = match.groupValues[2].trim()
        if (body.isEmpty() || body.contains("未设置") || body.contains("Not set", true)) {
            return letter to emptyList()
        }
        return letter to listOf(Item(letter, press, body))
    }

    /**
     * 遍历若干窗口根节点，按字母合并缓存；返回识别到的字母数，0 表示这次没读到。
     *
     * 传入多个根节点是必要的：用户删完键往往立刻按返回，等去抖结束活动窗口已经是启动器了，
     * 但系统页窗口在返回动画期间还在 `windows` 列表里，多扫一遍才不会漏掉这次改动。
     *
     * [listPress] 非空时，额外解析 `com.agui.keyboard` 列表页的 `按键C, 启动秒表` 格式，
     * 并把条目归到该触发方式下——列表页的短按/长按 tab 选中态读不到，只能靠"刚才是谁点的"。
     */
    fun capture(
        roots: List<AccessibilityNodeInfo?>,
        listPress: String? = null,
        listPressCertain: Boolean = true
    ): Int {
        if (roots.isEmpty()) return 0
        // 值里带上"这批数据的来源档位"：null = 键盘图（一格同时代表短按/长按两档，可整体覆盖）；
        // "short"/"long" = 列表页（只代表当前 tab 这一档，绝不能拿它去清另一档）。
        val cells = LinkedHashMap<String, Pair<List<Item>, String?>>()
        roots.forEach { r -> r?.let { collect(it, cells, 0, listPress) } }
        if (cells.isEmpty()) return 0

        val before = HashMap(DuoState.systemLetterShortcuts)
        val map = DuoState.systemLetterShortcuts
        cells.forEach { (letter, hit) ->
            val (items, srcPress) = hit
            val inferred = srcPress != null && !listPressCertain
            // 档位靠推断时，"读到空"不能当成删除：这一页看不到另一档，空值可能只是当前 tab 没配，
            // 拿它去清会把另一档真实存在的绑定抹掉
            if (inferred && items.isEmpty()) return@forEach
            // 列表页的 tab 选中态读不到：档位是用户点过 tab 才知道的，没人点就只能按缓存推断
            val press = if (inferred) inferPress(letter, items) else srcPress
            if (press == null) {
                // 键盘图：页面上这一格就是该字母的完整状态，未设置时连旧条目一起清掉
                map.keys.filter { it.substringBefore('/') == letter }.forEach { map.remove(it) }
            } else {
                // 列表页：只覆盖这一档，另一档原样保留
                map.remove(key(letter, press))
            }
            items.forEach { item ->
                // 列表页用推断/确认出来的档位；键盘图的 item 自带档位
                val targetPress = if (srcPress != null) press!! else item.press
                map[key(item.letter, targetPress)] = item.title
            }
        }
        // 内容没变就不写盘：一次改动会触发好几轮补读，别每轮都落一次 JSON
        if (before != HashMap(DuoState.systemLetterShortcuts)) DuoState.save()
        return cells.size
    }

    /**
     * 列表页没被告知停在哪个 tab 时，按已有缓存推断这一条属于哪一档。
     *
     * 系统这一页每次打开都停在「长按」（实测不记忆），所以：
     *  · 该字母长按已有绑定 → 当成长按（用户在长按页改绑定是最常见的操作）；
     *  · 只有短按有绑定 → 当成短按；
     *  · 两档都没有（新增绑定）→ 按默认的长按页算。
     * 从启动器点格子进来时档位是明确的，不走这里。
     */
    private fun inferPress(letter: String, items: List<Item>): String {
        val m = DuoState.systemLetterShortcuts
        val longV = m[key(letter, "long")]
        val shortV = m[key(letter, "short")]
        val title = items.firstOrNull()?.title
        return when {
            // 读到的内容只跟短按那一档对得上 → 用户就在短按页
            title != null && shortV != null && shortV == title && longV != title -> "short"
            // 其余（新绑定、和长按一致、两档都空）都按系统默认的长按页算
            else -> "long"
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableMap<String, Pair<List<Item>, String?>>,
        depth: Int,
        listPress: String?
    ) {
        if (node == null || depth > 12) return
        // 只认系统快捷键页自己的节点：rootInActiveWindow 有时已经是启动器（用户改完立刻返回），
        // 启动器按键格上的「A」这类文本会被误当成"这个字母未设置"，把缓存整批清掉
        val pkg = runCatching { node.packageName?.toString() }.getOrNull()
        if (pkg != null && pkg != KEYBOARD_PACKAGE_NAME && pkg != SYSTEM_PACKAGE_NAME) return
        // 「键盘快捷方式」页是 ViewPager：切到短按后，长按那一页的节点仍留在窗口树里。
        // 不过滤掉不可见的页，就会把长按页的「按键A, 计算器」也算到短按档上（2026-11-28 实测踩到）。
        if (depth > 0 && !node.isVisibleToUser) return
        val text = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) {
            // 键盘图的格子优先（带明确档位），列表页只作为兜底
            val grid = cell(text)
            val parsed: Pair<String, List<Item>>? =
                grid ?: listPress?.let { listCell(text, it) }
            if (parsed != null) {
                val press = if (grid != null) null else listPress
                val (letter, items) = parsed
                val old = out[letter]
                // 键盘图的结果压过列表页；同一来源时，有内容的那次压过空的那次
                val better = old == null ||
                    (old.second != null && press == null) ||
                    (old.first.isEmpty() && items.isNotEmpty())
                if (better) out[letter] = items to press
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1, listPress)
    }
}
