package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.imaginstudio.imagetools.pixellab.model.ArrowLayer
import com.imaginstudio.imagetools.pixellab.model.BackgroundType
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.DrawingLayer
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AdvanceImageResampler {

    const val MAX_DIM = 8192
    const val MIN_DIM = 1

    enum class SizeUnit(val code: String, val label: String) {
        PX("px", "Pixels"),
        PCT("pct", "Percent"),
        IN("in", "Inches"),
        CM("cm", "Centimeters"),
        MM("mm", "Millimeters"),
        PT("pt", "Points"),
        PC("pc", "Picas")
    }

    enum class ResUnit(val code: String, val label: String) {
        PPI("ppi", "Pixels/Inch"),
        PPCM("ppcm", "Pixels/Centimeter")
    }

    enum class ResampleMethod(val code: String, val label: String) {
        AUTO("auto", "Automatic"),
        PRESERVE("preserve", "Preserve Details 2.0"),
        SMOOTHER("smoother", "Bicubic Smoother"),
        SHARPER("sharper", "Bicubic Sharper"),
        BICUBIC("bicubic", "Bicubic"),
        BILINEAR("bilinear", "Bilinear"),
        NEAREST("nearest", "Nearest Neighbor")
    }

    data class FitPreset(val key: String, val label: String, val w: Int?, val h: Int?)

    val FIT_PRESETS = listOf(
        FitPreset("orig", "Original Size", null, null),
        FitPreset("custom", "Custom", null, null),
        FitPreset("640x480", "640 × 480", 640, 480),
        FitPreset("800x600", "800 × 600", 800, 600),
        FitPreset("1024x768", "1024 × 768", 1024, 768),
        FitPreset("1280x720", "1280 × 720 (HD)", 1280, 720),
        FitPreset("1280x960", "1280 × 960", 1280, 960),
        FitPreset("1920x1080", "1920 × 1080 (FHD)", 1920, 1080),
        FitPreset("2560x1440", "2560 × 1440 (2K)", 2560, 1440),
        FitPreset("3840x2160", "3840 × 2160 (4K)", 3840, 2160),
        FitPreset("1080x1080", "1080 × 1080 (Square)", 1080, 1080),
        FitPreset("1080x1350", "1080 × 1350 (Portrait)", 1080, 1350),
        FitPreset("1080x1920", "1080 × 1920 (Story)", 1080, 1920)
    )

    fun toUnit(px: Float, unit: SizeUnit, origPx: Int, dpi: Float): Float {
        val safeDpi = dpi.coerceAtLeast(1f)
        val safeOrig = origPx.coerceAtLeast(1).toFloat()
        return when (unit) {
            SizeUnit.PX -> px
            SizeUnit.PCT -> (px / safeOrig) * 100f
            SizeUnit.IN -> px / safeDpi
            SizeUnit.CM -> (px / safeDpi) * 2.54f
            SizeUnit.MM -> (px / safeDpi) * 25.4f
            SizeUnit.PT -> (px / safeDpi) * 72f
            SizeUnit.PC -> (px / safeDpi) * 6f
        }
    }

    fun toInches(value: Float, unit: SizeUnit): Float = when (unit) {
        SizeUnit.IN -> value
        SizeUnit.CM -> value / 2.54f
        SizeUnit.MM -> value / 25.4f
        SizeUnit.PT -> value / 72f
        SizeUnit.PC -> value / 6f
        else -> value
    }

    fun fromUnit(value: Float, unit: SizeUnit, origPx: Int, dpi: Float): Float {
        val safeDpi = dpi.coerceAtLeast(1f)
        return when (unit) {
            SizeUnit.PX -> value
            SizeUnit.PCT -> (value / 100f) * origPx.coerceAtLeast(1).toFloat()
            else -> toInches(value, unit) * safeDpi
        }
    }

    fun formatValue(v: Float, unit: SizeUnit): String {
        return when (unit) {
            SizeUnit.PX -> v.roundToInt().toString()
            SizeUnit.PCT -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        }
    }

    fun formatMegabytes(w: Int, h: Int): String {
        val mb = (w.toLong() * h.toLong() * 4L) / 1048576.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1fM", mb)
        } else {
            String.format(Locale.US, "%.2fM", mb)
        }
    }

    fun resampleBitmap(
        src: Bitmap,
        nw: Int,
        nh: Int,
        method: ResampleMethod,
        reduceNoisePct: Int
    ): Bitmap {
        val tw = nw.coerceIn(1, MAX_DIM)
        val th = nh.coerceIn(1, MAX_DIM)
        val resolved = if (method == ResampleMethod.AUTO) {
            if (tw > src.width || th > src.height) ResampleMethod.PRESERVE else ResampleMethod.SHARPER
        } else method

        if (tw == src.width && th == src.height) {
            return src.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (resolved == ResampleMethod.NEAREST) {
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(0).apply { isFilterBitmap = false }
            c.drawBitmap(src, null, android.graphics.Rect(0, 0, tw, th), p)
            return out
        }

        // Progressive halving for clean downscaling (matches HTML izResample)
        var cur = src
        var ownedCur = false
        while (cur.width > tw * 2 || cur.height > th * 2) {
            val hw = max(tw, (cur.width * 0.5f).roundToInt())
            val hh = max(th, (cur.height * 0.5f).roundToInt())
            val next = Bitmap.createScaledBitmap(cur, hw, hh, true)
            if (ownedCur) cur.recycle()
            cur = next
            ownedCur = true
        }

        val scaled = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (ownedCur && cur != scaled) cur.recycle()

        var out = if (scaled.config != Bitmap.Config.ARGB_8888 || !scaled.isMutable) {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (scaled != src) scaled.recycle()
            copy
        } else {
            scaled
        }

        if (resolved == ResampleMethod.SHARPER) {
            applyLaplacianSharpen(out, 0.35f)
        } else if ((resolved == ResampleMethod.PRESERVE || resolved == ResampleMethod.SMOOTHER) &&
            (tw > src.width || th > src.height)
        ) {
            val blurFactor = if (resolved == ResampleMethod.PRESERVE) {
                (reduceNoisePct.coerceIn(0, 100) / 100f) * 0.8f
            } else {
                0.5f
            }
            if (blurFactor > 0.05f) {
                applyLightNoiseSmooth(out, blurFactor)
            }
        }
        return out
    }

    private fun applyLaplacianSharpen(bmp: Bitmap, k: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val s = IntArray(w * h)
        val out = IntArray(w * h)
        bmp.getPixels(s, 0, w, 0, 0, w, h)
        System.arraycopy(s, 0, out, 0, s.size)
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val i = rowOff + x
                val c = s[i]
                val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                val l = s[i - 1]
                val r = s[i + 1]
                val u = s[i - w]
                val d = s[i + w]

                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF

                val nr = (cr + k * (4 * cr - ((l ushr 16) and 0xFF) - ((r ushr 16) and 0xFF) - ((u ushr 16) and 0xFF) - ((d ushr 16) and 0xFF))).roundToInt().coerceIn(0, 255)
                val ng = (cg + k * (4 * cg - ((l ushr 8) and 0xFF) - ((r ushr 8) and 0xFF) - ((u ushr 8) and 0xFF) - ((d ushr 8) and 0xFF))).roundToInt().coerceIn(0, 255)
                val nb = (cb + k * (4 * cb - (l and 0xFF) - (r and 0xFF) - (u and 0xFF) - (d and 0xFF))).roundToInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
    }

    private fun applyLightNoiseSmooth(bmp: Bitmap, strength: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val blend = (strength * 0.45f).coerceIn(0f, 0.45f)
        val inv = 1f - blend
        val s = IntArray(w * h)
        val out = IntArray(w * h)
        bmp.getPixels(s, 0, w, 0, 0, w, h)
        System.arraycopy(s, 0, out, 0, s.size)
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val i = rowOff + x
                val c = s[i]
                val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                val n1 = s[i - 1]
                val n2 = s[i + 1]
                val n3 = s[i - w]
                val n4 = s[i + w]
                val avgR = (((n1 ushr 16) and 0xFF) + ((n2 ushr 16) and 0xFF) + ((n3 ushr 16) and 0xFF) + ((n4 ushr 16) and 0xFF)) * 0.25f
                val avgG = (((n1 ushr 8) and 0xFF) + ((n2 ushr 8) and 0xFF) + ((n3 ushr 8) and 0xFF) + ((n4 ushr 8) and 0xFF)) * 0.25f
                val avgB = ((n1 and 0xFF) + (n2 and 0xFF) + (n3 and 0xFF) + (n4 and 0xFF)) * 0.25f
                val nr = (((c ushr 16) and 0xFF) * inv + avgR * blend).roundToInt().coerceIn(0, 255)
                val ng = (((c ushr 8) and 0xFF) * inv + avgG * blend).roundToInt().coerceIn(0, 255)
                val nb = ((c and 0xFF) * inv + avgB * blend).roundToInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
    }

    private fun savePngToMedia(context: Context, bmp: Bitmap, prefix: String): String? {
        return runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}_${(Math.random() * 9999).toInt()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    fun applyAdvanceResize(
        context: Context,
        project: ProjectState,
        targetW: Int,
        targetH: Int,
        newDpi: Float,
        resample: Boolean,
        method: ResampleMethod,
        reduceNoisePct: Int
    ): ProjectState {
        val nw = targetW.coerceIn(MIN_DIM, MAX_DIM)
        val nh = targetH.coerceIn(MIN_DIM, MAX_DIM)
        val clampedDpi = newDpi.coerceIn(1f, 99999f)

        if (!resample || (nw == project.width && nh == project.height)) {
            return project.copy(dpi = clampedDpi, updatedAt = System.currentTimeMillis())
        }

        val sx = nw.toFloat() / project.width.coerceAtLeast(1).toFloat()
        val sy = nh.toFloat() / project.height.coerceAtLeast(1).toFloat()
        val avgS = (sx + sy) * 0.5f

        val newBg = if (project.background.type == BackgroundType.IMAGE && !project.background.imageUri.isNullOrBlank()) {
            val bgBmp = PixelLabRenderer.loadHighResBitmap(context, project.background.imageUri, 4096)
            if (bgBmp != null) {
                val resampled = resampleBitmap(
                    bgBmp,
                    max(1, (bgBmp.width * sx).roundToInt()),
                    max(1, (bgBmp.height * sy).roundToInt()),
                    method,
                    reduceNoisePct
                )
                val newPath = savePngToMedia(context, resampled, "bg_res")
                if (resampled != bgBmp) resampled.recycle()
                bgBmp.recycle()
                if (newPath != null) project.background.copy(imageUri = newPath) else project.background
            } else project.background
        } else {
            project.background.copy(
                cornerRadii = project.background.cornerRadii.map { it * avgS }
            )
        }

        val scaledLayers = project.layers.map { l ->
            val nx = l.x * sx
            val ny = l.y * sy
            val lw = (l.width * sx).coerceAtLeast(4f)
            val lh = (l.height * sy).coerceAtLeast(4f)
            when (l) {
                is TextLayer -> l.copy(
                    x = nx,
                    y = ny,
                    width = lw,
                    height = lh,
                    fontSize = (l.fontSize * sy).coerceIn(6f, 1200f),
                    letterSpacing = l.letterSpacing * sx,
                    paddingLeft = l.paddingLeft * sx,
                    paddingRight = l.paddingRight * sx,
                    stroke = l.stroke.copy(width = l.stroke.width * avgS),
                    shadow = l.shadow.copy(
                        blur = l.shadow.blur * avgS,
                        offsetX = l.shadow.offsetX * sx,
                        offsetY = l.shadow.offsetY * sy
                    ),
                    innerShadow = l.innerShadow.copy(
                        blur = l.innerShadow.blur * avgS,
                        offsetX = l.innerShadow.offsetX * sx,
                        offsetY = l.innerShadow.offsetY * sy
                    ),
                    background = l.background.copy(
                        radius = l.background.radius * avgS,
                        padLeft = l.background.padLeft * sx,
                        padRight = l.background.padRight * sx
                    )
                )
                is ShapeLayer -> l.copy(
                    x = nx,
                    y = ny,
                    width = lw,
                    height = lh,
                    cornerRadii = l.cornerRadii?.map { it * avgS },
                    stroke = l.stroke.copy(width = l.stroke.width * avgS),
                    shadow = l.shadow.copy(
                        blur = l.shadow.blur * avgS,
                        offsetX = l.shadow.offsetX * sx,
                        offsetY = l.shadow.offsetY * sy
                    ),
                    innerShadow = l.innerShadow.copy(
                        blur = l.innerShadow.blur * avgS,
                        offsetX = l.innerShadow.offsetX * sx,
                        offsetY = l.innerShadow.offsetY * sy
                    )
                )
                is ArrowLayer -> l.copy(
                    x = nx,
                    y = ny,
                    width = lw,
                    height = lh,
                    headSize = l.headSize * avgS,
                    stemWidth = l.stemWidth * avgS,
                    stroke = l.stroke.copy(width = l.stroke.width * avgS),
                    shadow = l.shadow.copy(
                        blur = l.shadow.blur * avgS,
                        offsetX = l.shadow.offsetX * sx,
                        offsetY = l.shadow.offsetY * sy
                    )
                )
                is BezierLayer -> l.copy(
                    x = nx,
                    y = ny,
                    width = lw,
                    height = lh,
                    stroke = l.stroke.copy(width = l.stroke.width * avgS),
                    shadow = l.shadow.copy(
                        blur = l.shadow.blur * avgS,
                        offsetX = l.shadow.offsetX * sx,
                        offsetY = l.shadow.offsetY * sy
                    )
                )
                is DrawingLayer -> l.copy(
                    x = nx,
                    y = ny,
                    width = lw,
                    height = lh
                )
                is ImageLayer -> {
                    var newUri = l.uriString
                    if (l.uriString.isNotBlank()) {
                        val srcBmp = PixelLabRenderer.loadHighResBitmap(context, l.uriString, 4096)
                        if (srcBmp != null) {
                            val targetBmpW = max(1, (srcBmp.width * sx).roundToInt()).coerceAtMost(4096)
                            val targetBmpH = max(1, (srcBmp.height * sy).roundToInt()).coerceAtMost(4096)
                            val resampled = resampleBitmap(srcBmp, targetBmpW, targetBmpH, method, reduceNoisePct)
                            val saved = savePngToMedia(context, resampled, "img_res")
                            if (resampled != srcBmp) resampled.recycle()
                            srcBmp.recycle()
                            if (saved != null) newUri = saved
                        }
                    }
                    l.copy(
                        x = nx,
                        y = ny,
                        width = lw,
                        height = lh,
                        uriString = newUri,
                        cornerRadii = l.cornerRadii.map { it * avgS },
                        stroke = l.stroke.copy(width = l.stroke.width * avgS),
                        shadow = l.shadow.copy(
                            blur = l.shadow.blur * avgS,
                            offsetX = l.shadow.offsetX * sx,
                            offsetY = l.shadow.offsetY * sy
                        ),
                        innerShadow = l.innerShadow.copy(
                            blur = l.innerShadow.blur * avgS,
                            offsetX = l.innerShadow.offsetX * sx,
                            offsetY = l.innerShadow.offsetY * sy
                        )
                    )
                }
            }
        }

        PixelLabRenderer.clearCache()
        return project.copy(
            width = nw,
            height = nh,
            dpi = clampedDpi,
            background = newBg,
            layers = scaledLayers,
            updatedAt = System.currentTimeMillis()
        )
    }
}
