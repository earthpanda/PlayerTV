package com.ai.playtv.base.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅在选择器确实返回 persistable + read 标志时保留读授权；provider 拒绝则向调用方抛出。 */
object UriAccess {
    suspend fun persistReadPermission(resolver: ContentResolver, uri: Uri, resultFlags: Int): Boolean = withContext(Dispatchers.IO) {
        val persistable = resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val readable = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (!persistable || !readable) return@withContext false
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }

    suspend fun releaseReadPermission(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
