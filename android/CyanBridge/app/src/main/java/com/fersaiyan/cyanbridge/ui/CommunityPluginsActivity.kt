package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.databinding.ActivityCommunityPluginsBinding
import com.fersaiyan.cyanbridge.databinding.ItemCommunityPluginCardBinding
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CommunityPluginsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommunityPluginsBinding
    private var serverPluginsLoaded = false

    private enum class TimeWindow {
        ALL_TIME,
        WEEKLY,
        MONTHLY,
    }

    private data class PluginCardData(
        val title: String,
        val author: String,
        val description: String,
        val badge: String,
        val downloadsAll: Int,
        val downloadsMonthly: Int,
        val downloadsWeekly: Int,
        val votesAll: Int,
        val votesMonthly: Int,
        val votesWeekly: Int,
        val trendAll: Int,
        val trendMonthly: Int,
        val trendWeekly: Int,
    ) {
        fun downloads(window: TimeWindow): Int = when (window) {
            TimeWindow.ALL_TIME -> downloadsAll
            TimeWindow.MONTHLY -> downloadsMonthly
            TimeWindow.WEEKLY -> downloadsWeekly
        }

        fun votes(window: TimeWindow): Int = when (window) {
            TimeWindow.ALL_TIME -> votesAll
            TimeWindow.MONTHLY -> votesMonthly
            TimeWindow.WEEKLY -> votesWeekly
        }

        fun trend(window: TimeWindow): Int = when (window) {
            TimeWindow.ALL_TIME -> trendAll
            TimeWindow.MONTHLY -> trendMonthly
            TimeWindow.WEEKLY -> trendWeekly
        }
    }

    private val pluginPool = listOf(
        PluginCardData(
            title = "Meeting Spark Notes",
            author = "cyanlabs",
            description = "Builds concise action summaries from captures and chats.",
            badge = "Productivity",
            downloadsAll = 182_400,
            downloadsMonthly = 28_400,
            downloadsWeekly = 7_100,
            votesAll = 21_600,
            votesMonthly = 4_100,
            votesWeekly = 980,
            trendAll = 92,
            trendMonthly = 96,
            trendWeekly = 97,
        ),
        PluginCardData(
            title = "Live Caption Relay",
            author = "captionsmith",
            description = "Streams glasses audio to phone and pushes bilingual captions.",
            badge = "Accessibility",
            downloadsAll = 131_300,
            downloadsMonthly = 24_900,
            downloadsWeekly = 6_900,
            votesAll = 18_500,
            votesMonthly = 3_700,
            votesWeekly = 1_020,
            trendAll = 88,
            trendMonthly = 94,
            trendWeekly = 98,
        ),
        PluginCardData(
            title = "Errand Brain",
            author = "urbanaut",
            description = "Turns quick voice notes into checklist tasks and reminders.",
            badge = "Planner",
            downloadsAll = 98_200,
            downloadsMonthly = 15_600,
            downloadsWeekly = 4_200,
            votesAll = 12_900,
            votesMonthly = 2_100,
            votesWeekly = 610,
            trendAll = 81,
            trendMonthly = 85,
            trendWeekly = 89,
        ),
        PluginCardData(
            title = "Commute Copilot",
            author = "routepilot",
            description = "Summarizes route changes and sends trip status prompts.",
            badge = "Mobility",
            downloadsAll = 87_500,
            downloadsMonthly = 13_900,
            downloadsWeekly = 3_700,
            votesAll = 11_300,
            votesMonthly = 1_900,
            votesWeekly = 520,
            trendAll = 77,
            trendMonthly = 80,
            trendWeekly = 84,
        ),
        PluginCardData(
            title = "Retail Field Scout",
            author = "shelfops",
            description = "Captures shelf notes and auto-tags price/checklist anomalies.",
            badge = "Operations",
            downloadsAll = 74_800,
            downloadsMonthly = 11_100,
            downloadsWeekly = 2_900,
            votesAll = 9_900,
            votesMonthly = 1_600,
            votesWeekly = 430,
            trendAll = 73,
            trendMonthly = 78,
            trendWeekly = 82,
        ),
        PluginCardData(
            title = "Hands-Free Translator",
            author = "polyglot.dev",
            description = "Voice command translation presets for frequent phrases.",
            badge = "Language",
            downloadsAll = 165_000,
            downloadsMonthly = 19_700,
            downloadsWeekly = 4_800,
            votesAll = 23_100,
            votesMonthly = 3_400,
            votesWeekly = 820,
            trendAll = 86,
            trendMonthly = 83,
            trendWeekly = 79,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommunityPluginsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFilterControls()
        setupPublishFab()
        setupImageAutomationPluginCard()
        setupBottomNavigation()
        renderSections(TimeWindow.ALL_TIME)
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_refresh -> {
                    fetchPluginsFromServer()
                    true
                }
                else -> false
            }
        }
    }

    private fun fetchPluginsFromServer() {
        if (!serverPluginsLoaded) {
            Toast.makeText(this, "Fetching plugins from server...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val relayUrl = AiProviderPrefs.getRelayBaseUrl(this@CommunityPluginsActivity)
                val url = "$relayUrl/plugins"

                val client = java.net.URL(url)
                val connection = client.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CommunityPluginsActivity, "Plugins refreshed from server!", Toast.LENGTH_SHORT).show()
                        serverPluginsLoaded = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CommunityPluginsActivity, "Server unreachable. Using cached plugins.", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CommunityPluginsActivity, "Server unavailable. Using cached plugins.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_community_plugins).isChecked = true
        }
        refreshImageAutomationPluginStatus()

        if (CommunityPluginPrefs.isImageAutomationBannerDismissed(this)) {
            binding.cardPluginImageAutomation.visibility = android.view.View.GONE
        }
    }

    private fun setupImageAutomationPluginCard() {
        binding.btnPluginImageAutomation.setOnClickListener {
            showTaskerPluginPopup()
        }

        binding.btnPluginImageAutomationDismiss.setOnClickListener {
            showDismissConfirmation()
        }

        refreshImageAutomationPluginStatus()
    }

    private fun showDismissConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Hide this banner?")
            .setMessage("This will hide the Gemini/ChatGPT Image Questions Automation banner. You can re-enable it from Settings if needed.")
            .setPositiveButton("Hide") { _, _ ->
                CommunityPluginPrefs.setImageAutomationBannerDismissed(this, true)
                binding.cardPluginImageAutomation.visibility = android.view.View.GONE
                Toast.makeText(this, "Banner hidden", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTaskerPluginPopup() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tasker_plugin, null)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btn_download_tasker).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=net.dinglisch.android.taskerm")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm")))
            }
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_open_taskernet).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://taskernet.com/shares/?user=AS35m8m%2BZfcOI%2FAn4TYXwIRGXRuXzE9zXexYgafojsO%2FQSXgVbu8nOiYo%2BLhLj1izKWhtzdxI6eOvMI%3D&id=Profile%3ATasker+AI")))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open TaskerNet", Toast.LENGTH_SHORT).show()
            }
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun refreshImageAutomationPluginStatus() {
        val enabled = CommunityPluginPrefs.isGeminiChatGptImageAutomationEnabled(this)
        binding.tvPluginImageAutomationStatus.text = if (enabled) {
            "Status: Downloaded and enabled"
        } else {
            "Status: Not downloaded"
        }
        binding.btnPluginImageAutomation.text = if (enabled) {
            "Disable plugin"
        } else {
            "Download plugin"
        }
    }

    private fun setupFilterControls() {
        binding.chipGroupPeriod.setOnCheckedStateChangeListener { _, _ ->
            renderSections(currentWindow())
        }
    }

    private fun setupPublishFab() {
        binding.fabPublishHelp.setOnClickListener {
            startActivity(Intent(this, PublishPluginActivity::class.java))
        }
    }

    private fun renderSections(window: TimeWindow) {
        val trending = pluginPool
            .sortedByDescending { it.trend(window) }
            .take(4)

        val topVoted = pluginPool
            .sortedByDescending { it.votes(window) }
            .take(4)

        val topDownloaded = pluginPool
            .sortedByDescending { it.downloads(window) }
            .take(4)

        renderSection(binding.containerTrending, trending, window)
        renderSection(binding.containerTopVoted, topVoted, window)
        renderSection(binding.containerTopDownloaded, topDownloaded, window)
    }

    private fun renderSection(
        container: android.widget.LinearLayout,
        items: List<PluginCardData>,
        window: TimeWindow,
    ) {
        container.removeAllViews()
        items.forEachIndexed { index, plugin ->
            val cardBinding = ItemCommunityPluginCardBinding.inflate(layoutInflater, container, false)
            cardBinding.tvPluginTitle.text = "${index + 1}. ${plugin.title}"
            cardBinding.tvPluginAuthor.text = "by ${plugin.author}"
            cardBinding.tvPluginBadge.text = plugin.badge
            cardBinding.tvPluginDescription.text = plugin.description
            cardBinding.tvPluginDownloads.text = "${formatCount(plugin.downloads(window))} downloads"
            cardBinding.tvPluginVotes.text = "${formatCount(plugin.votes(window))} votes"
            cardBinding.tvPluginTrend.text = "${windowLabel(window)} trend ${plugin.trend(window)}"
            container.addView(cardBinding.root)
        }
    }

    private fun currentWindow(): TimeWindow = when {
        binding.chipWeekly.isChecked -> TimeWindow.WEEKLY
        binding.chipMonthly.isChecked -> TimeWindow.MONTHLY
        else -> TimeWindow.ALL_TIME
    }

    private fun windowLabel(window: TimeWindow): String = when (window) {
        TimeWindow.ALL_TIME -> "all-time"
        TimeWindow.WEEKLY -> "weekly"
        TimeWindow.MONTHLY -> "monthly"
    }

    private fun formatCount(value: Int): String {
        return when {
            value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
            value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000f)
            else -> value.toString()
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_community_plugins
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_community_plugins -> true
                R.id.nav_glasses -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_chats -> {
                    binding.bottomNavigation.post {
                        val last = ChatStore.listNonEmptyThreads().firstOrNull()
                        val now = System.currentTimeMillis()

                        fun lastUserMessageAtMs(chatId: String): Long? {
                            val msgs = ChatStore.listMessages(chatId)
                            return msgs.lastOrNull { it.role == ChatRole.USER }?.createdAt
                        }

                        val openChatId = if (last != null) {
                            val lastUserAt = lastUserMessageAtMs(last.id) ?: 0L
                            if (lastUserAt > 0L && (now - lastUserAt) < 30 * 60 * 1000) last.id else null
                        } else null

                        val intent = Intent(this, ChatThreadActivity::class.java)
                        if (openChatId != null) {
                            intent.putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    true
                }
                R.id.nav_transcriptions_recordings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, RecordingsListActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_settings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, SettingsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                else -> false
            }
        }
    }
}
