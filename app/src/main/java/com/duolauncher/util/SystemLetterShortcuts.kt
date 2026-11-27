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
    data class Item(val letter: String, val press: String, val title: String)

    /** 键盘图上的一个字母格：`A，长按 安大师` / `A，未设置` */
    private val LETTER_LINE = Regex("^([A-Z])[，,]\\s*(.+)$")

    /**
     * 「键盘快捷方式」列表页的条目：`按键C, 启动秒表` / `按键A, 未设置`。
     *
     * 这一页按短按 / 长按分两个 tab，但 tab 的选中态是自绘的，无障碍读不到 selected，
     * 所以解析时必须由调用方给一个 press 提示（见 [capture] 的 listPress 参数）。
     */
    private val LIST_LINE = Regex("^按键([A-Z])[，,]\\s*(.+)$")

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
    fun capture(roots: List<AccessibilityNodeInfo?>, listPress: String? = null): Int {
        if (roots.isEmpty()) return 0
        val cells = LinkedHashMap<String, List<Item>>()
        roots.forEach { r -> r?.let { collect(it, cells, 0, listPress) } }
        if (cells.isEmpty()) return 0

        // 逐字母覆盖：页面里出现过的字母以页面为准（未设置就把旧缓存清掉），
        // 这一屏没出现的字母保持原样，避免页面还没铺完时把别的字母弄丢。
        val before = HashMap(DuoState.systemLetterShortcuts)
        cells.forEach { (letter, items) ->
            DuoState.systemLetterShortcuts.keys
                .filter { it.substringBefore('/') == letter }
                .forEach { DuoState.systemLetterShortcuts.remove(it) }
            items.forEach { DuoState.systemLetterShortcuts[key(it.letter, it.press)] = it.title }
        }
        // 内容没变就不写盘：一次改动会触发好几轮补读，别每轮都落一次 JSON
        if (before != HashMap(DuoState.systemLetterShortcuts)) DuoState.save()
        return cells.size
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableMap<String, List<Item>>,
        depth: Int,
        listPress: String?
    ) {
        if (node == null || depth > 12) return
        val text = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) {
            val parsed = cell(text) ?: listPress?.let { listCell(text, it) }
            parsed?.let { (letter, items) ->
                // 同一字母出现两次时，取有条目的那次，别用空列表把结果盖掉
                val old = out[letter]
                if (old.isNullOrEmpty()) out[letter] = items
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1, listPress)
    }
}
