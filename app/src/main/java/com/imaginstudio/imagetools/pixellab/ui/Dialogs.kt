package com.imaginstudio.imagetools.pixellab.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.imaginstudio.imagetools.pixellab.R
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.model.AppDefaults
import com.imaginstudio.imagetools.pixellab.model.AssetRepository
import com.imaginstudio.imagetools.pixellab.model.AssetTextStyle
import com.imaginstudio.imagetools.pixellab.model.BackgroundType
import com.imaginstudio.imagetools.pixellab.model.EffectsState
import com.imaginstudio.imagetools.pixellab.model.GradientDirection
import com.imaginstudio.imagetools.pixellab.model.GradientFill
import com.imaginstudio.imagetools.pixellab.model.GradientStop
import com.imaginstudio.imagetools.pixellab.model.JsonPresetCodec
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.SavedTextStyle
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object Dialogs {

    fun showColorPickerDialog(
        context: Context,
        initialColor: Int,
        project: ProjectState? = null,
        onLiveChange: ((Int) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: (Int) -> Unit
    ) {
        ColorAndGradientDialogs.showColorPickerDialog(
            context = context,
            initialColor = initialColor,
            project = project,
            onLiveChange = onLiveChange,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()

    private fun styleEditText(context: Context, et: EditText, dark: Boolean) {
        et.setTextColor(ThemeManager.textPrimary(dark))
        et.setHintTextColor(ThemeManager.textSecondary(dark))
        et.background = ThemeManager.inputDrawable(context, dark, 8)
        et.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
    }

    private fun styleAlertDialog(context: Context, dlg: AlertDialog, dark: Boolean) {
        dlg.window?.setBackgroundDrawable(ThemeManager.dialogCardDrawable(context, dark, 12))
        dlg.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            ?.setTextColor(ThemeManager.textPrimary(dark))
        dlg.findViewById<TextView>(android.R.id.message)
            ?.setTextColor(ThemeManager.textSecondary(dark))
        val accent = ThemeManager.accentPrimary(dark)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ThemeManager.accentBlue(dark))
    }

    fun showTextEditorDialog(
        context: Context,
        initialText: String,
        onConfirm: (String) -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 4))
        }
        val edit = EditText(context).apply {
            setText(initialText)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setSelection(text.length)
            styleEditText(context, this, dark)
        }
        val toolsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 8), 0, 0)
        }
        fun makeActionChip(label: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.textPrimary(dark))
                gravity = Gravity.CENTER
                background = ThemeManager.pillDrawable(context, dark, active = false, radiusDp = 8)
                setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(context, 8) }
                setOnClickListener { onClick() }
            }
        }
        val btnCase = makeActionChip("TT") {
            val cur = edit.text.toString()
            edit.setText(if (cur == cur.uppercase()) cur.lowercase() else cur.uppercase())
            edit.setSelection(edit.text.length)
        }
        val btnClear = makeActionChip("Clear") {
            edit.setText("")
        }
        toolsRow.addView(btnCase)
        toolsRow.addView(btnClear)
        container.addView(edit)
        container.addView(toolsRow)

        val dlg = AlertDialog.Builder(context)
            .setTitle("Edit Text")
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                onConfirm(edit.text.toString().ifBlank { "New Text" })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleAlertDialog(context, dlg, dark)
    }

    fun showFontPickerDialog(
        context: Context,
        currentFont: String,
        sampleText: String,
        onSelect: (String) -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        val allFonts = AssetRepository.loadFonts(context)
        val categories = listOf("All") + allFonts.map { it.category }.distinct()
        var selectedCategory = "All"

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }
        val catScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(context, 6))
        }
        val catRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 340))
            layoutManager = LinearLayoutManager(context)
        }
        var dialog: AlertDialog? = null
        val previewLabel = sampleText.replace("\n", " ").take(24).ifBlank { "Abcdefg 123" }

        class FontVH(val row: LinearLayout, val title: TextView, val preview: TextView) :
            RecyclerView.ViewHolder(row)

        val fontAdapter = object : RecyclerView.Adapter<FontVH>() {
            private var items: List<com.imaginstudio.imagetools.pixellab.model.FontEntry> = allFonts

            fun submit(newItems: List<com.imaginstudio.imagetools.pixellab.model.FontEntry>) {
                items = newItems
                notifyDataSetChanged()
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FontVH {
                val wrapper = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                val content = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
                }
                val title = TextView(parent.context).apply {
                    textSize = 11f
                    setTextColor(ThemeManager.textSecondary(dark))
                }
                val preview = TextView(parent.context).apply {
                    textSize = 20f
                    setTextColor(ThemeManager.textPrimary(dark))
                }
                content.addView(title)
                content.addView(preview)
                wrapper.addView(content)
                wrapper.addView(View(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))
                    setBackgroundColor(ThemeManager.dividerColor(dark))
                })
                return FontVH(wrapper, title, preview)
            }

            override fun getItemCount(): Int = items.size

            override fun onBindViewHolder(holder: FontVH, position: Int) {
                val entry = items[position]
                val selected = entry.fontKey == currentFont || entry.displayName == currentFont
                holder.row.getChildAt(0).setBackgroundColor(
                    if (selected) ThemeManager.accentSoftBg(dark) else ThemeManager.cardBg(dark)
                )
                holder.title.text = "${entry.displayName} (${entry.category})"
                holder.preview.text = previewLabel
                holder.preview.typeface = AssetRepository.resolveTypeface(context, entry.fontKey)
                holder.row.setOnClickListener {
                    onSelect(entry.fontKey)
                    dialog?.dismiss()
                }
            }
        }
        recycler.adapter = fontAdapter

        val catButtons = ArrayList<Pair<String, TextView>>()
        fun refreshCatChips() {
            for ((cat, tv) in catButtons) {
                val active = cat == selectedCategory
                tv.background = ThemeManager.pillDrawable(context, dark, active = active, radiusDp = 8)
                tv.setTextColor(if (active) ThemeManager.accentPrimary(dark) else ThemeManager.textPrimary(dark))
            }
        }

        for (cat in categories) {
            val btn = TextView(context).apply {
                text = cat
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(context, 6) }
                setOnClickListener {
                    selectedCategory = cat
                    refreshCatChips()
                    val filtered = if (cat == "All") allFonts else allFonts.filter { it.category == cat }
                    fontAdapter.submit(filtered)
                }
            }
            catButtons.add(cat to btn)
            catRow.addView(btn)
        }
        refreshCatChips()
        catScroll.addView(catRow)
        root.addView(catScroll)
        root.addView(recycler)

        dialog = AlertDialog.Builder(context)
            .setTitle("Fonts (${allFonts.size})")
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog?.let { styleAlertDialog(context, it, dark) }
    }

    fun showGradientMakerDialog(
        context: Context,
        initial: GradientFill,
        project: ProjectState? = null,
        onLiveChange: ((GradientFill) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: (GradientFill) -> Unit
    ) {
        ColorAndGradientDialogs.showGradientMakerDialog(
            context = context,
            initial = initial,
            project = project,
            onLiveChange = onLiveChange,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }

    private data class ImageSizeOption(
        val label: String,
        val ratioText: String,
        val width: Int,
        val height: Int,
        val aspectW: Float,
        val aspectH: Float,
        val badgeColor: Int,
        val badgeChar: String? = null
    )

    private fun createRatioPreviewIcon(context: Context, opt: ImageSizeOption): android.graphics.drawable.BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (24 * density).roundToInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val maxSide = size * 0.78f
        val r = opt.aspectW / opt.aspectH.coerceAtLeast(0.01f)
        val boxW: Float
        val boxH: Float
        if (r >= 1f) {
            boxW = maxSide
            boxH = (maxSide / r).coerceAtLeast(size * 0.24f)
        } else {
            boxH = maxSide
            boxW = (maxSide * r).coerceAtLeast(size * 0.24f)
        }
        val left = (size - boxW) / 2f
        val top = (size - boxH) / 2f
        val rect = android.graphics.RectF(left, top, left + boxW, top + boxH)
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (opt.badgeChar != null) opt.badgeColor else Color.parseColor("#EBF4FF")
            style = android.graphics.Paint.Style.FILL
        }
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = opt.badgeColor
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.6f * density
        }
        val rad = 2.5f * density
        c.drawRoundRect(rect, rad, rad, fillPaint)
        c.drawRoundRect(rect, rad, rad, strokePaint)

        if (opt.badgeChar != null) {
            val txtPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = boxH * 0.62f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val fm = txtPaint.fontMetrics
            val ty = rect.centerY() - (fm.ascent + fm.descent) / 2f
            c.drawText(opt.badgeChar, rect.centerX(), ty, txtPaint)
        }
        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }

    fun showImageSizeDialog(
        context: Context,
        currentW: Int,
        currentH: Int,
        onConfirm: (Int, Int) -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        val dp = context.resources.displayMetrics.density
        fun dpPx(v: Int) = (v * dp).roundToInt()

        val socialPresets = listOf(
            ImageSizeOption("Custom", "${currentW}×${currentH}", currentW, currentH, 1f, 1f, Color.parseColor("#64748B")),
            ImageSizeOption("Square", "1:1", 1280, 1280, 1f, 1f, Color.parseColor("#2693B5")),
            ImageSizeOption("Profile Picture (Square)", "1:1", 1024, 1024, 1f, 1f, Color.parseColor("#3B82F6"), "P"),
            ImageSizeOption("YouTube Channel Banner", "16:9", 2560, 1440, 16f, 9f, Color.parseColor("#DC2626"), "▶"),
            ImageSizeOption("YouTube Thumbnail", "16:9", 1280, 720, 16f, 9f, Color.parseColor("#EF4444"), "▶"),
            ImageSizeOption("Facebook Cover Art", "2.7:1", 851, 315, 2.7f, 1f, Color.parseColor("#1877F2"), "f"),
            ImageSizeOption("Google+ Cover Photo", "16:9", 2120, 1192, 16f, 9f, Color.parseColor("#EA4335"), "G"),
            ImageSizeOption("Twitter Header Size", "3:1", 1500, 500, 3f, 1f, Color.parseColor("#0EA5E9"), "t")
        )

        val universalRatios = listOf(
            ImageSizeOption("Custom", "Free", currentW, currentH, currentW.toFloat(), currentH.toFloat(), Color.parseColor("#64748B")),
            ImageSizeOption("1 : 1", "Square", 1280, 1280, 1f, 1f, Color.parseColor("#2693B5")),
            ImageSizeOption("2 : 1", "Wide", 1600, 800, 2f, 1f, Color.parseColor("#2693B5")),
            ImageSizeOption("1 : 2", "Tall", 800, 1600, 1f, 2f, Color.parseColor("#2693B5")),
            ImageSizeOption("3 : 4", "Portrait", 960, 1280, 3f, 4f, Color.parseColor("#2693B5")),
            ImageSizeOption("4 : 3", "Standard", 1280, 960, 4f, 3f, Color.parseColor("#2693B5")),
            ImageSizeOption("16 : 9", "Landscape", 1920, 1080, 16f, 9f, Color.parseColor("#2693B5")),
            ImageSizeOption("9 : 16", "Portrait", 1080, 1920, 9f, 16f, Color.parseColor("#2693B5"))
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(18), dpPx(12), dpPx(18), dpPx(6))
        }

        var updatingProgrammatically = false
        var activeRatioFactor: Float? = null

        val etW = EditText(context).apply {
            hint = "Width"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentW.toString())
            styleEditText(context, this, dark)
        }
        val etH = EditText(context).apply {
            hint = "Height"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentH.toString())
            styleEditText(context, this, dark)
        }

        val socialItems = socialPresets.mapIndexed { idx, opt ->
            CompactDropdown.Item(
                id = idx.toString(),
                label = "${opt.label} (${opt.ratioText})",
                displayLabel = "${opt.label} — ${opt.ratioText}"
            )
        }
        val ratioItems = universalRatios.mapIndexed { idx, opt ->
            CompactDropdown.Item(
                id = idx.toString(),
                label = "${opt.label} (${opt.ratioText})",
                displayLabel = "${opt.label} — ${opt.ratioText}"
            )
        }

        lateinit var socialDropdown: CompactDropdown.Controller
        lateinit var ratioDropdown: CompactDropdown.Controller

        socialDropdown = CompactDropdown.create(context, socialItems, "0") { item ->
            val pos = item.id.toIntOrNull() ?: 0
            if (updatingProgrammatically || pos == 0) return@create
            val opt = socialPresets[pos]
            updatingProgrammatically = true
            activeRatioFactor = null
            ratioDropdown.selectById("0", notify = false)
            etW.setText(opt.width.toString())
            etH.setText(opt.height.toString())
            updatingProgrammatically = false
        }

        ratioDropdown = CompactDropdown.create(context, ratioItems, "0") { item ->
            val pos = item.id.toIntOrNull() ?: 0
            if (updatingProgrammatically) return@create
            if (pos == 0) {
                activeRatioFactor = null
                return@create
            }
            val opt = universalRatios[pos]
            updatingProgrammatically = true
            activeRatioFactor = opt.aspectW / opt.aspectH.coerceAtLeast(0.01f)
            socialDropdown.selectById("0", notify = false)
            etW.setText(opt.width.toString())
            etH.setText(opt.height.toString())
            updatingProgrammatically = false
        }

        etW.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updatingProgrammatically) return
                val ratio = activeRatioFactor ?: return
                val wVal = s?.toString()?.toIntOrNull() ?: return
                updatingProgrammatically = true
                etH.setText((wVal / ratio).roundToInt().coerceIn(64, 4096).toString())
                updatingProgrammatically = false
            }
        })

        etH.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updatingProgrammatically) return
                val ratio = activeRatioFactor ?: return
                val hVal = s?.toString()?.toIntOrNull() ?: return
                updatingProgrammatically = true
                etW.setText((hVal * ratio).roundToInt().coerceIn(64, 4096).toString())
                updatingProgrammatically = false
            }
        })

        root.addView(TextView(context).apply {
            text = "Preset"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.textSecondary(dark))
            setPadding(0, 0, 0, dpPx(4))
        })
        root.addView(socialDropdown.trigger)

        root.addView(TextView(context).apply {
            text = "Aspect Ratio"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.textSecondary(dark))
            setPadding(0, dpPx(10), 0, dpPx(4))
        })
        root.addView(ratioDropdown.trigger)

        val dimsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpPx(12), 0, 0)
        }
        val colW = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Width"
                textSize = 11.5f
                setTextColor(ThemeManager.textSecondary(dark))
                setPadding(0, 0, 0, dpPx(3))
            })
            addView(etW)
        }
        val swapBtn = TextView(context).apply {
            text = "⇄"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.accentBlue(dark))
            gravity = Gravity.CENTER
            setPadding(dpPx(10), dpPx(16), dpPx(10), dpPx(4))
            setOnClickListener {
                val curWStr = etW.text.toString()
                val curHStr = etH.text.toString()
                updatingProgrammatically = true
                activeRatioFactor = activeRatioFactor?.let { 1f / it }
                etW.setText(curHStr)
                etH.setText(curWStr)
                updatingProgrammatically = false
            }
        }
        val colH = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Height"
                textSize = 11.5f
                setTextColor(ThemeManager.textSecondary(dark))
                setPadding(0, 0, 0, dpPx(3))
            })
            addView(etH)
        }
        dimsRow.addView(colW)
        dimsRow.addView(swapBtn)
        dimsRow.addView(colH)
        root.addView(dimsRow)

        val dlg = AlertDialog.Builder(context)
            .setTitle("Image Size")
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val nw = etW.text.toString().toIntOrNull()?.coerceIn(120, 4096) ?: currentW
                val nh = etH.text.toString().toIntOrNull()?.coerceIn(120, 4096) ?: currentH
                onConfirm(nw, nh)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleAlertDialog(context, dlg, dark)
    }

    fun showExportDialog(
        context: Context,
        project: ProjectState
    ) {
        val dark = ThemeManager.isDark(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 6))
        }

        val formats = context.resources.getStringArray(R.array.image_format)
        val formatItems = formats.mapIndexed { idx, label ->
            CompactDropdown.Item(id = idx.toString(), label = label, displayLabel = label)
        }
        val initFmtId = if (project.background.type == BackgroundType.TRANSPARENT) "1" else "0"
        val formatDropdown = CompactDropdown.create(context, formatItems, initFmtId) { }

        val etW = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(project.width.toString())
            styleEditText(context, this, dark)
        }
        val etH = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(project.height.toString())
            styleEditText(context, this, dark)
        }

        val aspect = project.width.toFloat() / project.height.toFloat().coerceAtLeast(1f)
        val presets = context.resources.getStringArray(R.array.quality_presets)
        val qualityItems = presets.mapIndexed { idx, label ->
            CompactDropdown.Item(id = idx.toString(), label = label, displayLabel = label)
        }
        val qualityDropdown = CompactDropdown.create(context, qualityItems, "0") { item ->
            val pos = item.id.toIntOrNull() ?: 0
            val targetBase = when (pos) {
                0 -> project.width
                2 -> AppDefaults.DIM_PRESET_LOW
                3 -> AppDefaults.DIM_PRESET_DEFAULT
                4 -> AppDefaults.DIM_PRESET_HIGH
                5 -> AppDefaults.DIM_PRESET_VERY_HIGH
                6 -> AppDefaults.DIM_PRESET_ULTRA
                else -> null
            }
            if (targetBase != null) {
                val w = if (pos == 0) project.width else targetBase
                val h = if (pos == 0) project.height else (targetBase / aspect).roundToInt().coerceAtLeast(64)
                etW.setText(w.toString())
                etH.setText(h.toString())
            }
        }

        fun makeFieldLabel(textStr: String, topDp: Int = 0): TextView {
            return TextView(context).apply {
                text = textStr
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.textSecondary(dark))
                setPadding(0, dp(context, topDp), 0, dp(context, 4))
            }
        }

        root.addView(makeFieldLabel("Image Format", 0))
        root.addView(formatDropdown.trigger)
        root.addView(makeFieldLabel("Quality / Dimensions", 10))
        root.addView(qualityDropdown.trigger)
        root.addView(makeFieldLabel("Width (px)", 10))
        root.addView(etW)
        root.addView(makeFieldLabel("Height (px)", 8))
        root.addView(etH)

        fun renderOutput(isPng: Boolean, w: Int, h: Int): Bitmap {
            val bmp = PixelLabRenderer.exportToBitmap(context.applicationContext, project, w, h, includeBackground = true)
            if (!isPng && project.background.type == BackgroundType.TRANSPARENT) {
                val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(flat)
                c.drawColor(Color.WHITE)
                c.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
                return flat
            }
            return bmp
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val dlg = AlertDialog.Builder(context)
            .setTitle("Export Image")
            .setView(root)
            .setPositiveButton("Save to Gallery") { _, _ ->
                val isPng = formatDropdown.selectedId == "1"
                val w = etW.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: project.width
                val h = etH.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: project.height
                Toast.makeText(context, "Saving image…", Toast.LENGTH_SHORT).show()
                val exec = Executors.newSingleThreadExecutor()
                val appCtx = context.applicationContext
                exec.execute {
                    val result = runCatching {
                        val bmp = renderOutput(isPng, w, h)
                        val ok = saveBitmapToGallery(appCtx, bmp, isPng)
                        bmp.recycle()
                        ok
                    }
                    mainHandler.post {
                        val msg = result.fold(
                            onSuccess = { ok -> if (ok) "Saved to Pictures/PixelLab" else "Failed to save image" },
                            onFailure = { "Image too large for device memory" }
                        )
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    exec.shutdown()
                }
            }
            .setNeutralButton("Share") { _, _ ->
                val isPng = formatDropdown.selectedId == "1"
                val w = etW.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: project.width
                val h = etH.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: project.height
                val exec = Executors.newSingleThreadExecutor()
                val appCtx = context.applicationContext
                exec.execute {
                    val intentRes = runCatching {
                        val bmp = renderOutput(isPng, w, h)
                        val shareIntent = createShareBitmapIntent(appCtx, bmp, isPng)
                        bmp.recycle()
                        shareIntent
                    }
                    mainHandler.post {
                        val shareIntent = intentRes.getOrNull()
                        if (shareIntent != null) {
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Share Design"))
                            }.onFailure {
                                Toast.makeText(context, "Unable to share image", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Unable to export image", Toast.LENGTH_SHORT).show()
                        }
                    }
                    exec.shutdown()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleAlertDialog(context, dlg, dark)
    }

    private fun saveBitmapToGallery(context: Context, bmp: Bitmap, isPng: Boolean): Boolean {
        val ext = if (isPng) "png" else "jpg"
        val mime = if (isPng) "image/png" else "image/jpeg"
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val fileName = "PixelLab_${System.currentTimeMillis()}.$ext"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixelLab")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                val compressed = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(format, 95, out)
                } ?: false
                if (!compressed) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                compressed
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "PixelLab"
                ).apply { mkdirs() }
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { out ->
                    bmp.compress(format, 95, out)
                }
            }
        }.getOrDefault(false)
    }

    private fun createShareBitmapIntent(context: Context, bmp: Bitmap, isPng: Boolean): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ext = if (isPng) "png" else "jpg"
        val file = File(dir, "share_${System.currentTimeMillis()}.$ext")
        FileOutputStream(file).use { out ->
            bmp.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (isPng) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun loadProjectThumbnail(context: Context, plabFile: File): Bitmap? {
        val thumbFile = File(plabFile.parentFile, "${plabFile.nameWithoutExtension}.png")
        if (thumbFile.exists()) {
            BitmapFactory.decodeFile(thumbFile.absolutePath)?.let { return it }
        }
        return runCatching {
            val json = JSONObject(plabFile.readText())
            val proj = ProjectState.fromJson(json)
            val bmp = PixelLabRenderer.renderProjectPreviewBitmap(context, proj, 180)
            FileOutputStream(thumbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bmp
        }.getOrNull()
    }

    fun showSaveProjectDialog(context: Context, project: ProjectState, onSaved: (String) -> Unit) {
        val dark = ThemeManager.isDark(context)
        val et = EditText(context).apply {
            setText(project.name)
            setSelection(text.length)
            styleEditText(context, this, dark)
        }
        val wrap = LinearLayout(context).apply {
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 4))
            addView(et, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dlg = AlertDialog.Builder(context)
            .setTitle(R.string.save_project)
            .setView(wrap)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = et.text.toString().trim().ifBlank { "Untitled Design" }
                val updated = project.copy(name = name, updatedAt = System.currentTimeMillis())
                val dir = File(context.filesDir, "projects").apply { mkdirs() }
                val baseName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "project_${System.currentTimeMillis()}" }
                File(dir, "$baseName.plab").writeText(updated.toJson().toString())
                runCatching {
                    val thumbBmp = PixelLabRenderer.renderProjectPreviewBitmap(context, updated, 200)
                    FileOutputStream(File(dir, "$baseName.png")).use { out ->
                        thumbBmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                    }
                    thumbBmp.recycle()
                }
                onSaved(name)
                Toast.makeText(context, "Project saved: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleAlertDialog(context, dlg, dark)
    }

    fun showProjectsDialog(context: Context, onLoadProject: (ProjectState) -> Unit) {
        val dark = ThemeManager.isDark(context)
        val dp = context.resources.displayMetrics.density
        fun dpPx(v: Int) = (v * dp).roundToInt()

        val dir = File(context.filesDir, "projects").apply { mkdirs() }
        fun listPlabs(): List<File> =
            dir.listFiles { f -> f.extension == "plab" }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (listPlabs().isEmpty()) {
            val emptyDlg = AlertDialog.Builder(context)
                .setTitle(R.string.my_projects)
                .setMessage("No saved projects yet. Use Save -> Save as Project first.")
                .setPositiveButton(R.string.ok, null)
                .show()
            styleAlertDialog(context, emptyDlg, dark)
            return
        }

        val thumbExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scroll = ScrollView(context).apply {
            setPadding(dpPx(14), dpPx(10), dpPx(14), dpPx(6))
        }
        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listCol)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.my_projects)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { thumbExec.shutdownNow() }

        fun rebuildRows() {
            listCol.removeAllViews()
            val files = listPlabs()
            if (files.isEmpty()) {
                listCol.addView(TextView(context).apply {
                    text = "No saved projects."
                    textSize = 14f
                    setTextColor(ThemeManager.textSecondary(dark))
                    gravity = Gravity.CENTER
                    setPadding(dpPx(12), dpPx(24), dpPx(12), dpPx(24))
                })
                return
            }

            for (f in files) {
                val parsedMeta = runCatching {
                    val obj = JSONObject(f.readText())
                    val pName = obj.optString("name").ifBlank { f.nameWithoutExtension }
                    val w = obj.optInt("width", 1280)
                    val h = obj.optInt("height", 1280)
                    val lCount = obj.optJSONArray("layers")?.length() ?: 0
                    Triple(pName, w to h, lCount)
                }.getOrDefault(Triple(f.nameWithoutExtension, 1280 to 1280, 0))

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(ThemeManager.cardElevatedBg(dark))
                        cornerRadius = dpPx(10).toFloat()
                        setStroke(dpPx(1), ThemeManager.borderColor(dark))
                    }
                    setPadding(dpPx(10), dpPx(8), dpPx(6), dpPx(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpPx(8) }
                }

                val ivThumb = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dpPx(56), dpPx(56)).apply {
                        marginEnd = dpPx(12)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(ThemeManager.panelSecondaryBg(dark))
                        cornerRadius = dpPx(6).toFloat()
                        setStroke(dpPx(1), ThemeManager.borderColor(dark))
                    }
                    clipToOutline = true
                }
                thumbExec.execute {
                    val bmp = loadProjectThumbnail(context, f)
                    if (bmp != null) {
                        ivThumb.post { ivThumb.setImageBitmap(bmp) }
                    }
                }

                val infoCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoCol.addView(TextView(context).apply {
                    text = parsedMeta.first
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ThemeManager.textPrimary(dark))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                infoCol.addView(TextView(context).apply {
                    text = "${parsedMeta.second.first} × ${parsedMeta.second.second} • ${parsedMeta.third} layers"
                    textSize = 11.5f
                    setTextColor(ThemeManager.textSecondary(dark))
                    setPadding(0, dpPx(2), 0, 0)
                })

                val btnDelete = android.widget.ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dpPx(36), dpPx(36))
                    background = null
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(ThemeManager.dangerColor(dark))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpPx(6), dpPx(6), dpPx(6), dpPx(6))
                    setOnClickListener {
                        showConfirmDialog(
                            context = context,
                            title = "Delete Project",
                            message = "Delete \"${parsedMeta.first}\"?"
                        ) {
                            runCatching { f.delete() }
                            runCatching { File(f.parentFile, "${f.nameWithoutExtension}.png").delete() }
                            rebuildRows()
                        }
                    }
                }

                card.addView(ivThumb)
                card.addView(infoCol)
                card.addView(btnDelete)
                card.setOnClickListener {
                    runCatching {
                        val json = JSONObject(f.readText())
                        onLoadProject(ProjectState.fromJson(json))
                        dialog.dismiss()
                    }.onFailure {
                        Toast.makeText(context, "Failed to open project", Toast.LENGTH_SHORT).show()
                    }
                }
                listCol.addView(card)
            }
        }

        rebuildRows()
        dialog.show()
        styleAlertDialog(context, dialog, dark)
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        val dlg = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleAlertDialog(context, dlg, dark)
    }

    fun showStylesDialog(
        context: Context,
        project: ProjectState,
        onApplySavedStyle: (SavedTextStyle) -> Unit,
        onApplyAssetStyle: (AssetTextStyle) -> Unit,
        onRequestImportStyles: (onImported: (List<SavedTextStyle>) -> Unit) -> Unit,
        onRequestExportStyles: (List<SavedTextStyle>) -> Unit
    ) {
        val dark = ThemeManager.isDark(context)
        val dp = context.resources.displayMetrics.density
        fun dpPx(v: Int) = (v * dp).roundToInt()

        var activeTab = 0 // 0 = DEFAULT, 1 = MY STYLES
        val selectedIds = LinkedHashSet<String>()

        // Pre-render live background snapshot once for fast tile previews
        val tileW = 320
        val tileH = 128
        val cachedBgBmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val bgCanvas = android.graphics.Canvas(cachedBgBmp)
        if (project.background.type == BackgroundType.TRANSPARENT) {
            bgCanvas.drawColor(if (dark) Color.parseColor("#2A2A30") else Color.parseColor("#E5E7EB"))
        } else {
            PixelLabRenderer.renderBackground(context, bgCanvas, project, tileW.toFloat(), tileH.toFloat())
        }

        val generatedSampleCache = mutableMapOf<String, Bitmap>()

        fun getOrRenderSampleBitmap(cacheKey: String, sampleLayer: TextLayer): Bitmap {
            generatedSampleCache[cacheKey]?.let { if (!it.isRecycled) return it }
            val bmp = cachedBgBmp.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(bmp)
            val previewProj = ProjectState(
                id = "sty_preview",
                width = tileW,
                height = tileH,
                effects = EffectsState(vignetteEnabled = false),
                layers = listOf(sampleLayer)
            )
            PixelLabRenderer.renderProject(context, c, previewProj, skipTransparentBg = true)
            generatedSampleCache[cacheKey] = bmp
            return bmp
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogCardDrawable(context, dark, 12)
        }

        // Tabs row: DEFAULT | MY STYLES
        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(48))
            setBackgroundColor(ThemeManager.panelSecondaryBg(dark))
        }

        val accentBlue = ThemeManager.accentBlue(dark)
        val accentTeal = ThemeManager.accentPrimary(dark)

        fun buildTabView(label: String): Triple<LinearLayout, TextView, View> {
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val tv = TextView(context).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(ThemeManager.textSecondary(dark))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val line = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(3))
                setBackgroundColor(accentBlue)
            }
            tab.addView(tv)
            tab.addView(line)
            return Triple(tab, tv, line)
        }

        val (tabDefault, tvDefault, lineDefault) = buildTabView("DEFAULT")
        val (tabMine, tvMine, lineMine) = buildTabView("MY STYLES")
        tabsRow.addView(tabDefault)
        tabsRow.addView(tabMine)
        root.addView(tabsRow)

        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(1))
            setBackgroundColor(ThemeManager.dividerColor(dark))
        })

        // Long-press selection action bar (shown only when items are selected in MY STYLES)
        val selectionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeManager.accentSoftBg(dark))
            setPadding(dpPx(12), dpPx(6), dpPx(12), dpPx(6))
            visibility = View.GONE
        }
        val tvSelectionCount = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentBlue)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSelectAll = TextView(context).apply {
            text = "ALL"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentTeal)
            setPadding(dpPx(10), dpPx(4), dpPx(10), dpPx(4))
        }
        val btnDeleteSelected = TextView(context).apply {
            text = "DELETE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.dangerColor(dark))
            setPadding(dpPx(10), dpPx(4), dpPx(10), dpPx(4))
        }
        val btnClearSelection = TextView(context).apply {
            text = "CANCEL"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.textSecondary(dark))
            setPadding(dpPx(10), dpPx(4), dpPx(4), dpPx(4))
        }
        selectionBar.addView(tvSelectionCount)
        selectionBar.addView(btnSelectAll)
        selectionBar.addView(btnDeleteSelected)
        selectionBar.addView(btnClearSelection)
        root.addView(selectionBar)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(320))
        }
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(12), dpPx(12), dpPx(12), dpPx(12))
        }
        scroll.addView(grid)
        root.addView(scroll)

        // Footer bar: IMPORT | EXPORT | OK
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(1))
            setBackgroundColor(ThemeManager.dividerColor(dark))
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPx(16), dpPx(10), dpPx(16), dpPx(10))
        }
        val btnImport = TextView(context).apply {
            text = "IMPORT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentTeal)
            setPadding(dpPx(8), dpPx(8), dpPx(14), dpPx(8))
        }
        val btnExport = TextView(context).apply {
            text = "EXPORT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentTeal)
            setPadding(dpPx(14), dpPx(8), dpPx(14), dpPx(8))
        }
        val footerSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val btnOk = TextView(context).apply {
            text = "OK"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentTeal)
            setPadding(dpPx(14), dpPx(8), dpPx(8), dpPx(8))
        }
        footer.addView(btnImport)
        footer.addView(btnExport)
        footer.addView(footerSpacer)
        footer.addView(btnOk)
        root.addView(footer)

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        lateinit var renderGrid: () -> Unit

        fun buildTile(
            previewBmp: Bitmap,
            isSelected: Boolean,
            showDeleteBadge: Boolean,
            onTap: () -> Unit,
            onLongPress: (() -> Unit)? = null,
            onDelete: (() -> Unit)? = null
        ): FrameLayout {
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpPx(68), 1f).apply {
                    setMargins(dpPx(4), dpPx(4), dpPx(4), dpPx(4))
                }
                background = GradientDrawable().apply {
                    setColor(ThemeManager.panelSecondaryBg(dark))
                    setStroke(
                        dpPx(if (isSelected) 3 else 1),
                        if (isSelected) accentBlue else ThemeManager.borderColor(dark)
                    )
                }
                setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
            }
            val iv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(previewBmp)
            }
            frame.addView(iv)

            if (isSelected) {
                val badge = TextView(context).apply {
                    text = "✓"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setBackgroundColor(accentBlue)
                    layoutParams = FrameLayout.LayoutParams(dpPx(22), dpPx(22), Gravity.TOP or Gravity.END)
                }
                frame.addView(badge)
            }

            if (showDeleteBadge && onDelete != null) {
                val delBtn = TextView(context).apply {
                    text = "×"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#CC111111"))
                    layoutParams = FrameLayout.LayoutParams(dpPx(24), dpPx(24), Gravity.BOTTOM or Gravity.END)
                    setOnClickListener { onDelete() }
                }
                frame.addView(delBtn)
            }

            frame.setOnClickListener { onTap() }
            if (onLongPress != null) {
                frame.setOnLongClickListener {
                    onLongPress()
                    true
                }
            }
            return frame
        }

        renderGrid = {
            lineDefault.visibility = if (activeTab == 0) View.VISIBLE else View.INVISIBLE
            lineMine.visibility = if (activeTab == 1) View.VISIBLE else View.INVISIBLE
            tvDefault.setTextColor(if (activeTab == 0) ThemeManager.textPrimary(dark) else ThemeManager.textSecondary(dark))
            tvMine.setTextColor(if (activeTab == 1) ThemeManager.textPrimary(dark) else ThemeManager.textSecondary(dark))

            val isMine = activeTab == 1
            btnImport.visibility = if (isMine) View.VISIBLE else View.GONE
            btnExport.visibility = if (isMine) View.VISIBLE else View.GONE
            if (!isMine) selectedIds.clear()

            selectionBar.visibility = if (isMine && selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
            if (selectedIds.isNotEmpty()) {
                tvSelectionCount.text = "${selectedIds.size} selected"
            }

            grid.removeAllViews()

            if (!isMine) {
                val defaultStyles = JsonPresetCodec.referenceDefaultStyles
                val assetStyles = AssetRepository.loadTextStyles(context)
                val entries = mutableListOf<Triple<Bitmap, () -> Unit, Boolean>>()

                for (st in defaultStyles) {
                    val sample = st.applyTo(
                        TextLayer(
                            id = st.id,
                            name = "SAMPLE",
                            x = tileW / 2f,
                            y = tileH / 2f,
                            text = "SAMPLE",
                            fontSize = 46f
                        )
                    )
                    val (nw, nh) = PixelLabRenderer.measureTextBounds(sample, context)
                    val bmp = getOrRenderSampleBitmap("def_${st.id}", sample.copy(width = nw, height = nh))
                    entries.add(Triple(bmp, {
                        onApplySavedStyle(st)
                        dialog.dismiss()
                    }, false))
                }

                for (ast in assetStyles) {
                    val thumb = ast.thumbUri?.let { PixelLabRenderer.loadBitmap(context, it) }
                    val bmp = thumb ?: run {
                        val s = ast.template.copy(
                            x = tileW / 2f,
                            y = tileH / 2f,
                            text = "SAMPLE",
                            fontSize = 44f
                        )
                        val (nw, nh) = PixelLabRenderer.measureTextBounds(s, context)
                        getOrRenderSampleBitmap("ast_${ast.id}", s.copy(width = nw, height = nh))
                    }
                    entries.add(Triple(bmp, {
                        onApplyAssetStyle(ast)
                        dialog.dismiss()
                    }, false))
                }

                for (i in entries.indices step 2) {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val first = entries[i]
                    row.addView(buildTile(first.first, isSelected = false, showDeleteBadge = false, onTap = first.second))
                    if (i + 1 < entries.size) {
                        val second = entries[i + 1]
                        row.addView(buildTile(second.first, isSelected = false, showDeleteBadge = false, onTap = second.second))
                    } else {
                        row.addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dpPx(68), 1f).apply {
                                setMargins(dpPx(4), dpPx(4), dpPx(4), dpPx(4))
                            }
                        })
                    }
                    grid.addView(row)
                }
            } else {
                val myStyles = JsonPresetCodec.loadMyStyles(context)
                if (myStyles.isEmpty()) {
                    grid.addView(TextView(context).apply {
                        text = "No saved styles yet.\nUse Styles -> save to save a style or tap IMPORT to load a .json file."
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(ThemeManager.textSecondary(dark))
                        setPadding(dpPx(16), dpPx(40), dpPx(16), dpPx(40))
                    })
                } else {
                    for (i in myStyles.indices step 2) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        for (col in 0..1) {
                            val idx = i + col
                            if (idx < myStyles.size) {
                                val st = myStyles[idx]
                                val sample = st.applyTo(
                                    TextLayer(
                                        id = st.id,
                                        name = "SAMPLE",
                                        x = tileW / 2f,
                                        y = tileH / 2f,
                                        text = "SAMPLE",
                                        fontSize = 46f
                                    )
                                )
                                val (nw, nh) = PixelLabRenderer.measureTextBounds(sample, context)
                                val bmp = getOrRenderSampleBitmap("my_${st.id}_${st.hashCode()}", sample.copy(width = nw, height = nh))
                                val isSel = selectedIds.contains(st.id)
                                row.addView(
                                    buildTile(
                                        previewBmp = bmp,
                                        isSelected = isSel,
                                        showDeleteBadge = true,
                                        onTap = {
                                            if (selectedIds.isNotEmpty()) {
                                                if (!selectedIds.add(st.id)) selectedIds.remove(st.id)
                                                renderGrid()
                                            } else {
                                                onApplySavedStyle(st)
                                                dialog.dismiss()
                                            }
                                        },
                                        onLongPress = {
                                            if (!selectedIds.add(st.id)) selectedIds.remove(st.id)
                                            renderGrid()
                                        },
                                        onDelete = {
                                            selectedIds.remove(st.id)
                                            val remaining = JsonPresetCodec.loadMyStyles(context).filterNot { it.id == st.id }
                                            JsonPresetCodec.saveMyStyles(context, remaining)
                                            renderGrid()
                                        }
                                    )
                                )
                            } else {
                                row.addView(View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, dpPx(68), 1f).apply {
                                        setMargins(dpPx(4), dpPx(4), dpPx(4), dpPx(4))
                                    }
                                })
                            }
                        }
                        grid.addView(row)
                    }
                }
            }
        }

        tabDefault.setOnClickListener {
            activeTab = 0
            renderGrid()
        }
        tabMine.setOnClickListener {
            activeTab = 1
            renderGrid()
        }
        btnSelectAll.setOnClickListener {
            val all = JsonPresetCodec.loadMyStyles(context)
            selectedIds.clear()
            selectedIds.addAll(all.map { it.id })
            renderGrid()
        }
        btnDeleteSelected.setOnClickListener {
            if (selectedIds.isNotEmpty()) {
                val remaining = JsonPresetCodec.loadMyStyles(context).filterNot { selectedIds.contains(it.id) }
                JsonPresetCodec.saveMyStyles(context, remaining)
                selectedIds.clear()
                renderGrid()
            }
        }
        btnClearSelection.setOnClickListener {
            selectedIds.clear()
            renderGrid()
        }
        btnImport.setOnClickListener {
            onRequestImportStyles { imported ->
                if (imported.isNotEmpty()) {
                    val merged = JsonPresetCodec.loadMyStyles(context) + imported
                    JsonPresetCodec.saveMyStyles(context, merged)
                    activeTab = 1
                    renderGrid()
                    Toast.makeText(context, "Imported ${imported.size} style(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnExport.setOnClickListener {
            val myStyles = JsonPresetCodec.loadMyStyles(context)
            if (myStyles.isEmpty()) {
                Toast.makeText(context, "No styles in My Styles to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val toExport = if (selectedIds.isNotEmpty()) {
                myStyles.filter { selectedIds.contains(it.id) }
            } else {
                myStyles
            }
            onRequestExportStyles(toExport)
        }
        btnOk.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            generatedSampleCache.values.forEach { if (!it.isRecycled) it.recycle() }
            generatedSampleCache.clear()
            if (!cachedBgBmp.isRecycled) cachedBgBmp.recycle()
        }

        renderGrid()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }
}
