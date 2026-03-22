package com.fersaiyan.cyanbridge.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.databinding.ActivityNoteDetailBinding
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NoteDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteDetailBinding
    private val uiScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        if (noteId <= 0L) {
            finish()
            return
        }

        uiScope.launch {
            val note = MyApplication.notesRepository.getNoteById(noteId)
            if (note == null) {
                Toast.makeText(this@NoteDetailActivity, "Note not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            binding.toolbar.title = note.title
            binding.tvNoteBody.text = note.summary

            binding.btnCopy.setOnClickListener {
                copyToClipboard(note.summary)
            }
            binding.btnShare.setOnClickListener {
                shareText(note.summary)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("note", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, "Share note"))
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}
