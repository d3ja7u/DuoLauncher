package com.duolauncher.ui

import com.duolauncher.R
import com.duolauncher.util.Lang

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class WidgetBridge(val host: AppWidgetHost, val manager: AppWidgetManager, val context: Context)

val LocalWidgetBridge = staticCompositionLocalOf<WidgetBridge?> { null }

/** 渲染真正的系统 AppWidget。[onLongClick] 用于主屏（长按进入编辑/拖动），不传则保持系统默认行为 */
@Composable
fun SystemWidgetCard(
    appWidgetId: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val bridge = LocalWidgetBridge.current
    if (bridge == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(Lang.s(R.string.widget_service_not_ready), color = glassTextColor(dark)) }
        return
    }
    val info: AppWidgetProviderInfo? = remember(appWidgetId) {
        runCatching { bridge.manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
    }
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
                if (onLongClick != null) v.setOnLongClickListener { onLongClick(); true }
                v
            }.getOrElse {
                android.widget.TextView(ctx).apply { text = Lang.s(R.string.widget_cannot_show) }
            }
        },
        update = { v ->
            if (onLongClick != null) v.setOnLongClickListener { onLongClick(); true }
        }
    )
}

/**
 * 主屏专用：带「长按拿起 → 拖动 → 松手」手势的系统小组件。
 *
 * 为什么手势必须做在这一层：AppWidgetHostView 是真正的 Android View，触摸事件
 * 到了它就进了 View 体系，挂在 Compose 父容器上的 pointerInput 根本收不到
 * （表现为长按拖不动、也弹不出移除菜单）。
 *
 * [sizeDp] 除了约束容器，还会通过 `updateAppWidgetSize` 告诉 provider 自己被放进了
 * 多大的格子——自适应的小组件（时钟、天气、音乐）才会按整行宽度重新排版；
 * 不然它们会一直按 provider 声明的默认尺寸绘制，看起来就是「一大片空里一个小方块」。
 */
@Composable
fun SystemWidgetCardDraggable(
    appWidgetId: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    sizeDp: IntSize? = null,
    onLongPress: (Float, Float) -> Unit = { _, _ -> },
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    /** 参数：本次手势是否真的拖动过（false = 长按后原地松手，调用方弹菜单） */
    onRelease: (Boolean) -> Unit = {},
    onCancel: () -> Unit = {},
    /** 编辑模式：按下即进入拖动，不必再等长按（和拖图标一样跟手） */
    immediateDrag: Boolean = false
) {
    val bridge = LocalWidgetBridge.current
    if (bridge == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(Lang.s(R.string.widget_service_not_ready), color = glassTextColor(dark))
        }
        return
    }
    val info: AppWidgetProviderInfo? = remember(appWidgetId) {
        runCatching { bridge.manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
    }
    if (info == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(Lang.s(R.string.widget_invalid, appWidgetId), color = glassTextColor(dark))
        }
        return
    }
    // 上次已经告知过的尺寸，避免每帧都往 provider 推 options（那会触发其 onAppWidgetOptionsChanged）
    val lastSize = remember(appWidgetId) { intArrayOf(-1, -1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val box = WidgetGestureBox(ctx)
            val hostView: View = runCatching {
                val v = bridge.host.createView(ctx, appWidgetId, info)
                v.setAppWidget(appWidgetId, info)
                sizeDp?.let { s ->
                    applyWidgetSize(v, s.width, s.height)
                    lastSize[0] = s.width; lastSize[1] = s.height
                }
                v
            }.getOrElse {
                android.widget.TextView(ctx).apply { text = Lang.s(R.string.widget_cannot_show) }
            }
            box.setContent(hostView)
            box
        },
        update = { box ->
            box.onLongPress = onLongPress
            box.onDrag = onDrag
            box.onRelease = onRelease
            box.onCancel = onCancel
            box.immediateDrag = immediateDrag
            val hostView = box.content as? AppWidgetHostView ?: return@AndroidView
            val s = sizeDp ?: return@AndroidView
            if (s.width != lastSize[0] || s.height != lastSize[1]) {
                lastSize[0] = s.width; lastSize[1] = s.height
                applyWidgetSize(hostView, s.width, s.height)
            }
        }
    )
}

/** 把小组件实际占用的格子尺寸告知 provider（单位 dp），自适应布局才会跟着变 */
private fun applyWidgetSize(v: AppWidgetHostView, wDp: Int, hDp: Int) {
    val opts = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
    }
    runCatching { v.updateAppWidgetSize(opts, wDp, hDp, wDp, hDp) }
}

