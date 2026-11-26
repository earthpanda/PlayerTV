package example.playback

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.ai.playback.AndroidPlayer
import com.ai.playback.PlaybackRecording
import com.ai.playback.RecordingOptions
import com.ai.playback.RecordingSnapshot
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class SavedPlaybackRecording(val uri: String, val name: String, val location: String,
    val width: Int, val height: Int, val durationMs: Long, val videoFrames: Long)

/** Page-owned actual-output recording. Main caller supplies a finish signal and task cancellation.
 * onRecording exposes a temporary technical handle for crop/subtitle composition; never store it
 * in model State. This writer owns only its new unpublished file, not the player's source.
 */
class PlaybackRecordingWriter(context: Context, private val folder: String) {
    private val resolver = context.applicationContext.contentResolver
    init { require(folder.matches(Regex("[A-Za-z0-9_-]{1,48}"))) }

    @Suppress("DEPRECATION")
    suspend fun save(player: AndroidPlayer, options: RecordingOptions, finish: Deferred<Unit>,
        onRecording: (PlaybackRecording?) -> Unit,
        onState: (RecordingSnapshot) -> Unit = {}): SavedPlaybackRecording = withContext(Dispatchers.Main.immediate) {
        val name = "recording_" + UUID.randomUUID() + ".mp4"
        val location = Environment.DIRECTORY_MOVIES + "/" + folder
        var owned: Uri? = null
        var output: ParcelFileDescriptor? = null
        var recording: PlaybackRecording? = null
        var committed = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
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
                // Assign ownership before returning across a cancellable dispatcher boundary.
                owned = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Recording creation failed")
                output = resolver.openFileDescriptor(requireNotNull(owned), "rw") ?: throw IOException("Recording output unavailable")
            }
            val active = player.startRecording(requireNotNull(output), options)
            recording = active
            onRecording(active)
            onState(active.state.value)
            active.awaitStarted()
            while (!finish.isCompleted && !active.state.value.finished) {
                onState(active.state.value)
                delay(100)
            }
            // A cancelled finish signal is not a request to publish.
            if (finish.isCancelled) finish.await()
            val result = active.finish()
            onState(active.state.value)
            withContext(Dispatchers.IO) { output?.close(); output = null }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                val target = requireNotNull(owned)
                val completed = ContentValues().apply {
                    put(MediaStore.Video.Media.SIZE, resolver.openFileDescriptor(target, "r")?.use { it.statSize }
                        ?.takeIf { it > 0 } ?: throw IOException("Empty recording"))
                    put(MediaStore.Video.Media.WIDTH, result.width)
                    put(MediaStore.Video.Media.HEIGHT, result.height)
                    put(MediaStore.Video.Media.DURATION, result.durationMs)
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                if (resolver.update(target, completed, null, null) != 1) throw IOException("Recording publication failed")
                committed = true
            }
            SavedPlaybackRecording(requireNotNull(owned).toString(), name, location, result.width, result.height,
                result.durationMs, result.videoFrames)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun retain(error: Throwable) {
                val original = failure ?: cleanupFailure
                if (original == null) cleanupFailure = error
                else if (original !== error) original.addSuppressed(error)
            }
            withContext(NonCancellable) {
                // A host callback must not prevent descriptor/pending-row cleanup.
                try { recording?.cancel() } catch (error: Throwable) { retain(error) }
                try { onRecording(null) } catch (error: Throwable) { retain(error) }
                withContext(Dispatchers.IO) {
                    try { output?.close() } catch (error: Throwable) { retain(error) }
                    if (!committed) owned?.let {
                        try {
                            if (resolver.delete(it, null, null) != 1) throw IOException("Recording cleanup failed")
                        } catch (error: Throwable) { retain(error) }
                    }
                }
            }
            cleanupFailure?.let { throw it }
        }
    }
}
