package com.fersaiyan.cyanbridge.ui.recordings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.databinding.ItemSyncedMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class SyncedMediaItem(
    val id: Long,
    val contentUri: android.net.Uri,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val takenAtMs: Long,
)

class SyncedMediaAdapter(
    private val context: Context,
    private val onItemClick: (SyncedMediaItem) -> Unit,
) : ListAdapter<SyncedMediaItem, SyncedMediaAdapter.MediaViewHolder>(DIFF) {

    private val thumbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemSyncedMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        super.onViewRecycled(holder)
    }

    fun release() {
        thumbScope.cancel()
    }

    inner class MediaViewHolder(
        private val binding: ItemSyncedMediaBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        var thumbnailJob: Job? = null
        private var boundItemId: Long = -1L

        fun bind(item: SyncedMediaItem) {
            boundItemId = item.id

            binding.tvName.text = item.displayName
            binding.tvMeta.text = formatTakenTime(item.takenAtMs)
            binding.tvVideoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onItemClick(item) }

            thumbnailJob?.cancel()
            binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.ivThumbnail.setImageDrawable(null)

            val expectedId = item.id
            thumbnailJob = thumbScope.launch {
                val bitmap = loadThumbnail(item)
                withContext(Dispatchers.Main) {
                    if (boundItemId != expectedId) return@withContext

                    if (bitmap != null) {
                        binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                        binding.ivThumbnail.setImageBitmap(bitmap)
                    } else {
                        binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        binding.ivThumbnail.setImageResource(
                            if (item.isVideo) android.R.drawable.ic_media_play
                            else android.R.drawable.ic_menu_report_image
                        )
                    }
                }
            }
        }
    }

    private fun formatTakenTime(takenAtMs: Long): String {
        if (takenAtMs <= 0L) return ""
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(takenAtMs))
    }

    private fun loadThumbnail(item: SyncedMediaItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(item.contentUri, Size(420, 420), null)
            }
        }

        if (item.isVideo) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.contentUri)
                    return retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }

            @Suppress("DEPRECATION")
            runCatching {
                return MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                return MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }

            runCatching {
                context.contentResolver.openInputStream(item.contentUri)?.use { input ->
                    return BitmapFactory.decodeStream(input)
                }
            }
        }

        return null
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SyncedMediaItem>() {
            override fun areItemsTheSame(oldItem: SyncedMediaItem, newItem: SyncedMediaItem): Boolean {
                return oldItem.id == newItem.id && oldItem.isVideo == newItem.isVideo
            }

            override fun areContentsTheSame(oldItem: SyncedMediaItem, newItem: SyncedMediaItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
