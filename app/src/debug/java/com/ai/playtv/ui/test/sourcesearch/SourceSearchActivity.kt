package com.ai.playtv.ui.test.sourcesearch

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.SourcePreviewApplication
import com.ai.playtv.base.util.setTextPtSize
import com.ai.playtv.base.util.pt
import com.ai.playtv.databinding.ActivitySourceSearchBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.model.contract.sourcesearch.*
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.ui.test.tvplayback.TvPlaybackActivity

class SourceSearchActivity : BaseMviActivity<ActivitySourceSearchBinding, SourceSearchState,
    SourceSearchEvent, SourceSearchEffect, SourceSearchViewModel>(ActivitySourceSearchBinding::inflate) {
    override val viewModelClass = SourceSearchViewModel::class.java
    private var restoredQuery = ""
    private var restoredKey = 9
    private var lastKey: View? = null
    private var lastKeyIndex = 9
    private lateinit var artworkLoader: SearchArtworkLoader
    private var renderedQuery: String? = null
    private var resetSelection = false
    private val adapter = SourceSearchAdapter({ viewModel.onEvent(SourceSearchEvent.Open(it)) },
        { (lastKey ?: keyAt(9))?.requestFocus() }, ::maybeLoadMore)
    override fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory(viewModelClass) {
        SourceSearchViewModel((application as SourcePreviewApplication).sourceHistory, restoredQuery)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        restoredQuery = savedInstanceState?.getString("query").orEmpty()
        restoredKey = savedInstanceState?.getInt("key", 9) ?: 9
        super.onCreate(savedInstanceState)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", viewModel.uiState.value.query)
        outState.putInt("key", lastKeyIndex)
        super.onSaveInstanceState(outState)
    }
    override fun initView() {
        artworkLoader = SearchArtworkLoader(application as SourcePreviewApplication)
        adapter.artworkLoader = artworkLoader
        binding.results.setNumColumns(4)
        binding.results.adapter = adapter
        binding.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val last = (0 until recyclerView.childCount).maxOfOrNull {
                        recyclerView.getChildAdapterPosition(recyclerView.getChildAt(it))
                    } ?: RecyclerView.NO_POSITION
                    maybeLoadMore(last)
                }
            }
        })
        binding.pageStatus.setOnClickListener { viewModel.onEvent(SourceSearchEvent.LoadMore) }
        val keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        keys.forEachIndexed { index, char ->
            val key = TextView(this).apply {
                text = char.toString(); gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE); setTextPtSize(23f)
                setBackgroundResource(R.drawable.source_search_focus)
                isFocusable = true; isClickable = true
                contentDescription = char.toString()
                setOnFocusChangeListener { v, focused -> if (focused) { lastKey = v; lastKeyIndex = index } }
                setOnClickListener { updateQuery(viewModel.uiState.value.query + char) }
                setOnKeyListener { _, code, event ->
                    val state = viewModel.uiState.value
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && index % 6 == 5 &&
                        state.query.isNotBlank() && !state.searching && state.rows.isNotEmpty() && adapter.currentList == state.rows &&
                        binding.results.visibility == View.VISIBLE) {
                        binding.results.requestFocus()
                    } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && index >= 30) {
                        (if (index % 6 < 3) binding.clear else binding.delete).requestFocus()
                    } else false
                }
            }
            val cell = FrameLayout(this)
            cell.addView(key, FrameLayout.LayoutParams(54.pt, 54.pt, Gravity.CENTER))
            cell.layoutParams = GridLayout.LayoutParams(GridLayout.spec(index / 6, 1f), GridLayout.spec(index % 6, 1f)).apply {
                width = 0; height = 0
            }
            binding.keyboard.addView(cell)
        }
        binding.clear.setOnClickListener { updateQuery("") }
        binding.delete.setOnClickListener {
            val value = viewModel.uiState.value.query
            if (value.isNotEmpty()) {
                val end = value.length
                val start = Character.offsetByCodePoints(value, end, -1)
                updateQuery(value.substring(0, start))
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.query.isNotEmpty()) updateQuery("")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        keyAt(restoredKey)?.requestFocus()
    }
    private fun keyAt(index: Int): View? = (binding.keyboard.getChildAt(index) as? FrameLayout)?.getChildAt(0)
    private fun updateQuery(value: String) = viewModel.onEvent(SourceSearchEvent.Query(value))
    private fun maybeLoadMore(position: Int) {
        val state = viewModel.uiState.value
        if (state.hasMore && !state.loadingMore && !state.pageFailed && !state.searching &&
            position >= 0 && position >= state.rows.size - 8) viewModel.onEvent(SourceSearchEvent.LoadMore)
    }
    override fun onResume() {
        super.onResume()
        viewModel.onEvent(SourceSearchEvent.Resume)
    }
    override fun render(state: SourceSearchState) {
        if (binding.searchInput.text.toString() != state.query) {
            binding.searchInput.text = state.query
        }
        if (renderedQuery != state.query) resetSelection = true
        renderedQuery = state.query
        adapter.submitList(state.rows) {
            if (resetSelection && state.rows.isNotEmpty() && viewModel.uiState.value.query == state.query &&
                viewModel.uiState.value.rows == state.rows) {
                resetSelection = false
                binding.results.setSelectedPosition(0)
            }
        }
        binding.resultTitle.text = if (state.query.isNotBlank() && !state.failed && !state.loading && !state.searching)
            getString(if (state.hasMore) R.string.s_search_shown else R.string.s_search_count, state.rows.size) else ""
        binding.partial.visibility = if (state.partial && state.query.isNotBlank()) View.VISIBLE else View.GONE
        binding.pageStatus.visibility = if (state.loadingMore || state.pageFailed) View.VISIBLE else View.GONE
        binding.pageStatus.isFocusable = state.pageFailed
        binding.pageStatus.isClickable = state.pageFailed
        binding.pageStatus.setText(if (state.pageFailed) R.string.s_search_retry_more else R.string.s_search_loading_more)
        binding.message.text = when {
            state.loading -> getString(R.string.s_tv_history_loading)
            state.failed -> getString(R.string.s_search_failed)
            state.searching -> getString(R.string.s_search_searching)
            !state.hasItems -> getString(R.string.s_search_empty_sources)
            state.query.isBlank() -> getString(R.string.s_search_default)
            state.rows.isEmpty() -> getString(R.string.s_search_empty_results)
            else -> ""
        }
        binding.message.visibility = if (binding.message.text.isEmpty()) View.GONE else View.VISIBLE
        binding.results.visibility = if (binding.message.visibility == View.GONE) View.VISIBLE else View.GONE
    }
    override fun handleEffect(effect: SourceSearchEffect) {
        when (effect) {
            is SourceSearchEffect.OpenPlayer -> startActivity(Intent(this, TvPlaybackActivity::class.java)
                .putExtra(TvPlaybackActivity.EXTRA_HISTORY_ID, effect.historyId)
                .putExtra(TvPlaybackActivity.EXTRA_ITEM_ID, effect.itemId))
        }
    }
    override fun onDestroy() {
        binding.results.adapter = null
        if (::artworkLoader.isInitialized) artworkLoader.close()
        adapter.artworkLoader = null
        super.onDestroy()
    }
}
