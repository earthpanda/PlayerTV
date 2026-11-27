package com.ai.playtv.ui.test.sourceentry

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ai.playtv.R
import com.ai.playtv.BuildConfig
import com.ai.playtv.SourcePreviewApplication
import com.ai.playtv.base.ui.loading.OperationLoadingController
import com.ai.playtv.base.ui.KeyboardController
import com.ai.playtv.base.ui.message.MessageController
import com.ai.playtv.databinding.ActivitySourceEntryBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.ui.test.tvplayback.TvPlaybackActivity

class SourceEntryActivity : BaseMviActivity<ActivitySourceEntryBinding, SourceEntryState, SourceEntryEvent,
    SourceEntryEffect, SourceEntryViewModel>(ActivitySourceEntryBinding::inflate) {
    override val viewModelClass = SourceEntryViewModel::class.java
    override fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory(viewModelClass) {
        SourceEntryViewModel((application as SourcePreviewApplication).discoveryOwner, BuildConfig.TEST_MODE)
    }
    private lateinit var loading: OperationLoadingController
    private lateinit var messages: MessageController
    private lateinit var keyboard: KeyboardController
    private var imeVisible = false
    private var handledDirection: Int? = null
    private val inputBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = restoreInput()
    }
    private val imeDismissed = Runnable {
        val ime = ViewCompat.getRootWindowInsets(binding.sourceUrl)?.isVisible(WindowInsetsCompat.Type.ime())
        if (hasWindowFocus() && binding.sourceUrl.hasFocus() && ime == false) restoreInput()
    }
    override fun initView() {
        loading = OperationLoadingController(this)
        messages = MessageController(this)
        keyboard = KeyboardController(binding.sourceUrl)
        onBackPressedDispatcher.addCallback(this, inputBack)
        binding.sourceUrl.setOnFocusChangeListener { _, focused ->
            inputBack.isEnabled = focused
            if (focused) binding.sourceUrl.removeCallbacks(imeDismissed) else keyboard.hide()
        }
        inputBack.isEnabled = binding.sourceUrl.hasFocus()
        // The IME may consume Back itself; observe its insets without replacing the root's system-bar listener.
        ViewCompat.setOnApplyWindowInsetsListener(binding.sourceUrl) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible && !visible && binding.sourceUrl.hasFocus() && hasWindowFocus()) {
                binding.sourceUrl.removeCallbacks(imeDismissed)
                binding.sourceUrl.post(imeDismissed)
            }
            imeVisible = visible
            insets
        }
        val presets = listOf(
            "https://github.com/qist/tvbox",
            "https://github.com/52liulian/tvapp_store",
            "https://github.com/Guovin/iptv-api",
            "https://github.com/iptv-org/iptv",
            "https://github.com/pan8664716/HGTV",
            "https://github.com/LibreSpark/LibreTV",
            "https://github.com/Huanshere/VideoLingo",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv6.m3u",
            "https://raw.githubusercontent.com/CCSH/IPTV/main/live_lite.m3u",
            "https://raw.githubusercontent.com/CCSH/IPTV/main/live.m3u",
            "https://raw.githubusercontent.com/CCSH/IPTV/main/live_platforms.m3u",
            "https://raw.githubusercontent.com/jia070310/lemonTV/main/iptv-fe.m3u",
            "https://github.com/xiangfeigao/movie_resource",
            "https://github.com/laoma528/awesome-zhuiju-free",
            "https://github.com/linuxhobby/myquark",
            "https://github.com/acoooder/aliyunpanshare",
            "https://raw.githubusercontent.com/AudiHub/WanXiang-Release/main/1万部电影.txt",
            "https://raw.githubusercontent.com/AudiHub/WanXiang-Release/main/电影工厂.txt",
        )
        val labels = resources.getStringArray(R.array.tv_source_presets)
        binding.presets.visibility = if (BuildConfig.TEST_MODE) View.VISIBLE else View.GONE
        binding.presets.adapter = ArrayAdapter(this, R.layout.item_source_preset, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        var lastPreset = -1
        binding.presets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position - 1 in presets.indices && position != lastPreset && (lastPreset >= 0 || binding.sourceUrl.text.isNullOrEmpty())) binding.sourceUrl.setText(presets[position - 1])
                lastPreset = position
            }
        }
        binding.submit.setOnClickListener { viewModel.onEvent(SourceEntryEvent.Submit(binding.sourceUrl.text.toString())) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::keyboard.isInitialized) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_UP && handledDirection == event.keyCode) {
                handledDirection = null
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                val current = currentFocus
                val controls = listOf(binding.presets, binding.sourceUrl, binding.submit)
                    .filter { it.visibility == View.VISIBLE && it.isEnabled }
                val index = controls.indexOfFirst { it === current }
                if (index >= 0 || current == null || current === binding.root) {
                    val next = if (index < 0) controls.firstOrNull()
                        else controls.getOrNull(index + if (down) 1 else -1)
                    next?.requestFocus()
                    handledDirection = event.keyCode
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun restoreInput() {
        keyboard.hide(clearFocus = true)
        if (!binding.submit.requestFocus()) binding.root.requestFocus()
        viewModel.onEvent(SourceEntryEvent.ClearError)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::keyboard.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN && binding.sourceUrl.hasFocus()) {
            val bounds = Rect()
            if (binding.sourceUrl.getGlobalVisibleRect(bounds) && !bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                restoreInput()
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy() {
        binding.sourceUrl.removeCallbacks(imeDismissed)
        ViewCompat.setOnApplyWindowInsetsListener(binding.sourceUrl, null)
        if (::keyboard.isInitialized) keyboard.close()
        super.onDestroy()
    }
    override fun render(state: SourceEntryState) {
        binding.submit.isEnabled = state.loadingId == null
        loading.render(state.loadingId, getString(R.string.s_tv_import_loading), true) { viewModel.onEvent(SourceEntryEvent.Cancel(it)) }
        binding.status.text = when (state.error) {
            "EMPTY" -> getString(R.string.s_tv_import_empty)
            "PARTIAL_EMPTY" -> getString(R.string.s_tv_import_partial_empty)
            "CLOUD" -> getString(R.string.s_tv_import_cloud)
            "WEBSITE" -> getString(R.string.s_tv_import_website)
            "RATE_LIMIT" -> getString(R.string.s_tv_import_rate_limit)
            "HTTP_403" -> getString(R.string.s_tv_http_forbidden)
            "REQUIRED" -> getString(R.string.s_tv_import_required)
            "FAILED" -> getString(R.string.s_tv_import_failed)
            else -> ""
        }
    }
    override fun handleEffect(effect: SourceEntryEffect) {
        when (effect) {
            SourceEntryEffect.OpenDefaults -> startActivity(Intent(this, TvPlaybackActivity::class.java))
            SourceEntryEffect.ServerForbidden -> messages.show(getString(R.string.s_tv_http_forbidden))
            is SourceEntryEffect.OpenPlayer -> {
                val intent = Intent(this, TvPlaybackActivity::class.java)
                intent.putExtra(TvPlaybackActivity.EXTRA_SESSION_ID, effect.sessionId)
                startActivity(intent)
            }
        }
    }
}
