package com.imaginstudio.imagetools.pixellab.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class CropBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

enum class CropHandle {
    NONE, MOVE, TL, TR, BL, BR, T, B, L, R
}

data class ZoomPanState(
    val zoom: Float,
    val panX: Float,
    val panY: Float
)

object CropZoomMath {
    const val MIN_ZOOM = 0.02f
    const val MAX_ZOOM = 32f
    const val FIT_PADDING_FACTOR = 0.92f
    const val MIN_CROP_SIZE = 24f

    fun clampZoom(z: Float): Float = z.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun computeFitZoom(
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        paddingFactor: Float = FIT_PADDING_FACTOR
    ): Float {
        if (viewportW <= 0f || viewportH <= 0f || imageW <= 0f || imageH <= 0f) return 1f
        val scale = min(
            (viewportW * paddingFactor) / imageW,
            (viewportH * paddingFactor) / imageH
        )
        return clampZoom(scale)
    }

    fun computeStageOrigin(
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        zoom: Float,
        panX: Float,
        panY: Float
    ): Pair<Float, Float> {
        val stageLeft = (viewportW - imageW * zoom) * 0.5f + panX
        val stageTop = (viewportH - imageH * zoom) * 0.5f + panY
        return stageLeft to stageTop
    }

    fun viewToImage(
        vx: Float,
        vy: Float,
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        zoom: Float,
        panX: Float,
        panY: Float
    ): Pair<Float, Float> {
        val (sl, st) = computeStageOrigin(viewportW, viewportH, imageW, imageH, zoom, panX, panY)
        val safeZoom = zoom.coerceAtLeast(MIN_ZOOM)
        return ((vx - sl) / safeZoom) to ((vy - st) / safeZoom)
    }

    fun zoomAnchoredAt(
        focusViewX: Float,
        focusViewY: Float,
        targetZoom: Float,
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        currentZoom: Float,
        currentPanX: Float,
        currentPanY: Float
    ): ZoomPanState {
        val oldZoom = clampZoom(currentZoom)
        val newZoom = clampZoom(targetZoom)
        val (ux, uy) = viewToImage(
            focusViewX, focusViewY,
            viewportW, viewportH,
            imageW, imageH,
            oldZoom, currentPanX, currentPanY
        )
        val newPanX = focusViewX - (viewportW - imageW * newZoom) * 0.5f - ux * newZoom
        val newPanY = focusViewY - (viewportH - imageH * newZoom) * 0.5f - uy * newZoom
        return ZoomPanState(newZoom, newPanX, newPanY)
    }

    fun pinchPanTransform(
        anchorImageX: Float,
        anchorImageY: Float,
        initialZoom: Float,
        initialDist: Float,
        currentDist: Float,
        midViewX: Float,
        midViewY: Float,
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float
    ): ZoomPanState {
        val safeD0 = max(1f, initialDist)
        val newZoom = clampZoom(initialZoom * (currentDist / safeD0))
        val newPanX = midViewX - (viewportW - imageW * newZoom) * 0.5f - anchorImageX * newZoom
        val newPanY = midViewY - (viewportH - imageH * newZoom) * 0.5f - anchorImageY * newZoom
        return ZoomPanState(newZoom, newPanX, newPanY)
    }

    fun pinchZoomOnlyTransform(
        focusViewX: Float,
        focusViewY: Float,
        initialZoom: Float,
        initialPanX: Float,
        initialPanY: Float,
        initialDist: Float,
        currentDist: Float,
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float
    ): ZoomPanState {
        val safeD0 = max(1f, initialDist)
        val targetZoom = clampZoom(initialZoom * (currentDist / safeD0))
        return zoomAnchoredAt(
            focusViewX = focusViewX,
            focusViewY = focusViewY,
            targetZoom = targetZoom,
            viewportW = viewportW,
            viewportH = viewportH,
            imageW = imageW,
            imageH = imageH,
            currentZoom = initialZoom,
            currentPanX = initialPanX,
            currentPanY = initialPanY
        )
    }

    fun maxPanBounds(
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        zoom: Float
    ): Pair<Float, Float> {
        val stageW = imageW * zoom
        val stageH = imageH * zoom
        return max(stageW * 0.5f, viewportW * 0.45f) to max(stageH * 0.5f, viewportH * 0.45f)
    }

    fun clampPan(
        panX: Float,
        panY: Float,
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
        zoom: Float
    ): Pair<Float, Float> {
        val (maxX, maxY) = maxPanBounds(viewportW, viewportH, imageW, imageH, zoom)
        return panX.coerceIn(-maxX, maxX) to panY.coerceIn(-maxY, maxY)
    }

    fun aspectRatioRect(imageW: Float, imageH: Float, aspectRatio: Float?): CropBounds {
        val wBound = imageW.coerceAtLeast(1f)
        val hBound = imageH.coerceAtLeast(1f)
        if (aspectRatio == null || aspectRatio <= 0f) {
            return CropBounds(0f, 0f, wBound, hBound)
        }
        var w = wBound
        var h = wBound / aspectRatio
        if (h > hBound) {
            h = hBound
            w = hBound * aspectRatio
        }
        val left = (wBound - w) * 0.5f
        val top = (hBound - h) * 0.5f
        return CropBounds(left, top, left + w, top + h)
    }

