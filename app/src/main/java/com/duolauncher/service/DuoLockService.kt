package com.duolauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.duolauncher.data.AppRepository
import com.duolauncher.data.DuoState
import com.duolauncher.util.LetterKeys
import com.duolauncher.util.ShortcutActions
import com.duolauncher.util.ShortcutManager
import com.duolauncher.util.SystemLetterShortcuts

/**
 * 纯「锁屏」用途的无障碍服务。
 *
 * 只调用 [performGlobalAction] 的 GLOBAL_ACTION_LOCK_SCREEN，不读取窗口内容
 * （accessibility-service 里 canRetrieveWindowContent=false、不监听按键），
 * 因此系统权限页对它的描述是最小的一组。
 *
 * 之所以走无障碍而不是设备管理员：DPM.lockNow 需要用户把整个应用设为「设备管理员」，
 * 权限面过大（含擦除数据等），而 GLOBAL_ACTION_LOCK_SCREEN 只要求无障碍开关。
 */
class DuoLockService : AccessibilityService() {

    /** 正在按住的可绑定按键；-1 表示没有 */
    private var pressedCode = -1
    private var pressedAt = 0L

    /**
     * 只监听两个系统快捷键页所在的包（包过滤在系统侧完成），用来读字母键绑定。
     *
     * 不能只在启动器的「按键设置」页里临时开监听：用户在系统设置里改完字母键，
     * 再回到启动器时系统页早就关了，什么也读不到——2026-11-26 齐老师新配的长按 A
     * 就是这么丢的。改成常驻包过滤后，谁打开系统页都会被读到，平时没有任何唤醒。
     */
    private val captureHandler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null

    private fun dbg(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 实例在 onCreate 就登记，而不是等 onServiceConnected。
     *
     * 系统把服务「建好」到「回调 onServiceConnected」之间有一次 Binder 往返，
     * 界面恰好在这段时间里采样就看得到「已开启但未连接」——其实服务已经在跑了。
     * 提前登记可以消掉这段假空窗；[performGlobalAction] 要在连接完成后才有效，
     * 所以调用方仍以 [lockScreen] 的返回值为准。
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 覆盖安装后服务会稍晚才连上；连上就立刻装好包过滤监听
        applyCaptureConfig()
    }

