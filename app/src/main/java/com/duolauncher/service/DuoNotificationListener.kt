package com.duolauncher.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.duolauncher.data.DuoState
import java.util.Collections

/**
 * 通知角标数据源。
 *
 * Android 没有 iOS 那种系统级「应用未读数」，桌面只能自己数通知栏：
 * 监听每条通知的发出 / 撤销，按包名聚合，再喂给 [DuoState.notifCounts]。
 *
 * 计数口径：
 * - 通知自带 number（微信/QQ 这类会写消息条数）就用它，否则按「1 条通知 = 1」计；
 * - 只统计真正的「未读消息」：前台服务、常驻、媒体播放控制、分组摘要、系统框架通知一律不计。
 *   否则音乐播放器那条 `NO_CLEAR|FOREGROUND_SERVICE|transport` 的播放控制条会被当成
 *   1 条未读，通知栏里看不到、进出应用也不会消失，形成清不掉的假角标；
 * - 打开应用即把该应用现有通知标记为已读，标记会持久化。监听服务重连、桌面 onResume
 *   触发的 [resync] 都不会把用户已经清掉的角标重新加回来；只有通知里 number 变大
 *   （真正来了新消息）或通知撤销后重新发出，才会再次计数。
 *
 * 需要用户在系统「通知使用权」里授权本应用，否则服务不会被系统绑定。
 * 未授权时 [instance] 为 null，角标自然全为 0，不会报错。
 */
class DuoNotificationListener : NotificationListenerService() {

    /** key = 包名|通知id|tag，value = 该条通知贡献的数字 */
    private val entries = HashMap<String, Int>()

