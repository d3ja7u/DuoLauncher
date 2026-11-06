package com.duolauncher.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoActions
import com.duolauncher.data.DuoState
import com.duolauncher.data.WidgetItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

val BUILTIN_WIDGETS = listOf(
    "clock" to "时钟与日期",
    "battery" to "电池",
    "calendar" to "日历",
    "recents" to "最近常用",
    "quick" to "快捷操作",
    "note" to "便签",
    "storage" to "存储空间"
)

fun builtinWidgetName(type: String) = BUILTIN_WIDGETS.firstOrNull { it.first == type }?.second ?: type

@Composable
fun TodayPage(nav: Nav, dark: Boolean, wallpaper: Bitmap?, bridge: WidgetBridge? = null) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val widgets = DuoState.widgets.toList()
    val textColor = glassTextColor(dark)
    val subColor = glassSubTextColor(dark)

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop(wallpaper, DuoState.glassLevel, dark, extraDim = 0.06f)

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height((DuoState.cutoutPadding.coerceAtLeast(56) + 8).dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今天", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = textColor)
                Spacer(Modifier.weight(1f))
                GlassCard(modifier = Modifier.size(36.dp), radius = 18.dp, level = 0.8f, dark = dark) {
                    androidx.compose.material3.IconButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Add, "添加小组件", tint = textColor, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 空态：删光之后别只剩一片空白，给一句怎么加回来的提示
                if (widgets.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Dashboard, null,
                                tint = subColor.copy(alpha = 0.45f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("负一屏是空的", fontSize = 14.sp, color = subColor)
                            Spacer(Modifier.height(4.dp))
                            Text("点右上角 + 或下面的卡片添加小组件", fontSize = 12.sp,
                                color = subColor.copy(alpha = 0.65f))
                        }
                    }
                }
                itemsIndexed(widgets, key = { index, item -> "${item.type}:${item.extra}:$index" }) { index, item ->
                    WidgetContainer(
                        item = item,
                        dark = dark,
                        onLongClick = { editing = index },
                        nav = nav
                    )
                }
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable { showAdd = true },
                        radius = 20.dp,
                        level = 0.7f,
                        dark = dark
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Add, null, tint = subColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加小组件", color = subColor, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        DuoDialog(title = "添加小组件", dark = dark, onDismiss = { showAdd = false }) {
            BUILTIN_WIDGETS.forEach { (type, name) ->
                MenuRow(Icons.Filled.Dashboard, name, dark = dark) {
                    DuoState.widgets.add(WidgetItem(type)); DuoState.save(); showAdd = false
                }
            }
            MenuRow(Icons.Filled.Widgets, "系统小组件…", dark = dark) {
                showAdd = false; nav.openWidgetPicker()
            }
        }
    }

    editing?.let { index ->
        val item = widgets.getOrNull(index)
        DuoDialog(title = item?.let { builtinWidgetName(it.type) } ?: "小组件", dark = dark, onDismiss = { editing = null }) {
            MenuRow(Icons.Filled.ArrowUpward, "上移", dark = dark) {
                if (index > 0) {
                    val l = DuoState.widgets.toMutableList()
                    java.util.Collections.swap(l, index, index - 1)
                    DuoState.setWidgets(l)
                }
                editing = null
            }
            MenuRow(Icons.Filled.ArrowDownward, "下移", dark = dark) {
                if (index < DuoState.widgets.size - 1) {
                    val l = DuoState.widgets.toMutableList()
                    java.util.Collections.swap(l, index, index + 1)
                    DuoState.setWidgets(l)
                }
                editing = null
            }
            MenuRow(Icons.Filled.Delete, "移除", danger = true, dark = dark) {
                if (item?.type == "system") {
                    runCatching {
                        bridge?.host?.deleteAppWidgetId(item.extra.toIntOrNull() ?: -1)
                    }
                }
                val l = DuoState.widgets.toMutableList()
                if (index in l.indices) l.removeAt(index)
                DuoState.setWidgets(l)
                editing = null
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetContainer(item: WidgetItem, dark: Boolean, onLongClick: () -> Unit, nav: Nav) {
    val height = when (item.type) {
        "clock" -> 128.dp
        "calendar" -> 232.dp
        "recents" -> 148.dp
        "note" -> 150.dp
        "system" -> 150.dp
        else -> 104.dp
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .wrapContentHeight()
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongClick
            ),
        radius = 26.dp,
        level = 0.9f,
        dark = dark
    ) {
        Box(Modifier.padding(18.dp)) {
            when (item.type) {
                "clock" -> ClockWidget(dark)
                "battery" -> BatteryWidget(dark)
                "calendar" -> CalendarWidget(dark)
                "recents" -> RecentsWidget(dark, nav)
                "quick" -> QuickWidget(dark, nav)
                "note" -> NoteWidget(dark)
                "storage" -> StorageWidget(dark)
                "system" -> SystemWidgetCard(
                    item.extra.toIntOrNull() ?: -1,
                    dark,
                    Modifier.fillMaxWidth().height(120.dp)
                )
                else -> Text("未知小组件", color = glassTextColor(dark))
            }
        }
    }
}

@Composable
private fun WidgetTitle(text: String, dark: Boolean) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = glassSubTextColor(dark))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ClockWidget(dark: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(20_000L) } }
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
    val date = SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(cal.time)
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("时钟", dark)
        Text(time, fontSize = 46.sp, fontWeight = FontWeight.Light, color = glassTextColor(dark), lineHeight = 50.sp)
        Text(date, fontSize = 14.sp, color = glassSubTextColor(dark))
    }
}

