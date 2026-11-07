package com.duolauncher.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.duolauncher.data.DuoState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置备份 / 恢复。
 *
 * 备份的是整份 `shared_prefs/duo_prefs_v1.xml`，而不是逐个拼字段：
 *  - [DuoState.save] 把主屏布局、侧栏、文件夹、隐藏应用、分类、图标包、图标覆盖、
 *    角标、搜索、更新源等写成一个 "state" 字符串；
 *  - [Lang] 把界面语言写成 "appLang"。
 * 整份导出既不会漏字段，也能把以后新增的设置项原样带回（读不懂的 key 不做处理，但不会丢）。
 *
 * 不包含：通知角标已读记录（`duo_badge_read_v1`，属于进程临时状态）、应用自身数据、
 * 壁纸、通讯录、账号与任何网络上传。读写都走系统文件选择器（SAF），不需要存储权限。
 */
object BackupManager {

    const val MIME = "application/json"
    private const val PREF = "duo_prefs_v1"
    private const val APP_TAG = "DuoLauncher"
    private const val SCHEMA = 1
    /** 备份文件体积上限，避免误选超大文件把内存吃满 */
    private const val MAX_BYTES = 4 * 1024 * 1024
    /** 一次性 UI 标记，不写进备份 */
    private val SKIP_KEYS = setOf("langReopenSettings")

    /** 校验通过后带出的备份摘要，用于导入前的确认弹窗 */
    data class Info(
        val raw: String,
        val exportedAt: String,
        val versionName: String,
        val homeCount: Int,
        val sidebarCount: Int,
        val folderCount: Int,
        val hiddenCount: Int,
        val widgetCount: Int
    )

    fun fileName(): String =
        "DuoLauncher-backup-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"

    /** 生成备份文本；先 [DuoState.save] 保证刚改过、还没落盘的设置也在里面 */
    fun buildJson(ctx: Context, versionName: String): String {
        DuoState.save()
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val prefs = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in SKIP_KEYS) return@forEach
            when (v) {
                is String -> prefs.put(k, v)
                is Boolean -> prefs.put(k, v)
                is Int -> prefs.put(k, v)
                is Long -> prefs.put(k, v)
                is Float -> prefs.put(k, v.toDouble())
                is Set<*> -> prefs.put(k, JSONArray(v.map { it.toString() }))
                else -> { /* 其它类型不写 */ }
            }
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return JSONObject()
            .put("app", APP_TAG)
            .put("schema", SCHEMA)
            .put("exportedAt", now)
            .put("versionName", versionName)
            .put("prefs", prefs)
            .toString(2)
    }

    /** 写进系统文件选择器返回的 Uri，成功返回 true */
    fun writeTo(ctx: Context, uri: Uri, raw: String): Boolean = runCatching {
        val os = ctx.contentResolver.openOutputStream(uri) ?: return@runCatching false
        os.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    /** 从 Uri 读文本；读不到、文件过大都返回 null */
    fun readFrom(ctx: Context, uri: Uri): String? = runCatching {
        val size = ctx.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
            ?: -1L
        if (size > MAX_BYTES) return@runCatching null
        val stream = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
        // 部分文档提供者不返回 SIZE，读取时再兜一层上限，避免误选超大文件把内存吃满
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return@runCatching null
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * 校验是不是一份可用的 DuoLauncher 备份，并读出摘要。
     * 关键约束：必须带 `prefs.state`，且 state 里含 "home" 数组，
     * 避免把同名的其它 JSON / 新版不兼容格式误当成设置导入。
     */
    fun inspect(raw: String): Info? = runCatching {
        val root = JSONObject(raw)
        if (root.optString("app") != APP_TAG) return@runCatching null
        // 未来 schema 升版时拒绝解析，避免用老字段覆盖出不完整的状态
        if (root.optInt("schema", 0) > SCHEMA) return@runCatching null
        val prefs = root.optJSONObject("prefs") ?: return@runCatching null
        val stateRaw = prefs.optString("state", "")
        if (stateRaw.isBlank()) return@runCatching null
        val state = JSONObject(stateRaw)
        if (!state.has("home")) return@runCatching null
        Info(
            raw = raw,
            exportedAt = root.optString("exportedAt", "").ifBlank { "—" },
            versionName = root.optString("versionName", "").ifBlank { "—" },
            homeCount = state.optJSONArray("home")?.length() ?: 0,
            sidebarCount = state.optJSONArray("sidebar")?.length() ?: 0,
            folderCount = state.optJSONArray("folders")?.length() ?: 0,
            hiddenCount = state.optJSONArray("hidden")?.length() ?: 0,
            widgetCount = state.optJSONArray("widgets")?.length() ?: 0
        )
    }.getOrNull()

    /**
     * 覆盖写入整份 prefs 并刷新内存状态。
     * 语言可能存在变化，调用方随后应 rebuild Activity；[Lang.init] 先让取文案的 Context 跟上。
     */
    fun apply(ctx: Context, info: Info): Boolean = runCatching {
        val prefs = JSONObject(info.raw).getJSONObject("prefs")
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val edit = sp.edit().clear()
        prefs.keys().forEach { k ->
            when (val v = prefs.get(k)) {
                is String -> edit.putString(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Double -> edit.putFloat(k, v.toFloat())
                is JSONArray -> edit.putStringSet(
                    k, (0 until v.length()).map { v.optString(it) }.toSet()
                )
                else -> { /* JSONObject.NULL 等忽略 */ }
            }
        }
        // commit 而不是 apply：紧接着就要 reload，必须保证磁盘/内存都已更新
        if (!edit.commit()) return@runCatching false
        DuoState.reload()
        Lang.init(ctx)
        true
    }.getOrDefault(false)
}
