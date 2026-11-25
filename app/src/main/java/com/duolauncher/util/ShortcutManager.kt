package com.duolauncher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.duolauncher.R
import com.duolauncher.data.DuoState

/**
 * 系统快捷键（Agui ShortcutKey）对接 —— 只读，不写入。
 *
 * 为什么不自己写绑定：绑定存在 Settings.System 的自定义键里（如
 * `func1_short_press_package`），这类键不在 Android 允许第三方应用写的白名单内。
 * AOSP `SettingsProvider.enforceRestrictedSystemSettingsMutationForCallingPackage()`
 * 只对 system / shell / root 以及 privileged（装在 priv-app 的）应用放行，
 * 普通应用即使拿到 WRITE_SECURE_SETTINGS 也会抛
 * 「You cannot keep your settings in the secure settings」。所以绑定一律交给系统原生页面去写。
 *
 * DuoLauncher 只做三件事：
 *  1) 在清单里声明 6 个桌面入口「分身 1」…「分身 6」（activity-alias，默认禁用），
 *     系统原生页面的应用列表按 LAUNCHER activity 枚举，因此能直接选到它们；
 *     只在进系统页前临时放出，配置完回桌面立刻收起（见「入口可见性」一节）；
 *  2) 记住「分身 N」对应哪个分身应用（[DuoState.cloneSlots]）；
 *  3) 按键触发后进 [com.duolauncher.ShortcutProxyActivity]，再按槽位打开真正的分身应用。
 */
object ShortcutManager {

    /** 系统原生快捷键 App */
    const val SYSTEM_PACKAGE = "com.agui.shortcutsettings"
    private const val SYSTEM_ENTRY = "com.agui.shortcutsettings.ui.EntryAppActivity"
    const val NOOP_ACTIVITY = "NoOperate"

    /** 槽位数量，必须与 AndroidManifest 里的 ShortcutProxySlot1..N 一致 */
    const val SLOT_COUNT = 6
    private const val ALIAS_PREFIX = "ShortcutProxySlot"

