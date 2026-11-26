package com.ai.playtv.ui

import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.base.ui.BaseActivity
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Typed Activity binding/collection host with no account or analytics behavior.
 * Implement initView/render/handleEffect and provide an explicit ViewModel Factory.
 */
abstract class BaseMviActivity<VB : ViewBinding, S : UiState, E : UiEvent, F : UiEffect,
    VM : BaseMviViewModel<S, E, F>>(
    /** Inflation entry supplied by the screen's generated ViewBinding. */
    private val inflate: (LayoutInflater) -> VB,
) : BaseActivity() {
    /** Binding owned by this Activity instance. */
    protected lateinit var binding: VB
        private set
    /** Type registered with the screen's Factory. */
    protected abstract val viewModelClass: Class<VM>
    /** ViewModelProvider retains state across configuration changes. */
    protected val viewModel: VM by lazy {
        ViewModelProvider(this, createViewModelFactory())[viewModelClass]
    }

    /** Supply repository dependencies explicitly. */
    protected abstract fun createViewModelFactory(): ViewModelProvider.Factory
    /** Bind views and dispatch the screen's initial input. */
    protected abstract fun initView()
    /** Render an immutable, strongly typed state snapshot. */
    protected abstract fun render(state: S)
    /** Interpret a transient result using this Activity's resources and lifecycle. */
    protected abstract fun handleEffect(effect: F)

    /** Bind once and collect state/effects only while the Activity is STARTED. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        initView()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.effect.collect { handleEffect(it) } }
            }
        }
    }
}
