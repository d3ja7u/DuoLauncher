package com.duolauncher

import android.app.Application
import android.content.pm.ShortcutManager
import com.duolauncher.util.Lang

class DuoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 工具类 Lang 从这里拿到全局 Context，进程起来后任何地方都能取当前语言的文案。
        // 状态仍由 MainActivity 的 DuoState.init 负责，这里不提前读盘。
        //
        // 这里不再 override attachBaseContext 去换 Locale：Android 17 上系统在
        // handleBindApplication 里会立刻拿 Application 的 baseContext 取 Resources，
        // 换成 createConfigurationContext 返回的 Context 时 resources 还是 null，
        // 直接 NPE（覆盖安装后第一次启动必崩）。语言由两处保证，不需要它：
        //   · Lang.init 用一个本地化后的 Application Context 取文案（Lang.s）；
        //   · MainActivity.attachBaseContext 给界面资源套 Locale。
        Lang.init(this)
        // 清掉早期测试版注册过、但代码里早已删除的动态快捷方式（系统会把它们一直缓存下来，
        // 在「系统键盘快捷方式 → 所有快捷方式」里显示为 DuoLauncher 的「小窗测试」）。
        runCatching {
            val sm = getSystemService(ShortcutManager::class.java)
            sm?.dynamicShortcuts?.filter { it.id == "bubble_test_shortcut" }
                ?.let { if (it.isNotEmpty()) sm.removeDynamicShortcuts(listOf("bubble_test_shortcut")) }
        }
    }
}
