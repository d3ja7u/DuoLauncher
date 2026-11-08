package com.duolauncher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.duolauncher.data.DuoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * iOS 概念图风格的状态指示器（缩小版）。几何按素材像素实测反推，80×80 视图网格。
 *
 *   ╭─16⚡─╮   环形是「电量刻度轨」，不是整圈变色：
 *   │ ((·  │   · 轨道 33°~147° 为底口（放信号点 / 5G），其余 246° 为可填充区间；
 *   ╰· · · ·╯   · 填充从底口左端 147° 顺时针按电量百分比铺开（16% → 只到 186°，
 *              即素材里左下角那一小段红弧；64% → 越过顶部开口到 304°）；
 *             · 未填充部分为 40% 白轨道（素材为浅灰轨道）。
 *
 * 顶部开口 240°~300°（±30°，与素材一致）：电量数字嵌在开口正中（基线 y=20.4），
 * 充电时数字右侧紧跟一枚同色闪电（素材即如此，不在环底）。
 * 底部开口 33°~147°：已连 WiFi → 沿 R28.4 圆弧的 4 个等大信号点（126/102/78.5/54.5°）；
 * 未连 WiFi → 居中 4 根蜂窝条 + 下方放大的 5G。
 *
 * 颜色：填充 = 绿(充电) / 红(<20%) / 白(正常)；内容（数字 / 闪电 / WiFi / 信号点 / 5G）
 * 一律白色 —— 红、绿只染电量弧本身，低电量不会让整块徽标发红。
 * 环 R=30 / 描边 3.6 / 圆头；中心 WiFi 外弧 R21.2（圆心 y=+12.3）/ 内弧 R14.5（圆心 y=+13.9）。
 *
 * 同一套坐标与 duolauncher-preview.html 的 renderSig() 严格对应，保证预览与真机一致。
 * 时间、WiFi/蜂窝、电量均取系统真实数据；不显示安卓原生状态栏。
 */
data class DuoStatus(
    val time: String,
    val wifi: Boolean,
    val cellular: Boolean,
    val level: Int,       // 0..4
    val battery: Int,     // 0..100
    val charging: Boolean
)

/**
 * WiFi 信号格（1..4）：RSSI → 5 档后裁掉 0 档（已连上至少算 1 格）。
 * 读不到（老系统限制 / 权限受限抛异常）时回退 3 格，避免连上却显示成没信号。
 */
private fun readWifiLevel(app: Context): Int {
    var lvl: Int? = null
    runCatching {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val rssi = wm.connectionInfo?.rssi
        if (rssi != null && rssi < 0) lvl = WifiManager.calculateSignalLevel(rssi, 5)
    }
    return (lvl ?: 3).coerceIn(1, 4)
}

/**
 * dBm → 0..4 格。AOSP 的 `SignalStrength.getLevel()` 很保守（LTE 要 RSRP ≥ -98dBm
 * 才算满格），而国产 ROM 的状态栏普遍宽松得多，直接用它会「系统四格全满、徽标只有一两格」。
 * 这里按厂商常见的宽松档位再算一遍，和 getLevel() 取较大值；信号真差时照样掉格。
 */
private fun levelFromDbm(dbm: Int): Int = when {
    dbm >= -110 -> 4
    dbm >= -118 -> 3
    dbm >= -126 -> 2
    dbm >= -135 -> 1
    else -> 0
}

/**
 * 蜂窝信号格（0..4）。
 *
 * 需要 READ_PHONE_STATE：清单已声明，首次启动由 MainActivity 申请一次；用户拒绝时
 * 这里返回 4（四根条全亮，与旧版外观一致），不会每 30 秒抛一次 SecurityException。
 *
 * 三路取最大值，避免「系统满格、徽标一格」：
 *  ① 默认 TelephonyManager 的聚合 level；
 *  ② 每一条 CellSignalStrength 的 level + dBm 换算（5G NSA 下 LTE 锚点弱、NR 强时必要）；
 *  ③ 双卡时逐张活跃 SIM 再取一遍，外加 NetworkCapabilities 里的 dBm 兜底。
 */
private fun readCellularLevel(app: Context): Int {
    if (Build.VERSION.SDK_INT < 28) return 4
    val granted = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    if (!granted) return 4

    var best = 0
    var readAny = false
    fun take(ss: SignalStrength?) {
        ss ?: return
        readAny = true
        best = maxOf(best, ss.level)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                ss.cellSignalStrengths.forEach { c ->
                    best = maxOf(best, c.level, levelFromDbm(c.dbm))
                }
            }
        }
    }

    runCatching {
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        take(tm.signalStrength)
    }
    // 双卡：默认 TelephonyManager 未必是状态栏显示的那张卡
    runCatching {
        val sm = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        sm.activeSubscriptionInfoList?.forEach { info ->
            runCatching {
                val subTm = (app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                    .createForSubscriptionId(info.subscriptionId)
                take(subTm.signalStrength)
            }
        }
    }
    if (best < 4 && Build.VERSION.SDK_INT >= 29) {
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val dbm = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.signalStrength
            if (dbm != null && dbm != Int.MIN_VALUE && dbm < 0) {
                best = maxOf(best, levelFromDbm(dbm))
            }
        }
    }
    // 一次都没读到（机型限制）时保持满格，别把「读不到」显示成「没信号」
    return if (!readAny) 4 else best.coerceIn(0, 4)
}

