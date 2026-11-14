package com.duolauncher.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.duolauncher.data.DuoState
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 壁纸自动切换。
 *
 * 图片来自用户在系统相册／文件选择器里多选的 URI，**导入时即复制（下采样）到应用私有目录**，
 * 之后不再依赖 URI 授权，也不需要相册读取权限，图片不会上传。
 * 切换只在回到桌面时按时间间隔判断（MainActivity.onResume），不常驻后台、不额外耗电。
 */
object WallpaperRotator {
    private const val TAG = "DuoWP"
    private const val DIR = "wp_rotate"
    private const val MAX_EDGE = 2560
    /** 裁切算法版本：改动后 +1，回到桌面自动重绘当前壁纸一次 */
    const val RENDER_VER = 2

    /**
     * 所有切换串行执行。连续快速上滑时若两个协程并发，会先后读到同一个 wallpaperIndex，
     * 两次都设成同一张图 —— 表现就是「上滑了但壁纸没换」。加锁后每次必定推进到下一张。
     */
    private val lock = Any()
    /** 最近一次由本应用调用 setBitmap 的时刻（内存态，用于分辨系统颜色变化回调的来源） */
    @Volatile
    var lastSetAt = 0L

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 真实屏幕尺寸（含系统栏）。壁纸裁剪框必须按这个比例，不能用 desiredMinimum*（那可能是正方形）。 */
    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width().coerceAtLeast(1) to b.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
    }

    /** 文件名带时间戳，按名称排序即导入顺序 */
    fun images(ctx: Context): List<File> = (dir(ctx).listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".jpg", true) }
        .sortedBy { it.name }

    fun count(ctx: Context): Int = images(ctx).size

    /** 当前显示的是哪张：applyAt 成功后 index = idx + 1，所以是 index - 1 */
    private fun shownIndex(size: Int): Int =
        ((kotlin.math.abs(DuoState.wallpaperIndex) - 1).coerceAtLeast(0)) % size

    fun currentFile(ctx: Context): File? {
        val list = images(ctx)
        if (list.isEmpty()) return null
        return list[shownIndex(list.size)]
    }

    /**
     * 当前壁纸按真实屏幕比例 cover 后的缩略图，供状态栏采样「徽标背后那块区域」的明暗。
     * 与 applyAt 用同一套几何，采样位置与实际显示严格对应；且不依赖 WallpaperManager 的
     * drawable 缓存 / ROM 权限，换壁纸后必定拿到新图。
     */
    fun currentThumbnail(ctx: Context, w: Int, h: Int): Bitmap? {
        val f = currentFile(ctx) ?: return null
        val src = decodeFile(f, max(w, h) * 2) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        Canvas(out).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        src.recycle()
        return out
    }

    /** 导入选中的图片，返回成功数量 */
    fun importUris(ctx: Context, uris: List<Uri>): Int {
        var ok = 0
        for (uri in uris) {
            val bmp = decodeUri(ctx, uri) ?: continue
            val name = "w_%013d_%03d.jpg".format(System.currentTimeMillis(), ok)
            val saved = runCatching {
                FileOutputStream(File(dir(ctx), name)).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                true
            }.getOrDefault(false)
            bmp.recycle()
            if (saved) ok++
        }
        return ok
    }

    private fun decodeUri(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        }.getOrNull()
    }

    private fun decodeFile(f: File, maxEdge: Int = MAX_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun clear(ctx: Context) {
        images(ctx).forEach { runCatching { it.delete() } }
        DuoState.wallpaperIndex = 0
        DuoState.wallpaperLastAt = 0L
        DuoState.wallpaperRotate = false
        DuoState.save()
    }

    /** 到期才换；首次开启（lastAt=0）立即换一张 */
    fun rotateIfDue(ctx: Context): Boolean = synchronized(lock) {
        if (!DuoState.wallpaperRotate) return@synchronized false
        val list = images(ctx)
        if (list.size < 2) return@synchronized false
        val now = System.currentTimeMillis()
        val interval = DuoState.wallpaperIntervalMin.coerceAtLeast(1) * 60_000L
        val last = DuoState.wallpaperLastAt
        if (last in 1..now && now - last < interval) return@synchronized false
        applyNextLocked(ctx, list)
    }

    /** 设置页「立即换一张」/ 主屏上滑 */
    fun rotateNow(ctx: Context): Boolean = synchronized(lock) {
        val list = images(ctx)
        if (list.isEmpty()) return@synchronized false
        applyNextLocked(ctx, list)
    }

    private fun applyNextLocked(ctx: Context, list: List<File>): Boolean {
        val idx = if (DuoState.wallpaperRandom) {
            // 随机时排除当前这张：否则连着抽中同一张，用户会觉得「上滑了却没换」。
            val cur = shownIndex(list.size)
            if (list.size > 1) list.indices.filter { it != cur }.random() else 0
        } else kotlin.math.abs(DuoState.wallpaperIndex) % list.size
        return applyAtLocked(ctx, list, idx, advance = true)
    }

    /**
     * 升级后自动重绘「当前这张」：只重设壁纸，不推进轮播序号、不重置计时。
     * 修过裁切算法后，用户回到桌面就能看到正确效果，不必手动点「立即换一张」。
     */
    fun reapplyIfNeeded(ctx: Context): Boolean = synchronized(lock) {
        if (DuoState.wallpaperRenderVer >= RENDER_VER) return@synchronized false
        val list = images(ctx)
        if (list.isEmpty()) {
            DuoState.wallpaperRenderVer = RENDER_VER
            DuoState.save()
            return@synchronized false
        }
        if (!applyAtLocked(ctx, list, shownIndex(list.size), advance = false)) return@synchronized false
        DuoState.wallpaperRenderVer = RENDER_VER
        DuoState.save()
        true
    }

    private fun applyAtLocked(ctx: Context, list: List<File>, idx: Int, advance: Boolean): Boolean {
        val wm = WallpaperManager.getInstance(ctx)
        val (sw, sh) = screenSize(ctx)
        // 关键：位图宽高比必须等于真实屏幕宽高比。
        // desiredMinimumWidth/Height 在本机是 1200x1200 正方形（系统默认取最长边），
        // 若按它生成正方形位图，系统再铺到 1080x1200 竖屏时会二次 centerCrop，
        // 画面被明显放大、只剩中间一条 —— 这正是齐老师截图里「区域小」的原因。
        // 宽度取 max(屏宽, desiredMinimumWidth) 以满足系统横向视差最小尺寸要求，
        // 高度按屏幕比例推算，保证比例一致。
        val tw = max(sw, wm.desiredMinimumWidth.takeIf { it > 0 } ?: sw)
        val th = (tw.toDouble() * sh / sw).roundToInt().coerceAtLeast(1)
        // 只解码到刚好覆盖输出位图的尺寸。旧版固定解到 2560 边长：一张 2560x2844
        // 源图约占 29MB，而输出只有 1200x1338(约 6MB)，连续换壁纸时峰值内存被白白推高，
        // 整机内存紧张时容易把别的应用挤到 OOM。1.5 倍余量足够 cover 放大且不损画质。
        val decodeEdge = (max(tw, th) * 1.5f).roundToInt().coerceAtMost(MAX_EDGE)
        val src = decodeFile(list[idx], decodeEdge) ?: return false
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        // 开启同步锁屏时，主屏 + 锁屏一起换（FLAG_SYSTEM | FLAG_LOCK）；否则只换主屏。
        // minSdk 28，FLAG_LOCK 必然可用。
        val which = if (DuoState.wallpaperSyncLockscreen)
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        else
            WallpaperManager.FLAG_SYSTEM
        // 显式把整张位图作为可见裁剪区交给系统（对齐图库写法），
        // 且位图比例已与屏幕一致，ROM 即便忽略该提示也不会二次裁切。
        // 系统壁纸服务偶发拒绝一次 setBitmap，重试一次即可，避免「上滑没反应」。
        val ok = try {
            // 一次 cover：等比放大铺满、居中裁掉溢出，与系统图库「设为壁纸」效果一致
            val scale = max(tw.toFloat() / src.width, th.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            Canvas(out).drawBitmap(
                src,
                Rect(0, 0, src.width, src.height),
                RectF((tw - dw) / 2f, (th - dh) / 2f, (tw + dw) / 2f, (th + dh) / 2f),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            Log.d(
                TAG,
                "apply screen=${sw}x${sh} desired=${wm.desiredMinimumWidth}x${wm.desiredMinimumHeight} " +
                    "bitmap=${tw}x${th} src=${src.width}x${src.height} decode=$decodeEdge " +
                    "syncLs=${DuoState.wallpaperSyncLockscreen} file=${list[idx].name} advance=$advance"
            )
            runCatching {
                wm.setBitmap(out, Rect(0, 0, tw, th), true, which)
                true
            }.getOrDefault(false) || runCatching {
                Thread.sleep(180L)
                wm.setBitmap(out, Rect(0, 0, tw, th), true, which)
                true
            }.getOrDefault(false)
        } finally {
            // 解码、绘制或系统服务调用中途抛异常时也必须立刻回收，避免大位图滞留 native 堆
            src.recycle()
            out.recycle()
        }
        if (!ok) {
            Log.w(TAG, "setBitmap failed twice, src=${list[idx].name}")
            return false
        }
        lastSetAt = System.currentTimeMillis()
        if (advance) {
            DuoState.wallpaperLastAt = lastSetAt
            DuoState.wallpaperIndex = idx + 1
        }
        // 记录「当前系统壁纸由本应用设置」，状态栏颜色采样优先用自有图片，最精确
        DuoState.wallpaperOwned = true
        DuoState.save()
        // version 在组合期被读取，放主线程递增，确保毛玻璃底图缓存稳定失效
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            DuoState.wallpaperVersion++
        }
        return true
    }
}
