package com.duolauncher.util

import android.view.accessibility.AccessibilityNodeInfo
import com.duolauncher.data.DuoState

/**
 * 系统原生「字母键」快捷方式的只读抓取。
 *
 * Titan 2 把字母键绑定写在 com.agui.keyboard 的私有 SharedPreferences 里，
 * 提供器虽然导出，但要求 signature 级权限，第三方应用读不到。这里不碰那份数据，
 * 只在用户打开系统快捷键页时，从页面上的键盘图里读「Q，长按 QQ」这类描述，
 * 缓存到 [DuoState.systemLetterShortcuts] 供设置页展示。
 *
 * 抓取只在用户主动进入系统页时发生，不用常驻屏幕内容监听。
 */
object SystemLetterShortcuts {
    data class Item(val letter: String, val press: String, val title: String)

    private val LETTER_LINE = Regex("^([A-Z])[，,]\\s*(.+)$")

    fun key(letter: String, press: String): String = "$letter/$press"

    /** 把一条无障碍描述解析成 0～2 条绑定；非字母键一律忽略 */
    fun parse(text: String?): List<Item> {
        if (text.isNullOrBlank()) return emptyList()
        val match = LETTER_LINE.find(text.trim()) ?: return emptyList()
        val letter = match.groupValues[1]
        val body = match.groupValues[2].trim()
        if (body.isEmpty() || body.contains("未设置") || body.contains("Not set", true)) return emptyList()

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
        return out
    }

    /** 遍历当前窗口，缓存所有识别到的字母绑定；返回条目数，0 表示这次没读到 */
    fun capture(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0
        val found = ArrayList<Item>()
        collect(root, found, 0)
        if (found.isEmpty()) return 0
        DuoState.systemLetterShortcuts.clear()
        found.forEach { DuoState.systemLetterShortcuts[key(it.letter, it.press)] = it.title }
        DuoState.save()
        return found.size
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (node == null || depth > 12) return
        val text = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out += parse(text)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }
}
