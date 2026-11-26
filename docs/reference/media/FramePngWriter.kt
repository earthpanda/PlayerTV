package example.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.playback.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class SavedVideoFrame(val uri: String, val name: String, val location: String,
    val width: Int, val height: Int, val positionNs: Long,
    val pixelAspectRatioNumerator: Int, val pixelAspectRatioDenominator: Int)

/** SDK/platform adapter. Caller owns a single cancellable job and legacy write permission.
 * Saves only a new task-owned PNG. No scanning, reading permission, overwrite or retention policy.
 * Cancellation can race the final MediaStore commit; an already committed image is retained.
 */
class FramePngWriter(context: Context, private val folder: String) {
    private val resolver = context.applicationContext.contentResolver
    init { require(folder.matches(Regex("[A-Za-z0-9_-]{1,48}"))) }

    @Suppress("DEPRECATION")
    suspend fun save(frame: VideoFrame): SavedVideoFrame = withContext(Dispatchers.IO) {
        val name = "frame_${frame.positionNs / 1_000_000}_${UUID.randomUUID()}.png"
        val location = "${Environment.DIRECTORY_PICTURES}/$folder"
        var uri: Uri? = null
        var committed = false
        var bitmap: Bitmap? = null
        var failure: Throwable? = null
        try {
            ensureActive()
            val image = frame.toBitmap()
            bitmap = image
            ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.WIDTH, frame.width)
                put(MediaStore.Images.Media.HEIGHT, frame.height)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, location)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Picture directory unavailable")
                    put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
                }
            }
            val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Image creation failed")
            uri = target
            ensureActive()
            resolver.openOutputStream(target, "w")?.use { stream ->
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("PNG encoding failed")
            } ?: throw IOException("Image output unavailable")
            ensureActive()
            // A single provider commit is the publication boundary, after encode and close succeed.
            val completed = ContentValues().apply {
                put(MediaStore.Images.Media.SIZE, resolver.openFileDescriptor(target, "r")?.use { it.statSize }
                    ?.takeIf { it > 0 } ?: throw IOException("Empty PNG output"))
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            if (resolver.update(target, completed, null, null) != 1) throw IOException("Image publication failed")
            committed = true
            SavedVideoFrame(target.toString(), name, location, frame.width, frame.height, frame.positionNs,
                frame.pixelAspectRatioNumerator, frame.pixelAspectRatioDenominator)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            bitmap?.recycle()
            // Runs on IO even when cancelled; never deletes an earlier or already committed screenshot.
            if (!committed) uri?.let {
                try {
                    if (resolver.delete(it, null, null) != 1) throw IOException("Partial image cleanup failed")
                } catch (cleanup: Exception) {
                    // Cleanup failure must not replace a CancellationException or the original IO cause.
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}
