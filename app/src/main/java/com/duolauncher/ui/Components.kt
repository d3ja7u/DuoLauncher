package com.duolauncher.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.graphics.drawable.Drawable
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.data.AppInfo
import com.duolauncher.data.DuoAction
import com.duolauncher.data.DuoState
import com.duolauncher.data.FolderItem

object ActionPalette {
    private val colors = listOf(
        listOf(Color(0xFF5AC8FA), Color(0xFF007AFF)),
        listOf(Color(0xFF7BE0A1), Color(0xFF34C759)),
        listOf(Color(0xFFFFB35C), Color(0xFFFF9500)),
        listOf(Color(0xFFFF8FA3), Color(0xFFFF2D55)),
        listOf(Color(0xFFB39CFF), Color(0xFF5856D6)),
        listOf(Color(0xFF8FD0FF), Color(0xFF3D7BFF))
    )
    fun brush(id: String): Brush {
        val i = (id.hashCode() and 0x7fffffff) % colors.size
        return Brush.linearGradient(colors[i])
    }
}

/**
 * iOS 风格未读角标：实心圆 / 胶囊 + 白色粗体数字，压在图标右上角（略微溢出图标边界）。
 * 高度跟着图标大小走，超过 99 显示「99+」。
 */
@Composable
fun NotificationBadge(count: Int, iconSize: Dp, color: Color) {
    val h = (iconSize.value * 0.40f).coerceIn(15f, 23f).dp
    val fs = (h.value * 0.62f).sp
    val text = if (count > 99) "99+" else count.toString()
    Box(
        Modifier
            .shadow(2.dp, CircleShape)
            .height(h)
            .defaultMinSize(minWidth = h)
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = h * 0.30f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fs,
            lineHeight = (fs.value * 0.98f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(Color(0x33000000), androidx.compose.ui.geometry.Offset(0f, 0.5f), 1.5f)
            )
        )
    }
}

/**
 * 本窗口此刻是否真的在「前台可见且可交互」（Activity RESUMED）。
 *
 * 为什么需要它：Compose 的动画协程挂在**组合**上，不随 Activity 停止而暂停。
 * 桌面是常驻进程（按 Home / 熄屏 / 切到别的应用都不销毁），所以任何
 * `LaunchedEffect { while (true) { ...; delay(...) } }` 都会在后台继续跑。
 * 每次写状态都会让那块 UI 失效并请求一帧，屏幕黑着也照跑 —— 这是启动器里
 * 唯一还能长期偷电的形状。要长期跑的动画（播放频谱等）一律拿这个当成闸门。
 */
@Composable
fun rememberWindowResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.RESUMED
        ))
    }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * 编辑态图标抖动角度。
 *
 * 关键：**非编辑态完全不创建无限动画**。旧实现给每个桌面图标都无条件
 * rememberInfiniteTransition 并在组合期读它的值，wobble=false 只是把结果换成 0，
 * 动画照样每 120ms 请求一帧、每帧重组整个图标子树 —— 桌面几十个图标 × 常驻，
 * CPU 一直无法休眠，这是启动器最典型的耗电源。
 * 编辑态把动画值放到 graphicsLayer 的绘制阶段读取，只重绘 layer 不重组子树。
 */
