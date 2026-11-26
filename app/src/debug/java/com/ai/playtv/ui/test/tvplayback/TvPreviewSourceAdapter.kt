package com.ai.playtv.ui.test.tvplayback

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.databinding.ItemTvPreviewSourceBinding
import com.ai.playtv.model.contract.tvplayback.TvPreviewSource
import com.ai.playtv.model.bean.domain.source.ImportedSource

/** State flags distinguish the selected source, active playback and Android's current focus. */
data class TvPreviewSourceRow(val source: TvPreviewSource?, val imported: ImportedSource?, val selected: Boolean, val playing: Boolean) {
    val id: String get() = source?.name ?: requireNotNull(imported).id
}

class TvPreviewSourceAdapter(private val onSelect: (TvPreviewSourceRow) -> Unit) :
    ListAdapter<TvPreviewSourceRow, TvPreviewSourceAdapter.Holder>(DIFF) {
    class Holder(val binding: ItemTvPreviewSourceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemTvPreviewSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val available = row.source != TvPreviewSource.GITHUB_WEBM &&
            row.imported?.url != TvPlaybackViewModel.KNOWN_CRASHING_WEBM
        holder.binding.root.isEnabled = available
        holder.binding.root.isSelected = row.selected
        holder.binding.root.isActivated = row.playing
        if (row.source != null) holder.binding.sourceName.setText(sourceLabel(row.source))
        else holder.binding.sourceName.text = row.imported?.name
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
            TvPreviewSource.GITHUB_FLV -> R.string.s_tv_preview_source_github_flv
            TvPreviewSource.SAMPLE_AVI -> R.string.s_tv_preview_source_avi
            TvPreviewSource.SAMPLE_MOV -> R.string.s_tv_preview_source_mov
            TvPreviewSource.SAMPLE_M4V -> R.string.s_tv_preview_source_m4v
            TvPreviewSource.SAMPLE_TS -> R.string.s_tv_preview_source_ts
            TvPreviewSource.GITHUB_MP3 -> R.string.s_tv_preview_source_mp3
            TvPreviewSource.GITHUB_M4A -> R.string.s_tv_preview_source_m4a
            TvPreviewSource.HLS -> R.string.s_tv_preview_source_hls
            TvPreviewSource.DASH -> R.string.s_tv_preview_source_dash
            TvPreviewSource.CGTN -> R.string.s_tv_preview_source_cgtn
            TvPreviewSource.CGTN_DOCUMENTARY -> R.string.s_tv_preview_source_cgtn_documentary
            TvPreviewSource.FRANCE24_EN -> R.string.s_tv_preview_source_france24
        }
        private val DIFF = object : DiffUtil.ItemCallback<TvPreviewSourceRow>() {
            override fun areItemsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old.id == new.id
            override fun areContentsTheSame(old: TvPreviewSourceRow, new: TvPreviewSourceRow) = old == new
        }
    }
}
