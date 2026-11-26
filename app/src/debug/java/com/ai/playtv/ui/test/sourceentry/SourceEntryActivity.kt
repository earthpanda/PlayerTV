package com.ai.playtv.ui.test.sourceentry

import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.ViewModelProvider
import com.ai.playtv.R
import com.ai.playtv.BuildConfig
import com.ai.playtv.base.ui.loading.OperationLoadingController
import com.ai.playtv.base.ui.KeyboardController
import com.ai.playtv.databinding.ActivitySourceEntryBinding
import com.ai.playtv.di.NetworkDependencies
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.support.network.NetworkConfig
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.ui.test.tvplayback.TvPlaybackActivity
import okhttp3.HttpUrl.Companion.toHttpUrl

class SourceEntryActivity : BaseMviActivity<ActivitySourceEntryBinding, SourceEntryState, SourceEntryEvent,
    SourceEntryEffect, SourceEntryViewModel>(ActivitySourceEntryBinding::inflate) {
    override val viewModelClass = SourceEntryViewModel::class.java
    override fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory(viewModelClass) {
        val network = NetworkDependencies(NetworkConfig("https://api.github.com/".toHttpUrl()))
        SourceEntryViewModel(SourceRepository(network.service(GithubSourceService::class.java)), network, BuildConfig.TEST_MODE)
    }
    private lateinit var loading: OperationLoadingController
    private lateinit var keyboard: KeyboardController
    override fun initView() {
        loading = OperationLoadingController(this)
        keyboard = KeyboardController(binding.sourceUrl)
        val presets = listOf(
            "https://github.com/Huanshere/VideoLingo",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv6.m3u",
            "https://github.com/Guovin/iptv-api",
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
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::keyboard.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN && binding.sourceUrl.hasFocus()) {
            val bounds = Rect()
            if (binding.sourceUrl.getGlobalVisibleRect(bounds) && !bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                keyboard.hide(clearFocus = true)
                viewModel.onEvent(SourceEntryEvent.ClearError)
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy() {
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
            "REQUIRED" -> getString(R.string.s_tv_import_required)
            "FAILED" -> getString(R.string.s_tv_import_failed)
            else -> ""
        }
    }
    override fun handleEffect(effect: SourceEntryEffect) {
        when (effect) {
            SourceEntryEffect.OpenDefaults -> startActivity(Intent(this, TvPlaybackActivity::class.java))
            is SourceEntryEffect.OpenPlayer -> {
                val intent = Intent(this, TvPlaybackActivity::class.java)
                intent.putStringArrayListExtra(TvPlaybackActivity.EXTRA_URLS, ArrayList(effect.items.map { it.url }))
                intent.putStringArrayListExtra(TvPlaybackActivity.EXTRA_NAMES, ArrayList(effect.items.map { it.name }))
                intent.putStringArrayListExtra(TvPlaybackActivity.EXTRA_KINDS, ArrayList(effect.items.map { it.kind.name }))
                intent.putExtra(TvPlaybackActivity.EXTRA_PARTIAL, effect.partial)
                startActivity(intent)
            }
        }
    }
}
