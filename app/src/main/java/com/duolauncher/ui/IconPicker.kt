package com.duolauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.AppInfo
import com.duolauncher.data.DuoState
import com.duolauncher.data.IconPacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手动挑选图标：图标包的 appfilter.xml 常常收录不全，匹配不到的应用可以在这里
 * 直接从包里挑一个图标（存到 DuoState.iconOverride，跨图标包也生效）。
 *
 * 窗口几乎通屏，顶部是应用名 + 搜索框，下面是图标网格（4 列，标出资源名便于分辨）。
 */
@Composable
fun IconPickerDialog(
    app: AppInfo,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val pack = DuoState.iconPack
    val packLabel = remember(pack) {
        if (pack.isBlank()) "" else
            runCatching { IconPacks.listPacks(ctx).firstOrNull { it.pkg == pack }?.label }.getOrNull() ?: pack
    }
    var query by remember { mutableStateOf("") }
    var all by remember(pack) { mutableStateOf<List<String>>(emptyList()) }
    // 资源名 -> 原本对应的应用名（图标包里资源名常常是 icon00a1b2 这种，靠它才认得出）
    var owners by remember(pack) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember(pack) { mutableStateOf(true) }
    val current = DuoState.iconOverride[app.id]

    LaunchedEffect(pack) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            val names = runCatching { IconPacks.listDrawables(ctx, pack) }.getOrDefault(emptyList())
            val own = runCatching { IconPacks.drawableOwners(ctx, pack) }.getOrDefault(emptyMap())
            names to own
        }
        all = loaded.first
        owners = loaded.second
        loading = false
    }

    val shown = remember(all, query, owners) {
        if (query.isBlank()) all
        else all.filter {
            it.contains(query.trim(), ignoreCase = true) ||
                owners[it]?.contains(query.trim(), ignoreCase = true) == true
        }
    }

    // Dialog 窗口本身透明，这里自绘不透明底板（沿用文件夹窗口的做法）
    val shape = RoundedCornerShape(30.dp)
    val base = if (dark) Color(0xFF141926) else Color(0xFFF5F7FC)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x44000000))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.86f)
                    .clip(shape)
                    .background(base.copy(alpha = 0.98f))
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.16f else 0.85f), shape)
                    // 吃掉窗口内部的点击，避免误关
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (dark) 0.06f else 0.5f), Color.Transparent),
                            endY = 160f
                        )
                    )
                )
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp)) {
                    // ── 标题 ──
                    Text(
                        Lang.s(R.string.change_icon),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = glassTextColor(dark)
                    )
                    Text(
                        if (pack.isBlank()) Lang.s(R.string.no_icon_pack_selected)
                        else Lang.s(R.string.icon_from_pack, app.label, packLabel),
                        fontSize = 11.5.sp,
                        color = glassSubTextColor(dark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    if (pack.isBlank()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                Lang.s(R.string.no_pack_chosen),
                                fontSize = 13.sp,
                                color = glassSubTextColor(dark)
                            )
                        }
                    } else {

                    // ── 搜索 ──
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(glassTextColor(dark).copy(alpha = 0.09f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Search, null,
                            tint = glassSubTextColor(dark), modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    Lang.s(R.string.icon_search_hint),
                                    fontSize = 13.sp, color = glassSubTextColor(dark)
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, color = glassTextColor(dark)),
                                cursorBrush = SolidColor(glassBlue),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            "${shown.size}/${all.size}",
                            fontSize = 10.sp,
                            color = glassSubTextColor(dark)
                        )
                    }

                    // ── 网格 ──
                    Spacer(Modifier.height(6.dp))
                    if (loading) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(Lang.s(R.string.reading_icon_pack), fontSize = 13.sp, color = glassSubTextColor(dark))
                        }
                    } else if (shown.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                if (all.isEmpty()) Lang.s(R.string.no_icons_in_pack) else Lang.s(R.string.no_matching_icons),
                                fontSize = 13.sp, color = glassSubTextColor(dark)
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(shown, key = { it }) { name ->
                                val d = remember(pack, name) { IconPacks.drawableOf(ctx, pack, name) }
                                val picked = current == "$pack|$name"
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (picked) glassBlue.copy(alpha = 0.16f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            DuoState.iconOverride[app.id] = "$pack|$name"
                                            DuoState.save()
                                            onDismiss()
                                        }
                                        .padding(vertical = 6.dp, horizontal = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier
                                            .size(56.dp)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (d != null) {
                                            Image(
                                                painter = rememberDrawablePainter(d),
                                                contentDescription = name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .background(glassTextColor(dark).copy(alpha = 0.08f))
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    val owner = owners[name]
                                    Text(
                                        owner ?: name,
                                        fontSize = if (owner != null) 9.sp else 8.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        color = when {
                                            picked -> glassBlue
                                            owner != null -> glassTextColor(dark)
                                            else -> glassSubTextColor(dark)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    }

                    // ── 底部操作 ──
                    BottomBar(
                        dark = dark,
                        onReset = if (current != null) ({
                            DuoState.iconOverride.remove(app.id)
                            DuoState.save()
                            onDismiss()
                        }) else null,
                        onCancel = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(dark: Boolean, onReset: (() -> Unit)?, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onReset != null) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(glassTextColor(dark).copy(alpha = 0.10f))
                    .clickable(onClick = onReset)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(Lang.s(R.string.restore_auto_icon), fontSize = 13.sp, color = glassTextColor(dark))
            }
        }
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(glassBlue.copy(alpha = 0.92f))
                .clickable(onClick = onCancel)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(Lang.s(R.string.close), fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
