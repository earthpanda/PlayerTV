package com.ai.playtv.ui.test.sourcesearch

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.databinding.ItemSourceSearchBinding
import com.ai.playtv.model.contract.sourcesearch.SearchRow

class SourceSearchAdapter(private val onOpen: (SearchRow) -> Unit, private val onLeft: () -> Unit) :
    ListAdapter<SearchRow, SourceSearchAdapter.Holder>(DIFF) {
    class Holder(val binding: ItemSourceSearchBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSourceSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.title.text = row.name
        val scale = if (holder.binding.root.hasFocus()) 1.2f else 1f
        holder.binding.root.animate().cancel()
        holder.binding.root.scaleX = scale
        holder.binding.root.scaleY = scale
        holder.binding.root.setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.2f else 1f).scaleY(if (focused) 1.2f else 1f)
                .setDuration(150).start()
        }
        holder.binding.root.setOnClickListener { onOpen(row) }
        holder.binding.root.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                holder.bindingAdapterPosition != RecyclerView.NO_POSITION && holder.bindingAdapterPosition % 4 == 0) {
                onLeft(); true
            } else false
        }
    }
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SearchRow>() {
            override fun areItemsTheSame(old: SearchRow, new: SearchRow) = old.historyId == new.historyId && old.itemId == new.itemId
            override fun areContentsTheSame(old: SearchRow, new: SearchRow) = old == new
        }
    }
}