@Composable
private fun rememberWobble(enabled: Boolean): State<Float> {
    if (!enabled) return remember { mutableStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "wobble")
    return transition.animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobbleAngle"
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIconCell(
    app: AppInfo,
    size: Dp,
    dark: Boolean = false,
    showLabel: Boolean = true,
    onWallpaper: Boolean = false,
    wobble: Boolean = false,
    overrideIcon: Drawable? = null,
    /** 编辑主屏幕时在左上角显示 iOS 风格「-」按钮；非编辑态传 null */
    onMinusClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val wobbleAngle = rememberWobble(wobble)

    // 外层 Box 不参与抖动：iOS 里只有图标轻微摆动，左上角「-」是静止的
    Box(contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(size + 14.dp)
                .graphicsLayer { rotationZ = wobbleAngle.value }
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Box(Modifier.size(size)) {
                Image(
                    painter = rememberDrawablePainter(overrideIcon ?: rememberAppIcon(app)),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize().clip(squircle(24))
                )
                val badge = DuoState.badgeOf(app.id)
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = size * 0.06f, y = -size * 0.04f)
                    ) {
                        NotificationBadge(badge, size, Color(DuoState.badgeColor))
                    }
                }
            }
            if (showLabel) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = (11 * (size.value / 54f)).coerceIn(9.5f, 13f).sp,
                    textAlign = TextAlign.Center,
                    color = when {
                        onWallpaper -> Color.White
                        else -> glassTextColor(dark)
                    },
                    style = TextStyle(
                        shadow = if (onWallpaper) Shadow(Color(0x88000000), androidx.compose.ui.geometry.Offset(0f, 2f), 4f)
                        else null
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (onMinusClick != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-5).dp, y = (-9).dp)
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFFAEB4C0))
                    .clickable(onClick = onMinusClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Remove,
                    Lang.s(R.string.remove_from_home),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionIconCell(
    action: DuoAction,
    size: Dp,
    dark: Boolean = false,
    showLabel: Boolean = true,
    onWallpaper: Boolean = false,
    wobble: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val wobbleAngle = rememberWobble(wobble)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size + 14.dp)
            .graphicsLayer { rotationZ = wobbleAngle.value }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            Modifier
                .size(size)
                .clip(squircle(24))
                .background(ActionPalette.brush(action.id)),
            contentAlignment = Alignment.Center
        ) {
            Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                action.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = (11 * (size.value / 54f)).coerceIn(9.5f, 13f).sp,
                textAlign = TextAlign.Center,
                color = if (onWallpaper) Color.White else glassTextColor(dark),
                style = TextStyle(
                    shadow = if (onWallpaper) Shadow(Color(0x88000000), androidx.compose.ui.geometry.Offset(0f, 2f), 4f)
                    else null
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 主屏文件夹单元格：玻璃方块内叠 3×3 迷你图标（最多 9 个），下方显示文件夹名。
 *
 * 迷你图标用 `offset` 绝对定位，不能再靠 padding：`size()` 写在链最外层时，
 * padding 是从自身尺寸里"内缩"，第 2 格之后 padding 已经大于图标尺寸，
 * 内容被压成 0 —— 这就是「文件夹里只看得见第一个应用、看着像没更新」的原因。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCell(
    folder: FolderItem,
    size: Dp,
    dark: Boolean = false,
    onWallpaper: Boolean = false,
    wobble: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val wobbleAngle = rememberWobble(wobble)
    val apps = folder.apps.mapNotNull { DuoState.appOf(it) }.take(9)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size + 14.dp)
            .graphicsLayer { rotationZ = wobbleAngle.value }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        GlassCard(
            modifier = Modifier.size(size),
            radius = (size * 0.30f),
            level = 0.5f,
            dark = dark
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (apps.isEmpty()) {
                    Icon(Icons.Filled.Folder, null, tint = glassTextColor(dark), modifier = Modifier.size(size * 0.42f))
                } else {
                    // 3×3 迷你网格：整块占方块 74%，单格再留一点空隙
                    val inner = size * 0.74f
                    val cellW = inner / 3f
                    val mini = cellW * 0.88f
                    val inset = (cellW - mini) / 2f
                    Box(Modifier.size(inner)) {
                        apps.forEachIndexed { i, app ->
                            val r = i / 3
                            val c = i % 3
                            Image(
                                painter = rememberDrawablePainter(rememberAppIcon(app)),
                                contentDescription = app.label,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = cellW * c + inset, y = cellW * r + inset)
                                    .size(mini)
                                    .clip(squircle(16))
                            )
                        }
                    }
                }
            }
        }
        if (true) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = (11 * (size.value / 54f)).coerceIn(9.5f, 13f).sp,
                textAlign = TextAlign.Center,
                color = if (onWallpaper) Color.White else glassTextColor(dark),
                style = TextStyle(
                    shadow = if (onWallpaper) Shadow(Color(0x88000000), androidx.compose.ui.geometry.Offset(0f, 2f), 4f)
                    else null
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 把应用放入已有文件夹或新建文件夹 */
@Composable
fun FolderPickerDialog(
    app: AppInfo,
    dark: Boolean,
    onDismiss: () -> Unit,
    onResult: (folderId: String?, newName: String?) -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    DuoDialog(title = Lang.s(R.string.add_to_folder), dark = dark, onDismiss = onDismiss) {
        if (creating) {
            BasicTextField(
                name, { name = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = glassTextColor(dark)),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(glassTextColor(dark).copy(alpha = 0.12f)).padding(10.dp)
            )
            Spacer(Modifier.height(10.dp))
            GlassPrimaryButton(Lang.s(R.string.create_and_add), dark, { onResult(null, name.ifBlank { Lang.s(R.string.folder) }) })
        } else {
            if (DuoState.folders.isEmpty())
                Text(Lang.s(R.string.no_folder_yet), fontSize = 12.sp, color = glassSubTextColor(dark))
            DuoState.folders.toList().forEach { f ->
                MenuRow(Icons.Filled.Folder, f.name, dark = dark) { onResult(f.id, null) }
            }
            MenuRow(Icons.Filled.CreateNewFolder, Lang.s(R.string.new_folder), dark = dark) { creating = true }
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, dark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) Color(0xFFFF3B30) else glassTextColor(dark), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            fontSize = 15.sp,
            color = if (danger) Color(0xFFFF3B30) else glassTextColor(dark)
        )
    }
}

