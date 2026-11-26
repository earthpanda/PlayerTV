package com.ai.playtv.base.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只解析应用私有路径，不创建目录；作用域片段由 model 捕获后显式传入，不读取当前账号。 */
class AppDirectories(context: Context) {
    private val application = context.applicationContext
    suspend fun files(vararg segments: String): File = withContext(Dispatchers.IO) { resolve(application.filesDir, segments) }
    suspend fun cache(vararg segments: String): File = withContext(Dispatchers.IO) { resolve(application.cacheDir, segments) }
    suspend fun noBackup(vararg segments: String): File = withContext(Dispatchers.IO) { resolve(application.noBackupFilesDir, segments) }

    private fun resolve(root: File, segments: Array<out String>): File {
        var target = root
        segments.forEach {
            require(segment.matches(it)) { "Invalid storage path segment" }
            target = File(target, it)
        }
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget == canonicalRoot || canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
            "Storage path escapes root"
        }
        return canonicalTarget
    }

    private companion object { val segment = Regex("[A-Za-z0-9_-]{1,80}") }
}
