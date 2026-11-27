package com.ai.playtv.ui.test.sourceentry

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.ai.playtv.R
import com.ai.playtv.SourcePreviewApplication
import com.ai.playtv.base.ui.loading.OperationLoadingController
import com.ai.playtv.base.ui.KeyboardController
import com.ai.playtv.base.ui.message.MessageController
import com.ai.playtv.databinding.ActivitySourceEntryBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.model.contract.sourceentry.*
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.ui.test.tvplayback.TvPlaybackActivity
import com.ai.playtv.ui.test.sourcesearch.SourceSearchActivity
import com.ai.playtv.view.loading.createOperationLoadingController

class SourceEntryActivity : BaseMviActivity<ActivitySourceEntryBinding, SourceEntryState, SourceEntryEvent,
    SourceEntryEffect, SourceEntryViewModel>(ActivitySourceEntryBinding::inflate) {
    private enum class HomeTab { HISTORY, SOURCES }
    private companion object {
        const val SAVED_TAB = "source_home_tab"
        const val SAVED_HISTORY_FOCUS = "source_home_history_focus"
        const val SIDEBAR_EXPANDED = 0.18f
        const val SIDEBAR_COLLAPSED = 0.12f
    }
    override val viewModelClass = SourceEntryViewModel::class.java
    override fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory(viewModelClass) {
        val app = application as SourcePreviewApplication
        SourceEntryViewModel(app.discoveryOwner, app.sourceHistory)
    }
    private lateinit var loading: OperationLoadingController
    private lateinit var messages: MessageController
    private lateinit var keyboard: KeyboardController
    private var imeVisible = false
    private var handledKey: Int? = null
    private var selectedTab = HomeTab.HISTORY
    private var updatingTab = false
    private var lastHistoryFocus: String? = null
    private var pendingHistoryFocus = false
    private var sidebarExpanded = true
    private var sidebarAnimator: ValueAnimator? = null
    private val historyAdapter = SourceHistoryAdapter(
        onSelect = { viewModel.onEvent(SourceEntryEvent.OpenHistory(it)) },
        onFocused = { lastHistoryFocus = it },
    )
    private lateinit var repositoryAdapter: ArrayAdapter<String>
    private var repositorySuggestions = emptyList<String>()
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        when {
            isInside(focused, binding.sidebar) || focused === binding.searchTab -> setSidebarExpanded(true)
            isInside(focused, binding.content) -> setSidebarExpanded(false)
        }
    }
    private val inputBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = keyboard.hide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedTab = if (savedInstanceState?.getString(SAVED_TAB) == HomeTab.SOURCES.name)
            HomeTab.SOURCES else HomeTab.HISTORY
        lastHistoryFocus = savedInstanceState?.getString(SAVED_HISTORY_FOCUS)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_TAB, selectedTab.name)
        outState.putString(SAVED_HISTORY_FOCUS, lastHistoryFocus)
        super.onSaveInstanceState(outState)
    }
    override fun initView() {
        loading = createOperationLoadingController(this)
        messages = MessageController(this)
        keyboard = KeyboardController(binding.sourceUrl)
        repositoryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        binding.sourceUrl.setAdapter(repositoryAdapter)
        onBackPressedDispatcher.addCallback(this, inputBack)
        binding.sourceUrl.setOnFocusChangeListener { _, focused ->
            inputBack.isEnabled = focused && imeVisible
            if (focused) {
                keyboard.showAfterLayout(requestFocus = false)
            } else {
                keyboard.hide()
            }
        }
        binding.sourceUrl.setOnClickListener { keyboard.showAfterLayout(requestFocus = false) }
        // The IME may consume Back itself; observe its insets without replacing the root's system-bar listener.
        ViewCompat.setOnApplyWindowInsetsListener(binding.sourceUrl) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            imeVisible = visible
            inputBack.isEnabled = visible && binding.sourceUrl.hasFocus()
            insets
        }
        binding.history.layoutManager = LinearLayoutManager(this)
        binding.history.adapter = historyAdapter
        binding.historyTab.setOnFocusChangeListener { _, focused -> if (focused && !updatingTab) selectTab(HomeTab.HISTORY) }
        binding.sourceTab.setOnFocusChangeListener { _, focused -> if (focused && !updatingTab) selectTab(HomeTab.SOURCES) }
        binding.historyTab.setOnClickListener { selectTab(HomeTab.HISTORY); binding.historyTab.requestFocusFromTouch() }
        binding.sourceTab.setOnClickListener { selectTab(HomeTab.SOURCES); binding.sourceTab.requestFocusFromTouch() }
        binding.searchTab.setOnClickListener { startActivity(Intent(this, SourceSearchActivity::class.java)) }
        binding.addSource.setOnClickListener { selectTab(HomeTab.SOURCES); binding.sourceUrl.requestFocus() }
        binding.submit.setOnClickListener { viewModel.onEvent(SourceEntryEvent.Submit(binding.sourceUrl.text.toString())) }
        selectTab(selectedTab)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        currentTabView().requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::keyboard.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && handledKey == event.keyCode) {
            handledKey = null
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || binding.sourceUrl.isPopupShowing) return super.dispatchKeyEvent(event)
        val current = currentFocus
        if (current === binding.sourceUrl && imeVisible) return super.dispatchKeyEvent(event)
        if (current === binding.historyTab || current === binding.sourceTab) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.repeatCount == 0) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            if (current === binding.historyTab) binding.searchTab.requestFocus()
                            else binding.historyTab.requestFocus()
                        } else binding.sourceTab.requestFocus()
                    }
                    handledKey = event.keyCode
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) enterSelectedTab()
                    handledKey = event.keyCode
                    return true
                }
            }
        } else if (current === binding.searchTab) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.repeatCount == 0) currentTabView().requestFocus()
                    handledKey = event.keyCode
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    handledKey = event.keyCode
                    return true
                }
            }
        } else if (isInside(current, binding.content)) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val editing = current === binding.sourceUrl &&
                    (imeVisible || binding.sourceUrl.selectionStart > 0 || binding.sourceUrl.selectionEnd > 0)
                if (!editing) {
                    currentTabView().requestFocus()
                    handledKey = event.keyCode
                    return true
                }
            }
            if (current === binding.sourceUrl || current === binding.submit) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) {
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && current === binding.submit)
                                binding.sourceUrl.requestFocus()
                            else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && current === binding.sourceUrl && binding.submit.isEnabled)
                                binding.submit.requestFocus()
                        }
                        handledKey = event.keyCode
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectTab(tab: HomeTab) {
        updatingTab = true
        try {
            selectedTab = tab
            if (tab != HomeTab.HISTORY) pendingHistoryFocus = false
            binding.historyTab.isSelected = tab == HomeTab.HISTORY
            binding.sourceTab.isSelected = tab == HomeTab.SOURCES
            binding.historyPanel.visibility = if (tab == HomeTab.HISTORY) View.VISIBLE else View.GONE
            binding.sourcePanel.visibility = if (tab == HomeTab.SOURCES) View.VISIBLE else View.GONE
        } finally {
            updatingTab = false
        }
    }

    private fun currentTabView(): View = if (selectedTab == HomeTab.HISTORY) binding.historyTab else binding.sourceTab

    private fun enterSelectedTab() {
        if (selectedTab == HomeTab.SOURCES) {
            binding.sourceUrl.requestFocus()
        } else if (binding.historyEmpty.visibility == View.VISIBLE) {
            binding.addSource.requestFocus()
        } else if (historyAdapter.currentList.isNotEmpty()) {
            val index = historyAdapter.currentList.indexOfFirst { it.id == lastHistoryFocus }.coerceAtLeast(0)
            binding.history.scrollToPosition(index)
            binding.history.post {
                if (!isFinishing && !isDestroyed && selectedTab == HomeTab.HISTORY && binding.historyTab.hasFocus()) {
                    val item = binding.history.findViewHolderForAdapterPosition(index)?.itemView
                    if (item == null || !item.requestFocus()) binding.history.requestFocus()
                }
            }
        } else if (binding.history.visibility == View.VISIBLE) {
            // ListAdapter may still be applying a new history snapshot.
            pendingHistoryFocus = true
        }
    }

    private fun isInside(view: View?, ancestor: View): Boolean {
        var current = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun setSidebarExpanded(expanded: Boolean) {
        if (sidebarExpanded == expanded) return
        sidebarExpanded = expanded
        sidebarAnimator?.cancel()
        val params = binding.sidebar.layoutParams as ConstraintLayout.LayoutParams
        val target = if (expanded) SIDEBAR_EXPANDED else SIDEBAR_COLLAPSED
        sidebarAnimator = ValueAnimator.ofFloat(params.matchConstraintPercentWidth, target).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val current = binding.sidebar.layoutParams as ConstraintLayout.LayoutParams
                current.matchConstraintPercentWidth = animator.animatedValue as Float
                binding.sidebar.layoutParams = current
            }
            start()
        }
    }

    private fun restoreInput() {
        keyboard.hide(clearFocus = true)
        if (!binding.submit.requestFocus()) currentTabView().requestFocus()
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.sourceUrl, null)
        binding.sourceUrl.dismissDropDown()
        if (::keyboard.isInitialized) keyboard.close()
        sidebarAnimator?.cancel()
        if (binding.root.viewTreeObserver.isAlive) binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        binding.history.adapter = null
        super.onDestroy()
    }
    override fun render(state: SourceEntryState) {
        if (repositorySuggestions != state.repositorySuggestions) {
            repositorySuggestions = state.repositorySuggestions
            repositoryAdapter.clear()
            repositoryAdapter.addAll(repositorySuggestions)
        }
        if (!state.historyLoading) {
            historyAdapter.submitList(state.history) {
                if (!isFinishing && !isDestroyed && pendingHistoryFocus && selectedTab == HomeTab.HISTORY && binding.historyTab.hasFocus()) {
                    pendingHistoryFocus = false
                    enterSelectedTab()
                }
            }
            binding.history.visibility = if (state.history.isNotEmpty()) View.VISIBLE else View.GONE
            binding.historyEmpty.visibility = if (!state.historyFailed && state.history.isEmpty()) View.VISIBLE else View.GONE
            binding.historyMessage.text = if (state.historyFailed) getString(R.string.s_tv_history_failed) else ""
        }
        if (state.historyLoading) binding.historyLoading.showLoading(getString(R.string.s_tv_history_loading))
        else binding.historyLoading.hide()
        loading.render(state.loadingId, getString(R.string.s_tv_import_loading), true) { viewModel.onEvent(SourceEntryEvent.Cancel(it)) }
        binding.status.text = when (state.error) {
            "EMPTY" -> getString(R.string.s_tv_import_empty)
            "PARTIAL_EMPTY" -> getString(R.string.s_tv_import_partial_empty)
            "CLOUD" -> getString(R.string.s_tv_import_cloud)
            "WEBSITE" -> getString(R.string.s_tv_import_website)
            "RATE_LIMIT" -> getString(R.string.s_tv_import_rate_limit)
            "HTTP_403" -> getString(R.string.s_tv_http_forbidden)
            "REQUIRED" -> getString(R.string.s_tv_import_required)
            "HISTORY_FAILED" -> getString(R.string.s_tv_history_failed)
            "FAILED" -> getString(R.string.s_tv_import_failed)
            else -> ""
        }
    }
    override fun handleEffect(effect: SourceEntryEffect) {
        when (effect) {
            SourceEntryEffect.ServerForbidden -> messages.show(getString(R.string.s_tv_http_forbidden))
            is SourceEntryEffect.OpenHistory -> startActivity(Intent(this, TvPlaybackActivity::class.java)
                .putExtra(TvPlaybackActivity.EXTRA_HISTORY_ID, effect.id))
            is SourceEntryEffect.OpenPlayer -> {
                val intent = Intent(this, TvPlaybackActivity::class.java)
                intent.putExtra(TvPlaybackActivity.EXTRA_SESSION_ID, effect.sessionId)
                startActivity(intent)
            }
        }
    }
}
