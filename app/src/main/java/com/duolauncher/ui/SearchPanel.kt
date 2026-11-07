package com.duolauncher.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.util.Contacts
import com.duolauncher.util.ContactHit
import com.duolauncher.util.SearchEngines
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SearchPanel(
    nav: Nav,
    dark: Boolean,
    wallpaper: Bitmap?,
    /** 滑入动画停稳后才申请焦点：否则右滑刚起手 IME 就弹出来了 */
    focusEnabled: Boolean = true,
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
    val recents = DuoState.recentApps(DuoState.recentCount)
    val results = if (query.isBlank()) emptyList() else all.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    val iconSize = (48 * DuoState.iconScale).dp

    var showSettings by remember { mutableStateOf(false) }
    var contactsGranted by remember { mutableStateOf(Contacts.hasPermission(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> contactsGranted = granted }
    var contactHits by remember { mutableStateOf(emptyList<ContactHit>()) }

    // 从系统设置页返回时刷新授权状态（用户可能在别处改了权限）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
                contactsGranted = Contacts.hasPermission(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 输入变化 / 授权变化时检索通讯录；ContentResolver 查询放 IO，避免输入卡顿
    LaunchedEffect(query, contactsGranted, DuoState.searchContacts) {
        contactHits = if (DuoState.searchContacts && contactsGranted && query.isNotBlank())
            withContext(Dispatchers.IO) { Contacts.search(ctx, query) }
        else emptyList()
    }

    /**
     * 关闭前必须显式收键盘：只把面板从组合里摘掉，IME 窗口（尤其是接实体键盘时
     * 底部那条「输入状态 / 候选」快捷栏）有时会赖着不走，回到桌面还挂在下面。
     */
    fun hideKeyboard() {
        runCatching {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    val close = {
        hideKeyboard()
        runCatching { focusManager.clearFocus(force = true) }
        onClose()
    }

    fun openWeb() {
        close()
        if (!SearchEngines.open(ctx, DuoState.searchEngine, query))
            nav.toast(Lang.s(R.string.no_browser))
    }

    fun openContact(hit: ContactHit) {
        close()
        if (!Contacts.open(ctx, hit)) nav.toast(Lang.s(R.string.open_contact_failed))
    }

    /** 键盘回车：打开第一个结果；应用 → 联系人 → 用默认浏览器做网页搜索 */
    fun openFirstResult() {
        when {
            results.isNotEmpty() -> {
                close()
                AppRepository.launch(ctx, results.first().packageName)
            }
            contactHits.isNotEmpty() -> openContact(contactHits.first())
            query.isNotBlank() -> openWeb()
        }
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
    LaunchedEffect(focusEnabled) {
        if (focusEnabled) runCatching { focus.requestFocus() }
    }

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
            // 左滑关闭：与「右滑进来」完全对称，拖动时逐帧跟手滑出，松手后补齐 / 回弹
            .pointerInput(Unit) {
                val trigger = 80.dp.toPx()
                val startSlop = 12f
                val fullPx = size.width * 0.5f
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragCancel = {
                        dx = 0f
                        nav.searchCloseDrag(1f)
                        nav.searchCloseRelease(false)
                    },
                    onDragEnd = {
                        val commit = dx <= -trigger
                        if (commit) {
                            hideKeyboard()
                            runCatching { focusManager.clearFocus(force = true) }
                        }
                        nav.searchCloseRelease(commit)
                    }
                ) { change, dragAmount ->
                    dx += dragAmount
                    if (dx < -startSlop) {
                        change.consume()
                        nav.searchCloseDrag((1f - ((-dx) - startSlop) / fullPx).coerceIn(0f, 1f))
                    }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Lang.s(R.string.search),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                // 右上角设置：最近常用数量 / 搜索引擎 / 联系人开关
                GlassCard(modifier = Modifier.size(36.dp), radius = 18.dp, level = 0.9f, dark = dark) {
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            hideKeyboard()
                            runCatching { focusManager.clearFocus(force = true) }
                            showSettings = true
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            Lang.s(R.string.search_settings),
                            tint = textColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { openFirstResult() },
                                onDone = { openFirstResult() }
                            ),
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
                // 每排 5 个，数量由设置决定（5 / 10 / 15 / 20）
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
                val total = results.size + contactHits.size
                Text(Lang.s(R.string.search_results, total), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = subColor, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 60.dp)
                ) {
                    // ── 应用 ──
                    if (results.isNotEmpty()) {
                        item { ResultSection(Lang.s(R.string.section_apps, results.size), subColor) }
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

                    // ── 通讯录联系人 ──
                    if (DuoState.searchContacts && !contactsGranted) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(glassBlue.copy(alpha = 0.20f))
                                    .clickable { permLauncher.launch(Manifest.permission.READ_CONTACTS) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(Lang.s(R.string.contacts_permission_needed), fontSize = 14.sp, color = Color.White)
                            }
                        }
                    }
                    if (contactHits.isNotEmpty()) {
                        item { ResultSection(Lang.s(R.string.section_contacts, contactHits.size), subColor) }
                        items(contactHits) { hit ->
                            ContactResultRow(
                                hit = hit,
                                dark = dark,
                                onClick = { openContact(hit) },
                                onDial = { close(); Contacts.dial(ctx, hit.number) }
                            )
                        }
                    }

                    if (results.isEmpty() && contactHits.isEmpty()) {
                        item {
                            Text(Lang.s(R.string.no_match_anything), fontSize = 14.sp, color = subColor,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                        }
                    }

                    // ── 网页搜索（默认浏览器打开）──
                    item { ResultSection(Lang.s(R.string.section_web), subColor) }
                    item {
                        WebSearchRow(
                            engineLabel = SearchEngines.label(DuoState.searchEngine),
                            query = query,
                            dark = dark,
                            onClick = { openWeb() }
                        )
                    }
                }
            }
        }
    }

    // 搜索页右上角的设置弹窗：与设置页共用同一份 DuoState，改完即时生效
    if (showSettings) {
        DuoDialog(
            title = Lang.s(R.string.search_settings),
            dark = dark,
            onDismiss = { showSettings = false },
            level = 0.86f,
            opaque = true
        ) {
            Text(Lang.s(R.string.recent_count), fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = glassSubTextColor(dark))
            Spacer(Modifier.height(8.dp))
            SegmentedChips(
                options = SearchEngines.RECENT_COUNTS.map { it.toString() to it.toString() },
                selected = DuoState.recentCount.toString(),
                dark = dark
            ) { DuoState.recentCount = it.toInt(); DuoState.save() }
            Spacer(Modifier.height(18.dp))

            Text(Lang.s(R.string.search_engine), fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = glassSubTextColor(dark))
            Spacer(Modifier.height(8.dp))
            SegmentedChips(
                options = SearchEngines.ALL.map { it.id to Lang.s(it.labelRes) },
                selected = DuoState.searchEngine,
                dark = dark
            ) { DuoState.searchEngine = it; DuoState.save() }
            Spacer(Modifier.height(18.dp))

            SettingToggle(
                Lang.s(R.string.search_contacts),
                Lang.s(R.string.search_contacts_desc),
                DuoState.searchContacts, dark
            ) {
                DuoState.searchContacts = it
                DuoState.save()
                if (it && !contactsGranted) permLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
}

@Composable
private fun ResultSection(text: String, color: Color) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun ContactResultRow(
    hit: ContactHit,
    dark: Boolean,
    onClick: () -> Unit,
    onDial: () -> Unit
) {
    val c = glassTextColor(dark)
    val sub = glassSubTextColor(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(glassBlue.copy(alpha = 0.38f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.name, fontSize = 15.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.number.isNotBlank()) {
                Text(hit.number, fontSize = 12.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (hit.number.isNotBlank()) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDial),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Call, Lang.s(R.string.call_contact), tint = glassBlue,
                    modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun WebSearchRow(
    engineLabel: String,
    query: String,
    dark: Boolean,
    onClick: () -> Unit
) {
    val c = glassTextColor(dark)
    val sub = glassSubTextColor(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(glassBlue.copy(alpha = 0.38f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Public, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Lang.s(R.string.web_search_with, engineLabel), fontSize = 15.sp, color = c)
            Text("「$query」", fontSize = 12.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = sub, modifier = Modifier.size(18.dp))
    }
}
