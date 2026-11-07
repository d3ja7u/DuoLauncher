package com.duolauncher.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.duolauncher.service.DuoLockService

/**
 * 息屏能力（无障碍模式）。
 *
 * Android 上普通应用无法直接灭屏，两条合规路径：
 *  1. 设备管理员 + DevicePolicyManager.lockNow() —— 权限面过大（含擦除数据），已弃用；
 *  2. 无障碍服务 + GLOBAL_ACTION_LOCK_SCREEN（API 28+）—— 本项目采用。
 *
 * 这里只做封装，真正的动作在 [DuoLockService]。
 */
object ScreenOff {

    /** 是否可以直接息屏（服务已连上） */
    fun isReady(): Boolean = DuoLockService.isRunning()

    /** 用户是否已在系统设置里开启该无障碍服务 */
    fun isEnabled(ctx: Context): Boolean =
        DuoLockService.isRunning() || DuoLockService.isEnabled(ctx)

    /** 息屏；返回 false 表示服务未连上，调用方负责引导 */
    fun lock(): Boolean = DuoLockService.lockScreen()

    /** 打开系统「无障碍 / 已安装的服务」页面，让用户开启 DuoLauncher 息屏服务 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
