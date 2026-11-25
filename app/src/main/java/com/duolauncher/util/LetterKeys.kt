package com.duolauncher.util

import android.view.KeyEvent
import com.duolauncher.data.DuoState

/**
 * 字母键长按 → 启动应用。
 *
 * 有些全键盘机（不带 Unihertz 那套系统快捷键页的机器）没有可编程按键入口，
 * 但物理键盘的每个字母键都会以 KeyEvent 形式送进系统，无障碍服务可以拦到。
 * 这里把「哪个字母键长按打开哪个应用」存成一张表，实际的拦截在
 * [com.duolauncher.service.DuoLockService.onKeyEvent] 里做：
 *
 *  · 只在桌面（[DuoState.homeForeground]）生效，别处的字母照常输入，不干扰打字；
 *  · 按下到松手不足 [HOLD_MS] 视为普通点击，直接放行；
 *  · 长按到阈值松手才启动，拦下该次事件，避免桌面收到无意义的按键。
 */
object LetterKeys {

    /** 可绑定的键：26 个字母 + 顶部数字行 */
    val CODES: List<Int> =
        (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).toList() +
            (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).toList()

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

    fun appIdOf(code: Int): String? = DuoState.keyLetterApps[code.toString()]

    fun assign(code: Int, appId: String) {
        DuoState.keyLetterApps[code.toString()] = appId
        DuoState.save()
    }

    fun clear(code: Int) {
        DuoState.keyLetterApps.remove(code.toString())
        DuoState.save()
    }
}
