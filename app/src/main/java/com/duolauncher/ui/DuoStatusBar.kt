package com.duolauncher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SignalStrength
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.duolauncher.data.DuoState
import com.duolauncher.util.WallpaperRotator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 颜色：填充 = 黄(省电模式) / 绿(充电) / 红(<20%) / 白(正常)；内容（数字 / 闪电 /
 * WiFi / 信号点 / 制式文字）一律白色 —— 颜色只染电量弧本身。
 * 连接耳机或个人热点时，中间的 WiFi 双弧分别换成耳机 / 共享链环图标，底部信号点不变。
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
    val charging: Boolean,
    /**
     * 每张「已启用」SIM 卡各自的信号格，按卡槽顺序（卡1、卡2…）。
     * size >= 2 时徽标改用双排信号点；单卡 / 读不到时留空，保持原来的单排样式。
     */
    val simLevels: List<Int> = emptyList(),
    /** 底部网络制式文字：按真实网络自动识别（2G/3G/LTE/4G/5G/5GA/5GUW） */
    val netLabel: String = "5G",
    /** 已连接耳机（有线 / 蓝牙 / USB）：中间的 WiFi 双弧换成耳机图标 */
    val headphones: Boolean = false,
    /** 已开启个人热点：中间的 WiFi 双弧换成共享链环图标 */
    val hotspot: Boolean = false,
    /** 系统省电模式：电量弧改黄色 */
    val powerSave: Boolean = false,
    /** 电量已充满（BATTERY_STATUS_FULL）；充满时充电图标用插头而非闪电 */
    val full: Boolean = false
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

/**
 * 逐张已启用 SIM 卡读取信号格（0..4），按 simSlotIndex 排序。
 *
 * 只有「开关打开、能被 SubscriptionManager 列为 active」的卡才会返回；只插一张卡、
 * 或用户关掉其中一张时，列表就只有 1 项 —— 徽标据此自动回到单排样式。
 * 读不到（无权限 / ROM 限制）时留空，绝不凭空造出第二张卡。
 */
private fun readSimLevels(app: Context): List<Int> {
    if (Build.VERSION.SDK_INT < 28) return emptyList()
    val granted = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    if (!granted) return emptyList()
    return runCatching {
        val sm = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val infos = sm.activeSubscriptionInfoList
            ?.sortedBy { it.simSlotIndex } ?: emptyList()
        infos.map { info ->
            runCatching {
                val tm = (app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                    .createForSubscriptionId(info.subscriptionId)
                val ss = tm.signalStrength
                var best = ss?.level ?: 0
                if (Build.VERSION.SDK_INT >= 30) {
                    ss?.cellSignalStrengths?.forEach { c ->
                        best = maxOf(best, c.level, levelFromDbm(c.dbm))
                    }
                }
                best.coerceIn(0, 4)
            }.getOrDefault(4)   // 单卡读不到时不如显示满格，避免误报「无信号」
        }
    }.getOrDefault(emptyList())
}

/** 是否连着耳机：有线 / 蓝牙 / USB / BLE 耳机都算 */
private fun readHeadphones(app: Context): Boolean = runCatching {
    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
        when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }
}.getOrDefault(false)

/**
 * 个人热点是否开启。系统没有公开 API：优先反射 WifiManager 的隐藏方法，
 * 隐藏接口被限制时退回「存在一个不带 INTERNET 能力的 WiFi 本地网络」判断。
 */
private fun readHotspot(app: Context): Boolean {
    runCatching {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
        m.isAccessible = true
        (m.invoke(wm) as? Boolean)?.let { return it }
    }
    runCatching {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val m = WifiManager::class.java.getDeclaredMethod("getWifiApState")
        m.isAccessible = true
        if ((m.invoke(wm) as? Int) == 13) return true   // WIFI_AP_STATE_ENABLED
    }
    return runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrDefault(false)
}