    fun hitCropHandle(
        bounds: CropBounds,
        x: Float,
        y: Float,
        hitRadius: Float
    ): CropHandle {
        val cx = bounds.centerX
        val cy = bounds.centerY
        val candidates = arrayOf(
            CropHandle.TL to (bounds.left to bounds.top),
            CropHandle.TR to (bounds.right to bounds.top),
            CropHandle.BL to (bounds.left to bounds.bottom),
            CropHandle.BR to (bounds.right to bounds.bottom),
            CropHandle.T to (cx to bounds.top),
            CropHandle.B to (cx to bounds.bottom),
            CropHandle.L to (bounds.left to cy),
            CropHandle.R to (bounds.right to cy)
        )
        var bestHandle = CropHandle.NONE
        var bestDist = hitRadius
        for ((handle, pt) in candidates) {
            val d = hypot(x - pt.first, y - pt.second)
            if (d <= bestDist) {
                bestDist = d
                bestHandle = handle
            }
        }
        if (bestHandle != CropHandle.NONE) return bestHandle
        if (x > bounds.left && x < bounds.right && y > bounds.top && y < bounds.bottom) {
            return CropHandle.MOVE
        }
        return CropHandle.NONE
    }

    fun resizeCrop(
        handle: CropHandle,
        dx: Float,
        dy: Float,
        start: CropBounds,
        imageW: Float,
        imageH: Float,
        aspectRatio: Float?,
        minSize: Float = MIN_CROP_SIZE
    ): CropBounds {
        val maxW = imageW.coerceAtLeast(1f)
        val maxH = imageH.coerceAtLeast(1f)
        if (handle == CropHandle.NONE) return start

        if (handle == CropHandle.MOVE) {
            val w = start.width.coerceIn(1f, maxW)
            val h = start.height.coerceIn(1f, maxH)
            val nx = (start.left + dx).coerceIn(0f, max(0f, maxW - w))
            val ny = (start.top + dy).coerceIn(0f, max(0f, maxH - h))
            return CropBounds(nx, ny, nx + w, ny + h)
        }

        val minW = min(minSize, maxW)
        val minH = min(minSize, maxH)
        var l = start.left.coerceIn(0f, maxW)
        var t = start.top.coerceIn(0f, maxH)
        var r = start.right.coerceIn(0f, maxW)
        var b = start.bottom.coerceIn(0f, maxH)

        val hasL = handle == CropHandle.TL || handle == CropHandle.BL || handle == CropHandle.L
        val hasR = handle == CropHandle.TR || handle == CropHandle.BR || handle == CropHandle.R
        val hasT = handle == CropHandle.TL || handle == CropHandle.TR || handle == CropHandle.T
        val hasB = handle == CropHandle.BL || handle == CropHandle.BR || handle == CropHandle.B

        if (hasL) l = (start.left + dx).coerceIn(0f, start.right - minW)
        if (hasR) r = (start.right + dx).coerceIn(start.left + minW, maxW)
        if (hasT) t = (start.top + dy).coerceIn(0f, start.bottom - minH)
        if (hasB) b = (start.bottom + dy).coerceIn(start.top + minH, maxH)

        if (aspectRatio == null || aspectRatio <= 0f) {
            return CropBounds(l, t, r, b)
        }

        val ar = aspectRatio
        val minArW = min(max(minW, minH * ar), maxW)
        val minArH = minArW / ar

        if (handle == CropHandle.T || handle == CropHandle.B) {
            var h = (b - t).coerceAtLeast(minArH)
            var w = h * ar
            if (w > maxW) {
                w = maxW
                h = w / ar
                if (handle == CropHandle.T) t = b - h else b = t + h
            }
            val nx = (start.centerX - w * 0.5f).coerceIn(0f, max(0f, maxW - w))
            return CropBounds(nx, t, nx + w, b)
        }

        var w = if (handle == CropHandle.L || handle == CropHandle.R) {
            r - l
        } else {
            if (abs(dx) >= abs(dy) * ar) (r - l) else (b - t) * ar
        }
        w = w.coerceIn(minArW, maxW)
        var h = w / ar
        if (h < minArH) {
            h = minArH
            w = h * ar
        }

        val growTop = hasT && !hasB
        val growLeft = hasL && !hasR

        if (growTop) {
            t = start.bottom - h
            if (t < 0f) {
                t = 0f
                h = start.bottom
                w = h * ar
            }
        } else {
            b = start.top + h
            if (b > maxH) {
                b = maxH
                h = maxH - start.top
                w = h * ar
            }
        }

        if (growLeft) {
            l = start.right - w
            if (l < 0f) {
                l = 0f
                w = start.right
                h = w / ar
                if (growTop) t = start.bottom - h else b = start.top + h
            }
        } else {
            r = start.left + w
            if (r > maxW) {
                r = maxW
                w = maxW - start.left
                h = w / ar
                if (growTop) t = start.bottom - h else b = start.top + h
            }
        }

        return CropBounds(
            left = l.coerceIn(0f, maxW),
            top = t.coerceIn(0f, maxH),
            right = r.coerceIn(0f, maxW),
            bottom = b.coerceIn(0f, maxH)
        )
    }
}
