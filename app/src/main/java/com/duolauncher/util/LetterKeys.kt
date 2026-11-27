package com.duolauncher.util

import android.view.KeyEvent
import com.duolauncher.data.DuoState

/**
 * 字母键短按 / 长按 → 启动应用或执行系统动作。
 *
 * Titan 2 自带按键设置覆盖功能键 / 侧键；DuoLauncher 这一套把字母键的短按和长按
 * 分别绑到应用或动作。实际拦截在 [com.duolauncher.service.DuoLockService.onKeyEvent]：
 *
 *  · 只在桌面（[DuoState.homeForeground]）且不在输入框（[DuoState.textInputActive]）生效；
 *  · 按下到松手不足 [HOLD_MS] 走短按，超过走长按；两档独立绑定，互不影响；
 *  · 松手时才执行并拦下事件，避免打字误触。
 */
object LetterKeys {
    enum class Press { SHORT, LONG }

    /**
     * 可绑定的键：只有 26 个字母。
     *
     * 顶部数字行去掉了——全键盘手机上数字一般要和 Alt / Fn 组合才打得出来，
     * 单按数字键在桌面基本不会发生，绑了也用不上（齐老师 2026-11-27 反馈）。
     */
    val CODES: List<Int> = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).toList()

    /** 长按判定阈值；键盘的按键重复通常从 500ms 起，这里取稍大一点 */
    const val HOLD_MS = 520L

    fun isBindable(code: Int): Boolean = code in CODES

    fun label(code: Int): String = when {
        code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            ('A'.code + (code - KeyEvent.KEYCODE_A)).toChar().toString()

        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            ('0'.code + (code - KeyEvent.KEYCODE_0)).toChar().toString()

        else -> ""
    }

    fun mapOf(press: Press) =
        if (press == Press.SHORT) DuoState.keyLetterShort else DuoState.keyLetterApps

    fun targetOf(code: Int, press: Press): String? = mapOf(press)[code.toString()]

    fun assign(code: Int, press: Press, target: String) {
        mapOf(press)[code.toString()] = target
        DuoState.save()
    }

    fun clear(code: Int, press: Press) {
        mapOf(press).remove(code.toString())
        DuoState.save()
    }
}
