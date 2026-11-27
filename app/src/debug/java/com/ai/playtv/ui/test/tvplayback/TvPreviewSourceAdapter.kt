package com.ai.playtv.ui.test.tvplayback

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.BuildConfig
import com.ai.playtv.databinding.ItemTvPreviewSourceBinding
import com.ai.playtv.model.bean.domain.source.ImportedSource

/** State flags distinguish the selected source, active playback and Android's current focus. */
data class TvPreviewSourceRow(val imported: ImportedSource, val selected: Boolean, val playing: Boolean) {
    val id: String get() = imported.id
}

class TvPreviewSourceAdapter(private val onSelect: (TvPreviewSourceRow) -> Unit) :
    ListAdapter<TvPreviewSourceRow, TvPreviewSourceAdapter.Holder>(DIFF) {
    class Holder(val binding: ItemTvPreviewSourceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemTvPreviewSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val available = BuildConfig.TEST_WEBM_CANDIDATE ||
            row.imported.url != TvPlaybackViewModel.KNOWN_CRASHING_WEBM
        holder.binding.root.isEnabled = available
        holder.binding.root.isSelected = row.selected
        holder.binding.root.isActivated = row.playing
        holder.binding.sourceName.text = row.imported.name
        holder.binding.sourceName.isSelected = row.playing
        holder.binding.sourceState.setText(when {
            !available -> R.string.s_tv_preview_crashing_sample
            row.playing -> R.string.s_tv_preview_playing_label
            row.selected -> R.string.s_tv_preview_selected_label
            else -> R.string.s_tv_preview_available_label
        })
        holder.binding.root.setOnClickListener { onSelect(row) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TvPreviewSourceRow>() {
            override fun areItemsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old.id == new.id
            override fun areContentsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old == new
        }
    }
}
