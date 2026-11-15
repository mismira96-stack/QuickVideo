package com.quickvideo.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
private class MuteAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        for (i in 0 until remaining) {
            out.put(0.toByte())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

object VideoExporter {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(savedUri: Uri, fileName: String)
        fun onError(message: String)
    }

    /**
     * Computes even-numbered output (width, height) that properly preserves portrait (세로 9:16)
     * as well as landscape (가로 16:9) and square (1:1) videos.
     */
    fun computeOutputDimensions(
        firstClip: VideoClip?,
        resolution: ExportResolution,
        aspectRatio: AspectRatioOption
    ): Pair<Int, Int> {
        val baseW = (firstClip?.effectiveWidth ?: 1080).coerceAtLeast(16)
        val baseH = (firstClip?.effectiveHeight ?: 1920).coerceAtLeast(16)
        val targetRatio = aspectRatio.ratio ?: (baseW.toFloat() / baseH.toFloat())
        val shortSide = resolution.shortSidePx ?: minOf(baseW, baseH)

        val (rawW, rawH) = if (targetRatio < 1.0f) {
            // Portrait (세로 영상): width is the short side (e.g., 1080 x 1920)
            shortSide to (shortSide / targetRatio).roundToInt()
        } else {
            // Landscape or Square (가로/정방형 영상): height is the short side (e.g., 1920 x 1080)
            (shortSide * targetRatio).roundToInt() to shortSide
        }

        // H.264 hardware encoders require even dimensions
        val evenW = ((rawW + 1) / 2) * 2
        val evenH = ((rawH + 1) / 2) * 2
        return evenW.coerceAtLeast(16) to evenH.coerceAtLeast(16)
    }

    @OptIn(UnstableApi::class)
    fun exportProject(
        context: Context,
        clips: List<VideoClip>,
        resolution: ExportResolution,
        fps: ExportFps,
        aspectRatio: AspectRatioOption,
        callback: Callback
    ): () -> Unit {
        val mainHandler = Handler(Looper.getMainLooper())
        val tempFile = File(context.cacheDir, "quickvideo_export_${System.currentTimeMillis()}.mp4")
        if (tempFile.exists()) tempFile.delete()

        val allMuted = clips.all { it.isMuted }
        val (outWidth, outHeight) = computeOutputDimensions(clips.firstOrNull(), resolution, aspectRatio)

        val editedItems = clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(requireNotNull(clip.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()

            val audioProcessors = mutableListOf<AudioProcessor>()
            val videoEffects = mutableListOf<Effect>()

            if (clip.isMuted && !allMuted) {
                audioProcessors.add(MuteAudioProcessor())
            }

            if (abs(clip.speed - 1.0f) > 0.01f) {
                if (!allMuted) {
                    val sonic = SonicAudioProcessor().apply {
                        setSpeed(clip.speed)
                        setPitch(1.0f)
                    }
                    audioProcessors.add(sonic)
                }
                videoEffects.add(SpeedChangeEffect(clip.speed))
            }

            val hasRotation = abs(clip.rotationDegrees) > 0.1f
            val hasScale = abs(clip.scaleFactor - 1.0f) > 0.01f
            if (hasRotation || hasScale || clip.isMirrored) {
                val sx = (if (clip.isMirrored) -1f else 1f) * clip.scaleFactor
                val sy = clip.scaleFactor
                // ScaleAndRotateTransformation rotates counter-clockwise; convert clockwise degrees
                val ccwDegrees = ((-clip.rotationDegrees % 360f) + 360f) % 360f
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(sx, sy)
                        .setRotationDegrees(ccwDegrees)
                        .build()
                )
            }

            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    outWidth,
                    outHeight,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
            )

            videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(fps.fps.toFloat()))

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(allMuted)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        val sequence = EditedMediaItemSequence(editedItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        var isCancelled = false
        val progressHolder = ProgressHolder()
        lateinit var transformer: Transformer

        val progressRunnable = object : Runnable {
            override fun run() {
                if (isCancelled) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    callback.onProgress(progressHolder.progress.coerceIn(0, 99))
                }
                mainHandler.postDelayed(this, 150L)
            }
        }

        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.removeCallbacks(progressRunnable)
                    if (isCancelled) return
                    callback.onProgress(100)
                    val saved = saveToMediaStore(context, tempFile)
                    tempFile.delete()
                    if (saved != null) {
                        callback.onSuccess(saved.first, saved.second)
                    } else {
                        callback.onError("갤러리 저장 중 오류가 발생했습니다.")
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacks(progressRunnable)
                    tempFile.delete()
                    if (isCancelled) return
                    callback.onError(
                        exportException.localizedMessage ?: "동영상 인코딩 중 오류가 발생했습니다."
                    )
                }
            })
            .build()

        transformer.start(composition, tempFile.absolutePath)
        mainHandler.postDelayed(progressRunnable, 150L)

        return {
            isCancelled = true
            mainHandler.removeCallbacks(progressRunnable)
            try {
                transformer.cancel()
            } catch (_: Throwable) {
            }
            tempFile.delete()
        }
    }

    private fun saveToMediaStore(context: Context, sourceFile: File): Pair<Uri, String>? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "QuickVideo_$timestamp.mp4"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickVideo")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, doneValues, null, null)
            }
            itemUri to displayName
        } catch (_: Throwable) {
            null
        }
    }
}
