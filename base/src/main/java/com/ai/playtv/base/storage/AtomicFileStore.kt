package com.ai.playtv.base.storage

import android.net.Uri
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一个实例拥有一个受控目录；仅写新文件，目标存在时拒绝覆盖。
 * IO + 64KiB 流式复制；同目录临时文件成功后原子移动，失败/取消仅清自己的临时文件。
 * 同一目录使用同一实例；不保证跨进程并发，也不提供整目录删除或业务清缓存。
 */
class AtomicFileStore(private val directory: File) {
    private val writes = Mutex()

    suspend fun importUri(resolver: ContentResolver, uri: Uri, name: String, maxBytes: Long): File =
        writeNew(name, maxBytes) { resolver.openInputStream(uri) ?: throw IOException("Cannot open content Uri") }

    /** openInput 在 IO 上调用，返回流的所有权转给本方法；文件名为内部标识，不使用外部展示名。 */
    suspend fun writeNew(name: String, maxBytes: Long, openInput: () -> InputStream): File = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        require(fileName.matches(name) && name != "." && name != "..") { "Invalid file name" }
        writes.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create storage directory")
            val root = directory.canonicalFile
            val target = File(root, name)
            if (target.canonicalFile.parentFile != root) throw IOException("Target escapes storage directory")
            if (target.exists()) throw IOException("Target already exists")
            val temporary = File.createTempFile("pending_", ".part", root)
            try {
                openInput().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count.toLong() > maxBytes - total) throw IOException("File exceeds byte limit")
                            output.write(buffer, 0, count)
                            total += count
                        }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                // 同一实例串行化；ATOMIC_MOVE 的目标竞争语义不适用于多个独立实例。
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                target
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    com.ai.playtv.base.diagnostics.DiagnosticLog.write(
                        com.ai.playtv.base.diagnostics.DiagnosticLevel.WARN,
                        com.ai.playtv.base.diagnostics.DiagnosticEvent("storage", "temporary_cleanup", "failed"))
                }
            }
        }
    }

    private companion object { val fileName = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}") }
}