    /**
     * 监听范围：只留按键过滤 + 两个系统快捷键页的窗口事件（包过滤）。
     * 环境里成千上万个窗口事件都不会叫醒本进程，只有打开系统快捷键页时才唤醒，
     * 读完即走，不需要为了这项只读展示长期读取整屏内容。
     */
    private fun applyCaptureConfig() {
        val info = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300L
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(ShortcutManager.SYSTEM_PACKAGE, "com.agui.keyboard")
        }
        runCatching { serviceInfo = info }
    }

    /**
     * 桌面上的字母键短按 / 长按 → 应用或系统动作。
     *
     * 只处理已绑定的键、只在桌面且不在输入框时处理；短按和长按各有独立目标。
     * 松手时才执行并拦下事件：按住期间不打断，松手才知道是短按还是长按。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val code = event?.keyCode ?: return false
        if (!LetterKeys.isBindable(code)) return false
        // 输入框（搜索页等）打字时完全不接管，避免把输入的字母当快捷键
        if (!DuoState.homeForeground || DuoState.textInputActive) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressedCode = code
                pressedAt = SystemClock.uptimeMillis()
                return false
            }

            KeyEvent.ACTION_UP -> {
                if (pressedCode != code) return false
                val held = SystemClock.uptimeMillis() - pressedAt
                pressedCode = -1
                val press = if (held >= LetterKeys.HOLD_MS) LetterKeys.Press.LONG
                else LetterKeys.Press.SHORT
                val target = LetterKeys.targetOf(code, press) ?: return false
                if (dbg()) android.util.Log.d("DuoKey", "code=$code held=$held press=$press target=$target")
                val ok = runCatching {
                    ShortcutActions.idOf(target)?.let { ShortcutActions.fire(applicationContext, it) }
                        ?: AppRepository.launch(applicationContext, target)
                }.getOrDefault(false)
                if (ok) {
                    runCatching {
                        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createOneShot(18, 90))
                        } else {
                            @Suppress("DEPRECATION") v.vibrate(18)
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != ShortcutManager.SYSTEM_PACKAGE && pkg != "com.agui.keyboard") return
        // 系统页刚打开时键盘图还没铺完，合并短时间内的多次事件，最后一次再读
        captureRunnable?.let { captureHandler.removeCallbacks(it) }
        val runnable = Runnable { captureNow(0) }
        captureRunnable = runnable
        captureHandler.postDelayed(runnable, 500)
    }

    /**
     * 读一次系统页上的字母键。
     *
     * 部分机型上键盘图是异步铺开的，第一次读可能只拿到一半，所以读到内容后再补读两次
     * （每次都会整体覆盖同一批字母，不会重复写），保证新加的绑定一定进来。
     */
    private fun captureNow(attempt: Int) {
        val n = SystemLetterShortcuts.capture(rootInActiveWindow)
        if (n > 0) {
            if (dbg()) android.util.Log.d("DuoSysKeys", "captured $n system letter shortcuts")
            DuoState.systemShortcutsUpdated = SystemClock.uptimeMillis()
        }
        if (attempt < 2) {
            captureHandler.postDelayed({ captureNow(attempt + 1) }, 700L + attempt * 500L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 一次全局动作的结果。
     *
     * 必须区分「服务没连上」和「服务连上了、但系统没执行」：
     * 后者的现场是系统侧 `SystemActionPerformer` 的 PendingIntent 已失效
     * （logcat 里是 `PendingIntent$CanceledException`），所有无障碍全局动作一起失效，
     * 重启手机可以恢复。旧代码把这两种都当成「未连接」，把用户送去无障碍页，
     * 结果怎么重新开关都没用 —— 2026-11-25 齐老师遇到的「双击还是报错」就是它。
     */
    enum class Action { OK, NOT_CONNECTED, FAILED }

    companion object {
        @Volatile
        private var instance: DuoLockService? = null

        /**
         * 服务进程已连上（可直接调用全局操作）。
         *
         * 与「用户已在系统设置里开启」是两件事：应用被覆盖安装后进程被杀，系统会把本服务
         * 记进 Crashed services 且不再自动重连，此时设置里仍显示「已开启」，但这里是 null。
         */
        fun isConnected(): Boolean = instance != null

        /** 旧名，等价于 [isConnected] */
        fun isRunning(): Boolean = isConnected()

        /**
         * 用户在系统设置里已开启本服务。
         *
         * 两路判据取 OR，因为不同 ROM 的实现不一致：
         *  ① AccessibilityManager 的「已启用服务列表」——系统真正认为生效的那份；
         *  ② Settings.Secure 的 enabled_accessibility_services 字符串——部分国产 ROM
         *     不回填 ① 的列表，只能读这个。
         * 只靠 ② 时，ROM 把状态存在自有数据库 / 字符串返回 null 就会误报「未开启」，
         * 用户看到的就是「明明开了，过一会儿又显示没开」。
         */
        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx.applicationContext, DuoLockService::class.java)
            val flat = expected.flattenToString()
            val short = expected.flattenToShortString()
            val simple = DuoLockService::class.java.simpleName
            runCatching {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                if (list.any { info ->
                        info.id == flat || info.id == short ||
                            info.resolveInfo?.serviceInfo?.let {
                                it.packageName == ctx.packageName && it.name == DuoLockService::class.java.name
                            } == true
                    }) return true
            }
            val enabled = runCatching {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            return enabled.split(':', ';').any {
                val v = it.trim()
                v.equals(flat, true) || v.equals(short, true) ||
                    v.endsWith("/$simple") || v.endsWith(".$simple")
            }
        }

        /**
         * 执行一次全局动作，并把「没连上」和「系统没执行」分开。
         *
         * `performGlobalAction` 返回 false 有两种完全不同的原因，提示与后续引导也不同：
         * 服务实例为空 = 授权没了 / 进程刚被拉起；实例在但返回 false = 系统侧动作失效。
         */
        private fun runAction(action: Int): Action {
            val svc = instance ?: return Action.NOT_CONNECTED
            val ok = runCatching { svc.performGlobalAction(action) }.getOrDefault(false)
            return if (ok) Action.OK else Action.FAILED
        }

        /** 息屏；返回 false 表示服务尚未连上或系统未执行（用 [lockScreenAction] 区分） */
        fun lockScreen(): Boolean = lockScreenAction() == Action.OK

        fun lockScreenAction(): Action =
            runAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

        /** 下拉通知栏 */
        fun openNotifications(): Boolean = openNotificationsAction() == Action.OK

        fun openNotificationsAction(): Action =
            runAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

        /** 展开快捷开关 / 控制中心 */
        fun openQuickSettings(): Boolean = openQuickSettingsAction() == Action.OK

        fun openQuickSettingsAction(): Action =
            runAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }
}
