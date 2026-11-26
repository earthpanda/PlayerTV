package com.ai.playtv.ui.test.sample

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.databinding.ItemSampleBinding
import com.ai.playtv.model.bean.domain.sample.SampleItem

/**
 * AndroidX-only business Adapter; no MultiType dependency or custom adapter framework.
 * Example: recycler.adapter = SampleAdapter { id -> dispatch(SampleEvent.ItemClicked(id)) }.
 */
class SampleAdapter(
    /** Screen-owned interaction callback; not stored in a model State. */
    private val onItemClick: (Long) -> Unit,
) : ListAdapter<SampleItem, SampleAdapter.Holder>(Diff) {
    /** Binding holder is UI implementation, not business data. */
    class Holder(val binding: ItemSampleBinding) : RecyclerView.ViewHolder(binding.root)

    /** Inflate the strongly typed row without reflection or resource IDs in model. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSampleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    /** Bind a snapshot identity instead of retaining a mutable adapter position. */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.root.text = holder.itemView.context.getString(R.string.s_sample_item_title, item.id)
        holder.binding.root.setOnClickListener { onItemClick(item.id) }
    }

    /** Stable identity and immutable content comparison power differential updates. */
    private object Diff : DiffUtil.ItemCallback<SampleItem>() {
        /** Preserve row identity across append and refresh. */
        override fun areItemsTheSame(oldItem: SampleItem, newItem: SampleItem): Boolean = oldItem.id == newItem.id
        /** Compare only immutable display input. */
        override fun areContentsTheSame(oldItem: SampleItem, newItem: SampleItem): Boolean = oldItem == newItem
    }
}
