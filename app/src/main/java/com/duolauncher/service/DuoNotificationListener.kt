package com.duolauncher.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.duolauncher.R
import com.duolauncher.data.DuoState
import com.duolauncher.data.NowPlaying
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

    /**
     * 用户句柄 → 用户序号。通知回调里每条通知都要算 key，而
     * `UserManager.getSerialNumberForUser` 是跨进程调用；收 20 条通知就查 20 次。
     * 同一用户的序号在进程生命周期内不会变，缓存起来即可。
     */
    private val userSerial = java.util.concurrent.ConcurrentHashMap<android.os.UserHandle, Long>()

    /** 专辑封面缓存：同一首歌平繁回调（播放/暂停/进度）时不再重复解码大图 */
    private var artKey: String = ""
    private var artCache: Bitmap? = null
    /** 上一次用的原图（未缩放）：通知重复 post 时是同一个实例，可直接判等复用 */
    private var artRaw: Bitmap? = null
    /** 晚到封面的重试计数（按 artKey 归零，最多重试几次） */
    private var artRetryKey: String = ""
    private var artRetryCount: Int = 0
    /** 切歌后播放器的通知大图标常晚于元数据到达，延后再取一次封面 */
    private val artRefreshHandler = Handler(Looper.getMainLooper())
    private var artRefreshRunnable: Runnable? = null

    // ── 音乐胶囊：监听系统媒体会话 ──
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaRegistered = false
    private var lastControllers: List<MediaController> = emptyList()
    private var activeController: MediaController? = null
    private val mediaListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        lastControllers = list ?: emptyList()
        handleMedia(lastControllers)
    }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshCurrent()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshCurrent()
    }

    override fun onCreate() {
        super.onCreate()
        DuoState.init(applicationContext)
        loadReadKeys()
    }

    override fun onListenerConnected() {
        instance = this
        resync()
        // 音乐胶囊：连上后开始监听系统媒体会话
        initMedia()
        refreshMedia()
        // 服务未绑定期间用户点开过的应用，连上之后补做「已读」
        val pending = pendingDismiss.toList()
        pendingDismiss.clear()
        pending.forEach { clearFor(it) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // 音乐胶囊：断开时清掉活跃会话监听，避免泄漏
        activeController?.unregisterCallback(controllerCb)
        activeController = null
        mediaSessionManager?.let {
            runCatching { it.removeOnActiveSessionsChangedListener(mediaListener) }
        }
        mediaRegistered = false
        DuoState.nowPlaying = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        // 音乐胶囊兜底：媒体播放类通知出现、但当前还没拿到活跃会话时，
        // 主动重拉一次（部分 ROM 的 onActiveSessionsChanged 收不到，仅靠回调会漏掉）
        if (n?.category == Notification.CATEGORY_TRANSPORT) {
            val np = DuoState.nowPlaying
            if (np == null) {
                // 还没拿到会话：兜底重拉一次活跃会话（部分 ROM 收不到 onActiveSessionsChanged）
                refreshMedia()
            } else if (sbn.packageName == np.packageName && shouldRefreshArt(np)) {
                // 封面晚了才到：只重取封面，不再重拉活跃会话（那是跨进程调用）。
                // artIsStale 为假时（这首已经有封面了）完全不再排队 ——
                // 网易云播放中每秒刷一次通知，旧实现于是每秒 getActiveSessions + 缩图 + 整屏重组。
                artRefreshRunnable?.let { artRefreshHandler.removeCallbacks(it) }
                val forceRefresh = Runnable { handleMedia(lastControllers, forceArt = true) }
                artRefreshRunnable = forceRefresh
                artRefreshHandler.postDelayed(forceRefresh, 400L)
            }
        }
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
        lastResyncAt = SystemClock.elapsedRealtime()
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

    // ── 音乐胶囊：媒体会话处理 ──

    private fun initMedia() {
        if (mediaRegistered) return
        runCatching {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            msm.addOnActiveSessionsChangedListener(
                mediaListener,
                ComponentName(this, DuoNotificationListener::class.java),
                Handler(Looper.getMainLooper())
            )
            mediaRegistered = true
        }
    }

    /** 主动拉一次当前活跃会话（注册瞬间不一定会回调 onActiveSessionsChanged） */
    private fun refreshMedia(forceArt: Boolean = false) {
        val msm = mediaSessionManager ?: return
        runCatching {
            lastControllers = msm.getActiveSessions(
                ComponentName(this, DuoNotificationListener::class.java)
            )
            handleMedia(lastControllers, forceArt)
        }
    }

    private fun refreshCurrent() = handleMedia(lastControllers)

    /**
     * 这首还需不需要再取一次封面。
     *
     * - 还没有封面 / 缓存里放的是上一首：每首最多补 [ART_RETRY_LIMIT] 次；
     * - 已有封面：再补 [ART_RETRY_EXTRA] 次就够 —— 切歌瞬间播放器常先用上一首的大图标
     *   顶一下，晚到的高清封面得靠这几次补救。
     *
     * 总之每首都是「有限次」，不像旧实现那样播放器每刷一次通知就重取一次
     * （网易云播放中每秒刷通知 → 每秒 getActiveSessions + 缩图 + 整屏重组）。
     */
    private fun shouldRefreshArt(np: NowPlaying): Boolean {
        if (np.artKey != artRetryKey) { artRetryKey = np.artKey; artRetryCount = 0 }
        val stale = np.art == null || artKey != np.artKey
        val limit = if (stale) ART_RETRY_LIMIT else ART_RETRY_EXTRA
        if (artRetryCount >= limit) return false
        artRetryCount++
        return true
    }

    /** 仅在真正播放时展示胶囊；暂停 / 停止则清空。
     * 活跃判定放宽：除 STATE_PLAYING 外，BUFFERING / 快进快退 / 切歌等过渡态也算活跃；
     * 部分播放器（如网易云）在播放中上报的 state 不是标准的 PLAYING，或干脆为 null，
     * 这两种情况只要元数据里有曲名就当作正在播放，保证胶囊能稳定出现。 */
    private fun isActiveState(state: Int?): Boolean {
        if (state == null) return true
        return when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT -> true
            else -> false
        }
    }

    /**
     * 胶囊的存活判定：**只要媒体会话还在就保留**，暂停不清空（长按可恢复播放）。
     * 会话从 activeSessions 里消失（播放器被杀 / 退出后台 / 系统回收）才清空。
     *
     * 选会话的优先级：正在播或处于过渡态 → 当前已选且仍有曲目 → 任意有曲目的会话。
     * `playing` 用来驱动胶囊的视觉状态（暂停时竖条变暗）。
     */
    private fun handleMedia(controllers: List<MediaController>, forceArt: Boolean = false) {
        fun hasMeta(c: MediaController) =
            c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true

        fun isBusy(c: MediaController): Boolean {
            val s = c.playbackState?.state ?: return hasMeta(c)   // 报不出 state 的老播放器
            return isActiveState(s)
        }

        val chosen = controllers.firstOrNull { isBusy(it) }
            ?: controllers.firstOrNull { hasMeta(it) && it.sessionToken == activeController?.sessionToken }
            ?: controllers.firstOrNull { hasMeta(it) }

        if (chosen == null) {
            // 会话彻底消失（应用不在后台了）才收起胶囊
            activeController?.unregisterCallback(controllerCb)
            activeController = null
            DuoState.nowPlaying = null
            return
        }
        if (activeController == null || activeController?.sessionToken != chosen.sessionToken) {
            activeController?.unregisterCallback(controllerCb)
            activeController = chosen
            runCatching { chosen.registerCallback(controllerCb) }
        }
        val meta = chosen.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val pkg = chosen.packageName ?: ""
        val act: PendingIntent? = chosen.sessionActivity
        val state = chosen.playbackState?.state
        val playing = when (state) {
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> false
            else -> true
        }
        // 封面身份必须包含专辑和封面 URI：同一首歌在不同专辑里封面不同；
        // 很多播放器先给歌名、封面 URI 晚一拍才到，旧逻辑只比歌名就把后到的封面挡掉了。
        val newArtKey = listOf(
            title, artist, album,
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: "",
            meta?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: ""
        ).joinToString("|")
        // 播放器常常每秒刷新好几次 playbackState / 通知（进度），歌与播放状态都没变、
        // 封面也还是同一张时，**任何情况下**都不要再新建 NowPlaying ——
        // 那会让整个主屏（胶囊 + 频谱 + 状态栏避让）每秒钟重组一次。
        // 旧实现只要 forceArt（等晚到封面）为真就无条件重建，网易云播放中每秒刷通知，
        // 于是每秒白掉一次整屏重组 + 一次 320px 缩图。
        val cur = DuoState.nowPlaying
        val metaSame = cur != null && cur.title == title && cur.artist == artist &&
            cur.packageName == pkg && cur.playing == playing &&
            cur.sessionActivity == act && cur.artKey == newArtKey
        if (metaSame && cur?.art != null && !forceArt) return
        val art: Bitmap? = artFor(meta, pkg, newArtKey, forceArt) ?: cur?.art
        if (metaSame && art === cur?.art) return
        DuoState.nowPlaying = NowPlaying(title, artist, art, pkg, act, playing, newArtKey)
    }

    /**
     * 专辑封面：降采样到 320px 以内，按「歌名|歌手|专辑|封面URI」缓存。
     *
     * 取图顺序：① 会话里的 bitmap（ALBUM_ART / ART）；② 通知大图标（多数播放器都有）；
     * ③ content:// 的封面 URI。原来只走 ①，网易云等把封面放通知大图标 / URI 里时
     * 就一直空着；再加上「封面晚到被 early-return 挡住」，表现为切歌不换图。
     */
    private fun artFor(meta: MediaMetadata?, pkg: String, key: String, force: Boolean = false): Bitmap? {
        if (!force && key.isNotBlank() && key == artKey) return artCache
        // 通知大图标常比元数据晚到，优先用会话给出的封面 URI（明确指向当前曲目），
        // 再退回通知大图标，避免把上一首的图缓存成这一首的。
        val raw = runCatching {
            meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }.getOrNull()
            ?: uriArt(
                meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            )
            ?: notificationArt(pkg)
            ?: return null
        // 强制刷新时通知大图标可能还是同一张 Bitmap 实例（只是通知被重新 post 了一次）：
        // 直接复用缓存，别再缩一遍图、更别让上层因此重建 NowPlaying
        if (raw === artRaw && key == artKey) return artCache
        val out = downscale(raw)
        artKey = key
        artRaw = raw
        artCache = out
        return out
    }

    /** 媒体通知的大图标：系统里最稳定的封面来源，尤其网易云 / QQ 音乐 */
    private fun notificationArt(pkg: String): Bitmap? {
        if (pkg.isBlank()) return null
        return runCatching {
            activeNotifications.firstOrNull { sbn ->
                sbn.packageName == pkg &&
                    (sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
                        sbn.notification.extras?.containsKey("android.media.metadata.TITLE") == true)
            }?.notification?.extras?.let { b ->
                @Suppress("DEPRECATION")
                b.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
                    ?: (b.get(Notification.EXTRA_LARGE_ICON) as? android.graphics.drawable.Icon)
                        ?.loadDrawable(this)?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
            }
        }.getOrNull()
    }

    /** content:// 封面 URI：只在本地读，不发起网络请求 */
    private fun uriArt(uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "content") return@runCatching null
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun downscale(raw: Bitmap): Bitmap {
        val maxEdge = maxOf(raw.width, raw.height)
        return if (maxEdge > 320) runCatching {
            val s = 320f / maxEdge
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * s).toInt().coerceAtLeast(1),
                (raw.height * s).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(raw) else raw
    }

    private fun publish() {
        val agg = HashMap<String, Int>()
        entries.forEach { (k, v) ->
            val pkg = k.substringBefore('|')
            agg[pkg] = (agg[pkg] ?: 0) + v
        }
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
            // 按 UserHandle 缓存：序列号在进程生命周期内不会变，而通知回调是每条都走一遍
            userSerial.getOrPut(uh) {
                val um = getSystemService(Context.USER_SERVICE) as? UserManager
                um?.getSerialNumberForUser(uh) ?: -1L
            }
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
        /** 还没有封面时，同一首最多补几次 */
        private const val ART_RETRY_LIMIT = 4
        /** 已有封面后，再补几次（等晚到的高清图） */
        private const val ART_RETRY_EXTRA = 2
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

        /** 最近一次全量对齐通知栏的时刻 */
        @Volatile private var lastResyncAt = 0L

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
                // 服务连上时 onListenerConnected 已对齐过，之后通知变化都由
                // onNotificationPosted / Removed 实时维护。频繁按 Home 回桌面没必要
                // 每次都跨进程遍历整条通知栏，短时间内的重复 resync 合并掉。
                if (SystemClock.elapsedRealtime() - lastResyncAt >= 2_000L) cur.resync()
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

        /** 音乐胶囊：暂停 / 继续当前播放（暂停后胶囊仍保留，再长按恢复） */
        fun togglePlayPause() {
            val c = instance?.activeController ?: return
            val np = DuoState.nowPlaying
            val playing = np?.playing ?: (c.playbackState?.state == PlaybackState.STATE_PLAYING)
            runCatching {
                if (playing) c.transportControls.pause() else c.transportControls.play()
            }
            // 乐观更新：回调慢 / 不回调的播放器也能立刻看到状态翻转，胶囊不会闪没
            if (np != null) DuoState.nowPlaying = np.copy(playing = !playing)
        }

        /** 展开面板用：播放快照。返回 null 表示会话已不在 */
        fun snapshot(): com.duolauncher.data.PlaybackSnapshot? {
            val c = instance?.activeController ?: return null
            val st = c.playbackState ?: return null
            return com.duolauncher.data.PlaybackSnapshot(
                state = st.state,
                position = st.position,
                duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                speed = st.playbackSpeed,
                // getLastUpdateTime() 是隐藏 API，这里以读取时刻为基准，误差 < 1 帧
                lastUpdate = android.os.SystemClock.elapsedRealtime()
            )
        }

        /** 进度是否可拖：播放器声明了 ACTION_SEEK_TO 且时长已知 */
        fun canSeek(): Boolean {
            val c = instance?.activeController ?: return false
            val st = c.playbackState ?: return false
            return st.actions and PlaybackState.ACTION_SEEK_TO != 0L
        }

        fun skipNext() {
            runCatching { instance?.activeController?.transportControls?.skipToNext() }
        }

        fun skipPrevious() {
            runCatching { instance?.activeController?.transportControls?.skipToPrevious() }
        }

        fun seekTo(ms: Long) {
            runCatching { instance?.activeController?.transportControls?.seekTo(ms) }
        }

        /** 收藏曲目的稳定 key（跨会话重连仍认得同一首） */
        fun favoriteKey(np: com.duolauncher.data.NowPlaying): String =
            "${np.packageName}|${np.title}|${np.artist}"

        /**
         * 收藏 / 取消收藏。
         * 媒体会话没有统一标准：优先发播放器自带的 like / 收藏 自定义 action，
         * 没有则退回 setRating（Rating 在网易云等实际上是「喜欢」而非「收藏」，
         * 所以仅作兜底）。本机状态立即翻转，不依赖播放器回传。
         */
        fun toggleFavorite(ctx: Context): Boolean {
            val np = DuoState.nowPlaying ?: return false
            val key = favoriteKey(np)
            val next = !DuoState.mediaFavorites[key].orDefaultFalse()
            DuoState.mediaFavorites[key] = next
            DuoState.save()
            val c = instance?.activeController
            var sent = false
            if (c != null) {
                val actions = runCatching { c.playbackState?.customActions }.getOrNull().orEmpty()
                // 网易云同时给了 like（红心）和 收藏（加歌单），用户说的是「收藏」，优先后者
                val action = actions.firstOrNull { it.name.contains("收藏", true) }
                    ?: actions.firstOrNull {
                        it.name.toString().equals("like", ignoreCase = true) ||
                            it.name.toString().contains("favorite", ignoreCase = true)
                    }
                if (action != null) {
                    sent = runCatching {
                        c.transportControls.sendCustomAction(action, action.extras)
                        true
                    }.getOrDefault(false)
                }
                if (!sent) {
                    sent = runCatching {
                        c.transportControls.setRating(
                            Rating.newHeartRating(next)
                        )
                        true
                    }.getOrDefault(false)
                }
            }
            toast(ctx, if (next) R.string.np_favorited else R.string.np_unfavorited)
            return sent
        }

        /** 当前播放器是否提供歌词入口（网易云以 lyric 自定义 action 的形式给出） */
        fun hasLyricAction(): Boolean = lyricAction() != null

        /**
         * 打开播放器自带的歌词页。
         * 国内播放器普遍不把歌词文本放进 MediaMetadata，只能通过它暴露的
         * lyric / 歌词 自定义 action 跳到 App 内部歌词界面。
         */
        fun openLyrics(ctx: Context): Boolean {
            val c = instance?.activeController ?: return false
            val action = lyricAction() ?: return false
            val sent = runCatching {
                c.transportControls.sendCustomAction(action, action.extras)
                true
            }.getOrDefault(false)
            if (!sent) toast(ctx, R.string.np_lyric_unavailable)
            return sent
        }

        private fun lyricAction(): PlaybackState.CustomAction? {
            val actions = instance?.activeController?.playbackState?.customActions ?: return null
            fun isLyric(a: PlaybackState.CustomAction): Boolean {
                val n = a.name?.toString().orEmpty()
                return n.equals("lyric", true) || n.contains("lyric", true) || n.contains("歌词")
            }
            return actions.firstOrNull(::isLyric)
        }

        /** 当前可用音频输出设备（只列系统外放 / 有线 / 蓝牙 / USB，不含虚拟路由） */
        fun outputDevices(ctx: Context): List<AudioDeviceInfo> {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
            return runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { d ->
                    when (d.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_HDMI,
                        AudioDeviceInfo.TYPE_DOCK -> true
                        else -> false
                    }
                }.distinctBy { it.type }
            }.getOrDefault(emptyList())
        }

        /** 当前实际出声的是不是扬声器：扬声器用灰色默认输出图标，其它设备高亮。 */
        fun activeOutputIsSpeaker(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
            return runCatching {
                val active = am.activePlaybackConfigurations
                    .mapNotNull { it.audioDeviceInfo }
                    .filter { !it.isSource }
                active.isEmpty() || active.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }.getOrDefault(true)
        }

        /**
         * 切换输出到指定设备。
         * Android 12+ 的 MediaRouter2 只允许媒体会话持有者切系统路由，桌面启动器
         * 不是网易云的会话持有者，直接 selectRoute 会被系统拒；所以这里统一拉起
         * 媒体通知的输出切换器（stateful 的系统入口），由用户在系统面板里点选。
         */
        fun openOutputSwitcher(ctx: Context): Boolean =
            com.duolauncher.util.SystemPanel.openNotifications(ctx)

        private fun toast(ctx: Context, resId: Int) {
            runCatching {
                android.widget.Toast.makeText(ctx, resId, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        private fun Boolean?.orDefaultFalse(): Boolean = this == true

        /**
         * 音乐胶囊：点击打开对应播放器。
         * 顺序改为「先拉主界面、再回落 sessionActivity」——原来先发 PendingIntent，
         * 部分播放器的 PendingIntent 已失效却不会抛异常，runCatching 静默吞掉后直接 return，
         * 结果点了没反应。
         */
        fun openMediaApp(ctx: Context) {
            val np = DuoState.nowPlaying
            val pkg = np?.packageName
            if (!pkg.isNullOrBlank()) {
                val launched = runCatching {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent == null) {
                        false
                    } else {
                        intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )
                        // 桌面本身常驻前台：先把自己收起，避免播放器被压在新任务下面
                        ctx.startActivity(intent)
                        true
                    }
                }.getOrDefault(false)
                if (launched) return
            }
            val act = np?.sessionActivity
            if (act != null) {
                val ok = runCatching { act.send(ctx, 0, null); true }.getOrDefault(false)
                if (ok) return
            }
            runCatching {
                android.widget.Toast.makeText(
                    ctx, R.string.open_media_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
