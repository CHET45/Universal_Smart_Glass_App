package com.fersaiyan.cyanbridge.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.chat.ChatThread
import com.fersaiyan.cyanbridge.databinding.ItemChatThreadBinding

class ChatThreadAdapter(
    private val onClick: (ChatThread) -> Unit,
    private val onDelete: (ChatThread) -> Unit,
) : RecyclerView.Adapter<ChatThreadAdapter.VH>() {
    private var items: List<ChatThread> = emptyList()

    fun submitList(newItems: List<ChatThread>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatThreadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding, onClick, onDelete)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(
        private val binding: ItemChatThreadBinding,
        private val onClick: (ChatThread) -> Unit,
        private val onDelete: (ChatThread) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatThread) {
            binding.threadTitle.text = item.title
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { view ->
                AlertDialog.Builder(view.context)
                    .setTitle(view.context.getString(R.string.delete_chat_title))
                    .setMessage(view.context.getString(R.string.delete_chat_message, item.title))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        onDelete(item)
                    }
                    .show()
                true
            }
        }
    }
}
