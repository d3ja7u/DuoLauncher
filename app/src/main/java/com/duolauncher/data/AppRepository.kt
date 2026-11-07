package com.duolauncher.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.duolauncher.R
import com.duolauncher.util.Lang
import com.duolauncher.DuoDeviceAdmin
import com.duolauncher.service.DuoNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

object AppRepository {

    suspend fun loadApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val pm = ctx.packageManager
        val collator = Collator.getInstance(Locale.CHINA)
        val out = mutableListOf<AppInfo>()
        val profilesSeen = LinkedHashSet<UserHandle>()

        // 只有 LauncherApps 能枚举「克隆空间 / 工作资料」里的应用；
        // PackageManager.queryIntentActivities 只能看到当前用户（主用户）。
        val la = runCatching {
            ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        }.getOrNull()
        val um = runCatching {
            ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        }.getOrNull()

        if (la != null && um != null) {
            try {
                val profiles = LinkedHashSet<UserHandle>()
                runCatching { la.profiles?.let { profiles.addAll(it) } }
                runCatching { um.userProfiles?.let { profiles.addAll(it) } }
                profilesSeen.addAll(profiles)
                val density = ctx.resources.displayMetrics.densityDpi
                val me = Process.myUserHandle()
                for (uh in profiles) {
                    val serial = runCatching { um.getSerialNumberForUser(uh) }.getOrDefault(-1L)
                    val userId = if (uh == me) 0
                        else if (serial >= 0) serial.toInt() else uh.hashCode()
                    val activities = runCatching { la.getActivityList(null, uh) }.getOrDefault(emptyList())
                    for (info in activities) {
                        runCatching {
                            val cn = info.componentName
                            val rawIcon = if (userId == 0) info.getIcon(density) else info.getBadgedIcon(density)
                            val icon: Drawable = rawIcon ?: ColorDrawable(Color.DKGRAY)
                            val ai = info.applicationInfo
                            AppInfo(
                                label = info.label.toString() +
                                    if (userId == 0) "" else Lang.s(R.string.app_clone_suffix),
                                packageName = cn.packageName,
                                icon = icon,
                                systemCategory = if (Build.VERSION.SDK_INT >= 26) ai.category else -1,
                                activityClass = cn.className,
                                userId = userId,
                                userHandle = uh
                            )
                        }.getOrNull()?.let { out.add(it) }
                    }
                }
            } catch (e: Exception) {
                out.clear()
            }
        }

