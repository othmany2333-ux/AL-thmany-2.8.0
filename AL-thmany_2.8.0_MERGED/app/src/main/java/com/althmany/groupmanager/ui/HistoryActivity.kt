package com.althmany.groupmanager.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.R
import com.althmany.groupmanager.databinding.ActivityHistoryBinding
import com.althmany.groupmanager.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val app: GroupManagerApp get() = application as GroupManagerApp
    private val historyAdapter = HistoryAdapter(::confirmDeleteSession)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history) {
                confirmClearHistory()
                true
            } else false
        }
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { app.repository.history() }
            binding.loadingIndicator.visibility = View.GONE
            historyAdapter.submitList(history)
            binding.emptyHistoryText.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteSession(session: SessionSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_history_title)
            .setMessage(R.string.delete_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.repository.deleteHistoricalSession(session.id)
                    }
                    loadHistory()
                }
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.repository.clearHistory() }
                    loadHistory()
                }
            }
            .show()
    }
}
