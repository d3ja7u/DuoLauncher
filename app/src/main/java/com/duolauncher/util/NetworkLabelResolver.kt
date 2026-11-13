package com.duolauncher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * 徽标底部的网络制式文字：按手机真实注册网络自动判断，不再由用户手动固定。
 *
 * 大类用 `TelephonyManager.dataNetworkType`（2G / 3G / 4G / 5G）；
 * 5G 家族的细分（5G-A、5G 毫米波 UW）只有 TelephonyDisplayInfo 的 override 类型里有，
 * 需要注册回调才能拿到，所以这里在 Activity.onResume 里 attach 一次，回调只更新一个 Int。
 *
 * 无 READ_PHONE_STATE 权限或 ROM 读不到时一律回退 "5G"，与线上默认外观一致；
 * 用户可在「设置 → iOS 风格状态栏 → 电话权限」重新授权。
 */
object NetworkLabelResolver {

    // TelephonyDisplayInfo 的 override 值（部分常量在不同 API 等级才公开，直接写数值最稳）
    private const val OV_LTE_CA = 1
    private const val OV_LTE_ADVANCED_PRO = 2
    private const val OV_NR_NSA = 3
    private const val OV_NR_NSA_MMWAVE = 4
    private const val OV_NR_MMWAVE = 5
    private const val OV_NR_ADVANCED = 6   // 5G-Advanced / 5.5G

    @Volatile private var overrideType: Int = 0
    private var registered: Any? = null

    /** 幂等；权限是运行时授予的，所以每次回到桌面都试一次，授权后即生效 */
    fun attach(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30 || registered != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        overrideType = info.overrideNetworkType
                    }
                }
                tm.registerTelephonyCallback(ctx.mainExecutor, cb)
                registered = cb
            } else {
                val listener = object : PhoneStateListener(ctx.mainExecutor) {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        overrideType = info.overrideNetworkType
                    }
                }
                tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                registered = listener
            }
        }
    }

    /** 返回当前应显示的制式文字；[lteText] 由设置里的 LTE / 4G 开关决定 */
    fun label(ctx: Context, lteText: String): String {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return "5G"
        val type = runCatching {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).dataNetworkType
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val ov = overrideType
        return when {
            ov == OV_NR_ADVANCED -> "5GA"
            ov == OV_NR_NSA_MMWAVE || ov == OV_NR_MMWAVE -> "5GUW"
            ov == OV_NR_NSA || type == TelephonyManager.NETWORK_TYPE_NR -> "5G"
            type == TelephonyManager.NETWORK_TYPE_LTE ||
                    ov == OV_LTE_CA || ov == OV_LTE_ADVANCED_PRO -> lteText
            is3g(type) -> "3G"
            is2g(type) -> "2G"
            else -> "5G"      // 读不到时保持原来的默认外观
        }
    }

    private fun is2g(t: Int) = t in setOf(
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM
    )

    private fun is3g(t: Int) = t in setOf(
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA
    )
}