private fun readPowerSave(app: Context): Boolean = runCatching {
    (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
}.getOrDefault(false)

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
    var full = false
    runCatching {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            val levelRaw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            battery = ((levelRaw * 100f) / scale).toInt().coerceIn(0, 100)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            full = status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
    return DuoStatus(
        time, wifi, cellular, level, battery, charging, readSimLevels(app),
        com.duolauncher.util.NetworkLabelResolver.label(app, DuoState.status4gLabel),
        readHeadphones(app), readHotspot(app), readPowerSave(app), full
    )
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
            // 插拔 / 启用停用 SIM、飞行模式切换时立即重算双卡信号，不必等 30 秒轮询
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            // 耳机插拔 / 蓝牙耳机 / 个人热点 / 省电模式变化时立即刷新，不必等 30 秒轮询
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
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
    val netLabel = status.netLabel
    // 关掉电量数字后，顶部不再为数字留缺口，圆环连成完整一段
    val showNum = DuoState.showBatteryNumber

    val timeSize = (18 * scale).sp
    val iconSize = (50 * scale).dp
    val measurer = rememberTextMeasurer()

    // 只取「时间 + 三合一」正下方那块壁纸区域的明暗，不参考壁纸其他位置。
    // onGloballyPositioned 拿到的就是这两个元素的外接矩形（徽标在右上角也能正确对应）。
    var blockBounds by remember { mutableStateOf<Rect?>(null) }
    val wallpaperDark = rememberWallpaperInk(blockBounds).value
    // 白图标配黑阴影、黑图标配白阴影，两种底色下轮廓都清楚
    val shade = if (wallpaperDark) Color(0x55000000)
    else Color.White.copy(alpha = if (dark) 0.38f else 0.48f)

    // 填充色：黄=省电模式 / 绿=充电 / 红=<20% / 跟随壁纸自适应=正常。省电模式优先，与系统一致
    val fillColor = when {
        status.powerSave -> Color(0xFFFFCC00)
        status.charging -> Color(0xFF34C759)
        status.battery < 20 -> Color(0xFFFF3B30)
        else -> if (wallpaperDark) Color.White else Color.Black
    }
    // 内容色（数字 / WiFi / 信号点 / 制式文字 / 充电图标）：根据壁纸区域明暗自动白/黑，
    // 保证在任何壁纸上都看得清；红/绿只用于电量弧本身，不影响内容色。
    val inkColor = if (wallpaperDark) Color.White else Color.Black
    // 轨道色：与内容色同色系、40% 透明度，深色/亮色壁纸上都稳
    val trackColor = inkColor.copy(alpha = 0.40f)

        Column(
        modifier.then(Modifier.onGloballyPositioned { blockBounds = it.boundsInWindow() }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = status.time,
            fontSize = timeSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = inkColor,
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
            // 隐藏电量数字时不留顶口，两段弧在 270° 处对接，上半圈连成完整一段
            val th = if (showNum) maxOf(30f, half) else 0f
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

            // ── 5. 电量数字 + 充电 / 满电图标 ──
            // 充满（仍连着电）显示插头，未充满的充电显示闪电；「关闭数字」时二者也照常显示。
            val chargeGlyph = when {
                status.full -> 2      // 插头
                status.charging -> 1  // 闪电
                else -> 0             // 无
            }
            if (showNum) {
                val startX = cx - total / 2f
                drawText(
                    textLayoutResult = num,
                    topLeft = Offset(startX, cy - 19.6f * k - num.firstBaseline)
                )
                if (chargeGlyph == 1) {
                    drawBoltGlyph(
                        bx = startX + numW + boltGap + boltW / 2f,
                        by = cy - 25.8f * k,
                        k = k, color = inkColor, shade = shade, off = off
                    )
                } else if (chargeGlyph == 2) {
                    drawPlugGlyph(
                        bx = startX + numW + boltGap + boltW / 2f,
                        by = cy - 25.8f * k,
                        k = k, color = inkColor, shade = shade, off = off
                    )
                }
            } else {
                // 关掉数字：顶部圆环连成完整一段，充电 / 满电图标居中显示在环内上部
                if (chargeGlyph == 1) {
                    drawBoltGlyph(bx = cx, by = cy - 25.8f * k, k = k, color = inkColor, shade = shade, off = off)
                } else if (chargeGlyph == 2) {
                    drawPlugGlyph(bx = cx, by = cy - 25.8f * k, k = k, color = inkColor, shade = shade, off = off)
                }
            }

            // ── 6 / 7. 中心 + 底部 ──
            // 双卡（两张卡都处于启用状态）时信号区改成参考图的「双排 4 点」：
            //   上排 = 卡1、下排 = 卡2，各按自己的真实信号格点亮。
            //   连 WiFi → WiFi 双弧保留，双排点移到下半部的信号点位置；
            //   仅蜂窝 → 双排点居中替代原来的 4 根竖条，底部仍是制式文字。
            // 单卡、或读不到 SIM 列表时，一律走原来的画法，外观完全不变。
            val dualSim = status.simLevels.size >= 2
            // 中间图标优先级：个人热点 > 耳机 > WiFi / 蜂窝。前两者占据 WiFi 双弧的位置，
            // 替换后底部信号点仍按当前信号格点亮，与参考图一致。
            val specialGlyph = when {
                status.hotspot -> 2
                status.headphones -> 1
                else -> 0
            }
            if (specialGlyph != 0) {
                if (specialGlyph == 2) drawHotspotGlyph(cx, cy, k, inkColor, shade, off)
                else drawHeadphonesGlyph(cx, cy, k, inkColor, shade, off)
                if (dualSim) {
                    drawDualSimDots(cx, cy, k, inkColor, shade, off, status.simLevels, center = false)
                } else {
                    drawSignalDots(cx, cy, k, inkColor, shade, off, status.level)
                }
                if (!status.wifi) drawNetLabel(measurer, netLabel, cx, cy, k, inkColor, shade)
            } else if (status.wifi) {
                drawWifiGlyph(cx, cy, k, inkColor, shade, off)
                if (dualSim) {
                    drawDualSimDots(cx, cy, k, inkColor, shade, off, status.simLevels, center = false)
                } else {
                    drawSignalDots(cx, cy, k, inkColor, shade, off, status.level)
                }
            } else {
                if (dualSim) {
                    drawDualSimDots(cx, cy, k, inkColor, shade, off, status.simLevels, center = true)
                } else {
                    drawCellularGlyph(cx, cy, k, inkColor, shade, off, status.level)
                }
                drawNetLabel(measurer, netLabel, cx, cy, k, inkColor, shade)
            }
        }
    }
}

/** 底部网络制式文字：5GUW / 5GA 这类长标签按可用宽度自动收字号 */
private fun DrawScope.drawNetLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    label: String, cx: Float, cy: Float, k: Float, color: Color, shade: Color
) {
    val maxLabelW = 27f * k
    var style = TextStyle(
        color = color,
        fontSize = (17.5f * k).toSp(),
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        shadow = Shadow(shade, Offset(0f, 0.8f * k), 1.8f * k)
    )
    var layout = measurer.measure(AnnotatedString(label), style)
    if (layout.size.width > maxLabelW) {
        val f = 17.5f * maxLabelW / layout.size.width
        style = style.copy(fontSize = (f * k).toSp())
        layout = measurer.measure(AnnotatedString(label), style)
    }
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(cx - layout.size.width / 2f, cy + 30f * k - layout.firstBaseline)
    )
}