@Composable
private fun BatteryWidget(dark: Boolean) {
    val ctx = LocalContext.current
    var pct by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            pct = if (level >= 0) (level * 100 / scale.coerceAtLeast(1)) else 0
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            delay(30_000L)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("电池", dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryFull, null, tint = if (charging) Color(0xFF34C759) else glassBlue, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text("$pct%", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
            Spacer(Modifier.weight(1f))
            Text(if (charging) "充电中" else "未充电", fontSize = 13.sp, color = glassSubTextColor(dark))
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (charging) Color(0xFF34C759) else glassBlue,
            trackColor = glassSubTextColor(dark).copy(alpha = 0.25f)
        )
    }
}

@Composable
private fun CalendarWidget(dark: Boolean) {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_MONTH)
    val month = cal.get(Calendar.MONTH)
    val year = cal.get(Calendar.YEAR)
    val first = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        .get(Calendar.DAY_OF_WEEK) // 1=周日
    val days = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        .getActualMaximum(Calendar.DAY_OF_MONTH)
    val titles = listOf("日", "一", "二", "三", "四", "五", "六")
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("$year 年 ${month + 1} 月", dark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            titles.forEach { Text(it, fontSize = 11.sp, color = glassSubTextColor(dark), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.height(6.dp))
        val cells = (0 until first - 1).map { 0 } + (1..days).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                week.forEach { d ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (d == today) glassBlue else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (d > 0) Text("$d", fontSize = 11.sp,
                            color = if (d == today) Color.White else glassTextColor(dark))
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun RecentsWidget(dark: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    val apps = DuoState.recentApps(4)
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("最近常用", dark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            apps.forEach { app ->
                AppIconCell(app, 44.dp, dark = dark, showLabel = true,
                    onClick = { AppRepository.launch(ctx, app.packageName) },
                    onLongClick = { nav.showAppMenu(app, "widget") })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickWidget(dark: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    val actions = listOf("flashlight", "wifi", "bluetooth", "silent", "search", "settings")
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("快捷操作", dark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { id ->
                val a = DuoActions.of(id)
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(52.dp).clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                DuoActions.perform(ctx, id) { key ->
                                    when (key) {
                                        "search" -> nav.openSearch()
                                        "settings" -> nav.openSettings()
                                        "library" -> nav.goLibrary()
                                        "today" -> nav.goPage(0)
                                        "recents" -> nav.openSearch()
                                    }
                                }
                            }
                        )) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(ActionPalette.brush(id)),
                        contentAlignment = Alignment.Center
                    ) { Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label.replace("设置", ""), fontSize = 9.sp, maxLines = 1, color = glassSubTextColor(dark))
                }
            }
        }
    }
}

@Composable
private fun NoteWidget(dark: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("便签", dark)
        BasicTextField(
            value = DuoState.notes,
            onValueChange = { DuoState.notes = it; DuoState.save() },
            textStyle = TextStyle(fontSize = 14.sp, color = glassTextColor(dark), lineHeight = 20.sp),
            cursorBrush = SolidColor(glassBlue),
            modifier = Modifier.fillMaxWidth().height(84.dp)
        )
        if (DuoState.notes.isEmpty()) Text("点这里记点什么…", fontSize = 13.sp, color = glassSubTextColor(dark))
    }
}

@Composable
private fun StorageWidget(dark: Boolean) {
    val stat = remember {
        runCatching {
            val f = StatFs(Environment.getDataDirectory().path)
            val total = f.totalBytes / 1024f / 1024f / 1024f
            val free = f.availableBytes / 1024f / 1024f / 1024f
            total to (total - free)
        }.getOrDefault(0f to 0f)
    }
    val used = if (stat.first > 0f) stat.second / stat.first else 0f
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle("存储空间", dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${"%.1f".format(stat.second)} GB / ${"%.1f".format(stat.first)} GB",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
            Spacer(Modifier.weight(1f))
            Text("${(used * 100).toInt()}%", fontSize = 13.sp, color = glassSubTextColor(dark))
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { used.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFF9F0A),
            trackColor = glassSubTextColor(dark).copy(alpha = 0.25f)
        )
    }
}
