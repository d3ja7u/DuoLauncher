package com.duolauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoState

@Composable
fun SearchPanel(
    nav: Nav,
    dark: Boolean,
    wallpaper: Bitmap?,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val textColor = glassTextColor(dark)
    val subColor = glassSubTextColor(dark)
    val all = DuoState.visibleApps()
    val recents = DuoState.recentApps(10)
    val results = if (query.isBlank()) emptyList() else all.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    val iconSize = (48 * DuoState.iconScale).dp

    /**
     * 关闭前必须显式收键盘：只把面板从组合里摘掉，IME 窗口（尤其是接实体键盘时
     * 底部那条「输入状态 / 候选」快捷栏）有时会赖着不走，回到桌面还挂在下面。
     * 顺序：先 hide 软键盘 → 清焦点 → 再关闭面板。
     */
    val close = {
        runCatching {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        runCatching { focusManager.clearFocus(force = true) }
        onClose()
    }

    // 兜底：面板销毁时再收一次
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    BackHandler(onBack = { close() })
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (acc < -90f) close() },
                    onDragCancel = { acc = 0f }
                ) { change, dy -> if (dy < 0) { acc += dy; change.consume() } }
            }
            // 左滑关闭：搜索页是「右滑进来」的，左滑出去正好对称。
            // 输入框/列表这类子节点会先消费拖动，这里的判定不会抢它们。
            .pointerInput(Unit) {
                val trigger = 80.dp.toPx()
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragCancel = { dx = 0f },
                    onDragEnd = { if (dx < -trigger) close() }
                ) { change, dragAmount ->
                    dx += dragAmount
                    if (dx < -12f) change.consume()
                }
            }
    ) {
        GlassBackdrop(wallpaper, DuoState.glassLevel, dark, extraDim = 0.18f)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height((DuoState.cutoutPadding.coerceAtLeast(56) + 12).dp))
            // 标题不再配右上角的关闭按钮：左滑、上滑、返回键都能退出，视觉上干净些
            Text(Lang.s(R.string.search), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = textColor)
            Spacer(Modifier.height(14.dp))

            GlassCard(
                modifier = Modifier.fillMaxWidth().height(46.dp),
                radius = 16.dp,
                level = 0.9f,
                dark = dark
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = subColor, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(Lang.s(R.string.search), fontSize = 14.sp, color = subColor)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = textColor),
                            cursorBrush = SolidColor(glassBlue),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(Icons.Filled.Close, Lang.s(R.string.clear), tint = subColor,
                            modifier = Modifier.size(18.dp).clickable { query = "" })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            if (query.isBlank()) {
                Text(Lang.s(R.string.widget_recents), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = subColor,
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
                // 2 排 × 5 个
                recents.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { app ->
                            AppIconCell(
                                app = app,
                                size = iconSize,
                                dark = dark,
                                showLabel = true,
                                onClick = { close(); AppRepository.launch(ctx, app.packageName) },
                                onLongClick = { nav.showAppMenu(app, "search") }
                            )
                        }
                        repeat(5 - row.size) { Spacer(Modifier.width(iconSize + 14.dp)) }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            } else {
                Text(Lang.s(R.string.search_results, results.size), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = subColor, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 60.dp)
                ) {
                    items(results.chunked(5)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { app ->
                                AppIconCell(
                                    app = app,
                                    size = iconSize,
                                    dark = dark,
                                    showLabel = true,
                                    onClick = { close(); AppRepository.launch(ctx, app.packageName) },
                                    onLongClick = { nav.showAppMenu(app, "search") }
                                )
                            }
                            repeat(5 - row.size) { Spacer(Modifier.width(iconSize + 14.dp)) }
                        }
                    }
                }
                if (results.isEmpty()) {
                    Text(Lang.s(R.string.no_match_apps), fontSize = 14.sp, color = subColor,
                        modifier = Modifier.padding(start = 4.dp, top = 20.dp))
                }
            }
        }
    }
}
