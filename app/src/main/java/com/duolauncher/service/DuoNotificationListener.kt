package com.duolauncher.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.duolauncher.data.DuoState

/**
 * 通知角标数据源。
 *
 * Android 没有 iOS 那种系统级「应用未读数」，桌面只能自己数通知栏：
 * 监听每条通知的发出 / 撤销，按包名聚合，再喂给 [DuoState.notifCounts]。
 *
 * 计数口径：
 * - 通知自带 number（微信/QQ 这类会写消息条数）就用它；
 * - 否则按「1 条通知 = 1」计。
 *
 * 需要用户在系统「通知使用权」里授权本应用，否则服务不会被系统绑定。
 * 未授权时 [instance] 为 null，角标自然全为 0，不会报错。
 */
class DuoNotificationListener : NotificationListenerService() {

    /** key = 包名|通知id|tag，value = 该条通知贡献的数字 */
    private val entries = HashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        DuoState.init(applicationContext)
    }

    override fun onListenerConnected() {
        instance = this
        resync()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val key = keyOf(sbn)
        val count = countOf(sbn)
        if (entries[key] != count) {
            entries[key] = count
            publish()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (entries.remove(keyOf(sbn)) != null) publish()
    }

    /** 和通知栏对齐一次（服务重连 / 桌面回到前台时调用） */
    fun resync() {
        entries.clear()
        runCatching {
            activeNotifications?.forEach { entries[keyOf(it)] = countOf(it) }
        }
        publish()
    }

    /** 清掉某个应用的全部角标（打开应用时调用） */
    fun clearFor(pkg: String) {
        val removed = entries.keys.filter { it.startsWith("$pkg|") }
        if (removed.isNotEmpty()) {
            removed.forEach { entries.remove(it) }
            publish()
        } else {
            DuoState.clearBadge(pkg)
        }
    }

    private fun publish() {
        val agg = HashMap<String, Int>()
        entries.forEach { (k, v) ->
            val pkg = k.substringBefore('|')
            agg[pkg] = (agg[pkg] ?: 0) + v
        }
        android.util.Log.d("DuoNotif", "publish $agg")
        DuoState.setNotifCounts(agg)
    }

    private fun keyOf(sbn: StatusBarNotification) = "${sbn.packageName}|${sbn.id}|${sbn.tag ?: ""}"

    private fun countOf(sbn: StatusBarNotification): Int {
        val n: Notification = sbn.notification ?: return 1
        // 等价于 Notification.EXTRA_NUMBER（该类里没有公开常量，直接用字面量）
        val num = runCatching { n.extras?.getInt("android.number", 0) ?: 0 }.getOrDefault(0)
        return if (num > 0) num else 1
    }

    companion object {
        @Volatile
        var instance: DuoNotificationListener? = null
            private set

        /** 是否已在系统里授予「通知使用权」 */
        fun hasAccess(ctx: Context): Boolean {
            val flat = runCatching {
                Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            }.getOrNull() ?: return false
            return flat.split(':').any {
                ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
            }
        }

        /** 跳转系统「通知使用权」设置页 */
        fun openSettings(ctx: Context) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /** 已授权但服务没被绑定（常见于刚授权 / 进程被回收）时，主动请求重连 */
        fun ensureBound(ctx: Context) {
            val cur = instance
            if (cur != null) {
                cur.resync()
                return
            }
            if (!hasAccess(ctx)) return
            runCatching {
                requestRebind(ComponentName(ctx, DuoNotificationListener::class.java))
            }
        }

        /** 打开应用后清角标：内部计数和对外状态一起清，否则下条通知会把旧数字带回来 */
        fun dismiss(pkg: String) {
            instance?.clearFor(pkg) ?: DuoState.clearBadge(pkg)
        }
    }
}
