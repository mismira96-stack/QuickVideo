package com.quickvideo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Dedicated Export UI Dialog Manager:
 * - Resolution (720p / 1080p / 4K) & Frame Rate (24 / 30 / 60fps) selection modal
 * - Live progress modal with cancel support
 * - Export complete modal with instant share support
 */
object EditorExportDialogs {

    fun showExportOptionsDialog(
        activity: Activity,
        clips: List<VideoClip>,
        aspectRatioOption: AspectRatioOption
    ) {
        var chosenRes = ExportResolution.FHD_1080P
        var chosenFps = ExportFps.FPS_30

        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            setBackgroundColor(Color.parseColor("#181D2C"))
        }

        val header = TextView(activity).apply {
            text = "동영상 추출하기 (저장)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(header)

        val summary = TextView(activity).apply {
            setTextColor(Color.parseColor("#A5B2CC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, dp(16))
        }
        container.addView(summary)

        fun updateExportSummary() {
            val total = TimelineOperations.formatDuration(TimelineOperations.totalDurationMs(clips), true)
            val (outW, outH) = VideoExporter.computeOutputDimensions(clips.firstOrNull(), chosenRes, aspectRatioOption)
            val orientLabel = if (outH >= outW) "세로" else "가로"
            val muteInfo = if (clips.all { it.isMuted }) " · 전체 음소거" else if (clips.any { it.isMuted }) " · 일부 음소거" else ""
            summary.text = "총 ${clips.size}개 클립 · 길이 $total · ${outW}×${outH} ($orientLabel)$muteInfo"
        }

        val resLabel = TextView(activity).apply {
            text = "해상도 선택"
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(resLabel)

        val resButtons = mutableListOf<Pair<ExportResolution, LinearLayout>>()
        fun refreshResSelection() {
            for ((res, card) in resButtons) {
                val selected = res == chosenRes
                card.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) Color.parseColor("#332979FF") else Color.parseColor("#252B3D"))
                    setStroke(dp(if (selected) 2 else 1), if (selected) Color.parseColor("#38BDF8") else Color.parseColor("#353F5A"))
                }
            }
            updateExportSummary()
        }

        for (res in ExportResolution.entries) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                val t1 = TextView(activity).apply {
                    text = res.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val t2 = TextView(activity).apply {
                    text = res.desc
                    setTextColor(Color.parseColor("#9DA8C0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                }
                addView(t1)
                addView(t2)
                setOnClickListener {
                    chosenRes = res
                    refreshResSelection()
                }
            }
            resButtons.add(res to card)
            container.addView(card)
        }
        refreshResSelection()

        val fpsLabel = TextView(activity).apply {
            text = "프레임 레이트"
            setTextColor(Color.parseColor("#D8E0F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }
        container.addView(fpsLabel)

        val fpsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }
        val fpsButtons = mutableListOf<Pair<ExportFps, TextView>>()
        fun refreshFpsSelection() {
            for ((f, btn) in fpsButtons) {
                val selected = f == chosenFps
                btn.setTextColor(Color.WHITE)
                btn.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) Color.parseColor("#2979FF") else Color.parseColor("#252B3D"))
                    if (selected) setStroke(dp(1), Color.parseColor("#38BDF8"))
                }
            }
        }

        for (fps in ExportFps.entries) {
            val btn = TextView(activity).apply {
                text = fps.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    chosenFps = fps
                    refreshFpsSelection()
                }
            }
            fpsButtons.add(fps to btn)
            fpsRow.addView(btn)
        }
        refreshFpsSelection()
        container.addView(fpsRow)

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .create()

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = TextView(activity).apply {
            text = "취소"
            setTextColor(Color.parseColor("#A5A8BC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { dialog.dismiss() }
        }
        actionRow.addView(cancelBtn)

        val startExportBtn = TextView(activity).apply {
            text = "지금 저장하기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#2979FF"))
            }
            setOnClickListener {
                dialog.dismiss()
                startExportProcess(activity, clips, chosenRes, chosenFps, aspectRatioOption)
            }
        }
        actionRow.addView(startExportBtn)
        container.addView(actionRow)

        dialog.show()
    }

    private fun startExportProcess(
        activity: Activity,
        clips: List<VideoClip>,
        resolution: ExportResolution,
        fps: ExportFps,
        aspectRatioOption: AspectRatioOption
    ) {
        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(26), dp(28), dp(22))
            setBackgroundColor(Color.parseColor("#181D2C"))
        }

        val title = TextView(activity).apply {
            text = "동영상을 저장하고 있습니다..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(title)

        val percentView = TextView(activity).apply {
            text = "0%"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(10))
        }
        box.addView(percentView)

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
            ).apply {
                bottomMargin = dp(18)
            }
        }
        box.addView(bar)

        var cancelExport: (() -> Unit)? = null
        val progressDialog = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .create()

        val cancelBtn = TextView(activity).apply {
            text = "취소"
            setTextColor(Color.parseColor("#AAAEC2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                cancelExport?.invoke()
                progressDialog.dismiss()
                Toast.makeText(activity, "저장이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(cancelBtn)
        progressDialog.show()

        cancelExport = VideoExporter.exportProject(
            context = activity,
            clips = clips,
            resolution = resolution,
            fps = fps,
            aspectRatio = aspectRatioOption,
            callback = object : VideoExporter.Callback {
                override fun onProgress(percent: Int) {
                    percentView.text = "${percent}%"
                    bar.progress = percent
                }

                override fun onSuccess(savedUri: Uri, fileName: String) {
                    progressDialog.dismiss()
                    showExportCompleteDialog(activity, savedUri, fileName)
                }

                override fun onError(message: String) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showExportCompleteDialog(activity: Activity, savedUri: Uri, fileName: String) {
        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(20))
            setBackgroundColor(Color.parseColor("#181D2C"))
        }

        val checkIcon = TextView(activity).apply {
            text = "✓"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2979FF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                bottomMargin = dp(14)
            }
        }
        box.addView(checkIcon)

        val title = TextView(activity).apply {
            text = "갤러리에 저장 완료!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(title)

        val sub = TextView(activity).apply {
            text = "Movies/QuickVideo/$fileName"
            setTextColor(Color.parseColor("#9DA8C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(20))
        }
        box.addView(sub)

        val dialog = AlertDialog.Builder(activity)
            .setView(box)
            .create()

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val shareBtn = TextView(activity).apply {
            text = "공유하기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#283046"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(10)
            }
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "동영상 공유"))
            }
        }
        btnRow.addView(shareBtn)

        val doneBtn = TextView(activity).apply {
            text = "확인"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(10), dp(24), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#2979FF"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(doneBtn)
        box.addView(btnRow)

        dialog.show()
    }
}
