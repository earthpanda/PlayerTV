package com.ai.playtv.ui.test.bledemo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ai.playtv.R
import com.ai.playtv.databinding.ItemBlePeripheralBinding
import com.ai.playtv.base.bluetooth.ble.BlePeripheral

/** Scan address is the identity of this discovery run, not a persistent identity for rotating MACs. */
class BlePeripheralAdapter(private val onConnect: (String) -> Unit) :
    ListAdapter<BlePeripheral, BlePeripheralAdapter.Holder>(object : DiffUtil.ItemCallback<BlePeripheral>() {
        override fun areItemsTheSame(oldItem: BlePeripheral, newItem: BlePeripheral) = oldItem.address == newItem.address
        override fun areContentsTheSame(oldItem: BlePeripheral, newItem: BlePeripheral) = oldItem == newItem
    }) {
    inner class Holder(val binding: ItemBlePeripheralBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onConnect(getItem(position).address)
            }
        }
        fun bind(item: BlePeripheral) {
            binding.name.text = item.name ?: binding.root.context.getString(R.string.s_ble_demo_unknown_device)
            binding.details.text = binding.root.context.getString(R.string.s_ble_demo_device, item.address, item.rssi)
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemBlePeripheralBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
}
