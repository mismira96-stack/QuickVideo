package com.quickvideo.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI_STRINGS = "extra_video_uri_strings"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var aspectContainer: AspectRatioFrameLayout
    private lateinit var transformOverlayView: VlloTransformOverlayView
    private lateinit var timelineView: VlloTimelineView

    private lateinit var timeIndicatorText: TextView
    private lateinit var ratioBadgeButton: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var clipInfoBadge: TextView
    private lateinit var undoButton: TextView

    // Bottom action bar dynamic buttons
    private lateinit var speedActionIcon: EditorToolIconView
    private lateinit var speedActionLabel: TextView
    private lateinit var transformActionIcon: EditorToolIconView
    private lateinit var transformActionLabel: TextView
    private lateinit var mirrorActionIcon: EditorToolIconView
    private lateinit var mirrorActionLabel: TextView
    private lateinit var muteActionIcon: EditorToolIconView
    private lateinit var muteActionLabel: TextView

    // Speed Sub-Panel views
    private lateinit var speedPanel: LinearLayout
    private lateinit var speedValueText: TextView
    private lateinit var speedSeekBar: SeekBar
    private val speedPresetButtons = mutableListOf<Pair<Float, TextView>>()

    // Transform (Finger Rotate & Scale & Mirror) Sub-Panel views
    private lateinit var transformPanel: LinearLayout
    private lateinit var rotationValueText: TextView
    private lateinit var rotationSeekBar: SeekBar
    private lateinit var scaleValueText: TextView
    private lateinit var scaleSeekBar: SeekBar
    private var isTransformModeActive: Boolean = false

    private var clips: List<VideoClip> = emptyList()
    private val undoStack = ArrayDeque<List<VideoClip>>()
    private var selectedClipIndex: Int = 0
    private var aspectRatioOption: AspectRatioOption = AspectRatioOption.AUTO

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScrubbing = false
    private var lastScrubSeekUptimeMs = 0L

    private val addVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(15)
    ) { uris ->
        if (uris.isNotEmpty()) {
            appendVideoUris(uris)
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && !isScrubbing && clips.isNotEmpty()) {
                syncPlayheadFromPlayer()
            }
            mainHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPlayer()
        setupUi()

        val uriStrings = intent.getStringArrayListExtra(EXTRA_VIDEO_URI_STRINGS) ?: arrayListOf()
        val initialUris = uriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

        if (initialUris.isEmpty()) {
            Toast.makeText(this, "선택된 동영상이 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadInitialClips(initialUris)
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val idx = currentMediaItemIndex
                    if (idx in clips.indices) {
                        selectedClipIndex = idx
                        applyClipSpeedToPlayer(idx)
                        updateClipInfoUi()
                        syncTransformPanelToSelectedClip()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon()
                    if (isPlaying) {
                        val idx = currentMediaItemIndex
                        if (idx in clips.indices) {
                            applyClipSpeedToPlayer(idx)
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && playWhenReady) {
                        pause()
                        val total = TimelineOperations.totalDurationMs(clips)
                        timelineView.setPlayheadPosition(total)
                        updateTimeLabel(total, total)
                    }
                }
            })
        }
    }

    @OptIn(UnstableApi::class)
    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#17181B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. VLLO Top Toolbar: [✕] [↶ 취소] [비율: 원본] .... [추출하기] (Uniform 36dp height)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#222427"))
        }

        val backBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#292C2F"))
                setStroke(dp(1), Color.parseColor("#383B40"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        undoButton = TextView(this).apply {
            text = "↶ 취소"
            setTextColor(Color.parseColor("#6E7B96"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#292C2F"))
                setStroke(dp(1), Color.parseColor("#383B40"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                leftMargin = dp(8)
            }
            setOnClickListener { performUndo() }
        }
        topBar.addView(undoButton)

        ratioBadgeButton = TextView(this).apply {
            text = "비율: 원본"
            setTextColor(Color.parseColor("#E4E9F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#292C2F"))
                setStroke(dp(1), Color.parseColor("#383B40"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                leftMargin = dp(8)
            }
            setOnClickListener { cycleAspectRatio() }
        }
        topBar.addView(ratioBadgeButton)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        val exportBtn = TextView(this).apply {
            text = "추출하기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#009CE9"))
            }
            setOnClickListener { showExportDialog() }
        }
        topBar.addView(exportBtn)
        root.addView(topBar)

        // 2. Video Preview Area with Direct Hand Drag Rotate & Resize Overlay
        val previewStage = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#17181B"))
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        aspectContainer = AspectRatioFrameLayout(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        playerView = (LayoutInflater.from(this).inflate(
            R.layout.view_editor_player,
            aspectContainer,
            false
        ) as PlayerView).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            player = this@EditorActivity.player
        }
        aspectContainer.addView(playerView)
        previewStage.addView(aspectContainer)

        // Interactive touch overlay for finger drag rotation & pinch/handle scaling
        transformOverlayView = VlloTransformOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onGestureStart = {
                pushUndoSnapshot()
                player.pause()
            }
            onTransformChanged = { newRotation, newScale ->
                if (selectedClipIndex in clips.indices) {
                    val cur = clips[selectedClipIndex]
                    clips = TimelineOperations.updateClipTransform(
                        clips,
                        selectedClipIndex,
                        rotationDegrees = newRotation,
                        scaleFactor = newScale,
                        isMirrored = cur.isMirrored
                    )
                    applyClipTransformToPreview(clips[selectedClipIndex])
                    updateClipInfoUi()
                    syncTransformPanelToSelectedClip()
                    timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
                }
            }
            onDoubleTapReset = {
                if (selectedClipIndex in clips.indices) {
                    pushUndoSnapshot()
                    clips = TimelineOperations.resetClipTransform(clips, selectedClipIndex)
                    applyClipTransformToPreview(clips[selectedClipIndex])
                    updateClipInfoUi()
                    syncTransformPanelToSelectedClip()
                    timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
                    Toast.makeText(this@EditorActivity, "각도와 크기를 0° / 100%로 초기화했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        previewStage.addView(transformOverlayView)

        clipInfoBadge = TextView(this).apply {
            text = "클립 1/1 · 1.0x"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#A8222427"))
                setStroke(dp(1), Color.parseColor("#383B40"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins(dp(16), dp(12), dp(16), dp(12))
            }
        }
        previewStage.addView(clipInfoBadge)
        root.addView(previewStage)

        // 3. Transport & Time Bar (FrameLayout so [⏮ ▶ ⏭] is 100% centered with Timeline Playhead)
        val transportBar = FrameLayout(this).apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setBackgroundColor(Color.parseColor("#222427"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }

        timeIndicatorText = TextView(this).apply {
            text = "00:00.0/00:00.0"
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = -0.05f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        transportBar.addView(timeIndicatorText)

        val centerPlayControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val prevClipBtn = createTransportIconButton("⏮") {
            jumpToClipEdge(previous = true)
        }
        centerPlayControls.addView(prevClipBtn)

        playPauseButton = TextView(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#009CE9"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            setOnClickListener { togglePlayPause() }
        }
        centerPlayControls.addView(playPauseButton)

        val nextClipBtn = createTransportIconButton("⏭") {
            jumpToClipEdge(previous = false)
        }
        centerPlayControls.addView(nextClipBtn)
        transportBar.addView(centerPlayControls)

        val addClipBtn = TextView(this).apply {
            text = "+ 추가"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(Color.parseColor("#292C2F"))
                setStroke(dp(1), Color.parseColor("#383B40"))
            }
            setOnClickListener {
                player.pause()
                addVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        }
        transportBar.addView(addClipBtn)
        root.addView(transportBar)

        // 4. VLLO Multi-Clip Scrollable Timeline with Fixed Center Playhead
        timelineView = VlloTimelineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96)
            )
            listener = object : VlloTimelineView.Listener {
                override fun onScrubStart() {
                    isScrubbing = true
                    lastScrubSeekUptimeMs = 0L
                    player.pause()
                }

                override fun onScrubTo(timelineMs: Long) {
                    val now = SystemClock.uptimeMillis()
                    if (lastScrubSeekUptimeMs == 0L || now - lastScrubSeekUptimeMs >= 80L) {
                        if (lastScrubSeekUptimeMs == 0L) {
                            player.setSeekParameters(SeekParameters(100_000L, 100_000L))
                        }
                        lastScrubSeekUptimeMs = now
                        seekPlayerToTimelineMs(timelineMs, updateClipPlayback = false)
                    }
                    updateTimeLabel(timelineMs, TimelineOperations.totalDurationMs(clips))
                }

                override fun onScrubEnd(timelineMs: Long) {
                    isScrubbing = false
                    player.setSeekParameters(SeekParameters.EXACT)
                    seekPlayerToTimelineMs(timelineMs)
                    updateTimeLabel(timelineMs, TimelineOperations.totalDurationMs(clips))
                }

                override fun onClipSelected(index: Int) {
                    this@EditorActivity.selectedClipIndex = index
                    applyClipSpeedToPlayer(index)
                    updateClipInfoUi()
                    syncSpeedPanelToSelectedClip()
                    syncTransformPanelToSelectedClip()
                }

                override fun onClipTrimChanged(
                    index: Int,
                    newTrimStartMs: Long,
                    newTrimEndMs: Long,
                    previewTimelineMs: Long,
                    finished: Boolean
                ) {
                    if (!finished) {
                        clips = TimelineOperations.updateClipTrim(clips, index, newTrimStartMs, newTrimEndMs)
                        updateTimeLabel(previewTimelineMs, TimelineOperations.totalDurationMs(clips))
                        updateClipInfoUi()
                    } else {
                        isScrubbing = false
                        clips = TimelineOperations.updateClipTrim(clips, index, newTrimStartMs, newTrimEndMs)
                        rebuildPlayerPlaylist(targetTimelineMs = previewTimelineMs, autoPlay = false)
                    }
                }
            }
        }
        root.addView(timelineView)

        // 5. Symmetrical 5-Button Cut/Trim Bar: [첨부터] [여기부터] [✂ 분할] [여기까지] [끝까지]
        val cutTrimBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(Color.parseColor("#222427"))
        }

        cutTrimBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.CUT_TO_START, "처음부터") {
                performTrimToVeryStart()
            }.first
        )
        cutTrimBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.CUT_BEFORE, "여기부터") {
                performTrimFromHere()
            }.first
        )
        cutTrimBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.SPLIT, "분할") {
                performSplitAtPlayhead()
            }.first
        )
        cutTrimBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.CUT_AFTER, "여기까지") {
                performTrimToHere()
            }.first
        )
        cutTrimBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.CUT_TO_END, "끝까지") {
                performTrimToVeryEnd()
            }.first
        )
        root.addView(cutTrimBar)

        // 6. Collapsible Sub-Panels (Speed Panel & Hand Rotate/Scale/Mirror Panel)
        speedPanel = buildSpeedControlPanel()
        root.addView(speedPanel)

        transformPanel = buildTransformControlPanel()
        root.addView(transformPanel)

        // 7. VLLO Bottom Clip Tool Bar (5 spacious buttons): [배속] [회전/크기] [좌우반전] [음소거] [삭제]
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(4))
            setBackgroundColor(Color.parseColor("#2C2E31"))
        }

        val speedBtnTriple = createVlloActionButton(EditorToolIconView.IconType.SPEED, "배속") {
            toggleSpeedPanel()
        }
        speedActionIcon = speedBtnTriple.second
        speedActionLabel = speedBtnTriple.third
        actionBar.addView(speedBtnTriple.first)

        val transformBtnTriple = createVlloActionButton(EditorToolIconView.IconType.ROTATE, "회전/크기") {
            toggleTransformPanel()
        }
        transformActionIcon = transformBtnTriple.second
        transformActionLabel = transformBtnTriple.third
        actionBar.addView(transformBtnTriple.first)

        val mirrorBtnTriple = createVlloActionButton(EditorToolIconView.IconType.MIRROR, "좌우반전") {
            performToggleMirror()
        }
        mirrorActionIcon = mirrorBtnTriple.second
        mirrorActionLabel = mirrorBtnTriple.third
        actionBar.addView(mirrorBtnTriple.first)

        val muteBtnTriple = createVlloActionButton(EditorToolIconView.IconType.MUTE_OFF, "음소거") {
            performToggleMute()
        }
        muteActionIcon = muteBtnTriple.second
        muteActionLabel = muteBtnTriple.third
        actionBar.addView(muteBtnTriple.first)

        actionBar.addView(
            createVlloActionButton(EditorToolIconView.IconType.DELETE, "삭제") {
                performDeleteSelectedClip()
            }.first
        )

        root.addView(actionBar)

        // Apply system status bar & navigation bar insets so UI never overlaps nav bar
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(
                dp(16) + sysBars.left,
                dp(10) + sysBars.top,
                dp(16) + sysBars.right,
                dp(10)
            )
            transportBar.setPadding(
                dp(16) + sysBars.left,
                dp(6),
                dp(16) + sysBars.right,
                dp(6)
            )
            cutTrimBar.setPadding(
                dp(6) + sysBars.left,
                0,
                dp(6) + sysBars.right,
                0
            )
            speedPanel.setPadding(
                dp(16) + sysBars.left,
                dp(10),
                dp(16) + sysBars.right,
                dp(10)
            )
            transformPanel.setPadding(
                dp(16) + sysBars.left,
                dp(10),
                dp(16) + sysBars.right,
                dp(10)
            )
            actionBar.setPadding(
                dp(6) + sysBars.left,
                dp(2),
                dp(6) + sysBars.right,
                dp(4) + sysBars.bottom
            )
            windowInsets
        }

        setContentView(root)
    }

    private fun buildSpeedControlPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#1D2334"))
            visibility = View.GONE
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "클립 배속 조절 (0.25x ~ 2.0x)"
            setTextColor(Color.parseColor("#B5C0D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)

        speedValueText = TextView(this).apply {
            text = "1.0x"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(speedValueText)

        val closeSpeedBtn = TextView(this).apply {
            text = "완료"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(4), dp(4), dp(4))
            setOnClickListener {
                panel.visibility = View.GONE
            }
        }
        headerRow.addView(closeSpeedBtn)
        panel.addView(headerRow)

        speedSeekBar = SeekBar(this).apply {
            max = 35
            progress = 15 // 1.0x
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = 0.25f + progress * 0.05f
                    speedValueText.text = String.format("%.2fx", speed).replace(".00x", ".0x")
                    if (fromUser) {
                        applySpeedToSelectedClip(speed, rebuildPlayer = false)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    pushUndoSnapshot()
                    player.pause()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val speed = 0.25f + (seekBar?.progress ?: 15) * 0.05f
                    applySpeedToSelectedClip(speed, rebuildPlayer = true)
                }
            })
        }
        panel.addView(speedSeekBar)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        speedPresetButtons.clear()
        for (preset in presets) {
            val btn = TextView(this).apply {
                text = if (preset == 1.0f || preset == 2.0f || preset == 0.5f || preset == 1.5f) {
                    String.format("%.1fx", preset)
                } else {
                    String.format("%.2fx", preset)
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    pushUndoSnapshot()
                    applySpeedToSelectedClip(preset, rebuildPlayer = true)
                }
            }
            speedPresetButtons.add(preset to btn)
            presetRow.addView(btn)
        }
        panel.addView(presetRow)
        return panel
    }

    private fun buildTransformControlPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#1D2334"))
            visibility = View.GONE
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val hintText = TextView(this).apply {
            text = "손가락 드래그로 회전·크기 조절"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(hintText)

        val rotate90Btn = TextView(this).apply {
            text = "↻ 90°"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#2979FF"))
            }
            setOnClickListener {
                performRotate90()
            }
        }
        headerRow.addView(rotate90Btn)

        val resetBtn = TextView(this).apply {
            text = "초기화"
            setTextColor(Color.parseColor("#B5C0D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                if (selectedClipIndex in clips.indices) {
                    pushUndoSnapshot()
                    clips = TimelineOperations.resetClipTransform(clips, selectedClipIndex)
                    applyClipTransformToPreview(clips[selectedClipIndex])
                    updateClipInfoUi()
                    syncTransformPanelToSelectedClip()
                    timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
                }
            }
        }
        headerRow.addView(resetBtn)

        val closeBtn = TextView(this).apply {
            text = "완료"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                setTransformMode(false)
            }
        }
        headerRow.addView(closeBtn)
        panel.addView(headerRow)

        // Row 1: Angle slider (-180° .. +180°)
        val angleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        val angleLabel = TextView(this).apply {
            text = "각도"
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        angleRow.addView(angleLabel)

        rotationSeekBar = SeekBar(this).apply {
            max = 360 // 0..360 => -180°..+180°
            progress = 180
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val deg = (progress - 180).toFloat()
                    rotationValueText.text = "${deg.roundToInt()}°"
                    if (fromUser && selectedClipIndex in clips.indices) {
                        val cur = clips[selectedClipIndex]
                        clips = TimelineOperations.updateClipTransform(
                            clips,
                            selectedClipIndex,
                            rotationDegrees = deg,
                            scaleFactor = cur.scaleFactor,
                            isMirrored = cur.isMirrored
                        )
                        applyClipTransformToPreview(clips[selectedClipIndex])
                        updateClipInfoUi()
                        timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    pushUndoSnapshot()
                    player.pause()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        angleRow.addView(rotationSeekBar)

        rotationValueText = TextView(this).apply {
            text = "0°"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        angleRow.addView(rotationValueText)
        panel.addView(angleRow)

        // Row 2: Scale slider (30% .. 300%)
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        val scaleLabel = TextView(this).apply {
            text = "크기"
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scaleRow.addView(scaleLabel)

        scaleSeekBar = SeekBar(this).apply {
            max = 270 // 0..270 => 30% .. 300%
            progress = 70 // 100%
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pct = 30 + progress
                    scaleValueText.text = "${pct}%"
                    if (fromUser && selectedClipIndex in clips.indices) {
                        val cur = clips[selectedClipIndex]
                        clips = TimelineOperations.updateClipTransform(
                            clips,
                            selectedClipIndex,
                            rotationDegrees = cur.rotationDegrees,
                            scaleFactor = pct / 100f,
                            isMirrored = cur.isMirrored
                        )
                        applyClipTransformToPreview(clips[selectedClipIndex])
                        updateClipInfoUi()
                        timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    pushUndoSnapshot()
                    player.pause()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scaleRow.addView(scaleSeekBar)

        scaleValueText = TextView(this).apply {
            text = "100%"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scaleRow.addView(scaleValueText)
        panel.addView(scaleRow)

        return panel
    }

    private fun createTransportIconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.parseColor("#D0D8EC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            setOnClickListener { onClick() }
        }
    }

    private fun createVlloActionButton(
        iconType: EditorToolIconView.IconType,
        label: String,
        onClick: () -> Unit
    ): Triple<LinearLayout, EditorToolIconView, TextView> {
        val iconView = EditorToolIconView(this, iconType).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            addView(iconView)
            addView(labelView)
            setOnClickListener { onClick() }
        }
        return Triple(container, iconView, labelView)
    }

    private fun loadInitialClips(uris: List<Uri>) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { VideoRepository.inspectVideoClip(this@EditorActivity, it) }
            }
            if (loaded.isEmpty()) {
                Toast.makeText(this@EditorActivity, "영상을 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            clips = loaded
            selectedClipIndex = 0
            updateAspectContainerRatio()
            rebuildPlayerPlaylist(targetTimelineMs = 0L, autoPlay = false)
        }
    }

    private fun appendVideoUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { VideoRepository.inspectVideoClip(this@EditorActivity, it) }
            }
            if (added.isNotEmpty()) {
                pushUndoSnapshot()
                val currentPlayhead = timelineView.getPlayheadPosition()
                clips = clips + added
                selectedClipIndex = clips.lastIndex
                rebuildPlayerPlaylist(targetTimelineMs = currentPlayhead, autoPlay = false)
                Toast.makeText(this@EditorActivity, "${added.size}개 영상이 추가되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pushUndoSnapshot() {
        if (clips.isNotEmpty()) {
            if (undoStack.size >= 20) undoStack.removeFirst()
            undoStack.addLast(clips.toList())
            updateUndoButtonState()
        }
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "되돌릴 작업이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        player.pause()
        clips = undoStack.removeLast()
        selectedClipIndex = selectedClipIndex.coerceIn(0, clips.lastIndex)
        updateUndoButtonState()
        val clampedPlayhead = timelineView.getPlayheadPosition()
            .coerceIn(0L, TimelineOperations.totalDurationMs(clips))
        rebuildPlayerPlaylist(targetTimelineMs = clampedPlayhead, autoPlay = false)
    }

    private fun updateUndoButtonState() {
        undoButton.setTextColor(
            if (undoStack.isNotEmpty()) Color.WHITE else Color.parseColor("#6E7B96")
        )
    }

    /**
     * Cut Row Action 1: 첨부터 (Restore start trim to 00:00 or jump to start)
     */
    private fun performTrimToVeryStart() {
        player.pause()
        val playhead = timelineView.getPlayheadPosition()
        val result = TimelineOperations.trimToVeryStart(clips, playhead) ?: return
        val (updatedClips, targetMs, modified) = result
        if (modified) {
            pushUndoSnapshot()
            clips = updatedClips
            rebuildPlayerPlaylist(targetTimelineMs = targetMs, autoPlay = false)
            Toast.makeText(this, "클립 시작점을 맨 처음(첨부터)으로 복원했습니다.", Toast.LENGTH_SHORT).show()
        } else {
            timelineView.setPlayheadPosition(targetMs)
            seekPlayerToTimelineMs(targetMs)
            updateTimeLabel(targetMs, TimelineOperations.totalDurationMs(clips))
            Toast.makeText(this, "처음 위치로 이동했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Cut Row Action 2: 여기부터 (Trim Start to Playhead)
     */
    private fun performTrimFromHere() {
        player.pause()
        val playhead = timelineView.getPlayheadPosition()
        val result = TimelineOperations.trimFromHere(clips, playhead)
        if (result == null) {
            Toast.makeText(this, "현재 위치에서 앞부분을 더 자를 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndoSnapshot()
        clips = result.first
        rebuildPlayerPlaylist(targetTimelineMs = result.second, autoPlay = false)
        Toast.makeText(this, "여기부터 시작하도록 앞부분을 잘랐습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Cut Row Action 3: 분할 (Split at Playhead)
     */
    private fun performSplitAtPlayhead() {
        player.pause()
        val playhead = timelineView.getPlayheadPosition()
        val splitResult = TimelineOperations.splitAt(clips, playhead)
        if (splitResult == null) {
            Toast.makeText(this, "클립의 양 끝에서는 분할할 수 없습니다. 재생선을 안쪽으로 이동해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndoSnapshot()
        clips = splitResult.first
        selectedClipIndex = splitResult.second
        rebuildPlayerPlaylist(targetTimelineMs = playhead, autoPlay = false)
        Toast.makeText(this, "클립이 분할되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Cut Row Action 4: 여기까지 (Trim End to Playhead)
     */
    private fun performTrimToHere() {
        player.pause()
        val playhead = timelineView.getPlayheadPosition()
        val result = TimelineOperations.trimToHere(clips, playhead)
        if (result == null) {
            Toast.makeText(this, "현재 위치에서 뒷부분을 더 자를 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndoSnapshot()
        clips = result.first
        rebuildPlayerPlaylist(targetTimelineMs = result.second, autoPlay = false)
        Toast.makeText(this, "여기까지만 나오도록 뒷부분을 잘랐습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Cut Row Action 5: 끝까지 (Restore end trim to full duration or jump to end)
     */
    private fun performTrimToVeryEnd() {
        player.pause()
        val playhead = timelineView.getPlayheadPosition()
        val result = TimelineOperations.trimToVeryEnd(clips, playhead) ?: return
        val (updatedClips, targetMs, modified) = result
        if (modified) {
            pushUndoSnapshot()
            clips = updatedClips
            rebuildPlayerPlaylist(targetTimelineMs = targetMs, autoPlay = false)
            Toast.makeText(this, "클립 끝점을 원래 끝(끝까지)으로 복원했습니다.", Toast.LENGTH_SHORT).show()
        } else {
            timelineView.setPlayheadPosition(targetMs)
            seekPlayerToTimelineMs(targetMs)
            updateTimeLabel(targetMs, TimelineOperations.totalDurationMs(clips))
            Toast.makeText(this, "끝 위치로 이동했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tool Bar Action 1: 배속 (Speed 0.25x ~ 2.0x)
     */
    private fun toggleSpeedPanel() {
        if (speedPanel.visibility == View.VISIBLE) {
            speedPanel.visibility = View.GONE
        } else {
            setTransformMode(false)
            syncSpeedPanelToSelectedClip()
            speedPanel.visibility = View.VISIBLE
        }
    }

    /**
     * Tool Bar Action 2: 회전/크기 (Hand Drag Rotate & Scale Mode + Slider Panel)
     */
    private fun toggleTransformPanel() {
        setTransformMode(!isTransformModeActive)
    }

    private fun setTransformMode(active: Boolean) {
        isTransformModeActive = active
        if (active) {
            speedPanel.visibility = View.GONE
            syncTransformPanelToSelectedClip()
            transformPanel.visibility = View.VISIBLE
        } else {
            transformPanel.visibility = View.GONE
        }
        val clip = clips.getOrNull(selectedClipIndex)
        if (clip != null) {
            applyClipTransformToPreview(clip)
        }
        updateClipInfoUi()
    }

    private fun syncSpeedPanelToSelectedClip() {
        val clip = clips.getOrNull(selectedClipIndex) ?: return
        val progress = ((clip.speed - 0.25f) / 0.05f).roundToInt().coerceIn(0, 35)
        speedSeekBar.progress = progress
        speedValueText.text = String.format("%.2fx", clip.speed).replace(".00x", ".0x")

        for ((presetVal, btn) in speedPresetButtons) {
            val active = abs(clip.speed - presetVal) < 0.02f
            btn.setTextColor(Color.WHITE)
            btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (active) Color.parseColor("#2979FF") else Color.parseColor("#283046"))
            }
        }
    }

    private fun syncTransformPanelToSelectedClip() {
        val clip = clips.getOrNull(selectedClipIndex) ?: return
        val degInt = clip.rotationDegrees.roundToInt().coerceIn(-180, 180)
        rotationSeekBar.progress = (degInt + 180).coerceIn(0, 360)
        rotationValueText.text = "${degInt}°"

        val scalePct = (clip.scaleFactor * 100f).roundToInt().coerceIn(30, 300)
        scaleSeekBar.progress = (scalePct - 30).coerceIn(0, 270)
        scaleValueText.text = "${scalePct}%"
    }

    private fun applySpeedToSelectedClip(speed: Float, rebuildPlayer: Boolean) {
        if (selectedClipIndex !in clips.indices) return
        clips = TimelineOperations.updateClipSpeed(clips, selectedClipIndex, speed)
        val placements = TimelineOperations.placements(clips)
        val targetMs = placements[selectedClipIndex].timelineStartMs
        timelineView.setTimelineData(clips, selectedClipIndex, targetMs)
        updateClipInfoUi()
        syncSpeedPanelToSelectedClip()
        updateTimeLabel(targetMs, TimelineOperations.totalDurationMs(clips))

        if (rebuildPlayer) {
            applyClipSpeedToPlayer(selectedClipIndex)
            seekPlayerToTimelineMs(targetMs)
        }
    }

    /**
     * Tool Bar Action 3: 좌우반전 (Horizontal Mirror)
     */
    private fun performToggleMirror() {
        if (selectedClipIndex !in clips.indices) return
        pushUndoSnapshot()
        clips = TimelineOperations.toggleClipMirror(clips, selectedClipIndex)
        val clip = clips[selectedClipIndex]
        applyClipTransformToPreview(clip)
        timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
        updateClipInfoUi()
        Toast.makeText(
            this,
            if (clip.isMirrored) "영상을 좌우반전했습니다." else "좌우반전을 해제했습니다.",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Tool Bar Action 4: 음소거 (Toggle Mute on selected clip)
     */
    private fun performToggleMute() {
        if (selectedClipIndex !in clips.indices) return
        pushUndoSnapshot()
        clips = TimelineOperations.toggleClipMute(clips, selectedClipIndex)
        val nowMuted = clips[selectedClipIndex].isMuted
        timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
        applyClipSpeedToPlayer(selectedClipIndex)
        updateClipInfoUi()
        Toast.makeText(
            this,
            if (nowMuted) "선택한 클립을 음소거했습니다." else "선택한 클립 소리를 켰습니다.",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 90° Quick Rotate
     */
    private fun performRotate90() {
        if (selectedClipIndex !in clips.indices) return
        pushUndoSnapshot()
        clips = TimelineOperations.rotateClip90(clips, selectedClipIndex)
        val clip = clips[selectedClipIndex]
        applyClipTransformToPreview(clip)
        timelineView.setTimelineData(clips, selectedClipIndex, timelineView.getPlayheadPosition())
        updateClipInfoUi()
        syncTransformPanelToSelectedClip()
    }

    /**
     * Tool Bar Action 5: 삭제 (Delete Clip)
     */
    private fun performDeleteSelectedClip() {
        if (clips.size <= 1) {
            Toast.makeText(this, "최소 1개의 영상 클립이 있어야 합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        player.pause()
        pushUndoSnapshot()
        val mutable = clips.toMutableList()
        mutable.removeAt(selectedClipIndex)
        clips = mutable
        selectedClipIndex = selectedClipIndex.coerceIn(0, clips.lastIndex)
        val placements = TimelineOperations.placements(clips)
        val newPlayhead = placements[selectedClipIndex].timelineStartMs
        rebuildPlayerPlaylist(targetTimelineMs = newPlayhead, autoPlay = false)
        Toast.makeText(this, "선택한 클립을 삭제했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun jumpToClipEdge(previous: Boolean) {
        player.pause()
        val placements = TimelineOperations.placements(clips)
        if (placements.isEmpty()) return
        val cur = timelineView.getPlayheadPosition()

        val target = if (previous) {
            placements.lastOrNull { it.timelineStartMs < cur - 80L }?.timelineStartMs ?: 0L
        } else {
            placements.firstOrNull { it.timelineStartMs > cur + 80L }?.timelineStartMs
                ?: placements.last().timelineEndMs
        }
        timelineView.setPlayheadPosition(target)
        seekPlayerToTimelineMs(target)
        updateTimeLabel(target, TimelineOperations.totalDurationMs(clips))
    }

    @OptIn(UnstableApi::class)
    private fun cycleAspectRatio() {
        val all = AspectRatioOption.entries
        val nextIdx = (all.indexOf(aspectRatioOption) + 1) % all.size
        aspectRatioOption = all[nextIdx]
        ratioBadgeButton.text = "비율: ${aspectRatioOption.label}"
        updateAspectContainerRatio()
    }

    @OptIn(UnstableApi::class)
    private fun updateAspectContainerRatio() {
        val activeClip = clips.getOrNull(selectedClipIndex) ?: clips.firstOrNull()
        val ratio = aspectRatioOption.ratio ?: run {
            if (activeClip != null && activeClip.height > 0) {
                activeClip.width.toFloat() / activeClip.height.toFloat()
            } else {
                9f / 16f
            }
        }
        aspectContainer.setAspectRatio(ratio)
    }

    private fun applyClipTransformToPreview(clip: VideoClip) {
        val mirrorSign = if (clip.isMirrored) -1f else 1f
        playerView.scaleX = mirrorSign * clip.scaleFactor
        playerView.scaleY = clip.scaleFactor
        playerView.rotation = clip.rotationDegrees
        transformOverlayView.updateTransformState(
            rotationDegrees = clip.rotationDegrees,
            scaleFactor = clip.scaleFactor,
            showGuideBox = isTransformModeActive
        )
    }

    private fun rebuildPlayerPlaylist(targetTimelineMs: Long, autoPlay: Boolean) {
        if (clips.isEmpty()) return
        val mediaItems = clips.map { clip ->
            MediaItem.Builder()
                .setMediaId(clip.id)
                .setUri(requireNotNull(clip.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems)
        player.prepare()

        val totalMs = TimelineOperations.totalDurationMs(clips)
        val clampedTimelineMs = targetTimelineMs.coerceIn(0L, totalMs)
        timelineView.setTimelineData(clips, selectedClipIndex, clampedTimelineMs)
        updateAspectContainerRatio()
        seekPlayerToTimelineMs(clampedTimelineMs)
        updateTimeLabel(clampedTimelineMs, totalMs)
        updateClipInfoUi()
        syncSpeedPanelToSelectedClip()
        syncTransformPanelToSelectedClip()
        updateUndoButtonState()

        if (autoPlay) {
            player.play()
        }
    }

    private fun seekPlayerToTimelineMs(timelineMs: Long, updateClipPlayback: Boolean = true) {
        val placement = TimelineOperations.findPlacementAt(clips, timelineMs) ?: return
        val offsetTimelineMs = (timelineMs - placement.timelineStartMs).coerceAtLeast(0L)
        val offsetInClipSourceMs = (offsetTimelineMs * placement.clip.speed).roundToLong()
            .coerceIn(0L, placement.clip.trimmedSourceDurationMs)

        if (updateClipPlayback) applyClipSpeedToPlayer(placement.index)
        player.seekTo(placement.index, offsetInClipSourceMs)
    }

    private fun applyClipSpeedToPlayer(clipIndex: Int) {
        val clip = clips.getOrNull(clipIndex) ?: return
        if (abs(player.playbackParameters.speed - clip.speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(clip.speed, 1.0f)
        }
        val targetVolume = if (clip.isMuted) 0f else 1f
        if (abs(player.volume - targetVolume) > 0.01f) {
            player.volume = targetVolume
        }
        applyClipTransformToPreview(clip)
    }

    private fun syncPlayheadFromPlayer() {
        val idx = player.currentMediaItemIndex
        val placements = TimelineOperations.placements(clips)
        if (idx !in placements.indices) return
        val placement = placements[idx]
        val posInClipSource = player.currentPosition.coerceIn(0L, placement.clip.trimmedSourceDurationMs)
        val timelineOffset = (posInClipSource / placement.clip.speed).roundToLong()
        val globalTimelineMs = (placement.timelineStartMs + timelineOffset)
            .coerceIn(0L, TimelineOperations.totalDurationMs(clips))

        timelineView.setPlayheadPosition(globalTimelineMs)
        updateTimeLabel(globalTimelineMs, TimelineOperations.totalDurationMs(clips))
    }

    private fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            val total = TimelineOperations.totalDurationMs(clips)
            if (timelineView.getPlayheadPosition() >= total - 50L) {
                timelineView.setPlayheadPosition(0L)
                seekPlayerToTimelineMs(0L)
            }
            val currentPlacement = TimelineOperations.findPlacementAt(clips, timelineView.getPlayheadPosition())
            if (currentPlacement != null) {
                applyClipSpeedToPlayer(currentPlacement.index)
            }
            player.play()
        }
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        playPauseButton.text = if (player.isPlaying) "⏸" else "▶"
    }

    private fun updateTimeLabel(currentMs: Long, totalMs: Long) {
        timeIndicatorText.text =
            "${TimelineOperations.formatDuration(currentMs, true)}/${TimelineOperations.formatDuration(totalMs, true)}"
    }

    private fun updateClipInfoUi() {
        val clip = clips.getOrNull(selectedClipIndex) ?: return
        val speedText = String.format("%.2fx", clip.speed).replace(".00x", ".0x")
        val orientText = if (clip.isPortrait) "세로" else "가로"
        val extra = buildString {
            append("클립 ${selectedClipIndex + 1}/${clips.size} · $orientText · ${TimelineOperations.formatDuration(clip.timelineDurationMs, true)} · $speedText")
            if (abs(clip.rotationDegrees) > 0.5f) append(" · ${clip.rotationDegrees.roundToInt()}°")
            if (abs(clip.scaleFactor - 1.0f) > 0.02f) append(" · ${(clip.scaleFactor * 100).roundToInt()}%")
            if (clip.isMirrored) append(" · 좌우반전")
            if (clip.isMuted) append(" · 음소거")
        }
        clipInfoBadge.text = extra

        if (::speedActionIcon.isInitialized) {
            val speedActive = abs(clip.speed - 1.0f) > 0.01f
            speedActionIcon.setHighlighted(speedActive)
            speedActionLabel.text = if (speedActive) speedText else "배속"
            speedActionLabel.setTextColor(
                if (speedActive) Color.parseColor("#38BDF8") else Color.parseColor("#D8E0F2")
            )
        }
        if (::transformActionIcon.isInitialized) {
            val transformed = isTransformModeActive || abs(clip.rotationDegrees) > 0.5f || abs(clip.scaleFactor - 1.0f) > 0.02f
            transformActionIcon.setHighlighted(transformed)
            transformActionLabel.text = if (abs(clip.rotationDegrees) > 0.5f) {
                "${clip.rotationDegrees.roundToInt()}°·${(clip.scaleFactor * 100).roundToInt()}%"
            } else if (abs(clip.scaleFactor - 1.0f) > 0.02f) {
                "크기 ${(clip.scaleFactor * 100).roundToInt()}%"
            } else {
                "회전/크기"
            }
            transformActionLabel.setTextColor(
                if (transformed) Color.parseColor("#38BDF8") else Color.parseColor("#D8E0F2")
            )
        }
        if (::mirrorActionIcon.isInitialized) {
            mirrorActionIcon.setHighlighted(clip.isMirrored)
            mirrorActionLabel.text = if (clip.isMirrored) "반전됨" else "좌우반전"
            mirrorActionLabel.setTextColor(
                if (clip.isMirrored) Color.parseColor("#38BDF8") else Color.parseColor("#D8E0F2")
            )
        }
        if (::muteActionIcon.isInitialized) {
            muteActionIcon.iconType = if (clip.isMuted) {
                EditorToolIconView.IconType.MUTE_ON
            } else {
                EditorToolIconView.IconType.MUTE_OFF
            }
            muteActionIcon.setHighlighted(clip.isMuted)
            muteActionLabel.text = if (clip.isMuted) "음소거됨" else "음소거"
            muteActionLabel.setTextColor(
                if (clip.isMuted) Color.parseColor("#38BDF8") else Color.parseColor("#D8E0F2")
            )
        }
    }

    private fun showExportDialog() {
        player.pause()
        EditorExportDialogs.showExportOptionsDialog(this, clips, aspectRatioOption)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(progressTicker)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(progressTicker)
        if (::player.isInitialized) {
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(progressTicker)
        if (::player.isInitialized) {
            player.release()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