        // 回退：LauncherApps 不可用（例如不是默认桌面）时，至少保证主用户应用正常显示
        if (out.isEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                if (Build.VERSION.SDK_INT >= 33)
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) { emptyList() }
            resolved.mapNotNullTo(out) { ri ->
                runCatching {
                    val ai = ri.activityInfo.applicationInfo
                    val icon: Drawable = try { ri.loadIcon(pm) } catch (e: Exception) { ColorDrawable(Color.DKGRAY) }
                    AppInfo(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        icon = icon,
                        systemCategory = if (Build.VERSION.SDK_INT >= 26) ai.category else -1,
                        activityClass = ri.activityInfo.name
                    )
                }.getOrNull()
            }
        }

        // 按「包名 + 用户」去重：两个微信包名相同但属于不同用户，必须都保留
        val result = out.distinctBy { it.id }.sortedWith(compareBy(collator) { it.label })
        android.util.Log.d("DuoApps", "loaded ${result.size} apps, profiles=${profilesSeen.size}, clones=${result.count { it.isClone }}")
        result
    }

    /** 传入 AppInfo.id（主用户是纯包名，克隆应用是 `包名@用户序号`） */
    fun launch(context: Context, id: String): Boolean {
        val app = DuoState.appOf(id)
        return try {
            if (app != null && app.isClone && app.userHandle != null && app.activityClass.isNotEmpty()) {
                val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startMainActivity(
                    ComponentName(app.packageName, app.activityClass),
                    app.userHandle, null, null
                )
            } else {
                val pkg = app?.packageName ?: id.substringBefore("@")
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
            }
            val key = app?.id ?: id
            DuoState.recordUsage(key)
            // 打开过就算已读：清掉该应用的角标（与 iOS 一致）
            runCatching { DuoNotificationListener.dismiss(key) }
            true
        } catch (e: Exception) { false }
    }

    /** 克隆空间里的应用第三方无法跨用户卸载，改为打开该资料下的应用详情页 */
    fun uninstall(context: Context, id: String) {
        val app = DuoState.appOf(id)
        if (app != null && app.isClone && app.userHandle != null && app.activityClass.isNotEmpty()) {
            runCatching {
                val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startAppDetailsActivity(
                    ComponentName(app.packageName, app.activityClass),
                    app.userHandle, null, null
                )
            }
            return
        }
        runCatching {
            val pkg = app?.packageName ?: id.substringBefore("@")
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun appDetails(context: Context, id: String) {
        val app = DuoState.appOf(id)
        if (app != null && app.isClone && app.userHandle != null && app.activityClass.isNotEmpty()) {
            runCatching {
                val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startAppDetailsActivity(
                    ComponentName(app.packageName, app.activityClass),
                    app.userHandle, null, null
                )
            }
            return
        }
        runCatching {
            val pkg = app?.packageName ?: id.substringBefore("@")
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun setDefaultLauncher(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun wallpaper(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

data class DuoAction(
    val id: String,
    /** 存资源 id 而不是文案，切换语言后无需重建列表 */
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector
) {
    val label: String get() = Lang.s(labelRes)
}

object DuoActions {
    val ALL: List<DuoAction> = listOf(
        DuoAction("search", R.string.search, Icons.Filled.Search),
        DuoAction("library", R.string.action_library, Icons.Filled.GridView),
        // 负一屏已下线，这个动作改成「回主屏第一页」（id 保持 today 以兼容旧配置）
        DuoAction("today", R.string.action_today, Icons.Filled.Home),
        DuoAction("settings", R.string.launcher_settings, Icons.Filled.Settings),
        DuoAction("flashlight", R.string.action_flashlight, Icons.Filled.FlashlightOn),
        DuoAction("wifi", R.string.action_wifi, Icons.Filled.Wifi),
        DuoAction("bluetooth", R.string.action_bluetooth, Icons.Filled.Bluetooth),
        DuoAction("data", R.string.action_data, Icons.Filled.SignalCellularAlt),
        DuoAction("camera", R.string.action_camera, Icons.Filled.PhotoCamera),
        DuoAction("dial", R.string.action_dial, Icons.Filled.Call),
        DuoAction("silent", R.string.action_silent, Icons.Filled.VolumeOff),
        DuoAction("lock", R.string.action_lock, Icons.Filled.Lock),
        DuoAction("wallpaper", R.string.change_wallpaper, Icons.Filled.Wallpaper),
        DuoAction("recents", R.string.widget_recents, Icons.Filled.History)
    )

    fun of(id: String): DuoAction = ALL.firstOrNull { it.id == id }
        ?: DuoAction(id, R.string.action_unknown, Icons.Filled.Circle)

    /** navigate: search / library / today / settings / recents */
    fun perform(context: Context, id: String, navigate: (String) -> Unit) {
        when (id) {
            "search", "library", "today", "settings", "recents" -> navigate(id)
            "flashlight" -> toggleTorch(context)
            "wifi" -> open(context, Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> open(context, Settings.ACTION_BLUETOOTH_SETTINGS)
            "data" -> open(context, Settings.ACTION_WIRELESS_SETTINGS)
            "camera" -> open(context, MediaStore.ACTION_IMAGE_CAPTURE)
            "dial" -> open(context, Intent.ACTION_DIAL)
            "silent" -> toggleSilent(context)
            "lock" -> lock(context)
            "wallpaper" -> AppRepository.wallpaper(context)
        }
    }

    private fun open(context: Context, action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private var torchOn = false
    private fun toggleTorch(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        }
    }

    private fun toggleSilent(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_SILENT)
                AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT
        }
    }

    private fun lock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DuoDeviceAdmin::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        } else {
            runCatching {
                context.startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, Lang.s(R.string.admin_explain))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