    /** 本机是否有系统原生快捷键能力：没有的话这套设置整块都不该出现 */
    fun hasSystemSupport(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SYSTEM_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 打开系统原生快捷键页。
     *
     * 按键绑定的界面本来就由系统提供（含字母键、快捷功能、导入导出），
     * 没必要在启动器里重做一套，直接跳过去体验最一致。
     */
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

    // ───────────────────────── 槽位 ─────────────────────────

    fun slotAlias(slot: Int): String = ALIAS_PREFIX + slot

    fun slotOfAlias(name: String?): Int? {
        val tail = name.orEmpty().substringAfterLast('.')
        if (!tail.startsWith(ALIAS_PREFIX)) return null
        return tail.removePrefix(ALIAS_PREFIX).toIntOrNull()?.takeIf { it in 1..SLOT_COUNT }
    }

    fun appIdOfSlot(slot: Int): String? = DuoState.cloneSlots[slot.toString()]

    /** 第一个空槽位；都占满返回 null */
    fun freeSlot(): Int? = (1..SLOT_COUNT).firstOrNull { appIdOfSlot(it) == null }

    /** 这个分身应用当前占用的槽位（同一个应用可以只占一个槽） */
    fun slotOfApp(appId: String): Int? =
        (1..SLOT_COUNT).firstOrNull { appIdOfSlot(it) == appId }

    fun assign(ctx: Context, slot: Int, appId: String) {
        // 同一应用换槽时先清掉旧槽，避免两个入口指向同一个分身
        (1..SLOT_COUNT).forEach { n ->
            if (n != slot && appIdOfSlot(n) == appId) DuoState.cloneSlots.remove(n.toString())
        }
        DuoState.cloneSlots[slot.toString()] = appId
        DuoState.save()
        // 指派完立刻把入口放出来：系统按键随时可能触发它，不能等到下次重启
        syncToBound(ctx)
    }

    fun clearSlot(ctx: Context, slot: Int) {
        DuoState.cloneSlots.remove(slot.toString())
        DuoState.save()
        syncToBound(ctx)
    }

    // ─────────────────── 入口可见性（不污染启动器） ───────────────────
    /**
     * 系统快捷键页的应用列表是按「桌面入口（MAIN + LAUNCHER）」枚举的，所以
     * 「分身 N」必须声明成桌面入口才选得到；但它平时不该出现在启动器抽屉里。
     *
     * 做法：
     *  · 清单里默认 `android:enabled="false"`，平时完全不出现；
     *  · 从本页点「打开系统快捷键设置」前临时把 6 个全部放出，配置时才选得到；
     *  · 回到 app 后只保留系统里真的被某个按键引用到的槽位，其余立刻收起。
     *
     * 已被按键引用的入口必须保持启用 —— 禁用后系统按键 startActivity 会直接失败。
     */
    private var lastVisible: Set<Int>? = null

    private fun aliasOf(ctx: Context, slot: Int) =
        ComponentName(ctx.packageName, ctx.packageName + "." + slotAlias(slot))

    fun isSlotEnabled(ctx: Context, slot: Int): Boolean = runCatching {
        ctx.packageManager.getComponentEnabledSetting(aliasOf(ctx, slot)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** 系统里真的被某个按键 / 手势引用到的槽位 */
    fun boundSlots(ctx: Context): Set<Int> =
        configured(ctx).mapNotNull { it.third.slotFor(ctx) }.toSet()

    /**
     * 已经指派了分身应用的槽位。
     *
     * 这些入口必须一直保持启用 —— 字母键的绑定根本读不到：本机把「长按 A」这类
     * 配置存在键盘 App 自己的 SharedPreferences（`com.agui.keyboard/shared_prefs/
     * agui_keyboard_shortcut.xml` 的 `A-long-app`），属于别人的私有目录，第三方读不了。
     * 早先只按 [boundSlots] 决定可见性，结果回桌面就把 Slot1 收掉，
     * 系统按键 startActivity 时组件已禁用 → 长按 A 完全没反应。
     */
    /**
     * 直接从配置文件里读已指派的槽位。
     *
     * 进程刚起来时 [DuoState] 可能还没 load 完，读内存会拿到空集合，
     * 反过来把刚刚放出来的入口又禁掉（长按 A 就彻底没戏）。所以启动兜底走磁盘。
     */
    private fun assignedSlotsFromDisk(ctx: Context): Set<Int> = runCatching {
        val raw = ctx.getSharedPreferences("duo_prefs_v1", Context.MODE_PRIVATE)
            .getString("state", null) ?: return@runCatching emptySet<Int>()
        val obj = org.json.JSONObject(raw).optJSONObject("cloneSlots")
            ?: return@runCatching emptySet<Int>()
        obj.keys().asSequence().mapNotNull { it.toIntOrNull() }.toSet()
    }.getOrDefault(emptySet())

    /** 已指派分身应用的槽位（内存 + 磁盘，后者兜住启动阶段的空档） */
    fun assignedSlots(ctx: Context): Set<Int> =
        DuoState.cloneSlots.keys.mapNotNull { it.toIntOrNull() }.toSet() + assignedSlotsFromDisk(ctx)

    /** 需要保持可见的槽位：被按键引用的 + 已指派的 */
    fun keepSlots(ctx: Context): Set<Int> = boundSlots(ctx) + assignedSlots(ctx)

    // 进程启动时的 syncToBound 在后台线程跑，UI 侧也可能在进/出系统页时调用，
    // 这里串行化，避免 lastVisible 缓存与实际组件状态对不上。
    @Synchronized
    private fun applyVisible(ctx: Context, visible: Set<Int>, force: Boolean = false) {
        if (!force && lastVisible == visible) return
        val pm = ctx.packageManager
        for (slot in 1..SLOT_COUNT) {
            val want = if (slot in visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (runCatching { pm.getComponentEnabledSetting(aliasOf(ctx, slot)) }.getOrNull() == want) continue
            runCatching {
                pm.setComponentEnabledSetting(aliasOf(ctx, slot), want, PackageManager.DONT_KILL_APP)
            }
        }
        lastVisible = visible
    }

    /** 收缩到「被按键引用 + 已指派应用」的槽位：app 启动、指派变更、回桌面时调用 */
    fun syncToBound(ctx: Context) = applyVisible(ctx, keepSlots(ctx))

    /** 放了全部入口、还没收回（用户正在系统页里配置） */
    @Volatile
    var pendingCollapse = false
        private set

    /** 进系统页前临时放出全部入口 */
    fun showAllSlots(ctx: Context) {
        applyVisible(ctx, (1..SLOT_COUNT).toSet())
        pendingCollapse = true
    }

    /**
     * 回到桌面 / 设置页时收一次。
     *
     * 用户很可能在系统页配完直接按 Home 回桌面，这时必须由桌面把多出来的入口收掉；
     * 真正被按键引用的槽位照样保留。
     */
    fun collapseIfPending(ctx: Context) {
        if (!pendingCollapse) return
        pendingCollapse = false
        syncToBound(ctx)
    }

    // ─────────────────── 读取系统当前绑定（只读） ───────────────────

    data class KeyDef(val id: String, val keycode: Int, val labelRes: Int)

    /** 当前机型（Titan 2 / g72）系统 App 支持的 11 个可编程按键 */
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

        /** 指向 DuoLauncher 的「分身 N」入口时返回槽位号 */
        fun slotFor(ctx: Context): Int? =
            if (pkg == ctx.packageName) ShortcutManager.slotOfAlias(activity) else null

        fun label(ctx: Context): String {
            slotFor(ctx)?.let { n ->
                val app = appIdOfSlot(n)?.let { DuoState.appOf(it) }
                return Lang.s(R.string.clone_slot_n, n.toString()) + " · " +
                    (app?.label ?: Lang.s(R.string.clone_slot_no_app))
            }
            if (pkg == SYSTEM_PACKAGE) {
                if (activity == NOOP_ACTIVITY) return Lang.s(R.string.shortcut_none)
                return Lang.s(R.string.shortcut_system_function, activity.removePrefix("Shortcut_function_"))
            }
            DuoState.apps.firstOrNull { it.packageName == pkg && it.activityClass == activity }
                ?.let { return it.label }
            return DuoState.appOf(pkg)?.label ?: pkg
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

    /** 当前系统里已生效的绑定（按键 / 触发方式 / 目标），只用于展示 */
    fun configured(ctx: Context): List<Triple<KeyDef, Press, Binding>> =
        KEYS.flatMap { key ->
            Press.entries.mapNotNull { press ->
                read(ctx, key, press)?.takeIf { !it.isNoop }?.let { Triple(key, press, it) }
            }
        }
}
