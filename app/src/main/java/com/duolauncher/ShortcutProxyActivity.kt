package com.duolauncher

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoState
import com.duolauncher.util.Lang
import com.duolauncher.util.ShortcutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快捷键「应用分身」转发器。
 *
 * 系统原生快捷键只认普通应用的 ComponentName，写不了另一个用户的分身应用。
 * DuoLauncher 把分身目标记在 ShortcutManager / DuoState 里，并让系统按键启动
 * 本 Activity 的某个别名；这里再按 slot 找到 AppInfo.id，复用 LauncherApps
 * 把分身应用真正打开。透明 Activity 只做转发，不在最近任务里出现。
 */
class ShortcutProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alias = intent?.component?.className.orEmpty().substringAfterLast('.')
        val slot = ShortcutManager.slotOfAlias(alias)

        DuoState.init(this)
        val appId = slot?.let { ShortcutManager.appIdOfSlot(it) }
        if (appId.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            // 进程可能刚被系统拉起，内存里还没有应用快照，这里同步补一次。
            val apps = withContext(Dispatchers.IO) {
                AppRepository.loadApps(this@ShortcutProxyActivity)
            }
            DuoState.replaceApps(apps)
            val ok = AppRepository.launch(this@ShortcutProxyActivity, appId)
            if (!ok) {
                Toast.makeText(
                    this@ShortcutProxyActivity,
                    Lang.s(R.string.shortcut_launch_fail),
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }
}
