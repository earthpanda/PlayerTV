package com.ai.playtv.ui.test.sample

import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.ai.playtv.R
import com.ai.playtv.databinding.ActivitySampleBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.model.contract.sample.SampleEffect
import com.ai.playtv.model.contract.sample.SampleEvent
import com.ai.playtv.model.contract.sample.SampleLoadFailure
import com.ai.playtv.model.contract.sample.SampleState
import com.ai.playtv.AppApplication

/**
 * Launchable technical preview using only local data and AndroidX lists.
 * It demonstrates Event -> Repository -> State/Effect -> UI without a production business spec.
 */
class SampleActivity : BaseMviActivity<ActivitySampleBinding, SampleState, SampleEvent, SampleEffect,
    SampleViewModel>(ActivitySampleBinding::inflate) {
    /** AndroidX ViewModel type owned by this screen. */
    override val viewModelClass = SampleViewModel::class.java
    /** Adapter owns UI binding and delegates business inputs to the ViewModel. */
    private val adapter = SampleAdapter { viewModel.onEvent(SampleEvent.ItemClicked(it)) }

    /** Explicitly assemble the local demonstration data source. */
    override fun createViewModelFactory(): ViewModelProvider.Factory =
        viewModelFactory(viewModelClass) { SampleViewModel((application as AppApplication).container.sampleRepository) }

    /** Bind plain refresh/load-more actions when the optional gesture capability is absent. */
    override fun initView() {
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.refreshAction.setOnClickListener { viewModel.onEvent(SampleEvent.Refresh) }
        binding.loadMoreAction.setOnClickListener { viewModel.onEvent(SampleEvent.LoadMore) }
        binding.pageState.onRetry = { viewModel.onEvent(SampleEvent.Refresh) }
        viewModel.onEvent(SampleEvent.Initialize)
    }

    /** Render snapshots without mutating the adapter's current list. */
    override fun render(state: SampleState) {
        adapter.submitList(state.items)
        binding.pageState.render(state.pageStatus)
        val busy = state.isRefreshing || state.isLoadingMore
        binding.refreshAction.isEnabled = !busy
        binding.loadMoreAction.isEnabled = !busy && state.nextPage != null
    }

    /** Resolve static strings locally; model Effects carry only semantic data. */
    override fun handleEffect(effect: SampleEffect) {
        val message = when (effect) {
            is SampleEffect.LoadFailed -> getString(when (effect.phase) {
                SampleLoadFailure.Initial -> R.string.s_common_load_failed
                SampleLoadFailure.Refresh -> R.string.s_sample_refresh_failed
                SampleLoadFailure.LoadMore -> R.string.s_sample_load_more_failed
            })
            is SampleEffect.ItemSelected -> getString(R.string.s_sample_selected, effect.id)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
