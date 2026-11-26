package com.ai.playtv.base.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** 仅在选择器确实返回 persistable + read 标志时保留读授权；provider 拒绝则向调用方抛出。 */
object UriAccess {
    fun persistReadPermission(resolver: ContentResolver, uri: Uri, resultFlags: Int): Boolean {
        val persistable = resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val readable = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (!persistable || !readable) return false
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return true
    }

    fun releaseReadPermission(resolver: ContentResolver, uri: Uri) =
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
