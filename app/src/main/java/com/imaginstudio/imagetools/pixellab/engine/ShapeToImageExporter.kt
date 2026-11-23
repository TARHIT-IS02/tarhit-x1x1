package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ShapeToImageExporter {

    enum class HtmlShapeKind(
        val id: String,
        val label: String,
        val defaultWidthRatio: Float,
        val defaultHeightRatio: Float,
        val isSquareOrCircle: Boolean
    ) {
        RECT("rect", "Rectangle", 0.48f, 0.30f, false),
        RRECT("rrect", "Round Rect", 0.48f, 0.30f, false),
        SQUARE("square", "Square", 0.38f, 0.38f, true),
        RSQUARE("rsquare", "Round Square", 0.38f, 0.38f, true),
        CIRCLE("circle", "Circle", 0.38f, 0.38f, true),
        ELLIPSE("ellipse", "Ellipse", 0.48f, 0.30f, false),
        TRIANGLE("triangle", "Triangle", 0.40f, 0.36f, false),
        HEXAGON("hexagon", "Hexagon", 0.40f, 0.36f, false),
        STAR("star", "Star", 0.40f, 0.40f, false),
        LINE("line", "Line", 0.52f, 0.08f, false),
        ARROW("arrow", "Arrow", 0.52f, 0.18f, false)
    }

    fun drawShape(
        canvas: Canvas,
        kind: HtmlShapeKind,
        bounds: RectF,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        val x0 = bounds.left
        val y0 = bounds.top
        val x1 = bounds.right
        val y1 = bounds.bottom
        val w = bounds.width()
        val h = bounds.height()
        val path = Path()

        when (kind) {
            HtmlShapeKind.RECT, HtmlShapeKind.SQUARE -> {
                canvas.drawRect(bounds, fillPaint)
            }
            HtmlShapeKind.RRECT, HtmlShapeKind.RSQUARE -> {
                val r = min(w, h) * 0.22f
                canvas.drawRoundRect(bounds, r, r, fillPaint)
            }
            HtmlShapeKind.CIRCLE, HtmlShapeKind.ELLIPSE -> {
                canvas.drawOval(bounds, fillPaint)
            }
            HtmlShapeKind.TRIANGLE -> {
                path.moveTo(x0 + w * 0.5f, y0)
                path.lineTo(x1, y1)
                path.lineTo(x0, y1)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            HtmlShapeKind.HEXAGON -> {
                val cy = y0 + h * 0.5f
                path.moveTo(x0 + w * 0.25f, y0)
                path.lineTo(x0 + w * 0.75f, y0)
                path.lineTo(x1, cy)
                path.lineTo(x0 + w * 0.75f, y1)
                path.lineTo(x0 + w * 0.25f, y1)
                path.lineTo(x0, cy)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            HtmlShapeKind.STAR -> {
                val cx = x0 + w * 0.5f
                val cy = y0 + h * 0.5f
                for (i in 0 until 10) {
                    val a = -PI / 2.0 + i * (PI / 5.0)
                    val k = if (i % 2 != 0) 0.42f else 1f
                    val px = cx + (cos(a).toFloat() * (w * 0.5f) * k)
                    val py = cy + (sin(a).toFloat() * (h * 0.5f) * k)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            HtmlShapeKind.LINE -> {
                val cy = y0 + h * 0.5f
                val pad = strokePaint.strokeWidth * 0.6f
                canvas.drawLine(x0 + pad, cy, x1 - pad, cy, strokePaint)
            }
            HtmlShapeKind.ARROW -> {
                val cy = y0 + h * 0.5f
                val pad = strokePaint.strokeWidth * 0.7f
                val sx = x0 + pad
                val ex = x1 - pad
                val len = max(1f, ex - sx)
                val hd = min(len * 0.42f, max(h * 0.44f, strokePaint.strokeWidth * 2.6f))
                path.moveTo(sx, cy)
                path.lineTo(ex, cy)
                path.moveTo(ex - cos(-0.55).toFloat() * hd, cy - sin(-0.55).toFloat() * hd)
                path.lineTo(ex, cy)
                path.lineTo(ex - cos(0.55).toFloat() * hd, cy - sin(0.55).toFloat() * hd)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    fun renderPreviewBitmap(kind: HtmlShapeKind, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#475569")
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#475569")
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(3f, sizePx * 0.08f)
        }
        val m = sizePx * 0.16f
        val rect = when (kind) {
            HtmlShapeKind.RECT, HtmlShapeKind.RRECT, HtmlShapeKind.ELLIPSE ->
                RectF(m, sizePx * 0.25f, sizePx - m, sizePx * 0.75f)
            HtmlShapeKind.LINE, HtmlShapeKind.ARROW ->
                RectF(m, sizePx * 0.22f, sizePx - m, sizePx * 0.78f)
            else ->
                RectF(m, m, sizePx - m, sizePx - m)
        }
        drawShape(c, kind, rect, fillPaint, strokePaint)
        return bmp
    }

    private fun trimTransparentMargins(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                if ((px[rowOff + x] ushr 24) > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return src
        val tw = (maxX - minX + 1).coerceAtLeast(1)
        val th = (maxY - minY + 1).coerceAtLeast(1)
        if (minX == 0 && minY == 0 && tw == w && th == h) return src
        return Bitmap.createBitmap(src, minX, minY, tw, th)
    }

    fun exportShapeToImageLayer(
        context: Context,
        project: ProjectState,
        kind: HtmlShapeKind,
        widthPct: Int = (kind.defaultWidthRatio * 100f).roundToInt(),
        heightPct: Int = (kind.defaultHeightRatio * 100f).roundToInt(),
        lockedRatio: Boolean = kind.isSquareOrCircle
    ): ImageLayer? {
        val cw = project.width.coerceAtLeast(100).toFloat()
        val ch = project.height.coerceAtLeast(100).toFloat()
        val targetW = (cw * (widthPct.coerceIn(5, 300) / 100f)).coerceAtLeast(24f)
        val targetH = if (lockedRatio && kind.isSquareOrCircle) {
            targetW
        } else {
            (ch * (heightPct.coerceIn(5, 300) / 100f)).coerceAtLeast(24f)
        }

        // High-res PNG backing for crisp scaling/warping without pixelation
        val renderScale = (800f / max(targetW, targetH)).coerceIn(1f, 3f)
        val bmpW = (targetW * renderScale).roundToInt().coerceIn(64, 1400)
        val bmpH = (targetH * renderScale).roundToInt().coerceIn(64, 1400)
        val pad = if (kind == HtmlShapeKind.LINE || kind == HtmlShapeKind.ARROW) 32f else 8f
        val raw = Bitmap.createBitmap(
            (bmpW + pad * 2).roundToInt(),
            (bmpH + pad * 2).roundToInt(),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(raw)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(14f, min(bmpW, bmpH) * 0.22f)
        }
        drawShape(c, kind, RectF(pad, pad, pad + bmpW, pad + bmpH), fillPaint, strokePaint)
        val trimmed = trimTransparentMargins(raw)
        if (trimmed != raw) raw.recycle()

        val savedPath = runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val file = File(dir, "shape_${kind.id}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                trimmed.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()

        val trimmedAspect = trimmed.width.coerceAtLeast(1).toFloat() / trimmed.height.coerceAtLeast(1).toFloat()
        trimmed.recycle()
        if (savedPath == null) return null

        val finalW = targetW
        val finalH = if (kind == HtmlShapeKind.LINE || kind == HtmlShapeKind.ARROW) {
            (targetW / trimmedAspect).coerceAtLeast(20f)
        } else if (lockedRatio && kind.isSquareOrCircle) {
            finalW
        } else {
            targetH
        }

        return ImageLayer(
            id = "shape_img_${System.currentTimeMillis()}",
            name = kind.label,
            x = project.width / 2f,
            y = project.height / 2f,
            width = finalW,
            height = finalH,
            uriString = savedPath
        )
    }
}
