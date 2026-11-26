package com.duolauncher.util

import com.duolauncher.R
import com.duolauncher.util.Lang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub / Gitee Releases 检查更新。
 *
 * 两边都是公开的 REST 接口，不需要 token；设置里可以在两个官方源之间切换，
 * 也可以填任意返回同样 JSON 的自建镜像（例如代理），所以这里对地址做了宽松的规范化处理。
 */
object UpdateChecker {

    /** GitHub 官方接口（默认源） */
    const val GITHUB_URL = "https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest"

    /** Gitee 镜像接口：字段与 GitHub 基本一致，国内直连更稳 */
    const val GITEE_URL = "https://gitee.com/api/v5/repos/d3ja7u/DuoLauncher/releases/latest"

    /** 默认仍走 GitHub；设置里的「更新服务器」可切到 Gitee */
    const val DEFAULT_URL = GITHUB_URL

    const val GITHUB_PROJECT = "https://github.com/d3ja7u/DuoLauncher"
    const val GITEE_PROJECT = "https://gitee.com/d3ja7u/DuoLauncher"

    /** 「更新服务器」当前选的是哪一个，和设置里的开关一起存盘 */
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GITEE = "gitee"

    /** 按选择返回实际请求的接口地址；未知值一律走 Gitee（默认源） */
    fun urlFor(source: String): String =
        if (source == SOURCE_GITHUB) GITHUB_URL else GITEE_URL

    private const val TAG = "DuoUpdate"

    /** 自动检查的最小间隔（默认 24 小时），设置里可选 12 / 24 / 48 / 72 */
    const val DEFAULT_INTERVAL_HOURS = 24
    val INTERVAL_CHOICES = listOf(12, 24, 48, 72)
    const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L

