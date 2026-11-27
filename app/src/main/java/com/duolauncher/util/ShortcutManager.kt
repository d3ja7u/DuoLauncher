package com.duolauncher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.duolauncher.R
import com.duolauncher.data.DuoState

/**
 * 系统原生快捷键（Agui ShortcutKey）对接 —— 只读 + 跳转。
 *
 * 绑定写在 Settings.System 的自定义键里（如 `func1_short_press_package`），
 * 这些键不在 Android 允许第三方写的白名单内：只有 system / shell / privileged
 * 应用能写，普通应用即使拿到 WRITE_SECURE_SETTINGS 也会被拒。所以绑定一律交给
 * 系统原生页面，DuoLauncher 只做两件事：
 *
 *  1) 打开系统页；
 *  2) 把系统里已设置的按键读出来展示（点条目可跳回系统页修改）。
 */
object ShortcutManager {
    const val SYSTEM_PACKAGE = "com.agui.shortcutsettings"
    private const val SYSTEM_ENTRY = "com.agui.shortcutsettings.ui.EntryAppActivity"
    const val NOOP_ACTIVITY = "NoOperate"

    /**
     * 从启动器跳过去时要定位到的字母键。
     *
     * 系统页只接受一个无参 Intent，没法指定「打开后停在哪个键」，所以跳转前先把字母记下来，
     * 由无障碍服务在系统页出现后自己滚过去（见 `DuoLockService.revealPendingLetter`）。
     * 超过 [REVEAL_TTL_MS] 自动作废，避免下次从别处打开系统页时莫名滚动。
     */
    @Volatile var pendingRevealLetter: Char? = null

    /**
     * 没有指定字母、但希望打开后直接落到字母键盘那一屏。
     *
     * 系统页顶部是标题 + 一块「已配置 N 颗按键」的设备示意图，键盘图在它下面。
     * 从「打开系统快捷键设置」这类没有具体字母的入口进来时，把键盘图顶上去，
     * 下面直接露出「按键设置」列表，省得每次都要手动滑一下。
     */
    @Volatile var pendingScrollKeyboard = false

    @Volatile var pendingRevealAt: Long = 0L
    const val REVEAL_TTL_MS = 8_000L

    fun hasPendingReveal(): Boolean = pendingRevealLetter != null || pendingScrollKeyboard

    fun clearPendingReveal() {
        pendingRevealLetter = null
        pendingScrollKeyboard = false
    }

