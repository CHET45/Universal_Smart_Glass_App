package com.fersaiyan.cyanbridge.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ListAdapter version kept for efficiency; NotesListActivity currently uses NoteListAdapter,
 * but this adapter is also valid and compiled.
 */
class NotesListAdapter(
    private val onClick: (Note) -> Unit,
) : ListAdapter<Note, NotesListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class VH(private val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun bind(note: Note, onClick: (Note) -> Unit) {
            binding.tvTitle.text = note.title
            binding.tvMeta.text = sdf.format(Date(note.createdAt))
            binding.root.setOnClickListener { onClick(note) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean = oldItem == newItem
        }
    }
}
