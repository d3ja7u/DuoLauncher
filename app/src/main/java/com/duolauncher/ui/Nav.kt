package com.duolauncher.ui

import androidx.compose.ui.graphics.Color
import com.duolauncher.data.AppInfo

/** 各页面共享的导航 / 交互入口 */
class Nav(
    val openSearch: () -> Unit,
    /** 右滑手势进行中：把手指位移映射成 0..1 的搜索页揭示进度（逐帧直接写入，跟手） */
    val searchDrag: (Float) -> Unit,
    /** 右滑松手：commit=true 补齐到搜索页，false 回弹到桌面 */
    val searchRelease: (Boolean) -> Unit,
    /** 搜索页左滑进行中：传入剩余揭示进度（1→0），逐帧跟手滑回桌面 */
    val searchCloseDrag: (Float) -> Unit,
    /** 搜索页左滑松手：commit=true 关回桌面，false 弹回搜索页 */
    val searchCloseRelease: (Boolean) -> Unit,
    val openSettings: () -> Unit,
    val openSidebarEditor: () -> Unit,
    val openCategoryEditor: () -> Unit,
    val openHiddenEditor: () -> Unit,
    val openWidgetPicker: () -> Unit,
    val showAppMenu: (AppInfo, String) -> Unit,
    val showCategoryPicker: (AppInfo) -> Unit,
    val goPage: (Int) -> Unit,
    /** 跳转到 App 资源库页（自动处理倒序翻页与页数变化） */
    val goLibrary: () -> Unit,
    val toast: (String) -> Unit
)

val glassBlue = Color(0xFF3D6BFF)
