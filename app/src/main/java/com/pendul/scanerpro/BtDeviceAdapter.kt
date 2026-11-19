package com.pendul.scanerpro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BtDeviceAdapter(
    private val onDeviceClick: (BtDeviceItem) -> Unit
) : RecyclerView.Adapter<BtDeviceAdapter.ViewHolder>() {

    private val items = mutableListOf<BtDeviceItem>()

    fun submitList(newList: List<BtDeviceItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    fun upsertDevice(item: BtDeviceItem) {
        val index = items.indexOfFirst { it.address == item.address }
        if (index >= 0) {
            items[index] = item
            notifyItemChanged(index)
        } else {
            items.add(item)
            notifyItemInserted(items.size - 1)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bt_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name.ifBlank { "Perangkat Tanpa Nama" }
        holder.tvAddress.text = if (item.rssi != 0) "${item.address} (${item.rssi} dBm)" else item.address
        holder.tvBadge.text = when {
            item.isPaired -> "PAIRED"
            item.isBle -> "BLE"
            else -> "CLASSIC"
        }
        holder.itemView.setOnClickListener { onDeviceClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvBadge: TextView = view.findViewById(R.id.tvDeviceBadge)
    }
}
