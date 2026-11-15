package com.quickvideo.app

import android.net.Uri
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

data class VideoClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri?,
    val title: String,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    val rotationDegrees: Float = 0f,
    val scaleFactor: Float = 1.0f,
    val isMirrored: Boolean = false,
    val width: Int = 1080,
    val height: Int = 1920
) {
    val trimmedSourceDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(MIN_CLIP_SOURCE_MS)

    val timelineDurationMs: Long
        get() = (trimmedSourceDurationMs / speed.coerceIn(0.25f, 2.0f)).roundToLong().coerceAtLeast(100L)

    val effectiveWidth: Int
        get() {
            val norm = ((rotationDegrees.roundToLong().toInt() % 360) + 360) % 360
            return if (norm in 45..135 || norm in 225..315) height else width
        }

    val effectiveHeight: Int
        get() {
            val norm = ((rotationDegrees.roundToLong().toInt() % 360) + 360) % 360
            return if (norm in 45..135 || norm in 225..315) width else height
        }

    val isPortrait: Boolean
        get() = effectiveHeight >= effectiveWidth

    companion object {
        const val MIN_CLIP_SOURCE_MS = 300L
    }
}

enum class AspectRatioOption(val label: String, val ratio: Float?) {
    AUTO("원본 비율", null),
    SHORTS_9_16("9:16 숏폼", 9f / 16f),
    YOUTUBE_16_9("16:9 가로", 16f / 9f),
    SQUARE_1_1("1:1 정방형", 1f),
    PORTRAIT_4_5("4:5 세로", 4f / 5f)
}

enum class ExportResolution(val label: String, val desc: String, val shortSidePx: Int?) {
    FHD_1080P("고화질 1080p", "가장 선명하고 인스타·유튜브·카톡 공유에 최적 (추천)", 1080),
    HD_720P("일반화질 720p", "빠른 저장 및 가벼운 용량", 720),
    ORIGINAL("원본 해상도", "원본 크기 그대로 유지", null)
}

enum class ExportFps(val label: String, val fps: Int) {
    FPS_30("30fps (표준)", 30),
    FPS_60("60fps (부드러움)", 60)
}

data class ClipPlacement(
    val index: Int,
    val clip: VideoClip,
    val timelineStartMs: Long,
    val timelineEndMs: Long
)

object TimelineOperations {

    fun placements(clips: List<VideoClip>): List<ClipPlacement> {
        var cursor = 0L
        return clips.mapIndexed { idx, clip ->
            val start = cursor
            val end = start + clip.timelineDurationMs
            cursor = end
            ClipPlacement(idx, clip, start, end)
        }
    }

    fun totalDurationMs(clips: List<VideoClip>): Long =
        clips.sumOf { it.timelineDurationMs }

    fun findPlacementAt(clips: List<VideoClip>, timelineMs: Long): ClipPlacement? {
        if (clips.isEmpty()) return null
        val list = placements(clips)
        val clamped = timelineMs.coerceIn(0L, list.last().timelineEndMs)
        return list.firstOrNull { clamped >= it.timelineStartMs && clamped < it.timelineEndMs }
            ?: list.last()
    }

    /**
     * Converts a global timeline position (ms) into the source-video timestamp (ms) within the active clip.
     */
    fun timelineToSourceMs(placement: ClipPlacement, timelineMs: Long): Long {
        val offsetInClipTimeline = (timelineMs - placement.timelineStartMs)
            .coerceIn(0L, placement.clip.timelineDurationMs)
        val sourceOffset = (offsetInClipTimeline * placement.clip.speed).roundToLong()
        return (placement.clip.trimStartMs + sourceOffset)
            .coerceIn(placement.clip.trimStartMs, placement.clip.trimEndMs)
    }

    /**
     * Converts a source-video timestamp (ms) inside a placement's clip to global timeline position (ms).
     */
    fun sourceToTimelineMs(placement: ClipPlacement, sourceMs: Long): Long {
        val clampedSource = sourceMs.coerceIn(placement.clip.trimStartMs, placement.clip.trimEndMs)
        val timelineOffset = ((clampedSource - placement.clip.trimStartMs) / placement.clip.speed).roundToLong()
        return placement.timelineStartMs + timelineOffset
    }

