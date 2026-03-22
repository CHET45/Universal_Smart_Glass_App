package com.fersaiyan.cyanbridge.ui.localagent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.databinding.ActivityPendingActionsBinding
import com.fersaiyan.cyanbridge.localagent.LocalAgentAccessibilityBridge
import com.fersaiyan.cyanbridge.localagent.LocalAgentActionParser
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PendingActionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPendingActionsBinding
    private var current: PendingAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPendingActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadPending() }
        binding.btnApprove.setOnClickListener { approveCurrent() }
        binding.btnReject.setOnClickListener { rejectCurrent() }

        loadPending()
    }

    private fun loadPending() {
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()
            val pending = withContext(Dispatchers.IO) {
                dao.getActionsByStatus("pending")
            }

            binding.tvCount.text = "Pending: ${pending.size}"
            current = pending.firstOrNull()

            val rendered = if (current == null) {
                "(no pending actions)"
            } else {
                renderPendingAction(current!!)
            }
            binding.editAction.setText(rendered)

            val has = current != null
            binding.btnApprove.isEnabled = has
            binding.btnReject.isEnabled = has
        }
    }

    private fun renderPendingAction(p: PendingAction): String {
        val tsText = if (p.ts > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(p.ts))
        } else "(no-ts)"

        val prettyJson = runCatching {
            val trimmed = p.actionJson.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).toString(2)
            } else {
                trimmed
            }
        }.getOrDefault(p.actionJson)

        return buildString {
            appendLine("id=${p.id}")
            appendLine("ts=$tsText")
            appendLine("source=${p.source}")
            appendLine("status=${p.status}")
            if (!p.result.isNullOrBlank()) appendLine("result=${p.result}")
            appendLine("---")
            appendLine(prettyJson)
        }.trimEnd()
    }

    private fun rejectCurrent() {
        val p = current ?: return
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()
            withContext(Dispatchers.IO) {
                p.status = "rejected"
                p.result = "rejected_by_user"
                dao.update(p)
            }
            Toast.makeText(this@PendingActionsActivity, "Rejected action #${p.id}", Toast.LENGTH_SHORT).show()
            loadPending()
        }
    }

    private fun approveCurrent() {
        val p = current ?: return
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()

            // Mark approved
            withContext(Dispatchers.IO) {
                p.status = "approved"
                p.result = null
                dao.update(p)
            }

            val actions = LocalAgentActionParser.parseList(p.actionJson)
            if (actions.isEmpty()) {
                withContext(Dispatchers.IO) {
                    p.status = "executed"
                    p.result = "parse_failed"
                    dao.update(p)
                }
                Toast.makeText(this@PendingActionsActivity, "Could not parse action JSON", Toast.LENGTH_SHORT).show()
                loadPending()
                return@launch
            }

            val results = mutableListOf<String>()
            for (a in actions) {
                val ok = runCatching {
                    val intentOk = LocalAgentActionManager.executeNow(this@PendingActionsActivity, a)
                    if (intentOk) true else LocalAgentAccessibilityBridge.perform(a)
                }.getOrDefault(false)

                results += "${a.javaClass.simpleName}: ${if (ok) "ok" else "failed"}"
            }

            withContext(Dispatchers.IO) {
                p.status = "executed"
                p.result = results.joinToString("; ")
                dao.update(p)
            }

            Toast.makeText(this@PendingActionsActivity, "Executed action #${p.id}", Toast.LENGTH_SHORT).show()
            loadPending()
        }
    }
}
