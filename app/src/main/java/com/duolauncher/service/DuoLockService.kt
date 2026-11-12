package com.duolauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

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

        /**
         * 用户在系统设置里已开启本服务。
         *
         * 两路判据取 OR，因为不同 ROM 的实现不一致：
         *  ① AccessibilityManager 的「已启用服务列表」——系统真正认为生效的那份；
         *  ② Settings.Secure 的 enabled_accessibility_services 字符串——部分国产 ROM
         *     不回填 ① 的列表，只能读这个。
         * 只靠 ② 时，ROM 把状态存在自有数据库 / 字符串返回 null 就会误报「未开启」，
         * 用户看到的就是「明明开了，过一会儿又显示没开」。
         */
        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx.applicationContext, DuoLockService::class.java)
            val flat = expected.flattenToString()
            val short = expected.flattenToShortString()
            val simple = DuoLockService::class.java.simpleName
            runCatching {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                if (list.any { info ->
                        info.id == flat || info.id == short ||
                            info.resolveInfo?.serviceInfo?.let {
                                it.packageName == ctx.packageName && it.name == DuoLockService::class.java.name
                            } == true
                    }) return true
            }
            val enabled = runCatching {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            return enabled.split(':', ';').any {
                val v = it.trim()
                v.equals(flat, true) || v.equals(short, true) ||
                    v.endsWith("/$simple") || v.endsWith(".$simple")
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
