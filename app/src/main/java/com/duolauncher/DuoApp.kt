package com.duolauncher

import android.app.Application
import com.duolauncher.util.Lang
import com.duolauncher.util.ShortcutManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DuoApp : Application() {

    /** 启动后的小事务线程：别和首帧抢主线程 */
    private val bootIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "duo-boot").apply { isDaemon = true }
    }

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
        // 「分身 N」入口平时是收起的，重建进程后要把已被按键引用的槽位重新放出来，
        // 否则那些快捷键会因为组件被禁用而失效。
        // 这一步要读 33 个 Settings.System 键 + 6 次组件状态查询，全是跨进程调用；
        // 放在 Application.onCreate 主线程会直接拖慢冷启动（实测约 +40ms），
        // 挪到后台线程、错开首帧再做。晚一两秒不影响快捷键（没人会在开机瞬间按键）。
        bootIo.execute {
            runCatching { TimeUnit.MILLISECONDS.sleep(1200) }
            runCatching { ShortcutManager.syncToBound(this) }
        }
    }
}
