package com.duolauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.duolauncher.R
import com.duolauncher.data.DuoState
import com.duolauncher.util.Lang

/**
 * 「添加应用到主屏幕」选择器。
 *
 * 以前这个菜单项只是跳到 App 资源库，用户还得自己长按图标找「添加到主屏幕」，
 * 很容易以为点了没反应。这里直接列出（可搜索的）已安装应用，点一下即加入桌面，
 * 已在桌面的行显示 ✓，方便边看边加。
 */
@Composable
fun HomeAppPickerDialog(
    dark: Boolean,
    onDismiss: () -> Unit,
    onPick: (com.duolauncher.data.AppInfo) -> Unit
) {
    var q by remember { mutableStateOf("") }
    // 读 state list：应用扫描完成 / 加到桌面后会自动刷新
    val apps = DuoState.visibleApps()
        .filter {
            q.isBlank() ||
                    it.label.contains(q, ignoreCase = true) ||
                    it.id.contains(q, ignoreCase = true)
        }
        .sortedBy { it.label.lowercase() }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().height(480.dp),
            radius = 28.dp,
            level = 0.9f,
            dark = dark
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Text(
                    Lang.s(R.string.add_app_to_home),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = glassTextColor(dark)
                )
                Spacer(Modifier.height(12.dp))
                // 搜索框
                Row(
                    Modifier.fillMaxWidth().height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(glassTextColor(dark).copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search, null,
                        tint = glassSubTextColor(dark), modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isBlank()) {
                            Text(
                                Lang.s(R.string.search_apps),
                                fontSize = 13.sp, color = glassSubTextColor(dark)
                            )
                        }
                        BasicTextField(
                            value = q,
                            onValueChange = { q = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp, color = glassTextColor(dark)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (apps.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            Lang.s(R.string.search_no_match),
                            fontSize = 13.sp, color = glassSubTextColor(dark)
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(apps, key = { it.id }) { app ->
                            val onHome = DuoState.homeApps.contains(app.id)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = !onHome) { onPick(app) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    rememberDrawablePainter(rememberAppIcon(app)), null,
                                    Modifier.size(34.dp).clip(squircle(24))
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    app.label,
                                    fontSize = 14.sp,
                                    color = if (onHome)
                                        glassSubTextColor(dark) else glassTextColor(dark),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (onHome) Icons.Filled.Check else Icons.Filled.Add,
                                    null,
                                    tint = if (onHome) glassBlue else glassSubTextColor(dark),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                GlassPrimaryButton(
                    Lang.s(R.string.picker_done),
                    dark,
                    onDismiss,
                    Modifier.fillMaxWidth()
                )
            }
        }
    }
}
