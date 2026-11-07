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
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoActions
import com.duolauncher.data.DuoState
import com.duolauncher.data.WidgetItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

val BUILTIN_WIDGETS = listOf(
    "clock" to R.string.widget_clock,
    "battery" to R.string.widget_battery,
    "calendar" to R.string.widget_calendar,
    "recents" to R.string.widget_recents,
    "quick" to R.string.widget_quick,
    "note" to R.string.widget_note,
    "storage" to R.string.widget_storage
)

fun builtinWidgetName(type: String) =
    BUILTIN_WIDGETS.firstOrNull { it.first == type }?.second?.let { Lang.s(it) } ?: type

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
                Text(Lang.s(R.string.today), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = textColor)
                Spacer(Modifier.weight(1f))
                GlassCard(modifier = Modifier.size(36.dp), radius = 18.dp, level = 0.8f, dark = dark) {
                    androidx.compose.material3.IconButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Add, Lang.s(R.string.add_widget), tint = textColor, modifier = Modifier.size(20.dp))
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
                            Text(Lang.s(R.string.today_empty), fontSize = 14.sp, color = subColor)
                            Spacer(Modifier.height(4.dp))
                            Text(Lang.s(R.string.today_empty_hint), fontSize = 12.sp,
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
                            Text(Lang.s(R.string.add_widget), color = subColor, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        DuoDialog(title = Lang.s(R.string.add_widget), dark = dark, onDismiss = { showAdd = false }) {
            BUILTIN_WIDGETS.forEach { (type, nameRes) ->
                MenuRow(Icons.Filled.Dashboard, Lang.s(nameRes), dark = dark) {
                    DuoState.widgets.add(WidgetItem(type)); DuoState.save(); showAdd = false
                }
            }
            MenuRow(Icons.Filled.Widgets, Lang.s(R.string.system_widget), dark = dark) {
                showAdd = false; nav.openWidgetPicker()
            }
        }
    }

    editing?.let { index ->
        val item = widgets.getOrNull(index)
        DuoDialog(title = item?.let { builtinWidgetName(it.type) } ?: Lang.s(R.string.widget_generic), dark = dark, onDismiss = { editing = null }) {
            MenuRow(Icons.Filled.ArrowUpward, Lang.s(R.string.move_up), dark = dark) {
                if (index > 0) {
                    val l = DuoState.widgets.toMutableList()
                    java.util.Collections.swap(l, index, index - 1)
                    DuoState.setWidgets(l)
                }
                editing = null
            }
            MenuRow(Icons.Filled.ArrowDownward, Lang.s(R.string.move_down), dark = dark) {
                if (index < DuoState.widgets.size - 1) {
                    val l = DuoState.widgets.toMutableList()
                    java.util.Collections.swap(l, index, index + 1)
                    DuoState.setWidgets(l)
                }
                editing = null
            }
            MenuRow(Icons.Filled.Delete, Lang.s(R.string.remove), danger = true, dark = dark) {
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
                else -> Text(Lang.s(R.string.unknown_widget), color = glassTextColor(dark))
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
    val time = SimpleDateFormat("HH:mm", Lang.locale()).format(cal.time)
    val date = SimpleDateFormat(Lang.datePattern(), Lang.locale()).format(cal.time)
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle(Lang.s(R.string.clock_title), dark)
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
        WidgetTitle(Lang.s(R.string.widget_battery), dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryFull, null, tint = if (charging) Color(0xFF34C759) else glassBlue, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text("$pct%", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
            Spacer(Modifier.weight(1f))
            Text(if (charging) Lang.s(R.string.charging) else Lang.s(R.string.not_charging), fontSize = 13.sp, color = glassSubTextColor(dark))
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
    val titles = listOf(
        R.string.week_sun, R.string.week_mon, R.string.week_tue,
        R.string.week_wed, R.string.week_thu, R.string.week_fri, R.string.week_sat
    )
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle(Lang.s(R.string.calendar_month, year, month + 1), dark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            titles.forEach { Text(Lang.s(it), fontSize = 11.sp, color = glassSubTextColor(dark), modifier = Modifier.width(28.dp), textAlign = TextAlign.Center) }
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
        WidgetTitle(Lang.s(R.string.widget_recents), dark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            apps.forEach { app ->
                AppIconCell(app, 44.dp, dark = dark, showLabel = true,
                    onClick = { AppRepository.launch(ctx, app.id) },
                    onLongClick = { nav.showAppMenu(app, "widget") })
            }
        }
    }
}

/** 快捷操作小圆图标下的短标签，长名直接换成对应语言里的短说法 */
private fun quickActionLabel(id: String): String = Lang.s(
    when (id) {
        "flashlight" -> R.string.quick_flashlight
        "wifi" -> R.string.quick_wifi
        "bluetooth" -> R.string.quick_bluetooth
        "silent" -> R.string.quick_silent
        "search" -> R.string.quick_search
        "settings" -> R.string.quick_settings
        else -> R.string.action_unknown
    }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickWidget(dark: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    val actions = listOf("flashlight", "wifi", "bluetooth", "silent", "search", "settings")
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle(Lang.s(R.string.widget_quick), dark)
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
                    Text(quickActionLabel(id), fontSize = 9.sp, maxLines = 1, color = glassSubTextColor(dark))
                }
            }
        }
    }
}

@Composable
private fun NoteWidget(dark: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        WidgetTitle(Lang.s(R.string.widget_note), dark)
        BasicTextField(
            value = DuoState.notes,
            onValueChange = { DuoState.notes = it; DuoState.save() },
            textStyle = TextStyle(fontSize = 14.sp, color = glassTextColor(dark), lineHeight = 20.sp),
            cursorBrush = SolidColor(glassBlue),
            modifier = Modifier.fillMaxWidth().height(84.dp)
        )
        if (DuoState.notes.isEmpty()) Text(Lang.s(R.string.note_placeholder), fontSize = 13.sp, color = glassSubTextColor(dark))
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
        WidgetTitle(Lang.s(R.string.widget_storage), dark)
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
