package com.duolauncher.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.duolauncher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 在线更新：把 Release 里的 APK 下载到应用私有缓存，校验后调起系统安装器。
 *
 * - 不申请外部存储权限、不写公共目录；文件只通过 FileProvider 临时授权给安装器。
 * - HttpURLConnection 不会自动跨主机 / 跨协议跟随重定向，而 GitHub Release 资产
 *   一定会 302 到 objects.githubusercontent.com，所以这里手动跳。
 * - 支持协程取消：用户关弹窗 / 点取消时立刻停止写文件。
 */
object ApkUpdater {

    private const val DIR = "updates"
    private const val MAX_HOPS = 6
    private const val CHUNK = 16 * 1024
    private const val PROGRESS_STEP = 64 * 1024L

    sealed class State {
        data object Idle : State()
        data class Downloading(val downloaded: Long, val total: Long) : State() {
            val fraction: Float
                get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
        data class Failed(val reason: String) : State()
    }

    /** Android 8+ 需要用户手动授予「安装未知来源应用」 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }
        val n = if (base.endsWith(".apk", true)) base else "$base.apk"
        return n.ifBlank { "update.apk" }
    }

    private fun newConnection(link: String, ctx: Context): HttpURLConnection =
        (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DuoLauncher/${UpdateChecker.currentVersion(ctx)}")
            setRequestProperty("Accept", "application/vnd.android.package-archive, */*")
        }

    /**
     * 下载并校验 APK。进度回调在 IO 线程触发，仅用于刷新界面状态。
     */
    suspend fun download(
        ctx: Context,
        url: String,
        name: String,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            var link = url
            var conn: HttpURLConnection? = null
            var total = expectedSize

            for (hop in 0 until MAX_HOPS) {
                coroutineContext.ensureActive()
                val c = newConnection(link, ctx)
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location")
                            ?: error(Lang.s(R.string.err_download_http, code))
                        link = URL(URL(link), loc).toString()
                        c.disconnect()
                    }
                    code !in 200..299 -> {
                        c.disconnect()
                        error(Lang.s(R.string.err_download_http, code))
                    }
                    else -> {
                        total = c.getHeaderFieldLong("Content-Length", expectedSize)
                        conn = c
                        break
                    }
                }
            }
            val connection = conn ?: error(Lang.s(R.string.err_download_redirect))

            val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, safeName(name))
            val tmp = File(dir, file.name + ".tmp")
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(CHUNK)
                        var written = 0L
                        var last = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - last >= PROGRESS_STEP || (total > 0 && written >= total)) {
                                last = written
                                onProgress(written, total)
                            }
                        }
                        out.flush()
                        onProgress(written, total)
                    }
                }
            } finally {
                connection.disconnect()
            }

            // 只做轻量校验：能解析成 APK、且包名就是自己。签名一致性由系统安装器把关。
            val pi = if (tmp.length() > 1024) {
                ctx.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
            } else null
            if (pi == null || pi.packageName != ctx.packageName) {
                tmp.delete()
                error(Lang.s(R.string.err_download_bad_apk))
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error(Lang.s(R.string.err_download_bad_apk))
            }
            file
        }
    }

    /** 通过 FileProvider 把私有缓存里的 APK 交给系统安装器 */
    fun install(ctx: Context, file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            val main = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(flags)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
            }
            try {
                ctx.startActivity(main)
            } catch (e: Exception) {
                // 少数 ROM 只注册了 ACTION_VIEW，降级再试一次
                ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(flags)
                })
            }
            true
        }.getOrDefault(false)
    }
}
