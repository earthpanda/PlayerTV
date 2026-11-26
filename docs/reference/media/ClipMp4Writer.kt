package example.playback

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.ai.playback.ClipExportRequest
import com.ai.playback.VideoClipExporter
import com.ai.playback.VideoEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class SavedVideoClip(val uri: String, val name: String, val location: String,
    val startMs: Long, val endMs: Long, val width: Int, val height: Int, val videoFrames: Long)

/** Adopting host owns source, permission and cancellation. Only this task's new output is removed.
 * The SDK completes and releases its muxer before the file is closed and MediaStore publishes it.
 * A cancellation racing an already committed publication retains the complete user-owned video.
 */
class ClipMp4Writer(context: Context, private val folder: String) {
    private val resolver = context.applicationContext.contentResolver
    init { require(folder.matches(Regex("[A-Za-z0-9_-]{1,48}"))) }

    suspend fun save(source: ParcelFileDescriptor, request: ClipExportRequest,
        onProgress: suspend (Int) -> Unit = {}): SavedVideoClip = save(source, request, VideoEffect.NONE, onProgress)

    /** Explicit task setting, independent of playback effects; the default overload stays original. */
    @Suppress("DEPRECATION")
    suspend fun save(source: ParcelFileDescriptor, request: ClipExportRequest, videoEffect: VideoEffect,
        onProgress: suspend (Int) -> Unit = {}): SavedVideoClip = withContext(Dispatchers.IO) {
        val name = "clip_" + UUID.randomUUID() + ".mp4"
        val location = Environment.DIRECTORY_MOVIES + "/" + folder
        var owned: android.net.Uri? = null
        var committed = false
        var failure: Throwable? = null
        try {
            ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, location)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Video directory unavailable")
                    put(MediaStore.Video.Media.DATA, File(directory, name).absolutePath)
                }
            }
            val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Video creation failed")
            owned = target
            val result = resolver.openFileDescriptor(target, "rw")?.use { output ->
                VideoClipExporter.export(source, output, request, videoEffect) { onProgress(it.coerceAtMost(99)) }
            } ?: throw IOException("Video output unavailable")
            ensureActive()
            val completed = ContentValues().apply {
                put(MediaStore.Video.Media.SIZE, resolver.openFileDescriptor(target, "r")?.use { it.statSize }
                    ?.takeIf { it > 0 } ?: throw IOException("Empty MP4 output"))
                put(MediaStore.Video.Media.WIDTH, result.width)
                put(MediaStore.Video.Media.HEIGHT, result.height)
                put(MediaStore.Video.Media.DURATION, request.endPositionMs - request.startPositionMs)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            if (resolver.update(target, completed, null, null) != 1) throw IOException("Video publication failed")
            committed = true
            SavedVideoClip(target.toString(), name, location, request.startPositionMs, request.endPositionMs,
                result.width, result.height, result.videoFrames)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!committed) owned?.let {
                try {
                    if (resolver.delete(it, null, null) != 1) throw IOException("Partial video cleanup failed")
                } catch (cleanup: Exception) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}