    /** 把「小时」换算成毫秒；传进来的值不在选项里时按默认 24 小时算 */
    fun intervalMillis(hours: Int): Long =
        (if (hours in INTERVAL_CHOICES) hours else DEFAULT_INTERVAL_HOURS) * 60L * 60L * 1000L

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val publishedAt: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long
    ) {
        val sizeText: String
            get() = if (apkSize > 0) "%.1f MB".format(apkSize / 1024f / 1024f) else ""
    }

    fun currentVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * 允许直接贴 GitHub / Gitee 的网页地址（…/releases 或仓库主页），自动换成对应 API 地址；
     * 已经是 API 地址或其它自定义 JSON 接口时原样保留。
     */
    fun normalize(input: String): String {
        val s = input.trim()
        // 已经是 API 地址（或任意自建 JSON 接口）时原样返回。
        // 正则必须锚定开头：否则 "api.github.com" 里的 "github.com" 也会被当成网页地址误改。
        if (s.startsWith("http://api.github.com/", true) ||
            s.startsWith("https://api.github.com/", true) ||
            s.startsWith("https://gitee.com/api/", true) ||
            s.startsWith("http://gitee.com/api/", true)
        ) return s
        Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([^/\s?#]+)/([^/\s?#]+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            return "https://api.github.com/repos/$owner/$repo/releases/latest"
        }
        Regex(
            """^(?:https?://)?(?:www\.)?gitee\.com/([^/\s?#]+)/([^/\s?#]+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            // Gitee 的网页路径里可能带 releases / blob 之类的子路径，下面两段已裁过，这里只取 owner/repo
            return "https://gitee.com/api/v5/repos/$owner/$repo/releases/latest"
        }
        return s
    }

    /** 按数字段比较，兼容 1.3.9 / 1.3.10 / 1.4.0-beta 这类写法 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.split('.').map {
            it.trim().filter { c -> c.isDigit() }.toIntOrNull() ?: 0
        }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun fetch(url: String, localVersion: String): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val target = normalize(url)
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return@withContext Result.failure(
                IllegalArgumentException(Lang.s(R.string.err_url_scheme))
            )
        }

        val first = request(target, localVersion).mapCatching { parse(it, target) }
        Log.d(TAG, "主源 $target -> ${first.exceptionOrNull()?.message ?: "成功"}")
        if (first.isSuccess) return@withContext first

        // api.github.com 匿名请求每小时只有 60 次，校园网 / 运营商共享出口很容易撞限。
        // releases.atom 是 GitHub 官方公开订阅源，没有配额限制，自动作为备用源。
        // Gitee 的接口没有这个限制，也不需要备用源。
        val fallback = atomFallback(target)
        if (fallback != null) {
            val second = request(fallback, localVersion).mapCatching { parse(it, fallback) }
            Log.d(TAG, "备用源 $fallback -> ${second.exceptionOrNull()?.message ?: "成功"}")
            if (second.isSuccess) return@withContext second
        }
        first
    }

    /** 只负责取回文本；失败时把 HTTP 状态和剩余配额带出来，方便判断原因 */
    private fun request(url: String, localVersion: String): Result<String> = runCatching {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json, application/atom+xml, */*")
                // GitHub API 要求带 User-Agent，否则直接 403
                setRequestProperty("User-Agent", "DuoLauncher/$localVersion")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val left = conn.getHeaderField("X-RateLimit-Remaining")
                error(
                    when (code) {
                        403, 429 -> Lang.s(R.string.err_rate_limit, code, left ?: "?")
                        404 -> Lang.s(R.string.err_release_not_found)
                        else -> Lang.s(R.string.err_http, code)
                    }
                )
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun atomFallback(url: String): String? {
        val m = Regex("""api\.github\.com/repos/([^/]+)/([^/]+)""").find(url) ?: return null
        return "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}/releases.atom"
    }

    /** 从接口地址反推项目主页：GitHub / Gitee 两套接口的路径不同，但返回格式一样 */
    private fun projectBase(url: String): String? {
        Regex("""api\.github\.com/repos/([^/]+)/([^/]+)""").find(url)?.let {
            return "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}"
        }
        Regex("""gitee\.com/api/v\d+/repos/([^/]+)/([^/]+)""").find(url)?.let {
            return "https://gitee.com/${it.groupValues[1]}/${it.groupValues[2]}"
        }
        return null
    }

    /** 按内容自动识别：GitHub API JSON / Gitee API JSON / Release 数组 / releases.atom XML / 自建 JSON */
    private fun parse(body: String, sourceUrl: String): ReleaseInfo {
        val t = body.trim()
        return when {
            t.startsWith("<") -> parseAtom(t)
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                if (arr.length() == 0) error(Lang.s(R.string.err_no_release))
                parseJson(arr.getJSONObject(0), sourceUrl)
            }
            else -> parseJson(JSONObject(t), sourceUrl)
        }
    }

    /** 单个 Release 对象（/latest），或自建镜像里同结构的 JSON */
    private fun parseJson(o: JSONObject, sourceUrl: String): ReleaseInfo {
        val tag = o.optString("tag_name", "")
        if (tag.isBlank()) error(Lang.s(R.string.err_bad_json))
        val version = tag.removePrefix("v").removePrefix("V")

        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        o.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = n
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
        }

        // Gitee 的 Release 对象既没有 html_url 也没有资产大小，按 tag 自己拼出页面与直链；
        // 本项目的 APK 命名固定为 DuoLauncher-<version>.apk，规则与 GitHub 一致。
        val project = projectBase(sourceUrl)
        var pageUrl = o.optString("html_url", "")
        if (pageUrl.isBlank() && project != null) pageUrl = "$project/releases/tag/$tag"
        if (apkUrl.isBlank() && project != null) {
            apkName = "DuoLauncher-$version.apk"
            apkUrl = "$project/releases/download/$tag/$apkName"
        }
        // 发布时间：GitHub 用 published_at，Gitee 只有 created_at，个别自建源用 released_at
        val published = o.optString("published_at", "")
            .ifBlank { o.optString("created_at", "") }
            .ifBlank { o.optString("released_at", "") }

        return ReleaseInfo(
            tag = tag,
            version = version,
            name = o.optString("name", tag),
            notes = o.optString("body", ""),
            pageUrl = pageUrl,
            publishedAt = published.take(10),
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize
        )
    }

    /**
     * GitHub releases.atom：无配额限制，但没有 assets 信息。
     * 本项目的 APK 命名固定为 DuoLauncher-<version>.apk，可按 tag 拼出直链，
     * 这样即使主接口被限流、退回订阅源，也仍然支持在线下载安装。
     */
    private fun parseAtom(body: String): ReleaseInfo {
        val entry = Regex("""<entry>([\s\S]*?)</entry>""").find(body)?.groupValues?.get(1)
            ?: error(Lang.s(R.string.err_no_release))
        val pageUrl = Regex("""<link[^>]*href=["']([^"']+)["']""")
            .find(entry)?.groupValues?.get(1) ?: ""
        val tag = Regex("""/releases/tag/([^/?#"']+)""").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""").find(entry)?.groupValues?.get(1)?.trim()
            ?: error(Lang.s(R.string.err_no_tag))
        val version = tag.removePrefix("v").removePrefix("V")
        val updated = Regex("""<updated>([^<]+)</updated>""")
            .find(entry)?.groupValues?.get(1)?.take(10) ?: ""
        val raw = Regex("""<content[^>]*>([\s\S]*?)</content>""")
            .find(entry)?.groupValues?.get(1) ?: ""
        // atom 把渲染后的 HTML 做了实体转义：第一次解开实体得到 HTML，第二次再转成纯文本
        val html = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        val notes = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
        val repo = Regex("""github\.com/([^/]+)/([^/]+)""").find(pageUrl)
        val apkName = "DuoLauncher-$version.apk"
        val apkUrl = repo?.let {
            "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}/releases/download/$tag/$apkName"
        } ?: ""
        return ReleaseInfo(
            tag = tag,
            version = version,
            name = "DuoLauncher v$version",
            notes = notes,
            pageUrl = pageUrl,
            publishedAt = updated,
            apkUrl = apkUrl,
            apkName = if (apkUrl.isBlank()) "" else apkName,
            apkSize = 0L
        )
    }

    fun openInBrowser(ctx: Context, url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, Lang.s(R.string.copied_toast, label), Toast.LENGTH_SHORT).show()
    }
}
