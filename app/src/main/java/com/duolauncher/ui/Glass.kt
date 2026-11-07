package com.duolauncher.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duolauncher.data.AppInfo
import com.duolauncher.data.DuoState
import com.duolauncher.data.IconPacks
import kotlin.math.roundToInt

/** 取当前系统壁纸作为毛玻璃的模糊底图（动态壁纸时返回 null，自动降级为纯色玻璃） */
@Composable
fun rememberWallpaperBitmap(): Bitmap? {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            val d = WallpaperManager.getInstance(ctx).drawable ?: return@runCatching null
            val w = (d.intrinsicWidth.takeIf { it > 0 } ?: 720).coerceAtMost(720)
            val h = (d.intrinsicHeight.takeIf { it > 0 } ?: 1440).coerceAtMost(1440)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            b
        }.getOrNull()
    }
}

@Composable
fun rememberDrawablePainter(drawable: Drawable): Painter = remember(drawable) {
    object : Painter() {
        override val intrinsicSize: Size
            get() = Size(
                drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
                drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
            )

        override fun DrawScope.onDraw() {
            drawIntoCanvas { canvas ->
                drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
                drawable.draw(canvas.nativeCanvas)
            }
        }
    }
}

/**
 * 应用图标的统一入口：优先用手动指定的图标，其次用图标包自动匹配，最后退回系统图标。
 * 任何地方要画应用图标都走这里，切换图标包 / 单独换图标后全都会同步刷新。
 */
@Composable
fun rememberAppIcon(app: AppInfo): Drawable {
    val ctx = LocalContext.current
    val pack = DuoState.iconPack
    val ov = DuoState.iconOverride[app.id]
    return remember(pack, ov, app.id, app.activityClass, app.icon) {
        runCatching { IconPacks.resolveIcon(ctx, pack, app) }.getOrNull() ?: app.icon
    }
}

/** 全屏毛玻璃背景：模糊壁纸 + 半透明色调层 */
@Composable
fun GlassBackdrop(
    wallpaper: Bitmap?,
    level: Float,
    dark: Boolean,
    modifier: Modifier = Modifier,
    extraDim: Float = 0f
) {
    Box(modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur((6 + 26 * level).dp)
            )
        }
        val tint = if (dark) Color(0xFF10131C) else Color(0xFFF4F7FF)
        Box(
            Modifier
                .fillMaxSize()
                .background(tint.copy(alpha = (0.40f + 0.30f * level + extraDim).coerceIn(0f, 0.96f)))
        )
    }
}

/** 玻璃面板（卡片 / 胶囊 / 对话框） */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    level: Float = 0.55f,
    dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val base = if (dark) Color(0xFF1B2030) else Color.White
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        base.copy(alpha = 0.30f + 0.18f * level),
                        base.copy(alpha = 0.16f + 0.16f * level)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.48f), shape)
    ) {
        // 顶部高光，模拟 iOS 玻璃边缘。
        // 必须用 matchParentSize()（不参与父 Box 尺寸计算）而不是 fillMaxSize()：
        // 后者会把自己的最大高度当成需求，导致没写固定高度的玻璃卡（如侧边栏）
        // 被拉到父容器的可用高度，底下一大片空白玻璃。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.22f), Color.Transparent),
                        endY = 140f
                    )
                )
        )
        content()
    }
}

val onGlassLight = Color(0xFF1A1D26)
val onGlassDark = Color(0xFFF3F6FF)

@Composable
fun glassTextColor(dark: Boolean): Color = if (dark) onGlassDark else onGlassLight

@Composable
fun glassSubTextColor(dark: Boolean): Color =
    if (dark) Color(0xFFB9C1D6) else Color(0xFF4B5262)

fun squircle(percent: Int = 24): RoundedCornerShape = RoundedCornerShape(percent)
