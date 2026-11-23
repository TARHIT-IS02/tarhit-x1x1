package com.imaginstudio.imagetools.pixellab.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Native Android Canvas renderer matching the HTML `#loupe` 9x9 pixel magnifier,
 * colored outer rim, dark `#RRGGBB` pill badge, and crosshair touch reticle.
 */
class PixelLoupeDrawer(private val density: Float) {

    data class LoupeLayout(
        val loupeLeft: Float,
        val loupeTop: Float,
        val loupeSize: Float,
        val flip: Boolean,
        val pillLeft: Float,
        val pillTop: Float,
        val pillWidth: Float,
        val pillHeight: Float
    )

    companion object {
        const val LOUPE_N = 9
        const val LOUPE_HALF = 4

        private val BG_DARK = Color.rgb(43, 43, 48) // #2b2b30
        private val PILL_BG = Color.argb(240, 18, 18, 22) // rgba(18,18,22,.94)
        private val GRID_COLOR = Color.argb(115, 128, 128, 128) // rgba(128,128,128,.45)
        private val RIM_DARK = Color.argb(128, 0, 0, 0) // rgba(0,0,0,.5)
        private val TICK_DARK = Color.argb(145, 0, 0, 0) // rgba(0,0,0,.55)

        fun formatHexRgb(color: Int): String =
            String.format("#%06X", color and 0x00FFFFFF)

        fun compositeOverDark(color: Int, bg: Int = BG_DARK): Int {
            val a = Color.alpha(color)
            if (a >= 255) return color
            val inv = 255 - a
            val r = (Color.red(color) * a + Color.red(bg) * inv) / 255
            val g = (Color.green(color) * a + Color.green(bg) * inv) / 255
            val b = (Color.blue(color) * a + Color.blue(bg) * inv) / 255
            return Color.rgb(r, g, b)
        }

        fun computeLoupeLayout(
            touchX: Float,
            touchY: Float,
            viewWidth: Float,
            viewHeight: Float,
            density: Float,
            pillWidth: Float,
            pillHeight: Float,
            flipThresholdTopPx: Float = 56f * density
        ): LoupeLayout {
            val size = 88f * density
            val gap = 34f * density
            val marginH = 10f * density
            val pillGap = 14f * density

            val maxLeft = max(marginH, viewWidth - size - marginH)
            val left = (touchX - size * 0.5f).coerceIn(marginH, maxLeft)

            val minRequiredAbove = max(flipThresholdTopPx, pillHeight + pillGap + 6f * density)
            var top = touchY - size - gap
            var flip = false
            if (top < minRequiredAbove) {
                top = touchY + gap
                flip = true
            }
            val minTop = if (flip) 8f * density else pillHeight + pillGap + 6f * density
            val maxTop = max(
                minTop,
                viewHeight - size - (if (flip) pillHeight + pillGap + 10f * density else 10f * density)
            )
            top = top.coerceIn(minTop, maxTop)

            val cx = left + size * 0.5f
            val maxPillLeft = max(8f * density, viewWidth - pillWidth - 8f * density)
            val pillLeft = (cx - pillWidth * 0.5f).coerceIn(8f * density, maxPillLeft)
            val pillTop = if (!flip) {
                (top - pillGap - pillHeight).coerceAtLeast(4f * density)
            } else {
                min(top + size + pillGap, max(4f * density, viewHeight - pillHeight - 4f * density))
            }

            return LoupeLayout(
                loupeLeft = left,
                loupeTop = top,
                loupeSize = size,
                flip = flip,
                pillLeft = pillLeft,
                pillTop = pillTop,
                pillWidth = pillWidth,
                pillHeight = pillHeight
            )
        }
    }

