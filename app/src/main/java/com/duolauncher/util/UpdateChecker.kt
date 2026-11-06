package com.duolauncher.util

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
 * 从 GitHub Releases 检查更新。
 *
 * 走公开的 REST 接口，不需要 token；设置里可以换成任意返回同样 JSON 的地址
 * （例如自建镜像 / 代理），所以这里对地址做了宽松的规范化处理。
 */
object UpdateChecker {

    /** 项目自身的 Release 接口，设置里可改 */
    const val DEFAULT_URL = "https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest"
    private const val TAG = "DuoUpdate"

    /** 自动检查的最小间隔：12 小时，避免每次进设置都请求 GitHub */
    const val CHECK_INTERVAL = 12 * 60 * 60 * 1000L

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
     * 允许直接贴 GitHub 的网页地址（…/releases 或仓库主页），自动换成 API 地址；
     * 已经是 api.github.com 或其它自定义 JSON 接口时原样保留。
     */
    fun normalize(input: String): String {
        val s = input.trim()
        // 已经是 API 地址（或任意自建 JSON 接口）时原样返回。
        // 正则必须锚定开头：否则 "api.github.com" 里的 "github.com" 也会被当成网页地址误改。
        if (s.startsWith("http://api.github.com/", true) ||
            s.startsWith("https://api.github.com/", true)
        ) return s
        val m = Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([^/\s?#]+)/([^/\s?#]+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE
        ).find(s) ?: return s
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        return "https://api.github.com/repos/$owner/$repo/releases/latest"
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
                IllegalArgumentException("更新源地址需要以 http:// 或 https:// 开头")
            )
        }

        val first = request(target, localVersion).mapCatching { parse(it) }
        Log.d(TAG, "主源 $target -> ${first.exceptionOrNull()?.message ?: "成功"}")
        if (first.isSuccess) return@withContext first

        // api.github.com 匿名请求每小时只有 60 次，校园网 / 运营商共享出口很容易撞限。
        // releases.atom 是 GitHub 官方公开订阅源，没有配额限制，自动作为备用源。
        val fallback = atomFallback(target)
        if (fallback != null) {
            val second = request(fallback, localVersion).mapCatching { parse(it) }
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
                        403, 429 -> "GitHub API 触发访问限制（HTTP $code，剩余配额 ${left ?: "?"}）"
                        404 -> "找不到 Release，请检查更新源地址"
                        else -> "服务器返回 HTTP $code"
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

    /** 按内容自动识别：GitHub API JSON / Release 数组 / releases.atom XML / 自建 JSON */
    private fun parse(body: String): ReleaseInfo {
        val t = body.trim()
        return when {
            t.startsWith("<") -> parseAtom(t)
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                if (arr.length() == 0) error("该仓库还没有发布过 Release")
                parseJson(arr.getJSONObject(0))
            }
            else -> parseJson(JSONObject(t))
        }
    }

    /** 单个 Release 对象（/latest），或自建镜像里同结构的 JSON */
    private fun parseJson(o: JSONObject): ReleaseInfo {
        val tag = o.optString("tag_name", "")
        if (tag.isBlank()) error("返回内容不是 Release 信息（缺少 tag_name）")

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

        return ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            name = o.optString("name", tag),
            notes = o.optString("body", ""),
            pageUrl = o.optString("html_url", ""),
            publishedAt = o.optString("published_at", "").take(10),
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize
        )
    }

    /** GitHub releases.atom：无配额限制，但没有 assets 信息，下载统一走 Release 页面 */
    private fun parseAtom(body: String): ReleaseInfo {
        val entry = Regex("""<entry>([\s\S]*?)</entry>""").find(body)?.groupValues?.get(1)
            ?: error("该仓库还没有发布过 Release")
        val pageUrl = Regex("""<link[^>]*href=["']([^"']+)["']""")
            .find(entry)?.groupValues?.get(1) ?: ""
        val tag = Regex("""/releases/tag/([^/?#"']+)""").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""").find(entry)?.groupValues?.get(1)?.trim()
            ?: error("订阅源里找不到版本标签")
        val version = tag.removePrefix("v").removePrefix("V")
        val updated = Regex("""<updated>([^<]+)</updated>""")
            .find(entry)?.groupValues?.get(1)?.take(10) ?: ""
        val raw = Regex("""<content[^>]*>([\s\S]*?)</content>""")
            .find(entry)?.groupValues?.get(1) ?: ""
        // atom 把渲染后的 HTML 做了实体转义：第一次解开实体得到 HTML，第二次再转成纯文本
        val html = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        val notes = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
        return ReleaseInfo(
            tag = tag,
            version = version,
            name = "DuoLauncher v$version",
            notes = notes,
            pageUrl = pageUrl,
            publishedAt = updated,
            apkUrl = "",
            apkName = "",
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
        Toast.makeText(ctx, "已复制$label", Toast.LENGTH_SHORT).show()
    }
}
