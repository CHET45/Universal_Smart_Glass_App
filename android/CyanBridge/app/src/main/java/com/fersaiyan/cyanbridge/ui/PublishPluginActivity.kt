package com.fersaiyan.cyanbridge.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.databinding.ActivityPublishPluginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PublishPluginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPublishPluginBinding

    private val categories = listOf(
        "Productivity",
        "Accessibility",
        "Planner",
        "Mobility",
        "Operations",
        "Language",
        "Other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPublishPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupCategoryDropdown()
        setupPublishButton()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupCategoryDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        binding.dropdownCategory.setAdapter(adapter)
    }

    private fun setupPublishButton() {
        binding.btnPublish.setOnClickListener {
            submitPlugin()
        }
    }

    private fun submitPlugin() {
        val title = binding.editPluginTitle.text?.toString()?.trim() ?: ""
        val author = binding.editPluginAuthor.text?.toString()?.trim() ?: ""
        val description = binding.editPluginDescription.text?.toString()?.trim() ?: ""
        val category = binding.dropdownCategory.text?.toString()?.trim() ?: ""
        val taskernetLink = binding.editTaskernetLink.text?.toString()?.trim() ?: ""

        if (title.isEmpty()) {
            binding.tilPluginTitle.error = "Title is required"
            return
        }
        binding.tilPluginTitle.error = null

        if (author.isEmpty()) {
            binding.tilPluginAuthor.error = "Author name is required"
            return
        }
        binding.tilPluginAuthor.error = null

        if (description.isEmpty()) {
            binding.tilPluginDescription.error = "Description is required"
            return
        }
        binding.tilPluginDescription.error = null

        if (taskernetLink.isEmpty()) {
            binding.tilTaskernetLink.error = "TaskerNet link is required"
            return
        }
        binding.tilTaskernetLink.error = null

        binding.btnPublish.isEnabled = false
        binding.btnPublish.text = "Submitting..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pluginData = mapOf(
                    "title" to title,
                    "author" to author,
                    "description" to description,
                    "category" to category,
                    "taskernet_link" to taskernetLink
                )

                val result = submitPluginToServer(pluginData)

                withContext(Dispatchers.Main) {
                    if (result) {
                        Toast.makeText(
                            this@PublishPluginActivity,
                            "Plugin submitted for review! It will appear in the list once approved.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    } else {
                        binding.btnPublish.isEnabled = true
                        binding.btnPublish.text = "Submit Plugin"
                        Toast.makeText(
                            this@PublishPluginActivity,
                            "Failed to submit plugin. The Termux server may be unreachable. Please try again later.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnPublish.isEnabled = true
                    binding.btnPublish.text = "Submit Plugin"
                    Toast.makeText(
                        this@PublishPluginActivity,
                        "Server unavailable. Please try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun submitPluginToServer(pluginData: Map<String, String>): Boolean {
        return try {
            val relayUrl = AiProviderPrefs.getRelayBaseUrl(this@PublishPluginActivity)
            val url = "$relayUrl/plugins/submit"

            val client = java.net.URL(url)
            val connection = client.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonBody = buildString {
                append("{")
                pluginData.entries.forEachIndexed { index, entry ->
                    append("\"${entry.key}\": \"${entry.value.replace("\"", "\\\"")}\"")
                    if (index < pluginData.size - 1) append(",")
                }
                append("}")
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
