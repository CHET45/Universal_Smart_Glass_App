package com.fersaiyan.cyanbridge.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.app.DatePickerDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.databinding.ActivityChatListBinding
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewThreadStore
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearancePrefs
import com.fersaiyan.cyanbridge.ui.chat.ChatThreadAdapter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatListBinding
    private lateinit var adapter: ChatThreadAdapter

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ChatAppearancePrefs.setWallpaperUri(this, uri.toString())
            Toast.makeText(this, "Chat wallpaper updated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChatThreadAdapter(
            onClick = { thread ->
                startActivity(buildOpenChatIntent(thread.id))
            },
            onDelete = { thread ->
                deleteChat(thread)
            }
        )

        binding.recyclerThreads.layoutManager = LinearLayoutManager(this)
        binding.recyclerThreads.adapter = adapter

        binding.fabNewChat.setOnClickListener {
            if (isLocalModelsMissingSelection()) {
                promptLocalModelSetup()
            } else {
                showNewChatTypePicker()
            }
        }
        binding.btnChatAppearance.setOnClickListener {
            showChatAppearanceMenu()
        }

        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        // Ensure correct nav highlight when returning via CLEAR_TOP/SINGLE_TOP.
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_chats).isChecked = true
        }
        refreshList()
    }

    private fun deleteChat(thread: com.fersaiyan.cyanbridge.chat.ChatThread) {
        ChatStore.deleteThread(thread.id)
        DailyFactsReviewThreadStore.remove(this, thread.id)
        Toast.makeText(this, getString(R.string.chat_deleted), Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun buildOpenChatIntent(chatId: String): Intent {
        return Intent(this, ChatThreadActivity::class.java).apply {
            putExtra(ChatThreadActivity.EXTRA_CHAT_ID, chatId)

            val cfg = DailyFactsReviewThreadStore.load(this@ChatListActivity, chatId)
            if (cfg != null) {
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, cfg.date)
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, cfg.lookbackDays)
            }
        }
    }

    private fun refreshList() {
        val threads = ChatStore.listNonEmptyThreads()
        adapter.submitList(threads)
        binding.emptyState.visibility = if (threads.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerThreads.visibility = if (threads.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isLocalModelsMissingSelection(): Boolean {
        if (AiProviderPrefs.getProvider(this) != AiProviderType.LOCAL_MODELS) return false
        return LocalModelStorageRepository.resolveSelectedModel(this) == null
    }

    private fun promptLocalModelSetup() {
        AlertDialog.Builder(this)
            .setTitle("Install a local model")
            .setMessage("Local Models is selected, but no local model is installed. Configure a model to start a new chat.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Configure") { _, _ ->
                startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            }
            .show()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    binding.bottomNavigation.post {
                        val last = ChatStore.listNonEmptyThreads().firstOrNull()
                        val now = System.currentTimeMillis()

                        fun lastUserMessageAtMs(chatId: String): Long? {
                            val msgs = ChatStore.listMessages(chatId)
                            return msgs.lastOrNull { it.role == com.fersaiyan.cyanbridge.chat.ChatRole.USER }?.createdAt
                        }

                        val openChatId = if (last != null) {
                            val lastUserAt = lastUserMessageAtMs(last.id) ?: 0L
                            if (lastUserAt > 0L && (now - lastUserAt) < 30 * 60 * 1000) last.id else null
                        } else null

                        val intent = Intent(this, ChatThreadActivity::class.java)
                        if (openChatId != null) {
                            val cfg = DailyFactsReviewThreadStore.load(this@ChatListActivity, openChatId)
                            intent.putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
                            if (cfg != null) {
                                intent.putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                                intent.putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, cfg.date)
                                intent.putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, cfg.lookbackDays)
                            }
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    true
                }
                R.id.nav_glasses -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                R.id.nav_transcriptions_recordings -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java).apply {
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
                R.id.nav_community_plugins -> {
                    binding.bottomNavigation.post {
                        startActivity(Intent(this, CommunityPluginsActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showNewChatTypePicker() {
        val items = arrayOf(
            "Normal chat",
            "Daily review chat",
        )

        AlertDialog.Builder(this)
            .setTitle("Start a new chat")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startNormalChat()
                    1 -> showDailyReviewDateChooser()
                }
            }
            .show()
    }

    private fun startNormalChat() {
        startActivity(Intent(this, ChatThreadActivity::class.java))
    }

    private fun showDailyReviewDateChooser() {
        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(this).coerceIn(1, 365)
        val maxQuick = retentionDays.coerceAtMost(7)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dateOptions = ArrayList<String>(maxQuick)
        val labels = ArrayList<String>(maxQuick + 1)
        for (i in 0 until maxQuick) {
            val dayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
            val date = fmt.format(dayCal.time)
            dateOptions += date
            val human = when (i) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> "${i} days ago"
            }
            labels += "$human ($date)"
        }

        val customLabel = "Pick another date..."
        val allLabels = (labels + customLabel).toTypedArray()
        val todayDate = fmt.format(cal.time)

        AlertDialog.Builder(this)
            .setTitle("Daily review date")
            .setItems(allLabels) { _, which ->
                if (which in dateOptions.indices) {
                    startDailyReviewChat(dateOptions[which], retentionDays)
                } else {
                    showDailyReviewDatePicker(retentionDays)
                }
            }
            .setPositiveButton("Open calendar") { _, _ ->
                showDailyReviewDatePicker(retentionDays)
            }
            .setNeutralButton("Today") { _, _ ->
                startDailyReviewChat(todayDate, retentionDays)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDailyReviewDatePicker(retentionDays: Int) {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val min = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(retentionDays - 1)) }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val date = fmt.format(Date(picked.timeInMillis))
                startDailyReviewChat(date, retentionDays)
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = min.timeInMillis
            datePicker.maxDate = now.timeInMillis
        }.show()
    }

    private fun startDailyReviewChat(date: String, lookbackDays: Int) {
        startActivity(
            Intent(this, ChatThreadActivity::class.java)
                .putExtra(ChatThreadActivity.EXTRA_CREATE_THREAD_TITLE, "Daily review ($date)")
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, date)
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, lookbackDays),
        )
    }

    private fun showChatAppearanceMenu() {
        val items = arrayOf(
            "Change user bubble color",
            "Change assistant bubble color",
            "Choose wallpaper from gallery",
            "Remove wallpaper",
            "Reset chat appearance",
        )

        AlertDialog.Builder(this)
            .setTitle("Chat appearance")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showColorPicker(
                        title = "User bubble color",
                        current = ChatAppearancePrefs.getUserBubbleColor(this),
                    ) { selected ->
                        ChatAppearancePrefs.setUserBubbleColor(this, selected)
                    }

                    1 -> showColorPicker(
                        title = "Assistant bubble color",
                        current = ChatAppearancePrefs.getAssistantBubbleColor(this),
                    ) { selected ->
                        ChatAppearancePrefs.setAssistantBubbleColor(this, selected)
                    }

                    2 -> pickWallpaperLauncher.launch(arrayOf("image/*"))
                    3 -> {
                        ChatAppearancePrefs.clearWallpaper(this)
                        Toast.makeText(this, "Wallpaper removed", Toast.LENGTH_SHORT).show()
                    }

                    4 -> {
                        ChatAppearancePrefs.reset(this)
                        Toast.makeText(this, "Chat appearance reset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val options = listOf(
            "Cyan" to 0xFF00E5FF.toInt(),
            "Ocean" to 0xFF1F8AFA.toInt(),
            "Forest" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFFFB300.toInt(),
            "Coral" to 0xFFFF6F61.toInt(),
            "Slate" to 0xFF455A64.toInt(),
            "Rose" to 0xFFE91E63.toInt(),
            "Purple" to 0xFF7E57C2.toInt(),
        )

        val labels = options.map { (name, color) ->
            if (color == current) "$name (Current)" else name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(options[which].second)
            }
            .show()
    }
}
