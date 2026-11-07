package com.duolauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 纯「锁屏」用途的无障碍服务。
 *
 * 只调用 [performGlobalAction] 的 GLOBAL_ACTION_LOCK_SCREEN，不读取窗口内容
 * （accessibility-service 里 canRetrieveWindowContent=false、不监听按键），
 * 因此系统权限页对它的描述是最小的一组。
 *
 * 之所以走无障碍而不是设备管理员：DPM.lockNow 需要用户把整个应用设为「设备管理员」，
 * 权限面过大（含擦除数据等），而 GLOBAL_ACTION_LOCK_SCREEN 只要求无障碍开关。
 */
class DuoLockService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: DuoLockService? = null

        /** 服务进程已连上（可直接调用全局操作） */
        fun isRunning(): Boolean = instance != null

        /** 用户在系统设置里已开启本服务（重启进程后 instance 可能暂时为空） */
        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx.applicationContext, DuoLockService::class.java)
            val flat = expected.flattenToString()
            val short = expected.flattenToShortString()
            val enabled = runCatching {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            return enabled.split(':').any {
                it.equals(flat, true) || it.equals(short, true) ||
                    it.endsWith("/${DuoLockService::class.java.simpleName}")
            }
        }

        /** 息屏；返回 false 表示服务尚未连上 */
        fun lockScreen(): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }.getOrDefault(false)
        }

        /** 下拉通知栏；返回 false 表示服务尚未连上 */
        fun openNotifications(): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            }.getOrDefault(false)
        }

        /** 展开快捷开关 / 控制中心；返回 false 表示服务尚未连上 */
        fun openQuickSettings(): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }.getOrDefault(false)
        }
    }
}
