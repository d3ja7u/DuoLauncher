package com.duolauncher.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.AppInfo
import com.duolauncher.data.AppRepository
import com.duolauncher.data.CATEGORY_LIST
import com.duolauncher.data.DuoState
import com.duolauncher.data.categoryName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App 资源库（iOS 17 样式），两种**互斥**视图：
 *
 * ① 文件夹视图（默认）
 *   ┌ 搜索胶囊「App 资源库」 ┐
 *   ┌── 分类卡 ──┐ ┌── 分类卡 ──┐ ┌── 分类卡 ──┐   ← 3 列，圆角玻璃卡，内含 2×2 图标
 *   │ ▢  ▢ │     │ ▢  ▢ │     │ ▢  ▢ │
 *   │ ▢  ▣ │     │ ▢  ▣ │     │ ▢  ▣ │         前 3 格图标直接点开应用；第 4 格是「文件夹」入口
 *     社交            工具            系统       ← 分类名在卡片下方
 *
 * ② 列表视图（iOS 字母列表）
 *   A ─ 应用行（图标 + 名字 + 细分隔线）
 *   B ─ ...
 *   右侧 A–Z 索引条，点/拖直达分组。
 *
 * 切换方式：右上角图标按钮；或在顶部**下拉**（列表已到顶时继续往下拖）也可来回切。
 * 列表视图里屏幕上不会再有文件夹卡，从第一个字母开始铺满。
 *
 * 点击分类卡第 4 格（或卡片空白处）→ 弹出「窗口」（FolderWindow）展示该分类全部应用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPage(nav: Nav, dark: Boolean, wallpaper: Bitmap?) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var openCat by remember { mutableStateOf<Pair<String, List<AppInfo>>?>(null) }
    var indexActive by remember { mutableStateOf<Char?>(null) }
    // false = 文件夹视图，true = 字母列表视图。两者互斥，列表视图里不会有文件夹卡
    var listMode by remember { mutableStateOf(false) }
    val all = DuoState.visibleApps().filter { !DuoState.hidden.contains(it.id) }
    val textColor = glassTextColor(dark)
    val subColor = glassSubTextColor(dark)

    val groups = if (DuoState.smartCategory) {
        CATEGORY_LIST.map { it.first to all.filter { a -> DuoState.categoryOf(a) == it.first } }
            .filter { it.second.isNotEmpty() }
    } else {
        listOf("all" to all)
    }

    // ── 字母列表（iOS 样式）：列表视图专用，从第一个字母开始铺满整屏 ──
    val sections = remember(all) { buildLetterSections(all) }
    val letters = remember(sections) { sections.map { it.first } }
    val cardRows = remember(groups) { groups.chunked(3) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 切换到任一视图都回到顶部
    LaunchedEffect(listMode) { listState.scrollToItem(0) }
    // 各字母分组标题的 item 索引：列表视图里只有分组区，从 0 开始
    val headerIndex = remember(sections) {
        val map = HashMap<Char, Int>()
        var i = 0
        sections.forEach { (ch, list) -> map[ch] = i; i += 1 + list.size }
        map
    }
    // 顶部下拉（内容已到顶再往下拖）切换视图，阈值 58dp
    val pullThreshold = with(LocalDensity.current) { 58.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop(wallpaper, DuoState.glassLevel, dark, extraDim = 0.10f)

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(DuoState.cutoutPadding.coerceAtLeast(52).dp))

            // ── 顶部：搜索胶囊 + 分类管理 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCard(
                    modifier = Modifier.weight(1f).height(46.dp),
                    radius = 23.dp, level = 1f, dark = dark
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 未输入且未聚焦时显示居中的「App 资源库」占位
                        if (query.isEmpty() && !searchFocused) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Search, null, tint = textColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    Lang.s(R.string.app_library), fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, color = textColor
                                )
                            }
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 15.sp, color = textColor),
                                cursorBrush = SolidColor(glassBlue),
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)
                                    .onFocusChanged { searchFocused = it.isFocused }
                            )
                        }
                        if (query.isNotEmpty()) {
                            Box(Modifier.fillMaxSize().padding(end = 14.dp), contentAlignment = Alignment.CenterEnd) {
                                Icon(
                                    Icons.Filled.Close, Lang.s(R.string.clear), tint = subColor,
                                    modifier = Modifier.size(17.dp).clickable { query = "" }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(9.dp))
                // 文件夹视图 ⇄ 字母列表 切换
                GlassCard(modifier = Modifier.size(46.dp), radius = 23.dp, level = 0.9f, dark = dark) {
                    IconButton(onClick = { listMode = !listMode }, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (listMode) Icons.Filled.GridView else Icons.Filled.ViewList,
                            if (listMode) Lang.s(R.string.switch_folder_view) else Lang.s(R.string.switch_list_view),
                            tint = textColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                GlassCard(modifier = Modifier.size(46.dp), radius = 23.dp, level = 0.9f, dark = dark) {
                    IconButton(onClick = nav.openCategoryEditor, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Tune, Lang.s(R.string.manage_categories), tint = textColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (query.isNotBlank()) {
                // ── 搜索结果：常规 4 列 ──
                val result = all.filter {
                    it.label.contains(query, true) || it.packageName.contains(query, true)
                }
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        Lang.s(R.string.search_results, result.size), fontSize = 12.sp, color = subColor,
                        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
                    )
                    AppGrid(result, nav, dark)
                    if (result.isEmpty()) {
                        Text(
                            Lang.s(R.string.no_match_apps), fontSize = 14.sp, color = subColor,
                            modifier = Modifier.padding(top = 30.dp, start = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(96.dp))
                }
            } else {
                // ── 两套互斥布局：文件夹视图 / 字母列表视图 ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 顶部下拉切视图：必须**起点就在顶部**（否则从列表中段滑回顶部会误切），
                        // 且手指继续向下拖过阈值才生效
                        .pointerInput(listMode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Final
                                )
                                if (listState.canScrollBackward) return@awaitEachGesture
                                var highest = down.position.y
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Final)
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    if (ch.position.y < highest) highest = ch.position.y
                                    if (!listState.canScrollBackward &&
                                        ch.position.y - highest > pullThreshold
                                    ) {
                                        listMode = !listMode
                                        break
                                    }
                                }
                            }
                        },
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    if (!listMode) {
                        itemsIndexed(cardRows, key = { i, _ -> "cardRow$i" }) { _, row ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { (cat, list) ->
                                    CategoryTile(
                                        modifier = Modifier.weight(1f),
                                        title = if (cat == "all") Lang.s(R.string.all_apps) else categoryName(cat),
                                        list = list,
                                        dark = dark,
                                        onLaunch = { app -> AppRepository.launch(ctx, app.id) },
                                        onLongClick = { app -> nav.showAppMenu(app, "library") },
                                        onOpenFolder = { openCat = cat to list }
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                        sections.forEach { (ch, list) ->
                            stickyHeader(key = "hdr_$ch") { LetterHeader(ch, dark) }
                            items(list, key = { it.id }) { app ->
                                LetterRow(
                                    app = app,
                                    dark = dark,
                                    onClick = { AppRepository.launch(ctx, app.id) },
                                    onLongClick = { nav.showAppMenu(app, "library") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 右侧 A–Z 索引条：只在列表视图出现，点/拖直达分组 ──
        if (query.isBlank() && letters.size > 1) {
            // 索引条只在顶部搜索区之下居中，别顶到搜索胶囊上
            val topInset = DuoState.cutoutPadding.coerceAtLeast(52).dp + 56.dp
            AlphabetIndexBar(
                letters = letters,
                dark = dark,
                visible = listMode,
                onActive = { indexActive = it },
                onJump = { ch ->
                    headerIndex[ch]?.let { idx ->
                        // 拖动索引条时用瞬时定位（animate 会排队、拖起来发黏），跟 iOS 一致
                        scope.launch { listState.scrollToItem(idx) }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(top = topInset)
            )
            AnimatedVisibility(
                visible = indexActive != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = 60.dp, top = topInset)
            ) {
                indexActive?.let { ActiveLetterBubble(it, dark) }
            }
        }
    }

    // ── 分类「窗口」：点卡片展开 ──
    openCat?.let { (cat, list) ->
        FolderWindow(
            title = if (cat == "all") Lang.s(R.string.all_apps) else categoryName(cat),
            subtitle = Lang.s(R.string.app_count, list.size),
            apps = list,
            dark = dark,
            onDismiss = { openCat = null },
            onLaunch = { app -> openCat = null; AppRepository.launch(ctx, app.id) },
            onLongClick = { app -> openCat = null; nav.showAppMenu(app, "library") }
        )
    }
}

/**
 * 分类卡：iOS App 资源库样式。
 * 2×2 共 4 格：前 3 格是单个应用，**点击直达**；第 4 格是「文件夹」入口（2×2 迷你图标簇），
 * 点它（或卡片空白处）打开分类窗口。
 */
@Composable
private fun CategoryTile(
    title: String,
    list: List<AppInfo>,
    dark: Boolean,
    onLaunch: (AppInfo) -> Unit,
    onLongClick: (AppInfo) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = 7.dp
    Column(modifier) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onOpenFolder),
            radius = 22.dp,
            level = 0.95f,
            dark = dark
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    BigIconCell(list.getOrNull(0), dark, onLaunch, onLongClick)
                    BigIconCell(list.getOrNull(1), dark, onLaunch, onLongClick)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    BigIconCell(list.getOrNull(2), dark, onLaunch, onLongClick)
                    FolderClusterCell(list.drop(3), dark, onOpenFolder)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = glassTextColor(dark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 单枚大图标格：图标缩到格子的 82%，四周留出玻璃底色，点击直达应用 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.BigIconCell(
    app: AppInfo?,
    dark: Boolean,
    onLaunch: (AppInfo) -> Unit,
    onLongClick: (AppInfo) -> Unit
) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (app != null) {
            Image(
                painter = rememberDrawablePainter(rememberAppIcon(app)),
                contentDescription = app.label,
                modifier = Modifier
                    .fillMaxSize(0.82f)
                    .clip(squircle(22))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onLaunch(app) },
                        onLongClick = { onLongClick(app) }
                    )
            )
        }
    }
}

/** 第 4 格：2×2 迷你图标簇 = 分类「文件夹」入口，点击展开窗口 */
@Composable
private fun RowScope.FolderClusterCell(
    apps: List<AppInfo>,
    dark: Boolean,
    onOpenFolder: () -> Unit
) {
    val g = 4.dp
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenFolder),
        contentAlignment = Alignment.Center
    ) {
        if (apps.isNotEmpty()) {
            // 3 列后格子变窄，迷你簇放到 0.72 才不会糊成一团
            Column(
                Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.72f),
                verticalArrangement = Arrangement.spacedBy(g)
            ) {
                apps.take(4).chunked(2).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g)) {
                        for (app in row) {
                            Image(
                                painter = rememberDrawablePainter(rememberAppIcon(app)),
                                contentDescription = app.label,
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(squircle(30))
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGrid(list: List<AppInfo>, nav: Nav, dark: Boolean) {
    val ctx = LocalContext.current
    val size = (48 * DuoState.iconScale).dp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { app ->
                    AppIconCell(
                        app = app,
                        size = size,
                        dark = dark,
                        showLabel = true,
                        onClick = { AppRepository.launch(ctx, app.id) },
                        onLongClick = { nav.showAppMenu(app, "library") }
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.width(size + 14.dp)) }
            }
        }
    }
}

/* ───────────────────────── 字母列表（iOS 资源库下半段） ───────────────────────── */

/** 汉字→拼音转换器（只用于分组首字母，不改显示名）。首次用到时才初始化 */
private val HAN_LATIN by lazy {
    runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
}

/**
 * 取应用名的分组首字母：
 * 拉丁字母→大写本身；数字/符号→'#'；汉字→转拼音后取首个字母（去掉声调）；
 * 转换失败一律归 '#'，保证不会因为个别怪名字把应用漏掉。
 */
private fun initialOf(label: String): Char {
    val c = label.trim().firstOrNull() ?: return '#'
    if (c in 'A'..'Z') return c
    if (c in 'a'..'z') return c.uppercaseChar()
    if (c.isDigit()) return '#'
    val latin = HAN_LATIN?.let { runCatching { it.transliterate(c.toString()) }.getOrNull() } ?: return '#'
    // "汉" → "hàn"，NFD 拆成 h + a + 组合声调，取第一个 ASCII 字母即 h
    val plain = java.text.Normalizer.normalize(latin, java.text.Normalizer.Form.NFD)
    val first = plain.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' } ?: return '#'
    return first.uppercaseChar()
}

/**
 * 按首字母分组，组**顺序**严格按 A→Z 排，'#' 固定最后（与 iOS 一致）；
 * 组内用中文 Collator 按拼音排序。
 *
 * 注意：不能直接「整体排序后按首次出现分组」——ICU 的 zh 排序对个别汉字
 * （不在拼音表中的字）会退化到码位比较，排到所有拉丁字母之后，导致 E/I/N/O/U/V
 * 这类分组莫名跑到 Z 后面。这里先分组、再按字母排序，保证索引条永远 A→Z。
 */
private fun buildLetterSections(apps: List<AppInfo>): List<Pair<Char, List<AppInfo>>> {
    if (apps.isEmpty()) return emptyList()
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply {
        strength = java.text.Collator.PRIMARY
    }
    val map = LinkedHashMap<Char, MutableList<AppInfo>>()
    apps.forEach { app -> map.getOrPut(initialOf(app.label)) { mutableListOf() }.add(app) }
    val hashGroup = map.remove('#')
    val out = map.keys.sorted().map { ch ->
        ch to map.getValue(ch).sortedWith(Comparator { a, b -> collator.compare(a.label, b.label) })
    }
    return if (hashGroup != null) out + ('#' to hashGroup.toList()) else out
}

/** 分组标题：吸顶时盖住下方滚过的内容，所以带一层与底板同色的实底 */
@Composable
private fun LetterHeader(ch: Char, dark: Boolean) {
    val bg = if (dark) Color(0xFF10131C) else Color(0xFFF4F7FF)
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg.copy(alpha = 0.97f))
            .padding(start = 20.dp, top = 12.dp, bottom = 5.dp)
    ) {
        Text(
            ch.toString(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = glassTextColor(dark)
        )
    }
}

/** 列表行：左图标 + 右应用名 + 一条细分隔线（iOS App 资源库的列表样式） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LetterRow(
    app: AppInfo,
    dark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val textColor = glassTextColor(dark)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(start = 16.dp, end = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp)) {
                Image(
                    painter = rememberDrawablePainter(rememberAppIcon(app)),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize().clip(squircle(12))
                )
                val badge = DuoState.badgeOf(app.id)
                if (badge > 0) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp)) {
                        NotificationBadge(badge, 44.dp, Color(DuoState.badgeColor))
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                app.label,
                fontSize = 16.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 分隔线从文字左缘开始，跟 iOS 一致
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 74.dp, end = 58.dp)
                .height(0.6.dp)
                .background(textColor.copy(alpha = 0.13f))
        )
    }
}

/**
 * 右侧 A–Z 索引条：点按或上下拖动都能跳到对应分组。
 * 触控区刻意做宽（[BAR_WIDTH]），宽屏右侧本来就有余量，比 iOS 那条窄缝好按；
 * 字母本身靠右对齐，视觉上还是贴着屏幕边，手指向左偏一点也点得中。
 * 行高按可用高度自适应，字母多的时候自动压缩，且下限 12dp 保证不会叠在一起。
 */
private val BAR_WIDTH = 46.dp

@Composable
private fun AlphabetIndexBar(
    letters: List<Char>,
    dark: Boolean,
    visible: Boolean,
    onActive: (Char?) -> Unit,
    onJump: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    if (letters.isEmpty()) return
    val scope = rememberCoroutineScope()
    val textColor = glassTextColor(dark)
    var current by remember { mutableStateOf<Char?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        BoxWithConstraints(Modifier.width(BAR_WIDTH).fillMaxHeight()) {
            val step = (maxHeight * 0.94f / letters.size).coerceIn(12.dp, 19.dp)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(BAR_WIDTH)
                    .height(step * letters.size)
                    .pointerInput(letters, step) {
                        var lastIdx = -1
                        awaitEachGesture {
                            lastIdx = -1
                            val down = awaitFirstDown()

                            fun pick(y: Float) {
                                val per = size.height.toFloat() / letters.size
                                val idx = (y / per).toInt().coerceIn(0, letters.size - 1)
                                if (idx == lastIdx) return
                                lastIdx = idx
                                val letter = letters[idx]
                                current = letter
                                onActive(letter)
                                onJump(letter)
                            }

                            pick(down.position.y)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                pick(change.position.y)
                                change.consume()
                            }
                            // 松手后停一会儿再收掉字母气泡
                            scope.launch {
                                delay(700)
                                current = null
                                onActive(null)
                            }
                        }
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                letters.forEach { letter ->
                    val active = letter == current
                    Box(
                        Modifier.height(step).fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            letter.toString(),
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) glassBlue else textColor.copy(alpha = 0.78f),
                            modifier = Modifier.padding(end = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 拖动索引条时浮出来的大字母气泡 */
@Composable
private fun ActiveLetterBubble(letter: Char, dark: Boolean) {
    // 不用 GlassCard：气泡压在内容上，需要比卡片更实的底才看得清
    val bg = if (dark) Color(0xFF1B2030) else Color.White
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(bg.copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.20f else 0.55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter.toString(),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = glassTextColor(dark)
        )
    }
}