/** 耳机：头梁弧 + 左右耳罩，取代 WiFi 双弧 */
private fun DrawScope.drawHeadphonesGlyph(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float
) {
    drawArcAt(cx, cy + 9f * k, 12f * k, k, color, shade, off, 205f, 130f)
    val ew = 7.5f * k
    val eh = 12f * k
    val ey = cy + 5f * k
    val corner = androidx.compose.ui.geometry.CornerRadius(3f * k)
    listOf(cx - 16f * k, cx + 8.5f * k).forEach { x ->
        drawRoundRect(shade, topLeft = Offset(x, ey + off), size = Size(ew, eh), cornerRadius = corner)
        drawRoundRect(color, topLeft = Offset(x, ey), size = Size(ew, eh), cornerRadius = corner)
    }
}

/** 个人热点：两枚相扣的圆环（共享 / 链接），取代 WiFi 双弧 */
private fun DrawScope.drawHotspotGlyph(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float
) {
    val r = 7.2f * k
    val y = cy + 8f * k
    drawArcAt(cx - 5.2f * k, y, r, k, color, shade, off, 0f, 360f)
    drawArcAt(cx + 5.2f * k, y, r, k, color, shade, off, 0f, 360f)
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
 * 双卡信号点：两排各 4 个等大圆点，上排 = 卡1、下排 = 卡2，各按自己的信号格点亮
 * （点亮 = 实心白，未点亮 = 26% 白），与单卡时 4 点的视觉语言保持一致。
 *
 * [center] = true  未连 WiFi：放在环内中央，替代原来的 4 根蜂窝竖条；
 * [center] = false 已连 WiFi：放在下半部信号点区域，替代原来的沿弧 4 点，
 *                  上方仍是 WiFi 双弧。
 *
 * 坐标按 80×80 视图网格：点距 8 / 点径 3 / 行距 8，整体落在底口 33°~147° 的
 * 空白扇区内，不会压到电量弧，也不会和 WiFi 圆点（y=+8.3）或底部制式文字相撞。
 */
private fun DrawScope.drawDualSimDots(
    cx: Float, cy: Float, k: Float, color: Color, shade: Color, off: Float,
    levels: List<Int>, center: Boolean
) {
    val pitch = 8f * k
    val rowGap = 8f * k
    val radius = 3f * k
    val baseY = if (center) cy - 1.5f * k else cy + 16.5f * k
    listOf(baseY, baseY + rowGap).forEachIndexed { row, y ->
        val lit = levels.getOrElse(row) { 0 }.coerceIn(0, 4)
        for (i in 0 until 4) {
            val x = cx + (i - 1.5f) * pitch
            val on = i < lit
            if (on) drawCircle(shade, radius, Offset(x, y + off))
            drawCircle(
                if (on) color else color.copy(alpha = 0.26f),
                radius, Offset(x, y)
            )
        }
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

/**
 * 充满电插头：两枚插脚 + 壳体 + 底部电源线，尺寸与闪电相仿（约 7×14），居中绘制于 [bx]/[by]。
 * 充电未充满用闪电，充满（仍连着电）改用此插头，一眼区分「正在充」和「已充满」。
 */
private fun DrawScope.drawPlugGlyph(
    bx: Float, by: Float, k: Float, color: Color, shade: Color, off: Float
) {
    fun ux(v: Float) = bx + v * k
    fun uy(v: Float) = by + v * k
    val cr = androidx.compose.ui.geometry.CornerRadius(0.5f * k)
    // 两枚插脚（顶部）
    val prongW = 1.3f * k
    val prongH = 3.0f * k
    listOf(-1.7f, 0.4f).forEach { px ->
        val x = ux(px) - prongW / 2f
        val y = uy(-7.0f)
        drawRoundRect(shade, topLeft = Offset(x, y + off), size = Size(prongW, prongH), cornerRadius = cr)
        drawRoundRect(color, topLeft = Offset(x, y), size = Size(prongW, prongH), cornerRadius = cr)
    }
    // 壳体（中部圆角方块）
    val bodyW = 7.2f * k
    val bodyH = 7.6f * k
    val bodyR = androidx.compose.ui.geometry.CornerRadius(2.2f * k)
    val b0x = ux(0f) - bodyW / 2f
    val b0y = uy(-3.8f)
    drawRoundRect(shade, topLeft = Offset(b0x, b0y + off), size = Size(bodyW, bodyH), cornerRadius = bodyR)
    drawRoundRect(color, topLeft = Offset(b0x, b0y), size = Size(bodyW, bodyH), cornerRadius = bodyR)
    // 底部电源线（短圆角条）
    val cordW = 2.0f * k
    val cordH = 2.2f * k
    val cordR = androidx.compose.ui.geometry.CornerRadius(1f * k)
    val c0x = ux(0f) - cordW / 2f
    val c0y = uy(4.0f)
    drawRoundRect(shade, topLeft = Offset(c0x, c0y + off), size = Size(cordW, cordH), cornerRadius = cordR)
    drawRoundRect(color, topLeft = Offset(c0x, c0y), size = Size(cordW, cordH), cornerRadius = cordR)
}

private const val TAG_INK = "DuoInk"

/**
 * 只求「时间 + 三合一」正下方那一小块壁纸的平均灰度，壁纸其他区域一律不参与判断。
 * bounds 为 null（首帧尚未布局）时返回 -1，宁可这一轮不采样、等布局完成再算，
 * 也不拿「顶部居中」之类猜出来的区域凑数 —— 徽标在右上角，猜会完全错位。
 */
private fun regionLuma(bmp: Bitmap, bounds: Rect?, sw: Int, sh: Int): Float {
    if (bounds == null || bounds.width <= 0f || bounds.height <= 0f) return -1f
    val bw = bmp.width
    val bh = bmp.height
    // 四周各内缩 8%，进一步排除徽标边框外的背景
    val ix = bounds.width * 0.08f
    val iy = bounds.height * 0.08f
    val x0 = ((bounds.left + ix) / sw * bw).toInt().coerceIn(0, bw - 1)
    val x1 = ((bounds.right - ix) / sw * bw).toInt().coerceIn(x0 + 1, bw)
    val y0 = ((bounds.top + iy) / sh * bh).toInt().coerceIn(0, bh - 1)
    val y1 = ((bounds.bottom - iy) / sh * bh).toInt().coerceIn(y0 + 1, bh)
    var sum = 0L
    var n = 0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            n++
        }
    }
    return if (n == 0) -1f else sum.toFloat() / n
}

/**
 * 取色数据源优先级：
 * ① 本应用自动切换的壁纸：直接对「当前这张」按屏幕比例 cover 出缩略图采样。
 *    原实现只走 WallpaperManager.getDrawable()，在部分 ROM 上会因权限 / 内部缓存
 *    返回 null 或旧图，导致换壁纸后图标颜色永远不变 —— 现在这条路径与系统服务解耦。
 * ② 用户从图库手动设置的静态壁纸：系统壁纸 drawable。
 * ③ 动态壁纸等读不到位图的场合：系统算好的 WallpaperColors 提示。
 */
private fun computeRegionLuma(ctx: Context, bounds: Rect?): Float? {
    if (bounds == null) return null
    val dm = ctx.resources.displayMetrics
    val sw = dm.widthPixels.coerceAtLeast(1)
    val sh = dm.heightPixels.coerceAtLeast(1)
    val bw = 256
    val bh = (bw.toLong() * sh / sw).toInt().coerceAtLeast(1)

    // ① 本应用设置的壁纸：精确、与系统壁纸服务的缓存 / ROM 权限无关
    val own = if (DuoState.wallpaperOwned) runCatching {
        val t = WallpaperRotator.currentThumbnail(ctx, bw, bh) ?: return@runCatching null
        val v = regionLuma(t, bounds, sw, sh)
        t.recycle()
        if (v < 0f) null else v
    }.getOrNull() else null

    // ② 系统壁纸 drawable：用于识别「用户从图库换了壁纸」并取那块区域的真实明暗
    val sys = runCatching {
        val wm = WallpaperManager.getInstance(ctx.applicationContext)
        val d = wm.getDrawable() ?: wm.peekDrawable() ?: return@runCatching null
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bw, bh)
        d.draw(android.graphics.Canvas(bmp))
        val v = regionLuma(bmp, bounds, sw, sh)
        bmp.recycle()
        if (v < 0f) null else v
    }.getOrElse {
        // 部分 ROM 读系统壁纸位图要求运行时存储权限，取不到属正常；
        // 此时以自有图片采样为准，不为此申请额外权限。
        Log.d(TAG_INK, "system sample unavailable: ${it.message}")
        null
    }

    // 两者明显不同 → 用户在别处换了壁纸，改采系统壁纸；刚由本应用 setBitmap 后的
    // 3 秒内不这么判，避免读到系统服务尚未刷新的旧缓存。
    // 全程只用「徽标底下」这一块区域，不看壁纸其他位置、也不做整屏亮度兜底。
    val external = own != null && sys != null &&
        kotlin.math.abs(own - sys) > 45f &&
        (WallpaperRotator.lastSetAt == 0L ||
            System.currentTimeMillis() - WallpaperRotator.lastSetAt > 3000L)
    val picked = when {
        external -> sys
        own != null -> own
        sys != null -> sys
        else -> null
    }
    Log.d(TAG_INK, "own=$own sys=$sys external=$external rect=$bounds")
    return picked
}

