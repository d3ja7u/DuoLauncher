package com.duolauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.duolauncher.R
import com.duolauncher.data.DuoState
import com.duolauncher.util.ApkUpdater
import com.duolauncher.util.Lang
import com.duolauncher.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 桌面上的更新提示条。
 *
 * 刻意做成**常驻、不自动消失**：更新只提示一次的东西，一闪就没了等于没提示。
 * 只有两个出口——点条本身（或「查看」）打开更新说明与下载，点「×」本次不再显示。
 */
@Composable
fun UpdateBanner(
    version: String,
    dark: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.widthIn(max = 360.dp), radius = 22.dp, level = 0.9f, dark = dark) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.NewReleases, null,
                tint = glassBlue, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    Lang.s(R.string.update_banner_title, version),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = glassTextColor(dark)
                )
                Text(
                    Lang.s(R.string.update_banner_sub),
                    fontSize = 11.sp, color = glassSubTextColor(dark)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                Lang.s(R.string.view),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(glassBlue)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            Spacer(Modifier.width(2.dp))
            // 关掉只影响本次运行：下次启动 / 下一轮检查若仍是新版本会再出现，
            // 免得手滑点掉之后完全失去提示
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close, null,
                    tint = glassSubTextColor(dark), modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * 桌面上的更新流程：说明 + 下载 + 调起安装。
 *
 * 与设置页共用同一个 [UpdateResultDialog]，但下载与「安装未知来源应用」授权的状态
 * 由这里自己持有，不依赖设置页的导航，点开就能更新到底。
 */
@Composable
fun UpdateCenter(
    info: UpdateChecker.ReleaseInfo,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val localVersion = remember { UpdateChecker.currentVersion(ctx) }
    var dlState by remember { mutableStateOf<ApkUpdater.State>(ApkUpdater.State.Idle) }
    var dlJob by remember { mutableStateOf<Job?>(null) }
    /** 等用户去系统页开「安装未知来源应用」时暂存 Release，回来自动接着下 */
    var pendingRelease by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    fun cancelDownload() {
        dlJob?.cancel()
        dlJob = null
        dlState = ApkUpdater.State.Idle
    }

    fun startDownload(rel: UpdateChecker.ReleaseInfo) {
        if (rel.apkUrl.isBlank()) {
            UpdateChecker.openInBrowser(
                ctx,
                rel.pageUrl.ifBlank {
                    if (DuoState.updateSource == UpdateChecker.SOURCE_GITHUB) UpdateChecker.GITHUB_PROJECT
                    else UpdateChecker.GITEE_PROJECT
                }
            )
            return
        }
        if (!ApkUpdater.canInstall(ctx)) {
            pendingRelease = rel
            runCatching { ctx.startActivity(ApkUpdater.permissionIntent(ctx)) }
                .onFailure {
                    dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_permission))
                }
            return
        }
        pendingRelease = null
        dlJob?.cancel()
        dlState = ApkUpdater.State.Downloading(0L, rel.apkSize)
        dlJob = scope.launch {
            val r = ApkUpdater.download(ctx, rel.apkUrl, rel.apkName, rel.apkSize) { done, total ->
                dlState = ApkUpdater.State.Downloading(done, total)
            }
            r.onSuccess { file ->
                dlState = ApkUpdater.State.Idle
                if (ApkUpdater.install(ctx, file)) onDismiss()
                else dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_start))
            }.onFailure { e ->
                if (e is CancellationException) return@launch
                dlState = ApkUpdater.State.Failed(
                    e.message ?: Lang.s(R.string.update_download_failed)
                )
            }
        }
    }

    // 从「安装未知来源应用」设置页返回：授权通过就接着下载，否则给出原因
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val waiting = pendingRelease
                if (waiting != null) {
                    pendingRelease = null
                    if (ApkUpdater.canInstall(ctx)) startDownload(waiting)
                    else dlState = ApkUpdater.State.Failed(Lang.s(R.string.err_install_permission))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 弹窗关掉 / 回桌面就要停下载，别在后台偷偷跑流量
            dlJob?.cancel()
        }
    }

    UpdateResultDialog(
        dark = dark,
        localVersion = localVersion,
        info = info,
        error = null,
        dlState = dlState,
        onDismiss = { cancelDownload(); onDismiss() },
        // 桌面入口只有「发现新版本」这一种结果，不存在检查失败的形态，重试按钮不会出现
        onRetry = {},
        onDownload = { startDownload(info) },
        onCancelDownload = { cancelDownload() }
    )
}
