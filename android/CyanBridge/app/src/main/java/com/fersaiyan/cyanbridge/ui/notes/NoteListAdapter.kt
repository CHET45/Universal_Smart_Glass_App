package com.fersaiyan.cyanbridge.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteListAdapter(
    private val onClick: (Note) -> Unit,
) : RecyclerView.Adapter<NoteListAdapter.VH>() {

    private val items = mutableListOf<Note>()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun submitList(list: List<Note>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick, df)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(
        private val binding: ItemNoteBinding,
        private val onClick: (Note) -> Unit,
        private val df: SimpleDateFormat,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            binding.tvTitle.text = note.title

            val metaParts = mutableListOf<String>()
            metaParts.add(df.format(Date(note.createdAt)))
            note.deviceClass?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            binding.tvMeta.text = metaParts.joinToString(" · ")

            binding.root.setOnClickListener { onClick(note) }
        }
    }
}