/**
 * 跟随「徽标底下」区域明暗自动选图标颜色（白 / 黑），true = 偏暗 → 用白色。
 *
 * 计算时机（只在这几种「壁纸真的变了 / 首帧」时，其余一律不重算，省电）：
 *   ① 首帧拿到真实 bounds：用当前壁纸采样一次作为初始值；
 *   ② 本应用自动 / 上滑 / 立即切换壁纸（wallpaperVersion 递增）；
 *   ③ 系统壁纸颜色变化（含用户从图库手动换壁纸）；
 *   ④ 回到桌面 ON_RESUME（兜底，壁纸可能在别处被换过）。
 *
 * 主屏左右翻页（HorizontalPager）不会触发重算：翻页时徽标的屏幕坐标不变，且
 * bounds 不再作为任何 Launched/Disposable 的 key，页面重组 / 相邻页 mount 都不会
 * 重新解码位图；全应用只维护一份底色结论（inkState），多页 HomePage 共享同一结果，
 * 避免每个分页各自解码位图、重复计算导致滑动掉帧 / 耗电。
 */
// 全应用共享的一份底色结论：只算一次、所有页面同步，杜绝翻页时重复采样。
private val inkState = mutableStateOf(true)   // 默认偏暗 → 白图标，与旧版外观一致
private var inkBooted = false
private val inkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private var inkJob: Job? = null

