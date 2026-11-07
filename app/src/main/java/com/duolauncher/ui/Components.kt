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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    val wobbleAnim by rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobbleAngle"
    )
    val wobbleAngle = if (wobble) wobbleAnim else 0f

    // 外层 Box 不参与抖动：iOS 里只有图标轻微摆动，左上角「-」是静止的
    Box(contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(size + 14.dp)
                .graphicsLayer { rotationZ = wobbleAngle }
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
                val badge = DuoState.badgeOf(app.packageName)
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
    val wobbleAnim by rememberInfiniteTransition(label = "wobble2").animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobbleAngle2"
    )
    val wobbleAngle = if (wobble) wobbleAnim else 0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size + 14.dp)
            .graphicsLayer { rotationZ = wobbleAngle }
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

/** 主屏文件夹单元格：玻璃方块内叠加最多 4 个应用图标，下方显示文件夹名 */
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
    val wobbleAnim by rememberInfiniteTransition(label = "wobbleF").animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobbleAngleF"
    )
    val wobbleAngle = if (wobble) wobbleAnim else 0f
    val apps = folder.apps.mapNotNull { DuoState.appOf(it) }.take(4)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size + 14.dp)
            .graphicsLayer { rotationZ = wobbleAngle }
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
                    val mini = (size * 0.40f)
                    val off = (size * 0.16f)
                    for ((i, app) in apps.withIndex()) {
                        val row = i / 2
                        val col = i % 2
                        Image(
                            painter = rememberDrawablePainter(rememberAppIcon(app)),
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(mini)
                                .clip(squircle(18))
                                .align(Alignment.TopStart)
                                .then(if (row == 0) Modifier.padding(top = off) else Modifier.padding(top = size * 0.52f))
                                .then(if (col == 0) Modifier.padding(start = off) else Modifier.padding(start = size * 0.52f))
                        )
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

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    dark: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = glassTextColor(dark))
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = glassSubTextColor(dark), modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SettingToggle(title: String, subtitle: String? = null, checked: Boolean, dark: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, dark, trailing = {
        Switch(checked = checked, onCheckedChange = onChange)
    }, onClick = { onChange(!checked) })
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
 * iOS 风格「窗口」：点资源库分类卡 / 主屏文件夹后展开的浮窗。
 *
 * 居中一块小尺寸玻璃卡（宽 ≈ 屏宽 84%，最宽 372dp；高度随行数，最多 4 行后内部滚动）：
 * 标题 + 应用数 + 关闭按钮，下面是 4 列应用网格，底部可挂操作行。
 *
 * 玻璃效果：Android 12+ 走系统窗口级背景模糊（FLAG_BLUR_BEHIND + blurBehindRadius），
 * 底板半透明让背后的桌面/资源库虚化成磨砂；不支持模糊的机器自动退回近乎不透明的底板，
 * 避免背后的图标透上来叠成一片字。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderWindow(
    title: String,
    subtitle: String? = null,
    apps: List<AppInfo>,
    dark: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onLongClick: (AppInfo) -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val iconSize = 50.dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val blurOn = rememberDialogBlurBehind(radiusDp = 30f, dim = 0.16f)
        val shape = RoundedCornerShape(30.dp)
        val base = if (dark) Color(0xFF141926) else Color(0xFFF5F7FC)
        // 有窗口模糊时底板可以偏透（透出磨砂背景），没有模糊就必须近乎不透明
        val baseAlpha = if (blurOn) 0.80f else 0.97f
        val rows = ((apps.size + 3) / 4).coerceIn(1, 4)
        val rowH = 72.dp
        val vGap = 12.dp
        val gridH = rowH * rows + vGap * (rows - 1)

        // 点窗口外的空白处 = 返回（不再放 X 按钮）
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(if (blurOn) Color.Transparent else Color(0x44000000))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            val winW = minOf(maxWidth * 0.84f, 372.dp)
            Box(
                Modifier.width(winW)
                    .clip(shape)
                    .background(base.copy(alpha = baseAlpha))
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.80f), shape)
                    // 吃掉窗口内部的点击，避免穿透到遮罩把窗口关掉
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.50f), Color.Transparent),
                            endY = 160f
                        )
                    )
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = glassTextColor(dark), maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle != null) {
                                Text(
                                    subtitle, fontSize = 11.sp,
                                    color = glassSubTextColor(dark),
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    if (apps.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(rowH),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(Lang.s(R.string.no_apps_here), fontSize = 13.sp, color = glassSubTextColor(dark))
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().height(gridH),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(vGap)
                        ) {
                            items(apps, key = { it.packageName }) { app ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    AppIconCell(
                                        app = app,
                                        size = iconSize,
                                        dark = dark,
                                        showLabel = true,
                                        onClick = { onLaunch(app) },
                                        onLongClick = { onLongClick(app) }
                                    )
                                }
                            }
                        }
                    }

                    if (footer != null) {
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.fillMaxWidth()) { footer() }
                    }
                }
            }
        }
    }
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
