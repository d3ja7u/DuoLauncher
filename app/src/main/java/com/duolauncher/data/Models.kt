package com.duolauncher.data

import android.graphics.drawable.Drawable
import com.duolauncher.R
import com.duolauncher.util.Lang

/** 一个已安装、可启动的应用 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val systemCategory: Int = -1,
    /** 启动 Activity 的完整类名（含包名），用于图标包 appfilter 匹配 */
    val activityClass: String = ""
)

/** 侧边栏条目：type = app | action，value = 包名 或 动作 id */
data class SidebarItem(val type: String, val value: String) {
    val key: String get() = "$type:$value"
    fun encode(): String = "$type\u001F$value"
    companion object {
        fun decode(s: String): SidebarItem {
            val p = s.split("\u001F", limit = 2)
            return SidebarItem(p.getOrElse(0) { "app" }, p.getOrElse(1) { "" })
        }
    }
}

/** 负一屏小组件：type = 内置类型 | system，extra = 系统小组件 id 或文本 */
data class WidgetItem(val type: String, val extra: String = "") {
    fun encode(): String = "$type\u001F$extra"
    companion object {
        fun decode(s: String): WidgetItem {
            val p = s.split("\u001F", limit = 2)
            return WidgetItem(p.getOrElse(0) { "clock" }, p.getOrElse(1) { "" })
        }
    }
}

data class UsageRec(var count: Int = 0, var last: Long = 0L)

/** 主屏文件夹：包含若干应用，以叠加图标形式展示 */
data class FolderItem(
    val id: String,
    val name: String,
    val apps: List<String>
) {
    fun encode(): String = "{\"id\":\"$id\",\"name\":\"$name\",\"apps\":[${apps.joinToString(",") { "\"$it\"" }}]}"
    companion object {
        fun decode(s: String): FolderItem? = runCatching {
            val o = org.json.JSONObject(s)
            FolderItem(
                id = o.optString("id", "f${System.currentTimeMillis()}"),
                name = o.optString("name", Lang.s(R.string.folder)),
                apps = o.optJSONArray("apps")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it, null) }
                } ?: emptyList()
            )
        }.getOrNull()
    }
}

/** 分类表：id -> 名称字符串资源（存 id，切换语言后立即生效） */
val CATEGORY_LIST: List<Pair<String, Int>> = listOf(
    "social" to R.string.cat_social,
    "communication" to R.string.cat_communication,
    "media" to R.string.cat_media,
    "game" to R.string.cat_game,
    "photo" to R.string.cat_photo,
    "work" to R.string.cat_work,
    "life" to R.string.cat_life,
    "tools" to R.string.cat_tools,
    "system" to R.string.cat_system
)

fun categoryName(id: String): String = Lang.s(
    CATEGORY_LIST.firstOrNull { it.first == id }?.second ?: R.string.cat_system
)

/** 智能分类：系统 category + 名称/包名关键词启发式 */
fun guessCategory(label: String, packageName: String, systemCategory: Int): String {
    val s = (label + " " + packageName).lowercase()

    fun has(vararg keys: String) = keys.any { s.contains(it) }

    return when {
        has("game", "游戏", "原神", "王者", "和平精英", "minecraft", "gameloft", "supercell", "taptap") -> "game"
        has("wechat", "微信", "weibo", "微博", "qq", "抖音", "douyin", "tiktok", "xiaohongshu", "小红书", "moments", "instagram", "facebook", "twitter", "discord", "telegram", "soul") -> "social"
        has("mms", "sms", "短信", "信息", "dialer", "phone", "电话", "contacts", "联系人", "messenger", "whatsapp", "line") -> "communication"
        has("camera", "相机", "gallery", "相册", "照片", "photo", "snapseed", "美图", "修图", "lightroom") -> "photo"
        has("music", "音乐", "video", "视频", "bili", "哔哩", "youku", "优酷", "iqiyi", "爱奇艺", "netflix", "spotify", "player", "播放器", "kugou", "酷狗", "qqmusic", "阅读", "reader", "漫画") -> "media"
        has("office", "wps", "word", "excel", "ppt", "文档", "邮箱", "mail", "outlook", "calendar", "日历", "note", "笔记", "notion", "学习", "edu", "course", "词典", "translate", "翻译", "钉钉", "dingtalk", "飞书", "lark", "teams", "zoom") -> "work"
        has("map", "地图", "导航", "amap", "baidu", "高德", "didi", "滴滴", "12306", "铁路", "ctrip", "携程", "meituan", "美团", "eleme", "饿了么", "taobao", "淘宝", "jd", "京东", "pinduoduo", "拼多多", "alipay", "支付宝", "bank", "银行", "weather", "天气", "bus", "公交", "train", "flight", "航旅") -> "life"
        has("settings", "设置", "file", "文件", "manager", "管理器", "clean", "清理", "calculator", "计算器", "clock", "时钟", "compass", "指南针", "recorder", "录音", "browser", "浏览器", "chrome", "download", "下载", "input", "输入法", "vpn", "安全", "security", "tools", "工具") -> "tools"
        systemCategory == 8 /*AUDIO*/ || systemCategory == 7 /*VIDEO*/ || systemCategory == 6 /*IMAGE*/ -> "media"
        systemCategory == 0 /*GAME*/ -> "game"
        systemCategory == 3 /*MAPS*/ || systemCategory == 4 /*NEWS*/ || systemCategory == 5 /*PRODUCTIVITY*/ -> "work"
        systemCategory == 2 /*SOCIAL*/ -> "social"
        else -> "system"
    }
}