/** 长按图标菜单 */
@Composable
fun AppContextMenu(
    app: AppInfo,
    dark: Boolean,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onRemoveHome: (() -> Unit)?,
    onHide: () -> Unit,
    onInfo: () -> Unit,
    onCategory: () -> Unit,
    onAddSidebar: () -> Unit,
    onAddToFolder: (() -> Unit)? = null,
    onAddHome: (() -> Unit)? = null,
    onChangeIcon: (() -> Unit)? = null
) {
    DuoDialog(title = app.label, dark = dark, onDismiss = onDismiss) {
        onAddHome?.let { MenuRow(Icons.Filled.AddToHomeScreen, Lang.s(R.string.add_to_home), dark = dark, onClick = { onDismiss(); it() }) }
        onAddToFolder?.let { MenuRow(Icons.Filled.Folder, Lang.s(R.string.add_to_folder), dark = dark, onClick = { onDismiss(); it() }) }
        MenuRow(Icons.Filled.Apps, Lang.s(R.string.add_to_sidebar), dark = dark, onClick = { onDismiss(); onAddSidebar() })
        MenuRow(Icons.Filled.Category, Lang.s(R.string.edit_app_category), dark = dark, onClick = { onDismiss(); onCategory() })
        onChangeIcon?.let { MenuRow(Icons.Filled.Palette, Lang.s(R.string.change_icon), dark = dark, onClick = { onDismiss(); it() }) }
        onRemoveHome?.let { MenuRow(Icons.Filled.RemoveCircleOutline, Lang.s(R.string.remove_from_home), dark = dark, onClick = { onDismiss(); it() }) }
        MenuRow(Icons.Filled.VisibilityOff, Lang.s(R.string.hide_app), dark = dark, onClick = { onDismiss(); onHide() })
        MenuRow(Icons.Filled.Info, Lang.s(R.string.app_info), dark = dark, onClick = { onDismiss(); onInfo() })
        MenuRow(Icons.Filled.Delete, Lang.s(R.string.uninstall), danger = true, dark = dark, onClick = { onDismiss(); onUninstall() })
    }
}

@Composable
fun DuoDialog(
    title: String,
    dark: Boolean,
    onDismiss: () -> Unit,
    level: Float = 0.8f,
    opaque: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        // 长文本弹窗（如更新说明）需要更实的底板，否则背后的设置项文字会透上来干扰阅读。
        // 默认关闭，其它弹窗保持原来的通透玻璃效果。
        val shape = RoundedCornerShape(28.dp)
        val cardModifier = Modifier.fillMaxWidth().wrapContentHeight().let {
            if (opaque) it.background(if (dark) Color(0xEE1B2030) else Color(0xF5FFFFFF), shape)
            else it
        }
        GlassCard(modifier = cardModifier, radius = 28.dp, level = level, dark = dark) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark))
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/** 一排紧凑的分段选项（最近常用数量 / 搜索引擎等共用） */
@Composable
fun SegmentedChips(
    options: List<Pair<String, String>>,
    selected: String,
    dark: Boolean,
    onSelect: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else glassTextColor(dark),
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) glassBlue else glassTextColor(dark).copy(alpha = 0.10f))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(5.dp))
        }
    }
}

/**
 * 编辑主屏幕时点「-」弹出的 iOS 风格移除确认：
 * 从主屏幕移除（保留在 App 资源库）/ 删除 App / 取消。
 */
@Composable
fun RemoveAppDialog(
    appLabel: String,
    dark: Boolean,
    onDismiss: () -> Unit,
    onRemoveHome: () -> Unit,
    onUninstall: () -> Unit
) {
    DuoDialog(
        title = Lang.s(R.string.remove_app_title, appLabel),
        dark = dark,
        onDismiss = onDismiss,
        level = 0.86f,
        opaque = true
    ) {
        Text(
            Lang.s(R.string.remove_app_desc),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = glassSubTextColor(dark)
        )
        Spacer(Modifier.height(16.dp))
        RemoveDialogButton(Lang.s(R.string.remove_from_home_ios), false, false, dark) {
            onDismiss(); onRemoveHome()
        }
        Spacer(Modifier.height(9.dp))
        RemoveDialogButton(Lang.s(R.string.delete_app), true, false, dark) {
            onDismiss(); onUninstall()
        }
        Spacer(Modifier.height(9.dp))
        RemoveDialogButton(Lang.s(R.string.cancel), false, true, dark) { onDismiss() }
    }
}

