package com.quickvideo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

class VlloTimelineView(context: Context) : View(context) {

    interface Listener {
        fun onScrubStart()
        fun onScrubTo(timelineMs: Long)
        fun onScrubEnd(timelineMs: Long)
        fun onClipSelected(index: Int)
        fun onClipTrimChanged(index: Int, newTrimStartMs: Long, newTrimEndMs: Long, previewTimelineMs: Long, finished: Boolean)
    }

    var listener: Listener? = null

    private var clips: List<VideoClip> = emptyList()
    private var placements: List<ClipPlacement> = emptyList()
    var selectedClipIndex: Int = 0
        private set
    private var playheadMs: Long = 0L

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // Scale: 80dp per second of timeline
    private var pxPerSecond: Float = 84f * density

    private val trackHeightPx = 68f * density
    private val rulerHeightPx = 24f * density
    private val handleWidthPx = 16f * density
    private val cornerRadiusPx = 8f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#131722")
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A96B0")
        textSize = 10f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#323B52")
        strokeWidth = 1f * density
    }
    private val clipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242B3D")
    }
    private val clipDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10131C")
        strokeWidth = 2f * density
    }
    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF")
        style = Paint.Style.FILL
    }
    private val handleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val playheadLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
    }
    private val playheadCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val clipStripBitmaps = HashMap<String, List<Bitmap>>()
    private var thumbJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private enum class TouchMode {
        NONE, SCRUB, TRIM_LEFT, TRIM_RIGHT
    }

    private var touchMode = TouchMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var initialPlayheadMs = 0L
    private var initialTrimStartMs = 0L
    private var initialTrimEndMs = 0L
    private var activeTrimStartMs = 0L
    private var activeTrimEndMs = 0L

    fun setTimelineData(
        newClips: List<VideoClip>,
        selectedIndex: Int,
        currentPlayheadMs: Long
    ) {
        val oldIds = clips.map { it.id }
        clips = newClips
        placements = TimelineOperations.placements(newClips)
        selectedClipIndex = selectedIndex.coerceIn(0, max(0, newClips.lastIndex))
        val total = TimelineOperations.totalDurationMs(newClips)
        playheadMs = currentPlayheadMs.coerceIn(0L, total)

        if (oldIds != newClips.map { it.id }) {
            ensureClipThumbnails(newClips)
        }
        invalidate()
    }

    fun setPlayheadPosition(timelineMs: Long) {
        if (touchMode != TouchMode.NONE) return
        val total = TimelineOperations.totalDurationMs(clips)
        playheadMs = timelineMs.coerceIn(0L, total)
        val placement = TimelineOperations.findPlacementAt(clips, playheadMs)
        if (placement != null && placement.index != selectedClipIndex) {
            selectedClipIndex = placement.index
            listener?.onClipSelected(selectedClipIndex)
        }
        invalidate()
    }

    fun getPlayheadPosition(): Long = playheadMs

    fun selectClip(index: Int) {
        if (index in clips.indices) {
            selectedClipIndex = index
            listener?.onClipSelected(index)
            invalidate()
        }
    }

    private fun ensureClipThumbnails(targetClips: List<VideoClip>) {
        thumbJob?.cancel()
        thumbJob = uiScope.launch {
            for (clip in targetClips) {
                val clipUri = clip.uri ?: continue
                if (clipStripBitmaps.containsKey(clipUri.toString())) continue
                val bitmaps = withContext(Dispatchers.IO) {
                    val list = mutableListOf<Bitmap>()
                    val count = 6
                    val step = (clip.sourceDurationMs / count).coerceAtLeast(200L)
                    for (i in 0 until count) {
                        val t = (i * step).coerceAtMost(clip.sourceDurationMs)
                        val bmp = VideoRepository.extractFrameAt(context, clipUri, t, 140, 140)
                        if (bmp != null) list.add(bmp)
                    }
                    list
                }
                if (bitmaps.isNotEmpty()) {
                    clipStripBitmaps[clipUri.toString()] = bitmaps
                    invalidate()
                }
            }
        }
    }

    private fun msToX(ms: Long, centerX: Float): Float {
        val deltaMs = ms - playheadMs
        return centerX + (deltaMs / 1000f) * pxPerSecond
    }

    private fun xToMs(x: Float, centerX: Float): Long {
        val deltaPx = x - centerX
        val deltaMs = ((deltaPx / pxPerSecond) * 1000f).roundToLong()
        return playheadMs + deltaMs
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val trackTop = rulerHeightPx + 14f * density
        val trackBottom = (trackTop + trackHeightPx).coerceAtMost(h - 14f * density)

        // 1. Draw Time Ruler ticks
        val totalMs = TimelineOperations.totalDurationMs(clips)
        val maxRulerMs = max(totalMs + 5000L, 10000L)
        var tickMs = 0L
        while (tickMs <= maxRulerMs) {
            val x = msToX(tickMs, centerX)
            if (x >= -40f && x <= w + 40f) {
                if (tickMs % 1000L == 0L) {
                    val sec = tickMs / 1000L
                    canvas.drawText(
                        String.format("%02d:%02d", sec / 60, sec % 60),
                        x,
                        rulerHeightPx - 7f * density,
                        rulerTextPaint
                    )
                    canvas.drawLine(x, rulerHeightPx - 4f * density, x, rulerHeightPx + 3f * density, rulerTickPaint)
                } else {
                    canvas.drawCircle(x, rulerHeightPx - 3f * density, 1.5f * density, rulerTickPaint)
                }
            }
            tickMs += 500L
        }

        // 2. Draw Video Clip Blocks
        for (placement in placements) {
            val leftX = msToX(placement.timelineStartMs, centerX)
            val rightX = msToX(placement.timelineEndMs, centerX)
            if (rightX < -50f || leftX > w + 50f) continue

            val clipRect = RectF(leftX, trackTop, rightX, trackBottom)
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawRect(clipRect, clipBgPaint)

            // Draw thumbnail filmstrip inside clip
            val frames = clipStripBitmaps[placement.clip.uri.toString()]
            if (!frames.isNullOrEmpty()) {
                val cellWidth = trackBottom - trackTop
                var curX = leftX
                var frameIdx = 0
                while (curX < rightX) {
                    val bmp = frames[frameIdx % frames.size]
                    val dst = RectF(curX, trackTop, (curX + cellWidth).coerceAtMost(rightX), trackBottom)
                    val srcWidth = ((dst.width() / cellWidth) * bmp.width).toInt().coerceAtLeast(1)
                    val src = Rect(0, 0, srcWidth.coerceAtMost(bmp.width), bmp.height)
                    canvas.drawBitmap(bmp, src, dst, null)
                    curX += cellWidth
                    frameIdx++
                }
            }

            // Slight dark gradient/tint for readability
            canvas.drawColor(Color.parseColor("#33000000"))

            // Draw clip number & speed/duration/mute/rotation badge inside clip
            val label = buildString {
                append("#${placement.index + 1} ")
                append(String.format("%.1fs", placement.clip.timelineDurationMs / 1000f))
                if (abs(placement.clip.speed - 1.0f) > 0.01f) {
                    append(" · ${formatSpeed(placement.clip.speed)}")
                }
                if (placement.clip.isMuted) {
                    append(" · 음소거")
                }
                if (abs(placement.clip.rotationDegrees) > 0.5f) {
                    append(" · ${placement.clip.rotationDegrees.roundToLong()}°")
                }
                if (abs(placement.clip.scaleFactor - 1.0f) > 0.02f) {
                    append(" · ${(placement.clip.scaleFactor * 100).roundToLong()}%")
                }
                if (placement.clip.isMirrored) {
                    append(" · 반전")
                }
            }
            val textWidth = badgeTextPaint.measureText(label)
            val badgeRect = RectF(
                leftX + 8f * density,
                trackTop + 6f * density,
                (leftX + 18f * density + textWidth).coerceAtMost(rightX - 4f * density),
                trackTop + 24f * density
            )
            if (badgeRect.width() > 24f * density) {
                canvas.drawRoundRect(badgeRect, 5f * density, 5f * density, badgeBgPaint)
                canvas.drawText(
                    label,
                    badgeRect.left + 5f * density,
                    badgeRect.bottom - 5f * density,
                    badgeTextPaint
                )
            }

            canvas.restore()

            // Divider between clips
            canvas.drawLine(rightX, trackTop, rightX, trackBottom, clipDividerPaint)
        }

        // 3. Draw VLLO Trim Handles on Selected Clip
        if (selectedClipIndex in placements.indices) {
            val sel = placements[selectedClipIndex]
            val leftX = msToX(sel.timelineStartMs, centerX)
            val rightX = msToX(sel.timelineEndMs, centerX)
            val selRect = RectF(leftX, trackTop, rightX, trackBottom)

            // Outer highlight border
            canvas.drawRoundRect(selRect, cornerRadiusPx, cornerRadiusPx, selectedBorderPaint)

            // Left handle ear
            val leftHandleRect = RectF(
                leftX - handleWidthPx * 0.5f,
                trackTop - 2f * density,
                leftX + handleWidthPx * 0.5f,
                trackBottom + 2f * density
            )
            canvas.drawRoundRect(leftHandleRect, 6f * density, 6f * density, handlePaint)
            val gripCenterY = (trackTop + trackBottom) / 2f
            canvas.drawLine(
                leftHandleRect.centerX(),
                gripCenterY - 9f * density,
                leftHandleRect.centerX(),
                gripCenterY + 9f * density,
                handleGripPaint
            )

            // Right handle ear
            val rightHandleRect = RectF(
                rightX - handleWidthPx * 0.5f,
                trackTop - 2f * density,
                rightX + handleWidthPx * 0.5f,
                trackBottom + 2f * density
            )
            canvas.drawRoundRect(rightHandleRect, 6f * density, 6f * density, handlePaint)
            canvas.drawLine(
                rightHandleRect.centerX(),
                gripCenterY - 9f * density,
                rightHandleRect.centerX(),
                gripCenterY + 9f * density,
                handleGripPaint
            )
        }

        // 4. Draw Fixed Center Playhead (White vertical bar + top diamond/triangle cap)
        val playheadTop = rulerHeightPx - 2f * density
        val playheadBottom = trackBottom + 10f * density
        canvas.drawLine(centerX, playheadTop, centerX, playheadBottom, playheadLinePaint)
        canvas.drawCircle(centerX, playheadTop, 5f * density, playheadCapPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clips.isEmpty()) return false
        val centerX = width / 2f
        val trackTop = rulerHeightPx + 14f * density
        val trackBottom = (trackTop + trackHeightPx).coerceAtMost(height - 14f * density)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                isDragging = false
                initialPlayheadMs = playheadMs

                // Check if user touched left/right trim handle of the selected clip
                val sel = placements.getOrNull(selectedClipIndex)
                if (sel != null && event.y in (trackTop - 16f * density)..(trackBottom + 16f * density)) {
                    val leftX = msToX(sel.timelineStartMs, centerX)
                    val rightX = msToX(sel.timelineEndMs, centerX)
                    val hitRadius = 24f * density

                    if (abs(event.x - leftX) <= hitRadius) {
                        touchMode = TouchMode.TRIM_LEFT
                        initialTrimStartMs = sel.clip.trimStartMs
                        initialTrimEndMs = sel.clip.trimEndMs
                        activeTrimStartMs = initialTrimStartMs
                        activeTrimEndMs = initialTrimEndMs
                        listener?.onScrubStart()
                        return true
                    } else if (abs(event.x - rightX) <= hitRadius) {
                        touchMode = TouchMode.TRIM_RIGHT
                        initialTrimStartMs = sel.clip.trimStartMs
                        initialTrimEndMs = sel.clip.trimEndMs
                        activeTrimStartMs = initialTrimStartMs
                        activeTrimEndMs = initialTrimEndMs
                        listener?.onScrubStart()
                        return true
                    }
                }

                touchMode = TouchMode.SCRUB
                listener?.onScrubStart()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (abs(dx) > 4f * density) {
                    isDragging = true
                }

                when (touchMode) {
                    TouchMode.SCRUB -> {
                        val deltaMs = ((-dx / pxPerSecond) * 1000f).roundToLong()
                        val totalMs = TimelineOperations.totalDurationMs(clips)
                        playheadMs = (initialPlayheadMs + deltaMs).coerceIn(0L, totalMs)
                        val placement = TimelineOperations.findPlacementAt(clips, playheadMs)
                        if (placement != null && placement.index != selectedClipIndex) {
                            selectedClipIndex = placement.index
                            listener?.onClipSelected(selectedClipIndex)
                        }
                        listener?.onScrubTo(playheadMs)
                        invalidate()
                    }

                    TouchMode.TRIM_LEFT -> {
                        val sel = placements.getOrNull(selectedClipIndex) ?: return true
                        val deltaTimelineMs = ((dx / pxPerSecond) * 1000f).roundToLong()
                        val deltaSourceMs = (deltaTimelineMs * sel.clip.speed).roundToLong()
                        val maxStart = (initialTrimEndMs - VideoClip.MIN_CLIP_SOURCE_MS).coerceAtLeast(0L)
                        activeTrimStartMs = (initialTrimStartMs + deltaSourceMs).coerceIn(0L, maxStart)
                        activeTrimEndMs = initialTrimEndMs

                        clips = TimelineOperations.updateClipTrim(
                            clips,
                            selectedClipIndex,
                            activeTrimStartMs,
                            activeTrimEndMs
                        )
                        placements = TimelineOperations.placements(clips)
                        playheadMs = placements[selectedClipIndex].timelineStartMs
                        listener?.onClipTrimChanged(
                            selectedClipIndex,
                            activeTrimStartMs,
                            activeTrimEndMs,
                            playheadMs,
                            finished = false
                        )
                        invalidate()
                    }

                    TouchMode.TRIM_RIGHT -> {
                        val sel = placements.getOrNull(selectedClipIndex) ?: return true
                        val deltaTimelineMs = ((dx / pxPerSecond) * 1000f).roundToLong()
                        val deltaSourceMs = (deltaTimelineMs * sel.clip.speed).roundToLong()
                        val minEnd = initialTrimStartMs + VideoClip.MIN_CLIP_SOURCE_MS
                        activeTrimEndMs = (initialTrimEndMs + deltaSourceMs)
                            .coerceIn(minEnd, sel.clip.sourceDurationMs)
                        activeTrimStartMs = initialTrimStartMs

                        clips = TimelineOperations.updateClipTrim(
                            clips,
                            selectedClipIndex,
                            activeTrimStartMs,
                            activeTrimEndMs
                        )
                        placements = TimelineOperations.placements(clips)
                        playheadMs = placements[selectedClipIndex].timelineEndMs
                        listener?.onClipTrimChanged(
                            selectedClipIndex,
                            activeTrimStartMs,
                            activeTrimEndMs,
                            playheadMs,
                            finished = false
                        )
                        invalidate()
                    }

                    TouchMode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (touchMode) {
                    TouchMode.SCRUB -> {
                        if (!isDragging) {
                            // Tap to select clip
                            val tappedMs = xToMs(event.x, centerX)
                            val tappedPlacement = placements.firstOrNull {
                                tappedMs >= it.timelineStartMs && tappedMs <= it.timelineEndMs
                            }
                            if (tappedPlacement != null) {
                                selectedClipIndex = tappedPlacement.index
                                listener?.onClipSelected(selectedClipIndex)
                            }
                        }
                        listener?.onScrubEnd(playheadMs)
                    }

                    TouchMode.TRIM_LEFT, TouchMode.TRIM_RIGHT -> {
                        listener?.onClipTrimChanged(
                            selectedClipIndex,
                            activeTrimStartMs,
                            activeTrimEndMs,
                            playheadMs,
                            finished = true
                        )
                    }

                    TouchMode.NONE -> Unit
                }
                touchMode = TouchMode.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun formatSpeed(speed: Float): String {
        return if (abs(speed - speed.toInt()) < 0.01f) {
            "${speed.toInt()}.0x"
        } else if (abs(speed * 10f - (speed * 10f).toInt()) < 0.01f) {
            String.format("%.1fx", speed)
        } else {
            String.format("%.2fx", speed)
        }
    }
}
