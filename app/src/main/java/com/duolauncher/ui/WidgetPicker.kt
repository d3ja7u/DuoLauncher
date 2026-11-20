package com.duolauncher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duolauncher.R
import com.duolauncher.util.Lang

/**
 * 小组件选择器（替代系统 ACTION_APPWIDGET_PICK）。
 *
 * 系统那个列表只有一行名字，看不出加进来有多大、长什么样，加完才发现占了半屏。
 * 这里每个候选项都带上 provider 自己的预览图，并且**按它声明的格子比例**画预览框
 * （4×2 就是扁的、2×2 是方的、4×4 是高的），选之前就能直观判断占多大地方。
 */
@Composable
fun WidgetPickerDialog(
    dark: Boolean,
    onDismiss: () -> Unit,
    onPick: (AppWidgetProviderInfo) -> Unit
) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val providers = remember {
        runCatching { AppWidgetManager.getInstance(ctx).installedProviders }
            .getOrDefault(emptyList())
            .filter { it.provider != null }
            .sortedBy { runCatching { it.loadLabel(pm).toString().lowercase() }.getOrDefault("") }
    }

    DuoDialog(
        title = Lang.s(R.string.pick_widget_title),
        dark = dark,
        opaque = true,
        onDismiss = onDismiss
    ) {
        if (providers.isEmpty()) {
            Text(
                Lang.s(R.string.pick_widget_empty),
                fontSize = 13.sp,
                color = glassSubTextColor(dark)
            )
            return@DuoDialog
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(providers, key = { it.provider.flattenToString() }) { info ->
                WidgetPickerRow(info, dark) { onPick(info) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            Lang.s(R.string.pick_widget_hint),
            fontSize = 11.sp,
            color = glassSubTextColor(dark)
        )
    }
}

@Composable
private fun WidgetPickerRow(
    info: AppWidgetProviderInfo,
    dark: Boolean,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val cols = cells(info.minWidth)
    val rows = cells(info.minHeight)
    val label = remember(info.provider) {
        runCatching { info.loadLabel(ctx.packageManager).toString() }.getOrNull().orEmpty()
            .ifBlank { info.provider.packageName }
    }
    val preview = remember(info.provider) {
        runCatching { info.loadPreviewImage(ctx, 0) }.getOrNull()
    }
    // 预览框按格子比例画：宽 = 高 × 列/行，太扁或太窄都夹一下，保证看得清
    val boxH = 52.dp
    val boxW = (boxH * (cols.toFloat() / rows.toFloat())).coerceIn(52.dp, 172.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(boxW, boxH)
                .clip(RoundedCornerShape(10.dp))
                .background(if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    painter = rememberDrawablePainter(preview),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                AppIconFallback(ctx, info)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = glassTextColor(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                Lang.s(R.string.pick_widget_size, cols, rows),
                fontSize = 11.sp,
                color = glassSubTextColor(dark)
            )
        }
    }
}

/** provider 没提供预览图时用应用图标兜底 */
@Composable
private fun AppIconFallback(ctx: Context, info: AppWidgetProviderInfo) {
    val icon = remember(info.provider) {
        runCatching { ctx.packageManager.getApplicationIcon(info.provider.packageName) }.getOrNull()
    }
    if (icon != null) {
        Image(
            painter = rememberDrawablePainter(icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            contentScale = ContentScale.Fit
        )
    }
}

/** dp 尺寸换算成格子数：Android 侧一格按 70dp 算（min = 70×n − 30） */
private fun cells(minDp: Int): Int = (((minDp + 30) / 70f).toInt()).coerceIn(1, 6)
