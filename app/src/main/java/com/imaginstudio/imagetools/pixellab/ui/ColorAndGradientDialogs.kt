package com.imaginstudio.imagetools.pixellab.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.imaginstudio.imagetools.pixellab.R
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.model.GradientDirection
import com.imaginstudio.imagetools.pixellab.model.GradientFill
import com.imaginstudio.imagetools.pixellab.model.GradientStop
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ColorAndGradientDialogs {

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun makeCheckerShader(ctx: Context): BitmapShader {
        val cell = dp(ctx, 6).coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.WHITE
        c.drawRect(0f, 0f, cell * 2f, cell * 2f, p)
        p.color = Color.parseColor("#CBD5E1")
        c.drawRect(cell.toFloat(), 0f, cell * 2f, cell.toFloat(), p)
        c.drawRect(0f, cell.toFloat(), cell.toFloat(), cell * 2f, p)
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun formatHexArgbText(color: Int): String {
        val a = Color.alpha(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return String.format(Locale.US, "#%02X%02X%02X - (%d,%d,%d,%d)", r, g, b, a, r, g, b)
    }

    private fun unifiedPopupSize(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val w = min(dm.widthPixels - dp(context, 36), dp(context, 356))
        val wheelSize = (w - dp(context, 64)).coerceIn(dp(context, 190), dp(context, 250))
        val naturalH = wheelSize + dp(context, 276)
        val maxSafeH = (dm.heightPixels * 0.86f).roundToInt()
        val h = min(naturalH, maxSafeH).coerceAtLeast(dp(context, 380))
        return w to h
    }

    private fun configureUnifiedDialogWindow(
        dialog: Dialog,
        dialogW: Int,
        dialogH: Int,
        isNested: Boolean
    ) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            if (isNested) {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                setWindowAnimations(0)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.55f)
            }
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setLayout(dialogW, dialogH)
        }
    }

    private fun makeUnifiedCardContainer(context: Context): LinearLayout {
        val dark = ThemeManager.isDark(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogCardDrawable(context, dark, 12)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 10))
        }
    }

    private fun makeFooterButtonsRow(
        context: Context,
        onCancelClick: () -> Unit,
        onOkClick: () -> Unit
    ): LinearLayout {
        val teal = ThemeManager.accentPrimary(ThemeManager.isDark(context))
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(context, 2), 0, dp(context, 2))
            addView(TextView(context).apply {
                text = "CANCEL"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(teal)
                setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
                setOnClickListener { onCancelClick() }
            })
            addView(TextView(context).apply {
                text = "OK"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(teal)
                setPadding(dp(context, 14), dp(context, 8), dp(context, 8), dp(context, 8))
                setOnClickListener { onOkClick() }
            })
        }
    }

    // =========================================================================
    // 1. HSV Color Wheel + 3 Sliders + Eyedropper + Manual Input Dialog
    // =========================================================================

    fun showColorPickerDialog(
        context: Context,
        initialColor: Int,
        project: ProjectState? = null,
        onLiveChange: ((Int) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        isNested: Boolean = false,
        onConfirm: (Int) -> Unit
    ) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var alpha = Color.alpha(initialColor)
        var committed = false
        var subDialogShowing = false

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val (dialogW, dialogH) = unifiedPopupSize(context)
        val card = makeUnifiedCardContainer(context)

        fun currentColor(): Int = Color.HSVToColor(alpha, hsv)

        val dark = ThemeManager.isDark(context)
        val previewCircle = object : View(context) {
            private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = makeCheckerShader(context)
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 1).toFloat()
                color = ThemeManager.borderColor(dark)
            }
            override fun onDraw(canvas: Canvas) {
                val r = min(width, height) / 2f - strokePaint.strokeWidth
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawCircle(cx, cy, r, checkerPaint)
                fillPaint.color = currentColor()
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 8)
            }
        }
        card.addView(previewCircle)

        val hexBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.panelSecondaryBg(dark))
                cornerRadius = dp(context, 6).toFloat()
                if (dark) setStroke(dp(context, 1), ThemeManager.borderColor(dark))
            }
            setPadding(dp(context, 10), dp(context, 5), dp(context, 8), dp(context, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }

        val tvHexInfo = TextView(context).apply {
            text = formatHexArgbText(currentColor())
            textSize = 13f
            setTextColor(ThemeManager.textPrimary(dark))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 6)
            }
        }

        fun makeCircleToolBtn(iconRes: Int, desc: String): ImageButton {
            return ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                    marginStart = dp(context, 6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (dark) ThemeManager.cardElevatedBg(true) else Color.parseColor("#9CA3AF"))
                    if (dark) setStroke(dp(context, 1), ThemeManager.borderColor(true))
                }
                setImageResource(iconRes)
                setColorFilter(if (dark) ThemeManager.textPrimary(true) else Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7))
                contentDescription = desc
            }
        }

        val btnEyedropper = makeCircleToolBtn(R.drawable.ic_eyedropper, "Eyedropper")
        val btnPencil = makeCircleToolBtn(R.drawable.ic_edit, "Manual Color Input")
        hexBar.addView(tvHexInfo)
        hexBar.addView(btnEyedropper)
        hexBar.addView(btnPencil)
        card.addView(hexBar)

        lateinit var wheelView: ColorWheelView
        lateinit var satBar: GradientRangeBar
        lateinit var valBar: GradientRangeBar
        lateinit var alphaBar: GradientRangeBar

        fun syncAllControls(fireLive: Boolean) {
            val col = currentColor()
            previewCircle.invalidate()
            tvHexInfo.text = formatHexArgbText(col)
            wheelView.setHueSat(hsv[0], hsv[1])
            satBar.setProgress(hsv[1])
            valBar.setProgress(hsv[2])
            alphaBar.setProgress(alpha / 255f)
            satBar.invalidate()
            valBar.invalidate()
            alphaBar.invalidate()
            if (fireLive) {
                onLiveChange?.invoke(col)
            }
        }

        val wheelSize = (dialogW - dp(context, 64)).coerceIn(dp(context, 190), dp(context, 250))
        val wheelSlot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                bottomMargin = dp(context, 8)
            }
        }
        wheelView = ColorWheelView(context, wheelSize).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setHueSat(hsv[0], hsv[1])
            onChange = { h, s ->
                hsv[0] = h
                hsv[1] = s
                syncAllControls(fireLive = true)
            }
        }
        wheelSlot.addView(wheelView)
        card.addView(wheelSlot)

        val barH = dp(context, 24)
        val barLp = { bottomDp: Int ->
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH).apply {
                bottomMargin = dp(context, bottomDp)
            }
        }

        satBar = GradientRangeBar(context, useChecker = false) {
            val leftCol = Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2]))
            val rightCol = Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
            leftCol to rightCol
        }.apply {
            layoutParams = barLp(8)
            onValueChange = { frac ->
                hsv[1] = frac
                syncAllControls(fireLive = true)
            }
        }

        valBar = GradientRangeBar(context, useChecker = false) {
            val rightCol = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
            Color.BLACK to rightCol
        }.apply {
            layoutParams = barLp(8)
            onValueChange = { frac ->
                hsv[2] = frac
                syncAllControls(fireLive = true)
            }
        }

        alphaBar = GradientRangeBar(context, useChecker = true) {
            val rgb = Color.HSVToColor(hsv) and 0x00FFFFFF
            rgb to (rgb or (0xFF shl 24))
        }.apply {
            layoutParams = barLp(6)
            onValueChange = { frac ->
                alpha = (frac * 255f).roundToInt().coerceIn(0, 255)
                syncAllControls(fireLive = true)
            }
        }

        card.addView(satBar)
        card.addView(valBar)
        card.addView(alphaBar)

        card.addView(
            makeFooterButtonsRow(
                context = context,
                onCancelClick = { dialog.dismiss() },
                onOkClick = {
                    committed = true
                    onConfirm(currentColor())
                    dialog.dismiss()
                }
            )
        )

        btnEyedropper.setOnClickListener {
            if (subDialogShowing) return@setOnClickListener
            if (project == null) {
                Toast.makeText(context, "Canvas unavailable for eyedropper", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            subDialogShowing = true
            showEyedropperDialog(
                context = context,
                project = project,
                initialColor = currentColor(),
                isNested = true,
                onFinished = { subDialogShowing = false }
            ) { picked ->
                Color.colorToHSV(picked, hsv)
                alpha = Color.alpha(picked)
                syncAllControls(fireLive = true)
            }
        }

        btnPencil.setOnClickListener {
            if (subDialogShowing) return@setOnClickListener
            subDialogShowing = true
            showManualColorInputDialog(
                context = context,
                initialColor = currentColor(),
                onFinished = { subDialogShowing = false }
            ) { picked ->
                Color.colorToHSV(picked, hsv)
                alpha = Color.alpha(picked)
                syncAllControls(fireLive = true)
            }
        }

        dialog.setOnDismissListener {
            if (!committed) {
                onCancel?.invoke()
            }
        }

        syncAllControls(fireLive = false)
        dialog.setContentView(card, ViewGroup.LayoutParams(dialogW, dialogH))
        configureUnifiedDialogWindow(dialog, dialogW, dialogH, isNested = isNested)
        dialog.show()
        dialog.window?.setLayout(dialogW, dialogH)
    }

    // =========================================================================
    // 2. Manual Hex + R/G/B Input Modal
    // =========================================================================

    private fun showManualColorInputDialog(
        context: Context,
        initialColor: Int,
        onFinished: (() -> Unit)? = null,
        onConfirm: (Int) -> Unit
    ) {
        var r = Color.red(initialColor)
        var g = Color.green(initialColor)
        var b = Color.blue(initialColor)
        val a = Color.alpha(initialColor)
        var syncing = false

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val dark = ThemeManager.isDark(context)
        val dm = context.resources.displayMetrics
        val dialogW = min(dm.widthPixels - dp(context, 48), dp(context, 320))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogCardDrawable(context, dark, 12)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 10))
        }

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 10)
            }
        }
        fun updatePreview() {
            val col = Color.argb(a, r, g, b)
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col)
                setStroke(dp(context, 1), ThemeManager.borderColor(dark))
            }
        }
        updatePreview()
        card.addView(preview)

        lateinit var etHex: EditText
        lateinit var etR: EditText
        lateinit var etG: EditText
        lateinit var etB: EditText

        fun makeRow(label: String, prefix: String?, inputType: Int, initial: String): Pair<LinearLayout, EditText> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 4), 0, dp(context, 4))
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 13.5f
                setTextColor(ThemeManager.textSecondary(dark))
                layoutParams = LinearLayout.LayoutParams(dp(context, 62), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            if (prefix != null) {
                row.addView(TextView(context).apply {
                    text = prefix
                    textSize = 15f
                    setTextColor(ThemeManager.textPrimary(dark))
                    setPadding(dp(context, 4), 0, dp(context, 4), 0)
                })
            }
            val et = EditText(context).apply {
                setText(initial)
                this.inputType = inputType
                textSize = 15f
                setTextColor(ThemeManager.textPrimary(dark))
                gravity = Gravity.CENTER_HORIZONTAL
                background = ThemeManager.inputDrawable(context, dark, 6)
                setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(et)
            return row to et
        }

        val hexInit = String.format(Locale.US, "%02X%02X%02X", r, g, b)
        val (rowHex, hexInput) = makeRow("Hex :", "#", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, hexInit)
        etHex = hexInput
        etHex.filters = arrayOf(InputFilter.LengthFilter(8))

        val (rowR, rInput) = makeRow("red :", null, InputType.TYPE_CLASS_NUMBER, r.toString())
        val (rowG, gInput) = makeRow("green :", null, InputType.TYPE_CLASS_NUMBER, g.toString())
        val (rowB, bInput) = makeRow("blue :", null, InputType.TYPE_CLASS_NUMBER, b.toString())
        etR = rInput
        etG = gInput
        etB = bInput

        etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val raw = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (raw.length == 6 || raw.length == 8) {
                    val parsed = runCatching { Color.parseColor("#$raw") }.getOrNull() ?: return
                    syncing = true
                    r = Color.red(parsed)
                    g = Color.green(parsed)
                    b = Color.blue(parsed)
                    etR.setText(r.toString())
                    etG.setText(g.toString())
                    etB.setText(b.toString())
                    updatePreview()
                    syncing = false
                }
            }
        })

        fun attachChannelWatcher(et: EditText, setter: (Int) -> Unit) {
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val v = s?.toString()?.toIntOrNull()?.coerceIn(0, 255) ?: return
                    syncing = true
                    setter(v)
                    if (s.toString().toIntOrNull() != v) {
                        et.setText(v.toString())
                        et.setSelection(et.text.length)
                    }
                    etHex.setText(String.format(Locale.US, "%02X%02X%02X", r, g, b))
                    updatePreview()
                    syncing = false
                }
            })
        }
        attachChannelWatcher(etR) { r = it }
        attachChannelWatcher(etG) { g = it }
        attachChannelWatcher(etB) { b = it }

        card.addView(rowHex)
        card.addView(rowR)
        card.addView(rowG)
        card.addView(rowB)

        val teal = ThemeManager.accentPrimary(dark)
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 10), 0, 0)
        }
        btnRow.addView(TextView(context).apply {
            text = "CANCEL"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(teal)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(context).apply {
            text = "OK"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(teal)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 6), dp(context, 8))
            setOnClickListener {
                onConfirm(Color.argb(a, r, g, b))
                dialog.dismiss()
            }
        })
        card.addView(btnRow)

        dialog.setOnDismissListener { onFinished?.invoke() }
        dialog.setContentView(card, ViewGroup.LayoutParams(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        dialog.window?.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // =========================================================================
    // 3. Layer-by-Layer Eyedropper Popup Modal with Pinch Zoom
    // =========================================================================

    private class EyedropperSourceEntry(
        val thumbBmp: Bitmap,
        private val loader: () -> Bitmap?
    ) {
        var fullBmp: Bitmap? = null
        fun getOrLoadFull(): Bitmap {
            fullBmp?.let { if (!it.isRecycled) return it }
            return (loader() ?: thumbBmp).also { fullBmp = it }
        }
    }

    private fun showEyedropperDialog(
        context: Context,
        project: ProjectState,
        initialColor: Int,
        isNested: Boolean = false,
        onFinished: (() -> Unit)? = null,
        onConfirm: (Int) -> Unit
    ) {
        val bitmapsToRecycle = HashSet<Bitmap>()

        // Prepare lightweight thumbnails + on-demand high-resolution source loaders
        val sources = ArrayList<EyedropperSourceEntry>()
        val visibleLayers = project.layers.filter { !it.hidden }

        if (visibleLayers.isNotEmpty()) {
            val previewThumb = runCatching {
                PixelLabRenderer.renderProjectPreviewBitmap(context, project, maxDim = 128)
            }.getOrNull()
            if (previewThumb != null) {
                bitmapsToRecycle.add(previewThumb)
                sources.add(
                    EyedropperSourceEntry(previewThumb) {
                        val compScale = min(1f, 2560f / max(project.width, project.height).coerceAtLeast(1))
                        val compW = (project.width * compScale).roundToInt().coerceAtLeast(64)
                        val compH = (project.height * compScale).roundToInt().coerceAtLeast(64)
                        val compFull = runCatching {
                            PixelLabRenderer.exportToBitmap(context, project, compW, compH, includeBackground = true)
                        }.recoverCatching {
                            val fbScale = min(1f, 1440f / max(project.width, project.height).coerceAtLeast(1))
                            val fw = (project.width * fbScale).roundToInt().coerceAtLeast(64)
                            val fh = (project.height * fbScale).roundToInt().coerceAtLeast(64)
                            PixelLabRenderer.exportToBitmap(context, project, fw, fh, includeBackground = true)
                        }.getOrNull()
                        if (compFull != null) bitmapsToRecycle.add(compFull)
                        compFull
                    }
                )
            }
        }

        val bgPreviewBmp = runCatching {
            PixelLabRenderer.renderProjectPreviewBitmap(context, project.copy(layers = emptyList()), maxDim = 128)
        }.getOrNull()
        if (bgPreviewBmp != null) {
            bitmapsToRecycle.add(bgPreviewBmp)
            sources.add(
                EyedropperSourceEntry(bgPreviewBmp) {
                    val bgFull = runCatching {
                        PixelLabRenderer.renderBackgroundHighResBitmap(context, project, 2560)
                    }.recoverCatching {
                        PixelLabRenderer.renderBackgroundHighResBitmap(context, project, 1440)
                    }.getOrNull()
                    if (bgFull != null) bitmapsToRecycle.add(bgFull)
                    bgFull
                }
            )
        }

        for (layer in visibleLayers) {
            val thumbBmp = runCatching {
                PixelLabRenderer.renderLayerPreviewBitmap(context, layer, 128)
            }.getOrNull() ?: continue
            bitmapsToRecycle.add(thumbBmp)
            sources.add(
                EyedropperSourceEntry(thumbBmp) {
                    val fullBmp = runCatching {
                        PixelLabRenderer.renderLayerHighResBitmap(context, layer, 1600)
                    }.getOrNull()
                    if (fullBmp != null) bitmapsToRecycle.add(fullBmp)
                    fullBmp
                }
            )
        }

        if (sources.isEmpty()) {
            onFinished?.invoke()
            Toast.makeText(context, "Nothing on canvas to sample", Toast.LENGTH_SHORT).show()
            return
        }

        val initialBitmap = sources[0].getOrLoadFull()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dark = ThemeManager.isDark(context)
        val (dialogW, dialogH) = unifiedPopupSize(context)
        var sampledColor = initialColor
        val card = makeUnifiedCardContainer(context)

        // Top Sampled Color Preview Dot (centered, matching Color Palette popup)
        val previewDot = object : View(context) {
            private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = makeCheckerShader(context)
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 1).toFloat()
                color = ThemeManager.borderColor(dark)
            }
            override fun onDraw(canvas: Canvas) {
                val r = min(width, height) / 2f - strokePaint.strokeWidth
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawCircle(cx, cy, r, checkerPaint)
                fillPaint.color = sampledColor
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 8)
            }
        }
        card.addView(previewDot)

        // Hex + Zoom Pill + Hand Tool Bar
        val infoBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.panelSecondaryBg(dark))
                cornerRadius = dp(context, 6).toFloat()
                if (dark) setStroke(dp(context, 1), ThemeManager.borderColor(true))
            }
            setPadding(dp(context, 10), dp(context, 5), dp(context, 8), dp(context, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }
        val tvSampledHex = TextView(context).apply {
            text = formatHexArgbText(sampledColor)
            textSize = 12f
            setTextColor(ThemeManager.textPrimary(dark))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 6)
            }
        }
        val zoomPillBg = GradientDrawable().apply {
            setColor(ThemeManager.cardElevatedBg(dark))
            cornerRadius = dp(context, 14).toFloat()
            if (dark) setStroke(dp(context, 1), ThemeManager.borderColor(true))
        }
        val zoomPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumWidth = dp(context, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(context, 32)
            )
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            background = zoomPillBg
        }
        val ivZoomIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 16), dp(context, 16))
            setImageResource(R.drawable.ic_zoom)
            setColorFilter(ThemeManager.textPrimary(dark))
        }
        val tvZoomPct = TextView(context).apply {
            text = "100%"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeManager.textPrimary(dark))
            setPadding(dp(context, 4), 0, 0, 0)
            visibility = View.GONE
        }
        zoomPill.addView(ivZoomIcon)
        zoomPill.addView(tvZoomPct)

        val handToolBg = GradientDrawable().apply {
            cornerRadius = dp(context, 10).toFloat()
        }
        val btnHandTool = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                marginStart = dp(context, 6)
            }
            background = handToolBg
            setImageResource(R.drawable.ic_hand)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            contentDescription = "Hand Tool"
        }

        infoBar.addView(tvSampledHex)
        infoBar.addView(zoomPill)
        infoBar.addView(btnHandTool)
        card.addView(infoBar)

        // Layer Thumbnails Strip
        val thumbStripBg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(if (dark) Color.parseColor("#141416") else Color.parseColor("#3B3B3B"))
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 54)
            ).apply { bottomMargin = dp(context, 8) }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5))
        }
        val thumbScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val thumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thumbScroll.addView(thumbRow)
        thumbStripBg.addView(thumbScroll)
        card.addView(thumbStripBg)

        // Zoomable Canvas Sampling Box fills remaining vertical space in fixed container
        val canvasContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = dp(context, 6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262626"))
                cornerRadius = dp(context, 6).toFloat()
                setStroke(dp(context, 1), ThemeManager.borderColor(dark))
            }
            clipToOutline = true
        }
        val canvasView = EyedropperCanvasView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        canvasContainer.addView(canvasView)
        card.addView(canvasContainer)

        fun syncHandToolUi() {
            val active = canvasView.handToolEnabled
            if (active) {
                handToolBg.setColor(ThemeManager.accentPrimary(dark))
                handToolBg.setStroke(dp(context, 1), ThemeManager.accentBlue(dark))
                btnHandTool.setColorFilter(Color.WHITE)
                btnHandTool.alpha = 1f
            } else {
                handToolBg.setColor(ThemeManager.cardElevatedBg(dark))
                handToolBg.setStroke(dp(context, 1), ThemeManager.borderColor(dark))
                btnHandTool.setColorFilter(ThemeManager.textPrimary(dark))
                btnHandTool.alpha = 0.85f
            }
            btnHandTool.invalidate()
        }
        syncHandToolUi()
        btnHandTool.setOnClickListener {
            canvasView.handToolEnabled = !canvasView.handToolEnabled
            syncHandToolUi()
        }

        var selectedIdx = 0
        val thumbFrames = ArrayList<FrameLayout>()

        fun refreshThumbBorders() {
            for (i in thumbFrames.indices) {
                val frame = thumbFrames[i]
                frame.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(context, 4).toFloat()
                    setStroke(
                        dp(context, if (i == selectedIdx) 2 else 1),
                        if (i == selectedIdx) Color.WHITE else Color.parseColor("#64748B")
                    )
                }
            }
        }

        for (i in sources.indices) {
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)).apply {
                    marginEnd = dp(context, 6)
                }
                setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            }
            val iv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(sources[i].thumbBmp)
            }
            frame.addView(iv)
            frame.setOnClickListener {
                if (i == selectedIdx) return@setOnClickListener
                selectedIdx = i
                refreshThumbBorders()
                canvasView.setTargetBitmap(sources[i].getOrLoadFull())
            }
            thumbFrames.add(frame)
            thumbRow.addView(frame)
        }
        refreshThumbBorders()

        canvasView.onColorSampled = { col ->
            sampledColor = col
            previewDot.invalidate()
            tvSampledHex.text = formatHexArgbText(col)
        }
        canvasView.onZoomStateChanged = { zoom ->
            val pct = (zoom * 100f).roundToInt()
            if (pct > 101) {
                tvZoomPct.text = "$pct%"
                tvZoomPct.visibility = View.VISIBLE
                zoomPillBg.setColor(ThemeManager.accentPrimary(dark))
                ivZoomIcon.setColorFilter(Color.WHITE)
                tvZoomPct.setTextColor(Color.WHITE)
            } else {
                tvZoomPct.text = "100%"
                tvZoomPct.visibility = View.GONE
                zoomPillBg.setColor(ThemeManager.cardElevatedBg(dark))
                ivZoomIcon.setColorFilter(ThemeManager.textPrimary(dark))
                tvZoomPct.setTextColor(ThemeManager.textPrimary(dark))
            }
            zoomPill.invalidate()
        }
        zoomPill.setOnClickListener {
            canvasView.cycleZoom()
        }
        zoomPill.setOnLongClickListener {
            canvasView.resetZoom()
            true
        }
        canvasView.setTargetBitmap(initialBitmap)

        // Bottom CANCEL / OK row (matching Color Palette & Gradient popups)
        card.addView(
            makeFooterButtonsRow(
                context = context,
                onCancelClick = { dialog.dismiss() },
                onOkClick = {
                    onConfirm(sampledColor)
                    dialog.dismiss()
                }
            )
        )

        lateinit var dialogRoot: FrameLayout
        val loupeOverlay = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvasView.drawExternalLoupeOverlay(canvas, dialogRoot)
            }
        }.apply {
            isClickable = false
            isFocusable = false
        }
        dialogRoot = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        dialogRoot.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        canvasView.onLoupeOverlayInvalidated = { loupeOverlay.invalidate() }
        dialogRoot.addView(
            loupeOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        dialog.setOnDismissListener {
            for (b in bitmapsToRecycle) {
                if (!b.isRecycled) b.recycle()
            }
            bitmapsToRecycle.clear()
            onFinished?.invoke()
        }

        dialog.setContentView(dialogRoot, ViewGroup.LayoutParams(dialogW, dialogH))
        configureUnifiedDialogWindow(dialog, dialogW, dialogH, isNested = isNested)
        dialog.show()
        dialog.window?.setLayout(dialogW, dialogH)
    }

    // =========================================================================
    // 4. Visual Gradient Editor Dialog (Presets, Handles, Stops, 6 Action Buttons)
    // =========================================================================

    fun showGradientMakerDialog(
        context: Context,
        initial: GradientFill,
        project: ProjectState? = null,
        onLiveChange: ((GradientFill) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: (GradientFill) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val (dialogW, dialogH) = unifiedPopupSize(context)
        var committed = false
        var subDialogShowing = false

        var curType = initial.type
        val stops = initial.stops
            .map { GradientStop(it.offset.coerceIn(0f, 1f), it.color) }
            .sortedBy { it.offset }
            .toMutableList()
            .apply {
                if (size < 2) {
                    clear()
                    add(GradientStop(0f, Color.parseColor("#388DFF")))
                    add(GradientStop(1f, Color.parseColor("#FFFFFF")))
                }
            }
        var selectedStopIdx = 0

        fun defaultCoordsFor(dir: GradientDirection, angle: Float): FloatArray {
            return when (dir) {
                GradientDirection.LINEAR_H -> floatArrayOf(0f, 0.5f, 1f, 0.5f)
                GradientDirection.LINEAR_V -> floatArrayOf(0.5f, 0f, 0.5f, 1f)
                GradientDirection.LINEAR_DIAG -> {
                    val norm = ((angle % 360f) + 360f) % 360f
                    if (norm in 20f..80f) floatArrayOf(1f, 0f, 0f, 1f)
                    else floatArrayOf(0f, 0f, 1f, 1f)
                }
                GradientDirection.RADIAL -> floatArrayOf(0.5f, 0.5f, 1f, 0.5f)
            }
        }

        val initCoords = if (
            initial.startX != null && initial.startY != null &&
            initial.endX != null && initial.endY != null
        ) {
            floatArrayOf(initial.startX, initial.startY, initial.endX, initial.endY)
        } else {
            defaultCoordsFor(initial.type, initial.angle)
        }

        var sx = initCoords[0]
        var sy = initCoords[1]
        var ex = initCoords[2]
        var ey = initCoords[3]

        fun buildCurrentGradient(): GradientFill {
            val angleDeg = ((Math.toDegrees(atan2((ey - sy).toDouble(), (ex - sx).toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
            val sortedStops = stops.map { GradientStop(it.offset.coerceIn(0f, 1f), it.color) }.sortedBy { it.offset }
            return GradientFill(
                type = curType,
                angle = if (curType == GradientDirection.RADIAL) 0f else angleDeg,
                stops = sortedStops,
                startX = sx,
                startY = sy,
                endX = ex,
                endY = ey
            )
        }

        val card = makeUnifiedCardContainer(context)

        // Preset geometry definitions (6 thumbnails matching Video 2)
        data class GradGeomPreset(
            val dir: GradientDirection,
            val angle: Float,
            val sx: Float,
            val sy: Float,
            val ex: Float,
            val ey: Float
        )
        val presets = listOf(
            GradGeomPreset(GradientDirection.LINEAR_H, 90f, 0f, 0.5f, 1f, 0.5f),
            GradGeomPreset(GradientDirection.LINEAR_V, 180f, 0.5f, 0f, 0.5f, 1f),
            GradGeomPreset(GradientDirection.LINEAR_DIAG, 135f, 0f, 0f, 1f, 1f),
            GradGeomPreset(GradientDirection.LINEAR_DIAG, 45f, 1f, 0f, 0f, 1f),
            GradGeomPreset(GradientDirection.RADIAL, 0f, 0.5f, 0.5f, 1f, 0.5f),
            GradGeomPreset(GradientDirection.RADIAL, 0f, 0.5f, 0.5f, 0.96f, 0.96f)
        )
        var activePresetIdx = presets.indexOfFirst { p ->
            p.dir == curType &&
                kotlin.math.abs(p.sx - sx) < 0.08f &&
                kotlin.math.abs(p.sy - sy) < 0.08f &&
                kotlin.math.abs(p.ex - ex) < 0.08f &&
                kotlin.math.abs(p.ey - ey) < 0.08f
        }

        // 1. Row of 6 preset thumbnails
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8) }
        }
        val presetViews = ArrayList<GradientThumbView>(presets.size)

        lateinit var previewBox: GradientPreviewBox
        lateinit var stopsBar: GradientStopsBar

        fun syncAll(fireLive: Boolean) {
            previewBox.updateState(curType, stops, sx, sy, ex, ey)
            stopsBar.updateState(stops, selectedStopIdx)
            for (i in presetViews.indices) {
                presetViews[i].updateStops(stops, selected = (i == activePresetIdx))
            }
            if (fireLive) {
                onLiveChange?.invoke(buildCurrentGradient())
            }
        }

        for ((idx, preset) in presets.withIndex()) {
            val tv = GradientThumbView(
                ctx = context,
                dir = preset.dir,
                sx = preset.sx,
                sy = preset.sy,
                ex = preset.ex,
                ey = preset.ey
            ).apply {
                val size = dp(context, 36)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(dp(context, 3), 0, dp(context, 3), 0)
                }
                setOnClickListener {
                    activePresetIdx = idx
                    curType = preset.dir
                    sx = preset.sx
                    sy = preset.sy
                    ex = preset.ex
                    ey = preset.ey
                    syncAll(fireLive = true)
                }
            }
            presetViews.add(tv)
            presetRow.addView(tv)
        }
        card.addView(presetRow)

        // 2. Large live gradient preview box fills remaining vertical space in fixed container
        val previewSlot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = dp(context, 8) }
        }
        previewBox = GradientPreviewBox(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            onHandlesChanged = { nsx, nsy, nex, ney ->
                sx = nsx
                sy = nsy
                ex = nex
                ey = ney
                activePresetIdx = -1
                syncAll(fireLive = true)
            }
        }
        previewSlot.addView(previewBox)
        card.addView(previewSlot)

        // 3. Gradient bar + stop markers
        stopsBar = GradientStopsBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            ).apply { bottomMargin = dp(context, 8) }
            onSelectStop = { idx ->
                selectedStopIdx = idx.coerceIn(0, stops.lastIndex)
                syncAll(fireLive = false)
            }
            onMoveStop = { idx, newOffset ->
                val targetStop = stops[idx].copy(offset = newOffset.coerceIn(0f, 1f))
                stops[idx] = targetStop
                // Keep stops sorted while keeping selectedStopIdx pointing to the moved stop
                val indexed = stops.mapIndexed { i, st -> i to st }.sortedBy { it.second.offset }
                stops.clear()
                indexed.forEachIndexed { newPos, pair ->
                    stops.add(pair.second)
                    if (pair.first == idx) selectedStopIdx = newPos
                }
                syncAll(fireLive = true)
            }
        }
        card.addView(stopsBar)

        // 4. Row of 6 round blue buttons: +, -, <, reverse, >, paint-bucket
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 6) }
        }
        val dark = ThemeManager.isDark(context)
        val blueBg = if (dark) ThemeManager.accentPrimary(true) else Color.parseColor("#1E88E5")

        fun makeBlueCircleIconBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(context).apply {
                val sz = dp(context, 38)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(dp(context, 4), 0, dp(context, 4), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(blueBg)
                }
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9))
                contentDescription = desc
                setOnClickListener { onClick() }
            }
        }

        // "+" Add stop
        actionsRow.addView(makeBlueCircleIconBtn(R.drawable.ic_plus_simple, "Add Stop") {
            val curStop = stops[selectedStopIdx]
            val neighborIdx = if (selectedStopIdx < stops.lastIndex) selectedStopIdx + 1 else selectedStopIdx - 1
            val neighbor = stops.getOrElse(neighborIdx) { curStop }
            val newOff = ((curStop.offset + neighbor.offset) / 2f).coerceIn(0f, 1f)
            val newCol = blendArgb(curStop.color, neighbor.color, 0.5f)
            val newStop = GradientStop(newOff, newCol)
            stops.add(newStop)
            stops.sortBy { it.offset }
            selectedStopIdx = stops.indexOf(newStop).coerceAtLeast(0)
            syncAll(fireLive = true)
        })

        // "-" Remove stop
        actionsRow.addView(makeBlueCircleIconBtn(R.drawable.ic_minus, "Remove Stop") {
            if (stops.size <= 2) {
                Toast.makeText(context, "At least 2 stops required", Toast.LENGTH_SHORT).show()
                return@makeBlueCircleIconBtn
            }
            stops.removeAt(selectedStopIdx)
            selectedStopIdx = selectedStopIdx.coerceIn(0, stops.lastIndex)
            syncAll(fireLive = true)
        })

        // "<" Move stop left
        actionsRow.addView(makeBlueCircleIconBtn(R.drawable.ic_chevron_left, "Move Stop Left") {
            val idx = selectedStopIdx
            val moved = stops[idx].copy(offset = (stops[idx].offset - 0.03f).coerceIn(0f, 1f))
            stops[idx] = moved
            val indexed = stops.mapIndexed { i, st -> i to st }.sortedBy { it.second.offset }
            stops.clear()
            indexed.forEachIndexed { newPos, pair ->
                stops.add(pair.second)
                if (pair.first == idx) selectedStopIdx = newPos
            }
            syncAll(fireLive = true)
        })

        // "◉" Reverse stops
        val btnReverse = object : View(context) {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blueBg }
            private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 2).toFloat()
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = min(width, height) / 2f
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r * 0.44f, ringPaint)
                canvas.drawCircle(cx, cy, r * 0.19f, dotPaint)
            }
        }.apply {
            val sz = dp(context, 38)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                setMargins(dp(context, 4), 0, dp(context, 4), 0)
            }
            contentDescription = "Reverse Stops"
            setOnClickListener {
                val selectedObj = stops[selectedStopIdx]
                val flipped = stops.map { st ->
                    GradientStop((1f - st.offset).coerceIn(0f, 1f), st.color)
                }.reversed()
                val targetIdx = stops.size - 1 - selectedStopIdx
                stops.clear()
                stops.addAll(flipped)
                selectedStopIdx = targetIdx.coerceIn(0, stops.lastIndex)
                if (stops[selectedStopIdx].color != selectedObj.color) {
                    selectedStopIdx = stops.indexOfFirst { it.color == selectedObj.color }.coerceAtLeast(0)
                }
                syncAll(fireLive = true)
            }
        }
        actionsRow.addView(btnReverse)

        // ">" Move stop right
        actionsRow.addView(makeBlueCircleIconBtn(R.drawable.ic_chevron_right, "Move Stop Right") {
            val idx = selectedStopIdx
            val moved = stops[idx].copy(offset = (stops[idx].offset + 0.03f).coerceIn(0f, 1f))
            stops[idx] = moved
            val indexed = stops.mapIndexed { i, st -> i to st }.sortedBy { it.second.offset }
            stops.clear()
            indexed.forEachIndexed { newPos, pair ->
                stops.add(pair.second)
                if (pair.first == idx) selectedStopIdx = newPos
            }
            syncAll(fireLive = true)
        })

        // Paint-bucket: edit selected stop color via Color Picker Dialog
        actionsRow.addView(makeBlueCircleIconBtn(R.drawable.ic_color_fill, "Edit Stop Color") {
            if (subDialogShowing) return@makeBlueCircleIconBtn
            subDialogShowing = true
            val editIdx = selectedStopIdx.coerceIn(0, stops.lastIndex)
            val prevCol = stops[editIdx].color
            showColorPickerDialog(
                context = context,
                initialColor = prevCol,
                project = project,
                onLiveChange = { c ->
                    stops[editIdx] = stops[editIdx].copy(color = c)
                    syncAll(fireLive = true)
                },
                onCancel = {
                    subDialogShowing = false
                    stops[editIdx] = stops[editIdx].copy(color = prevCol)
                    syncAll(fireLive = true)
                },
                isNested = true,
                onConfirm = { c ->
                    subDialogShowing = false
                    stops[editIdx] = stops[editIdx].copy(color = c)
                    syncAll(fireLive = true)
                }
            )
        })
        card.addView(actionsRow)

        // 5. CANCEL and OK
        card.addView(
            makeFooterButtonsRow(
                context = context,
                onCancelClick = { dialog.dismiss() },
                onOkClick = {
                    committed = true
                    onConfirm(buildCurrentGradient())
                    dialog.dismiss()
                }
            )
        )

        dialog.setOnDismissListener {
            if (!committed) {
                onCancel?.invoke()
            }
        }

        syncAll(fireLive = false)
        dialog.setContentView(card, ViewGroup.LayoutParams(dialogW, dialogH))
        configureUnifiedDialogWindow(dialog, dialogW, dialogH, isNested = false)
        dialog.show()
        dialog.window?.setLayout(dialogW, dialogH)
    }

    private fun blendArgb(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * f).roundToInt()
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).roundToInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).roundToInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).roundToInt()
        return Color.argb(a, r, g, b)
    }

    // =========================================================================
    // Custom Views for Color Picker, Eyedropper, and Gradient Editor
    // =========================================================================

    private class ColorWheelView(ctx: Context, private val maxWheelPx: Int) : View(ctx) {
        private var hue = 0f
        private var sat = 1f
        var onChange: ((Float, Float) -> Unit)? = null

        private var wheelBmp: Bitmap? = null
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * ctx.resources.displayMetrics.density
            color = Color.parseColor("#CBD5E1")
        }
        private val selDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * ctx.resources.displayMetrics.density
            color = Color.parseColor("#1E293B")
        }
        private val selWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * ctx.resources.displayMetrics.density
            color = Color.WHITE
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val wMode = MeasureSpec.getMode(widthMeasureSpec)
            val hMode = MeasureSpec.getMode(heightMeasureSpec)
            val availW = if (wMode == MeasureSpec.UNSPECIFIED) maxWheelPx else MeasureSpec.getSize(widthMeasureSpec)
            val availH = if (hMode == MeasureSpec.UNSPECIFIED) maxWheelPx else MeasureSpec.getSize(heightMeasureSpec)
            val side = min(min(availW, availH), maxWheelPx).coerceAtLeast(1)
            setMeasuredDimension(side, side)
        }

        fun setHueSat(h: Float, s: Float) {
            hue = ((h % 360f) + 360f) % 360f
            sat = s.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            wheelBmp?.recycle()
            wheelBmp = null
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) / 2f - 4f * resources.displayMetrics.density
            if (r > 4f) {
                val hueColors = intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                    Color.BLUE, Color.MAGENTA, Color.RED
                )
                val sweep = SweepGradient(cx, cy, hueColors, null)
                val radial = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                c.save()
                // Rotate 180° so Red(0°) sits at the LEFT (matching PixelLab reference video)
                c.rotate(180f, cx, cy)
                p.shader = sweep
                c.drawCircle(cx, cy, r, p)
                c.restore()
                p.shader = radial
                c.drawCircle(cx, cy, r, p)
                c.drawCircle(cx, cy, r, outerRingPaint)
            }
            wheelBmp = bmp
        }

        override fun onDetachedFromWindow() {
            wheelBmp?.recycle()
            wheelBmp = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            wheelBmp?.let { canvas.drawBitmap(it, 0f, 0f, bmpPaint) }
            val cx = width / 2f
            val cy = height / 2f
            val r = (min(width, height) / 2f - 4f * resources.displayMetrics.density).coerceAtLeast(1f)
            // Hue=0 is LEFT (180° in standard canvas space), increasing clockwise
            val rad = Math.toRadians((hue + 180f).toDouble())
            val sx = cx + (cos(rad) * sat * r).toFloat()
            val sy = cy + (sin(rad) * sat * r).toFloat()
            val ringR = 5.5f * resources.displayMetrics.density
            canvas.drawCircle(sx, sy, ringR, selDarkPaint)
            canvas.drawCircle(sx, sy, ringR - resources.displayMetrics.density, selWhitePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = (min(width, height) / 2f - 4f * resources.displayMetrics.density).coerceAtLeast(1f)
                    val dx = event.x - cx
                    val dy = event.y - cy
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val newSat = (dist / r).coerceIn(0f, 1f)
                    val rawDeg = Math.toDegrees(atan2(-dy.toDouble(), -dx.toDouble())).toFloat()
                    val newHue = ((rawDeg % 360f) + 360f) % 360f
                    hue = newHue
                    sat = newSat
                    onChange?.invoke(hue, sat)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private class GradientRangeBar(
        ctx: Context,
        private val useChecker: Boolean,
        private val colorsProvider: () -> Pair<Int, Int>
    ) : View(ctx) {
        private var progress = 1f
        var onValueChange: ((Float) -> Unit)? = null

        private val checkerPaint = if (useChecker) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = makeCheckerShader(ctx) }
        } else null
        private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * ctx.resources.displayMetrics.density
            color = Color.parseColor("#94A3B8")
        }
        private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * ctx.resources.displayMetrics.density
            color = Color.parseColor("#475569")
        }
        private val trackRect = RectF()

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = height * 0.48f
            val left = pad
            val right = (width - pad).coerceAtLeast(left + 1f)
            val top = 2f * resources.displayMetrics.density
            val bottom = height - top
            trackRect.set(left, top, right, bottom)

            if (checkerPaint != null) {
                canvas.drawRect(trackRect, checkerPaint)
            }
            val (c0, c1) = colorsProvider()
            gradPaint.shader = LinearGradient(left, 0f, right, 0f, c0, c1, Shader.TileMode.CLAMP)
            canvas.drawRect(trackRect, gradPaint)
            canvas.drawRect(trackRect, borderPaint)

            val tx = left + progress * (right - left)
            val ty = height / 2f
            val tr = (height / 2f - 1.5f * resources.displayMetrics.density).coerceAtLeast(6f)
            canvas.drawCircle(tx, ty, tr, thumbFill)
            canvas.drawCircle(tx, ty, tr, thumbStroke)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val pad = height * 0.48f
            val left = pad
            val right = (width - pad).coerceAtLeast(left + 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val frac = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                    progress = frac
                    onValueChange?.invoke(frac)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private class EyedropperCanvasView(ctx: Context) : View(ctx) {
        private var targetBmp: Bitmap? = null
        private var crossNormX = 0.5f
        private var crossNormY = 0.5f
        private var lastSampledColor = Color.WHITE
        private var isSamplingDrag = false
        private var isPanningDrag = false

        var handToolEnabled: Boolean = false

        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var baseScale = 1f
        private var baseLeft = 0f
        private var baseTop = 0f

        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastPanX = 0f
        private var lastPanY = 0f
        private var prevFocusX = 0f
        private var prevFocusY = 0f
        private var isMultiTouchGesture = false

        var onColorSampled: ((Int) -> Unit)? = null
        var onZoomStateChanged: ((Float) -> Unit)? = null
        var onLoupeOverlayInvalidated: (() -> Unit)? = null

        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = makeCheckerShader(ctx) }
        private val dstRect = RectF()
        private val loupeDrawer = PixelLoupeDrawer(ctx.resources.displayMetrics.density)
        private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 148, 163, 184)
        }

        private val scaleDetector = ScaleGestureDetector(
            ctx,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    prevFocusX = detector.focusX
                    prevFocusY = detector.focusY
                    lastPanX = detector.focusX
                    lastPanY = detector.focusY
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prevZoom = zoom
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
                    val factor = zoom / prevZoom
                    val prevFx = prevFocusX - width / 2f
                    val prevFy = prevFocusY - height / 2f
                    val currFx = detector.focusX - width / 2f
                    val currFy = detector.focusY - height / 2f
                    panX = (panX - prevFx) * factor + currFx
                    panY = (panY - prevFy) * factor + currFy
                    prevFocusX = detector.focusX
                    prevFocusY = detector.focusY
                    lastPanX = detector.focusX
                    lastPanY = detector.focusY
                    clampPan(snapAtBaseZoom = !handToolEnabled)
                    onZoomStateChanged?.invoke(zoom)
                    invalidate()
                    return true
                }
            }
        )

        private val gestureDetector = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    resetZoom()
                    return true
                }
            }
        )

        fun setTargetBitmap(bmp: Bitmap) {
            targetBmp = bmp
            crossNormX = 0.5f
            crossNormY = 0.5f
            isSamplingDrag = false
            isPanningDrag = false
            zoom = 1f
            panX = 0f
            panY = 0f
            recomputeBaseMetrics()
            onZoomStateChanged?.invoke(zoom)
            sampleAtCrosshair()
            invalidate()
            onLoupeOverlayInvalidated?.invoke()
        }

        fun cycleZoom() {
            val target = when {
                zoom < 1.95f -> 2f
                zoom < 3.95f -> 4f
                else -> 1f
            }
            if (target <= 1.01f) {
                resetZoom()
            } else {
                zoomTo(target)
            }
        }

        fun zoomTo(targetZoom: Float) {
            val bmp = targetBmp ?: return
            zoom = targetZoom.coerceIn(1f, 8f)
            if (zoom <= 1.01f) {
                zoom = 1f
                panX = 0f
                panY = 0f
            } else if (width > 0 && height > 0) {
                val drawW = bmp.width * baseScale * zoom
                val drawH = bmp.height * baseScale * zoom
                panX = -(crossNormX - 0.5f) * drawW
                panY = -(crossNormY - 0.5f) * drawH
                clampPan()
            }
            onZoomStateChanged?.invoke(zoom)
            invalidate()
        }

        fun resetZoom() {
            zoom = 1f
            panX = 0f
            panY = 0f
            onZoomStateChanged?.invoke(zoom)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recomputeBaseMetrics()
            clampPan()
        }

        private fun recomputeBaseMetrics() {
            val bmp = targetBmp ?: return
            if (width <= 0 || height <= 0) return
            baseScale = min(width.toFloat() / bmp.width.coerceAtLeast(1), height.toFloat() / bmp.height.coerceAtLeast(1))
            val drawW = bmp.width * baseScale
            val drawH = bmp.height * baseScale
            baseLeft = (width - drawW) / 2f
            baseTop = (height - drawH) / 2f
        }

        private fun clampPan(snapAtBaseZoom: Boolean = false) {
            val bmp = targetBmp ?: return
            if (zoom <= 1f && (snapAtBaseZoom || (!handToolEnabled && panX == 0f && panY == 0f))) {
                panX = 0f
                panY = 0f
                return
            }
            val scaledW = bmp.width * baseScale * zoom
            val scaledH = bmp.height * baseScale * zoom
            val margin = 40f * resources.displayMetrics.density
            val extraX = if (handToolEnabled || zoom <= 1.01f) width * 0.45f else 0f
            val extraY = if (handToolEnabled || zoom <= 1.01f) height * 0.45f else 0f
            val maxPanX = max(extraX, (scaledW - width) / 2f + margin)
            val maxPanY = max(extraY, (scaledH - height) / 2f + margin)
            panX = panX.coerceIn(-maxPanX, maxPanX)
            panY = panY.coerceIn(-maxPanY, maxPanY)
        }

        private fun currentDrawRect(): RectF {
            val bmp = targetBmp ?: return RectF()
            val drawW = bmp.width * baseScale * zoom
            val drawH = bmp.height * baseScale * zoom
            val cx = width / 2f + panX
            val cy = height / 2f + panY
            dstRect.set(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)
            return dstRect
        }

        private fun sampleAtCrosshair() {
            val bmp = targetBmp ?: return
            if (bmp.isRecycled) return
            val px = (crossNormX * (bmp.width - 1)).roundToInt().coerceIn(0, bmp.width - 1)
            val py = (crossNormY * (bmp.height - 1)).roundToInt().coerceIn(0, bmp.height - 1)
            lastSampledColor = bmp.getPixel(px, py)
            onColorSampled?.invoke(lastSampledColor)
        }

        fun drawExternalLoupeOverlay(canvas: Canvas, rootContainer: View) {
            if (!isSamplingDrag) return
            val bmp = targetBmp ?: return
            if (bmp.isRecycled) return
            val rect = currentDrawRect()
            var ox = 0f
            var oy = 0f
            var cur: View? = this
            while (cur != null && cur !== rootContainer) {
                ox += cur.left
                oy += cur.top
                cur = cur.parent as? View
            }
            val touchRootX = ox + rect.left + crossNormX * rect.width()
            val touchRootY = oy + rect.top + crossNormY * rect.height()
            val px = (crossNormX * (bmp.width - 1)).roundToInt().coerceIn(0, bmp.width - 1)
            val py = (crossNormY * (bmp.height - 1)).roundToInt().coerceIn(0, bmp.height - 1)
            loupeDrawer.drawLoupeWithCrosshair(
                canvas = canvas,
                viewWidth = rootContainer.width.toFloat(),
                viewHeight = rootContainer.height.toFloat(),
                bitmap = bmp,
                sampleBmpX = px,
                sampleBmpY = py,
                touchX = touchRootX,
                touchY = touchRootY,
                sampledColor = lastSampledColor,
                showLoupe = true,
                flipThresholdTopPx = 52f * resources.displayMetrics.density
            )
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = targetBmp ?: return
            if (bmp.isRecycled) return
            val rect = currentDrawRect()
            canvas.drawRect(rect, checkerPaint)
            bmpPaint.isFilterBitmap = rect.width() <= bmp.width * 1.35f
            canvas.drawBitmap(bmp, null, rect, bmpPaint)

            if (!isSamplingDrag) {
                val cx = rect.left + crossNormX * rect.width()
                val cy = rect.top + crossNormY * rect.height()
                loupeDrawer.drawCrosshairOnly(canvas, cx, cy, lastSampledColor)
            }

            // Draw subtle scrollbars when zoomed
            if (zoom > 1.02f) {
                val barThick = 3.5f * resources.displayMetrics.density
                val margin = 4f * resources.displayMetrics.density
                if (rect.height() > height) {
                    val thumbH = max(dp(context, 24).toFloat(), height * (height / rect.height()))
                    val fracY = ((-rect.top) / (rect.height() - height)).coerceIn(0f, 1f)
                    val topY = fracY * (height - thumbH)
                    canvas.drawRoundRect(
                        width - barThick - margin, topY,
                        width - margin, topY + thumbH,
                        barThick, barThick, scrollTrackPaint
                    )
                }
                if (rect.width() > width) {
                    val thumbW = max(dp(context, 24).toFloat(), width * (width / rect.width()))
                    val fracX = ((-rect.left) / (rect.width() - width)).coerceIn(0f, 1f)
                    val leftX = fracX * (width - thumbW)
                    canvas.drawRoundRect(
                        leftX, height - barThick - margin,
                        leftX + thumbW, height - margin,
                        barThick, barThick, scrollTrackPaint
                    )
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
                isMultiTouchGesture = true
                isPanningDrag = false
                isSamplingDrag = false
                lastPanX = (event.getX(0) + event.getX(1)) / 2f
                lastPanY = (event.getY(0) + event.getY(1)) / 2f
                prevFocusX = lastPanX
                prevFocusY = lastPanY
                invalidate()
                onLoupeOverlayInvalidated?.invoke()
            } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
                val remIdx = if (event.actionIndex == 0) 1 else 0
                activePointerId = event.getPointerId(remIdx)
                lastPanX = event.getX(remIdx)
                lastPanY = event.getY(remIdx)
            }
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                isMultiTouchGesture = true
                isPanningDrag = false
                if (isSamplingDrag) {
                    isSamplingDrag = false
                    invalidate()
                    onLoupeOverlayInvalidated?.invoke()
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    isMultiTouchGesture = false
                    lastPanX = event.x
                    lastPanY = event.y
                    if (handToolEnabled) {
                        isPanningDrag = true
                        isSamplingDrag = false
                    } else {
                        isPanningDrag = false
                        isSamplingDrag = true
                        updateCrosshairAt(event.x, event.y)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 || isMultiTouchGesture) {
                        if (event.pointerCount >= 2) {
                            val mx = (event.getX(0) + event.getX(1)) / 2f
                            val my = (event.getY(0) + event.getY(1)) / 2f
                            panX += mx - lastPanX
                            panY += my - lastPanY
                            lastPanX = mx
                            lastPanY = my
                            clampPan()
                            invalidate()
                        } else if (handToolEnabled) {
                            val pIdx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                            val cx = event.getX(pIdx)
                            val cy = event.getY(pIdx)
                            panX += cx - lastPanX
                            panY += cy - lastPanY
                            lastPanX = cx
                            lastPanY = cy
                            clampPan()
                            invalidate()
                        }
                    } else if (handToolEnabled) {
                        isPanningDrag = true
                        isSamplingDrag = false
                        val pIdx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                        val cx = event.getX(pIdx)
                        val cy = event.getY(pIdx)
                        panX += cx - lastPanX
                        panY += cy - lastPanY
                        lastPanX = cx
                        lastPanY = cy
                        clampPan()
                        invalidate()
                    } else {
                        isSamplingDrag = true
                        updateCrosshairAt(event.x, event.y)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    isMultiTouchGesture = false
                    isPanningDrag = false
                    isSamplingDrag = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    onLoupeOverlayInvalidated?.invoke()
                    return true
                }
            }
            return true
        }

        private fun updateCrosshairAt(x: Float, y: Float) {
            val rect = currentDrawRect()
            if (rect.width() <= 1f || rect.height() <= 1f) return
            crossNormX = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
            crossNormY = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
            sampleAtCrosshair()
            invalidate()
            onLoupeOverlayInvalidated?.invoke()
        }
    }

    private class GradientThumbView(
        ctx: Context,
        private val dir: GradientDirection,
        private val sx: Float,
        private val sy: Float,
        private val ex: Float,
        private val ey: Float
    ) : View(ctx) {
        private var stops: List<GradientStop> = GradientFill().stops
        private var isSelected = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()

        fun updateStops(newStops: List<GradientStop>, selected: Boolean) {
            stops = newStops.sortedBy { it.offset }
            isSelected = selected
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 2f * resources.displayMetrics.density
            rect.set(pad, pad, width - pad, height - pad)
            val w = rect.width().coerceAtLeast(1f)
            val h = rect.height().coerceAtLeast(1f)
            val cols = stops.map { it.color }.toIntArray()
            val pos = stops.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
            val x0 = rect.left + sx * w
            val y0 = rect.top + sy * h
            val x1 = rect.left + ex * w
            val y1 = rect.top + ey * h
            paint.shader = if (dir == GradientDirection.RADIAL) {
                val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat().coerceAtLeast(1f)
                RadialGradient(x0, y0, r, cols, pos, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP)
            }
            val cr = 4f * resources.displayMetrics.density
            canvas.drawRoundRect(rect, cr, cr, paint)
            paint.shader = null
            borderPaint.strokeWidth = (if (isSelected) 2.5f else 1f) * resources.displayMetrics.density
            borderPaint.color = if (isSelected) Color.parseColor("#1E88E5") else Color.parseColor("#CBD5E1")
            canvas.drawRoundRect(rect, cr, cr, borderPaint)
        }
    }

    private class GradientPreviewBox(ctx: Context) : View(ctx) {
        private var dir: GradientDirection = GradientDirection.LINEAR_H
        private var stops: List<GradientStop> = GradientFill().stops
        private var sx = 0f
        private var sy = 0.5f
        private var ex = 1f
        private var ey = 0.5f
        private var draggingHandle = 0 // 0=none, 1=start, 2=end

        var onHandlesChanged: ((Float, Float, Float, Float) -> Unit)? = null

        private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = makeCheckerShader(ctx) }
        private val lineShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            strokeWidth = 2.6f * ctx.resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.6f * ctx.resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * ctx.resources.displayMetrics.density
        }
        private val arrowPath = Path()
        private val boxRect = RectF()

        fun updateState(
            newDir: GradientDirection,
            newStops: List<GradientStop>,
            nsx: Float,
            nsy: Float,
            nex: Float,
            ney: Float
        ) {
            dir = newDir
            stops = newStops.sortedBy { it.offset }
            sx = nsx.coerceIn(0f, 1f)
            sy = nsy.coerceIn(0f, 1f)
            ex = nex.coerceIn(0f, 1f)
            ey = ney.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            boxRect.set(0f, 0f, w, h)
            canvas.drawRect(boxRect, checkerPaint)

            val cols = stops.map { it.color }.toIntArray()
            val pos = stops.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
            val x0 = sx * w
            val y0 = sy * h
            val x1 = ex * w
            val y1 = ey * h

            gradPaint.shader = if (dir == GradientDirection.RADIAL) {
                val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat().coerceAtLeast(1f)
                RadialGradient(x0, y0, r, cols, pos, Shader.TileMode.CLAMP)
            } else {
                val fx1 = if (kotlin.math.abs(x1 - x0) < 0.5f && kotlin.math.abs(y1 - y0) < 0.5f) x0 + 1f else x1
                LinearGradient(x0, y0, fx1, y1, cols, pos, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(boxRect, gradPaint)
            gradPaint.shader = null

            // Guide line + direction chevrons
            canvas.drawLine(x0, y0, x1, y1, lineShadowPaint)
            canvas.drawLine(x0, y0, x1, y1, linePaint)
            val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
            if (dist > 36f) {
                val angleDeg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                val chev = 5f * resources.displayMetrics.density
                for (t in floatArrayOf(0.28f, 0.5f, 0.72f)) {
                    val ax = x0 + (x1 - x0) * t
                    val ay = y0 + (y1 - y0) * t
                    canvas.save()
                    canvas.translate(ax, ay)
                    canvas.rotate(angleDeg)
                    arrowPath.reset()
                    arrowPath.moveTo(-chev, -chev)
                    arrowPath.lineTo(chev * 0.6f, 0f)
                    arrowPath.lineTo(-chev, chev)
                    canvas.drawPath(arrowPath, lineShadowPaint)
                    canvas.drawPath(arrowPath, linePaint)
                    canvas.restore()
                }
            }

            // Start & End draggable handles
            fun drawHandle(hx: Float, hy: Float, active: Boolean) {
                val r = (if (active) 17f else 11.5f) * resources.displayMetrics.density
                canvas.drawCircle(hx, hy, r, handleFill)
                canvas.drawCircle(hx, hy, r, handleRing)
                val innerR = 4.5f * resources.displayMetrics.density
                canvas.drawCircle(hx, hy, innerR, handleRing)
            }
            drawHandle(x0, y0, draggingHandle == 1)
            drawHandle(x1, y1, draggingHandle == 2)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val x0 = sx * w
                    val y0 = sy * h
                    val x1 = ex * w
                    val y1 = ey * h
                    val d0 = hypot((event.x - x0).toDouble(), (event.y - y0).toDouble())
                    val d1 = hypot((event.x - x1).toDouble(), (event.y - y1).toDouble())
                    val hitR = 38f * resources.displayMetrics.density
                    draggingHandle = when {
                        d0 <= d1 && d0 <= hitR -> 1
                        d1 <= hitR -> 2
                        d0 <= d1 -> 1
                        else -> 2
                    }
                    updateDraggedHandle(event.x, event.y, w, h)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle != 0) {
                        updateDraggedHandle(event.x, event.y, w, h)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingHandle = 0
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun updateDraggedHandle(x: Float, y: Float, w: Float, h: Float) {
            val nx = (x / w).coerceIn(0f, 1f)
            val ny = (y / h).coerceIn(0f, 1f)
            if (draggingHandle == 1) {
                sx = nx
                sy = ny
            } else if (draggingHandle == 2) {
                ex = nx
                ey = ny
            }
            onHandlesChanged?.invoke(sx, sy, ex, ey)
            invalidate()
        }
    }

    private class GradientStopsBar(ctx: Context) : View(ctx) {
        private var stops: List<GradientStop> = GradientFill().stops
        private var selectedIdx = 0
        private var draggingIdx = -1

        var onSelectStop: ((Int) -> Unit)? = null
        var onMoveStop: ((Int, Float) -> Unit)? = null

        private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = makeCheckerShader(ctx) }
        private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * ctx.resources.displayMetrics.density
            color = Color.parseColor("#CBD5E1")
        }
        private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val barRect = RectF()
        private val markerPath = Path()

        fun updateState(newStops: List<GradientStop>, selIdx: Int) {
            stops = newStops.toList()
            selectedIdx = selIdx.coerceIn(0, (stops.size - 1).coerceAtLeast(0))
            invalidate()
        }

        private fun trackBounds(): Pair<Float, Float> {
            val pad = 12f * resources.displayMetrics.density
            return pad to (width - pad).coerceAtLeast(pad + 1f)
        }

        override fun onDraw(canvas: Canvas) {
            val (left, right) = trackBounds()
            val top = 2f * resources.displayMetrics.density
            val barBottom = 26f * resources.displayMetrics.density
            barRect.set(left, top, right, barBottom)

            canvas.drawRect(barRect, checkerPaint)
            val sorted = stops.sortedBy { it.offset }
            if (sorted.size >= 2) {
                val cols = sorted.map { it.color }.toIntArray()
                val pos = sorted.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
                gradPaint.shader = LinearGradient(left, 0f, right, 0f, cols, pos, Shader.TileMode.CLAMP)
                canvas.drawRect(barRect, gradPaint)
                gradPaint.shader = null
            }
            canvas.drawRect(barRect, barBorder)

            val mw = 7.5f * resources.displayMetrics.density
            val tipY = barBottom + 1f * resources.displayMetrics.density
            val midY = tipY + 6f * resources.displayMetrics.density
            val botY = height - 2f * resources.displayMetrics.density

            // Draw unselected first, selected on top
            for (pass in 0..1) {
                for (i in stops.indices) {
                    val isSel = i == selectedIdx
                    if ((pass == 0 && isSel) || (pass == 1 && !isSel)) continue
                    val st = stops[i]
                    val mx = left + st.offset.coerceIn(0f, 1f) * (right - left)
                    markerPath.reset()
                    markerPath.moveTo(mx, tipY)
                    markerPath.lineTo(mx + mw, midY)
                    markerPath.lineTo(mx + mw, botY)
                    markerPath.lineTo(mx - mw, botY)
                    markerPath.lineTo(mx - mw, midY)
                    markerPath.close()

                    markerFill.color = st.color
                    canvas.drawPath(markerPath, markerFill)
                    markerBorder.strokeWidth = (if (isSel) 2.2f else 1.2f) * resources.displayMetrics.density
                    markerBorder.color = if (isSel) Color.parseColor("#F97316") else Color.parseColor("#64748B")
                    canvas.drawPath(markerPath, markerBorder)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val (left, right) = trackBounds()
            val span = (right - left).coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    var bestIdx = -1
                    var bestDist = Float.MAX_VALUE
                    for (i in stops.indices) {
                        val mx = left + stops[i].offset * span
                        val d = kotlin.math.abs(event.x - mx)
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = i
                        }
                    }
                    val hitTol = 26f * resources.displayMetrics.density
                    if (bestIdx >= 0 && bestDist <= hitTol) {
                        draggingIdx = bestIdx
                        selectedIdx = bestIdx
                        onSelectStop?.invoke(bestIdx)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingIdx in stops.indices) {
                        val newOff = ((event.x - left) / span).coerceIn(0f, 1f)
                        val oldIdx = draggingIdx
                        onMoveStop?.invoke(oldIdx, newOff)
                        draggingIdx = selectedIdx
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingIdx = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }
    }
}