/**
 * 承载小组件、并接管长按手势的容器。
 *
 * 行为：
 *  • DOWN / MOVE 照常下发给小组件 —— 点击、内部列表滚动、内部按钮都不受影响；
 *  • 同时起一个长按计时，超时且手指没移动 → 接管手势（回调 [onLongPress]），
 *    并给小组件补一个 ACTION_CANCEL，避免它停在按下态；
 *  • 接管之后的事件不再下发，只回调拖动位移，避免小组件在拖动过程中跟着乱动。
 *
 * dispatchTouchEvent 恒返回 true：手势一旦落到小组件区域就由这里负责，
 * 不再冒泡给上层分页器，否则拖动时会顺带把桌面翻页。
 *
 * 另外在 DOWN 时会 `requestDisallowInterceptTouchEvent(true)`：HorizontalPager 的
 * scrollable 一旦决定接管，会给子 View 补一个 ACTION_CANCEL —— 那会把长按计时器
 * 一起取消，表现就是「按住毫无反应」。手指确实滑动（超过 slop）时再把拦截权交还，
 * 让桌面照常翻页。
 */
private class WidgetGestureBox(context: Context) : FrameLayout(context) {
    var onLongPress: (Float, Float) -> Unit = { _, _ -> }
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    var onRelease: (Boolean) -> Unit = {}
    var onCancel: () -> Unit = {}
    var immediateDrag: Boolean = false

    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    /** 上一帧的**屏幕**坐标。位移必须用屏幕坐标算：
     *  拖动时这个容器本身跟着手指平移，`ev.x/ev.y` 是「相对本容器」的，
     *  会同时被反变换回去，算出来的增量被人为抵消（表现就是拖小组件特别沉、
     *  往下拖很难落到位）。rawX/rawY 是屏幕坐标，不受自身平移影响。 */
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    /** 手指是否已经离开长按判定的容忍范围 */
    private var moved = false
    /** 长按是否已经成立、手势是否已被本容器接管 */
    private var captured = false

    var content: View? = null
        private set

    private val holdRunnable = Runnable { beginCapture() }

    fun setContent(v: View) {
        content = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun beginCapture() {
        if (captured || moved) return
        captured = true
        // 长按成立后这段时间不该翻页，重新锁住父容器（滑动分支可能已经放开了）
        parent?.requestDisallowInterceptTouchEvent(true)
        onLongPress(downX, downY)
        // 手势被接管：给小组件补一个 CANCEL，避免它一直停在按下态
        val now = SystemClock.uptimeMillis()
        runCatching {
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            content?.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun releaseParent() {
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                lastRawX = ev.rawX; lastRawY = ev.rawY
                downRawX = ev.rawX; downRawY = ev.rawY
                moved = false
                captured = false
                // 先按住不放：否则分页器会在 MOVE 时抢走手势并给我们发 CANCEL
                parent?.requestDisallowInterceptTouchEvent(true)
                handler.removeCallbacks(holdRunnable)
                if (immediateDrag) {
                    // 编辑模式：不用等长按，按下就拿起；也不再下发给小组件，
                    // 免得它以为自己被按了一下（内部列表还会跟着滚）
                    beginCapture()
                    return true
                }
                handler.postDelayed(holdRunnable, longPressMs)
                super.dispatchTouchEvent(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - lastRawX
                val dy = ev.rawY - lastRawY
                lastRawX = ev.rawX; lastRawY = ev.rawY
                // 「有没有真的拖动」用离按下点的**屏幕**距离判定（不是逐帧增量，
                // 否则手指来回小幅抖动也会被当成拖动，松手就弹不出菜单）
                if (!moved) {
                    val tx = ev.rawX - downRawX
                    val ty = ev.rawY - downRawY
                    if (tx * tx + ty * ty > touchSlop * touchSlop) moved = true
                }
                if (captured) {
                    if (dx != 0f || dy != 0f) onDrag(dx, dy)
                    return true
                }
                if (moved) {
                    handler.removeCallbacks(holdRunnable)
                    // 是滑动不是长按：把拦截权还给分页器，让桌面正常翻页
                    releaseParent()
                }
                super.dispatchTouchEvent(ev)
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                releaseParent()
                if (captured) {
                    captured = false
                    onRelease(moved)
                    return true
                }
                super.dispatchTouchEvent(ev)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                releaseParent()
                if (captured) {
                    captured = false
                    onCancel()
                    return true
                }
                super.dispatchTouchEvent(ev)
                return true
            }
        }
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }
}
