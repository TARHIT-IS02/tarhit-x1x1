package com.imaginstudio.imagetools.pixellab.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RetouchEngine {

    enum class RetouchMode(val label: String) {
        HEAL("Heal"),
        DODGE("Dodge"),
        BURN("Burn"),
        BLUR("Blur"),
        MOSAIC("Mosaic")
    }

    class RetouchSession(
        val initialBmp: Bitmap,
        var workBmp: Bitmap,
        val w: Int,
        val h: Int
    ) {
        var hasChanges: Boolean = false
        fun reset() {
            val c = Canvas(workBmp)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.drawBitmap(initialBmp, 0f, 0f, null)
            hasChanges = false
        }

        fun recycle() {
            if (!initialBmp.isRecycled) initialBmp.recycle()
            if (!workBmp.isRecycled) workBmp.recycle()
        }
    }

    fun createSession(src: Bitmap): RetouchSession {
        val maxSide = 1024f
        val scale = min(1f, maxSide / max(src.width, src.height).coerceAtLeast(1))
        val w = (src.width * scale).roundToInt().coerceAtLeast(16)
        val h = (src.height * scale).roundToInt().coerceAtLeast(16)
        val work = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val init = work.copy(Bitmap.Config.ARGB_8888, false)
        return RetouchSession(init, work, w, h)
    }

    fun retouchSegment(
        bmp: Bitmap,
        mode: RetouchMode,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radiusPx: Float,
        strengthPct: Int
    ) {
        val r = max(3f, radiusPx)
        val dist = hypot(bx - ax, by - ay)
        val step = max(2f, r * 0.35f)
        val n = max(1, ceil(dist / step).toInt())
        val dx = (bx - ax) / n
        val dy = (by - ay) / n
        val strength = strengthPct.coerceIn(5, 100) / 100f
        for (i in 0..n) {
            retouchDab(bmp, mode, ax + dx * i, ay + dy * i, r, strength)
        }
    }

    private fun retouchDab(
        bmp: Bitmap,
        mode: RetouchMode,
        cx: Float,
        cy: Float,
        r: Float,
        strength: Float
    ) {
        val pad = when (mode) {
            RetouchMode.HEAL -> max(6, (r * 0.35f).roundToInt())
            RetouchMode.BLUR -> 3
            else -> 1
        }
        val sx = max(0, floor(cx - r - pad).toInt())
        val sy = max(0, floor(cy - r - pad).toInt())
        val ex = min(bmp.width, ceil(cx + r + pad).toInt())
        val ey = min(bmp.height, ceil(cy + r + pad).toInt())
        val w = ex - sx
        val h = ey - sy
        if (w < 2 || h < 2) return

        val src = IntArray(w * h)
        val out = IntArray(w * h)
        bmp.getPixels(src, 0, w, sx, sy, w, h)
        System.arraycopy(src, 0, out, 0, src.size)
        val rr = r * r
        val block = max(4, (r * 0.28f).roundToInt())

        // For HEAL, sample average surround texture around the ring just outside r
        var ringR = 0
        var ringG = 0
        var ringB = 0
        var ringCnt = 0
        if (mode == RetouchMode.HEAL) {
            val innerR2 = rr * 0.72f
            val outerR2 = (r + pad) * (r + pad)
            for (y in 0 until h step 2) {
                val wy = sy + y + 0.5f - cy
                val wy2 = wy * wy
                val row = y * w
                for (x in 0 until w step 2) {
                    val wx = sx + x + 0.5f - cx
                    val d2 = wx * wx + wy2
                    if (d2 in innerR2..outerR2) {
                        val px = src[row + x]
                        val a = (px ushr 24) and 0xFF
                        if (a > 16) {
                            ringR += (px ushr 16) and 0xFF
                            ringG += (px ushr 8) and 0xFF
                            ringB += px and 0xFF
                            ringCnt++
                        }
                    }
                }
            }
        }
        val avgRingR = if (ringCnt > 0) ringR / ringCnt else 0
        val avgRingG = if (ringCnt > 0) ringG / ringCnt else 0
        val avgRingB = if (ringCnt > 0) ringB / ringCnt else 0

        for (y in 0 until h) {
            val wy = sy + y + 0.5f - cy
            val wy2 = wy * wy
            val row = y * w
            for (x in 0 until w) {
                val wx = sx + x + 0.5f - cx
                val d2 = wx * wx + wy2
                if (d2 >= rr) continue
                val falloff = (1f - sqrt(d2) / r).coerceIn(0f, 1f)
                val f = falloff * falloff * strength
                val idx = row + x
                val px = src[idx]
                val a = (px ushr 24) and 0xFF
                if (a == 0) continue

                when (mode) {
                    RetouchMode.HEAL -> {
                        var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                        val kx0 = max(0, x - 3)
                        val kx1 = min(w - 1, x + 3)
                        val ky0 = max(0, y - 3)
                        val ky1 = min(h - 1, y + 3)
                        for (ky in ky0..ky1 step 2) {
                            val kRow = ky * w
                            for (kx in kx0..kx1 step 2) {
                                val kp = src[kRow + kx]
                                if (((kp ushr 24) and 0xFF) > 16) {
                                    sr += (kp ushr 16) and 0xFF
                                    sg += (kp ushr 8) and 0xFF
                                    sb += kp and 0xFF
                                    cnt++
                                }
                            }
                        }
                        val localR = if (cnt > 0) sr / cnt else ((px ushr 16) and 0xFF)
                        val localG = if (cnt > 0) sg / cnt else ((px ushr 8) and 0xFF)
                        val localB = if (cnt > 0) sb / cnt else (px and 0xFF)
                        val targetR = if (ringCnt > 0) (localR * 0.65f + avgRingR * 0.35f) else localR.toFloat()
                        val targetG = if (ringCnt > 0) (localG * 0.65f + avgRingG * 0.35f) else localG.toFloat()
                        val targetB = if (ringCnt > 0) (localB * 0.65f + avgRingB * 0.35f) else localB.toFloat()
                        val blend = (f * 0.9f).coerceIn(0f, 1f)
                        val r0 = (px ushr 16) and 0xFF
                        val g0 = (px ushr 8) and 0xFF
                        val b0 = px and 0xFF
                        val rc = (r0 + (targetR - r0) * blend).roundToInt().coerceIn(0, 255)
                        val gc = (g0 + (targetG - g0) * blend).roundToInt().coerceIn(0, 255)
                        val bc = (b0 + (targetB - b0) * blend).roundToInt().coerceIn(0, 255)
                        out[idx] = (a shl 24) or (rc shl 16) or (gc shl 8) or bc
                    }
                    RetouchMode.DODGE -> {
                        val boost = 1f + f * 0.35f
                        val rc = min(255, (((px ushr 16) and 0xFF) * boost + f * 18f).roundToInt())
                        val gc = min(255, (((px ushr 8) and 0xFF) * boost + f * 18f).roundToInt())
                        val bc = min(255, ((px and 0xFF) * boost + f * 18f).roundToInt())
                        out[idx] = (a shl 24) or (rc shl 16) or (gc shl 8) or bc
                    }
                    RetouchMode.BURN -> {
                        val dim = max(0.2f, 1f - f * 0.28f)
                        val rc = (((px ushr 16) and 0xFF) * dim).roundToInt().coerceIn(0, 255)
                        val gc = (((px ushr 8) and 0xFF) * dim).roundToInt().coerceIn(0, 255)
                        val bc = ((px and 0xFF) * dim).roundToInt().coerceIn(0, 255)
                        out[idx] = (a shl 24) or (rc shl 16) or (gc shl 8) or bc
                    }
                    RetouchMode.BLUR -> {
                        var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                        val kx0 = max(0, x - 2)
                        val kx1 = min(w - 1, x + 2)
                        val ky0 = max(0, y - 2)
                        val ky1 = min(h - 1, y + 2)
                        for (ky in ky0..ky1) {
                            val kRow = ky * w
                            for (kx in kx0..kx1) {
                                val kp = src[kRow + kx]
                                sr += (kp ushr 16) and 0xFF
                                sg += (kp ushr 8) and 0xFF
                                sb += kp and 0xFF
                                cnt++
                            }
                        }
                        if (cnt > 0) {
                            val blend = (f * 0.85f).coerceIn(0f, 1f)
                            val r0 = (px ushr 16) and 0xFF
                            val g0 = (px ushr 8) and 0xFF
                            val b0 = px and 0xFF
                            val rc = (r0 + (sr / cnt - r0) * blend).roundToInt().coerceIn(0, 255)
                            val gc = (g0 + (sg / cnt - g0) * blend).roundToInt().coerceIn(0, 255)
                            val bc = (b0 + (sb / cnt - b0) * blend).roundToInt().coerceIn(0, 255)
                            out[idx] = (a shl 24) or (rc shl 16) or (gc shl 8) or bc
                        }
                    }
                    RetouchMode.MOSAIC -> {
                        val bx0 = ((((sx + x) / block) * block) - sx).coerceIn(0, w - 1)
                        val by0 = ((((sy + y) / block) * block) - sy).coerceIn(0, h - 1)
                        val mPx = src[by0 * w + bx0]
                        out[idx] = (a shl 24) or (mPx and 0x00FFFFFF)
                    }
                }
            }
        }
        bmp.setPixels(out, 0, w, sx, sy, w, h)
    }

    fun saveRetouchBitmap(context: Context, bmp: Bitmap): String? {
        return runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val file = File(dir, "retouch_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }
}
