package com.duolauncher.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.duolauncher.R
import java.net.URLEncoder

data class SearchEngine(
    val id: String,
    /** 存资源 id，切换语言后立即生效 */
    @StringRes val labelRes: Int,
    val baseUrl: String
)

object SearchEngines {
    val ALL: List<SearchEngine> = listOf(
        SearchEngine("baidu", R.string.engine_baidu, "https://www.baidu.com/s?wd="),
        SearchEngine("bing", R.string.engine_bing, "https://www.bing.com/search?q="),
        SearchEngine("google", R.string.engine_google, "https://www.google.com/search?q=")
    )

    val RECENT_COUNTS: List<Int> = listOf(5, 10, 15, 20)

    fun of(id: String): SearchEngine = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun label(id: String): String = Lang.s(of(id).labelRes)

    fun url(id: String, query: String): String =
        of(id).baseUrl + URLEncoder.encode(query.trim(), "UTF-8")

    /** 用系统默认浏览器打开，绝不在应用内自更新/内嵌浏览 */
    fun open(ctx: Context, id: String, query: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url(id, query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
