package com.ai.playtv.ui.test.sourcesearch

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.ViewGroup
import android.text.TextUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.databinding.ItemSourceSearchBinding
import com.ai.playtv.base.util.pt
import com.ai.playtv.model.contract.sourcesearch.SearchRow
import kotlinx.coroutines.Job

class SourceSearchAdapter(private val onOpen: (SearchRow) -> Unit, private val onLeft: () -> Unit,
    private val onFocused: (Int) -> Unit, private val onDown: (Int) -> Unit) :
    ListAdapter<SearchRow, SourceSearchAdapter.Holder>(DIFF) {
    var artworkLoader: SearchArtworkLoader? = null
    class Holder(val binding: ItemSourceSearchBinding) : RecyclerView.ViewHolder(binding.root) {
        var artworkJob: Job? = null
        var boundRow: SearchRow? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSourceSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        clearArtwork(holder)
        holder.boundRow = row
        bindArtwork(holder)
        holder.binding.title.text = row.name
        val focused = holder.binding.root.hasFocus()
        val scale = if (focused) 1.2f else 1f
        holder.binding.root.animate().cancel()
        holder.binding.root.scaleX = scale
        holder.binding.root.scaleY = scale
        holder.binding.root.translationZ = if (focused) 12.pt.toFloat() else 0f
        holder.binding.title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        holder.binding.title.isSelected = focused
        holder.binding.root.setOnFocusChangeListener { view, focused ->
            view.translationZ = if (focused) 12.pt.toFloat() else 0f
            holder.binding.title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            holder.binding.title.isSelected = focused
            if (focused && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) onFocused(holder.bindingAdapterPosition)
            view.animate().scaleX(if (focused) 1.2f else 1f).scaleY(if (focused) 1.2f else 1f)
                .setDuration(150).start()
        }
        holder.binding.root.setOnClickListener { onOpen(row) }
        holder.binding.root.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onDown(holder.bindingAdapterPosition)
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                holder.bindingAdapterPosition != RecyclerView.NO_POSITION && holder.bindingAdapterPosition % 4 == 0) {
                onLeft(); true
            } else false
        }
    }
    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        if (holder.artworkJob == null) bindArtwork(holder)
    }
    override fun onViewDetachedFromWindow(holder: Holder) {
        clearArtwork(holder)
        super.onViewDetachedFromWindow(holder)
    }
    override fun onFailedToRecycleView(holder: Holder): Boolean {
        holder.binding.root.animate().cancel()
        return !holder.binding.root.hasTransientState()
    }
    override fun onViewRecycled(holder: Holder) {
        clearArtwork(holder)
        holder.boundRow = null
        holder.binding.root.animate().cancel()
        holder.binding.root.setOnFocusChangeListener(null)
        holder.binding.root.translationZ = 0f
        holder.binding.root.scaleX = 1f
        holder.binding.root.scaleY = 1f
        holder.binding.title.isSelected = false
        holder.binding.title.ellipsize = TextUtils.TruncateAt.END
        super.onViewRecycled(holder)
    }
    private fun bindArtwork(holder: Holder) {
        holder.boundRow?.let { holder.artworkJob = artworkLoader?.bind(holder.binding.poster, it) }
    }
    private fun clearArtwork(holder: Holder) {
        holder.artworkJob?.cancel()
        holder.artworkJob = null
        holder.binding.poster.setImageDrawable(null)
    }
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SearchRow>() {
            override fun areItemsTheSame(old: SearchRow, new: SearchRow) = old.historyId == new.historyId && old.itemId == new.itemId
            override fun areContentsTheSame(old: SearchRow, new: SearchRow) = old == new
        }
    }
}