@Composable
private fun RemoveDialogButton(
    text: String,
    danger: Boolean,
    secondary: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    // 浅色主题下弹窗底板接近纯白，取消按钮若仍用透明底 + 白字会完全看不见
    val bg = when {
        secondary && dark -> Color(0x22FFFFFF)
        secondary -> Color(0xFFE7E9F2)
        else -> glassBlue.copy(alpha = 0.52f)
    }
    val fg = when {
        danger -> Color(0xFFFF453A)
        secondary && !dark -> Color(0xFF1B2030)
        else -> Color.White
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun SectionTitle(text: String, dark: Boolean) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = glassSubTextColor(dark),
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp)
    )
}

/** iOS 设置卡里的细分隔线：深色用极淡白、浅色用极淡黑，只在卡片内出现 */
private fun settingDividerColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.08f)

/** iOS 开关的绿色（不跟随 App 主题色，开关本身就是系统语言的绿） */
private val IOS_SWITCH_GREEN = Color(0xFF34C759)

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    dark: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    /** 行首图标（iOS 设置那种圆角方块）。传了之后行高、分隔线起点都跟着它走 */
    leading: @Composable (() -> Unit)? = null,
    /** 行底细线；卡片里除最后一行都画 */
    divider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    // 外层 Column 是为了把「行」和「分隔线」分开：线要贴到整行底部，
    // 不能算进 clickable 的 padding 里，否则点线也会触发这一行。
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (leading != null) 11.dp else 13.dp,
                    bottom = if (leading != null) 11.dp else 13.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = glassTextColor(dark))
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = glassSubTextColor(dark),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (divider) {
            // 起点跟着图标走（iOS 的线从文字左边缘开始，不切到图标下面）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (leading != null) 63.dp else 16.dp)
                    .height(0.5.dp)
                    .background(settingDividerColor(dark))
            )
        }
    }
}

@Composable
fun SettingToggle(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    dark: Boolean,
    leading: @Composable (() -> Unit)? = null,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        title, subtitle, dark,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IOS_SWITCH_GREEN,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = if (dark) Color(0xFF39393D) else Color(0xFFE9E9EB),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        },
        onClick = { onChange(!checked) },
        leading = leading,
        divider = divider
    )
}

@Composable
fun GlassTopBar(title: String, dark: Boolean, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, Lang.s(R.string.back), tint = glassTextColor(dark))
        }
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = glassTextColor(dark), modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

@Composable
fun GlassPrimaryButton(text: String, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Color(0xFF5B7CFF) else Color(0xFF3D6BFF),
            contentColor = Color.White
        )
    ) { Text(text, fontSize = 14.sp) }
}


/**
 * 给 Dialog 窗口挂上系统级背景模糊（玻璃磨砂）。
 * 返回是否可用；不可用时调用方应把底板做成近乎不透明。
 */
