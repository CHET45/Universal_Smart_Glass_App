package com.fersaiyan.cyanbridge.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Initial state (privacy-first defaults are handled by PrivacySettings).
        binding.switchTranscriptStorage.isChecked = PrivacySettings.isTranscriptStorageEnabled(this)
        binding.switchRedaction.isChecked = PrivacySettings.isRedactionEnabled(this)

        binding.switchTranscriptStorage.setOnCheckedChangeListener { _, isChecked ->
            PrivacySettings.setTranscriptStorageEnabled(this, isChecked)
        }

        binding.switchRedaction.setOnCheckedChangeListener { _, isChecked ->
            PrivacySettings.setRedactionEnabled(this, isChecked)
        }

        binding.btnClearLocalData.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(binding.toolbar.title)
                .setMessage(
                    "This will clear app-local cached data and any stored transcripts (if present).\n\n" +
                        "It will not delete photos/videos already saved to your Gallery."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    clearLocalData()
                }
                .show()
        }
    }

    private fun clearLocalData() {
        binding.btnClearLocalData.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LocalDataCleaner.clearLocalData(this@SettingsActivity)
            }

            binding.btnClearLocalData.isEnabled = true

            val msg = if (result.errors == 0) {
                "Cleared local data (deleted ${result.deletedFiles} files, ${result.deletedDirs} folders)."
            } else {
                "Cleared local data with ${result.errors} errors (deleted ${result.deletedFiles} files, ${result.deletedDirs} folders)."
            }
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
