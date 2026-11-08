package com.duolauncher.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    /**
     * 已经决定关闭（MainActivity 在开始滑出前就置 true）。
     * 一旦为 true，搜索框里的 BasicTextField **立刻从组合里摘掉**——
     * 输入法找不到可依附的编辑器，会话只能结束，那条「输入状态」工具栏也就没了。
     * 这是 App 资源库上已经实测有效的唯一一条路径（页面还在、输入框先没），
     * 比反复调 hide()/清焦点可靠得多。
     */
    closing: Boolean = false,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    // 真实 EditText 的控制器 —— 只有走真实控件的 IMM 才能把输入法会话真正结束掉，
    // 桌面底部那条「双拼 / 麦克风」工具栏才不会残留（Compose 的 BasicTextField 做不到）
    val fieldCtl = remember { DuoFieldController() }
    // 输入框是否已从组合里摘掉（关闭流程第二步）
    var fieldGone by remember { mutableStateOf(false) }
    // 一次关闭只收一次键盘，绝不多播一遍收起动画（「跳两下」就是这么来的）
    var inputDismissed by remember { mutableStateOf(false) }
    fun dismissInputOnce() {
        if (inputDismissed) return
        inputDismissed = true
        fieldCtl.dismissInput()
    }
    // 兜底收键盘要用 View 的 windowToken
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
     * 只在「页面还留着、但要把键盘收掉」的场景用（打开右上角设置弹窗）。
     * 关闭搜索页走 [dismissInputOnce]，同一个门闩保证整个关闭流程只收一次。
     */
    fun hideInput() {
        dismissInputOnce()
    }

    // 关闭搜索页：这里什么都不碰，只通知上层。上层会把 closing 置 true，
    // 输入框随即从组合里消失（见下方搜索框），输入法会话自然结束。
    val close = { onClose() }

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
                AppRepository.launch(ctx, results.first().id)
            }
            contactHits.isNotEmpty() -> openContact(contactHits.first())
            query.isNotBlank() -> openWeb()
        }
    }

    // closing 落地：先让真实编辑框失焦（框架据此收键盘、结束输入法会话，只收这一次），
    // 下一帧再把它从组合里摘掉——双保险，且不会多播一遍收起动画。
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        dismissInputOnce()
        withFrameNanos { }
        fieldGone = true
    }

    // 最后一道保险：面板真要销毁时，先看输入法还挂着没（insets 里 ime 是否可见）。
    // 还挂着才补一次 IMM.hide —— 正常情况这里是空操作，不会多播一遍收起动画。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val insets = ViewCompat.getRootWindowInsets(view)
                if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    val imm = view.context
                        .getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        }
    }

    BackHandler(onBack = { close() })
    // 滑入停稳才申请焦点；一旦开始滑出（reveal 掉到 0.98 以下）立刻结束输入——
    // 焦点在手时 IME 会被系统一直保持，这是「滑回桌面后键盘还赖着」的根源。
    LaunchedEffect(focusEnabled) {
        if (focusEnabled) {
            inputDismissed = false
            fieldCtl.requestInput()
        } else {
            dismissInputOnce()
        }
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
        // 背景虚化壁纸改由 MainActivity 根层级统一渲染（与 App 资源库同一套），
        // 这里不再自带背景——否则会再叠一层色调，把整页压成灰暗蒙版。

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height((DuoState.cutoutPadding.coerceAtLeast(56) + 12).dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                // 右上角设置：最近常用数量 / 搜索引擎 / 联系人开关
                GlassCard(modifier = Modifier.size(36.dp), radius = 18.dp, level = 0.9f, dark = dark) {
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            hideInput()
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

            // 搜索框：磨砂玻璃条（细边框 + 半透明底），浮在虚化壁纸上——有「轮廓底」但不实心白板
            // 圆角与 App 资源库顶部搜索框保持一致（高 46dp → 半径 23dp，两端全圆）
            GlassCard(
                modifier = Modifier.fillMaxWidth().height(46.dp),
                radius = 23.dp,
                level = 0.55f,
                dark = dark,
                fill = 0.35f
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = subColor, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty() && fieldGone) Text(Lang.s(R.string.search), fontSize = 15.sp, color = subColor)
                        // 关键：closing 一旦为 true 就不再合成输入框。
                        // 输入框消失 → 输入法失去可依附的编辑器 → 会话结束 → 那条工具栏才会走。
                        // 此时把已输入的内容画成静态文字，滑出过程中视觉不变。
                        if (!fieldGone) {
                            // 真实 EditText：Compose 的 BasicTextField 收不掉输入法会话，
                            // 这条「输入状态工具栏」残留的根因就在这里
                            DuoSearchField(
                                controller = fieldCtl,
                                value = query,
                                textColor = textColor,
                                hintColor = subColor,
                                cursorColor = glassBlue,
                                hint = Lang.s(R.string.search),
                                autoFocus = focusEnabled,
                                onValueChange = { query = it },
                                onFocusChange = { if (it) inputDismissed = false },
                                onSubmit = { openFirstResult() }
                            )
                        } else if (query.isNotEmpty()) {
                            Text(query, fontSize = 15.sp, color = textColor, maxLines = 1)
                        }
                    }
                    if (query.isNotEmpty() && !closing) {
                        Icon(Icons.Filled.Close, Lang.s(R.string.clear), tint = subColor,
                            modifier = Modifier.size(18.dp).clickable { query = "" })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            if (query.isBlank()) {
                Text(Lang.s(R.string.widget_recents), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = subColor,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                // 常用应用包进一张磨砂玻璃面板（细边框 + 半透明底）：有「轮廓底」而非实心白板
                val recentRows = recents.chunked(5)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    radius = 24.dp,
                    level = 0.55f,
                    dark = dark,
                    fill = 0.30f
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        recentRows.forEachIndexed { i, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { app ->
                                    AppIconCell(
                                        app = app,
                                        size = iconSize,
                                        dark = dark,
                                        showLabel = true,
                                        onClick = { close(); AppRepository.launch(ctx, app.id) },
                                        onLongClick = { nav.showAppMenu(app, "search") }
                                    )
                                }
                                repeat(5 - row.size) { Spacer(Modifier.width(iconSize + 14.dp)) }
                            }
                            if (i < recentRows.lastIndex) Spacer(Modifier.height(14.dp))
                        }
                    }
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
                                        onClick = { close(); AppRepository.launch(ctx, app.id) },
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
    // 图3 风格：深蓝半透明磨砂胶囊卡 + 圆形磨砂头像 + 右侧信息 / 电话两枚圆钮
    val cardBg = if (dark) Color(0xFF16283A) else Color(0xFF33567C)
    val btnBg = if (dark) Color(0xFF1E3448) else Color(0xFF4A6B92)
    val avatarBg = if (dark) Color(0xFF4A3D55) else Color(0xFF6B5A78)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(cardBg.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(avatarBg.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.number.isNotBlank()) {
                Text(hit.number, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        // 信息按钮：打开联系人
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(btnBg.copy(alpha = 0.9f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Email, Lang.s(R.string.open_contact), tint = Color.White, modifier = Modifier.size(19.dp))
        }
        if (hit.number.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            // 电话按钮：直接拨号
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(btnBg.copy(alpha = 0.9f))
                    .clickable(onClick = onDial),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Call, Lang.s(R.string.call_contact), tint = Color.White, modifier = Modifier.size(19.dp))
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
    // 与联系人卡片统一成深蓝半透明磨砂胶囊
    val cardBg = if (dark) Color(0xFF16283A) else Color(0xFF33567C)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(cardBg.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(glassBlue.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Public, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(Lang.s(R.string.web_search_with, engineLabel), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("「$query」", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
    }
}
