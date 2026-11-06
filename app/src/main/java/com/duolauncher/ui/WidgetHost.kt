package com.duolauncher.ui

import com.duolauncher.R
import com.duolauncher.util.Lang

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class WidgetBridge(val host: AppWidgetHost, val manager: AppWidgetManager, val context: Context)

val LocalWidgetBridge = staticCompositionLocalOf<WidgetBridge?> { null }

/** 渲染真正的系统 AppWidget */
@Composable
fun SystemWidgetCard(appWidgetId: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val bridge = LocalWidgetBridge.current
    if (bridge == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(Lang.s(R.string.widget_service_not_ready), color = glassTextColor(dark)) }
        return
    }
    val info: AppWidgetProviderInfo? = runCatching { bridge.manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
    if (info == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(Lang.s(R.string.widget_invalid, appWidgetId), color = glassTextColor(dark))
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            runCatching {
                val v: AppWidgetHostView = bridge.host.createView(ctx, appWidgetId, info)
                v.setAppWidget(appWidgetId, info)
                v
            }.getOrElse {
                android.widget.TextView(ctx).apply { text = Lang.s(R.string.widget_cannot_show) }
            }
        },
        update = { }
    )
}