    private val clipPath = Path()
    private val cellRect = RectF()
    private val pillRect = RectF()
    private val centerRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BG_DARK
    }

    private val cellPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = GRID_COLOR
        strokeWidth = max(1f, 0.8f * density)
    }

    private val centerBlackPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f * density
    }

    private val centerWhitePaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1f * density
    }

    private val shadowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }

    private val ringWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.6f * density
    }

    private val ringDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = RIM_DARK
        strokeWidth = 1.1f * density
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PILL_BG
    }

    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 12.2f * density
    }

    private val swatchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val swatchWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }

    private val swatchDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(102, 0, 0, 0)
        strokeWidth = 1f * density
    }

    private val crossShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TICK_DARK
        strokeWidth = 3f * density
    }

    private val crossFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val crossWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.75f * density
    }

    private val crossTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TICK_DARK
        strokeWidth = 1.15f * density
    }

    fun drawCrosshairOnly(
        canvas: Canvas,
        x: Float,
        y: Float,
        sampledColor: Int
    ) {
        val r = 9f * density
        val tickInner = 5f * density
        val tickOuter = 14f * density

        canvas.drawCircle(x, y, r, crossShadowPaint)
        crossFillPaint.color = compositeOverDark(sampledColor)
        canvas.drawCircle(x, y, r, crossFillPaint)
        canvas.drawCircle(x, y, r, crossWhitePaint)

        canvas.drawLine(x - tickOuter, y, x - tickInner, y, crossTickPaint)
        canvas.drawLine(x + tickInner, y, x + tickOuter, y, crossTickPaint)
        canvas.drawLine(x, y - tickOuter, x, y - tickInner, crossTickPaint)
        canvas.drawLine(x, y + tickInner, x, y + tickOuter, crossTickPaint)
    }

    fun drawLoupeWithCrosshair(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        bitmap: Bitmap,
        sampleBmpX: Int,
        sampleBmpY: Int,
        touchX: Float,
        touchY: Float,
        sampledColor: Int,
        showLoupe: Boolean,
        flipThresholdTopPx: Float = 56f * density
    ) {
        drawCrosshairOnly(canvas, touchX, touchY, sampledColor)
        if (!showLoupe || bitmap.isRecycled || viewWidth <= 0f || viewHeight <= 0f) return

        val hexText = formatHexRgb(sampledColor)
        val swSize = 16f * density
        val padLeft = 7f * density
        val padRight = 12f * density
        val padV = 6f * density
        val gap = 7f * density
        val fm = pillTextPaint.fontMetrics
        val textH = ceil((fm.descent - fm.ascent).toDouble()).toFloat()
        val pillH = max(swSize, textH) + padV * 2f
        val pillW = padLeft + swSize + gap + pillTextPaint.measureText(hexText) + padRight

        val layout = computeLoupeLayout(
            touchX = touchX,
            touchY = touchY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            density = density,
            pillWidth = pillW,
            pillHeight = pillH,
            flipThresholdTopPx = flipThresholdTopPx
        )

        val size = layout.loupeSize
        val left = layout.loupeLeft
        val top = layout.loupeTop
        val cx = left + size * 0.5f
        val cy = top + size * 0.5f
        val radius = size * 0.5f
        val outerRingRadius = radius + 6.5f * density
        val solidSample = compositeOverDark(sampledColor)

        // 1. Soft drop shadow behind circle (compatible across all API levels)
        val shadowOffsetY = 6f * density
        for (step in 5 downTo 1) {
            val spread = step * 2.2f * density
            shadowFillPaint.color = Color.argb(26 - step * 4, 0, 0, 0)
            canvas.drawCircle(cx, cy + shadowOffsetY * (step / 5f), outerRingRadius + spread, shadowFillPaint)
        }

        // 2. Outer circular rings: --lp-c (4dp), white (1.5dp), dark hairline (1dp)
        ringColorPaint.color = solidSample
        canvas.drawCircle(cx, cy, radius + 2f * density, ringColorPaint)
        canvas.drawCircle(cx, cy, radius + 4.75f * density, ringWhitePaint)
        canvas.drawCircle(cx, cy, radius + 6.0f * density, ringDarkPaint)

        // 3. Clipped 9x9 crisp nearest-neighbor pixel grid + gridlines + center reticle
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        canvas.drawCircle(cx, cy, radius, bgPaint)

        val cell = size / LOUPE_N
        val bmpW = bitmap.width
        val bmpH = bitmap.height
        val safeCx = sampleBmpX.coerceIn(0, bmpW - 1)
        val safeCy = sampleBmpY.coerceIn(0, bmpH - 1)

        for (dy in -LOUPE_HALF..LOUPE_HALF) {
            val py = safeCy + dy
            val cTop = top + (dy + LOUPE_HALF) * cell
            val cBottom = cTop + cell + 0.6f
            for (dx in -LOUPE_HALF..LOUPE_HALF) {
                val px = safeCx + dx
                if (px in 0 until bmpW && py in 0 until bmpH) {
                    val pixel = bitmap.getPixel(px, py)
                    cellPaint.color = compositeOverDark(pixel)
                    val cLeft = left + (dx + LOUPE_HALF) * cell
                    cellRect.set(cLeft, cTop, cLeft + cell + 0.6f, cBottom)
                    canvas.drawRect(cellRect, cellPaint)
                }
            }
        }

        // Pixel separator lines
        for (i in 1 until LOUPE_N) {
            val vx = left + i * cell
            canvas.drawLine(vx, top, vx, top + size, gridPaint)
            val hy = top + i * cell
            canvas.drawLine(left, hy, left + size, hy, gridPaint)
        }

        // Center cell (4, 4) outer black + inner white frame
        val cLeft = left + LOUPE_HALF * cell
        val cTop = top + LOUPE_HALF * cell
        centerRect.set(cLeft, cTop, cLeft + cell, cTop + cell)
        canvas.drawRect(centerRect, centerBlackPaint)
        val inset = 1.5f * density
        centerRect.inset(inset, inset)
        canvas.drawRect(centerRect, centerWhitePaint)

        canvas.restore()

        // 4. Floating dark pill badge (#RRGGBB + 16dp swatch dot)
        pillRect.set(layout.pillLeft, layout.pillTop, layout.pillLeft + pillW, layout.pillTop + pillH)
        val pillCorner = pillH * 0.5f

        for (step in 4 downTo 1) {
            val sp = step * 1.6f * density
            shadowFillPaint.color = Color.argb(26 - step * 5, 0, 0, 0)
            canvas.drawRoundRect(
                pillRect.left - sp * 0.4f,
                pillRect.top + 3f * density * (step / 4f),
                pillRect.right + sp * 0.4f,
                pillRect.bottom + sp * 0.8f + 3f * density,
                pillCorner + sp,
                pillCorner + sp,
                shadowFillPaint
            )
        }

        canvas.drawRoundRect(pillRect, pillCorner, pillCorner, pillBgPaint)

        val swCx = pillRect.left + padLeft + swSize * 0.5f
        val swCy = pillRect.centerY()
        val swR = swSize * 0.5f
        swatchFillPaint.color = solidSample
        canvas.drawCircle(swCx, swCy, swR, swatchFillPaint)
        canvas.drawCircle(swCx, swCy, swR - 1f * density, swatchWhitePaint)
        canvas.drawCircle(swCx, swCy, swR + 0.5f * density, swatchDarkPaint)

        val textX = pillRect.left + padLeft + swSize + gap
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(hexText, textX, baseline, pillTextPaint)
    }
}
