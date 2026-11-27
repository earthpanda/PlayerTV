package com.ai.playtv.ui.test.tvplayback

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.playtv.R
import com.ai.playtv.SourcePreviewApplication
import com.ai.playtv.base.ui.message.MessageController
import com.ai.playtv.databinding.ActivityTvPlaybackBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.di.NetworkDependencies
import com.ai.playtv.di.sourceNetworkConfig
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.model.bean.domain.source.ImportedSource
import com.ai.playtv.model.bean.domain.source.SourceKind
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEffect
import com.ai.playtv.model.contract.tvplayback.TvPlaybackEvent
import com.ai.playtv.model.contract.tvplayback.TvPlaybackState
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.ui.test.sourceentry.SourceEntryActivity
import java.util.Locale

class TvPlaybackActivity : BaseMviActivity<ActivityTvPlaybackBinding, TvPlaybackState,
    TvPlaybackEvent, TvPlaybackEffect, TvPlaybackViewModel>(ActivityTvPlaybackBinding::inflate), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_URLS = "source_urls"
        const val EXTRA_NAMES = "source_names"
        const val EXTRA_KINDS = "source_kinds"
        const val EXTRA_PARTIAL = "source_partial"
        const val EXTRA_SESSION_ID = "source_session_id"
        const val EXTRA_HISTORY_ID = "source_history_id"
        const val EXTRA_ITEM_ID = "source_item_id"
    }
    override val viewModelClass = TvPlaybackViewModel::class.java
    override fun createViewModelFactory(): ViewModelProvider.Factory =
        viewModelFactory(viewModelClass) {
            val network = NetworkDependencies(sourceNetworkConfig())
            val app = application as SourcePreviewApplication
            val owner = app.discoveryOwner
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            val session = sessionId?.let(owner::find)
            val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
            val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
            val kinds = intent.getStringArrayListExtra(EXTRA_KINDS).orEmpty()
            val sources = urls.mapIndexed { i, url -> ImportedSource(url, names.getOrNull(i) ?: url, url,
                SourceKind.entries.firstOrNull { it.name == kinds.getOrNull(i) } ?: SourceKind.UNKNOWN) }
            val initial = session?.state?.value?.result?.items ?: sources
            TvPlaybackViewModel(applicationContext, SourceRepository(network.service(GithubSourceService::class.java)), network,
                initial, session?.state?.value?.result?.partial ?: intent.getBooleanExtra(EXTRA_PARTIAL, false),
                owner, sessionId, session, app.sourceHistory, intent.getStringExtra(EXTRA_HISTORY_ID),
                intent.getStringExtra(EXTRA_ITEM_ID))
        }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var messages: MessageController
    private var state = TvPlaybackState()
    private var previewMs: Long? = null
    private var pendingSeek: PendingSeek? = null
    private var gestureSource: String? = null
    private var downX = 0f
    private var downY = 0f
    private var gestureStartMs = 0L
    private var gestureDurationMs = 0L
    private var touchMode = 0 // 0 undecided, 1 horizontal, 2 vertical
    private var heldKey: Int? = null
    private var longSeekStarted = false
    private var videoTouchActive = false
    private var progressTracking = false
    private val playlistBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePlaylist()
    }
    private val hideControls = Runnable {
        if (canHideControls()) {
            binding.topControls.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
        }
    }
    private val seekTimeout = Runnable {
        pendingSeek = null
        render(state)
        scheduleHide()
    }
    private val seekStep = object : Runnable {
        override fun run() {
            val key = heldKey ?: return
            longSeekStarted = true
            val duration = state.durationMs
            if (state.seekable && duration != null && duration > 0 && gestureSource == state.activeId) {
                val delta = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                previewMs = TvPreviewSeekMath.step(previewMs ?: pendingSeek?.targetMs ?: state.positionMs, delta, duration)
                renderProgress()
            } else {
                binding.hint.setText(R.string.s_tv_preview_seek_unavailable)
            }
            handler.postDelayed(this, 350L)
        }
    }
    private val sourceAdapter = TvPreviewSourceAdapter { row ->
        closePlaylist()
        viewModel.onEvent(TvPlaybackEvent.SelectImported(row.imported))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id != null && (application as SourcePreviewApplication).discoveryOwner.find(id) == null) {
            startActivity(Intent(this, SourceEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun initView() {
        messages = MessageController(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        binding.video.holder.addCallback(this)
        binding.video.setOnTouchListener { _, event -> onVideoTouch(event) }
        binding.toggle.setOnClickListener { showControls(); togglePlayback() }
        onBackPressedDispatcher.addCallback(this, playlistBack)
        binding.playlistOverlay.setOnClickListener { closePlaylist() }
        binding.playlist.setNumColumns(1)
        binding.playlist.adapter = sourceAdapter
        binding.progress.onTracking = { target ->
            progressTracking = true
            handler.removeCallbacks(hideControls)
            showControls(scheduleHide = false)
            gestureSource = state.activeId
            previewMs = target
            if (target == null) binding.hint.setText(R.string.s_tv_preview_seek_unavailable)
            renderProgress()
        }
        binding.progress.onFinishTracking = { target ->
            progressTracking = false
            commitSeek(target)
            previewMs = null
            renderProgress()
            scheduleHide()
        }
    }
    override fun onStart() { super.onStart(); viewModel.foreground(); showControls() }
    override fun onStop() {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(seekStep)
        clearPendingSeek()
        heldKey = null
        videoTouchActive = false
        progressTracking = false
        previewMs = null
        viewModel.onEvent(TvPlaybackEvent.Background)
        super.onStop()
    }
    override fun onDestroy() { binding.video.holder.removeCallback(this); viewModel.attachSurface(null); super.onDestroy() }
    override fun surfaceCreated(holder: SurfaceHolder) { viewModel.attachSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { viewModel.attachSurface(null) }

    override fun render(state: TvPlaybackState) {
        val wasPlaying = isPlayingForControls(this.state)
        val previousSourceId = this.state.activeId
        this.state = state
        val pending = pendingSeek
        if (pending != null && (state.activeId != pending.sourceId || state.sourceResolving ||
                state.phase in setOf("FAILED", "ENDED", "IDLE", "RELEASING", "RELEASED") ||
                (state.error != null && state.error != pending.initialError) ||
                (pending.appearsReached(state.positionMs, state.durationMs) &&
                    state.phase == (if (pending.playing) "PLAYING" else "PAUSED")))) {
            clearPendingSeek()
        }
        val isPlaying = isPlayingForControls(state)
        if (!isPlaying) {
            handler.removeCallbacks(hideControls)
            if (wasPlaying || binding.bottomControls.visibility != View.VISIBLE) showControls(scheduleHide = false)
        } else if (!wasPlaying || previousSourceId != state.activeId) {
            if (binding.playlistOverlay.visibility != View.VISIBLE) showControls()
        }
        binding.buffering.visibility = if (state.phase == "BUFFERING") View.VISIBLE else View.GONE
        binding.audioWithoutVideo.visibility = if (state.audioWithoutVideo && !state.sourceResolving && state.error == null)
            View.VISIBLE else View.GONE
        binding.buffering.text = getString(R.string.s_tv_preview_buffer_percent, state.bufferedPercent.coerceIn(0, 100))
        val shownPhase = displayedPhase(state)
        val status = getString(when (shownPhase) {
            "INITIALIZING" -> R.string.s_tv_preview_initializing
            "IDLE" -> R.string.s_tv_preview_idle
            "PREPARING" -> R.string.s_tv_preview_preparing
            "PLAYING" -> R.string.s_tv_preview_playing
            "PAUSED" -> R.string.s_tv_preview_paused
            "BUFFERING" -> R.string.s_tv_preview_buffering
            "ENDED" -> R.string.s_tv_preview_ended
            "FAILED" -> R.string.s_tv_preview_failed
            "RELEASING" -> R.string.s_tv_preview_releasing
            "RELEASED" -> R.string.s_tv_preview_released
            else -> R.string.s_tv_preview_status_unknown
        })
        binding.status.text = if (state.sourceResolving) getString(R.string.s_tv_preview_preparing) else if (state.error != null) {
            getString(R.string.s_tv_preview_status_with_error, status)
        } else status
        val sourceName = state.activeName ?: getString(R.string.s_tv_preview_no_source)
        binding.title.text = getString(if (shownPhase == "PLAYING") R.string.s_tv_preview_now_playing
            else R.string.s_tv_preview_source_title, sourceName)
        binding.toggle.setText(if (shownPhase in setOf("PLAYING", "BUFFERING", "PREPARING"))
            R.string.s_tv_preview_pause else R.string.s_tv_preview_play)
        sourceAdapter.submitList(state.imported.map {
            TvPreviewSourceRow(it, it.id == state.activeId, it.id == state.activeId && state.phase == "PLAYING")
        })
        binding.importStatus.text = when {
            state.discoveryRateLimited -> getString(R.string.s_tv_import_rate_limit)
            state.historyFailed -> getString(R.string.s_tv_history_failed)
            state.discoveryRunning -> getString(R.string.s_tv_import_discovering)
            state.discoveryFailed -> getString(R.string.s_tv_import_discovery_failed)
            state.importPartial -> getString(R.string.s_tv_import_partial)
            else -> ""
        }
        renderProgress()
        if (pending != null && pendingSeek == null) scheduleHide()
    }

    private fun renderProgress() {
        val duration = state.durationMs?.takeIf { it > 0 } ?: pendingSeek?.durationMs
        val position = previewMs ?: pendingSeek?.targetMs ?: state.positionMs
        binding.progress.durationMs = duration
        binding.progress.canSeek = state.seekable && duration != null && duration > 0
        binding.progress.positionMs = position
        binding.elapsed.text = time(position)
        binding.duration.text = duration?.takeIf { it > 0 }?.let(::time)
            ?: getString(R.string.s_tv_preview_unknown_time)
    }

    private fun showControls(scheduleHide: Boolean = true) {
        binding.topControls.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        binding.hint.setText(R.string.s_tv_preview_controls_hint)
        handler.removeCallbacks(hideControls)
        if (scheduleHide) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (canHideControls()) handler.postDelayed(hideControls, 5_000L)
    }

    private fun displayedPhase(state: TvPlaybackState): String {
        val pending = pendingSeek
        return if (pending != null && pending.sourceId == state.activeId && !state.sourceResolving &&
            state.phase in setOf("PLAYING", "PAUSED", "BUFFERING")) {
            if (pending.playing) "PLAYING" else "PAUSED"
        } else state.phase
    }

    private fun isPlayingForControls(state: TvPlaybackState): Boolean =
        displayedPhase(state) == "PLAYING" && !state.sourceResolving && state.error == null

    private fun canHideControls(): Boolean = isPlayingForControls(state) &&
        binding.playlistOverlay.visibility != View.VISIBLE && heldKey == null && !videoTouchActive && !progressTracking && pendingSeek == null

    private fun togglePlayback() {
        val pending = pendingSeek
        if (pending == null) viewModel.onEvent(TvPlaybackEvent.Toggle)
        else {
            val playing = !pending.playing
            pendingSeek = pending.copy(playing = playing)
            viewModel.onEvent(TvPlaybackEvent.SetPlaying(playing))
            render(state)
        }
    }

    private fun clearPendingSeek() {
        handler.removeCallbacks(seekTimeout)
        pendingSeek = null
    }

    private fun openPlaylist() {
        handler.removeCallbacks(hideControls)
        if (isPlayingForControls(state)) {
            binding.topControls.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
        }
        binding.playlistOverlay.visibility = View.VISIBLE
        playlistBack.isEnabled = true
        binding.playlist.post {
            if (!isFinishing && !isDestroyed && binding.playlistOverlay.visibility == View.VISIBLE) {
                val index = sourceAdapter.currentList.indexOfFirst { it.id == state.activeId }.coerceAtLeast(0)
                binding.playlist.setSelectedPosition(index)
                binding.playlist.post {
                    if (!isFinishing && !isDestroyed && binding.playlistOverlay.visibility == View.VISIBLE) {
                        binding.playlist.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                            ?: binding.playlist.requestFocus()
                    }
                }
            }
        }
    }

    private fun closePlaylist() {
        playlistBack.isEnabled = false
        binding.playlistOverlay.visibility = View.GONE
        showControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.playlistOverlay.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openPlaylist()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    showControls()
                    togglePlayback()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handler.removeCallbacks(seekStep)
                    heldKey = event.keyCode
                    gestureSource = state.activeId
                    longSeekStarted = false
                    previewMs = null
                    showControls(scheduleHide = false)
                    handler.postDelayed(seekStep, ViewConfiguration.getLongPressTimeout().toLong())
                } else if (event.action == KeyEvent.ACTION_UP && heldKey == event.keyCode) {
                    handler.removeCallbacks(seekStep)
                    heldKey = null
                    if (longSeekStarted && !event.isCanceled) commitSeek(previewMs)
                    previewMs = null
                    renderProgress()
                    scheduleHide()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onVideoTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                videoTouchActive = true
                downX = event.x; downY = event.y; touchMode = 0
                gestureStartMs = pendingSeek?.targetMs ?: state.positionMs
                gestureDurationMs = state.durationMs ?: pendingSeek?.durationMs ?: 0L
                gestureSource = state.activeId
                showControls(scheduleHide = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (touchMode == 0) touchMode = TvPreviewSeekMath.axis(dx, dy, slop)
                if (touchMode == 1) {
                    if (state.seekable && gestureDurationMs > 0 && binding.video.width > 0 && gestureSource == state.activeId) {
                        previewMs = TvPreviewSeekMath.drag(gestureStartMs, dx, binding.video.width, gestureDurationMs)
                        renderProgress()
                    } else binding.hint.setText(R.string.s_tv_preview_seek_unavailable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                videoTouchActive = false
                if (touchMode == 1) commitSeek(previewMs)
                else if (TvPreviewSeekMath.opensPlaylist(touchMode, event.y - downY,
                        ViewConfiguration.get(this).scaledTouchSlop)) openPlaylist()
                previewMs = null
                renderProgress()
                if (binding.playlistOverlay.visibility != View.VISIBLE) scheduleHide()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                videoTouchActive = false
                previewMs = null
                renderProgress()
                scheduleHide()
                return true
            }
        }
        return false
    }

    private fun commitSeek(target: Long?) {
        if (target != null && state.seekable && (state.durationMs ?: 0L) > 0 && gestureSource == state.activeId) {
            val duration = state.durationMs ?: return
            val oldPending = pendingSeek
            pendingSeek = PendingSeek(state.activeId, target.coerceIn(0L, duration), state.positionMs, duration,
                state.error, oldPending?.playing ?: (state.phase in setOf("PLAYING", "BUFFERING", "PREPARING")))
            handler.removeCallbacks(seekTimeout)
            handler.postDelayed(seekTimeout, 15_000L)
            viewModel.onEvent(TvPlaybackEvent.SeekTo(target))
        }
    }
    override fun handleEffect(effect: TvPlaybackEffect) {
        when (effect) {
            TvPlaybackEffect.ServerForbidden -> messages.show(getString(R.string.s_tv_http_forbidden))
        }
    }
    private fun time(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