private fun readStatus(ctx: Context, use24h: Boolean): DuoStatus {
    val app = ctx.applicationContext
    val time = DateFormat
        .format(if (use24h) "HH:mm" else "hh:mm", Calendar.getInstance())
        .toString()

    var wifi = false
    var cellular = false
    var level = 0
    runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps != null) {
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            // 注意：这里不再要求 NET_CAPABILITY_VALIDATED。连上 AP 但没外网时也该有格子，
            // 之前用 online 做门槛，会让「连了 WiFi 但没验证通过」的情况下 level 恒为 0。
            level = when {
                wifi -> readWifiLevel(app)
                cellular -> readCellularLevel(app)
                else -> 0
            }
        }
    }

    var battery = 0
    var charging = false
    runCatching {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            val levelRaw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            battery = ((levelRaw * 100f) / scale).toInt().coerceIn(0, 100)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
    return DuoStatus(time, wifi, cellular, level, battery, charging)
}

/** 每分钟刷新 + 监听时间/网络/电量变化广播 */
@Composable
fun rememberDuoStatus(use24h: Boolean): State<DuoStatus> {
    val ctx = LocalContext.current
    // 组合（主线程）只算一次首帧值。后续所有刷新都走 IO 线程：
    // readStatus 里的 telephony / WiFi 查询是跨进程 binder 调用（172 为了对齐系统
    // 信号格还加了逐 SIM、逐小区遍历），而 RSSI_CHANGED_ACTION 这类广播每秒都会来，
    // 放在主线程执行就是持续掉帧。广播接收器只负责「发起一次后台刷新」。
    val initial = remember(ctx, use24h) { readStatus(ctx, use24h) }
    return produceState(initialValue = initial, ctx, use24h) {
        fun refresh() {
            launch(Dispatchers.IO) {
                val s = runCatching { readStatus(ctx, use24h) }.getOrNull() ?: return@launch
                value = s
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refresh() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        try {
            refresh()
            while (isActive) {
                delay(30_000L)
                refresh()
            }
            awaitCancellation()
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
}

@Composable
fun DuoStatusBar(dark: Boolean, modifier: Modifier = Modifier) {
    val status = rememberDuoStatus(DuoState.status24h).value
    val scale = DuoState.statusScale.coerceIn(0.55f, 1.8f)

    val timeSize = (18 * scale).sp
    val iconSize = (50 * scale).dp
    // 填充色：绿=充电 / 红=<20% / 白=正常
    val fillColor = when {
        status.charging -> Color(0xFF34C759)
        status.battery < 20 -> Color(0xFFFF3B30)
        else -> Color.White
    }
    // 内容色：固定白色。红/绿只用于电量弧本身 —— 数字、WiFi、信号点、5G 始终白色，
    // 低电量时只有电量弧变红，避免整块徽标发红看不清。
    val inkColor = Color.White
    // 轨道色：素材为浅灰轨道，深色壁纸上用 40% 白更稳
    val trackColor = Color.White.copy(alpha = 0.40f)
    val shade = if (dark) Color(0x55000000) else Color(0x77000000)
    val measurer = rememberTextMeasurer()

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = status.time,
            fontSize = timeSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = Color.White,
            style = TextStyle(shadow = Shadow(shade, Offset(0f, 1.8f * scale), 4f * scale))
        )
        Spacer(Modifier.height((6 * scale).dp))
        Canvas(Modifier.size(iconSize)) {
            val side = min(size.width, size.height)
            val k = side / 80f           // 80×80 视图网格 → 像素
            val cx = size.width / 2f
            val cy = size.height / 2f
            val off = 1.1f * k
            val R = 30f * k

            // ── 1. 先量「数字(+闪电)」总宽 ──
            val txt = "${status.battery}"
            val boltW = 7.9f * k
            val boltGap = 0.3f * k
            var numStyle = TextStyle(
                color = inkColor,
                fontSize = (16f * k).toSp(),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.8).sp,
                shadow = Shadow(shade, Offset(0f, 1f * k), 2f * k)
            )
            // 允许的最大数字宽：顶口弦长上限 30.9 - 两侧留白，充电时再让出闪电位
            val allowed = 26.9f * k - if (status.charging) boltGap + boltW else 0f
            var num = measurer.measure(AnnotatedString(txt), numStyle)
            if (num.size.width > allowed) {
                val f = 16f * allowed / num.size.width
                numStyle = numStyle.copy(fontSize = (f * k).toSp())
                num = measurer.measure(AnnotatedString(txt), numStyle)
            }
            val numW = num.size.width
            val total = numW + if (status.charging) boltGap + boltW else 0f

            // ── 2. 顶口角度：素材固定 ±30°，数字过宽时自动再收放 ──
            val edge = 2.0f * k
            val half = Math.toDegrees(
                kotlin.math.asin(((total / 2f + edge) / R).coerceIn(0f, 1f).toDouble())
            ).toFloat()
            val th = maxOf(30f, half)     // 半角 ≥ 30° → 顶口 ≥ 60°
            val leftEnd = 270f - th       // 240°
            val rightStart = 270f + th    // 300°
            val botStart = 147f           // 底口 33°~147°
            val botEnd = 33f
            val span = botEnd + 360f - botStart   // 246°

            // ── 3. 轨道：整段 246° 分左右两段（顶部让开数字）──
            drawRingArc(cx, cy, k, trackColor, shade, off, botStart, leftEnd - botStart, false)
            drawRingArc(cx, cy, k, trackColor, shade, off, rightStart, botEnd + 360f - rightStart, false)

            // ── 4. 电量填充：从底口左端 147° 顺时针按百分比铺开，遇顶部开口断开 ──
            val sweep = span * status.battery.coerceIn(0, 100) / 100f
            val fillEnd = botStart + sweep
            if (sweep > 0.6f) {
                val s1 = (minOf(fillEnd, leftEnd) - botStart)
                if (s1 > 0.6f) {
                    drawRingArc(cx, cy, k, fillColor, shade, off, botStart, s1, true)
                }
                if (fillEnd > rightStart) {
                    val s2 = (minOf(fillEnd, botEnd + 360f) - rightStart)
                    if (s2 > 0.6f) {
                        drawRingArc(cx, cy, k, fillColor, shade, off, rightStart, s2, true)
                    }
                }
            }

            // ── 5. 数字居中（整体含闪电），充电时右侧紧跟白色闪电 ──
            val startX = cx - total / 2f
            drawText(
                textLayoutResult = num,
                topLeft = Offset(startX, cy - 19.6f * k - num.firstBaseline)
            )
            if (status.charging) {
                drawBoltGlyph(
                    bx = startX + numW + boltGap + boltW / 2f,
                    by = cy - 25.8f * k,
                    k = k, color = inkColor, shade = shade, off = off
                )
            }

            // ── 6. 中心：WiFi 双弧（已连）/ 蜂窝信号条（未连，垂直居中）──
            if (status.wifi) drawWifiGlyph(cx, cy, k, inkColor, shade, off)
            else drawCellularGlyph(cx, cy, k, inkColor, shade, off, status.level)

            // ── 7. 底部：已连 WiFi→沿弧排列的 4 点（按信号强度点亮）；未连→放大的 5G ──
            if (status.wifi) {
                drawSignalDots(cx, cy, k, inkColor, shade, off, status.level)
            } else {
                val g5Style = TextStyle(
                    color = inkColor,
                    fontSize = (17.5f * k).toSp(),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    shadow = Shadow(shade, Offset(0f, 0.8f * k), 1.8f * k)
                )
                val g5 = measurer.measure(AnnotatedString("5G"), g5Style)
                drawText(
                    textLayoutResult = g5,
                    topLeft = Offset(cx - g5.size.width / 2f, cy + 30f * k - g5.firstBaseline)
                )
            }
        }
    }
}

/** 圆环单段弧：R=30 / 描边 3.6 / 圆头；起点角 + 扫过角 */
private fun DrawScope.drawRingArc(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float,
    startAngle: Float, sweep: Float, shadow: Boolean = true
) {
    val r = 30f * k
    val topLeft = Offset(cx - r, cy - r)
    val sz = Size(r * 2, r * 2)
    if (shadow) {
        drawArc(shade, startAngle, sweep, false, topLeft + Offset(0f, off), sz,
            style = Stroke(3.6f * k, cap = StrokeCap.Round))
    }
    drawArc(color, startAngle, sweep, false, topLeft, sz,
        style = Stroke(3.6f * k, cap = StrokeCap.Round))
}

/** 任意半径的双色弧（WiFi 用）：圆心 + 半径 + 起角 + 扫角，均按视图单位 */
private fun DrawScope.drawArcAt(
    ccx: Float, ccy: Float, r: Float, k: Float, color: Color, shade: Color, off: Float,
    startAngle: Float, sweep: Float
) {
    val topLeft = Offset(ccx - r, ccy - r)
    val sz = Size(r * 2, r * 2)
    drawArc(shade, startAngle, sweep, false, topLeft + Offset(0f, off), sz,
        style = Stroke(3.6f * k, cap = StrokeCap.Round))
    drawArc(color, startAngle, sweep, false, topLeft, sz,
        style = Stroke(3.6f * k, cap = StrokeCap.Round))
}

/**
 * WiFi：外弧 R21.2（圆心 y=+12.3）/ 内弧 R14.5（圆心 y=+13.9）+ 圆点，整体关于圆心略下 0.5
 * 素材实测：外弧弦长 51px、内弧 32px、点径 11px（按环外径 107px 折算到 80 网格）。
 */
private fun DrawScope.drawWifiGlyph(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float
) {
    // 外弧：以 (cx, cy+12.3) 为心、R21.2，半角 39°（231°→309°）
    drawArcAt(cx, cy + 12.3f * k, 21.2f * k, k, color, shade, off, 231f, 78f)
    // 内弧：以 (cx, cy+13.9) 为心、R14.5，半角 32°（238°→302°）
    drawArcAt(cx, cy + 13.9f * k, 14.5f * k, k, color, shade, off, 238f, 64f)
    // 圆点
    drawCircle(shade, radius = 3.3f * k, center = Offset(cx, cy + 8.3f * k + off))
    drawCircle(color, radius = 3.3f * k, center = Offset(cx, cy + 8.3f * k))
}

/**
 * 蜂窝：4 根递增圆角竖条，底边 y=+8.3、整体垂直居中于圆心（素材信号条即居中），
 * 条宽 3.7 / 间距 2.8（步距 6.5）/ 高 9.4·11.5·13.5·15.6。
 *
 * [level] 为真实信号格 0..4：前 level 根用实心白，其余降到 26% 白当作「未点亮」，
 * 这样 5G 状态下中间这几根条会随手机信号变化，而不是永远四根全亮。
 */
private fun DrawScope.drawCellularGlyph(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float, level: Int
) {
    val barW = 3.7f * k
    val pitch = 6.5f * k
    val baseY = cy + 8.3f * k
    val totalW = barW * 4 + (pitch - barW) * 3
    val startX = cx - totalW / 2f
    listOf(9.4f, 11.5f, 13.5f, 15.6f).forEachIndexed { i, h ->
        val bh = h * k
        val x = startX + i * pitch
        val y = baseY - bh
        val rad = androidx.compose.ui.geometry.CornerRadius(barW * 0.45f)
        val on = i < level.coerceIn(0, 4)
        if (on) drawRoundRect(shade, topLeft = Offset(x, y + off), size = Size(barW, bh), cornerRadius = rad)
        drawRoundRect(
            if (on) color else color.copy(alpha = 0.26f),
            topLeft = Offset(x, y), size = Size(barW, bh), cornerRadius = rad
        )
    }
}

/**
 * 已连 WiFi：沿 R=28.4 圆弧排列的 4 个等大信号点（126/102/78.5/54.5°，半径 3.5）。
 * 素材实测：4 点同在一条以圆心为心的弧上，点径一致，故不再做由大到小的渐隐。
 *
 * [level] 为 WiFi 信号格 1..4：从左（126°）往右依次点亮 level 个，其余 26% 白。
 */
private fun DrawScope.drawSignalDots(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float, level: Int
) {
    val dots = listOf(126f, 102f, 78.5f, 54.5f)
    val lit = level.coerceIn(0, 4)
    dots.forEachIndexed { i, deg ->
        val a = Math.toRadians(deg.toDouble())
        val x = cx + 28.4f * k * cos(a).toFloat()
        val y = cy + 28.4f * k * sin(a).toFloat()
        val on = i < lit
        if (on) drawCircle(shade, radius = 3.5f * k, center = Offset(x, y + off))
        drawCircle(
            if (on) color else color.copy(alpha = 0.26f),
            radius = 3.5f * k, center = Offset(x, y)
        )
    }
}

/**
 * 充电闪电：紧跟在电量数字右侧（与素材一致），尺寸 7.2×14、略向右倾 12°。
 * 素材中闪电宽约为数字宽度的 45%、高约为字高的 1.18 倍，此处按此比例定尺。
 * [bx]/[by] 为闪电中心点。
 */
private fun DrawScope.drawBoltGlyph(
    bx: Float, by: Float, k: Float, color: Color, shade: Color, off: Float
) {
    fun ux(v: Float) = bx + v * k
    fun uy(v: Float) = by + v * k
    // 斜向闪电：局部坐标顺时针旋转 12°，形状与预览页 SVG 完全一致
    val a = Math.toRadians(12.0)
    val ca = cos(a).toFloat()
    val sa = sin(a).toFloat()
    fun rx(x: Float, y: Float) = ux(x * ca - y * sa)
    fun ry(x: Float, y: Float) = uy(x * sa + y * ca)
    val p = Path().apply {
        moveTo(rx(2.9f, -7.0f), ry(2.9f, -7.0f))
        lineTo(rx(-3.3f, 0.9f), ry(-3.3f, 0.9f))
        lineTo(rx(0.2f, 0.9f), ry(0.2f, 0.9f))
        lineTo(rx(-2.7f, 7.0f), ry(-2.7f, 7.0f))
        lineTo(rx(3.9f, -0.5f), ry(3.9f, -0.5f))
        lineTo(rx(0.4f, -0.5f), ry(0.4f, -0.5f))
        close()
    }
    withTransform({ translate(0f, off) }) { drawPath(p, shade) }
    drawPath(p, color)
}
