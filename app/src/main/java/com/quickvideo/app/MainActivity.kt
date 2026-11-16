package com.quickvideo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var permissionButton: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var bottomDock: LinearLayout
    private lateinit var selectedTrayRow: LinearLayout
    private lateinit var selectedSummaryText: TextView
    private lateinit var startEditButton: TextView
    private lateinit var clearSelectionButton: TextView
    private lateinit var videoCountBadge: TextView

    private val selectedVideos = mutableListOf<GalleryVideoItem>()
    private var galleryItems: List<GalleryListItem> = emptyList()
    private lateinit var adapter: DateGroupedVideoAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadVideos()
    }

    private val systemPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            launchEditorWithUris(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (hasVideoPermission() && galleryItems.isEmpty()) {
            loadVideos()
        }
    }

    private fun setupUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#10131C"))
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Header Bar (VLLO dark clean style with Blue/Yellow theme)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#161A26"))
        }

        val brandDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(8)
            }
        }
        topBar.addView(brandDot)

        val titleView = TextView(this).apply {
            setText(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        topBar.addView(titleView)

        videoCountBadge = TextView(this).apply {
            text = "전체 동영상"
            setTextColor(Color.parseColor("#8FA0C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(videoCountBadge)

        val pickerBtn = TextView(this).apply {
            text = "+ 파일 선택"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#23293B"))
                setStroke(dp(1), Color.parseColor("#353F5A"))
            }
            setOnClickListener {
                systemPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        }
        topBar.addView(pickerBtn)

        mainColumn.addView(topBar)

        // Subtitle instruction bar
        val subHintBar = TextView(this).apply {
            val hint = "날짜별 동영상을 1개 또는 여러 개 순서대로 눌러 바로 편집하세요"
            val policyLabel = getString(R.string.privacy_policy)
            text = SpannableString("$hint\n$policyLabel").apply {
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val policyUri = Uri.parse(getString(R.string.privacy_policy_url))
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, policyUri))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, R.string.privacy_policy_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, hint.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(Color.parseColor("#9BA7C0"))
            setLinkTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            includeFontPadding = false
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.parseColor("#131722"))
        }
        mainColumn.addView(subHintBar)

        // Content area
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        adapter = DateGroupedVideoAdapter(
            context = this,
            onVideoClick = { toggleVideoSelection(it) },
            selectionOrderProvider = { item ->
                val idx = selectedVideos.indexOfFirst { it.id == item.id && it.uri == item.uri }
                if (idx >= 0) idx + 1 else null
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.getItemViewType(position) == DateGroupedVideoAdapter.TYPE_HEADER) 3 else 1
                }
            }
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(6), dp(156))
        }
        contentFrame.addView(recyclerView)

        loadingBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
            visibility = View.GONE
        }
        contentFrame.addView(loadingBar)

        emptyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        emptyTitle = TextView(this).apply {
            text = getString(R.string.permission_required)
            setTextColor(Color.parseColor("#D0D6E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        emptyContainer.addView(emptyTitle)

        permissionButton = TextView(this).apply {
            text = getString(R.string.grant_permission)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#2979FF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            setOnClickListener { requestVideoPermission() }
        }
        emptyContainer.addView(permissionButton)
        contentFrame.addView(emptyContainer)

        mainColumn.addView(contentFrame)
        root.addView(mainColumn)

        // VLLO-style Bottom Multi-Clip Tray & Start Button Dock
        bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5181D2C"))
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), Color.parseColor("#2B344D"))
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val trayScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        selectedTrayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        trayScroll.addView(selectedTrayRow)
        bottomDock.addView(trayScroll)

        val bottomActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        selectedSummaryText = TextView(this).apply {
            text = "1개 선택됨"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomActionRow.addView(selectedSummaryText)

        clearSelectionButton = TextView(this).apply {
            text = "전체 해제"
            setTextColor(Color.parseColor("#A4B0C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
            setOnClickListener {
                selectedVideos.clear()
                updateSelectionUi()
            }
        }
        bottomActionRow.addView(clearSelectionButton)

        startEditButton = TextView(this).apply {
            text = "편집 시작  ➔"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(21).toFloat()
                setColor(Color.parseColor("#2979FF"))
            }
            setOnClickListener {
                if (selectedVideos.isNotEmpty()) {
                    launchEditorWithVideos(selectedVideos)
                }
            }
        }
        bottomActionRow.addView(startEditButton)
        bottomDock.addView(bottomActionRow)

        root.addView(bottomDock)

        // Apply system status bar & navigation bar insets so UI never overlaps nav bar
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(
                dp(16) + sysBars.left,
                dp(12) + sysBars.top,
                dp(16) + sysBars.right,
                dp(12)
            )
            subHintBar.setPadding(
                dp(16) + sysBars.left,
                dp(9),
                dp(16) + sysBars.right,
                dp(9)
            )
            recyclerView.setPadding(
                dp(6) + sysBars.left,
                dp(4),
                dp(6) + sysBars.right,
                dp(156) + sysBars.bottom
            )
            bottomDock.setPadding(
                dp(16) + sysBars.left,
                dp(12),
                dp(16) + sysBars.right,
                dp(14) + sysBars.bottom
            )
            windowInsets
        }

        setContentView(root)
    }

    private fun hasVideoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestVideoPermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    private fun checkPermissionAndLoad() {
        if (hasVideoPermission()) {
            loadVideos()
        } else {
            emptyContainer.visibility = View.VISIBLE
            emptyTitle.text = getString(R.string.permission_required)
            permissionButton.text = getString(R.string.grant_permission)
            permissionButton.setOnClickListener { requestVideoPermission() }
            requestVideoPermission()
        }
    }

    private fun loadVideos() {
        loadingBar.visibility = View.VISIBLE
        emptyContainer.visibility = View.GONE
        lifecycleScope.launch {
            val items = VideoRepository.loadVideosGroupedByDate(this@MainActivity)
            loadingBar.visibility = View.GONE
            galleryItems = items
            val videoCount = items.count { it is GalleryListItem.VideoEntry }
            videoCountBadge.text = "전체 ${videoCount}개"

            if (items.isEmpty()) {
                emptyContainer.visibility = View.VISIBLE
                emptyTitle.text = "기기에 저장된 동영상이 없습니다.\n우측 상단 '+ 파일 선택'으로 영상을 불러올 수 있습니다."
                permissionButton.text = "동영상 파일 직접 선택"
                permissionButton.setOnClickListener {
                    systemPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
            } else {
                emptyContainer.visibility = View.GONE
                adapter.submitList(items)
            }
        }
    }

    private fun toggleVideoSelection(video: GalleryVideoItem) {
        val existingIdx = selectedVideos.indexOfFirst { it.id == video.id && it.uri == video.uri }
        if (existingIdx >= 0) {
            selectedVideos.removeAt(existingIdx)
        } else {
            selectedVideos.add(video)
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        adapter.notifyDataSetChanged()
        if (selectedVideos.isEmpty()) {
            bottomDock.visibility = View.GONE
            return
        }
        bottomDock.visibility = View.VISIBLE
        val totalDurationMs = selectedVideos.sumOf { it.durationMs }
        selectedSummaryText.text =
            "${selectedVideos.size}개 선택됨 · 총 ${TimelineOperations.formatDuration(totalDurationMs)}"

        selectedTrayRow.removeAllViews()
        selectedVideos.forEachIndexed { idx, video ->
            val chip = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply {
                    rightMargin = dp(8)
                }
            }
            val thumbView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#2A2A36"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            chip.addView(thumbView)
            lifecycleScope.launch {
                val u = video.uri ?: return@launch
                val bmp = VideoRepository.loadThumbnail(this@MainActivity, u, 160, 160)
                if (bmp != null) thumbView.setImageBitmap(bmp)
            }

            val orderBadge = TextView(this).apply {
                text = "${idx + 1}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2979FF"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(3), dp(3), 0, 0)
                }
            }
            chip.addView(orderBadge)

            val removeBadge = TextView(this).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#B3000000"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(3), dp(3), 0)
                }
                setOnClickListener {
                    selectedVideos.removeAt(idx)
                    updateSelectionUi()
                }
            }
            chip.addView(removeBadge)
            selectedTrayRow.addView(chip)
        }
    }

    private fun launchEditorWithVideos(videos: List<GalleryVideoItem>) {
        val uris = ArrayList<Uri>(videos.mapNotNull { it.uri })
        launchEditorWithUris(uris)
    }

    private fun launchEditorWithUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, "편집할 영상을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditorActivity::class.java).apply {
            putStringArrayListExtra(EditorActivity.EXTRA_VIDEO_URI_STRINGS, ArrayList(uris.map { it.toString() }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

private class DateGroupedVideoAdapter(
    private val context: Context,
    private val onVideoClick: (GalleryVideoItem) -> Unit,
    private val selectionOrderProvider: (GalleryVideoItem) -> Int?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_VIDEO = 1
    }

    private var items: List<GalleryListItem> = emptyList()

    fun submitList(newItems: List<GalleryListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GalleryListItem.DateHeader -> TYPE_HEADER
        is GalleryListItem.VideoEntry -> TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(16), dp(10), dp(8))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val count = TextView(context).apply {
                setTextColor(Color.parseColor("#8E92A4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(title)
            row.addView(count)
            HeaderViewHolder(row, title, count)
        } else {
            val cellSize = (context.resources.displayMetrics.widthPixels - dp(24)) / 3
            val frame = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(cellSize, cellSize).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setBackgroundColor(Color.parseColor("#20202A"))
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val dimOverlay = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val durationBadge = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#99000000"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
            }
            val orderBadge = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            }
            frame.addView(image)
            frame.addView(dimOverlay)
            frame.addView(durationBadge)
            frame.addView(orderBadge)
            VideoViewHolder(frame, image, dimOverlay, durationBadge, orderBadge)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GalleryListItem.DateHeader -> {
                val vh = holder as HeaderViewHolder
                vh.title.text = item.dateLabel
                vh.count.text = "${item.count}개"
            }
            is GalleryListItem.VideoEntry -> {
                val vh = holder as VideoViewHolder
                val video = item.video
                vh.durationBadge.text = TimelineOperations.formatDuration(video.durationMs)
                val order = selectionOrderProvider(video)

                if (order != null) {
                    vh.dimOverlay.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#402979FF"))
                        setStroke(dp(3), Color.parseColor("#38BDF8"))
                    }
                    vh.orderBadge.text = order.toString()
                    vh.orderBadge.setTextColor(Color.WHITE)
                    vh.orderBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#2979FF"))
                    }
                } else {
                    vh.dimOverlay.background = null
                    vh.orderBadge.text = ""
                    vh.orderBadge.setTextColor(Color.WHITE)
                    vh.orderBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#66000000"))
                        setStroke(dp(1), Color.parseColor("#CCFFFFFF"))
                    }
                }

                vh.image.setImageBitmap(null)
                vh.loadJob?.cancel()
                if (context is AppCompatActivity) {
                    val u = video.uri
                    if (u != null) {
                        vh.loadJob = context.lifecycleScope.launch {
                            val bmp = VideoRepository.loadThumbnail(context, u, 260, 260)
                            if (bmp != null) {
                                vh.image.setImageBitmap(bmp)
                            }
                        }
                    }
                }

                vh.itemView.setOnClickListener {
                    onVideoClick(video)
                }
            }
        }
    }

    private class HeaderViewHolder(
        view: View,
        val title: TextView,
        val count: TextView
    ) : RecyclerView.ViewHolder(view)

    private class VideoViewHolder(
        view: View,
        val image: ImageView,
        val dimOverlay: View,
        val durationBadge: TextView,
        val orderBadge: TextView,
        var loadJob: Job? = null
    ) : RecyclerView.ViewHolder(view)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