private fun inkSchedule(ctx: Context, bounds: Rect?) {
    // 一次换壁纸会连发多次事件（wallpaperVersion 变化 / OnColorsChanged / ON_RESUME），
    // 每次都要解码图片并建两张位图。合并成最后一次，降低连续切壁纸时的内存峰值。
    inkJob?.cancel()
    inkJob = inkScope.launch {
        delay(140)
        val luma = computeRegionLuma(ctx, bounds) ?: return@launch
        // 滞回区间避免临界灰度反复跳色：<135 判暗、>165 判亮，中间保持现状
        val next = when {
            luma < 135f -> true
            luma > 165f -> false
            else -> inkState.value
        }
        Log.d(TAG_INK, "luma=$luma ink ${inkState.value} -> $next")
        withContext(Dispatchers.Main) { inkState.value = next }
    }
}

@Composable
private fun rememberWallpaperInk(bounds: Rect?): State<Boolean> {
    val ctx = LocalContext.current
    // 首帧拿到真实 bounds 后只采样一次（初始化）；之后翻页 / 重排不再触发，省电。
    LaunchedEffect(bounds) {
        if (!inkBooted && bounds != null) {
            inkBooted = true
            inkSchedule(ctx, bounds)
        }
    }
    // 之后只在「切换壁纸」时重算：本应用换壁纸（wallpaperVersion 递增）
    LaunchedEffect(DuoState.wallpaperVersion) {
        if (inkBooted) inkSchedule(ctx, bounds)
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // 注意：bounds 不放在 key 里，避免翻页时徽标 bounds 每帧拿到新对象就反复
    // 注销 / 重新注册系统监听（原来正是这个导致滑动掉帧、耗电）。
    DisposableEffect(ctx, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // 回到桌面兜底：壁纸可能已在别处被换过，但翻页不会走到这里，不影响省电
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && inkBooted) {
                inkSchedule(ctx, bounds)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val wm = WallpaperManager.getInstance(ctx.applicationContext)
        val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
            if (inkBooted) inkSchedule(ctx, bounds)
        }
        if (Build.VERSION.SDK_INT >= 24) {
            wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= 24) wm.removeOnColorsChangedListener(listener)
        }
    }
    return inkState
}
