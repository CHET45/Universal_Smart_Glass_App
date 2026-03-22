package com.fersaiyan.cyanbridge.ui.recordings

import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.databinding.ActivitySyncedMediaGalleryBinding
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncedMediaGalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncedMediaGalleryBinding
    private lateinit var adapter: SyncedMediaAdapter

    private val uiScope = MainScope()
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncedMediaGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvFolderHint.text = getString(
            R.string.synced_media_folder_hint,
            SyncedMediaFolder.relativePath
        )

        adapter = SyncedMediaAdapter(
            context = this,
            onItemClick = ::openMediaItem,
        )

        binding.recyclerSyncedMedia.layoutManager = GridLayoutManager(this, gridSpanCount())
        binding.recyclerSyncedMedia.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        loadSyncedMedia()
    }

    override fun onStop() {
        super.onStop()
        loadJob?.cancel()
        loadJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        adapter.release()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSyncedMedia() {
        loadJob?.cancel()
        binding.progressLoading.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE

        loadJob = uiScope.launch {
            val mediaItems = withContext(Dispatchers.IO) {
                querySyncedMediaItems()
            }

            adapter.submitList(mediaItems)
            binding.progressLoading.visibility = View.GONE
            binding.emptyState.visibility = if (mediaItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun querySyncedMediaItems(): List<SyncedMediaItem> {
        val items = mutableListOf<SyncedMediaItem>()

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
            selection = buildString {
                append("(")
                append(MediaStore.Files.FileColumns.MEDIA_TYPE)
                append("=? OR ")
                append(MediaStore.Files.FileColumns.MEDIA_TYPE)
                append("=?) AND (")
                append(MediaStore.MediaColumns.RELATIVE_PATH)
                append("=? OR ")
                append(MediaStore.MediaColumns.RELATIVE_PATH)
                append("=? OR ")
                append(MediaStore.MediaColumns.RELATIVE_PATH)
                append(" LIKE ?)")
            }
            selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                SyncedMediaFolder.relativePath,
                SyncedMediaFolder.relativePathWithTrailingSlash,
                SyncedMediaFolder.relativePathLikePattern(),
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                projection += MediaStore.MediaColumns.DATA
            }
            selection = buildString {
                append("(")
                append(MediaStore.Files.FileColumns.MEDIA_TYPE)
                append("=? OR ")
                append(MediaStore.Files.FileColumns.MEDIA_TYPE)
                append("=?) AND ")
                append(MediaStore.MediaColumns.DATA)
                append(" LIKE ?")
            }
            selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                SyncedMediaFolder.legacyAbsolutePathLikePattern(),
            )
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val uri = MediaStore.Files.getContentUri("external")
        contentResolver.query(uri, projection.toTypedArray(), selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateTakenIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val dateAddedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val typeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

                while (cursor.moveToNext()) {
                    if (idIdx < 0 || typeIdx < 0) continue

                    val mediaType = cursor.getInt(typeIdx)
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val isImage = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    if (!isVideo && !isImage) continue

                    val id = cursor.getLong(idIdx)
                    val contentUri = if (isVideo) {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }

                    val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        cursor.getString(nameIdx)
                    } else {
                        "media_$id"
                    }

                    val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                        cursor.getString(mimeIdx)
                    } else {
                        if (isVideo) "video/mp4" else "image/jpeg"
                    }

                    val dateTakenMs = if (dateTakenIdx >= 0 && !cursor.isNull(dateTakenIdx)) {
                        cursor.getLong(dateTakenIdx)
                    } else {
                        0L
                    }

                    val dateAddedMs = if (dateAddedIdx >= 0 && !cursor.isNull(dateAddedIdx)) {
                        cursor.getLong(dateAddedIdx) * 1000L
                    } else {
                        0L
                    }

                    items += SyncedMediaItem(
                        id = id,
                        contentUri = contentUri,
                        displayName = name,
                        mimeType = mime,
                        isVideo = isVideo,
                        takenAtMs = if (dateTakenMs > 0L) dateTakenMs else dateAddedMs,
                    )
                }
            }

        return items
    }

    private fun openMediaItem(item: SyncedMediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.contentUri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, getString(R.string.synced_media_open_failed), Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 900 -> 4
            widthDp >= 600 -> 3
            else -> 2
        }
    }
}
