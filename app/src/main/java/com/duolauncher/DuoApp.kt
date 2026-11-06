package com.duolauncher

import android.app.Application
import android.content.Context
import com.duolauncher.util.Lang

class DuoApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 工具类 Lang 从这里拿到全局 Context，进程起来后任何地方都能取当前语言的文案。
        // 状态仍由 MainActivity 的 DuoState.init 负责，这里不提前读盘。
        Lang.init(this)
    }
}
