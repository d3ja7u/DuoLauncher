package com.duolauncher.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/** 搜索命中的一条联系人（一个联系人可能有多个号码，按号码逐条展示） */
data class ContactHit(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val number: String
)

object Contacts {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 按姓名 / 号码模糊匹配。
     * SQL 的 LIKE 对带空格、横线的号码不友好，所以在 Kotlin 侧再做一次纯数字比对；
     * 查询放在调用方切到 IO 的协程里执行，避免输入时卡主线程。
     */
    fun search(ctx: Context, q: String, limit: Int = 12): List<ContactHit> {
        val key = q.trim()
        if (key.isEmpty() || !hasPermission(ctx)) return emptyList()
        val out = mutableListOf<ContactHit>()
        val seen = mutableSetOf<String>()
        val proj = arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME, Phone.NUMBER)
        val sel = "${Phone.DISPLAY_NAME} LIKE ? OR ${Phone.NUMBER} LIKE ?"
        val args = arrayOf("%$key%", "%$key%")
        val qDigits = key.filter { it.isDigit() }
        runCatching {
            ctx.contentResolver.query(
                Phone.CONTENT_URI, proj, sel, args, "${Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                val iId = c.getColumnIndex(Phone.CONTACT_ID)
                val iLookup = c.getColumnIndex(Phone.LOOKUP_KEY)
                val iName = c.getColumnIndex(Phone.DISPLAY_NAME)
                val iNum = c.getColumnIndex(Phone.NUMBER)
                while (c.moveToNext() && out.size < limit) {
                    val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                    val number = if (iNum >= 0) c.getString(iNum).orEmpty() else ""
                    if (name.isBlank() && number.isBlank()) continue
                    val digits = number.filter { it.isDigit() }
                    val match = name.contains(key, ignoreCase = true) ||
                        (qDigits.length >= 2 && digits.contains(qDigits))
                    if (!match) continue
                    val dedup = "$name|$number"
                    if (!seen.add(dedup)) continue
                    out.add(
                        ContactHit(
                            contactId = if (iId >= 0) c.getLong(iId) else -1L,
                            lookupKey = if (iLookup >= 0) c.getString(iLookup) else null,
                            name = name.ifBlank { number },
                            number = number
                        )
                    )
                }
            }
        }
        return out
    }

    /** 打开系统联系人详情 */
    fun open(ctx: Context, hit: ContactHit): Boolean = runCatching {
        val u = if (hit.contactId >= 0L && !hit.lookupKey.isNullOrEmpty())
            ContactsContract.Contacts.getLookupUri(hit.contactId, hit.lookupKey)
        else Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, hit.contactId.toString())
        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** 直接带号码打开拨号盘 */
    fun dial(ctx: Context, number: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
