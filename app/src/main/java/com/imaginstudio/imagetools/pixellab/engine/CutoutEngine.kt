package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object CutoutEngine {

    enum class CutoutMode(val label: String) {
        LASSO("Lasso"),
        POLYGON("Polygon"),
        RECT("Rect"),
        ELLIPSE("Ellipse"),
        MAGIC_WAND("Magic Wand"),
        COLOR_RANGE("Color Range"),
        QUICK_SELECT("Quick Brush"),
        AUTO_SELECT("Auto Select")
    }

    enum class SelectionOp(val label: String) { NEW("New"), ADD("Add"), SUB("Sub"), INT("Intersect") }

    class CutoutSession(
        val initialBmp: Bitmap,
        var workBmp: Bitmap,
        val w: Int,
        val h: Int
    ) {
        val pixels = IntArray(w * h)
        var mask = IntArray(w * h)
        var baseMask = IntArray(w * h)
        var hasMask: Boolean = false
        var hasImageChanges: Boolean = false
        val overlayBmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        private val overlayBuf = IntArray(w * h)

        init {
            refreshPixels()
        }

        fun refreshPixels() {
            workBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        }

        fun snapshotBaseMask() {
            System.arraycopy(mask, 0, baseMask, 0, mask.size)
        }

        fun updateOverlay() {
            var any = false
            val fillCol = Color.argb(92, 47, 107, 255)
            val edgeCol = Color.argb(245, 255, 255, 255)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    val a = mask[idx]
                    if (a > 12) {
                        any = true
                        val isBorder =
                            x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
                            mask[idx - 1] <= 12 || mask[idx + 1] <= 12 ||
                            mask[idx - w] <= 12 || mask[idx + w] <= 12
                        overlayBuf[idx] = if (isBorder) edgeCol else fillCol
                    } else {
                        overlayBuf[idx] = 0
                    }
                }
            }
            hasMask = any
            overlayBmp.setPixels(overlayBuf, 0, w, 0, 0, w, h)
        }

        fun clearMask() {
            mask.fill(0)
            baseMask.fill(0)
            hasMask = false
            overlayBuf.fill(0)
            overlayBmp.setPixels(overlayBuf, 0, w, 0, 0, w, h)
        }

        fun recycle() {
            if (!initialBmp.isRecycled) initialBmp.recycle()
            if (!workBmp.isRecycled) workBmp.recycle()
            if (!overlayBmp.isRecycled) overlayBmp.recycle()
        }
    }

    fun createSession(src: Bitmap): CutoutSession {
        val maxSide = 1024f
        val scale = min(1f, maxSide / max(src.width, src.height).coerceAtLeast(1))
        val w = (src.width * scale).roundToInt().coerceAtLeast(16)
        val h = (src.height * scale).roundToInt().coerceAtLeast(16)
        val work = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val init = work.copy(Bitmap.Config.ARGB_8888, false)
        return CutoutSession(init, work, w, h)
    }

    fun combineMaskValue(existing: Int, incoming: Int, op: SelectionOp): Int {
        return when (op) {
            SelectionOp.NEW -> incoming
            SelectionOp.ADD -> max(existing, incoming)
            SelectionOp.SUB -> max(0, existing - incoming)
            SelectionOp.INT -> min(existing, incoming)
        }
    }

    fun applyMaskBuffer(mask: IntArray, incoming: IntArray, op: SelectionOp) {
        for (i in mask.indices) {
            mask[i] = combineMaskValue(mask[i], incoming[i], op)
        }
    }

    fun applyPathSelection(
        session: CutoutSession,
        op: SelectionOp,
        drawFn: (Canvas, Paint) -> Unit
    ) {
        val temp = Bitmap.createBitmap(session.w, session.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(temp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        drawFn(c, p)
        val tmpPx = IntArray(session.w * session.h)
        temp.getPixels(tmpPx, 0, session.w, 0, 0, session.w, session.h)
        temp.recycle()

        for (i in tmpPx.indices) {
            val a = (tmpPx[i] ushr 24) and 0xFF
            session.mask[i] = combineMaskValue(session.mask[i], a, op)
        }
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    fun paintQuickSelection(
        session: CutoutSession,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radiusPx: Float,
        subtract: Boolean
    ) {
        val r = max(2f, radiusPx)
        val dist = hypot(bx - ax, by - ay)
        val steps = max(1, ceil(dist / max(1f, r * 0.25f)).toInt())
        val dx = (bx - ax) / steps
        val dy = (by - ay) / steps
        val rr = r * r
        for (s in 0..steps) {
            val cx = ax + dx * s
            val cy = ay + dy * s
            val sx = max(0, floor(cx - r).toInt())
            val sy = max(0, floor(cy - r).toInt())
            val ex = min(session.w - 1, ceil(cx + r).toInt())
            val ey = min(session.h - 1, ceil(cy + r).toInt())
            for (y in sy..ey) {
                val dy2 = (y - cy) * (y - cy)
                val row = y * session.w
                for (x in sx..ex) {
                    val d2 = (x - cx) * (x - cx) + dy2
                    if (d2 <= rr) {
                        val edge = (1f - sqrt(d2) / r).coerceIn(0f, 1f)
                        val alpha = (edge * 4f).coerceIn(0f, 1f)
                        val v = (alpha * 255f).roundToInt()
                        val idx = row + x
                        session.mask[idx] = if (subtract) {
                            min(session.mask[idx], 255 - v)
                        } else {
                            max(session.mask[idx], v)
                        }
                    }
                }
            }
        }
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    fun selectMagicWand(
        session: CutoutSession,
        tapX: Int,
        tapY: Int,
        tolerance: Int,
        op: SelectionOp
    ) {
        val w = session.w
        val h = session.h
        if (tapX !in 0 until w || tapY !in 0 until h) return
        val px = session.pixels
        val start = tapY * w + tapX
        val seed = px[start]
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        val sa = Color.alpha(seed)
        val tolSq = tolerance * tolerance * 4

        val region = IntArray(w * h)
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = start
        seen[start] = true

        while (head < tail) {
            val i = queue[head++]
            region[i] = 255
            val x = i % w
            val y = i / w
            if (x > 0) {
                val n = i - 1
                if (!seen[n] && colorNear(px[n], sr, sg, sb, sa, tolSq)) {
                    seen[n] = true; queue[tail++] = n
                }
            }
            if (x < w - 1) {
                val n = i + 1
                if (!seen[n] && colorNear(px[n], sr, sg, sb, sa, tolSq)) {
                    seen[n] = true; queue[tail++] = n
                }
            }
            if (y > 0) {
                val n = i - w
                if (!seen[n] && colorNear(px[n], sr, sg, sb, sa, tolSq)) {
                    seen[n] = true; queue[tail++] = n
                }
            }
            if (y < h - 1) {
                val n = i + w
                if (!seen[n] && colorNear(px[n], sr, sg, sb, sa, tolSq)) {
                    seen[n] = true; queue[tail++] = n
                }
            }
        }
        applyMaskBuffer(session.mask, region, op)
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    fun selectColorRange(
        session: CutoutSession,
        tapX: Int,
        tapY: Int,
        tolerance: Int,
        op: SelectionOp
    ) {
        val w = session.w
        val h = session.h
        if (tapX !in 0 until w || tapY !in 0 until h) return
        val px = session.pixels
        val seed = px[tapY * w + tapX]
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        val sa = Color.alpha(seed)
        val maxD = max(1f, tolerance * 2f)

        for (i in px.indices) {
            val c = px[i]
            val dr = ((c ushr 16) and 0xFF) - sr
            val dg = ((c ushr 8) and 0xFF) - sg
            val db = (c and 0xFF) - sb
            val da = ((c ushr 24) and 0xFF) - sa
            val d = sqrt((dr * dr + dg * dg + db * db + da * da).toFloat())
            val alpha = if (d <= maxD) {
                val soft = ((1f - (d / maxD) * 0.35f) * 255f).roundToInt().coerceIn(0, 255)
                if (d < maxD * 0.7f) 255 else soft
            } else 0
            session.mask[i] = combineMaskValue(session.mask[i], alpha, op)
        }
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    /**
     * One-tap subject/object selection: floods background from outer borders matching edge palette
     * or grows connected region from tap point when tapped inside a distinct foreground object.
     */
    fun selectAutoSubject(
        session: CutoutSession,
        tapX: Int,
        tapY: Int,
        tolerance: Int,
        op: SelectionOp
    ) {
        val w = session.w
        val h = session.h
        if (tapX !in 0 until w || tapY !in 0 until h) return
        val px = session.pixels
        val total = w * h

        // Check if image has transparent background first -> select opaque subject
        var transparentCount = 0
        for (i in 0 until total step 13) {
            if (((px[i] ushr 24) and 0xFF) < 24) transparentCount++
        }
        val region = IntArray(total)
        if (transparentCount > total / 130) {
            // Select connected opaque component around tap, or all opaque pixels
            val seedAlpha = (px[tapY * w + tapX] ushr 24) and 0xFF
            if (seedAlpha >= 24) {
                val seen = BooleanArray(total)
                val queue = IntArray(total)
                var head = 0
                var tail = 0
                val start = tapY * w + tapX
                seen[start] = true
                queue[tail++] = start
                while (head < tail) {
                    val i = queue[head++]
                    region[i] = (px[i] ushr 24) and 0xFF
                    val x = i % w
                    val y = i / w
                    if (x > 0 && !seen[i - 1] && ((px[i - 1] ushr 24) and 0xFF) >= 24) { seen[i - 1] = true; queue[tail++] = i - 1 }
                    if (x < w - 1 && !seen[i + 1] && ((px[i + 1] ushr 24) and 0xFF) >= 24) { seen[i + 1] = true; queue[tail++] = i + 1 }
                    if (y > 0 && !seen[i - w] && ((px[i - w] ushr 24) and 0xFF) >= 24) { seen[i - w] = true; queue[tail++] = i - w }
                    if (y < h - 1 && !seen[i + w] && ((px[i + w] ushr 24) and 0xFF) >= 24) { seen[i + w] = true; queue[tail++] = i + w }
                }
                applyMaskBuffer(session.mask, region, op)
                session.snapshotBaseMask()
                session.updateOverlay()
                return
            }
        }

        // Opaque photo: flood background from border pixels matching corner/edge backdrop, keep foreground
        val corners = intArrayOf(
            px[0], px[w - 1], px[(h - 1) * w], px[total - 1]
        )
        var br = 0; var bg = 0; var bb = 0
        for (c in corners) {
            br += Color.red(c)
            bg += Color.green(c)
            bb += Color.blue(c)
        }
        br /= 4; bg /= 4; bb /= 4

        val tapCol = px[tapY * w + tapX]
        val tapDistFromBg = hypot(
            (Color.red(tapCol) - br).toFloat(),
            hypot((Color.green(tapCol) - bg).toFloat(), (Color.blue(tapCol) - bb).toFloat())
        )
        val tolSq = max(24, tolerance + 16).let { it * it * 4 }

        if (tapDistFromBg < tolerance * 1.4f) {
            // User tapped background-like area -> fallback to adaptive magic wand at tap
            selectMagicWand(session, tapX, tapY, max(24, tolerance + 10), op)
            return
        }

        // Border flood-fill away background
        val isBg = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        fun trySeed(idx: Int) {
            if (!isBg[idx] && colorNear(px[idx], br, bg, bb, 255, tolSq)) {
                isBg[idx] = true
                queue[tail++] = idx
            }
        }
        for (x in 0 until w) {
            trySeed(x)
            trySeed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            trySeed(y * w)
            trySeed(y * w + (w - 1))
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) trySeed(i - 1)
            if (x < w - 1) trySeed(i + 1)
            if (y > 0) trySeed(i - w)
            if (y < h - 1) trySeed(i + w)
        }
        for (i in 0 until total) {
            region[i] = if (isBg[i]) 0 else 255
        }
        applyMaskBuffer(session.mask, region, op)
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    private fun colorNear(c: Int, sr: Int, sg: Int, sb: Int, sa: Int, tolSq: Int): Boolean {
        val dr = ((c ushr 16) and 0xFF) - sr
        val dg = ((c ushr 8) and 0xFF) - sg
        val db = (c and 0xFF) - sb
        val da = ((c ushr 24) and 0xFF) - sa
        return dr * dr + dg * dg + db * db + da * da <= tolSq
    }

    fun invertSelection(session: CutoutSession) {
        for (i in session.mask.indices) {
            session.mask[i] = 255 - session.mask[i]
        }
        session.snapshotBaseMask()
        session.updateOverlay()
    }

    private fun separableBoxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val f = radius.coerceIn(1, 45)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val diam = f * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (kx in -f..f) {
                sum += src[row + kx.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                tmp[row + x] = sum / diam
                val rem = src[row + (x - f).coerceIn(0, w - 1)]
                val add = src[row + (x + f + 1).coerceIn(0, w - 1)]
                sum += add - rem
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (ky in -f..f) {
                sum += tmp[ky.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / diam
                val rem = tmp[(y - f).coerceIn(0, h - 1) * w + x]
                val add = tmp[(y + f + 1).coerceIn(0, h - 1) * w + x]
                sum += add - rem
            }
        }
        return out
    }

    fun applyMaskModifiers(
        session: CutoutSession,
        featherPx: Int,
        expandPx: Int,
        smoothPx: Int = 0,
        contrastPct: Int = 0
    ) {
        val w = session.w
        val h = session.h
        var work = session.baseMask.copyOf()

        // Smooth edge pass
        val sm = smoothPx.coerceIn(0, 40)
        if (sm > 0) {
            val blurred = separableBoxBlur(work, w, h, sm)
            for (i in work.indices) {
                work[i] = if (blurred[i] >= 128) 255 else 0
            }
        }

        // Expand / Contract morphological pass
        val exp = expandPx.coerceIn(-40, 40)
        if (exp != 0) {
            val r = kotlin.math.abs(exp)
            val tmp = IntArray(w * h)
            val out = IntArray(w * h)
            val isDilate = exp > 0
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var best = if (isDilate) 0 else 255
                    val x0 = max(0, x - r)
                    val x1 = min(w - 1, x + r)
                    for (kx in x0..x1) {
                        val v = work[row + kx]
                        best = if (isDilate) max(best, v) else min(best, v)
                    }
                    tmp[row + x] = best
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var best = if (isDilate) 0 else 255
                    val y0 = max(0, y - r)
                    val y1 = min(h - 1, y + r)
                    for (ky in y0..y1) {
                        val v = tmp[ky * w + x]
                        best = if (isDilate) max(best, v) else min(best, v)
                    }
                    out[y * w + x] = best
                }
            }
            work = out
        }

        // Separable feather blur
        val f = featherPx.coerceIn(0, 40)
        if (f > 0) {
            work = separableBoxBlur(work, w, h, f)
        }

        // Contrast edge sharpening
        val cPct = contrastPct.coerceIn(0, 100)
        if (cPct > 0) {
            val gain = 1f + cPct / 15f
            for (i in work.indices) {
                val n = work[i] / 255f
                val boosted = ((n - 0.5f) * gain + 0.5f).coerceIn(0f, 1f)
                work[i] = (boosted * 255f).roundToInt()
            }
        }

        session.mask = work
        session.updateOverlay()
    }

    fun keepInsideSelection(session: CutoutSession): Boolean {
        if (!session.hasMask) return false
        for (i in session.pixels.indices) {
            val c = session.pixels[i]
            val origA = (c ushr 24) and 0xFF
            val m = session.mask[i]
            val newA = (origA * m) / 255
            session.pixels[i] = (c and 0x00FFFFFF) or (newA shl 24)
        }
        session.workBmp.setPixels(session.pixels, 0, session.w, 0, 0, session.w, session.h)
        session.hasImageChanges = true
        session.clearMask()
        return true
    }

    fun eraseInsideSelection(session: CutoutSession): Boolean {
        if (!session.hasMask) return false
        for (i in session.pixels.indices) {
            val c = session.pixels[i]
            val origA = (c ushr 24) and 0xFF
            val invM = 255 - session.mask[i]
            val newA = (origA * invM) / 255
            session.pixels[i] = (c and 0x00FFFFFF) or (newA shl 24)
        }
        session.workBmp.setPixels(session.pixels, 0, session.w, 0, 0, session.w, session.h)
        session.hasImageChanges = true
        session.clearMask()
        return true
    }

    fun extractSelectionAsBitmap(session: CutoutSession): Bitmap? {
        if (!session.hasMask) return null
        val out = IntArray(session.pixels.size)
        for (i in session.pixels.indices) {
            val c = session.pixels[i]
            val origA = (c ushr 24) and 0xFF
            val newA = (origA * session.mask[i]) / 255
            out[i] = (c and 0x00FFFFFF) or (newA shl 24)
        }
        val full = Bitmap.createBitmap(session.w, session.h, Bitmap.Config.ARGB_8888)
        full.setPixels(out, 0, session.w, 0, 0, session.w, session.h)
        val r = WarpEngine.extractNonZeroRect(full)
        val cropped = Bitmap.createBitmap(full, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
        if (cropped != full) full.recycle()
        return cropped
    }

    fun resetSession(session: CutoutSession) {
        val c = Canvas(session.workBmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(session.initialBmp, 0f, 0f, null)
        session.refreshPixels()
        session.clearMask()
        session.hasImageChanges = false
    }

    fun saveTrimmedBitmap(context: Context, bmp: Bitmap, prefix: String = "cutout"): Pair<String, Rect>? {
        val r = WarpEngine.extractNonZeroRect(bmp)
        val trimmed = Bitmap.createBitmap(bmp, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
        val path = runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                trimmed.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
        if (trimmed != bmp) trimmed.recycle()
        return if (path != null) path to r else null
    }
}
