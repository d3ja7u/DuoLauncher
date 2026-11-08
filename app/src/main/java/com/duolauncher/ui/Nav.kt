package com.duolauncher.ui

import androidx.compose.ui.graphics.Color
import com.duolauncher.data.AppInfo

/** 各页面共享的导航 / 交互入口 */
class Nav(
    /** 打开搜索页：与跳转 App 资源库一样，用 pager 的 animateScrollToPage */
    val openSearch: () -> Unit,
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
