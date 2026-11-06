package com.duolauncher.util

import android.content.Context
import android.util.Log
import com.duolauncher.service.DuoLockService

/**
 * 呼出系统「通知栏 / 控制中心」。
 *
 * 两条路径，先走不需要用户授权的，再用无障碍兜底：
 *
 *  1. **StatusBarManager（首选，零配置）**
 *     系统服务 `statusbar` 上的 [expandNotificationsPanel] / [expandSettingsPanel] 是
 *     @hide 方法，需要 `android.permission.EXPAND_STATUS_BAR`。该权限在多数 ROM 上
 *     的保护级别是 `normal`（本项目已在清单里声明，安装即授予），所以反射调用通常可用，
 *     不需要用户去开无障碍。个别 ROM 把方法删了或加了签名校验，会抛异常 → 返回 false。
 *
 *  2. **无障碍 GLOBAL_ACTION_NOTIFICATIONS / GLOBAL_ACTION_QUICK_SETTINGS（兜底）**
 *     复用双击熄屏那个最小的 [DuoLockService]（只调 performGlobalAction、不读窗口内容），
 *     用户开了无障碍就一定可用。
 *
 * 两者都失败时返回 false，由调用方提示用户去开无障碍。
 */
object SystemPanel {

    private const val STATUS_BAR_SERVICE = "statusbar"
    private const val TAG = "DuoPanel"

    /** 展开通知栏（通知列表在前，快捷开关可继续下拉展开） */
    fun openNotifications(ctx: Context): Boolean {
        if (DuoLockService.isRunning()) {
            // 无障碍已连上：直接用全局操作，稳且没有 hiddenapi 噪音
            if (DuoLockService.openNotifications()) { Log.d(TAG, "notifications via a11y"); return true }
        }
        if (reflect(ctx, "expandNotificationsPanel", arg = false)) {
            Log.d(TAG, "notifications via statusbar"); return true
        }
        val ok = DuoLockService.openNotifications()
        Log.d(TAG, "notifications fallback a11y=$ok")
        return ok
    }

    /**
     * 展开控制中心。
     *
     * 先试 `expandSettingsPanel(String hint)`：Android 12+ 上它是「把快捷开关拉开一档」，
     * 通知列表仍在下面 —— 即用户要的「先出面板、还能继续往下拉」。
     * 没有这个方法的老实现就退回无参版本，最后退到无障碍。
     */
    fun openQuickSettings(ctx: Context): Boolean {
        if (DuoLockService.isRunning()) {
            if (DuoLockService.openQuickSettings()) { Log.d(TAG, "qs via a11y"); return true }
        }
        if (reflect(ctx, "expandSettingsPanel", arg = true)) {
            Log.d(TAG, "qs via statusbar"); return true
        }
        val ok = DuoLockService.openQuickSettings()
        Log.d(TAG, "qs fallback a11y=$ok")
        return ok
    }

    /** 手势是否可用（用于设置页文案；false 时手势仍会尝试，只是可能弹提示） */
    fun isAvailable(ctx: Context): Boolean =
        statusBar(ctx) != null || DuoLockService.isRunning() || DuoLockService.isEnabled(ctx)

    private fun statusBar(ctx: Context): Any? = runCatching {
        ctx.getSystemService(STATUS_BAR_SERVICE)
    }.getOrNull()

    private fun reflect(ctx: Context, method: String, arg: Boolean): Boolean {
        val svc = statusBar(ctx) ?: return false
        val cls = svc.javaClass
        // 带 hint 参数的重载（新版才有），hint 传 null 表示不指定
        if (arg) {
            runCatching {
                cls.getMethod(method, String::class.java).invoke(svc, null as String?)
            }.onSuccess { return true }.onFailure { Log.d(TAG, "$method(String) 失败: ${it.javaClass.simpleName}") }
        }
        // 无参重载
        runCatching {
            cls.getMethod(method).invoke(svc)
        }.onSuccess { return true }.onFailure { Log.d(TAG, "$method() 失败: ${it.javaClass.simpleName}") }
        return false
    }
}
