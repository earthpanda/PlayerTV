package com.ai.playtv.ui.test.sourceentry

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.databinding.ItemSourceHistoryBinding
import com.ai.playtv.model.contract.sourceentry.SourceHistoryRow
import java.text.DateFormat
import java.util.Date

/** Presentation only: the store owns the saved source and its candidates. */
class SourceHistoryAdapter(private val onSelect: (String) -> Unit) :
    ListAdapter<SourceHistoryRow, SourceHistoryAdapter.Holder>(DIFF) {
    class Holder(val binding: ItemSourceHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemSourceHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.historyName.text = item.title
        holder.binding.historyTime.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(item.lastUsedAtMs))
        holder.binding.root.setOnClickListener { onSelect(item.id) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SourceHistoryRow>() {
            override fun areItemsTheSame(old: SourceHistoryRow, new: SourceHistoryRow) = old.id == new.id
            override fun areContentsTheSame(old: SourceHistoryRow, new: SourceHistoryRow) = old == new
        }
    }
}
