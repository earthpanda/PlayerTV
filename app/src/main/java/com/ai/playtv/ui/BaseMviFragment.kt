package com.ai.playtv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.base.ui.BaseFragment
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Fragment-scoped ViewModel with view-lifecycle-scoped collectors and binding cleanup.
 * A subclass supplies its binding inflater, Factory, initView, render and handleEffect.
 * The Activity owns system-bar insets; this host does not apply them a second time.
 */
abstract class BaseMviFragment<VB : ViewBinding, S : UiState, E : UiEvent, F : UiEffect,
    VM : BaseMviViewModel<S, E, F>>(
    /** Binding factory for this Fragment's view tree. */
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB,
) : BaseFragment() {
    /** Cleared whenever the Fragment view is destroyed. */
    private var viewBinding: VB? = null
    /** Access is valid only between onCreateView and onDestroyView. */
    protected val binding: VB get() = checkNotNull(viewBinding)
    /** Registered ViewModel type. */
    protected abstract val viewModelClass: Class<VM>
    /** Fragment-scoped state survives view recreation. */
    protected val viewModel: VM by lazy {
        ViewModelProvider(this, createViewModelFactory())[viewModelClass]
    }

    /** Provide dependencies without retaining a View/Activity inside model data. */
    protected abstract fun createViewModelFactory(): ViewModelProvider.Factory
    /** Attach listeners and initialize the newly created view. */
    protected abstract fun initView()
    /** Render the current Contract state. */
    protected abstract fun render(state: S)
    /** Handle this screen's transient results. */
    protected abstract fun handleEffect(effect: F)

    /** Inflate a fresh binding for each view lifecycle. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewBinding = inflate(inflater, container, false)
        return binding.root
    }

    /** Scope collectors to the view, preventing updates after onDestroyView. */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.effect.collect { handleEffect(it) } }
            }
        }
    }

    /** Release only view references; ViewModel ownership remains with the Fragment. */
    override fun onDestroyView() {
        viewBinding = null
        super.onDestroyView()
    }
}
