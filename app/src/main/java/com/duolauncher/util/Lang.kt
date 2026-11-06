package com.duolauncher.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 应用内语言。
 *
 * - 不指定时完全交给系统：values/（中文）、values-en/、values-ja/ 由资源系统按设备语言自动匹配。
 * - 在设置里手动指定后，用 createConfigurationContext 给 Application / Activity 套一层 Locale，
 *   再 recreate() 重建界面。比 AppCompatDelegate 少一个依赖，minSdk 28 也能用。
 * - [s] 是普通函数，在 Composable、onClick lambda、Service、工具类中都能直接取到当前语言的文案。
 */
object Lang {

    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"
    const val JA = "ja"

    /** 与设置页单选顺序一致，也是 string-array language_options 的顺序 */
    val CHOICES = listOf(SYSTEM, ZH, EN, JA)

    private const val PREF = "duo_prefs_v1"
    private const val KEY = "appLang"
    private const val KEY_REOPEN = "langReopenSettings"

    @Volatile
    private var ctx: Context? = null

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前设置值：system / zh / en / ja */
    fun get(c: Context): String = runCatching {
        prefs(c).getString(KEY, SYSTEM) ?: SYSTEM
    }.getOrDefault(SYSTEM).let { if (it in CHOICES) it else SYSTEM }

    /** 切换语言会 recreate()，标记一下让重建后仍停留在设置页 */
    fun markReopenSettings(c: Context) =
        prefs(c).edit().putBoolean(KEY_REOPEN, true).apply()

    fun consumeReopenSettings(c: Context): Boolean {
        val p = prefs(c)
        val v = p.getBoolean(KEY_REOPEN, false)
        if (v) p.edit().remove(KEY_REOPEN).apply()
        return v
    }

    /** 保存选择并立刻刷新取文案用的 Context */
    fun set(c: Context, tag: String) {
        prefs(c).edit().putString(KEY, tag).apply()
        init(c)
    }

    /** Application.onCreate / Activity.attachBaseContext 时调用 */
    fun init(c: Context) {
        // 用传入 Context 的配置（Activity 上可能带 Android 13+ 的 per-app locale）包一层
        // Application Context：既能取到正确语言，又不会持有 Activity 造成泄漏。
        val app = c.applicationContext ?: c
        val localized = runCatching {
            app.createConfigurationContext(Configuration(c.resources.configuration))
        }.getOrDefault(app)
        ctx = wrap(localized)
    }

    /**
     * 给 Context 套上手动选择的语言。SYSTEM 时原样返回，让系统资源规则生效。
     * 用 createConfigurationContext 而不是改 Resources.updateConfiguration，避免污染全局配置。
     */
    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag == SYSTEM) return base
        return runCatching {
            val locale = when (tag) {
                ZH -> Locale.SIMPLIFIED_CHINESE
                EN -> Locale.ENGLISH
                JA -> Locale.JAPANESE
                else -> return base
            }
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(locale)
            cfg.setLayoutDirection(locale)
            base.createConfigurationContext(cfg)
        }.getOrDefault(base)
    }

    /** 最终生效的语言：跟随系统时按当前资源 locale 推断 */
    val tag: String
        get() {
            val chosen = ctx?.let { get(it) } ?: SYSTEM
            if (chosen != SYSTEM) return chosen
            val sys = ctx?.resources?.configuration?.locales?.get(0)?.language
            return when (sys) {
                "en" -> EN
                "ja" -> JA
                else -> ZH
            }
        }

    fun locale(): Locale = when (tag) {
        EN -> Locale.ENGLISH
        JA -> Locale.JAPANESE
        else -> Locale.SIMPLIFIED_CHINESE
    }

    /** 小组件里的长日期格式，按语言换写法 */
    fun datePattern(): String = when (tag) {
        EN -> "EEEE, MMM d"
        JA -> "M月d日 EEEE"
        else -> "M月d日  EEEE"
    }

    @SuppressLint("StringFormatInvalid")
    fun s(@StringRes id: Int): String =
        ctx?.getString(id) ?: ""

    @SuppressLint("StringFormatInvalid")
    fun s(@StringRes id: Int, vararg args: Any): String =
        ctx?.getString(id, *args) ?: ""
}
