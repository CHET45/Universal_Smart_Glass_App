package com.fersaiyan.cyanbridge.ui.recordings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.databinding.ItemRecordingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingListAdapter(
    private val onPlayClick: (CaptureSession) -> Unit,
    private val onTranscribeClick: (CaptureSession) -> Unit,
    private val onViewTranscriptionClick: (CaptureSession) -> Unit,
) : RecyclerView.Adapter<RecordingListAdapter.VH>() {

    private val items = mutableListOf<CaptureSession>()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private var playingId: Long? = null
    private var transcribingId: Long? = null

    fun submitList(list: List<CaptureSession>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setPlaying(id: Long?) {
        playingId = id
        notifyDataSetChanged()
    }

    fun setTranscribing(id: Long?) {
        transcribingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onPlayClick, onTranscribeClick, onViewTranscriptionClick, df)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.bind(
            session = s,
            isPlaying = s.id == playingId,
            isTranscribing = s.id == transcribingId,
        )
    }

    override fun getItemCount(): Int = items.size

    class VH(
        private val binding: ItemRecordingBinding,
        private val onPlayClick: (CaptureSession) -> Unit,
        private val onTranscribeClick: (CaptureSession) -> Unit,
        private val onViewTranscriptionClick: (CaptureSession) -> Unit,
        private val df: SimpleDateFormat,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: CaptureSession, isPlaying: Boolean, isTranscribing: Boolean) {
            val titlePrefix = if (session.captureSource.equals("GLASSES_SYNC_P2P", ignoreCase = true)) {
                "Glasses audio"
            } else {
                "Meeting"
            }
            binding.tvTitle.text = "$titlePrefix · ${df.format(Date(session.startedAt))}"

            val metaParts = mutableListOf<String>()
            metaParts.add("${session.durationSec}s")
            session.captureSource.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            session.deviceClass.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            binding.tvMeta.text = metaParts.joinToString(" · ")

            val playBtn: ImageButton = binding.btnPlay
            playBtn.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            playBtn.setOnClickListener { onPlayClick(session) }

            binding.btnTranscribe.isEnabled = !isTranscribing
            binding.btnViewTranscription.isEnabled = !isTranscribing

            binding.btnTranscribe.text = if (isTranscribing) "Transcribing…" else "Transcribe"

            binding.btnTranscribe.setOnClickListener { onTranscribeClick(session) }
            binding.btnViewTranscription.setOnClickListener { onViewTranscriptionClick(session) }

            binding.progressTranscribe.visibility = if (isTranscribing) android.view.View.VISIBLE else android.view.View.GONE
            binding.progressTranscribe.isIndeterminate = true
        }
    }
}
