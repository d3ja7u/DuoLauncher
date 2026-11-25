package com.duolauncher.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.duolauncher.service.DuoLockService

/**
 * 息屏能力：只走无障碍服务 GLOBAL_ACTION_LOCK_SCREEN（API 28+）。
 *
 * 注意：应用每次被覆盖安装（含应用内更新）系统都会杀掉进程，
 * Android 会把当时仍绑定的无障碍服务记进「Crashed services」黑名单且不再自动重连。
 * 此时系统设置里那一项仍显示「已开启」，用户再点一次也不会绑定成功 —— 表现就是
 * 「明明开了，双击却一直提示正在启动」。这种「开了但没连上」的状态必须明确
 * 引导用户去关一次再打开，不能给出无解的「正在启动」提示。
 *
 * 这里刻意不保留设备管理员（DevicePolicyManager.lockNow）作为备用：
 * DPM 主动锁屏会置上 STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW，
 * 解锁时必须输入一次设备密码、生物识别无效，体验上像是出了故障。
 */
object ScreenOff {

    /** 引导跳转的节流，避免连续双击反复弹系统页面 */
    private var lastGuideAt = 0L

    /** 无障碍服务已真正连上（可直接调用全局操作） */
    fun isReady(): Boolean = DuoLockService.isConnected()

    /** 用户是否已在系统设置里开启该无障碍服务（注意：开了不等于连上了） */
    fun isEnabled(ctx: Context): Boolean =
        DuoLockService.isConnected() || DuoLockService.isEnabled(ctx)

    /**
     * 已开启但没连上 —— 覆盖安装后的典型状态，需要用户「关闭再重新打开」才能恢复。
     * 光靠 [isEnabled] 判断会把这种情况当成正常，从而给出无解的「正在启动」提示。
     */
    fun isStale(ctx: Context): Boolean = !isReady() && DuoLockService.isEnabled(ctx)

    /** 无障碍息屏；返回 false 表示服务未连上，调用方负责引导重绑 */
    fun lock(): Boolean = DuoLockService.lockScreen()

    // ---------------- 引导 ----------------

    /** 直接打开「本服务」的无障碍详情页（比通用列表页精确，用户只需拨一下开关） */
    fun openServiceSettings(ctx: Context) {
        val cn = ComponentName(ctx.applicationContext, DuoLockService::class.java)
        // 该 Action 在 AOSP 里是 @hide（只给 SystemUI 用），没有公开常量，故用字面量；
        // 拿不到时 runCatching 会失败并回落到下面的通用无障碍页。
        runCatching {
            ctx.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        requestPermission(ctx)
    }

    /** 打开系统「无障碍 / 已安装的服务」页面，让用户开启 DuoLauncher 息屏服务 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 「开了但没连上」时跳详情页引导，6 秒内不重复跳（连续双击时不至于反复弹页面）。
     * 返回是否真的跳转了。
     */
    fun guideToRebind(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuideAt < 6_000L) return false
        lastGuideAt = now
        openServiceSettings(ctx)
        return true
    }
}
