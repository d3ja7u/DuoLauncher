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

    fun hasSystemSupport(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SYSTEM_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun openSystemPage(ctx: Context): Boolean {
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
