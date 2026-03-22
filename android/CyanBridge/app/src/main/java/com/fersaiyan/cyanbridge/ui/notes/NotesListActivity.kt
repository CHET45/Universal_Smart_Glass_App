package com.fersaiyan.cyanbridge.ui.notes

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.fersaiyan.cyanbridge.databinding.ActivityNotesListBinding
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class NotesListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesListBinding
    private lateinit var adapter: NoteListAdapter

    private val uiScope = MainScope()
    private var notesJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = NoteListAdapter { note ->
            startActivity(Intent(this, NoteDetailActivity::class.java).apply {
                putExtra(NoteDetailActivity.EXTRA_NOTE_ID, note.id)
            })
        }

        binding.recyclerNotes.layoutManager = LinearLayoutManager(this)
        binding.recyclerNotes.adapter = adapter

        binding.fabNewNote.setOnClickListener {
            showCreateFromTranscriptDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        notesJob?.cancel()
        notesJob = uiScope.launch {
            MyApplication.notesRepository.getAllNotes().collect { notes ->
                adapter.submitList(notes)
                binding.emptyState.visibility = if (notes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.recyclerNotes.visibility = if (notes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    override fun onStop() {
        super.onStop()
        notesJob?.cancel()
        notesJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showCreateFromTranscriptDialog() {
        val titleInput = EditText(this).apply {
            hint = "Title (optional)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val transcriptInput = EditText(this).apply {
            hint = "Paste transcript here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(titleInput)
            addView(transcriptInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("New note from transcript")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val transcript = transcriptInput.text?.toString().orEmpty().trim()
                val hintTitle = titleInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }

                if (transcript.isBlank()) {
                    Toast.makeText(this, "Transcript is empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                uiScope.launch {
                    try {
                        val id = MyApplication.notesRepository.createFromTranscript(
                            transcript = transcript,
                            hintTitle = hintTitle,
                            deviceClass = null,
                            durationSec = null,
                            tagsCsv = null,
                            storeTranscript = true,
                        )
                        startActivity(Intent(this@NotesListActivity, NoteDetailActivity::class.java).apply {
                            putExtra(NoteDetailActivity.EXTRA_NOTE_ID, id)
                        })
                    } catch (t: Throwable) {
                        Toast.makeText(this@NotesListActivity, "Failed to create note: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}
