package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object WarpEngine {

    enum class WarpMode { BRUSH, PAPER }

    const val GRID_N = 4

    data class WarpSession(
        val initialPaddedBmp: Bitmap,
        var workBmp: Bitmap,
        var paperSrcBmp: Bitmap,
        val imgRectInBmp: RectF,
        val gridPts: MutableList<PointF>,
        val paddedCanvasW: Float,
        val paddedCanvasH: Float,
        val origLayerW: Float,
        val origLayerH: Float
    )

    data class TrimResult(
        val trimmedBmp: Bitmap,
        val centerOffsetCanvasX: Float,
        val centerOffsetCanvasY: Float,
        val newCanvasW: Float,
        val newCanvasH: Float
    )

    fun createSession(
        src: Bitmap,
        layerW: Float,
        layerH: Float,
        canvasW: Float,
        canvasH: Float
    ): WarpSession {
        // Generous canvas-space padding so brush/paper warping never clips against original layer bounds
        val padCanvasX = max(layerW * 0.65f, canvasW * 0.40f).coerceAtLeast(80f)
        val padCanvasY = max(layerH * 0.65f, canvasH * 0.40f).coerceAtLeast(80f)
        val paddedCanvasW = layerW + padCanvasX * 2f
        val paddedCanvasH = layerH + padCanvasY * 2f

        val maxBmpDim = 1150f
        val scaleToBmp = min(
            maxBmpDim / max(paddedCanvasW, paddedCanvasH).coerceAtLeast(1f),
            max(src.width.toFloat() / layerW.coerceAtLeast(1f), src.height.toFloat() / layerH.coerceAtLeast(1f))
        ).coerceAtMost(maxBmpDim / max(paddedCanvasW, paddedCanvasH).coerceAtLeast(1f))
            .coerceAtLeast(220f / max(paddedCanvasW, paddedCanvasH).coerceAtLeast(1f))

        val bmpW = (paddedCanvasW * scaleToBmp).roundToInt().coerceIn(220, 1200)
        val bmpH = (paddedCanvasH * scaleToBmp).roundToInt().coerceIn(220, 1200)

        val imgW = (layerW * (bmpW / paddedCanvasW)).coerceAtLeast(16f)
        val imgH = (layerH * (bmpH / paddedCanvasH)).coerceAtLeast(16f)
        val left = (bmpW - imgW) * 0.5f
        val top = (bmpH - imgH) * 0.5f
        val imgRect = RectF(left, top, left + imgW, top + imgH)

        val padded = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(padded)
        c.drawBitmap(src, null, imgRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        val paperSrc = Bitmap.createScaledBitmap(
            src,
            imgW.roundToInt().coerceAtLeast(8),
            imgH.roundToInt().coerceAtLeast(8),
            true
        )

        return WarpSession(
            initialPaddedBmp = padded.copy(Bitmap.Config.ARGB_8888, false),
            workBmp = padded,
            paperSrcBmp = paperSrc,
            imgRectInBmp = imgRect,
            gridPts = createGridForRect(imgRect),
            paddedCanvasW = paddedCanvasW,
            paddedCanvasH = paddedCanvasH,
            origLayerW = layerW,
            origLayerH = layerH
        )
    }

    fun createGridForRect(rect: RectF): MutableList<PointF> {
        val pts = ArrayList<PointF>(GRID_N * GRID_N)
        for (r in 0 until GRID_N) {
            val v = r / (GRID_N - 1f)
            for (c in 0 until GRID_N) {
                val u = c / (GRID_N - 1f)
                pts.add(PointF(rect.left + u * rect.width(), rect.top + v * rect.height()))
            }
        }
        return pts
    }

    fun extractNonZeroRect(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (((row[x] ushr 24) and 0xFF) > 1) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return Rect(0, 0, w, h)
        }
        return Rect(
            max(0, minX - 2),
            max(0, minY - 2),
            min(w, maxX + 3),
            min(h, maxY + 3)
        )
    }

    fun buildPaperSourceFromWork(session: WarpSession): Pair<Bitmap, RectF> {
        val bounds = extractNonZeroRect(session.workBmp)
        val bw = bounds.width().coerceAtLeast(8)
        val bh = bounds.height().coerceAtLeast(8)
        val sub = Bitmap.createBitmap(session.workBmp, bounds.left, bounds.top, bw, bh)
        return sub to RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
    }

    fun warpBrushSegment(
        bmp: Bitmap,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radiusPx: Float,
        strengthPct: Int
    ) {
        val r = max(2f, radiusPx)
        val dist = hypot(bx - ax, by - ay)
        if (dist < 0.5f) return
        val step = max(1f, r * 0.2f)
        val n = max(1, ceil(dist / step).toInt())
        val dx = (bx - ax) / n
        val dy = (by - ay) / n
        val k = max(0.05f, strengthPct.coerceIn(5, 100) / 100f)
        for (i in 1..n) {
            warpPush(bmp, ax + dx * i, ay + dy * i, dx, dy, r, k)
        }
    }

    fun warpPush(
        bmp: Bitmap,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
        r: Float,
        k: Float
    ) {
        val bigR = ceil(r).toInt() + 2
        val sx = max(0, floor(cx - bigR).toInt())
        val sy = max(0, floor(cy - bigR).toInt())
        val ex = min(bmp.width, ceil(cx + bigR).toInt())
        val ey = min(bmp.height, ceil(cy + bigR).toInt())
        val w = ex - sx
        val h = ey - sy
        if (w < 2 || h < 2) return

        val src = IntArray(w * h)
        val out = IntArray(w * h)
        bmp.getPixels(src, 0, w, sx, sy, w, h)
        System.arraycopy(src, 0, out, 0, src.size)

        val rr = r * r
        val maxX = (w - 1).toFloat()
        val maxY = (h - 1).toFloat()

        for (y in 0 until h) {
            val uy = sy + y + 0.5f - cy
            val uySq = uy * uy
            val rowOff = y * w
            for (x in 0 until w) {
                val ux = sx + x + 0.5f - cx
                val t = (ux * ux + uySq) / rr
                if (t >= 1f) continue

                val f = (1f - t) * (1f - t) * k
                var fx = x - dx * f
                var fy = y - dy * f
                if (fx < 0f) fx = 0f else if (fx > maxX) fx = maxX
                if (fy < 0f) fy = 0f else if (fy > maxY) fy = maxY

                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = min(w - 1, x0 + 1)
                val y1 = min(h - 1, y0 + 1)
                val tx = fx - x0
                val ty = fy - y0
                val itx = 1f - tx
                val ity = 1f - ty

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumA = 0f

                val p00 = src[y0 * w + x0]
                val w00 = itx * ity
                if (w00 > 0f) {
                    val a = ((p00 ushr 24) and 0xFF) * w00
                    sumR += ((p00 ushr 16) and 0xFF) * a
                    sumG += ((p00 ushr 8) and 0xFF) * a
                    sumB += (p00 and 0xFF) * a
                    sumA += a
                }
                val p10 = src[y0 * w + x1]
                val w10 = tx * ity
                if (w10 > 0f) {
                    val a = ((p10 ushr 24) and 0xFF) * w10
                    sumR += ((p10 ushr 16) and 0xFF) * a
                    sumG += ((p10 ushr 8) and 0xFF) * a
                    sumB += (p10 and 0xFF) * a
                    sumA += a
                }
                val p01 = src[y1 * w + x0]
                val w01 = itx * ty
                if (w01 > 0f) {
                    val a = ((p01 ushr 24) and 0xFF) * w01
                    sumR += ((p01 ushr 16) and 0xFF) * a
                    sumG += ((p01 ushr 8) and 0xFF) * a
                    sumB += (p01 and 0xFF) * a
                    sumA += a
                }
                val p11 = src[y1 * w + x1]
                val w11 = tx * ty
                if (w11 > 0f) {
                    val a = ((p11 ushr 24) and 0xFF) * w11
                    sumR += ((p11 ushr 16) and 0xFF) * a
                    sumG += ((p11 ushr 8) and 0xFF) * a
                    sumB += (p11 and 0xFF) * a
                    sumA += a
                }

                out[rowOff + x] = if (sumA > 0.001f) {
                    val aa = sumA.roundToInt().coerceIn(0, 255)
                    val rrC = (sumR / sumA).roundToInt().coerceIn(0, 255)
                    val ggC = (sumG / sumA).roundToInt().coerceIn(0, 255)
                    val bbC = (sumB / sumA).roundToInt().coerceIn(0, 255)
                    (aa shl 24) or (rrC shl 16) or (ggC shl 8) or bbC
                } else {
                    0
                }
            }
        }
        bmp.setPixels(out, 0, w, sx, sy, w, h)
    }

    fun renderPaperMesh(src: Bitmap, dst: Bitmap, grid: List<PointF>) {
        if (grid.size != GRID_N * GRID_N) return
        val canvas = Canvas(dst)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val subPerCell = 8
        val meshCols = (GRID_N - 1) * subPerCell
        val meshRows = (GRID_N - 1) * subPerCell
        val verts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)

        fun bil(a: PointF, b: PointF, c: PointF, d: PointF, u: Float, v: Float): Pair<Float, Float> {
            val topX = a.x + (b.x - a.x) * u
            val topY = a.y + (b.y - a.y) * u
            val botX = c.x + (d.x - c.x) * u
            val botY = c.y + (d.y - c.y) * u
            return (topX + (botX - topX) * v) to (topY + (botY - topY) * v)
        }

        var idx = 0
        for (mr in 0..meshRows) {
            val cellR = min(GRID_N - 2, mr / subPerCell)
            val v = (mr - cellR * subPerCell).toFloat() / subPerCell.toFloat()
            for (mc in 0..meshCols) {
                val cellC = min(GRID_N - 2, mc / subPerCell)
                val u = (mc - cellC * subPerCell).toFloat() / subPerCell.toFloat()
                val i00 = cellR * GRID_N + cellC
                val (px, py) = bil(grid[i00], grid[i00 + 1], grid[i00 + GRID_N], grid[i00 + GRID_N + 1], u, v)
                verts[idx++] = px
                verts[idx++] = py
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmapMesh(src, meshCols, meshRows, verts, 0, null, 0, paint)
    }

    fun trimAndMeasureResult(
        session: WarpSession,
        flipH: Boolean,
        flipV: Boolean,
        rotationDeg: Float
    ): TrimResult {
        val work = session.workBmp
        val r = extractNonZeroRect(work)
        val bw = work.width.coerceAtLeast(1).toFloat()
        val bh = work.height.coerceAtLeast(1).toFloat()

        val trimmed = Bitmap.createBitmap(work, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))

        val cropCenterU = (r.left + r.width() * 0.5f) / bw
        val cropCenterV = (r.top + r.height() * 0.5f) / bh

        // Account for flip when translating from bitmap local shift to rotated canvas space
        val lx = ((if (flipH) 1f - cropCenterU else cropCenterU) - 0.5f) * session.paddedCanvasW
        val ly = ((if (flipV) 1f - cropCenterV else cropCenterV) - 0.5f) * session.paddedCanvasH
        val rad = rotationDeg * PI / 180.0
        val dxCanvas = (lx * cos(rad) - ly * sin(rad)).toFloat()
        val dyCanvas = (lx * sin(rad) + ly * cos(rad)).toFloat()

        val newCanvasW = (r.width() / bw) * session.paddedCanvasW
        val newCanvasH = (r.height() / bh) * session.paddedCanvasH
        return TrimResult(
            trimmedBmp = trimmed,
            centerOffsetCanvasX = dxCanvas,
            centerOffsetCanvasY = dyCanvas,
            newCanvasW = max(16f, newCanvasW),
            newCanvasH = max(16f, newCanvasH)
        )
    }

    fun saveWarpedBitmap(context: Context, bmp: Bitmap): String? {
        return runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val file = File(dir, "warped_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }
}
