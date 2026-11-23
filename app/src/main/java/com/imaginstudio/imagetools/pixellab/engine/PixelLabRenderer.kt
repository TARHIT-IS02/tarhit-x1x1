package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.util.LruCache
import com.imaginstudio.imagetools.pixellab.model.ArrowLayer
import com.imaginstudio.imagetools.pixellab.model.AssetRepository
import com.imaginstudio.imagetools.pixellab.model.BackgroundState
import com.imaginstudio.imagetools.pixellab.model.BackgroundType
import com.imaginstudio.imagetools.pixellab.model.BezierAnchor
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.BoundedShapeLabel
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.DrawPoint
import com.imaginstudio.imagetools.pixellab.model.DrawingLayer
import com.imaginstudio.imagetools.pixellab.model.DrawingStroke
import com.imaginstudio.imagetools.pixellab.model.EffectsState
import com.imaginstudio.imagetools.pixellab.model.FillType
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.ShadowConfig
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object PixelLabRenderer {

    private class RenderScratch {
        val camera = Camera()
        val matrix3D = Matrix()
        val matrixPersp = Matrix()
        val matrixShader = Matrix()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val auxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val auxTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val auxPath = Path()
        val rectF = RectF()
        val auxRectF = RectF()
        val srcPts = FloatArray(8)
        val dstPts = FloatArray(8)
        val radii8 = FloatArray(8)
        val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val srcAtopXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        var cachedVignetteShader: RadialGradient? = null
        var cachedVignetteKey: String = ""
        var cachedStripeShader: BitmapShader? = null
        var cachedStripeBmp: Bitmap? = null
        var cachedNoiseShader: BitmapShader? = null
        var cachedNoiseBmp: Bitmap? = null
    }

    private val maxCacheKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt().coerceIn(8192, 49152)
    private val bitmapCache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val chromaCache = LruCache<String, Bitmap>(6)
    private val drawingLayerCache = LruCache<String, Bitmap>(8)
    private val scratchHolder = ThreadLocal.withInitial { RenderScratch() }

    private fun scratch(): RenderScratch = scratchHolder.get()!!

    fun clearCache() {
        bitmapCache.evictAll()
        chromaCache.evictAll()
        drawingLayerCache.evictAll()
    }

    private fun openUriStream(context: Context, uriString: String): java.io.InputStream? {
        return when {
            uriString.startsWith("asset://") -> context.assets.open(uriString.removePrefix("asset://"))
            uriString.startsWith("/") -> java.io.FileInputStream(uriString)
            else -> context.contentResolver.openInputStream(Uri.parse(uriString))
        }
    }

    private fun calculateInSampleSize(rawW: Int, rawH: Int, maxDim: Int = 2048): Int {
        var sample = 1
        var w = rawW
        var h = rawH
        while (w > maxDim || h > maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    fun loadBitmap(context: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        bitmapCache.get(uriString)?.let { if (!it.isRecycled) return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openUriStream(context, uriString)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2048)
            } else 1
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            openUriStream(context, uriString)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input, null, opts)
                if (bmp != null) {
                    bitmapCache.put(uriString, bmp)
                }
                bmp
            }
        }.getOrNull()
    }

    fun loadHighResBitmap(context: Context, uriString: String, maxDim: Int = 4096): Bitmap? {
        if (uriString.isBlank()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openUriStream(context, uriString)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val caps = intArrayOf(maxDim, 3072, 2048, 1280)
            for (cap in caps) {
                if (cap > maxDim && cap != caps.first()) continue
                val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    calculateInSampleSize(bounds.outWidth, bounds.outHeight, cap)
                } else 1
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                try {
                    val bmp = openUriStream(context, uriString)?.use { input ->
                        BitmapFactory.decodeStream(input, null, opts)
                    }
                    if (bmp != null) return bmp
                } catch (_: OutOfMemoryError) {
                    // Retry with a smaller cap
                }
            }
            null
        }.getOrNull()
    }

    fun resolveTextLines(layer: TextLayer, paint: Paint, wrapWidth: Float? = null): List<String> {
        val paragraphs = layer.text.split("\n").ifEmpty { listOf(" ") }
        val maxW = wrapWidth?.takeIf { it > 20f && layer.curve == 0 } ?: return paragraphs
        val out = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.isEmpty()) {
                out.add(" ")
                continue
            }
            if (paint.measureText(para) <= maxW) {
                out.add(para)
                continue
            }
            val words = para.split(" ")
            var line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxW) {
                    line = StringBuilder(candidate)
                } else {
                    if (line.isNotEmpty()) {
                        out.add(line.toString())
                        line = StringBuilder()
                    }
                    if (paint.measureText(word) <= maxW) {
                        line.append(word)
                    } else {
                        var chunk = StringBuilder()
                        for (ch in word) {
                            val next = chunk.toString() + ch
                            if (chunk.isNotEmpty() && paint.measureText(next) > maxW) {
                                out.add(chunk.toString())
                                chunk = StringBuilder(ch.toString())
                            } else {
                                chunk.append(ch)
                            }
                        }
                        line = chunk
                    }
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out.ifEmpty { listOf(" ") }
    }

    fun measureTextBounds(layer: TextLayer, context: Context? = null): Pair<Float, Float> {
        val paint = scratch().auxTextPaint
        configureTextPaint(paint, context, layer, alphaOverride = 255)
        val lines = layer.text.split("\n").ifEmpty { listOf(" ") }
        var maxW = 0f
        for (line in lines) {
            maxW = max(maxW, paint.measureText(line.ifEmpty { " " }))
        }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * layer.lineSpacing
        val totalH = lineH * lines.size + 4f
        val totalW = maxW + layer.paddingLeft + layer.paddingRight
        return Pair(totalW.coerceAtLeast(60f), totalH.coerceAtLeast(48f))
    }

    fun measureWrappedTextBounds(layer: TextLayer, targetBoxW: Float, context: Context? = null): Pair<Float, Float> {
        val paint = scratch().auxTextPaint
        configureTextPaint(paint, context, layer, alphaOverride = 255)
        val padAndMargin = layer.paddingLeft + layer.paddingRight
        val availTextW = (targetBoxW - padAndMargin).coerceAtLeast(paint.measureText("W"))
        val lines = resolveTextLines(layer, paint, availTextW)
        var maxW = 0f
        for (line in lines) {
            maxW = max(maxW, paint.measureText(line.ifEmpty { " " }))
        }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * layer.lineSpacing
        val totalH = lineH * lines.size + 4f
        val finalW = max(targetBoxW, maxW + padAndMargin).coerceAtLeast(60f)
        return Pair(finalW, totalH.coerceAtLeast(48f))
    }

    fun exportToBitmap(
        context: Context,
        project: ProjectState,
        outWidth: Int,
        outHeight: Int,
        includeBackground: Boolean = true
    ): Bitmap {
        val out = Bitmap.createBitmap(outWidth.coerceAtLeast(1), outHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sx = outWidth.toFloat() / project.width.toFloat().coerceAtLeast(1f)
        val sy = outHeight.toFloat() / project.height.toFloat().coerceAtLeast(1f)
        canvas.save()
        canvas.scale(sx, sy)
        renderProject(context, canvas, project, skipTransparentBg = !includeBackground)
        canvas.restore()
        return out
    }

    fun renderLayerPreviewBitmap(
        context: Context,
        layer: CanvasLayer,
        size: Int = 96
    ): Bitmap {
        val s = size.coerceIn(48, 256)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sc = scratch()

        val tile = (s / 8).coerceAtLeast(6)
        val pLight = sc.paint.apply { reset(); color = Color.parseColor("#7A828E") }
        val pDark = sc.auxPaint.apply { reset(); color = Color.parseColor("#646B75") }
        var y = 0
        while (y < s) {
            var x = 0
            val rowOdd = (y / tile) % 2 == 1
            while (x < s) {
                val colOdd = (x / tile) % 2 == 1
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + tile).toFloat(),
                    (y + tile).toFloat(),
                    if (rowOdd xor colOdd) pDark else pLight
                )
                x += tile
            }
            y += tile
        }

        val lw = layer.width.coerceAtLeast(16f)
        val lh = layer.height.coerceAtLeast(16f)
        val fitScale = min((s * 0.78f) / lw, (s * 0.78f) / lh)

        canvas.save()
        canvas.translate(s / 2f, s / 2f)
        canvas.scale(fitScale, fitScale)
        canvas.translate(-layer.x, -layer.y)
        renderLayer(context, canvas, layer.copyBase(hidden = false))
        canvas.restore()
        return out
    }

    fun renderLayerHighResBitmap(
        context: Context,
        layer: CanvasLayer,
        maxDim: Int = 1600
    ): Bitmap? {
        if (layer is ImageLayer) {
            val srcBmp = loadHighResBitmap(context, layer.uriString, maxDim = 4096)
            if (srcBmp != null) {
                val hasCorner = layer.cornerRadii.any { it > 0.5f }
                val isUnmodified = !layer.flipH && !layer.flipV &&
                    !layer.circularCrop && !hasCorner &&
                    !layer.colorOverlayEnabled && !layer.removeColorEnabled &&
                    layer.brightness == 0 && layer.contrast == 0 &&
                    layer.saturation == 0 && layer.hue == 0 &&
                    layer.eraserStrokes.isEmpty() && !layer.stroke.enabled &&
                    !layer.shadow.enabled && !layer.innerShadow.enabled &&
                    !layer.maskEnabled && !layer.perspectiveEnabled &&
                    layer.opacity >= 100
                if (isUnmodified) {
                    return srcBmp
                }

                val outScale = min(1f, 2800f / max(srcBmp.width, srcBmp.height).coerceAtLeast(1))
                val outW = (srcBmp.width * outScale).toInt().coerceAtLeast(1)
                val outH = (srcBmp.height * outScale).toInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val sc = scratch()
                val scaleFromLayer = outW.toFloat() / layer.width.coerceAtLeast(1f)
                val hw = outW / 2f
                val hh = outH / 2f

                var workingBmp = srcBmp
                if (layer.removeColorEnabled) {
                    workingBmp = applyChromaKey(srcBmp, layer.removeColorTarget, layer.removeColorTolerance)
                }

                canvas.save()
                canvas.translate(hw, hh)

                val rect = sc.rectF
                rect.set(-hw, -hh, hw, hh)
                val maxR = min(outW.toFloat(), outH.toFloat()) / 2f
                val r0 = if (layer.cornerEnabled.getOrElse(0) { true }) (layer.cornerRadii.getOrElse(0) { 0f } * scaleFromLayer).coerceIn(0f, maxR) else 0f
                val r1 = if (layer.cornerEnabled.getOrElse(1) { true }) (layer.cornerRadii.getOrElse(1) { 0f } * scaleFromLayer).coerceIn(0f, maxR) else 0f
                val r2 = if (layer.cornerEnabled.getOrElse(2) { true }) (layer.cornerRadii.getOrElse(2) { 0f } * scaleFromLayer).coerceIn(0f, maxR) else 0f
                val r3 = if (layer.cornerEnabled.getOrElse(3) { true }) (layer.cornerRadii.getOrElse(3) { 0f } * scaleFromLayer).coerceIn(0f, maxR) else 0f
                val hasScaledCorner = r0 > 0.5f || r1 > 0.5f || r2 > 0.5f || r3 > 0.5f

                val shapePath = sc.path
                shapePath.reset()
                when {
                    layer.circularCrop -> shapePath.addOval(rect, Path.Direction.CW)
                    hasScaledCorner -> {
                        val rad8 = sc.radii8
                        rad8[0] = r0; rad8[1] = r0
                        rad8[2] = r1; rad8[3] = r1
                        rad8[4] = r2; rad8[5] = r2
                        rad8[6] = r3; rad8[7] = r3
                        shapePath.addRoundRect(rect, rad8, Path.Direction.CW)
                    }
                    else -> shapePath.addRect(rect, Path.Direction.CW)
                }

                if (layer.maskEnabled && layer.maskPoints.size >= 3) {
                    val maskPath = sc.auxPath
                    maskPath.reset()
                    maskPath.moveTo((layer.maskPoints[0].x - 0.5f) * outW, (layer.maskPoints[0].y - 0.5f) * outH)
                    for (i in 1 until layer.maskPoints.size) {
                        maskPath.lineTo((layer.maskPoints[i].x - 0.5f) * outW, (layer.maskPoints[i].y - 0.5f) * outH)
                    }
                    maskPath.close()
                    if (layer.maskInvert) {
                        val outer = Path().apply { addRect(-hw * 2f, -hh * 2f, hw * 2f, hh * 2f, Path.Direction.CW) }
                        outer.op(maskPath, Path.Op.DIFFERENCE)
                        canvas.clipPath(outer)
                    } else {
                        canvas.clipPath(maskPath)
                    }
                }

                if (layer.circularCrop || hasScaledCorner) {
                    canvas.clipPath(shapePath)
                }

                val alphaFactor = (layer.opacity.coerceIn(0, 100) / 100f)
                val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)
                val isGradientTint = layer.colorOverlayEnabled && layer.colorOverlayFillType == FillType.GRADIENT
                val needOffscreen = layer.eraserStrokes.isNotEmpty() || isGradientTint
                val offSave = if (needOffscreen) {
                    canvas.saveLayer(-hw, -hh, hw, hh, null)
                } else {
                    canvas.save()
                }

                canvas.save()
                canvas.scale(if (layer.flipH) -1f else 1f, if (layer.flipV) -1f else 1f)
                val paint = sc.bitmapPaint
                paint.reset()
                paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                paint.alpha = mainAlpha
                paint.colorFilter = if (layer.colorOverlayEnabled && layer.colorOverlayFillType == FillType.COLOR) {
                    PorterDuffColorFilter(layer.colorOverlay, PorterDuff.Mode.SRC_ATOP)
                } else {
                    buildColorMatrixFilter(layer.brightness, layer.contrast, layer.saturation, layer.hue)
                }
                canvas.drawBitmap(workingBmp, null, rect, paint)
                paint.colorFilter = null
                canvas.restore()

                if (isGradientTint) {
                    val gp = sc.paint
                    gp.reset()
                    gp.flags = Paint.ANTI_ALIAS_FLAG
                    gp.shader = ShapePathBuilder.createShader(layer.colorOverlayGradient, -hw, -hh, outW.toFloat(), outH.toFloat())
                    gp.alpha = mainAlpha
                    gp.xfermode = sc.srcAtopXfermode
                    canvas.drawRect(rect, gp)
                    gp.shader = null
                    gp.xfermode = null
                }

                if (layer.eraserStrokes.isNotEmpty()) {
                    val ptsBuf = ArrayList<DrawPoint>(64)
                    for (es in layer.eraserStrokes) {
                        ptsBuf.clear()
                        for (pt in es.points) {
                            ptsBuf.add(DrawPoint((pt.x - 0.5f) * outW, (pt.y - 0.5f) * outH))
                        }
                        drawStrokePoints(
                            canvas = canvas,
                            points = ptsBuf,
                            color = Color.TRANSPARENT,
                            width = es.width * scaleFromLayer,
                            blur = es.blur * scaleFromLayer,
                            dashed = es.dashed,
                            neon = es.neon,
                            fill = es.fill,
                            eraser = true,
                            alphaFactor = 1f
                        )
                    }
                }

                canvas.restoreToCount(offSave)
                canvas.restore()

                if (workingBmp !== srcBmp && !workingBmp.isRecycled) workingBmp.recycle()
                if (!srcBmp.isRecycled) srcBmp.recycle()
                return out
            }
        }

        val lw = layer.width.coerceAtLeast(32f)
        val lh = layer.height.coerceAtLeast(32f)
        val padFrac = 1.12f
        val targetMax = maxDim.coerceIn(512, 2048).toFloat()
        val fitScale = targetMax / max(lw * padFrac, lh * padFrac)
        val outW = (lw * padFrac * fitScale).toInt().coerceIn(64, 2048)
        val outH = (lh * padFrac * fitScale).toInt().coerceIn(64, 2048)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.save()
        canvas.translate(outW / 2f, outH / 2f)
        canvas.scale(fitScale, fitScale)
        canvas.translate(-layer.x, -layer.y)
        renderLayer(context, canvas, layer.copyBase(hidden = false))
        canvas.restore()
        return out
    }

    fun renderBackgroundHighResBitmap(
        context: Context,
        project: ProjectState,
        maxDim: Int = 2400
    ): Bitmap? {
        val bg = project.background
        val pw = project.width.coerceAtLeast(1)
        val ph = project.height.coerceAtLeast(1)
        if (bg.type == BackgroundType.IMAGE && !bg.imageUri.isNullOrBlank()) {
            val srcBmp = loadHighResBitmap(context, bg.imageUri, maxDim = 4096)
            if (srcBmp != null) {
                val targetMax = max(max(srcBmp.width, srcBmp.height), max(pw, ph)).coerceIn(256, 3200)
                val scale = targetMax.toFloat() / max(pw, ph).toFloat()
                val outW = (pw * scale).toInt().coerceAtLeast(64)
                val outH = (ph * scale).toInt().coerceAtLeast(64)
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val sc = scratch()
                val w = outW.toFloat()
                val h = outH.toFloat()

                val scaledBg = bg.copy(
                    cornerRadii = bg.cornerRadii.map { it * scale }
                )
                val saveCount = if (buildArtboardClipPath(scaledBg, w, h, sc.path, sc.auxRectF, sc.radii8)) {
                    val c = canvas.save()
                    canvas.clipPath(sc.path)
                    c
                } else -1

                val paint = sc.bitmapPaint
                paint.reset()
                paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                paint.colorFilter = buildColorMatrixFilter(
                    project.effects.brightness,
                    project.effects.contrast,
                    project.effects.saturation,
                    project.effects.hue
                )
                canvas.save()
                canvas.translate(w / 2f, h / 2f)
                if (bg.imageRotation != 0) canvas.rotate(bg.imageRotation.toFloat())
                canvas.scale(if (bg.flipH) -1f else 1f, if (bg.flipV) -1f else 1f)
                val isSwapped = bg.imageRotation % 180 != 0
                val targetW = if (isSwapped) h else w
                val targetH = if (isSwapped) w else h
                val imgScale = max(targetW / srcBmp.width, targetH / srcBmp.height)
                val drawW = srcBmp.width * imgScale
                val drawH = srcBmp.height * imgScale
                sc.rectF.set(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f)
                canvas.drawBitmap(srcBmp, null, sc.rectF, paint)
                paint.colorFilter = null
                canvas.restore()
                if (saveCount >= 0) canvas.restoreToCount(saveCount)
                if (!srcBmp.isRecycled) srcBmp.recycle()
                return out
            }
        }
        val scale = min(1f, maxDim.toFloat() / max(pw, ph).toFloat())
        val bw = (pw * scale).toInt().coerceAtLeast(64)
        val bh = (ph * scale).toInt().coerceAtLeast(64)
        return exportToBitmap(context, project.copy(layers = emptyList()), bw, bh, includeBackground = true)
    }

    fun renderProjectPreviewBitmap(
        context: Context,
        project: ProjectState,
        maxDim: Int = 180
    ): Bitmap {
        val pw = project.width.coerceAtLeast(1)
        val ph = project.height.coerceAtLeast(1)
        val scale = min(maxDim.toFloat() / pw, maxDim.toFloat() / ph)
        val outW = (pw * scale).toInt().coerceIn(32, maxDim)
        val outH = (ph * scale).toInt().coerceIn(32, maxDim)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sc = scratch()

        if (project.background.type == BackgroundType.TRANSPARENT ||
            project.background.circular ||
            hasBgCornerRadius(project.background, pw.toFloat(), ph.toFloat())
        ) {
            val tile = 14
            val pLight = sc.paint.apply { reset(); color = Color.WHITE }
            val pDark = sc.auxPaint.apply { reset(); color = Color.parseColor("#D9D9D9") }
            var y = 0
            while (y < outH) {
                var x = 0
                val rowOdd = (y / tile) % 2 == 1
                while (x < outW) {
                    val colOdd = (x / tile) % 2 == 1
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + tile).toFloat(),
                        (y + tile).toFloat(),
                        if (rowOdd xor colOdd) pDark else pLight
                    )
                    x += tile
                }
                y += tile
            }
        }

        val sx = outW.toFloat() / pw
        val sy = outH.toFloat() / ph
        canvas.save()
        canvas.scale(sx, sy)
        renderProject(context, canvas, project, skipTransparentBg = false, clipToArtboard = true)
        canvas.restore()
        return out
    }

    fun hasBgCornerRadius(bg: BackgroundState, w: Float, h: Float): Boolean {
        if (bg.circular) return false
        val maxR = min(w, h) / 2f
        if (maxR <= 0f) return false
        for (i in 0..3) {
            if (bg.cornerEnabled.getOrElse(i) { true } && bg.cornerRadii.getOrElse(i) { 0f }.coerceIn(0f, maxR) > 0.5f) {
                return true
            }
        }
        return false
    }

    fun buildArtboardClipPath(
        bg: BackgroundState,
        w: Float,
        h: Float,
        outPath: Path,
        outRect: RectF,
        radii8: FloatArray
    ): Boolean {
        outPath.reset()
        outRect.set(0f, 0f, w, h)
        if (bg.circular) {
            outPath.addOval(outRect, Path.Direction.CW)
            return true
        }
        val maxR = min(w, h) / 2f
        val r0 = if (bg.cornerEnabled.getOrElse(0) { true }) bg.cornerRadii.getOrElse(0) { 0f }.coerceIn(0f, maxR) else 0f
        val r1 = if (bg.cornerEnabled.getOrElse(1) { true }) bg.cornerRadii.getOrElse(1) { 0f }.coerceIn(0f, maxR) else 0f
        val r2 = if (bg.cornerEnabled.getOrElse(2) { true }) bg.cornerRadii.getOrElse(2) { 0f }.coerceIn(0f, maxR) else 0f
        val r3 = if (bg.cornerEnabled.getOrElse(3) { true }) bg.cornerRadii.getOrElse(3) { 0f }.coerceIn(0f, maxR) else 0f
        if (r0 > 0.5f || r1 > 0.5f || r2 > 0.5f || r3 > 0.5f) {
            radii8[0] = r0; radii8[1] = r0
            radii8[2] = r1; radii8[3] = r1
            radii8[4] = r2; radii8[5] = r2
            radii8[6] = r3; radii8[7] = r3
            outPath.addRoundRect(outRect, radii8, Path.Direction.CW)
            return true
        }
        return false
    }

    private fun clipArtboardShape(canvas: Canvas, bg: BackgroundState, w: Float, h: Float, sc: RenderScratch) {
        canvas.clipRect(0f, 0f, w, h)
        if (buildArtboardClipPath(bg, w, h, sc.auxPath, sc.auxRectF, sc.radii8)) {
            canvas.clipPath(sc.auxPath)
        }
    }

    fun renderProject(
        context: Context,
        canvas: Canvas,
        project: ProjectState,
        skipTransparentBg: Boolean = false,
        clipToArtboard: Boolean = true,
        overrideImageBitmaps: Map<String, Bitmap> = emptyMap(),
        overrideImageSizes: Map<String, Pair<Float, Float>> = emptyMap()
    ) {
        val w = project.width.toFloat()
        val h = project.height.toFloat()
        val sc = scratch()
        val isPureTransparent = project.background.type == BackgroundType.TRANSPARENT

        // 1 & 2. Artboard Background + Stripes & Noise always clip strictly to the artboard shape
        val bgSave = canvas.save()
        clipArtboardShape(canvas, project.background, w, h, sc)
        if (!skipTransparentBg) {
            renderBackground(context, canvas, project, w, h)
        }
        if (!isPureTransparent && project.effects.stripesEnabled) {
            renderStripes(context, canvas, w, h, project.effects)
        }
        if (!isPureTransparent && project.effects.noiseEnabled) {
            renderNoise(context, canvas, w, h, project.effects)
        }
        canvas.restoreToCount(bgSave)

        // 3. Layers: clipped on export/preview, UNCLIPPED on live stage (Photoshop-style workspace)
        val layerSave = if (clipToArtboard) {
            canvas.save().also { clipArtboardShape(canvas, project.background, w, h, sc) }
        } else {
            -1
        }
        for (layer in project.layers) {
            if (!layer.hidden) {
                renderLayer(context, canvas, layer, overrideImageBitmaps, overrideImageSizes)
            }
        }
        if (layerSave >= 0) {
            canvas.restoreToCount(layerSave)
        }

        // 4. Foreground Vignette clipped to the artboard shape
        if (!isPureTransparent && project.effects.vignetteEnabled) {
            val vigSave = canvas.save()
            clipArtboardShape(canvas, project.background, w, h, sc)
            renderVignette(canvas, w, h, project.effects)
            canvas.restoreToCount(vigSave)
        }
    }

    fun renderBackground(
        context: Context,
        canvas: Canvas,
        project: ProjectState,
        w: Float,
        h: Float
    ) {
        val bg = project.background
        if (bg.type == BackgroundType.TRANSPARENT) return

        val sc = scratch()
        val fxFilter = buildColorMatrixFilter(
            project.effects.brightness,
            project.effects.contrast,
            project.effects.saturation,
            project.effects.hue
        )

        val saveCount = if (buildArtboardClipPath(bg, w, h, sc.path, sc.auxRectF, sc.radii8)) {
            val c = canvas.save()
            canvas.clipPath(sc.path)
            c
        } else -1

        val paint = sc.bitmapPaint
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        paint.colorFilter = fxFilter

        when (bg.type) {
            BackgroundType.COLOR -> {
                paint.color = bg.color
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            BackgroundType.GRADIENT -> {
                paint.shader = ShapePathBuilder.createShader(bg.gradient, 0f, 0f, w, h)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
            BackgroundType.IMAGE -> {
                val bmp = bg.imageUri?.let { loadBitmap(context, it) }
                if (bmp != null) {
                    canvas.save()
                    canvas.translate(w / 2f, h / 2f)
                    if (bg.imageRotation != 0) canvas.rotate(bg.imageRotation.toFloat())
                    canvas.scale(if (bg.flipH) -1f else 1f, if (bg.flipV) -1f else 1f)
                    val isSwapped = bg.imageRotation % 180 != 0
                    val targetW = if (isSwapped) h else w
                    val targetH = if (isSwapped) w else h
                    val scale = max(targetW / bmp.width, targetH / bmp.height)
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    sc.rectF.set(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f)
                    canvas.drawBitmap(bmp, null, sc.rectF, paint)
                    canvas.restore()
                } else {
                    paint.color = bg.color
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
            BackgroundType.TRANSPARENT -> Unit
        }
        paint.colorFilter = null

        if (saveCount >= 0) canvas.restoreToCount(saveCount)
    }

    private fun renderStripes(context: Context, canvas: Canvas, w: Float, h: Float, fx: EffectsState) {
        val sc = scratch()
        val assetStripe = loadBitmap(context, "asset://effects/stripe.png")
        if (assetStripe != null) {
            val scale = (h / (fx.stripesRepeat.coerceIn(2, 40) * assetStripe.height.coerceAtLeast(1) * 0.4f)).coerceIn(0.25f, 6f)
            sc.matrixShader.setScale(scale, scale)
            val shader = if (sc.cachedStripeBmp === assetStripe && sc.cachedStripeShader != null) {
                sc.cachedStripeShader!!
            } else {
                BitmapShader(assetStripe, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                    sc.cachedStripeBmp = assetStripe
                    sc.cachedStripeShader = it
                }
            }
            shader.setLocalMatrix(sc.matrixShader)
            val p = sc.bitmapPaint
            p.reset()
            p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
            p.shader = shader
            p.alpha = fx.stripesAlpha.coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
            return
        }
        val count = fx.stripesRepeat.coerceIn(2, 40)
        val stripeH = h / (count * 2f)
        val paint = sc.paint
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG
        paint.color = Color.argb(fx.stripesAlpha.coerceIn(0, 255), 255, 255, 255)
        var y = 0f
        while (y < h) {
            canvas.drawRect(0f, y, w, y + stripeH, paint)
            y += stripeH * 2f
        }
    }

    private fun renderNoise(context: Context, canvas: Canvas, w: Float, h: Float, fx: EffectsState) {
        val sc = scratch()
        val assetNoise = loadBitmap(context, "asset://effects/noise.png")
        if (assetNoise != null) {
            val scale = (fx.noiseScale.coerceIn(2, 24) / 7f).coerceIn(0.4f, 4f)
            sc.matrixShader.setScale(scale, scale)
            val shader = if (sc.cachedNoiseBmp === assetNoise && sc.cachedNoiseShader != null) {
                sc.cachedNoiseShader!!
            } else {
                BitmapShader(assetNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                    sc.cachedNoiseBmp = assetNoise
                    sc.cachedNoiseShader = it
                }
            }
            shader.setLocalMatrix(sc.matrixShader)
            val p = sc.bitmapPaint
            p.reset()
            p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
            p.shader = shader
            p.alpha = fx.noiseAlpha.coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
            return
        }
        val step = (fx.noiseScale.coerceIn(2, 24) * 3).toFloat()
        val paint = sc.paint
        paint.reset()
        paint.strokeWidth = step * 0.7f
        val rng = Random(42)
        val alpha = fx.noiseAlpha.coerceIn(0, 255)
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                val lum = if (rng.nextBoolean()) 255 else 0
                if (rng.nextFloat() > 0.45f) {
                    paint.color = Color.argb((alpha * 0.55f).toInt().coerceIn(0, 255), lum, lum, lum)
                    canvas.drawPoint(x, y, paint)
                }
                x += step
            }
            y += step
        }
    }

    private fun renderVignette(canvas: Canvas, w: Float, h: Float, fx: EffectsState) {
        val sc = scratch()
        val alpha = fx.vignetteAlpha.coerceIn(0, 255)
        val key = "${w.toInt()}x${h.toInt()}_${fx.vignetteColor}_$alpha"
        val shader = if (sc.cachedVignetteKey == key && sc.cachedVignetteShader != null) {
            sc.cachedVignetteShader!!
        } else {
            val cx = w / 2f
            val cy = h / 2f
            val radius = max(w, h) * 0.72f
            val edgeColor = Color.argb(
                alpha,
                Color.red(fx.vignetteColor),
                Color.green(fx.vignetteColor),
                Color.blue(fx.vignetteColor)
            )
            RadialGradient(
                cx, cy, radius.coerceAtLeast(1f),
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edgeColor),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            ).also {
                sc.cachedVignetteKey = key
                sc.cachedVignetteShader = it
            }
        }
        val p = sc.paint
        p.reset()
        p.flags = Paint.ANTI_ALIAS_FLAG
        p.shader = shader
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    fun renderLayer(
        context: Context,
        canvas: Canvas,
        layer: CanvasLayer,
        overrideImageBitmaps: Map<String, Bitmap> = emptyMap(),
        overrideImageSizes: Map<String, Pair<Float, Float>> = emptyMap()
    ) {
        val sc = scratch()
        canvas.save()
        canvas.translate(layer.x, layer.y)

        if (layer.rotX != 0f || layer.rotY != 0f) {
            sc.camera.save()
            sc.camera.rotateX(layer.rotX)
            sc.camera.rotateY(layer.rotY)
            sc.camera.getMatrix(sc.matrix3D)
            sc.camera.restore()
            canvas.concat(sc.matrix3D)
        }
        if (layer.rotation != 0f) {
            canvas.rotate(layer.rotation)
        }

        val perspective = when (layer) {
            is TextLayer -> if (layer.perspectiveEnabled && layer.perspectivePoints.size == 4) layer.perspectivePoints else null
            is ShapeLayer -> if (layer.perspectiveEnabled && layer.perspectivePoints.size == 4) layer.perspectivePoints else null
            is ImageLayer -> if (layer.perspectiveEnabled && layer.perspectivePoints.size == 4) layer.perspectivePoints else null
            else -> null
        }
        if (perspective != null) {
            val hw = layer.width / 2f
            val hh = layer.height / 2f
            val src = sc.srcPts
            src[0] = -hw; src[1] = -hh
            src[2] = hw; src[3] = -hh
            src[4] = hw; src[5] = hh
            src[6] = -hw; src[7] = hh

            val dst = sc.dstPts
            for (i in 0..3) {
                dst[i * 2] = (perspective[i].x - 0.5f) * layer.width
                dst[i * 2 + 1] = (perspective[i].y - 0.5f) * layer.height
            }
            if (sc.matrixPersp.setPolyToPoly(src, 0, dst, 0, 4)) {
                canvas.concat(sc.matrixPersp)
            }
        }

        applyLayerMaskIfNeeded(canvas, layer, sc)

        val alphaFactor = (layer.opacity.coerceIn(0, 100) / 100f)

        when (layer) {
            is TextLayer -> renderTextLayer(context, canvas, layer, alphaFactor, sc)
            is ShapeLayer -> renderShapeLayer(context, canvas, layer, alphaFactor, sc)
            is BezierLayer -> renderBezierLayer(canvas, layer, alphaFactor, sc)
            is ArrowLayer -> renderArrowLayer(canvas, layer, alphaFactor, sc)
            is DrawingLayer -> renderDrawingLayer(canvas, layer, alphaFactor, sc)
            is ImageLayer -> renderImageLayer(
                context, canvas, layer, alphaFactor, sc,
                overrideImageBitmaps[layer.id],
                overrideImageSizes[layer.id]
            )
        }

        canvas.restore()
    }

    private fun applyLayerMaskIfNeeded(canvas: Canvas, layer: CanvasLayer, sc: RenderScratch) {
        val enabled: Boolean
        val invert: Boolean
        val pts: List<DrawPoint>
        when (layer) {
            is TextLayer -> { enabled = layer.maskEnabled; invert = layer.maskInvert; pts = layer.maskPoints }
            is ShapeLayer -> { enabled = layer.maskEnabled; invert = layer.maskInvert; pts = layer.maskPoints }
            is ImageLayer -> { enabled = layer.maskEnabled; invert = layer.maskInvert; pts = layer.maskPoints }
            else -> return
        }
        if (!enabled || pts.size < 3) return
        val maskPath = sc.path
        maskPath.reset()
        val hw = layer.width / 2f
        val hh = layer.height / 2f
        maskPath.moveTo((pts[0].x - 0.5f) * layer.width, (pts[0].y - 0.5f) * layer.height)
        for (i in 1 until pts.size) {
            maskPath.lineTo((pts[i].x - 0.5f) * layer.width, (pts[i].y - 0.5f) * layer.height)
        }
        maskPath.close()
        if (invert) {
            val outer = sc.auxPath
            outer.reset()
            outer.addRect(-hw * 3f, -hh * 3f, hw * 3f, hh * 3f, Path.Direction.CW)
            outer.op(maskPath, Path.Op.DIFFERENCE)
            canvas.clipPath(outer)
        } else {
            canvas.clipPath(maskPath)
        }
    }

    private fun renderTextLayer(
        context: Context,
        canvas: Canvas,
        layer: TextLayer,
        alphaFactor: Float,
        sc: RenderScratch
    ) {
        val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)
        val basePaint = sc.textPaint
        configureTextPaint(basePaint, context, layer, mainAlpha)
        val padAndMargin = layer.paddingLeft + layer.paddingRight
        val availTextW = (layer.width - padAndMargin).coerceAtLeast(basePaint.measureText("W"))
        val rawParagraphs = layer.text.split("\n").ifEmpty { listOf(" ") }
        var rawMaxW = 0f
        for (para in rawParagraphs) {
            rawMaxW = max(rawMaxW, basePaint.measureText(para.ifEmpty { " " }))
        }
        val lines = if (layer.curve == 0 && availTextW < rawMaxW - 2f) {
            resolveTextLines(layer, basePaint, availTextW)
        } else {
            rawParagraphs
        }
        val fm = basePaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * layer.lineSpacing
        val totalTextH = lineHeight * lines.size
        var maxLineW = 0f
        for (l in lines) {
            maxLineW = max(maxLineW, basePaint.measureText(l.ifEmpty { " " }))
        }
        val padCenterShiftX = (layer.paddingLeft - layer.paddingRight) * 0.5f

        // Optional Background box
        if (layer.background.enabled) {
            val bg = layer.background
            val bgRect = sc.rectF
            bgRect.set(
                -maxLineW / 2f - bg.padLeft + padCenterShiftX,
                -totalTextH / 2f - bg.padTop,
                maxLineW / 2f + bg.padRight + padCenterShiftX,
                totalTextH / 2f + bg.padBottom
            )
            val bgPaint = sc.paint
            bgPaint.reset()
            bgPaint.flags = Paint.ANTI_ALIAS_FLAG
            if (bg.fillType == FillType.GRADIENT) {
                bgPaint.shader = ShapePathBuilder.createShader(bg.gradient, bgRect.left, bgRect.top, bgRect.width(), bgRect.height())
            } else {
                bgPaint.color = bg.color
            }
            bgPaint.alpha = mainAlpha
            canvas.drawRoundRect(bgRect, bg.radius, bg.radius, bgPaint)
            bgPaint.shader = null
        }

        // Curved text path (non-zero curve)
        if (layer.curve != 0) {
            val flatText = layer.text.replace("\n", " ")
            val radius = (1800f / abs(layer.curve).coerceAtLeast(1)).coerceIn(80f, 1800f)
            val path = sc.path
            path.reset()
            val arcRect = sc.rectF
            if (layer.curve > 0) {
                arcRect.set(-radius, -radius * 0.1f, radius, radius * 1.9f)
            } else {
                arcRect.set(-radius, -radius * 1.9f, radius, radius * 0.1f)
            }
            val sweep = if (layer.curve > 0) 170f else -170f
            val start = if (layer.curve > 0) -90f - sweep / 2f else 90f - sweep / 2f
            path.addArc(arcRect, start, sweep)
            basePaint.textAlign = Paint.Align.CENTER
            applyTextFill(context, basePaint, layer, -maxLineW / 2f, -totalTextH / 2f, maxLineW, totalTextH, mainAlpha)
            if (layer.stroke.enabled) {
                val sp = sc.auxTextPaint
                sp.set(basePaint)
                sp.shader = null
                sp.style = Paint.Style.STROKE
                sp.strokeJoin = Paint.Join.ROUND
                sp.strokeWidth = layer.stroke.width
                sp.color = multiplyAlpha(layer.stroke.color, alphaFactor)
                canvas.drawTextOnPath(flatText, path, padCenterShiftX, 0f, sp)
            }
            canvas.drawTextOnPath(flatText, path, padCenterShiftX, 0f, basePaint)
            basePaint.shader = null
            return
        }

        val startY = -totalTextH / 2f - fm.ascent

        fun drawLinesWithPaint(p: Paint, dx: Float = 0f, dy: Float = 0f) {
            val alignOffset = when (layer.align) {
                0 -> -maxLineW / 2f
                2 -> maxLineW / 2f
                else -> 0f
            } + padCenterShiftX + dx
            p.textAlign = when (layer.align) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            for (idx in lines.indices) {
                canvas.drawText(lines[idx], alignOffset, startY + idx * lineHeight + dy, p)
            }
        }

        // Reflection
        if (layer.reflection.enabled) {
            canvas.save()
            canvas.translate(0f, totalTextH + layer.reflection.offset)
            canvas.scale(1f, -0.55f)
            val reflAlpha = ((layer.reflection.opacity / 100f) * mainAlpha).toInt().coerceIn(0, 255)
            val rp = sc.auxTextPaint
            configureTextPaint(rp, context, layer, reflAlpha)
            applyTextFill(context, rp, layer, -maxLineW / 2f, -totalTextH / 2f, maxLineW, totalTextH, reflAlpha)
            drawLinesWithPaint(rp)
            rp.shader = null
            canvas.restore()
        }

        // Outer Shadow / Glow
        if (layer.shadow.enabled) {
            val sp = sc.auxTextPaint
            configureTextPaint(sp, context, layer, mainAlpha)
            sp.color = multiplyAlpha(layer.shadow.color, alphaFactor)
            sp.shader = null
            sp.maskFilter = if (layer.shadow.blur > 0.5f) {
                BlurMaskFilter(layer.shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            } else null
            drawLinesWithPaint(sp, layer.shadow.offsetX, layer.shadow.offsetY)
            sp.maskFilter = null
        }

        // 3D Extrusion
        if (layer.threeD.enabled && layer.threeD.depth > 0) {
            val rad = layer.threeD.angle * PI / 180.0
            val ux = cos(rad).toFloat()
            val uy = sin(rad).toFloat()
            val baseExtrudeColor = if (layer.threeD.colorAuto) {
                darkenColor(layer.color, layer.threeD.darken / 100f)
            } else {
                layer.threeD.color
            }
            val tdPaint = sc.auxTextPaint
            configureTextPaint(tdPaint, context, layer, mainAlpha)
            tdPaint.color = multiplyAlpha(baseExtrudeColor, alphaFactor)
            tdPaint.shader = null
            for (step in layer.threeD.depth downTo 1) {
                drawLinesWithPaint(tdPaint, ux * step * 0.9f, uy * step * 0.9f)
            }
        }

        // Stroke
        if (layer.stroke.enabled && layer.stroke.width > 0f) {
            val strokePaint = sc.auxTextPaint
            configureTextPaint(strokePaint, context, layer, mainAlpha)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = layer.stroke.width
            strokePaint.color = multiplyAlpha(layer.stroke.color, alphaFactor)
            strokePaint.shader = null
            drawLinesWithPaint(strokePaint)
        }

        // Hardware-accelerated directional bevel emboss (replaces deprecated CPU-only EmbossMaskFilter)
        if (layer.emboss.enabled) {
            val rad = layer.emboss.lightAngle * PI.toFloat() / 180f
            val bevelDist = (layer.emboss.bevel / 12f).coerceIn(1f, 5f)
            val dx = cos(rad) * bevelDist
            val dy = sin(rad) * bevelDist
            val embAlpha = ((layer.emboss.intensity / 100f) * 160f * alphaFactor).toInt().coerceIn(25, 180)
            val ep = sc.auxTextPaint
            configureTextPaint(ep, context, layer, embAlpha)
            ep.color = Color.argb(embAlpha, 0, 0, 0)
            drawLinesWithPaint(ep, -dx, -dy)
            ep.color = Color.argb(embAlpha, 255, 255, 255)
            drawLinesWithPaint(ep, dx, dy)
        }

        // Main Fill + optional Inner Shadow
        applyTextFill(context, basePaint, layer, -maxLineW / 2f, -totalTextH / 2f, maxLineW, totalTextH, mainAlpha)
        if (layer.innerShadow.enabled) {
            val pad = maxLineW + 40f
            val padH = totalTextH + 40f
            val saveL = canvas.saveLayer(-pad, -padH, pad, padH, null)
            drawLinesWithPaint(basePaint)
            val isPaint = sc.auxTextPaint
            configureTextPaint(isPaint, context, layer, mainAlpha)
            isPaint.xfermode = sc.srcAtopXfermode
            isPaint.color = multiplyAlpha(layer.innerShadow.color, alphaFactor)
            isPaint.shader = null
            isPaint.maskFilter = if (layer.innerShadow.blur > 0.5f) {
                BlurMaskFilter(layer.innerShadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.INNER)
            } else null
            drawLinesWithPaint(isPaint, layer.innerShadow.offsetX, layer.innerShadow.offsetY)
            isPaint.maskFilter = null
            isPaint.xfermode = null
            canvas.restoreToCount(saveL)
        } else {
            drawLinesWithPaint(basePaint)
        }
        basePaint.shader = null
    }

    private fun configureTextPaint(paint: TextPaint, context: Context?, layer: TextLayer, alphaOverride: Int) {
        val styleBits = when {
            layer.bold && layer.italic -> Typeface.BOLD_ITALIC
            layer.bold -> Typeface.BOLD
            layer.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG
        paint.typeface = AssetRepository.resolveTypeface(context, layer.fontName, styleBits)
        paint.textSize = layer.fontSize
        paint.isUnderlineText = layer.underline
        paint.isStrikeThruText = layer.strikethrough
        paint.letterSpacing = layer.letterSpacing
        paint.color = layer.color
        paint.alpha = alphaOverride
    }

    private fun applyTextFill(
        context: Context,
        paint: Paint,
        layer: TextLayer,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        alphaOverride: Int
    ) {
        val texBmp = layer.textureUri?.let { loadBitmap(context, it) }
        if (texBmp != null) {
            paint.shader = BitmapShader(texBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            paint.alpha = alphaOverride
        } else if (layer.fillType == FillType.GRADIENT) {
            paint.shader = ShapePathBuilder.createShader(layer.gradient, left, top, w, h)
            paint.alpha = alphaOverride
        } else {
            paint.shader = null
            paint.color = layer.color
            paint.alpha = alphaOverride
        }
    }

    private fun renderShapeLayer(
        context: Context,
        canvas: Canvas,
        layer: ShapeLayer,
        alphaFactor: Float,
        sc: RenderScratch
    ) {
        val hw = layer.width / 2f
        val hh = layer.height / 2f
        val path = sc.path
        val radiiList = layer.cornerRadii
        if (layer.shapeLabel == BoundedShapeLabel.RECT && radiiList != null && radiiList.size == 4) {
            val maxR = min(layer.width, layer.height) / 2f
            val r0 = if (layer.cornerEnabled.getOrElse(0) { true }) radiiList[0].coerceIn(0f, maxR) else 0f
            val r1 = if (layer.cornerEnabled.getOrElse(1) { true }) radiiList[1].coerceIn(0f, maxR) else 0f
            val r2 = if (layer.cornerEnabled.getOrElse(2) { true }) radiiList[2].coerceIn(0f, maxR) else 0f
            val r3 = if (layer.cornerEnabled.getOrElse(3) { true }) radiiList[3].coerceIn(0f, maxR) else 0f
            if (r0 > 0.5f || r1 > 0.5f || r2 > 0.5f || r3 > 0.5f) {
                path.reset()
                sc.rectF.set(-hw, -hh, hw, hh)
                val rad8 = sc.radii8
                rad8[0] = r0; rad8[1] = r0
                rad8[2] = r1; rad8[3] = r1
                rad8[4] = r2; rad8[5] = r2
                rad8[6] = r3; rad8[7] = r3
                path.addRoundRect(sc.rectF, rad8, Path.Direction.CW)
            } else {
                ShapePathBuilder.buildBoundedShapePathInto(path, layer.shapeLabel, layer.width, layer.height, layer.cornerRadius)
            }
        } else {
            ShapePathBuilder.buildBoundedShapePathInto(path, layer.shapeLabel, layer.width, layer.height, layer.cornerRadius)
        }
        val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)

        // Shadow
        if (layer.shadow.enabled) {
            val sp = sc.paint
            sp.reset()
            sp.flags = Paint.ANTI_ALIAS_FLAG
            sp.color = multiplyAlpha(layer.shadow.color, alphaFactor)
            if (layer.shadow.blur > 0.5f) {
                sp.maskFilter = BlurMaskFilter(layer.shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(layer.shadow.offsetX, layer.shadow.offsetY)
            canvas.drawPath(path, sp)
            canvas.restore()
            sp.maskFilter = null
        }

        // 3D Extrusion
        if (layer.threeD.enabled && layer.threeD.depth > 0) {
            val rad = layer.threeD.angle * PI / 180.0
            val ux = cos(rad).toFloat()
            val uy = sin(rad).toFloat()
            val extrudeCol = if (layer.threeD.colorAuto) darkenColor(layer.color, layer.threeD.darken / 100f) else layer.threeD.color
            val tdPaint = sc.paint
            tdPaint.reset()
            tdPaint.flags = Paint.ANTI_ALIAS_FLAG
            tdPaint.color = multiplyAlpha(extrudeCol, alphaFactor)
            for (step in layer.threeD.depth downTo 1) {
                canvas.save()
                canvas.translate(ux * step * 0.9f, uy * step * 0.9f)
                canvas.drawPath(path, tdPaint)
                canvas.restore()
            }
        }

        // Main Fill (+ Texture)
        val texBmp = layer.textureUri?.let { loadBitmap(context, it) }
        val fillPaint = sc.paint
        fillPaint.reset()
        fillPaint.flags = Paint.ANTI_ALIAS_FLAG
        fillPaint.style = Paint.Style.FILL
        if (texBmp != null) {
            fillPaint.shader = BitmapShader(texBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            fillPaint.alpha = mainAlpha
        } else if (layer.fillType == FillType.GRADIENT) {
            fillPaint.shader = ShapePathBuilder.createShader(layer.gradient, -hw, -hh, layer.width, layer.height)
            fillPaint.alpha = mainAlpha
        } else {
            fillPaint.color = layer.color
            fillPaint.alpha = mainAlpha
        }
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null

        // Hardware-accelerated directional bevel emboss inside shape contour
        if (layer.emboss.enabled) {
            val rad = layer.emboss.lightAngle * PI.toFloat() / 180f
            val bevelDist = (layer.emboss.bevel / 10f).coerceIn(1.5f, 8f)
            val dx = cos(rad) * bevelDist
            val dy = sin(rad) * bevelDist
            val embAlpha = ((layer.emboss.intensity / 100f) * 145f * alphaFactor).toInt().coerceIn(20, 165)
            val ep = sc.paint
            ep.reset()
            ep.flags = Paint.ANTI_ALIAS_FLAG
            ep.style = Paint.Style.STROKE
            ep.strokeWidth = bevelDist * 2f

            canvas.save()
            canvas.clipPath(path)
            ep.color = Color.argb(embAlpha, 255, 255, 255)
            canvas.save()
            canvas.translate(dx, dy)
            canvas.drawPath(path, ep)
            canvas.restore()
            ep.color = Color.argb(embAlpha, 0, 0, 0)
            canvas.save()
            canvas.translate(-dx, -dy)
            canvas.drawPath(path, ep)
            canvas.restore()
            canvas.restore()
        }

        if (layer.innerShadow.enabled) {
            drawPathInnerShadow(canvas, path, layer.innerShadow, alphaFactor, sc)
        }

        // Stroke
        if (layer.stroke.enabled && layer.stroke.width > 0f) {
            val sp = sc.paint
            sp.reset()
            sp.flags = Paint.ANTI_ALIAS_FLAG
            sp.style = Paint.Style.STROKE
            sp.strokeJoin = Paint.Join.ROUND
            sp.strokeWidth = layer.stroke.width
            sp.color = multiplyAlpha(layer.stroke.color, alphaFactor)
            canvas.drawPath(path, sp)
        }
    }

    private fun drawPathInnerShadow(
        canvas: Canvas,
        path: Path,
        shadow: ShadowConfig,
        alphaFactor: Float,
        sc: RenderScratch
    ) {
        canvas.save()
        canvas.clipPath(path)
        val isp = sc.paint
        isp.reset()
        isp.flags = Paint.ANTI_ALIAS_FLAG
        isp.style = Paint.Style.STROKE
        isp.strokeWidth = (shadow.blur * 1.4f).coerceAtLeast(8f)
        isp.color = multiplyAlpha(shadow.color, alphaFactor)
        if (shadow.blur > 0.5f) {
            isp.maskFilter = BlurMaskFilter(shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.translate(shadow.offsetX, shadow.offsetY)
        canvas.drawPath(path, isp)
        isp.maskFilter = null
        canvas.restore()
    }

    private fun renderBezierLayer(canvas: Canvas, layer: BezierLayer, alphaFactor: Float, sc: RenderScratch) {
        if (layer.anchors.size < 2) return
        val path = sc.path
        buildBezierPathInto(path, layer.anchors, layer.closed, layer.width, layer.height)
        val hw = layer.width / 2f
        val hh = layer.height / 2f
        val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)

        if (layer.shadow.enabled) {
            val sp = sc.paint
            sp.reset()
            sp.flags = Paint.ANTI_ALIAS_FLAG
            sp.style = if (layer.fillEnabled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            sp.strokeWidth = layer.stroke.width
            sp.color = multiplyAlpha(layer.shadow.color, alphaFactor)
            if (layer.shadow.blur > 0.5f) {
                sp.maskFilter = BlurMaskFilter(layer.shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(layer.shadow.offsetX, layer.shadow.offsetY)
            canvas.drawPath(path, sp)
            canvas.restore()
            sp.maskFilter = null
        }

        if (layer.fillEnabled) {
            val fp = sc.paint
            fp.reset()
            fp.flags = Paint.ANTI_ALIAS_FLAG
            fp.style = Paint.Style.FILL
            if (layer.fillType == FillType.GRADIENT) {
                fp.shader = ShapePathBuilder.createShader(layer.gradient, -hw, -hh, layer.width, layer.height)
            } else {
                fp.color = layer.color
            }
            fp.alpha = mainAlpha
            canvas.drawPath(path, fp)
            fp.shader = null
        }

        if (layer.stroke.enabled && layer.stroke.width > 0f) {
            val stp = sc.paint
            stp.reset()
            stp.flags = Paint.ANTI_ALIAS_FLAG
            stp.style = Paint.Style.STROKE
            stp.strokeCap = Paint.Cap.ROUND
            stp.strokeJoin = Paint.Join.ROUND
            stp.strokeWidth = layer.stroke.width
            stp.color = multiplyAlpha(layer.stroke.color, alphaFactor)
            canvas.drawPath(path, stp)
        }
    }

    fun buildBezierPathInto(path: Path, anchors: List<BezierAnchor>, closed: Boolean, w: Float, h: Float) {
        path.reset()
        if (anchors.isEmpty()) return
        fun toX(nx: Float) = (nx - 0.5f) * w
        fun toY(ny: Float) = (ny - 0.5f) * h
        path.moveTo(toX(anchors[0].x), toY(anchors[0].y))
        for (i in 1 until anchors.size) {
            val prev = anchors[i - 1]
            val cur = anchors[i]
            path.cubicTo(
                toX(prev.ctrlOutX), toY(prev.ctrlOutY),
                toX(cur.ctrlInX), toY(cur.ctrlInY),
                toX(cur.x), toY(cur.y)
            )
        }
        if (closed && anchors.size >= 2) {
            val last = anchors.last()
            val first = anchors.first()
            path.cubicTo(
                toX(last.ctrlOutX), toY(last.ctrlOutY),
                toX(first.ctrlInX), toY(first.ctrlInY),
                toX(first.x), toY(first.y)
            )
            path.close()
        }
    }

    private fun renderArrowLayer(canvas: Canvas, layer: ArrowLayer, alphaFactor: Float, sc: RenderScratch) {
        val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)
        if (layer.shadow.enabled) {
            val shadowPaint = sc.paint
            shadowPaint.reset()
            shadowPaint.flags = Paint.ANTI_ALIAS_FLAG
            shadowPaint.color = multiplyAlpha(layer.shadow.color, alphaFactor)
            if (layer.shadow.blur > 0.5f) {
                shadowPaint.maskFilter = BlurMaskFilter(layer.shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(layer.shadow.offsetX, layer.shadow.offsetY)
            ShapePathBuilder.drawArrow(
                canvas, shadowPaint, null, layer.width, layer.stemWidth, layer.headSize,
                layer.startStyle, layer.endStyle
            )
            canvas.restore()
            shadowPaint.maskFilter = null
        }

        val fillPaint = sc.paint
        fillPaint.reset()
        fillPaint.flags = Paint.ANTI_ALIAS_FLAG
        fillPaint.style = Paint.Style.FILL
        if (layer.fillType == FillType.GRADIENT) {
            fillPaint.shader = ShapePathBuilder.createShader(layer.gradient, -layer.width / 2f, -layer.height / 2f, layer.width, layer.height)
        } else {
            fillPaint.color = layer.color
        }
        fillPaint.alpha = mainAlpha

        val strokePaint = if (layer.stroke.enabled && layer.stroke.width > 0f) {
            sc.auxPaint.apply {
                reset()
                flags = Paint.ANTI_ALIAS_FLAG
                style = Paint.Style.STROKE
                strokeWidth = layer.stroke.width
                strokeJoin = Paint.Join.ROUND
                color = multiplyAlpha(layer.stroke.color, alphaFactor)
            }
        } else null

        ShapePathBuilder.drawArrow(
            canvas, fillPaint, strokePaint, layer.width, layer.stemWidth, layer.headSize,
            layer.startStyle, layer.endStyle
        )
        fillPaint.shader = null
    }

    private fun renderDrawingLayer(canvas: Canvas, layer: DrawingLayer, alphaFactor: Float, sc: RenderScratch) {
        val hw = layer.width / 2f
        val hh = layer.height / 2f
        val hasBrushEngine = layer.strokes.any { it.presetId != "legacy" }
        if (hasBrushEngine) {
            val srcW = layer.sourceWidth.roundToInt().coerceIn(64, 2048)
            val srcH = layer.sourceHeight.roundToInt().coerceIn(64, 2048)
            val scaleCap = min(1f, 1600f / max(srcW, srcH).toFloat())
            val bmpW = (srcW * scaleCap).roundToInt().coerceIn(64, 1600)
            val bmpH = (srcH * scaleCap).roundToInt().coerceIn(64, 1600)
            val key = "${layer.id}|$bmpW|$bmpH|${layer.strokes.size}|${layer.strokes.lastOrNull()?.hashCode() ?: 0}"
            val bmp = drawingLayerCache.get(key)?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).also { created ->
                val c = Canvas(created)
                c.scale(bmpW.toFloat() / layer.sourceWidth.coerceAtLeast(1f), bmpH.toFloat() / layer.sourceHeight.coerceAtLeast(1f))
                for (st in layer.strokes) {
                    drawStroke(c, st, 1f)
                }
                drawingLayerCache.put(key, created)
            }
            val paint = sc.bitmapPaint
            paint.reset()
            paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
            paint.alpha = (255 * alphaFactor).roundToInt().coerceIn(0, 255)
            sc.rectF.set(-hw, -hh, hw, hh)
            canvas.drawBitmap(bmp, null, sc.rectF, paint)
            return
        }

        val sx = layer.width / layer.sourceWidth.coerceAtLeast(1f)
        val sy = layer.height / layer.sourceHeight.coerceAtLeast(1f)

        val saveLayer = canvas.saveLayer(-hw - 30f, -hh - 30f, hw + 30f, hh + 30f, null)
        canvas.translate(-hw, -hh)
        canvas.scale(sx, sy)
        for (stroke in layer.strokes) {
            drawStroke(canvas, stroke, alphaFactor)
        }
        canvas.restoreToCount(saveLayer)
    }

    fun drawStroke(canvas: Canvas, stroke: DrawingStroke, alphaFactor: Float = 1f) {
        if (stroke.presetId != "legacy") {
            BrushEngine.renderStroke(canvas, stroke, alphaFactor)
            return
        }
        drawStrokePoints(
            canvas = canvas,
            points = stroke.points,
            color = stroke.color,
            width = stroke.width,
            blur = stroke.blur,
            dashed = stroke.dashed,
            neon = stroke.neon,
            fill = stroke.fill,
            eraser = stroke.eraser,
            alphaFactor = alphaFactor
        )
    }

    fun drawStrokePoints(
        canvas: Canvas,
        points: List<DrawPoint>,
        color: Int,
        width: Float,
        blur: Float,
        dashed: Boolean,
        neon: Boolean,
        fill: Boolean,
        eraser: Boolean,
        alphaFactor: Float = 1f
    ) {
        if (points.isEmpty()) return
        val sc = scratch()
        val path = sc.auxPath
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val midX = (prev.x + curr.x) / 2f
            val midY = (prev.y + curr.y) / 2f
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        if (points.size > 1) {
            val last = points.last()
            path.lineTo(last.x, last.y)
        } else {
            path.lineTo(points[0].x + 0.5f, points[0].y + 0.5f)
        }
        if (fill) path.close()

        val p = sc.paint
        p.reset()
        p.flags = Paint.ANTI_ALIAS_FLAG
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.style = if (fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        if (eraser) {
            p.xfermode = sc.clearXfermode
        } else {
            p.color = multiplyAlpha(color, alphaFactor)
            if (dashed) {
                p.pathEffect = DashPathEffect(floatArrayOf(width * 2.2f, width * 1.5f), 0f)
            }
            if (neon) {
                p.maskFilter = BlurMaskFilter((blur + 8f).coerceAtLeast(6f), BlurMaskFilter.Blur.SOLID)
            } else if (blur > 0.5f) {
                p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawPath(path, p)
        p.xfermode = null
        p.pathEffect = null
        p.maskFilter = null
    }

    private fun renderImageLayer(
        context: Context,
        canvas: Canvas,
        layer: ImageLayer,
        alphaFactor: Float,
        sc: RenderScratch,
        overrideBitmap: Bitmap? = null,
        overrideSize: Pair<Float, Float>? = null
    ) {
        val drawW = overrideSize?.first ?: layer.width
        val drawH = overrideSize?.second ?: layer.height
        val hw = drawW / 2f
        val hh = drawH / 2f
        val mainAlpha = (255 * alphaFactor).toInt().coerceIn(0, 255)

        var bmp = overrideBitmap?.takeIf { !it.isRecycled } ?: loadBitmap(context, layer.uriString) ?: return
        if (overrideBitmap == null && layer.removeColorEnabled) {
            val key = "${layer.uriString}_${layer.removeColorTarget}_${layer.removeColorTolerance}"
            val cached = chromaCache.get(key)
            bmp = if (cached != null && !cached.isRecycled) {
                cached
            } else {
                applyChromaKey(bmp, layer.removeColorTarget, layer.removeColorTolerance).also {
                    chromaCache.put(key, it)
                }
            }
        }

        val rect = sc.rectF
        rect.set(-hw, -hh, hw, hh)
        val maxR = min(drawW, drawH) / 2f
        val r0 = if (overrideSize == null && layer.cornerEnabled.getOrElse(0) { true }) layer.cornerRadii.getOrElse(0) { 0f }.coerceIn(0f, maxR) else 0f
        val r1 = if (overrideSize == null && layer.cornerEnabled.getOrElse(1) { true }) layer.cornerRadii.getOrElse(1) { 0f }.coerceIn(0f, maxR) else 0f
        val r2 = if (overrideSize == null && layer.cornerEnabled.getOrElse(2) { true }) layer.cornerRadii.getOrElse(2) { 0f }.coerceIn(0f, maxR) else 0f
        val r3 = if (overrideSize == null && layer.cornerEnabled.getOrElse(3) { true }) layer.cornerRadii.getOrElse(3) { 0f }.coerceIn(0f, maxR) else 0f
        val hasCornerRadius = r0 > 0.5f || r1 > 0.5f || r2 > 0.5f || r3 > 0.5f

        val shapePath = sc.path
        shapePath.reset()
        when {
            overrideSize == null && layer.circularCrop -> shapePath.addOval(rect, Path.Direction.CW)
            hasCornerRadius -> {
                val rad8 = sc.radii8
                rad8[0] = r0; rad8[1] = r0
                rad8[2] = r1; rad8[3] = r1
                rad8[4] = r2; rad8[5] = r2
                rad8[6] = r3; rad8[7] = r3
                shapePath.addRoundRect(rect, rad8, Path.Direction.CW)
            }
            else -> shapePath.addRect(rect, Path.Direction.CW)
        }

        if (layer.shadow.enabled) {
            val sp = sc.paint
            sp.reset()
            sp.flags = Paint.ANTI_ALIAS_FLAG
            sp.color = multiplyAlpha(layer.shadow.color, alphaFactor)
            if (layer.shadow.blur > 0.5f) {
                sp.maskFilter = BlurMaskFilter(layer.shadow.blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(layer.shadow.offsetX, layer.shadow.offsetY)
            canvas.drawPath(shapePath, sp)
            canvas.restore()
            sp.maskFilter = null
        }

        val isGradientTint = layer.colorOverlayEnabled && layer.colorOverlayFillType == FillType.GRADIENT
        val needOffscreen = layer.eraserStrokes.isNotEmpty() || isGradientTint
        val offscreenSave = if (needOffscreen) {
            canvas.saveLayer(-hw - 20f, -hh - 20f, hw + 20f, hh + 20f, null)
        } else {
            canvas.save()
        }

        if (layer.circularCrop || hasCornerRadius) {
            canvas.clipPath(shapePath)
        }

        canvas.save()
        canvas.scale(if (layer.flipH) -1f else 1f, if (layer.flipV) -1f else 1f)

        val paint = sc.bitmapPaint
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        paint.alpha = mainAlpha
        paint.colorFilter = if (layer.colorOverlayEnabled && layer.colorOverlayFillType == FillType.COLOR) {
            PorterDuffColorFilter(layer.colorOverlay, PorterDuff.Mode.SRC_ATOP)
        } else {
            buildColorMatrixFilter(layer.brightness, layer.contrast, layer.saturation, layer.hue)
        }
        canvas.drawBitmap(bmp, null, rect, paint)
        paint.colorFilter = null
        canvas.restore()

        if (isGradientTint) {
            val gp = sc.paint
            gp.reset()
            gp.flags = Paint.ANTI_ALIAS_FLAG
            gp.shader = ShapePathBuilder.createShader(layer.colorOverlayGradient, -hw, -hh, layer.width, layer.height)
            gp.alpha = mainAlpha
            gp.xfermode = sc.srcAtopXfermode
            canvas.drawRect(rect, gp)
            gp.shader = null
            gp.xfermode = null
        }

        if (layer.eraserStrokes.isNotEmpty()) {
            val ptsBuf = ArrayList<DrawPoint>(64)
            for (es in layer.eraserStrokes) {
                ptsBuf.clear()
                for (pt in es.points) {
                    ptsBuf.add(DrawPoint((pt.x - 0.5f) * layer.width, (pt.y - 0.5f) * layer.height))
                }
                drawStrokePoints(
                    canvas = canvas,
                    points = ptsBuf,
                    color = Color.TRANSPARENT,
                    width = es.width,
                    blur = es.blur,
                    dashed = es.dashed,
                    neon = es.neon,
                    fill = es.fill,
                    eraser = true,
                    alphaFactor = 1f
                )
            }
        }

        if (layer.innerShadow.enabled) {
            drawPathInnerShadow(canvas, shapePath, layer.innerShadow, alphaFactor, sc)
        }

        canvas.restoreToCount(offscreenSave)

        if (layer.stroke.enabled && layer.stroke.width > 0f) {
            val sp = sc.paint
            sp.reset()
            sp.flags = Paint.ANTI_ALIAS_FLAG
            sp.style = Paint.Style.STROKE
            sp.strokeJoin = Paint.Join.ROUND
            sp.strokeWidth = layer.stroke.width
            sp.color = multiplyAlpha(layer.stroke.color, alphaFactor)
            canvas.drawPath(shapePath, sp)
        }
    }

    private fun applyChromaKey(src: Bitmap, targetColor: Int, tolerancePct: Int): Bitmap {
        val maxDist = (tolerancePct.coerceIn(1, 100) / 100f) * 255f * 1.732f
        val maxDistSq = maxDist * maxDist
        val tr = Color.red(targetColor)
        val tg = Color.green(targetColor)
        val tb = Color.blue(targetColor)

        val sampled = if (src.width > 900 || src.height > 900) {
            val ratio = min(900f / src.width, 900f / src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
        } else {
            src
        }
        val w = sampled.width
        val h = sampled.height
        val pixels = IntArray(w * h)
        sampled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sampled !== src && !sampled.isRecycled) {
            sampled.recycle()
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val dr = ((c shr 16) and 0xFF) - tr
            val dg = ((c shr 8) and 0xFF) - tg
            val db = (c and 0xFF) - tb
            val distSq = (dr * dr + dg * dg + db * db).toFloat()
            if (distSq < maxDistSq) {
                pixels[i] = c and 0x00FFFFFF
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun buildColorMatrixFilter(brightness: Int, contrast: Int, saturation: Int, hue: Int): ColorMatrixColorFilter? {
        if (brightness == 0 && contrast == 0 && saturation == 0 && hue == 0) return null
        val cm = ColorMatrix()

        if (saturation != 0) {
            val satMat = ColorMatrix()
            satMat.setSaturation((1f + saturation / 100f).coerceIn(0f, 3f))
            cm.postConcat(satMat)
        }

        if (contrast != 0 || brightness != 0) {
            val c = (1f + contrast / 100f).coerceIn(0.2f, 3f)
            val b = brightness * 2.55f
            val translate = (-0.5f * c + 0.5f) * 255f + b
            val cbMat = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(cbMat)
        }

        if (hue != 0) {
            val rad = hue * PI.toFloat() / 180f
            val cosVal = cos(rad)
            val sinVal = sin(rad)
            val lumR = 0.213f
            val lumG = 0.715f
            val lumB = 0.072f
            val hueMat = ColorMatrix(
                floatArrayOf(
                    lumR + cosVal * (1f - lumR) + sinVal * (-lumR), lumG + cosVal * (-lumG) + sinVal * (-lumG), lumB + cosVal * (-lumB) + sinVal * (1f - lumB), 0f, 0f,
                    lumR + cosVal * (-lumR) + sinVal * 0.143f, lumG + cosVal * (1f - lumG) + sinVal * 0.140f, lumB + cosVal * (-lumB) + sinVal * (-0.283f), 0f, 0f,
                    lumR + cosVal * (-lumR) + sinVal * (-(1f - lumR)), lumG + cosVal * (-lumG) + sinVal * lumG, lumB + cosVal * (1f - lumB) + sinVal * lumB, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(hueMat)
        }

        return ColorMatrixColorFilter(cm)
    }

    private fun multiplyAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun darkenColor(color: Int, amount: Float): Int {
        val f = (1f - amount).coerceIn(0.15f, 1f)
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * f).toInt(),
            (Color.green(color) * f).toInt(),
            (Color.blue(color) * f).toInt()
        )
    }

    fun rasterizeLayerToImageLayer(context: Context, layer: CanvasLayer): ImageLayer? {
        if (layer is ImageLayer) return layer
        val lw = layer.width.coerceAtLeast(32f)
        val lh = layer.height.coerceAtLeast(32f)
        val padFrac = 1.12f
        val neutral = layer.copyBase(
            hidden = false,
            rotation = 0f,
            rotX = 0f,
            rotY = 0f,
            opacity = 100
        )
        val bmp = renderLayerHighResBitmap(context, neutral, maxDim = 1600) ?: return null
        val savedPath = runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val outFile = File(dir, "rast_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        }.getOrNull()
        if (!bmp.isRecycled) bmp.recycle()
        if (savedPath == null) return null
        return ImageLayer(
            id = layer.id,
            name = layer.name,
            x = layer.x,
            y = layer.y,
            width = lw * padFrac,
            height = lh * padFrac,
            rotation = layer.rotation,
            rotX = layer.rotX,
            rotY = layer.rotY,
            opacity = layer.opacity,
            locked = layer.locked,
            hidden = layer.hidden,
            uriString = savedPath
        )
    }
}
