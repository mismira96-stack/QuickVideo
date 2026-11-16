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
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
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

    private val defaultPxPerSecond = 84f * density
    private var pxPerSecond = defaultPxPerSecond
    private val rulerStepsMs = longArrayOf(
        125L, 250L, 500L, 1000L, 2500L, 5000L, 15_000L, 30_000L, 60_000L,
        150_000L, 300_000L, 900_000L, 1_800_000L
    )

    private val trackHeightPx = 58f * density
    private val rulerHeightPx = 22f * density
    private val trackTopPx = rulerHeightPx + 6f * density
    private val handleWidthPx = 7f * density
    private val cornerRadiusPx = 4f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#17181B")
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0A4A8")
        textSize = 10f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55575A")
        strokeWidth = 1f * density
    }
    private val clipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2E31")
    }
    private val clipDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10131C")
        strokeWidth = density
    }
    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F2F4")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F2F4")
        style = Paint.Style.FILL
    }
    private val handleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5E63")
        strokeWidth = density
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * scaledDensity
        typeface = Typeface.DEFAULT
    }
    private val playheadLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#009CE9")
        strokeWidth = 1.25f * density
    }

    private val clipStripBitmaps = HashMap<String, List<Bitmap>>()
    private var thumbJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private enum class TouchMode {
        NONE, SCRUB, TRIM_LEFT, TRIM_RIGHT, ZOOM
    }

    private var touchMode = TouchMode.NONE
    private var downX = 0f
    private var isDragging = false
    private var lastScrubX = 0f
    private var lastScrubEventTime = 0L
    private var initialTrimStartMs = 0L
    private var initialTrimEndMs = 0L
    private var activeTrimStartMs = 0L
    private var activeTrimEndMs = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (touchMode != TouchMode.ZOOM) return false
            val totalSeconds = (placements.last().timelineEndMs / 1000f).coerceAtLeast(1f)
            val fitScale = (width / totalSeconds).coerceAtMost(defaultPxPerSecond)
            pxPerSecond = (pxPerSecond * detector.scaleFactor).coerceIn(fitScale, defaultPxPerSecond * 4f)
            invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

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
        if (oldIds.isEmpty() && total > 0L) {
            // Start long videos with a wider view while keeping short clips at the normal scale.
            pxPerSecond = (max(width.toFloat(), 320f * density) / (total / 1000f))
                .coerceIn(16f * density, defaultPxPerSecond)
        }
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

        val trackTop = trackTopPx
        val trackBottom = (trackTop + trackHeightPx).coerceAtMost(h - 7f * density)
        if (trackBottom <= trackTop) return

        // 1. Draw Time Ruler ticks
        val totalMs = TimelineOperations.totalDurationMs(clips)
        val maxRulerMs = max(totalMs + 5000L, 10000L)
        val tickStepMs = rulerStepsMs.firstOrNull { it / 1000f * pxPerSecond >= 38f * density }
            ?: rulerStepsMs.last()
        var tickMs = xToMs(-40f, centerX).coerceAtLeast(0L) / tickStepMs * tickStepMs
        val lastTickMs = minOf(maxRulerMs, xToMs(w + 40f, centerX))
        while (tickMs <= lastTickMs) {
            val x = msToX(tickMs, centerX)
            if (x >= -40f && x <= w + 40f) {
                if (tickMs % (2L * tickStepMs) == 0L) {
                    canvas.drawText(
                        TimelineOperations.formatDuration(tickMs, tickMs % 1000L != 0L),
                        x,
                        rulerHeightPx - 7f * density,
                        rulerTextPaint
                    )
                    canvas.drawLine(x, rulerHeightPx - 4f * density, x, rulerHeightPx + 3f * density, rulerTickPaint)
                } else {
                    canvas.drawCircle(x, rulerHeightPx - 3f * density, 1.5f * density, rulerTickPaint)
                }
            }
            tickMs += tickStepMs
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
                var frameIdx = floor(-leftX / cellWidth).toInt().coerceAtLeast(0)
                var curX = leftX + frameIdx * cellWidth
                val visibleRight = minOf(rightX, w)
                while (curX < visibleRight) {
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
            canvas.drawColor(if (placement.index == selectedClipIndex) 0x18000000 else 0x48000000)

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

        // Draw the fixed center playhead above the clip track.
        val playheadTop = rulerHeightPx - 2f * density
        val playheadBottom = h
        canvas.drawLine(centerX, playheadTop, centerX, playheadBottom, playheadLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clips.isEmpty()) return false
        val centerX = width / 2f
        val trackTop = trackTopPx
        val trackBottom = (trackTop + trackHeightPx).coerceAtMost(height - 7f * density)

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && touchMode != TouchMode.ZOOM) {
            if ((touchMode == TouchMode.TRIM_LEFT || touchMode == TouchMode.TRIM_RIGHT) &&
                (activeTrimStartMs != initialTrimStartMs || activeTrimEndMs != initialTrimEndMs)
            ) {
                // Let a trim already in progress finish before starting a zoom gesture.
                return true
            }
            touchMode = TouchMode.ZOOM
        }
        scaleDetector.onTouchEvent(event)
        if (touchMode == TouchMode.ZOOM) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchMode = TouchMode.NONE
                listener?.onScrubEnd(playheadMs)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                isDragging = false
                lastScrubX = event.x
                lastScrubEventTime = event.eventTime

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
                if (event.pointerCount != 1) return true
                val dx = event.x - downX
                if (abs(dx) > 4f * density) {
                    isDragging = true
                }
                if (!isDragging) return true

                when (touchMode) {
                    TouchMode.SCRUB -> {
                        val movementPx = event.x - lastScrubX
                        val elapsedMs = (event.eventTime - lastScrubEventTime).coerceAtLeast(1L)
                        lastScrubX = event.x
                        lastScrubEventTime = event.eventTime
                        val speedDpPerSecond = abs(movementPx) * 1000f / (elapsedMs * density)
                        // Keep slow movement at the normal scale and cover more time on a quick swipe.
                        val acceleration = ((speedDpPerSecond - 450f) / 1350f).coerceIn(0f, 1f)
                        val deltaMs = (-movementPx / pxPerSecond * 1000f * (1f + 7f * acceleration)).roundToLong()
                        val targetMs = (playheadMs + deltaMs).coerceIn(0L, placements.last().timelineEndMs)
                        if (targetMs == playheadMs) return true
                        playheadMs = targetMs
                        val placement = placements.firstOrNull { playheadMs < it.timelineEndMs } ?: placements.last()
                        if (placement.index != selectedClipIndex) {
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

                    TouchMode.NONE, TouchMode.ZOOM -> Unit
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
                        if (isDragging) {
                            listener?.onClipTrimChanged(
                                selectedClipIndex,
                                activeTrimStartMs,
                                activeTrimEndMs,
                                playheadMs,
                                finished = true
                            )
                        } else {
                            listener?.onScrubEnd(playheadMs)
                        }
                    }

                    TouchMode.NONE, TouchMode.ZOOM -> Unit
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