    fun hasSystemSupport(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SYSTEM_PACKAGE, 0)
        true
    }.getOrDefault(false)

    // ─────────────────── 「键盘快捷方式」列表页 ───────────────────

    /**
     * com.agui.keyboard 的 MainActivity 就是系统里那张「键盘快捷方式」列表页
     * （`按键A, 未设置` / `按键C, 启动秒表`，上面对应短按 / 长按两个 tab）。
     *
     * 它不带参数启动就直接落在列表页，所以从红框格跳过去最省事：改绑定、删绑定
     * 都是在这一页完成的，比先到键盘图页再点一下更直接。
     */
    const val KEYBOARD_PACKAGE = "com.agui.keyboard"
    private const val KEYBOARD_ENTRY = "com.agui.keyboard.MainActivity"

    /** 跳过去之后要定位到的字母；null 表示不用定位 */
    @Volatile var pendingShortcutLetter: Char? = null

    /**
     * 要切到的 tab：true = 长按，false = 短按。
     * 服务处理完一次就置 null（表示"tab 已对齐"），[hasPendingShortcut] 靠字母维持。
     */
    @Volatile var pendingShortcutLong: Boolean? = null

    @Volatile var pendingShortcutAt: Long = 0L
    const val SHORTCUT_TTL_MS = 8_000L

    fun hasPendingShortcut(): Boolean = pendingShortcutLetter != null || pendingShortcutLong != null

    fun clearPendingShortcut() {
        pendingShortcutLetter = null
        pendingShortcutLong = null
    }

    fun openKeyboardShortcuts(ctx: Context, letter: Char?, long: Boolean): Boolean {
        pendingShortcutLetter = letter
        pendingShortcutLong = long
        pendingShortcutAt = android.os.SystemClock.uptimeMillis()
        val ok = runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(KEYBOARD_PACKAGE, KEYBOARD_ENTRY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (!ok) clearPendingShortcut()
        return ok
    }

    fun openSystemPage(
        ctx: Context,
        letter: Char? = null,
        scrollToKeyboard: Boolean = false
    ): Boolean {
        pendingRevealLetter = letter
        pendingScrollKeyboard = scrollToKeyboard || letter != null
        pendingRevealAt = android.os.SystemClock.uptimeMillis()
        val direct = runCatching {
            ctx.startActivity(
                Intent().setComponent(ComponentName(SYSTEM_PACKAGE, SYSTEM_ENTRY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (direct) return true
        val fallback = ctx.packageManager.getLaunchIntentForPackage(SYSTEM_PACKAGE) ?: return false
        return runCatching {
            ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    // ─────────────────── 读取系统当前绑定（只读） ───────────────────

    data class KeyDef(val id: String, val keycode: Int, val labelRes: Int)

    /** 当前机型系统 App 支持的 11 个可编程按键 */
    val KEYS = listOf(
        KeyDef("func1_", 401, R.string.shortcut_key_func1),
        KeyDef("func2_", 402, R.string.shortcut_key_func2),
        KeyDef("alt_l_", 57, R.string.shortcut_key_alt_l),
        KeyDef("alt_r_", 58, R.string.shortcut_key_alt_r),
        KeyDef("sym_", 63, R.string.shortcut_key_sym),
        KeyDef("shift_l_", 59, R.string.shortcut_key_shift_l),
        KeyDef("shift_r_", 60, R.string.shortcut_key_shift_r),
        KeyDef("home_", 3, R.string.shortcut_key_home),
        KeyDef("back_", 4, R.string.shortcut_key_back),
        KeyDef("recent_", 187, R.string.shortcut_key_recent),
        KeyDef("fn_", 403, R.string.shortcut_key_fn)
    )

    enum class Press(val setting: String, val labelRes: Int) {
        SHORT("short_press", R.string.shortcut_short_press),
        LONG("long_press", R.string.shortcut_long_press),
        DOUBLE("double_press", R.string.shortcut_double_press)
    }

    data class Binding(val pkg: String, val activity: String) {
        val isNoop: Boolean
            get() = pkg.isBlank() || (pkg == SYSTEM_PACKAGE && activity == NOOP_ACTIVITY)

        fun label(ctx: Context): String {
            if (pkg == SYSTEM_PACKAGE) {
                if (activity == NOOP_ACTIVITY) return Lang.s(R.string.shortcut_none)
                return Lang.s(R.string.shortcut_system_function, activity.removePrefix("Shortcut_function_"))
            }
            // 优先用启动器已枚举到的应用名（含分身）
            DuoState.apps.firstOrNull { it.packageName == pkg && it.activityClass == activity }
                ?.let { return it.label }
            DuoState.appOf(pkg)?.let { return it.label }
            return runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    }

    fun read(ctx: Context, key: KeyDef, press: Press): Binding? {
        val pkg = runCatching {
            Settings.System.getString(ctx.contentResolver, key.id + press.setting + "_package")
        }.getOrNull() ?: return null
        if (pkg.isBlank()) return null
        val activity = runCatching {
            Settings.System.getString(ctx.contentResolver, key.id + press.setting + "_activity") ?: ""
        }.getOrNull() ?: ""
        return Binding(pkg, activity)
    }

    /** 系统里已生效的绑定（按键 / 触发方式 / 目标），只用于展示 */
    fun configured(ctx: Context): List<Triple<KeyDef, Press, Binding>> =
        KEYS.flatMap { key ->
            Press.entries.mapNotNull { press ->
                read(ctx, key, press)?.takeIf { !it.isNoop }?.let { Triple(key, press, it) }
            }
        }
}
