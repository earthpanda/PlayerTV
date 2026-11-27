package com.ai.playtv.model.storage.artwork

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
import java.security.MessageDigest

enum class ArtworkSlot { SMALL, BACKGROUND }

/** Returns false when the input should be retained without conversion. */
fun interface ArtworkImageEncoder { fun encode(input: File, output: File, slot: ArtworkSlot): Boolean }

/** A ticket is captured before an asynchronous request and invalidated by deletion. */
class ArtworkWriteTicket internal constructor(
    internal val source: String, internal val item: String, internal val sourceGeneration: Long, internal val itemGeneration: Long,
)

/** Installation-scoped files. One instance owns the directory; no cross-process writes are supported. */
class ArtworkFileStore(private val directory: File, private val maxImageBytes: Long, private val maxTotalBytes: Long) {
    private val mutex = Mutex()
    private val sourceGenerations = mutableMapOf<String, Long>()
    private val itemGenerations = mutableMapOf<Pair<String, String>, Long>()

    init { require(maxImageBytes > 0 && maxTotalBytes >= maxImageBytes) }

    suspend fun ticket(sourceId: String, itemId: String): ArtworkWriteTicket = withContext(Dispatchers.IO) {
        require(sourceId.isNotBlank() && itemId.isNotBlank())
        val source = digest(sourceId); val item = digest(itemId)
        mutex.withLock {
            recoverPendingDeletes()
            ArtworkWriteTicket(source, item, sourceGenerations[source] ?: 0L, itemGenerations[source to item] ?: 0L)
        }
    }

    suspend fun find(sourceId: String, itemId: String, slot: ArtworkSlot): File? = withContext(Dispatchers.IO) {
        require(sourceId.isNotBlank() && itemId.isNotBlank())
        mutex.withLock {
            recoverPendingDeletes()
            val source = sourceDirectory(digest(sourceId), create = false) ?: return@withLock null
            val file = managedFile(source, digest(itemId), slot)
            if (file.isFile) file else null
        }
    }

