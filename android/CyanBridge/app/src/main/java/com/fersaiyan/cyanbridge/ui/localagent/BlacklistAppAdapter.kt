package com.fersaiyan.cyanbridge.ui.localagent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.databinding.ItemBlacklistAppBinding

class BlacklistAppAdapter(
    initiallySelected: Set<String>,
) : ListAdapter<BlacklistAppItem, BlacklistAppAdapter.VH>(DIFF) {

    private val selected = initiallySelected.toMutableSet()

    fun getSelectedPackages(): Set<String> = selected.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBlacklistAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class VH(private val b: ItemBlacklistAppBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: BlacklistAppItem) {
            b.tvLabel.text = item.label
            b.tvPackage.text = item.packageName
            b.ivIcon.setImageDrawable(item.icon)

            // IMPORTANT: clear any old listener before setting isChecked.
            // RecyclerView recycles views; leaving the previous listener causes selection bugs
            // when the checkbox state is programmatically updated during bind.
            b.checkbox.setOnCheckedChangeListener(null)

            val isChecked = selected.contains(item.packageName)
            b.checkbox.isChecked = isChecked

            b.checkbox.setOnCheckedChangeListener { _, checked ->
                setSelected(item.packageName, checked)
            }

            // Toggle via row click (listener above updates the selection set).
            b.root.setOnClickListener {
                b.checkbox.isChecked = !b.checkbox.isChecked
            }
        }

        private fun setSelected(pkg: String, checked: Boolean) {
            if (checked) selected.add(pkg) else selected.remove(pkg)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlacklistAppItem>() {
            override fun areItemsTheSame(oldItem: BlacklistAppItem, newItem: BlacklistAppItem): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: BlacklistAppItem, newItem: BlacklistAppItem): Boolean {
                return oldItem.label == newItem.label &&
                    oldItem.packageName == newItem.packageName &&
                    oldItem.isSystemApp == newItem.isSystemApp
            }
        }
    }
}
