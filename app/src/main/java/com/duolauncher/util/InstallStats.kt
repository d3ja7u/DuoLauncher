package com.duolauncher.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.duolauncher.data.DuoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 可关闭的匿名使用统计。
 *
 * 设计原则：
 * - 只发「随机匿名 ID + 版本 + 界面语言 + Android API + 上报类型」，不含任何设备标识、位置、
 *   通讯录、已安装应用列表；服务端也不落盘 IP。
 * - 匿名 ID 单独存一份 SharedPreferences，**不进 DuoState 的备份**，避免导入别人的备份后串号。
 * - 开关关闭时立即删除本地匿名 ID；再次打开会生成全新的 ID，无法与之前关联。
 * - 只有用户确认过首次告知（DuoState.statsConsentShown）后才会联网；地址留空也完全不发。
 * - 失败静默（不弹提示、不反复重试），心跳按 24 小时节流，绝不影响桌面使用。
 */
object InstallStats {

    /** 默认接收端（WorkBuddy 沙箱实例）；正式使用建议自托管 tools/stats-server 后改掉这里 */
    const val DEFAULT_URL = "https://duolauncher-stats.app.workbuddy.host/ping?sharecode=AKJCYWSUYS"
    /** 人看的统计页，设置里「查看统计数据」打开它 */
    const val STATS_PAGE = "https://duolauncher-stats.app.workbuddy.host/?sharecode=AKJCYWSUYS"

    private const val PREF = "duo_stats_v1"
    private const val K_ID = "anon_id"
    private const val K_VER = "last_version"
    private const val K_LAST = "last_ping"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val TAG = "DuoStats"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前匿名 ID（未开启 / 已清除时为 null），设置页用来展示状态 */
    fun anonymousId(ctx: Context): String? = runCatching {
        sp(ctx).getString(K_ID, null)
    }.getOrNull()

    /** 同步取（必要时生成）匿名 ID，供设置页打开开关后立即展示状态 */
    fun ensureId(ctx: Context): String = runCatching {
        val p = sp(ctx)
        p.getString(K_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(K_ID, it).apply()
        }
    }.getOrDefault("")

    /** 关闭统计：抹掉本地一切统计痕迹 */
    fun clear(ctx: Context) {
        runCatching { sp(ctx).edit().clear().apply() }
    }

    private fun versionCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    private fun language(ctx: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 24) ctx.resources.configuration.locales[0].language
        else @Suppress("DEPRECATION") ctx.resources.configuration.locale.language
    }.getOrDefault(Lang.get(ctx)).take(8)

    private fun nextKind(ctx: Context): String {
        val ver = UpdateChecker.currentVersion(ctx)
        val last = runCatching { sp(ctx).getString(K_VER, "") ?: "" }.getOrDefault("")
        return when {
            last.isBlank() -> "install"
            last != ver -> "upgrade"
            else -> "heartbeat"
        }
    }

    /**
     * 在进程启动 / 回到桌面 / 用户确认告知时调用；内部自行判断是否需要真的发请求。
     * [force] 用于「首次确认」这种希望立刻上报一次的场合。
     */
    fun reportIfAllowed(ctx: Context, force: Boolean = false) {
        if (!DuoState.statsEnabled || !DuoState.statsConsentShown) return
        val url = DuoState.statsUrl.trim()
        if (url.isBlank()) return
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        val kind = nextKind(ctx)
        val last = runCatching { sp(ctx).getLong(K_LAST, 0L) }.getOrDefault(0L)
        if (!force && kind == "heartbeat" && System.currentTimeMillis() - last < DAY) return
        CoroutineScope(Dispatchers.IO).launch {
            send(ctx.applicationContext, url, kind)
        }
    }

    private fun send(ctx: Context, url: String, kind: String) {
        val p = sp(ctx)
        val id = ensureId(ctx)
        val ver = UpdateChecker.currentVersion(ctx)
        val body = JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("app", ver)
            .put("code", versionCode(ctx))
            .put("lang", language(ctx))
            .put("api", Build.VERSION.SDK_INT)
            .put("kind", kind)
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                // 只有成功才推进时间与版本，失败下次启动会自然重试
                p.edit()
                    .putLong(K_LAST, System.currentTimeMillis())
                    .putString(K_VER, ver)
                    .apply()
            } else {
                Log.d(TAG, "report returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.d(TAG, "report skipped: ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
