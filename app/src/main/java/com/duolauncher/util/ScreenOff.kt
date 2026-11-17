package com.duolauncher.util

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.duolauncher.DuoDeviceAdmin
import com.duolauncher.R
import com.duolauncher.service.DuoLockService

/**
 * 息屏能力。两条路径，主用无障碍、备用设备管理员：
 *  1. 无障碍服务 + GLOBAL_ACTION_LOCK_SCREEN（API 28+）—— 不需要额外授权，默认走这条；
 *  2. 设备管理员 + DevicePolicyManager.lockNow()，只声明 force-lock 一条策略。
 *
 * 为什么要有备用：应用每次被覆盖安装（含应用内更新）系统都会杀掉进程，
 * Android 会把当时仍绑定的无障碍服务记进「Crashed services」黑名单且不再自动重连。
 * 此时系统设置里那一项仍显示「已开启」，用户再点一次也不会绑定成功 —— 表现就是
 * 「明明开了，双击却一直提示正在启动」。设备管理员不受这个绑定失效影响，
 * 因此作为兜底：无障碍连不上时仍能息屏，不必等用户去设置里关一次再开。
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

    /** 无障碍息屏；返回 false 表示服务未连上，调用方负责兜底 / 引导 */
    fun lock(): Boolean = DuoLockService.lockScreen()

    // ---------------- 设备管理员（备用） ----------------

    private fun dpm(ctx: Context): DevicePolicyManager? =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun adminComponent(ctx: Context): ComponentName =
        ComponentName(ctx.applicationContext, DuoDeviceAdmin::class.java)

    /** 本应用是否已被设为设备管理员 */
    fun isAdminActive(ctx: Context): Boolean = runCatching {
        dpm(ctx)?.isAdminActive(adminComponent(ctx)) == true
    }.getOrDefault(false)

    /** 备用息屏：lockNow 只锁屏，不受无障碍绑定失效影响。未激活管理员时返回 false */
    fun lockByAdmin(ctx: Context): Boolean {
        if (!isAdminActive(ctx)) return false
        return runCatching {
            dpm(ctx)?.lockNow()
            true
        }.getOrDefault(false)
    }

    /** 跳系统「启用设备管理员」页，由用户确认；返回后由调用方重新读 [isAdminActive] */
    fun requestAdmin(ctx: Context): Boolean = runCatching {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                Lang.s(R.string.lock_admin_explain)
            )
        // 这里绝对不能加 FLAG_ACTIVITY_NEW_TASK：系统的 DeviceAdminAdd 一旦发现该 flag 就
        // 直接 finish()（logcat: "Cannot start ADD_DEVICE_ADMIN as a new task"），
        // 表现是授权页闪一下又退回、开关怎么也打不开。必须由 Activity 原地启动。
        val target = ctx.activityOrNull() ?: ctx.applicationContext
        target.startActivity(intent)
    }.isSuccess

    /** 逐层解包 ContextWrapper，拿到真正的 Activity */
    private fun Context.activityOrNull(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** 关闭备用方式（撤销设备管理员） */
    fun removeAdmin(ctx: Context) {
        runCatching { dpm(ctx)?.removeActiveAdmin(adminComponent(ctx)) }
    }

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
