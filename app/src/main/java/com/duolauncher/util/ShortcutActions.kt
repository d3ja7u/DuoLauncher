package com.duolauncher.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.duolauncher.R

/**
 * 桌面快捷键可以指向的「系统动作」（非应用目标）。
 *
 * 绑定值里用 `act:` 前缀区分：普通字符串是应用 id（`包名` 或 `包名@用户序号`），
 * `act:息屏` 这类是动作。这样既能兼容老存档里直接存应用 id 的绑定，
 * 也不必新建一张表。
 *
 * 这里只收录第三方应用确实能做到的动作：需要系统签名 / 特权权限的
 * （静默截屏、录音等）一律不放进来，免得点了没反应。
 */
object ShortcutActions {
    const val PREFIX = "act:"

    data class Action(
        val id: String,
        @StringRes val labelRes: Int,
        @StringRes val descRes: Int
    )

    val ALL = listOf(
        Action("screenoff", R.string.act_screenoff, R.string.act_screenoff_desc),
        Action("notifications", R.string.act_notifications, R.string.act_notifications_desc),
        Action("quicksettings", R.string.act_quicksettings, R.string.act_quicksettings_desc),
        Action("dial", R.string.act_dial, R.string.act_dial_desc),
        Action("sms", R.string.act_sms, R.string.act_sms_desc)
    )

    fun encode(id: String): String = PREFIX + id
    fun isAction(value: String): Boolean = value.startsWith(PREFIX)
    fun idOf(value: String): String? = value.takeIf { isAction(it) }?.removePrefix(PREFIX)
    fun find(id: String): Action? = ALL.firstOrNull { it.id == id }

    /** 执行动作；失败返回 false，让调用方可以不吞掉这次按键 */
    fun fire(context: Context, id: String): Boolean = when (id) {
        "screenoff" -> ScreenOff.lock()
        "notifications" -> SystemPanel.openNotifications(context)
        "quicksettings" -> SystemPanel.openQuickSettings(context)
        "dial" -> start(context, Intent(Intent.ACTION_DIAL))
        "sms" -> start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        else -> false
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
