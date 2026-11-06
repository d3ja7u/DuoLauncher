package com.duolauncher.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
        val pm = context.applicationContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) { emptyList() }

        val collator = Collator.getInstance(Locale.CHINA)
        resolved.mapNotNull { ri ->
            runCatching {
                val ai = ri.activityInfo.applicationInfo
                val icon: Drawable = try { ri.loadIcon(pm) } catch (e: Exception) { ColorDrawable(Color.DKGRAY) }
                val comp = ri.activityInfo.name
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = icon,
                    systemCategory = if (Build.VERSION.SDK_INT >= 26) ai.category else -1,
                    activityClass = comp
                )
            }.getOrNull()
        }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(collator) { it.label })
    }

    fun launch(context: Context, pkg: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            DuoState.recordUsage(pkg)
            // 打开过就算已读：清掉该应用的角标（与 iOS 一致）
            runCatching { DuoNotificationListener.dismiss(pkg) }
            true
        } catch (e: Exception) { false }
    }

    fun uninstall(context: Context, pkg: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun appDetails(context: Context, pkg: String) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
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