    /** 已读通知：key -> 标记已读时的计数。持久化，跨服务重连 / 进程重启有效 */
    private val readKeys = HashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        DuoState.init(applicationContext)
        loadReadKeys()
    }

    override fun onListenerConnected() {
        instance = this
        resync()
        // 服务未绑定期间用户点开过的应用，连上之后补做「已读」
        val pending = pendingDismiss.toList()
        pendingDismiss.clear()
        pending.forEach { clearFor(it) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = keyOf(sbn)
        if (!isCountable(sbn)) {
            // 普通通知变成常驻通知（例如开始播放音乐）：不再计入角标
            if (entries.remove(key) != null) publish()
            return
        }
        val count = countOf(sbn)
        val readAt = readKeys[key]
        if (readAt != null) {
            // 应用反复刷新同一条通知（切歌、进度更新、服务重连）不算新消息；
            // 只有 number 变大才认为来了新消息，重新显示角标。
            if (count <= readAt) return
            readKeys.remove(key)
            saveReadKeys()
        }
        if (entries[key] != count) {
            entries[key] = count
            publish()
        }
    }

    private fun handleRemoved(sbn: StatusBarNotification) {
        val key = keyOf(sbn)
        val had = entries.remove(key) != null
        // 通知真的从通知栏消失了，已读标记也撤掉：下次再发就是一条新通知
        if (readKeys.remove(key) != null) saveReadKeys()
        if (had) publish()
    }

    // 不同系统版本 / ROM 回调的重载不一样，三个都兜住，且都不调 super 避免重复处理
    override fun onNotificationRemoved(sbn: StatusBarNotification) = handleRemoved(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification, ranking: RankingMap) = handleRemoved(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification, ranking: RankingMap, reason: Int) =
        handleRemoved(sbn)

    /** 和通知栏对齐一次（服务重连 / 桌面回到前台时调用），保留用户已读状态 */
    fun resync() {
        entries.clear()
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        val liveKeys = HashSet<String>(active.size)
        var readDirty = false
        active.forEach { sbn ->
            if (!isCountable(sbn)) return@forEach
            val key = keyOf(sbn)
            val count = countOf(sbn)
            val readAt = readKeys[key]
            if (readAt != null) {
                if (count <= readAt) {
                    liveKeys.add(key)
                    return@forEach
                }
                readKeys.remove(key)
                readDirty = true
            }
            entries[key] = count
            liveKeys.add(key)
        }
        // 通知栏里已经不存在的已读记录无需保留（下次出现即视为新通知）
        if (readKeys.keys.retainAll(liveKeys)) readDirty = true
        if (readDirty) saveReadKeys()
        publish()
    }

    /** 清掉某个应用的全部角标（打开应用时调用） */
    fun clearFor(pkg: String) {
        val keys = entries.keys.filter { it.startsWith("$pkg|") }
        if (keys.isEmpty()) {
            DuoState.clearBadge(pkg)
            return
        }
        keys.forEach { k -> readKeys[k] = entries.remove(k) ?: 1 }
        saveReadKeys()
        publish()
    }

    /** 设置里「清除全部角标」：当前所有在计数的通知都标记为已读 */
    fun clearAll() {
        if (entries.isEmpty()) {
            DuoState.setNotifCounts(emptyMap())
            return
        }
        entries.forEach { (k, v) -> readKeys[k] = v }
        entries.clear()
        saveReadKeys()
        publish()
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

    /** 是否算「未读消息」。常驻 / 前台服务 / 媒体控制类通知不产生角标 */
    private fun isCountable(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        // 本应用与系统框架的常驻通知（USB、前台服务提示、静默分组）不算应用未读
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return false
        val n = sbn.notification ?: return false
        val sticky = FLAG_ONGOING or FLAG_NO_CLEAR or FLAG_GROUP_SUMMARY or FLAG_FOREGROUND_SERVICE
        if (n.flags and sticky != 0) return false
        return when (n.category) {
            Notification.CATEGORY_TRANSPORT,   // 媒体播放控制
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_SYSTEM -> false
            else -> true
        }
    }

    /**
     * 通知所属应用对应的 AppInfo.id：主用户是纯包名，克隆空间是 `包名@用户序号`。
     * 两个微信包名相同，不区分用户的话角标会串在一起。
     */
    private fun appKeyOf(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName ?: return ""
        val serial = runCatching {
            if (Build.VERSION.SDK_INT < 29) return@runCatching -1L
            val uh = sbn.user ?: return@runCatching -1L
            if (uh == Process.myUserHandle()) return@runCatching -1L
            val um = getSystemService(Context.USER_SERVICE) as? UserManager
            um?.getSerialNumberForUser(uh) ?: -1L
        }.getOrDefault(-1L)
        return if (serial > 0) "$pkg@$serial" else pkg
    }

    private fun keyOf(sbn: StatusBarNotification) = "${appKeyOf(sbn)}|${sbn.id}|${sbn.tag ?: ""}"

    private fun countOf(sbn: StatusBarNotification): Int {
        val n: Notification = sbn.notification ?: return 1
        // 等价于 Notification.EXTRA_NUMBER（该类里没有公开常量，直接用字面量）
        val num = runCatching { n.extras?.getInt("android.number", 0) ?: 0 }.getOrDefault(0)
        return if (num > 0) num else 1
    }

    private fun loadReadKeys() {
        readKeys.clear()
        runCatching {
            getSharedPreferences(PREF_READ, MODE_PRIVATE)
                .getStringSet(KEY_READ, emptySet())
                .orEmpty()
                .forEach { s ->
                    val i = s.lastIndexOf('#')
                    if (i > 0) {
                        s.substring(i + 1).toIntOrNull()?.let { readKeys[s.substring(0, i)] = it }
                    }
                }
        }
    }

    private fun saveReadKeys() {
        runCatching {
            getSharedPreferences(PREF_READ, MODE_PRIVATE).edit()
                .putStringSet(KEY_READ, readKeys.map { "${it.key}#${it.value}" }.toSet())
                .apply()
        }
    }

    companion object {
        private const val PREF_READ = "duo_badge_read_v1"
        private const val KEY_READ = "readKeys"

        // 位值直接写，避开部分 API 里被 @hide 的 Notification 常量
        private const val FLAG_ONGOING = 0x8              // 1 << 3  FLAG_ONGOING_EVENT
        private const val FLAG_NO_CLEAR = 0x20            // 1 << 5
        private const val FLAG_GROUP_SUMMARY = 0x200      // 1 << 9
        private const val FLAG_FOREGROUND_SERVICE = 0x400000 // 1 << 22

        @Volatile
        var instance: DuoNotificationListener? = null
            private set

        /** 服务没连上时用户点开过的应用，等绑定后补清角标 */
        private val pendingDismiss = Collections.synchronizedSet(LinkedHashSet<String>())

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
            val cur = instance
            if (cur != null) cur.clearFor(pkg)
            else {
                DuoState.clearBadge(pkg)
                pendingDismiss.add(pkg)
            }
        }

        /** 设置里一键清除所有角标 */
        fun dismissAll() {
            val cur = instance
            if (cur != null) cur.clearAll()
            else DuoState.setNotifCounts(emptyMap())
        }
    }
}