    /**
     * VLLO "분할 (Split)": Splits the clip at the given global timeline position into two clips.
     * Returns pair of (updatedClips, newSelectedIndex) or null if split point is too close to clip edges.
     */
    fun splitAt(clips: List<VideoClip>, timelineMs: Long): Pair<List<VideoClip>, Int>? {
        val placement = findPlacementAt(clips, timelineMs) ?: return null
        val clip = placement.clip
        val splitSourceMs = timelineToSourceMs(placement, timelineMs)

        if (splitSourceMs - clip.trimStartMs < VideoClip.MIN_CLIP_SOURCE_MS ||
            clip.trimEndMs - splitSourceMs < VideoClip.MIN_CLIP_SOURCE_MS
        ) {
            return null
        }

        val leftClip = clip.copy(
            id = UUID.randomUUID().toString(),
            trimEndMs = splitSourceMs
        )
        val rightClip = clip.copy(
            id = UUID.randomUUID().toString(),
            trimStartMs = splitSourceMs
        )

        val mutable = clips.toMutableList()
        mutable[placement.index] = leftClip
        mutable.add(placement.index + 1, rightClip)
        return mutable to (placement.index + 1)
    }

    /**
     * VLLO "첨부터 (From Very Start)":
     * If the current clip's start was trimmed (trimStartMs > 0), restores trimStartMs back to 0L.
     * Moves playhead to the start of the current clip (or 0L if already at clip start).
     */
    fun trimToVeryStart(clips: List<VideoClip>, timelineMs: Long): Triple<List<VideoClip>, Long, Boolean>? {
        val placement = findPlacementAt(clips, timelineMs) ?: return null
        val clip = placement.clip
        return if (clip.trimStartMs > 0L) {
            val updatedClip = clip.copy(trimStartMs = 0L)
            val mutable = clips.toMutableList()
            mutable[placement.index] = updatedClip
            val newPlacements = placements(mutable)
            Triple(mutable, newPlacements[placement.index].timelineStartMs, true)
        } else {
            val targetMs = if (timelineMs > placement.timelineStartMs + 50L) {
                placement.timelineStartMs
            } else {
                0L
            }
            Triple(clips, targetMs, false)
        }
    }

    /**
     * VLLO "끝까지 (To Very End)":
     * If the current clip's end was trimmed (trimEndMs < sourceDurationMs), restores trimEndMs back to sourceDurationMs.
     * Moves playhead to the end of the current clip (or total timeline end).
     */
    fun trimToVeryEnd(clips: List<VideoClip>, timelineMs: Long): Triple<List<VideoClip>, Long, Boolean>? {
        val placement = findPlacementAt(clips, timelineMs) ?: return null
        val clip = placement.clip
        return if (clip.trimEndMs < clip.sourceDurationMs) {
            val updatedClip = clip.copy(trimEndMs = clip.sourceDurationMs)
            val mutable = clips.toMutableList()
            mutable[placement.index] = updatedClip
            val newPlacements = placements(mutable)
            Triple(mutable, newPlacements[placement.index].timelineEndMs, true)
        } else {
            val allPlacements = placements(clips)
            val targetMs = if (timelineMs < placement.timelineEndMs - 50L) {
                placement.timelineEndMs
            } else {
                allPlacements.lastOrNull()?.timelineEndMs ?: 0L
            }
            Triple(clips, targetMs, false)
        }
    }

    /**
     * VLLO "여기부터 (From Here)": Trims the start of the clip at the playhead to the current playhead position.
     */
    fun trimFromHere(clips: List<VideoClip>, timelineMs: Long): Pair<List<VideoClip>, Long>? {
        val placement = findPlacementAt(clips, timelineMs) ?: return null
        val clip = placement.clip
        val targetSourceMs = timelineToSourceMs(placement, timelineMs)

        if (clip.trimEndMs - targetSourceMs < VideoClip.MIN_CLIP_SOURCE_MS) {
            return null
        }
        if (targetSourceMs <= clip.trimStartMs + 30L) {
            return null
        }

        val updatedClip = clip.copy(trimStartMs = targetSourceMs)
        val mutable = clips.toMutableList()
        mutable[placement.index] = updatedClip
        val newPlacements = placements(mutable)
        val newPlayhead = newPlacements[placement.index].timelineStartMs
        return mutable to newPlayhead
    }

    /**
     * VLLO "여기까지 (To Here)": Trims the end of the clip at the playhead to the current playhead position.
     */
    fun trimToHere(clips: List<VideoClip>, timelineMs: Long): Pair<List<VideoClip>, Long>? {
        val placement = findPlacementAt(clips, timelineMs) ?: return null
        val clip = placement.clip
        val targetSourceMs = timelineToSourceMs(placement, timelineMs)

        if (targetSourceMs - clip.trimStartMs < VideoClip.MIN_CLIP_SOURCE_MS) {
            return null
        }
        if (targetSourceMs >= clip.trimEndMs - 30L) {
            return null
        }

        val updatedClip = clip.copy(trimEndMs = targetSourceMs)
        val mutable = clips.toMutableList()
        mutable[placement.index] = updatedClip
        val newPlacements = placements(mutable)
        val newPlayhead = newPlacements[placement.index].timelineEndMs
        return mutable to newPlayhead
    }