@Composable
private fun rememberDialogBlurBehind(radiusDp: Float, dim: Float): Boolean {
    val ctx = LocalContext.current
    val view = LocalView.current
    val supported = remember(ctx) { crossWindowBlurEnabled(ctx) }
    LaunchedEffect(supported) {
        if (!supported) return@LaunchedEffect
        val win = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        win.addFlags(
            WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        val density = view.resources.displayMetrics.density
        win.attributes = win.attributes.apply {
            setBlurBehindRadius((radiusDp * density).toInt())
            dimAmount = dim
        }
    }
    return supported
}

private fun crossWindowBlurEnabled(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
    return runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
}

/**
 * iOS 风格文件夹窗口：主屏点开文件夹、资源库点开分类卡都用这一个。
 *
 *  - 文件夹名在**框外左上角**（粗体 + 阴影），框内不显示「N 个应用」；
 *  - 一排 4 个、一页最多 4 行（16 个），**第一页填满才出第二页，右滑切换**（不再上下滑）；
 *    底部小圆点指示当前页；
 *  - 面板比背景更深：半透明深色底 + 系统窗口级背景模糊（Android 12+），
 *    不支持模糊的机器自动加深底色，避免背后的图标透上来。
 *
 * [dark] = true（桌面文件夹 / 暗色主题）走黑面板 + 白字；
 * false（亮色资源库）走浅玻璃底 + 深字，避免在浅色页面上突兀。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderIosWindow(
    name: String,
    apps: List<AppInfo>,
    dark: Boolean = true,
    onDismiss: () -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onLongClick: (AppInfo) -> Unit = {}
) {
    val shape = RoundedCornerShape(34.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val blurOn = rememberDialogBlurBehind(radiusDp = 44f, dim = 0.28f)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    if (blurOn) Color.Transparent
                    else Color.Black.copy(alpha = if (dark) 0.40f else 0.18f)
                )
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            val winW = minOf(maxWidth * 0.88f, 386.dp)
            val iconSize = 54.dp
            val cellH = 82.dp
            val vGap = 12.dp
            // 最多 4 行；横屏 / 小屏再按可用高度收，保证窗口始终完整露出
            val totalRows = ((apps.size + 3) / 4).coerceAtLeast(1)
            val roomFor = (((maxHeight * 0.58f) + vGap) / (cellH + vGap)).toInt()
            val visibleRows = minOf(totalRows, roomFor.coerceIn(1, 4))
            val gridH = cellH * visibleRows + vGap * (visibleRows - 1)
            // 横向分页：一页 4 列 × visibleRows 行，第一页填满才出现第二页
            val perPage = 4 * visibleRows
            val pageCount = if (apps.isEmpty()) 1 else (apps.size + perPage - 1) / perPage
            val pagerState = rememberPagerState(pageCount = { pageCount })

            val panelColor = if (dark) Color.Black else Color(0xFFF5F7FC)
            val panelAlpha = if (blurOn) (if (dark) 0.34f else 0.80f)
            else (if (dark) 0.70f else 0.97f)
            val txt = if (dark) Color.White else glassTextColor(false)
            val subTxt = if (dark) Color.White.copy(alpha = 0.62f) else glassSubTextColor(false)

            Column(Modifier.width(winW)) {
                // 文件夹名：框外左上角
                Text(
                    text = name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = txt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        shadow = if (dark)
                            Shadow(Color(0xAA000000), androidx.compose.ui.geometry.Offset(0f, 2f), 8f)
                        else null
                    ),
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        // 比背景更深的一层：有模糊时仍保留明显压暗，避免和壁纸糊成一片
                        .background(panelColor.copy(alpha = panelAlpha))
                        .border(1.dp, Color.White.copy(alpha = if (dark) 0.14f else 0.75f), shape)
                        // 吃掉框内点击，别穿透到遮罩把窗口关掉
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    if (apps.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(cellH),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(Lang.s(R.string.no_apps_here), fontSize = 13.sp, color = subTxt)
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(gridH)
                            ) { page ->
                                val from = page * perPage
                                val to = minOf(from + perPage, apps.size)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(vGap)
                                ) {
                                    for (r in 0 until visibleRows) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(cellH),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            for (c in 0 until 4) {
                                                val idx = from + r * 4 + c
                                                Box(
                                                    Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (idx < to) {
                                                        val app = apps[idx]
                                                        AppIconCell(
                                                            app = app,
                                                            size = iconSize,
                                                            dark = dark,
                                                            showLabel = true,
                                                            onWallpaper = dark,
                                                            onClick = { onLaunch(app) },
                                                            onLongClick = { onLongClick(app) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 页面指示点：超过一页才显示，跟随当前页
                            if (pageCount > 1) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(pageCount) { i ->
                                        val active = i == pagerState.currentPage
                                        Box(
                                            Modifier
                                                .size(if (active) 7.dp else 5.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (active) txt else txt.copy(alpha = 0.28f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 首次启动的一次性匿名统计告知。
 * 不点「保持开启」就绝不会联网；点外部或返回键只是暂不决定，下次启动还会再问。
 */
@Composable
fun StatsConsentDialog(
    dark: Boolean,
    visible: Boolean,
    onDecideLater: () -> Unit,
    onKeep: () -> Unit,
    onDisable: () -> Unit
) {
    if (!visible) return
    DuoDialog(
        Lang.s(R.string.stats_consent_title), dark, opaque = true,
        onDismiss = onDecideLater
    ) {
        Text(
            Lang.s(R.string.stats_consent_body),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = glassSubTextColor(dark)
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDisable) {
                Text(
                    Lang.s(R.string.stats_disable),
                    fontSize = 13.sp,
                    color = glassSubTextColor(dark)
                )
            }
            Spacer(Modifier.weight(1f))
            GlassPrimaryButton(Lang.s(R.string.stats_keep_on), dark, onClick = onKeep)
        }
    }
}