    /** Save one checked image; failure preserves an earlier committed image. */
    suspend fun save(ticket: ArtworkWriteTicket, slot: ArtworkSlot, input: InputStream, encoder: ArtworkImageEncoder? = null): File = withContext(Dispatchers.IO) {
        input.use { stream ->
            mutex.withLock {
                recoverPendingDeletes()
                if (!isCurrent(ticket)) throw IOException("Artwork request expired")
                val source = sourceDirectory(ticket.source, create = true) ?: throw IOException("Artwork directory unavailable")
                val target = managedFile(source, ticket.item, slot)
                val used = totalBytes()
                val previous = if (target.isFile) target.length() else 0L
                val tmp = File.createTempFile("pending_", ".part", source)
                var converted: File? = null
                var failure: Throwable? = null
                try {
                    var size = 0L
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            size += read
                            if (size > maxImageBytes || (encoder == null && size > maxTotalBytes - (used - previous))) throw IOException("Artwork capacity exceeded")
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    if (!hasImageSignature(tmp)) throw IOException("Unsupported artwork content")
                    val output = if (encoder != null) {
                        val encoded = File.createTempFile("pending_", ".part", source)
                        converted = encoded
                        if (encoder.encode(tmp, encoded, slot)) {
                            if (!isWebp(encoded) || encoded.length() > maxImageBytes) throw IOException("Invalid encoded artwork")
                            encoded
                        } else tmp
                    } else tmp
                    if (output.length() > maxTotalBytes - (used - previous)) throw IOException("Artwork capacity exceeded")
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(ticket)) throw IOException("Artwork request expired")
                    Files.move(output.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    target
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    var cleanupFailure: IOException? = null
                    for (file in listOfNotNull(tmp, converted)) {
                        if (file.exists() && !file.delete()) {
                            val cleanup = IOException("Artwork temporary cleanup failed")
                            if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup)
                        }
                    }
                    cleanupFailure?.let { if (failure == null) throw it else failure.addSuppressed(it) }
                }
            }
        }
    }

    /** Invalidates outstanding requests, then deletes only this item's two owned files. */
    suspend fun deleteItem(sourceId: String, itemId: String) = withContext(Dispatchers.IO) {
        require(sourceId.isNotBlank() && itemId.isNotBlank())
        val source = digest(sourceId); val item = digest(itemId)
        mutex.withLock {
            recoverPendingDeletes()
            val key = source to item
            itemGenerations[key] = (itemGenerations[key] ?: 0L) + 1L
            val dir = sourceDirectory(source, create = false) ?: return@withLock
            val marker = markerFile(dir, "delete-item-$item")
            createMarker(marker)
            completeItemDelete(dir, item, marker)
        }
    }

    /** Invalidates the source generation before deleting its managed files; unknown files are preserved. */
    suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
        require(sourceId.isNotBlank())
        val source = digest(sourceId)
        mutex.withLock {
            recoverPendingDeletes()
            sourceGenerations[source] = (sourceGenerations[source] ?: 0L) + 1L
            val dir = sourceDirectory(source, create = false) ?: return@withLock
            val marker = markerFile(dir, SOURCE_DELETE)
            createMarker(marker)
            completeSourceDelete(dir, marker)
        }
    }

    /** Retry incomplete file deletions before any new ticket or write in this instance. */
    private fun recoverPendingDeletes() {
        if (!directory.exists()) return
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) throw IOException("Invalid artwork root")
        val dirs = directory.listFiles() ?: throw IOException("Cannot list artwork root")
        for (entry in dirs) {
            if (!entry.name.matches(HASH)) continue
            val dir = sourceDirectory(entry.name, create = false) ?: continue
            val files = dir.listFiles() ?: throw IOException("Cannot list artwork directory")
            val sourceMarker = files.firstOrNull { it.name == SOURCE_DELETE }
            if (sourceMarker != null) {
                completeSourceDelete(dir, markerFile(dir, SOURCE_DELETE))
                continue
            }
            for (file in files) {
                val match = ITEM_DELETE.matchEntire(file.name) ?: continue
                completeItemDelete(dir, match.groupValues[1], markerFile(dir, file.name))
            }
        }
    }

    private fun createMarker(marker: File) {
        if (marker.exists()) throw IOException("Artwork deletion already pending")
        Files.createFile(marker.toPath())
        marker.outputStream().use { it.fd.sync() }
    }

    private fun markerFile(dir: File, name: String): File {
        val marker = File(dir, name)
        if (Files.isSymbolicLink(marker.toPath()) || marker.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Invalid artwork deletion marker")
        if (marker.exists() && !marker.isFile) throw IOException("Invalid artwork deletion marker")
        return marker
    }

    private fun completeItemDelete(dir: File, item: String, marker: File) {
        ArtworkSlot.entries.forEach { slot -> deleteFile(managedFile(dir, item, slot)) }
        deleteFile(marker)
    }

    private fun completeSourceDelete(dir: File, marker: File) {
        val files = dir.listFiles() ?: throw IOException("Cannot list artwork directory")
        var unknown = false
        for (file in files) {
            if (file.name == SOURCE_DELETE) continue
            if ((file.name.matches(MANAGED) || file.name.matches(PENDING) || file.name.matches(ITEM_DELETE)) &&
                file.isFile && !Files.isSymbolicLink(file.toPath())) deleteFile(file)
            else unknown = true
        }
        if (unknown) throw IOException("Artwork directory contains unmanaged files")
        deleteFile(marker)
        if (!dir.delete() && dir.exists()) throw IOException("Artwork directory cleanup failed")
    }

    private fun isCurrent(ticket: ArtworkWriteTicket): Boolean =
        ticket.sourceGeneration == (sourceGenerations[ticket.source] ?: 0L) &&
            ticket.itemGeneration == (itemGenerations[ticket.source to ticket.item] ?: 0L)

    private fun sourceDirectory(source: String, create: Boolean): File? {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Invalid artwork root")
        if (!directory.exists() && (!create || !directory.mkdirs())) return if (create) throw IOException("Cannot create artwork root") else null
        val root = directory.canonicalFile
        if (!root.isDirectory) throw IOException("Invalid artwork root")
        val dir = File(root, source)
        if (Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile.parentFile != root) throw IOException("Invalid artwork directory")
        if (!dir.exists() && (!create || !dir.mkdir())) return if (create) throw IOException("Cannot create artwork directory") else null
        if (!dir.isDirectory) throw IOException("Invalid artwork directory")
        return dir
    }

    private fun managedFile(dir: File, item: String, slot: ArtworkSlot): File {
        val file = File(dir, "$item.${slot.name.lowercase(java.util.Locale.ROOT)}")
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Invalid artwork file")
        return file
    }

    private fun totalBytes(): Long {
        val root = directory.canonicalFile
        var sum = 0L
        for (dir in root.listFiles() ?: throw IOException("Cannot list artwork root")) {
            if (!dir.name.matches(HASH) || Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) continue
            for (file in dir.listFiles() ?: throw IOException("Cannot list artwork directory")) {
                if (file.name.matches(MANAGED) && file.isFile && !Files.isSymbolicLink(file.toPath())) {
                    if (file.length() > maxTotalBytes - sum) throw IOException("Artwork capacity exceeded")
                    sum += file.length()
                }
            }
        }
        return sum
    }

    private fun deleteFile(file: File) { if (file.exists() && !file.delete()) throw IOException("Artwork file deletion failed") }

    private fun hasImageSignature(file: File): Boolean {
        val head = file.inputStream().use { input ->
            val bytes = ByteArray(12)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            bytes.copyOf(count)
        }
        return head.size >= 8 && head.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) ||
            head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte() ||
            head.size >= 6 && (String(head, 0, 6, Charsets.US_ASCII) == "GIF87a" || String(head, 0, 6, Charsets.US_ASCII) == "GIF89a") ||
            head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
    }

    private fun isWebp(file: File): Boolean {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        return count == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
        val MANAGED = Regex("[0-9a-f]{64}\\.(small|background)")
        val PENDING = Regex("pending_[0-9]+\\.part")
        val ITEM_DELETE = Regex("delete-item-([0-9a-f]{64})")
        const val SOURCE_DELETE = "delete-source"
    }
}
