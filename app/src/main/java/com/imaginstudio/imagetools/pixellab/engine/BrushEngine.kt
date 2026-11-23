package com.imaginstudio.imagetools.pixellab.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import com.imaginstudio.imagetools.pixellab.model.DrawPoint
import com.imaginstudio.imagetools.pixellab.model.DrawingStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object BrushEngine {

    enum class TipShape { ROUND, SQUARE, DIAMOND, STAR, ELLIPSE }

    data class BrushPreset(
        val id: String,
        val name: String,
        val category: String,
        val shape: TipShape,
        val defaultHardness: Int,
        val tex: String? = null,
        val pow: Float = 1f,
        val rot: Boolean = false,
        val spacing: Float = 0.15f
    )

    val PRESETS: List<BrushPreset> = listOf(
        BrushPreset("hard", "Solid Hard", "Basic", TipShape.ROUND, 100),
        BrushPreset("soft", "Soft Round", "Basic", TipShape.ROUND, 0),
        BrushPreset("air", "Airbrush", "Basic", TipShape.ROUND, 0, pow = 2.4f),
        BrushPreset("square", "Square", "Shapes", TipShape.SQUARE, 100),
        BrushPreset("diamond", "Diamond", "Shapes", TipShape.DIAMOND, 100),
        BrushPreset("star", "Star", "Shapes", TipShape.STAR, 100, rot = true, spacing = 0.50f),
        BrushPreset("blade", "Calligraphy", "Shapes", TipShape.ELLIPSE, 100, spacing = 0.08f),
        BrushPreset("chalk", "Chalk", "Textured", TipShape.ROUND, 60, tex = "chalk", rot = true, spacing = 0.40f),
        BrushPreset("charcoal", "Charcoal", "Textured", TipShape.ROUND, 30, tex = "charcoal", rot = true, spacing = 0.40f),
        BrushPreset("grain", "Film Grain", "Textured", TipShape.ROUND, 40, tex = "grain", rot = true, spacing = 0.40f),
        BrushPreset("canvas", "Canvas", "Textured", TipShape.SQUARE, 100, tex = "canvas", spacing = 0.25f),
        BrushPreset("dots", "Halftone", "Textured", TipShape.ROUND, 100, tex = "dots", spacing = 0.30f),
        BrushPreset("splatter", "Splatter", "Textured", TipShape.ROUND, 100, tex = "splatter", rot = true, spacing = 0.45f),
        BrushPreset("hatch", "Hatch", "Textured", TipShape.SQUARE, 100, tex = "hatch", spacing = 0.25f),
        BrushPreset("bristle", "Bristle", "Textured", TipShape.ROUND, 70, tex = "bristle", spacing = 0.12f)
    )

    fun presetOf(id: String?): BrushPreset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    fun hash2(x: Int, y: Int, s: Int): Float {
        var h = (x * 374761393) + (y * 668265263) + (s * 1274126177)
        h = (h xor (h ushr 13)) * 1274126177
        val u = (h xor (h ushr 16)).toLong() and 0xFFFFFFFFL
        return u.toFloat() / 4294967296f
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun texVal(t: String?, x: Int, y: Int, d: Int): Float {
        return when (t) {
            "chalk" -> clamp01((hash2(x, y, 1) - 0.3f) * 3f)
            "charcoal" -> clamp01((hash2(x shr 1, y shr 1, 2) * 0.6f + hash2(x, y, 3) * 0.4f - 0.35f) * 3.5f)
            "grain" -> 0.3f + 0.7f * hash2(x, y, 4)
            "canvas" -> 0.25f + 0.75f * (0.5f + 0.5f * sin(x * 0.9f) * sin(y * 0.9f))
            "dots" -> {
                val s = max(4f, d / 8f)
                val cx = (floor(x / s) + 0.5f) * s
                val cy = (floor(y / s) + 0.5f) * s
                clamp01((s * 0.38f - hypot(x - cx, y - cy)) * 1.5f + 0.5f)
            }
            "splatter" -> {
                val r = hash2(x shr 1, y shr 1, 5)
                val q = hash2(x shr 2, y shr 2, 6)
                when {
                    r > 0.86f || q > 0.9f -> 1f
                    r > 0.72f -> 0.35f
                    else -> 0f
                }
            }
            "hatch" -> {
                val p = max(4, (d / 9f).roundToInt())
                if (((x + y) % p + p) % p < p * 0.45f) 1f else 0f
            }
            "bristle" -> if (hash2(x, 0, 7) > 0.3f) 0.55f + 0.45f * hash2(x, y, 9) else 0f
            else -> 1f
        }
    }

    private val tipCache = LruCache<String, Bitmap>(64)
    private val tintCache = LruCache<String, Bitmap>(64)

    fun makeTip(p: BrushPreset, dRaw: Float, hardnessRaw: Int): Bitmap {
        val d = max(2, dRaw.roundToInt()).coerceAtMost(512)
        val hard = hardnessRaw.coerceIn(0, 100)
        val key = "${p.id}|$d|$hard"
        tipCache.get(key)?.let { if (!it.isRecycled) return it }

        val pixels = IntArray(d * d)
        val h = hard / 100f
        val r = d / 2f
        val invR = 1f / r

        for (y in 0 until d) {
            val v = (y + 0.5f - r) * invR
            val rowOff = y * d
            for (x in 0 until d) {
                val u = (x + 0.5f - r) * invR
                val n = when (p.shape) {
                    TipShape.SQUARE -> max(abs(u), abs(v))
                    TipShape.DIAMOND -> abs(u) + abs(v)
                    TipShape.ELLIPSE -> {
                        val uu = (u * 0.7071f + v * 0.7071f) * 0.72f
                        val vv = (-u * 0.7071f + v * 0.7071f) * 2.6f
                        hypot(uu, vv)
                    }
                    TipShape.STAR -> {
                        val rad = hypot(u, v)
                        val th = atan2(v, u)
                        val cosT = cos(2.5f * (th + (PI / 2.0).toFloat()))
                        rad / (0.38f + 0.62f * cosT * cosT)
                    }
                    TipShape.ROUND -> hypot(u, v)
                }

                var al = when {
                    n >= 1f -> 0f
                    n <= h -> 1f
                    else -> 1f - (n - h) / (1f - h + 1e-6f)
                }
                if (p.pow != 1f && al > 0f) {
                    al = al.pow(p.pow)
                }
                if (al > 0f && p.tex != null) {
                    al *= texVal(p.tex, x, y, d)
                }
                val aByte = (al * 255f).roundToInt().coerceIn(0, 255)
                pixels[rowOff + x] = (aByte shl 24) or 0x00FFFFFF
            }
        }
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, d, 0, 0, d, d)
        tipCache.put(key, bmp)
        return bmp
    }

    fun getTintedTip(preset: BrushPreset, diameter: Float, hardness: Int, color: Int, isEraser: Boolean): Bitmap {
        val tip = makeTip(preset, diameter, hardness)
        if (isEraser) return tip
        val rgb = color and 0x00FFFFFF
        val key = "${preset.id}|${tip.width}|${hardness.coerceIn(0, 100)}|$rgb"
        tintCache.get(key)?.let { if (!it.isRecycled) return it }

        val w = tip.width
        val h = tip.height
        val src = IntArray(w * h)
        tip.getPixels(src, 0, w, 0, 0, w, h)
        for (i in src.indices) {
            val a = src[i] ushr 24
            src[i] = (a shl 24) or rgb
        }
        val tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tinted.setPixels(src, 0, w, 0, 0, w, h)
        tintCache.put(key, tinted)
        return tinted
    }

    private val eraseXfer = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    fun stampDab(
        canvas: Canvas,
        preset: BrushPreset,
        srcTip: Bitmap,
        x: Float,
        y: Float,
        diameter: Float,
        alpha01: Float,
        eraser: Boolean,
        seedIndex: Int,
        paint: Paint
    ) {
        val scale = diameter / srcTip.width.coerceAtLeast(1).toFloat()
        val half = diameter * 0.5f
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.alpha = (alpha01 * 255f).roundToInt().coerceIn(0, 255)
        paint.xfermode = if (eraser) eraseXfer else null

        canvas.save()
        canvas.translate(x, y)
        if (preset.rot) {
            val angleDeg = hash2(seedIndex, 7, 3) * 360f
            canvas.rotate(angleDeg)
        }
        if (abs(scale - 1f) > 0.01f) {
            canvas.scale(scale, scale)
            canvas.drawBitmap(srcTip, -srcTip.width * 0.5f, -srcTip.height * 0.5f, paint)
        } else {
            canvas.drawBitmap(srcTip, -half, -half, paint)
        }
        canvas.restore()
        paint.xfermode = null
    }

    fun stampSegment(
        canvas: Canvas,
        preset: BrushPreset,
        diameter: Float,
        hardness: Int,
        color: Int,
        opacityPct: Int,
        flowPct: Int,
        eraser: Boolean,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        stampCounterStart: Int
    ): Int {
        val srcTip = getTintedTip(preset, diameter, hardness, color, eraser)
        val step = max(1f, diameter * preset.spacing)
        val dist = hypot(x1 - x0, y1 - y0)
        val n = max(1, floor(dist / step).toInt())
        val alpha01 = (opacityPct.coerceIn(0, 100) / 100f) * (flowPct.coerceIn(0, 100) / 100f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var counter = stampCounterStart
        for (i in 1..n) {
            val t = i.toFloat() / n.toFloat()
            val sx = x0 + (x1 - x0) * t
            val sy = y0 + (y1 - y0) * t
            stampDab(canvas, preset, srcTip, sx, sy, diameter, alpha01, eraser, counter++, paint)
        }
        return counter
    }

    fun renderStroke(canvas: Canvas, stroke: DrawingStroke, alphaFactor: Float = 1f) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        val preset = presetOf(stroke.presetId)
        val d = stroke.width.coerceAtLeast(1f)
        val srcTip = getTintedTip(preset, d, stroke.hardness, stroke.color, stroke.eraser)
        val baseAlpha = (stroke.opacity.coerceIn(0, 100) / 100f) *
            (stroke.flow.coerceIn(0, 100) / 100f) *
            alphaFactor.coerceIn(0f, 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var counter = stroke.seed
        stampDab(canvas, preset, srcTip, pts[0].x, pts[0].y, d, baseAlpha, stroke.eraser, counter++, paint)
        val step = max(1f, d * preset.spacing)
        for (idx in 1 until pts.size) {
            val p0 = pts[idx - 1]
            val p1 = pts[idx]
            val dist = hypot(p1.x - p0.x, p1.y - p0.y)
            val n = max(1, floor(dist / step).toInt())
            for (i in 1..n) {
                val t = i.toFloat() / n.toFloat()
                stampDab(
                    canvas,
                    preset,
                    srcTip,
                    p0.x + (p1.x - p0.x) * t,
                    p0.y + (p1.y - p0.y) * t,
                    d,
                    baseAlpha,
                    stroke.eraser,
                    counter++,
                    paint
                )
            }
        }
    }

    fun drawBrushStrokePreview(
        width: Int,
        height: Int,
        preset: BrushPreset,
        color: Int,
        hardnessOverride: Int? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(8), height.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = max(5f, height * 0.42f)
        val hard = hardnessOverride ?: preset.defaultHardness
        val srcTip = getTintedTip(preset, d, hard, color, isEraser = false)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val steps = 34
        val startX = d * 0.65f
        val endX = width - d * 0.65f
        val midY = height * 0.5f
        val amp = height * 0.18f
        var prevX = startX
        var prevY = midY
        var counter = 1
        stampDab(c, preset, srcTip, prevX, prevY, d, 0.92f, eraser = false, counter++, paint)
        val stepLen = max(1f, d * preset.spacing)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val x = startX + (endX - startX) * t
            val y = midY + sin(t * PI * 2.0).toFloat() * amp
            val dist = hypot(x - prevX, y - prevY)
            if (dist >= stepLen || i == steps) {
                stampDab(c, preset, srcTip, x, y, d, 0.92f, eraser = false, counter++, paint)
                prevX = x
                prevY = y
            }
        }
        return bmp
    }
}
