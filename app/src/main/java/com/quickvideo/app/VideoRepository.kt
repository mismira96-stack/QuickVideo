package com.quickvideo.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class GalleryVideoItem(
    val id: Long,
    val uri: Uri?,
    val displayName: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

sealed class GalleryListItem {
    data class DateHeader(val dateLabel: String, val count: Int) : GalleryListItem()
    data class VideoEntry(val video: GalleryVideoItem) : GalleryListItem()
}

object VideoRepository {

    private val thumbCache = object : LruCache<String, Bitmap>(80) {}

    suspend fun loadVideosGroupedByDate(context: Context): List<GalleryListItem> = withContext(Dispatchers.IO) {
        val videos = queryDeviceVideos(context)
        groupByDate(videos)
    }

    fun queryDeviceVideos(context: Context): List<GalleryVideoItem> {
        val result = mutableListOf<GalleryVideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val addCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)
                if (size <= 0L) continue
                val contentUri = ContentUris.withAppendedId(collection, id)
                val rawDuration = cursor.getLong(durCol)
                if (rawDuration in 1L..199L) continue
                val duration = if (rawDuration >= 200L) rawDuration else 3000L
                val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                val modified = cursor.getLong(modCol)
                val added = cursor.getLong(addCol)
                val timestampSec = if (modified > 0L) modified else added
                val width = cursor.getInt(widthCol).let { if (it > 0) it else 1080 }
                val height = cursor.getInt(heightCol).let { if (it > 0) it else 1920 }

                result.add(
                    GalleryVideoItem(
                        id = id,
                        uri = contentUri,
                        displayName = name,
                        durationMs = duration,
                        dateAddedSec = timestampSec,
                        width = width,
                        height = height,
                        sizeBytes = size
                    )
                )
            }
        }
        return result
    }

    private fun probeDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun groupByDate(videos: List<GalleryVideoItem>, nowMillis: Long = System.currentTimeMillis()): List<GalleryListItem> {
        if (videos.isEmpty()) return emptyList()
        val items = mutableListOf<GalleryListItem>()
        val grouped = LinkedHashMap<String, MutableList<GalleryVideoItem>>()

        for (video in videos) {
            val label = formatDateSection(video.dateAddedSec * 1000L, nowMillis)
            grouped.getOrPut(label) { mutableListOf() }.add(video)
        }

        for ((dateLabel, group) in grouped) {
            items.add(GalleryListItem.DateHeader(dateLabel, group.size))
            for (v in group) {
                items.add(GalleryListItem.VideoEntry(v))
            }
        }
        return items
    }

    fun formatDateSection(timestampMs: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

        val sameYear = targetCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val sameDay = sameYear && targetCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "오늘"

        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = targetCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
            targetCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "어제"

        val pattern = if (sameYear) "M월 d일 (E)" else "yyyy년 M월 d일 (E)"
        return SimpleDateFormat(pattern, Locale.KOREAN).format(Date(timestampMs))
    }

    suspend fun loadThumbnail(context: Context, uri: Uri, widthPx: Int = 280, heightPx: Int = 280): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${uri}_$widthPx"
            thumbCache.get(key)?.let { return@withContext it }

            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(widthPx, heightPx), null)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            } ?: extractFrameAt(context, uri, 0L, widthPx, heightPx)

            if (bmp != null) {
                thumbCache.put(key, bmp)
            }
            bmp
        }

    fun extractFrameAt(
        context: Context,
        uri: Uri,
        timeMs: Long,
        targetWidth: Int = 160,
        targetHeight: Int = 160
    ): Bitmap? {
        val key = "frame_${uri}_${timeMs / 250}_${targetWidth}"
        thumbCache.get(key)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                )
            } else {
                retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (raw != null) {
                thumbCache.put(key, raw)
            }
            raw
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun inspectVideoClip(context: Context, uri: Uri): VideoClip? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)

            val durationMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 3500L).coerceAtLeast(1000L)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1080
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1920
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (w, h) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }
            val title = uri.lastPathSegment ?: "영상"
            VideoClip(
                uri = uri,
                title = title,
                sourceDurationMs = durationMs,
                trimStartMs = 0L,
                trimEndMs = durationMs,
                speed = 1.0f,
                width = w,
                height = h
            )
        } catch (_: Throwable) {
            VideoClip(
                uri = uri,
                title = uri.lastPathSegment ?: "영상",
                sourceDurationMs = 3500L,
                trimStartMs = 0L,
                trimEndMs = 3500L,
                speed = 1.0f,
                width = 1080,
                height = 1920
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
