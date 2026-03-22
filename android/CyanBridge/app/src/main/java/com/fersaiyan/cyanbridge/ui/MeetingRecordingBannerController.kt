package com.fersaiyan.cyanbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.audio.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService

/**
 * Chapter 8: Recording transparency.
 *
 * Reuses the Chapter 5 banner pattern across top-level destinations.
 */
class MeetingRecordingBannerController(
    private val context: Context,
    private val banner: View,
    private val bannerText: TextView,
    private val stopButton: View,
) {
    private var receiver: BroadcastReceiver? = null

    fun bind() {
        stopButton.setOnClickListener {
            MeetingCaptureService.stop(context)
        }
    }

    fun onStart() {
        registerReceiverIfNeeded()
        syncFromPrefs()
    }

    fun onStop() {
        unregisterReceiver()
    }

    private fun syncFromPrefs() {
        val state = MeetingCapturePrefs.getState(context)
        setRecordingUi(state.isRecording, state.source)
    }

    private fun setRecordingUi(isRecording: Boolean, source: CaptureSource?) {
        if (isRecording) {
            banner.visibility = View.VISIBLE
            val src = when (source) {
                CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                CaptureSource.PHONE_MIC -> "Phone mic"
                null -> "(detecting…)"
            }
            bannerText.text = "Recording active · $src"
            stopButton.isEnabled = true
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MeetingCaptureService.ACTION_STATE) return
                val isRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false)
                val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)?.let {
                    runCatching { CaptureSource.valueOf(it) }.getOrNull()
                }
                setRecordingUi(isRecording, source)
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver!!, IntentFilter(MeetingCaptureService.ACTION_STATE))
    }

    private fun unregisterReceiver() {
        val r = receiver ?: return
        receiver = null
        runCatching {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(r)
        }
    }
}
