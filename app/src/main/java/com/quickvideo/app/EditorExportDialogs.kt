package com.quickvideo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dedicated Export UI Dialog Manager:
 * - Resolution and frame rate selection
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

        val surface = Color.parseColor("#222427")
        val foreground = Color.parseColor("#F0F2F4")
        val secondaryText = Color.parseColor("#C0C5CA")
        val accent = Color.parseColor("#009CE9")
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(surface)
        }
        val scroll = ScrollView(activity).apply { addView(container) }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()

        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(activity).apply {
                this.text = text
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        container.addView(label("동영상 저장", 18f, foreground, bold = true))
        val summary = label("", 12f, secondaryText).apply {
            setPadding(0, dp(16), 0, dp(10))
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val duration = TimelineOperations.formatDuration(TimelineOperations.totalDurationMs(clips), true)
        val muteInfo = when {
            clips.all { it.isMuted } -> " · 전체 음소거"
            clips.any { it.isMuted } -> " · 일부 음소거"
            else -> ""
        }

        fun updateSummary() {
            val (width, height) = VideoExporter.computeOutputDimensions(clips.firstOrNull(), chosenRes, aspectRatioOption)
            val detail = "${width}×${height} · ${chosenFps.fps}fps · $duration$muteInfo"
            summary.text = if (chosenRes == ExportResolution.ORIGINAL) {
                "$detail\n저장본은 새로 인코딩돼요."
            } else {
                detail
            }
        }

        fun <T> addOptions(
            heading: String,
            options: List<T>,
            initial: T,
            titleFor: (T) -> String,
            select: (T) -> Unit
        ) {
            container.addView(label(heading, 13f, secondaryText, bold = true).apply {
                setPadding(0, dp(14), 0, dp(6))
            })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = options.mapIndexed { index, option ->
                label(titleFor(option), 13f, foreground).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                    row.addView(this)
                }
            }

            fun showSelection(selected: T) {
                buttons.forEachIndexed { index, button ->
                    val active = options[index] == selected
                    button.isSelected = active
                    button.background = GradientDrawable().apply {
                        cornerRadius = dp(7).toFloat()
                        setColor(if (active) Color.parseColor("#213C4D") else Color.parseColor("#2C2E31"))
                        if (active) setStroke(dp(1), accent)
                    }
                }
            }

            buttons.forEachIndexed { index, button ->
                button.setOnClickListener {
                    val option = options[index]
                    select(option)
                    showSelection(option)
                    updateSummary()
                }
            }
            showSelection(initial)
            container.addView(row)
        }

        addOptions("해상도", ExportResolution.entries, chosenRes, {
            it.shortSidePx?.let { height -> "${height}p" } ?: "원본 해상도"
        }) { chosenRes = it }
        addOptions("프레임 레이트", ExportFps.entries, chosenFps, { "${it.fps}fps" }) { chosenFps = it }
        updateSummary()
        container.addView(summary)

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actionRow.addView(label("취소", 14f, secondaryText).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { dialog.dismiss() }
        })
        actionRow.addView(label("저장", 14f, Color.parseColor("#071C28"), bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(accent)
            }
            setOnClickListener {
                dialog.dismiss()
                startExportProcess(activity, clips, chosenRes, chosenFps, aspectRatioOption)
            }
        })
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

        box.addView(TextView(activity).apply {
            text = "저장한 영상 보기 ›"
            setTextColor(Color.parseColor("#8FD8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#566775"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            setOnClickListener {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(savedUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(viewIntent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, "갤러리의 QuickVideo 앨범에서 저장한 영상을 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
            }
        })

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
