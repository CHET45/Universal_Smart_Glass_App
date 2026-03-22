package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.databinding.ActivityAppBlacklistBinding
import kotlin.concurrent.thread

/**
 * UI to blacklist apps from Local Agent screen-content capture.
 *
 * Lists ALL installed apps/packages. Requires:
 *   <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
 */
class AppBlacklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppBlacklistBinding

    private lateinit var adapter: BlacklistAppAdapter

    private var allApps: List<BlacklistAppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppBlacklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BlacklistAppAdapter(
            initiallySelected = LocalAgentPrefs.getCaptureBlacklistPackages(this)
        )

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.switchHideSystemApps.isChecked = LocalAgentPrefs.isHideSystemAppsEnabled(this)
        binding.switchHideSystemApps.setOnCheckedChangeListener { _, isChecked ->
            LocalAgentPrefs.setHideSystemAppsEnabled(this, isChecked)
            refreshFilteredList()
        }

        binding.btnCancel.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener {
            val selected = adapter.getSelectedPackages()
            LocalAgentPrefs.setCaptureBlacklistPackages(this, selected)
            Toast.makeText(this, "Saved blacklist (${selected.size} apps)", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent())
            finish()
        }

        binding.editSearch.doAfterTextChanged {
            refreshFilteredList()
        }

        loadAppsAsync()
    }

    private fun loadAppsAsync() {
        binding.tvCount.text = "Loading…"

        thread {
            val pm = packageManager
            val list = pm.getInstalledApplications(0)

            val items = list
                .asSequence()
                .mapNotNull { ai ->
                    val pkg = ai.packageName?.trim().orEmpty()
                    if (pkg.isBlank()) return@mapNotNull null

                    val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { pkg }

                    val icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull()

                    val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    BlacklistAppItem(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        isSystemApp = isSystem,
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<BlacklistAppItem> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()

            runOnUiThread {
                allApps = items
                refreshFilteredList()
            }
        }
    }

    private fun refreshFilteredList() {
        val q = binding.editSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val hideSystem = binding.switchHideSystemApps.isChecked

        val filtered = allApps.filter {
            val okSystem = !(hideSystem && it.isSystemApp)
            val okQuery = q.isBlank() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            okSystem && okQuery
        }

        adapter.submitList(filtered)
        binding.tvCount.text = "${filtered.size} / ${allApps.size} apps"
    }
}
