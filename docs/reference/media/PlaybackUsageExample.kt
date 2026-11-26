package example.playback

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ai.playback.AndroidPlayer
import com.ai.playback.AudioTrack
import com.ai.playback.SrtSubtitle
import com.ai.playback.SubtitleTrack
import com.ai.playback.DecoderMode
import com.ai.playback.PlaybackOptions
import com.ai.playback.PlaybackLoop
import com.ai.playback.SeekMode
import com.ai.playback.VideoEffect
import com.ai.playback.ClipExportRequest
import com.ai.playback.VideoClipExporter

/** Reference under docs, not an Android source set. Caller invokes commands on Main. */
class PlaybackUsageExample private constructor(private val player: AndroidPlayer) {
    val state = player.state

    fun attach(surface: Surface?) = player.setSurface(surface)

    /** Caller opens/closes fd on IO. Fail explicitly if hardware texture output is unavailable. */
    fun load(descriptor: ParcelFileDescriptor) = player.load(descriptor,
        PlaybackOptions(decoderMode = DecoderMode.HARDWARE_ONLY, videoEffect = VideoEffect.GRAYSCALE))

    /** Direct HTTP(S) media URL, not JSON/HTML. HTTPS by default; HTTP requires the
     * host's per-domain Network Security Config. No automatic redirects or retries.
     * Network errors stay in player.state; stop/load/close cancel the current input. */
    fun loadNetwork(url: String) = player.loadNetwork(url)

    /** Prepared effect changes on the next frame; no reload or new decoder/audio session. */
    fun grayscale(enabled: Boolean) = player.setVideoEffect(if (enabled) VideoEffect.GRAYSCALE else VideoEffect.NONE)

    fun volume(value: Double) = player.setVolume(value)
    fun mute(enabled: Boolean) = player.setMuted(enabled)
    fun seekPrecisely(positionMs: Long) = player.seekTo(positionMs, SeekMode.ACCURATE)

    /** Use a track from state.audioTracks. Observe pendingAudioTrack and the confirmed index. */
    fun selectAudio(track: AudioTrack) = player.selectAudioTrack(track)

    /** Null disables subtitles. Render state.subtitleCue?.text above the same video viewport. */
    fun selectSubtitle(track: SubtitleTrack?) = player.selectSubtitleTrack(track)

    /** Parse SrtSubtitle.read(stream, title) on IO with caller-owned stream.use; capture generation first.
     * Return to Main, discard after host stop, and observe the confirmed subtitle selection. */
    fun attachSrt(document: SrtSubtitle, generation: Long) = player.addSubtitle(document, generation)

    /** Dragging is one native session. Observe state.scrub; the final position is accurate. */
    fun beginDrag() = player.beginScrub()
    fun preview(positionMs: Long) = player.scrubTo(positionMs)
    fun finishDrag(positionMs: Long) = player.endScrub(positionMs)
    fun cancelDrag() = player.cancelScrub()

    /** Current-source only. Observe state.loop for native acknowledgment; no business persistence. */
    fun repeatInterval(startMs: Long, endMs: Long) = player.setLoop(PlaybackLoop(startMs, endMs))
    fun clearInterval() = player.setLoop(null)
    fun repeatWholeVideo(enabled: Boolean) = player.setVideoLoop(enabled)

    /** Requires PAUSED. Observe state.frameStep; Accepted is not a displayed-frame ack. */
    fun nextFrame() = player.stepForward()

    /** Main-thread suspend request; one bounded GPU readback, no pause/seek/reload.
     * Caller encodes frame.toBitmap() on a worker, closes its stream and recycles the Bitmap.
     * Keep frame.generation/positionNs with the saved Uri, and cancel when the host leaves. */
    suspend fun captureFrame() = player.captureFrame()

    /** Capture on Main, with descriptors for the very same local source. Host owns the Job,
     * cancellation, empty destination, close and publication; exporter owns its confined worker.
     * Playback rate/effects/volume/subtitles are not inherited. Choose the export effect explicitly. */
    suspend fun exportInterval(source: ParcelFileDescriptor, output: ParcelFileDescriptor,
        onProgress: suspend (Int) -> Unit = {}) : com.ai.playback.ClipExportResult =
        exportInterval(source, output, VideoEffect.NONE, onProgress)

    /** Pass a captured UI choice; playback changes after this call cannot mutate the task. */
    suspend fun exportInterval(source: ParcelFileDescriptor, output: ParcelFileDescriptor, videoEffect: VideoEffect,
        onProgress: suspend (Int) -> Unit = {}) : com.ai.playback.ClipExportResult {
        val snapshot = player.state.value
        val interval = requireNotNull(snapshot.loop).also { require(!it.wholeVideo) }
        require(snapshot.pendingAudioTrack == null)
        require(snapshot.audioTracks.isEmpty() || snapshot.selectedAudioTrackIndex != null)
        return VideoClipExporter.export(source, output, ClipExportRequest(interval.startMs, interval.endMs,
            snapshot.selectedAudioTrackIndex, snapshot.audioTracks.size), videoEffect, onProgress)
    }

    /** Destination is a new empty file; actual output, not the old fixed-effect source export.
     * Main caller must awaitStarted, mirror crop/subtitle composition, and finish/cancel before
     * closing/publishing output. Use PlaybackRecordingWriter for MediaStore ownership. */
    fun recordOutput(output: ParcelFileDescriptor, options: com.ai.playback.RecordingOptions) =
        player.startRecording(output, options)

    fun leavePage() { player.stop(); player.setSurface(null) }
    fun close() = player.close()
    suspend fun awaitReleased() = player.awaitReleased()

    companion object {
        suspend fun create(context: Context) = PlaybackUsageExample(AndroidPlayer.create(context.applicationContext))
    }
}
