package com.ai.playtv.ui.test.tvplayback

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.databinding.ItemTvPreviewSourceBinding
import com.ai.playtv.model.contract.tvplayback.TvPreviewSource

/** State flags distinguish the selected source, active playback and Android's current focus. */
data class TvPreviewSourceRow(val source: TvPreviewSource, val selected: Boolean, val playing: Boolean)

class TvPreviewSourceAdapter(private val onSelect: (TvPreviewSource) -> Unit) :
    ListAdapter<TvPreviewSourceRow, TvPreviewSourceAdapter.Holder>(DIFF) {
    class Holder(val binding: ItemTvPreviewSourceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemTvPreviewSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val available = row.source != TvPreviewSource.GITHUB_WEBM
        holder.binding.root.isEnabled = available
        holder.binding.root.isSelected = row.selected
        holder.binding.root.isActivated = row.playing
        holder.binding.sourceName.setText(sourceLabel(row.source))
        holder.binding.sourceName.isSelected = row.playing
        holder.binding.sourceState.setText(when {
            !available -> R.string.s_tv_preview_crashing_sample
            row.playing -> R.string.s_tv_preview_playing_label
            row.selected -> R.string.s_tv_preview_selected_label
            else -> R.string.s_tv_preview_available_label
        })
        holder.binding.root.setOnClickListener { onSelect(row.source) }
    }

    companion object {
        fun sourceLabel(source: TvPreviewSource): Int = when (source) {
            TvPreviewSource.BUNNY -> R.string.s_tv_preview_source_bunny
            TvPreviewSource.OCEANS -> R.string.s_tv_preview_source_oceans
            TvPreviewSource.FLOWER -> R.string.s_tv_preview_source_flower
            TvPreviewSource.SINTEL -> R.string.s_tv_preview_source_sintel
            TvPreviewSource.GITHUB_BUNNY -> R.string.s_tv_preview_source_github_bunny
            TvPreviewSource.GITHUB_ECHO -> R.string.s_tv_preview_source_github_echo
            TvPreviewSource.GITHUB_FLOWER -> R.string.s_tv_preview_source_github_flower
            TvPreviewSource.GITHUB_WEBM -> R.string.s_tv_preview_source_github_webm
            TvPreviewSource.GITHUB_OGV -> R.string.s_tv_preview_source_github_ogv
            TvPreviewSource.GITHUB_MKV -> R.string.s_tv_preview_source_github_mkv
            TvPreviewSource.HLS -> R.string.s_tv_preview_source_hls
            TvPreviewSource.DASH -> R.string.s_tv_preview_source_dash
            TvPreviewSource.CGTN -> R.string.s_tv_preview_source_cgtn
            TvPreviewSource.CGTN_DOCUMENTARY -> R.string.s_tv_preview_source_cgtn_documentary
            TvPreviewSource.FRANCE24_EN -> R.string.s_tv_preview_source_france24
        }
        private val DIFF = object : DiffUtil.ItemCallback<TvPreviewSourceRow>() {
            override fun areItemsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old.source == new.source
            override fun areContentsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old == new
        }
    }
}