    /**
     * Drag handle trim on a specific clip index.
     */
    fun updateClipTrim(
        clips: List<VideoClip>,
        index: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val clip = clips[index]
        val clampedStart = newTrimStartMs.coerceIn(
            0L,
            max(0L, clip.trimEndMs - VideoClip.MIN_CLIP_SOURCE_MS)
        )
        val clampedEnd = newTrimEndMs.coerceIn(
            clampedStart + VideoClip.MIN_CLIP_SOURCE_MS,
            clip.sourceDurationMs.coerceAtLeast(clampedStart + VideoClip.MIN_CLIP_SOURCE_MS)
        )
        val mutable = clips.toMutableList()
        mutable[index] = clip.copy(trimStartMs = clampedStart, trimEndMs = clampedEnd)
        return mutable
    }

    /**
     * VLLO "배속 (Speed)": Updates clip speed (0.25x .. 2.0x).
     */
    fun updateClipSpeed(
        clips: List<VideoClip>,
        index: Int,
        speed: Float
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val clampedSpeed = ((speed.coerceIn(0.25f, 2.0f) * 20f).roundToLong() / 20f)
        val mutable = clips.toMutableList()
        mutable[index] = clips[index].copy(speed = clampedSpeed)
        return mutable
    }

    /**
     * VLLO "음소거 (Mute)": Toggles audio mute on a specific clip index.
     */
    fun toggleClipMute(
        clips: List<VideoClip>,
        index: Int
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val mutable = clips.toMutableList()
        val cur = mutable[index]
        mutable[index] = cur.copy(isMuted = !cur.isMuted)
        return mutable
    }

    /**
     * VLLO "회전 (Rotate 90°)": Rotates the clip clockwise by 90 degrees.
     */
    fun rotateClip90(
        clips: List<VideoClip>,
        index: Int
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val mutable = clips.toMutableList()
        val cur = mutable[index]
        var nextRot = (cur.rotationDegrees + 90f) % 360f
        if (nextRot > 180f) nextRot -= 360f
        if (nextRot <= -180f) nextRot += 360f
        mutable[index] = cur.copy(rotationDegrees = nextRot)
        return mutable
    }

    /**
     * VLLO "손으로 회전/크기 조절 (Free Angle & Scale & Mirror)":
     */
    fun updateClipTransform(
        clips: List<VideoClip>,
        index: Int,
        rotationDegrees: Float = clips.getOrNull(index)?.rotationDegrees ?: 0f,
        scaleFactor: Float = clips.getOrNull(index)?.scaleFactor ?: 1.0f,
        isMirrored: Boolean = clips.getOrNull(index)?.isMirrored ?: false
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        var normRot = rotationDegrees % 360f
        if (normRot > 180f) normRot -= 360f
        if (normRot <= -180f) normRot += 360f
        val clampedScale = scaleFactor.coerceIn(0.3f, 4.0f)
        val mutable = clips.toMutableList()
        mutable[index] = mutable[index].copy(
            rotationDegrees = normRot,
            scaleFactor = clampedScale,
            isMirrored = isMirrored
        )
        return mutable
    }

    /**
     * VLLO "좌우반전 (Horizontal Mirror)":
     */
    fun toggleClipMirror(
        clips: List<VideoClip>,
        index: Int
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val mutable = clips.toMutableList()
        val cur = mutable[index]
        mutable[index] = cur.copy(isMirrored = !cur.isMirrored)
        return mutable
    }

    /**
     * Resets rotation, scale, and mirror on the selected clip.
     */
    fun resetClipTransform(
        clips: List<VideoClip>,
        index: Int
    ): List<VideoClip> {
        if (index !in clips.indices) return clips
        val mutable = clips.toMutableList()
        mutable[index] = mutable[index].copy(
            rotationDegrees = 0f,
            scaleFactor = 1.0f,
            isMirrored = false
        )
        return mutable
    }

    fun formatDuration(ms: Long, includeTenths: Boolean = false): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val tenths = (safeMs % 1000L) / 100L
        return if (includeTenths) {
            String.format("%02d:%02d.%d", minutes, seconds, tenths)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
