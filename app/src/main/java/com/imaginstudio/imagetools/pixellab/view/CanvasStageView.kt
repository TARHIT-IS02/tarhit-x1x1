package com.imaginstudio.imagetools.pixellab.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.imaginstudio.imagetools.pixellab.engine.BrushEngine
import com.imaginstudio.imagetools.pixellab.engine.CropBounds
import com.imaginstudio.imagetools.pixellab.engine.CropHandle
import com.imaginstudio.imagetools.pixellab.engine.CropZoomMath
import com.imaginstudio.imagetools.pixellab.engine.CutoutEngine
import com.imaginstudio.imagetools.pixellab.engine.LayerTransformMath
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.engine.RetouchEngine
import com.imaginstudio.imagetools.pixellab.engine.WarpEngine
import com.imaginstudio.imagetools.pixellab.model.BezierAnchor
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.DrawPoint
import com.imaginstudio.imagetools.pixellab.model.DrawingLayer
import com.imaginstudio.imagetools.pixellab.model.DrawingStroke
import com.imaginstudio.imagetools.pixellab.model.GridState
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import com.imaginstudio.imagetools.pixellab.ui.PixelLoupeDrawer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CanvasStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class BrushConfig(
        var color: Int = Color.parseColor("#FF3B30"),
        var width: Float = 30f,
        var blur: Float = 0f,
        var dashed: Boolean = false,
        var neon: Boolean = false,
        var fill: Boolean = false,
        var eraser: Boolean = false,
        var presetId: String = "hard",
        var hardness: Int = 70,
        var opacity: Int = 100,
        var flow: Int = 100
    )

    enum class StageToolMode {
        NORMAL,
        DRAW,
        PERSPECTIVE,
        MASK,
        BEZIER,
        BITMAP_ERASER,
        EYEDROPPER,
        CROP,
        WARP,
        CUTOUT,
        RETOUCH
    }

    data class WarpCommitResult(
        val uriString: String,
        val newX: Float,
        val newY: Float,
        val newW: Float,
        val newH: Float
    )

    data class CutoutCommitResult(
        val uriString: String,
        val newX: Float,
        val newY: Float,
        val newW: Float,
        val newH: Float
    )

    enum class CropTarget {
        IMAGE_LAYER,
        CANVAS
    }

    var project: ProjectState = ProjectState(id = "init")
        set(value) {
            val dimsChanged = field.width != value.width || field.height != value.height
            field = value
            if (toolMode == StageToolMode.EYEDROPPER) {
                eyedropperBitmapDirty = true
            }
            if (dimsChanged && autoFit && width > 0 && height > 0) {
                fitToScreen()
            } else {
                invalidate()
            }
        }

    var grid: GridState = GridState()
        set(value) {
            field = value
            invalidate()
        }

    var isDarkTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                workspaceDotsPaintCache = null
                invalidate()
            }
        }

    var gridEditMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedGuideIndex = -1
            } else if (selectedGuideIndex < 0) {
                if (grid.hGuides.isNotEmpty()) {
                    selectedGuideIsHorizontal = true
                    selectedGuideIndex = 0
                } else if (grid.vGuides.isNotEmpty()) {
                    selectedGuideIsHorizontal = false
                    selectedGuideIndex = 0
                }
            }
            invalidate()
        }
    var selectedGuideIsHorizontal: Boolean = true
    var selectedGuideIndex: Int = -1
    var onGridChanged: ((GridState) -> Unit)? = null
    var onGuideSelected: ((Boolean, Int, Float) -> Unit)? = null

    var selectedLayerId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var zoomModeEnabled: Boolean = false
        set(value) {
            field = value
            notifyZoomChanged()
            invalidate()
        }

    var toolMode: StageToolMode = StageToolMode.NORMAL
        set(value) {
            field = value
            if (value != StageToolMode.DRAW) {
                activeDrawPoints.clear()
                drawLastPt = null
                brushIndicatorPt = null
                brushPinLabel = null
            }
            if (value != StageToolMode.EYEDROPPER) {
                eyedropperX = -1f
                eyedropperY = -1f
                eyedropperDragging = false
                eyedropperBitmapDirty = false
                eyedropperBitmap?.recycle()
                eyedropperBitmap = null
            }
            if (value != StageToolMode.CROP) {
                activeCropHandle = CropHandle.NONE
            }
            if (value != StageToolMode.WARP) {
                brushIndicatorPt = null
                brushPinLabel = null
                warpActiveVertex = -1
                warpLastBmpPt = null
            }
            if (value != StageToolMode.CUTOUT) {
                cutoutPolyPts.clear()
                cutoutDragStartBmp = null
                cutoutDragCurBmp = null
                cutoutLastBrushBmp = null
                brushIndicatorPt = null
                brushPinLabel = null
            }
            if (value != StageToolMode.RETOUCH) {
                retouchLastBmpPt = null
                brushIndicatorPt = null
                brushPinLabel = null
            }
            invalidate()
        }

    var bezierActionMode: Int = 0 // 0 = Add Point, 1 = Move/Drag Point
    var bitmapEraserWidth: Float = 28f

    var warpMode: WarpEngine.WarpMode = WarpEngine.WarpMode.BRUSH
    var warpSize: Float = 160f
    var warpStrength: Int = 50
    private var warpTargetLayerId: String? = null
    private var warpSession: WarpEngine.WarpSession? = null
    private var warpActiveVertex: Int = -1
    private var warpLastBmpPt: PointF? = null
    private var warpHasChanges: Boolean = false

    var cutoutMode: CutoutEngine.CutoutMode = CutoutEngine.CutoutMode.LASSO
    var cutoutOp: CutoutEngine.SelectionOp = CutoutEngine.SelectionOp.NEW
    var cutoutTolerance: Int = 36
    var cutoutBrushRadius: Float = 36f
    var cutoutFeather: Int = 0
    var cutoutExpand: Int = 0
    var cutoutSmooth: Int = 0
    var cutoutContrast: Int = 0
    var onCutoutStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onPolygonPointsChanged: ((Int) -> Unit)? = null
    private var cutoutTargetLayerId: String? = null
    private var cutoutSession: CutoutEngine.CutoutSession? = null
    private val cutoutPolyPts = mutableListOf<PointF>()
    private var cutoutDragStartBmp: PointF? = null
    private var cutoutDragCurBmp: PointF? = null
    private var cutoutLastBrushBmp: PointF? = null
    private var cutoutAntsPhase: Float = 0f

    var retouchMode: RetouchEngine.RetouchMode = RetouchEngine.RetouchMode.DODGE
    var retouchSize: Float = 60f
    var retouchStrength: Int = 55
    private var retouchTargetLayerId: String? = null
    private var retouchSession: RetouchEngine.RetouchSession? = null
    private var retouchLastBmpPt: PointF? = null

    val brushConfig = BrushConfig()
    private val pendingStrokes = mutableListOf<DrawingStroke>()
    private val activeDrawPoints = mutableListOf<DrawPoint>()
    private val activeEraserPoints = mutableListOf<DrawPoint>()
    private var liveDrawBitmap: Bitmap? = null
    private var liveDrawScale: Float = 1f
    private var liveDrawStampCounter: Int = 1
    private var currentStrokeSeed: Int = 1
    private var drawLastPt: DrawPoint? = null
    private var brushIndicatorPt: DrawPoint? = null
    private var brushPinLabel: String? = null

    private var activeGuideX: Float? = null
    private var activeGuideY: Float? = null
    private val smartGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#FF2D78")
    }

    private var activePerspectiveIndex = -1
    private var activeVertexIndex = -1
    private var eyedropperX = -1f
    private var eyedropperY = -1f
    private var eyedropperColor = Color.WHITE
    private var eyedropperDragging = false
    private var eyedropperBitmapDirty = false
    private var eyedropperBitmap: Bitmap? = null

    var onLayerSelected: ((String?) -> Unit)? = null
    var onBeforeTransform: (() -> Unit)? = null
    var onLayerTransform: ((CanvasLayer) -> Unit)? = null
    var onTransformEnd: (() -> Unit)? = null
    var onDoubleTapText: ((TextLayer) -> Unit)? = null
    var onDrawStrokeCountChanged: ((Int) -> Unit)? = null
    var onEyedropperColor: ((Int) -> Unit)? = null
    var onZoomChanged: ((Int, Boolean) -> Unit)? = null

    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set
    var autoFit: Boolean = true
        private set

    var cropTarget: CropTarget = CropTarget.CANVAS
        private set
    var cropRect: RectF = RectF(0f, 0f, 1280f, 1280f)
        private set
    var cropAspectRatio: Float? = null
        private set
    var straightenAngle: Float = 0f
        private set

    private var activeCropHandle = CropHandle.NONE
    private var cropDragStartX = 0f
    private var cropDragStartY = 0f
    private var cropRectStart = CropBounds(0f, 0f, 1280f, 1280f)

    private val activePointers = LinkedHashMap<Int, Pair<Float, Float>>()
    private var gestureActive = false
    private var gestureWaitingAllUp = false
    private var gestureInitialDist = 1f
    private var gestureInitialZoom = 1f
    private var gestureInitialPanX = 0f
    private var gestureInitialPanY = 0f
    private var gestureStartMidX = 0f
    private var gestureStartMidY = 0f
    private var gestureAnchorImgX = 0f
    private var gestureAnchorImgY = 0f

    private var baseScale = 1f
    private var stageLeft = 0f
    private var stageTop = 0f

    private enum class DragMode { NONE, MOVE, RESIZE_CORNER, RESIZE_WIDTH, RESIZE_HEIGHT, ROTATE, PAN, MOVE_GUIDE }

    private var dragMode = DragMode.NONE
    private var dragSnapshotSent = false
    private var touchStartProjX = 0f
    private var touchStartProjY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var origLayerX = 0f
    private var origLayerY = 0f
    private var origLayerW = 0f
    private var origLayerH = 0f
    private var origRot = 0f
    private var origFontSize = 0f
    private var origAspect = 1f
    private var origLocalX = 1f
    private var origLocalY = 1f
    private var origTouchAngleDeg = 0f
    private var origHandleEdgeOffsetX = 0f
    private var origHandleEdgeOffsetY = 0f
    private val tempArcRect = RectF()
    private val tempPath = Path()
    private val tempStageRect = RectF()
    private val tempStageRadii8 = FloatArray(8)
    private val tempPerspPts = FloatArray(8)
    private var eraserStrokeAdded = false

    private val checkerPaint: Paint by lazy {
        val tile = 32
        val half = tile / 2
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val light = Paint().apply { color = Color.parseColor("#FFFFFF") }
        val dark = Paint().apply { color = Color.parseColor("#D9D9D9") }
        c.drawRect(0f, 0f, half.toFloat(), half.toFloat(), light)
        c.drawRect(half.toFloat(), 0f, tile.toFloat(), half.toFloat(), dark)
        c.drawRect(0f, half.toFloat(), half.toFloat(), tile.toFloat(), dark)
        c.drawRect(half.toFloat(), half.toFloat(), tile.toFloat(), tile.toFloat(), light)
        Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private var workspaceDotsPaintCache: Paint? = null
    private fun getWorkspaceDotsPaint(): Paint {
        workspaceDotsPaintCache?.let { return it }
        val spacing = kotlin.math.round(22f * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        val dotRadius = (1.1f * resources.displayMetrics.density).coerceAtLeast(1.2f)
        val bmp = Bitmap.createBitmap(spacing, spacing, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (isDarkTheme) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkTheme) Color.argb(40, 255, 255, 255) else Color.argb(46, 127, 127, 127)
            style = Paint.Style.FILL
        }
        c.drawCircle(spacing * 0.5f, spacing * 0.5f, dotRadius, dotPaint)
        return Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }.also { workspaceDotsPaintCache = it }
    }
    private val loupeDrawer: PixelLoupeDrawer by lazy {
        PixelLoupeDrawer(resources.displayMetrics.density)
    }
    private val stageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#B4B4B4")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(190, 56, 189, 248)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val selShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        color = Color.argb(95, 0, 0, 0)
    }
    private val selStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = Color.WHITE
    }
    private val rotArcShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(130, 0, 0, 0)
    }
    private val rotArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val overlayWirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F59E0B")
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(130, 0, 0, 0)
    }
    private val handleWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#F59E0B")
    }

    private val cropMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88000000")
    }
    private val cropBorderShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(120, 0, 0, 0)
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(145, 255, 255, 255)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomModeEnabled) {
                resetZoomToFit()
                return true
            }
            if (toolMode != StageToolMode.NORMAL) return false
            val (px, py) = viewToProject(e.x, e.y)
            val hit = hitTestLayer(px, py)
            if (hit is TextLayer && !hit.locked) {
                onDoubleTapText?.invoke(hit)
                return true
            }
            return false
        }
    })

    private fun dpPx(dpVal: Float): Float = dpVal * context.resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && autoFit) {
            fitToScreen()
        }
    }

    private fun notifyZoomChanged() {
        val pct = (zoom * 100f).roundToIntCompat().coerceAtLeast(2)
        val fitScale = computeFitScale(width.toFloat(), height.toFloat())
        val visible = zoomModeEnabled || !autoFit || abs(zoom - fitScale) > 0.01f
        onZoomChanged?.invoke(pct, visible)
    }

    private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

    private fun computeFitScale(vw: Float, vh: Float): Float {
        return CropZoomMath.computeFitZoom(
            viewportW = vw,
            viewportH = vh,
            imageW = project.width.toFloat().coerceAtLeast(1f),
            imageH = project.height.toFloat().coerceAtLeast(1f)
        )
    }

    private fun clampPan() {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val (cx, cy) = CropZoomMath.clampPan(
            panX = panX,
            panY = panY,
            viewportW = vw,
            viewportH = vh,
            imageW = project.width.toFloat().coerceAtLeast(1f),
            imageH = project.height.toFloat().coerceAtLeast(1f),
            zoom = zoom
        )
        panX = cx
        panY = cy
    }

    fun fitToScreen() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) {
            autoFit = true
            return
        }
        zoom = computeFitScale(vw, vh)
        panX = 0f
        panY = 0f
        autoFit = true
        notifyZoomChanged()
        invalidate()
    }

    fun zoomAt(viewX: Float, viewY: Float, targetZoom: Float) {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val iw = project.width.toFloat().coerceAtLeast(1f)
        val ih = project.height.toFloat().coerceAtLeast(1f)
        val next = CropZoomMath.zoomAnchoredAt(
            focusViewX = viewX,
            focusViewY = viewY,
            targetZoom = targetZoom,
            viewportW = vw,
            viewportH = vh,
            imageW = iw,
            imageH = ih,
            currentZoom = zoom,
            currentPanX = panX,
            currentPanY = panY
        )
        zoom = next.zoom
        panX = next.panX
        panY = next.panY
        autoFit = false
        clampPan()
        notifyZoomChanged()
        invalidate()
    }

    fun zoomIn() {
        zoomAt(width * 0.5f, height * 0.5f, zoom * 1.25f)
    }

    fun zoomOut() {
        zoomAt(width * 0.5f, height * 0.5f, zoom / 1.25f)
    }

    fun zoomTo100() {
        zoomAt(width * 0.5f, height * 0.5f, 1f)
    }

    fun resetZoomToFit() {
        fitToScreen()
    }

    fun resetZoom() {
        fitToScreen()
    }

    fun getCropImageDimensions(): Pair<Float, Float> {
        return if (cropTarget == CropTarget.IMAGE_LAYER) {
            val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer
            if (sel != null) {
                sel.width.coerceAtLeast(1f) to sel.height.coerceAtLeast(1f)
            } else {
                project.width.toFloat().coerceAtLeast(1f) to project.height.toFloat().coerceAtLeast(1f)
            }
        } else {
            project.width.toFloat().coerceAtLeast(1f) to project.height.toFloat().coerceAtLeast(1f)
        }
    }

    fun initCrop(target: CropTarget) {
        cropTarget = target
        val (imgW, imgH) = getCropImageDimensions()
        cropRect = RectF(0f, 0f, imgW, imgH)
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        toolMode = StageToolMode.CROP
        invalidate()
    }

    fun setCropAspectRatio(ratio: Float?) {
        cropAspectRatio = ratio
        val (imgW, imgH) = getCropImageDimensions()
        val fitted = CropZoomMath.aspectRatioRect(imgW, imgH, ratio)
        cropRect.set(fitted.left, fitted.top, fitted.right, fitted.bottom)
        invalidate()
    }

    fun setStraightenAngle(deg: Float) {
        straightenAngle = deg.coerceIn(-45f, 45f)
        invalidate()
    }

    fun resetCrop() {
        val (imgW, imgH) = getCropImageDimensions()
        cropRect = RectF(0f, 0f, imgW, imgH)
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        invalidate()
    }

    fun clearCropState() {
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        if (toolMode == StageToolMode.CROP) {
            toolMode = StageToolMode.NORMAL
        }
        invalidate()
    }

    private fun ensureLiveDrawBitmap(): Bitmap {
        val pw = project.width.coerceAtLeast(1)
        val ph = project.height.coerceAtLeast(1)
        val s = min(1f, 1600f / max(pw, ph).toFloat())
        val bw = (pw * s).roundToInt().coerceIn(64, 1600)
        val bh = (ph * s).roundToInt().coerceIn(64, 1600)
        val cur = liveDrawBitmap
        if (cur != null && !cur.isRecycled && cur.width == bw && cur.height == bh) {
            liveDrawScale = bw.toFloat() / pw.toFloat()
            return cur
        }
        cur?.recycle()
        liveDrawScale = bw.toFloat() / pw.toFloat()
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { liveDrawBitmap = it }
    }

    private fun rebuildLiveDrawBitmap() {
        val bmp = ensureLiveDrawBitmap()
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.save()
        c.scale(liveDrawScale, liveDrawScale)
        for (st in pendingStrokes) {
            PixelLabRenderer.drawStroke(c, st, 1f)
        }
        c.restore()
    }

    fun undoPendingStroke() {
        if (pendingStrokes.isNotEmpty()) {
            pendingStrokes.removeAt(pendingStrokes.lastIndex)
            rebuildLiveDrawBitmap()
            onDrawStrokeCountChanged?.invoke(pendingStrokes.size)
            invalidate()
        }
    }

    fun clearPendingDrawing() {
        pendingStrokes.clear()
        activeDrawPoints.clear()
        drawLastPt = null
        brushIndicatorPt = null
        liveDrawBitmap?.recycle()
        liveDrawBitmap = null
        onDrawStrokeCountChanged?.invoke(0)
        invalidate()
    }

    fun finishDrawingLayer(): DrawingLayer? {
        if (pendingStrokes.isEmpty()) {
            clearPendingDrawing()
            return null
        }
        val layer = DrawingLayer(
            id = "draw_${System.currentTimeMillis()}",
            name = "Drawing",
            x = project.width / 2f,
            y = project.height / 2f,
            width = project.width.toFloat(),
            height = project.height.toFloat(),
            sourceWidth = project.width.toFloat(),
            sourceHeight = project.height.toFloat(),
            strokes = pendingStrokes.toList()
        )
        pendingStrokes.clear()
        activeDrawPoints.clear()
        drawLastPt = null
        brushIndicatorPt = null
        liveDrawBitmap?.recycle()
        liveDrawBitmap = null
        onDrawStrokeCountChanged?.invoke(0)
        invalidate()
        return layer
    }

    fun initWarp(layer: ImageLayer): Boolean {
        clearWarpState()
        val loaded = PixelLabRenderer.loadHighResBitmap(context, layer.uriString, 1200) ?: return false
        warpMode = WarpEngine.WarpMode.BRUSH
        warpTargetLayerId = layer.id
        warpSession = WarpEngine.createSession(
            src = loaded,
            layerW = layer.width,
            layerH = layer.height,
            canvasW = project.width.toFloat(),
            canvasH = project.height.toFloat()
        )
        warpHasChanges = false
        invalidate()
        return true
    }

    fun switchWarpMode(mode: WarpEngine.WarpMode) {
        val sess = warpSession ?: return
        if (warpMode == mode) return
        if (mode == WarpEngine.WarpMode.PAPER) {
            val (sub, rect) = WarpEngine.buildPaperSourceFromWork(sess)
            if (!sess.paperSrcBmp.isRecycled) sess.paperSrcBmp.recycle()
            sess.paperSrcBmp = sub
            sess.imgRectInBmp.set(rect)
            sess.gridPts.clear()
            sess.gridPts.addAll(WarpEngine.createGridForRect(rect))
        } else {
            sess.gridPts.clear()
            sess.gridPts.addAll(WarpEngine.createGridForRect(sess.imgRectInBmp))
        }
        warpMode = mode
        brushIndicatorPt = null
        brushPinLabel = null
        invalidate()
    }

    fun showBrushCenterPreview(diameterPx: Float, label: String? = "${diameterPx.roundToInt()} px") {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val (cx, cy) = viewToProject(vw * 0.5f, vh * 0.5f)
        brushIndicatorPt = DrawPoint(cx, cy)
        brushPinLabel = label
        invalidate()
    }

    fun hideBrushCenterPreview() {
        brushIndicatorPt = null
        brushPinLabel = null
        invalidate()
    }

    fun resetWarp() {
        val sess = warpSession ?: return
        val c = Canvas(sess.workBmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(sess.initialPaddedBmp, 0f, 0f, null)
        val loaded = warpTargetLayerId?.let { id ->
            project.layers.firstOrNull { it.id == id } as? ImageLayer
        }?.let { PixelLabRenderer.loadHighResBitmap(context, it.uriString, 1200) }
        if (loaded != null) {
            val imgW = (sess.origLayerW * (sess.workBmp.width / sess.paddedCanvasW)).roundToInt().coerceAtLeast(8)
            val imgH = (sess.origLayerH * (sess.workBmp.height / sess.paddedCanvasH)).roundToInt().coerceAtLeast(8)
            val left = (sess.workBmp.width - imgW) * 0.5f
            val top = (sess.workBmp.height - imgH) * 0.5f
            sess.imgRectInBmp.set(left, top, left + imgW, top + imgH)
            if (!sess.paperSrcBmp.isRecycled) sess.paperSrcBmp.recycle()
            sess.paperSrcBmp = Bitmap.createScaledBitmap(loaded, imgW, imgH, true)
        }
        sess.gridPts.clear()
        sess.gridPts.addAll(WarpEngine.createGridForRect(sess.imgRectInBmp))
        warpHasChanges = false
        invalidate()
    }

    fun commitWarp(): WarpCommitResult? {
        val sess = warpSession
        val targetId = warpTargetLayerId
        val layer = project.layers.firstOrNull { it.id == targetId } as? ImageLayer
        val changed = warpHasChanges
        val result = if (changed && sess != null && layer != null && !sess.workBmp.isRecycled) {
            val trimmed = WarpEngine.trimAndMeasureResult(sess, layer.flipH, layer.flipV, layer.rotation)
            val path = WarpEngine.saveWarpedBitmap(context, trimmed.trimmedBmp)
            if (!trimmed.trimmedBmp.isRecycled) trimmed.trimmedBmp.recycle()
            if (path != null) {
                WarpCommitResult(
                    uriString = path,
                    newX = layer.x + trimmed.centerOffsetCanvasX,
                    newY = layer.y + trimmed.centerOffsetCanvasY,
                    newW = trimmed.newCanvasW,
                    newH = trimmed.newCanvasH
                )
            } else null
        } else null
        clearWarpState()
        return result
    }

    fun clearWarpState() {
        warpSession?.let { s ->
            if (!s.initialPaddedBmp.isRecycled) s.initialPaddedBmp.recycle()
            if (!s.workBmp.isRecycled) s.workBmp.recycle()
            if (!s.paperSrcBmp.isRecycled) s.paperSrcBmp.recycle()
        }
        warpSession = null
        warpTargetLayerId = null
        warpActiveVertex = -1
        warpLastBmpPt = null
        warpHasChanges = false
        brushIndicatorPt = null
        brushPinLabel = null
        if (toolMode == StageToolMode.WARP) {
            toolMode = StageToolMode.NORMAL
        }
        invalidate()
    }

    // ---- Cutout Session API ----
    fun initCutout(layer: ImageLayer): Boolean {
        clearCutoutState()
        val loaded = PixelLabRenderer.loadHighResBitmap(context, layer.uriString, 1024) ?: return false
        cutoutTargetLayerId = layer.id
        cutoutSession = CutoutEngine.createSession(loaded)
        cutoutFeather = 0
        cutoutExpand = 0
        cutoutSmooth = 0
        cutoutContrast = 0
        cutoutPolyPts.clear()
        onPolygonPointsChanged?.invoke(0)
        onCutoutStateChanged?.invoke(false, false)
        invalidate()
        return true
    }

    fun hasCutoutSelection(): Boolean = cutoutSession?.hasMask == true
    fun hasCutoutImageChanges(): Boolean = cutoutSession?.hasImageChanges == true
    fun cutoutPolyPointCount(): Int = cutoutPolyPts.size

    private fun applyPendingCutoutModifiers(sess: CutoutEngine.CutoutSession) {
        if (cutoutFeather > 0 || cutoutExpand != 0 || cutoutSmooth > 0 || cutoutContrast > 0) {
            CutoutEngine.applyMaskModifiers(sess, cutoutFeather, cutoutExpand, cutoutSmooth, cutoutContrast)
        }
    }

    fun closePolygonCutoutSelection(): Boolean {
        val sess = cutoutSession ?: return false
        if (cutoutPolyPts.size < 3) return false
        val pts = cutoutPolyPts.toList()
        cutoutPolyPts.clear()
        onPolygonPointsChanged?.invoke(0)
        CutoutEngine.applyPathSelection(sess, cutoutOp) { c, p ->
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            path.close()
            c.drawPath(path, p)
        }
        applyPendingCutoutModifiers(sess)
        onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
        invalidate()
        return true
    }

    fun clearPolygonPoints() {
        if (cutoutPolyPts.isNotEmpty()) {
            cutoutPolyPts.clear()
            onPolygonPointsChanged?.invoke(0)
            invalidate()
        }
    }

    fun invertCutoutSelection() {
        val sess = cutoutSession ?: return
        CutoutEngine.invertSelection(sess)
        onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
        invalidate()
    }

    fun clearCutoutSelection() {
        val sess = cutoutSession ?: return
        cutoutPolyPts.clear()
        onPolygonPointsChanged?.invoke(0)
        sess.clearMask()
        onCutoutStateChanged?.invoke(false, sess.hasImageChanges)
        invalidate()
    }

    fun updateCutoutModifiers(
        featherPx: Int = cutoutFeather,
        expandPx: Int = cutoutExpand,
        smoothPx: Int = cutoutSmooth,
        contrastPct: Int = cutoutContrast
    ) {
        cutoutFeather = featherPx
        cutoutExpand = expandPx
        cutoutSmooth = smoothPx
        cutoutContrast = contrastPct
        val sess = cutoutSession ?: return
        if (sess.hasMask) {
            CutoutEngine.applyMaskModifiers(sess, featherPx, expandPx, smoothPx, contrastPct)
            onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
            invalidate()
        }
    }

    fun keepInsideCutout(): Boolean {
        val sess = cutoutSession ?: return false
        val ok = CutoutEngine.keepInsideSelection(sess)
        if (ok) {
            onCutoutStateChanged?.invoke(false, true)
            invalidate()
        }
        return ok
    }

    fun eraseInsideCutout(): Boolean {
        val sess = cutoutSession ?: return false
        val ok = CutoutEngine.eraseInsideSelection(sess)
        if (ok) {
            onCutoutStateChanged?.invoke(false, true)
            invalidate()
        }
        return ok
    }

    fun extractCutoutAsNewImageLayer(): ImageLayer? {
        val sess = cutoutSession ?: return null
        val layer = project.layers.firstOrNull { it.id == cutoutTargetLayerId } as? ImageLayer ?: return null
        val bmp = CutoutEngine.extractSelectionAsBitmap(sess) ?: return null
        val res = CutoutEngine.saveTrimmedBitmap(context, bmp, "cutout_extract")
        bmp.recycle()
        if (res == null) return null
        val (path, _) = res
        return ImageLayer(
            id = "cut_${System.currentTimeMillis()}",
            name = "${layer.name} Cutout",
            x = layer.x + 24f,
            y = layer.y + 24f,
            width = max(40f, layer.width * 0.85f),
            height = max(40f, layer.height * 0.85f),
            uriString = path
        )
    }

    fun cutToNewImageLayer(): ImageLayer? {
        val sess = cutoutSession ?: return null
        val extracted = extractCutoutAsNewImageLayer() ?: return null
        CutoutEngine.eraseInsideSelection(sess)
        onCutoutStateChanged?.invoke(false, true)
        invalidate()
        return extracted
    }

    fun resetCutout() {
        val sess = cutoutSession ?: return
        cutoutPolyPts.clear()
        onPolygonPointsChanged?.invoke(0)
        CutoutEngine.resetSession(sess)
        onCutoutStateChanged?.invoke(false, false)
        invalidate()
    }

    fun commitCutout(): CutoutCommitResult? {
        val sess = cutoutSession
        val layer = project.layers.firstOrNull { it.id == cutoutTargetLayerId } as? ImageLayer
        val res = if (sess != null && layer != null && sess.hasImageChanges) {
            val saved = CutoutEngine.saveTrimmedBitmap(context, sess.workBmp, "cutout")
            if (saved != null) {
                val (path, r) = saved
                val bw = sess.w.coerceAtLeast(1).toFloat()
                val bh = sess.h.coerceAtLeast(1).toFloat()
                val u = (r.left + r.width() * 0.5f) / bw
                val v = (r.top + r.height() * 0.5f) / bh
                val lx = ((if (layer.flipH) 1f - u else u) - 0.5f) * layer.width
                val ly = ((if (layer.flipV) 1f - v else v) - 0.5f) * layer.height
                val rad = layer.rotation * PI / 180.0
                val dx = (lx * cos(rad) - ly * sin(rad)).toFloat()
                val dy = (lx * sin(rad) + ly * cos(rad)).toFloat()
                CutoutCommitResult(
                    uriString = path,
                    newX = layer.x + dx,
                    newY = layer.y + dy,
                    newW = max(16f, (r.width() / bw) * layer.width),
                    newH = max(16f, (r.height() / bh) * layer.height)
                )
            } else null
        } else null
        clearCutoutState()
        return res
    }

    fun clearCutoutState() {
        cutoutSession?.recycle()
        cutoutSession = null
        cutoutTargetLayerId = null
        cutoutPolyPts.clear()
        onPolygonPointsChanged?.invoke(0)
        cutoutDragStartBmp = null
        cutoutDragCurBmp = null
        cutoutLastBrushBmp = null
        brushIndicatorPt = null
        brushPinLabel = null
        if (toolMode == StageToolMode.CUTOUT) {
            toolMode = StageToolMode.NORMAL
        }
        invalidate()
    }

    // ---- Mixed Grid Edit Helpers ----
    fun addGuide(horizontal: Boolean): GridState {
        val list = (if (horizontal) grid.hGuides else grid.vGuides).toMutableList()
        val nextPos = if (list.isEmpty()) 0.5f else {
            val sorted = list.sorted()
            var bestGap = sorted.first()
            var bestMid = sorted.first() * 0.5f
            for (i in 0 until sorted.size - 1) {
                val g = sorted[i + 1] - sorted[i]
                if (g > bestGap) {
                    bestGap = g
                    bestMid = (sorted[i] + sorted[i + 1]) * 0.5f
                }
            }
            val tailGap = 1f - sorted.last()
            if (tailGap > bestGap) {
                bestMid = (sorted.last() + 1f) * 0.5f
            }
            bestMid.coerceIn(0.05f, 0.95f)
        }
        list.add(nextPos)
        val updated = if (horizontal) {
            grid.copy(enabled = true, showGuides = true, hGuides = list)
        } else {
            grid.copy(enabled = true, showGuides = true, vGuides = list)
        }
        grid = updated
        selectedGuideIsHorizontal = horizontal
        selectedGuideIndex = list.lastIndex
        onGridChanged?.invoke(updated)
        onGuideSelected?.invoke(horizontal, selectedGuideIndex, nextPos)
        invalidate()
        return updated
    }

    fun deleteSelectedGuide(): GridState? {
        if (selectedGuideIndex < 0) return null
        val updated = if (selectedGuideIsHorizontal) {
            if (selectedGuideIndex !in grid.hGuides.indices) return null
            val next = grid.hGuides.toMutableList().apply { removeAt(selectedGuideIndex) }
            grid.copy(hGuides = next)
        } else {
            if (selectedGuideIndex !in grid.vGuides.indices) return null
            val next = grid.vGuides.toMutableList().apply { removeAt(selectedGuideIndex) }
            grid.copy(vGuides = next)
        }
        grid = updated
        if (updated.hGuides.isNotEmpty()) {
            selectedGuideIsHorizontal = true
            selectedGuideIndex = selectedGuideIndex.coerceIn(0, updated.hGuides.lastIndex)
        } else if (updated.vGuides.isNotEmpty()) {
            selectedGuideIsHorizontal = false
            selectedGuideIndex = selectedGuideIndex.coerceIn(0, updated.vGuides.lastIndex)
        } else {
            selectedGuideIndex = -1
        }
        onGridChanged?.invoke(updated)
        invalidate()
        return updated
    }

    fun getSelectedGuidePosition(): Float? {
        val idx = selectedGuideIndex
        return if (selectedGuideIsHorizontal) {
            grid.hGuides.getOrNull(idx)
        } else {
            grid.vGuides.getOrNull(idx)
        }
    }

    fun setSelectedGuidePosition(normPos: Float): GridState? {
        val norm = normPos.coerceIn(0.01f, 0.99f)
        val idx = selectedGuideIndex
        val updated = if (selectedGuideIsHorizontal) {
            if (idx !in grid.hGuides.indices) return null
            val next = grid.hGuides.toMutableList().apply { set(idx, norm) }
            grid.copy(hGuides = next)
        } else {
            if (idx !in grid.vGuides.indices) return null
            val next = grid.vGuides.toMutableList().apply { set(idx, norm) }
            grid.copy(vGuides = next)
        }
        grid = updated
        onGridChanged?.invoke(updated)
        invalidate()
        return updated
    }

    fun applyGuideCount(hCount: Int, vCount: Int): GridState {
        val hN = hCount.coerceIn(0, 12)
        val vN = vCount.coerceIn(0, 12)
        val hs = (1..hN).map { it.toFloat() / (hN + 1) }
        val vs = (1..vN).map { it.toFloat() / (vN + 1) }
        val updated = grid.copy(enabled = true, showGuides = true, hGuides = hs, vGuides = vs)
        grid = updated
        if (hs.isNotEmpty()) {
            selectedGuideIsHorizontal = true
            selectedGuideIndex = 0
        } else if (vs.isNotEmpty()) {
            selectedGuideIsHorizontal = false
            selectedGuideIndex = 0
        } else {
            selectedGuideIndex = -1
        }
        onGridChanged?.invoke(updated)
        invalidate()
        return updated
    }

    // ---- Retouch Session API ----
    fun initRetouch(layer: ImageLayer): Boolean {
        clearRetouchState()
        val loaded = PixelLabRenderer.loadHighResBitmap(context, layer.uriString, 1024) ?: return false
        retouchTargetLayerId = layer.id
        retouchSession = RetouchEngine.createSession(loaded)
        invalidate()
        return true
    }

    fun resetRetouch() {
        retouchSession?.reset()
        invalidate()
    }

    fun commitRetouch(): String? {
        val sess = retouchSession
        val saved = if (sess != null && sess.hasChanges) {
            RetouchEngine.saveRetouchBitmap(context, sess.workBmp)
        } else null
        clearRetouchState()
        return saved
    }

    fun clearRetouchState() {
        retouchSession?.recycle()
        retouchSession = null
        retouchTargetLayerId = null
        retouchLastBmpPt = null
        brushIndicatorPt = null
        brushPinLabel = null
        if (toolMode == StageToolMode.RETOUCH) {
            toolMode = StageToolMode.NORMAL
        }
        invalidate()
    }

    private fun updateStageMetrics() {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        baseScale = CropZoomMath.clampZoom(zoom)
        val stageW = project.width * baseScale
        val stageH = project.height * baseScale
        stageLeft = (vw - stageW) * 0.5f + panX
        stageTop = (vh - stageH) * 0.5f + panY
    }

    private fun viewToProject(vx: Float, vy: Float): Pair<Float, Float> {
        updateStageMetrics()
        val px = (vx - stageLeft) / baseScale
        val py = (vy - stageTop) / baseScale
        return Pair(px, py)
    }

    private fun projectToLayerNormalized(layer: CanvasLayer, px: Float, py: Float): DrawPoint {
        val dx = px - layer.x
        val dy = py - layer.y
        val rad = -layer.rotation * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val nx = (lx / layer.width.coerceAtLeast(1f)) + 0.5f
        val ny = (ly / layer.height.coerceAtLeast(1f)) + 0.5f
        return DrawPoint(nx, ny)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), getWorkspaceDotsPaint())
        updateStageMetrics()

        val stageW = project.width * baseScale
        val stageH = project.height * baseScale

        val pw = project.width.toFloat()
        val ph = project.height.toFloat()
        val hasBgCorners = PixelLabRenderer.hasBgCornerRadius(project.background, pw, ph)
        canvas.save()
        canvas.translate(stageLeft, stageTop)
        if (project.background.type == com.imaginstudio.imagetools.pixellab.model.BackgroundType.TRANSPARENT ||
            project.background.circular ||
            hasBgCorners
        ) {
            canvas.drawRect(0f, 0f, stageW, stageH, checkerPaint)
        }

        canvas.save()
        canvas.scale(baseScale, baseScale)

        val renderProjectState = if (
            toolMode == StageToolMode.CROP &&
            cropTarget == CropTarget.IMAGE_LAYER &&
            abs(straightenAngle) > 0.01f
        ) {
            val selId = selectedLayerId
            project.copy(layers = project.layers.map { l ->
                if (l is ImageLayer && l.id == selId) {
                    l.copy(rotation = l.rotation + straightenAngle)
                } else l
            })
        } else {
            project
        }

        val overrideBitmaps = mutableMapOf<String, Bitmap>()
        val overrideSizes = mutableMapOf<String, Pair<Float, Float>>()
        if (toolMode == StageToolMode.WARP && warpTargetLayerId != null) {
            warpSession?.let { sess ->
                overrideBitmaps[warpTargetLayerId!!] = sess.workBmp
                overrideSizes[warpTargetLayerId!!] = sess.paddedCanvasW to sess.paddedCanvasH
            }
        } else if (toolMode == StageToolMode.CUTOUT && cutoutTargetLayerId != null) {
            cutoutSession?.let { sess ->
                overrideBitmaps[cutoutTargetLayerId!!] = sess.workBmp
            }
        } else if (toolMode == StageToolMode.RETOUCH && retouchTargetLayerId != null) {
            retouchSession?.let { sess ->
                overrideBitmaps[retouchTargetLayerId!!] = sess.workBmp
            }
        }

        canvas.save()
        if (toolMode == StageToolMode.CROP && cropTarget == CropTarget.CANVAS && abs(straightenAngle) > 0.01f) {
            canvas.rotate(straightenAngle, pw * 0.5f, ph * 0.5f)
        }
        PixelLabRenderer.renderProject(
            context = context,
            canvas = canvas,
            project = renderProjectState,
            skipTransparentBg = false,
            clipToArtboard = false,
            overrideImageBitmaps = overrideBitmaps,
            overrideImageSizes = overrideSizes
        )
        canvas.restore()

        if (toolMode == StageToolMode.DRAW) {
            liveDrawBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                tempStageRect.set(0f, 0f, pw, ph)
                canvas.drawBitmap(bmp, null, tempStageRect, null)
            }
        }

        val invScale = (1f / baseScale).coerceAtLeast(0.5f)

        if (grid.enabled) {
            drawMixedGridOverlay(canvas, pw, ph, invScale)
        }

        if (activeGuideX != null || activeGuideY != null) {
            smartGuidePaint.strokeWidth = 1.5f * invScale
            smartGuidePaint.pathEffect = DashPathEffect(floatArrayOf(10f * invScale, 8f * invScale), 0f)
            activeGuideX?.let { gx -> canvas.drawLine(gx, 0f, gx, ph, smartGuidePaint) }
            activeGuideY?.let { gy -> canvas.drawLine(0f, gy, pw, gy, smartGuidePaint) }
        }

        val selected = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden }
        if (toolMode == StageToolMode.CROP) {
            if (cropTarget == CropTarget.CANVAS) {
                drawCropOverlay(canvas, project.width.toFloat(), project.height.toFloat(), invScale)
            } else if (selected is ImageLayer) {
                canvas.save()
                canvas.translate(selected.x - selected.width * 0.5f, selected.y - selected.height * 0.5f)
                if (selected.rotation != 0f) {
                    canvas.rotate(selected.rotation, selected.width * 0.5f, selected.height * 0.5f)
                }
                drawCropOverlay(canvas, selected.width, selected.height, invScale)
                canvas.restore()
            }
        } else if (toolMode == StageToolMode.WARP && selected is ImageLayer) {
            drawWarpOverlay(canvas, selected, invScale)
        } else if (toolMode == StageToolMode.CUTOUT && selected is ImageLayer) {
            drawCutoutOverlay(canvas, selected, invScale)
        } else if (selected != null) {
            when (toolMode) {
                StageToolMode.NORMAL -> drawSelectionOverlay(canvas, selected, invScale)
                StageToolMode.PERSPECTIVE -> drawPerspectiveOverlay(canvas, selected, invScale)
                StageToolMode.MASK -> drawMaskOverlay(canvas, selected, invScale)
                StageToolMode.BEZIER -> if (selected is BezierLayer) drawBezierOverlay(canvas, selected, invScale)
                StageToolMode.BITMAP_ERASER -> drawSelectionOverlay(canvas, selected, invScale)
                else -> Unit
            }
        }

        val showBrushCircle = (
            toolMode == StageToolMode.DRAW ||
            (toolMode == StageToolMode.WARP && warpMode == WarpEngine.WarpMode.BRUSH) ||
            toolMode == StageToolMode.RETOUCH ||
            (toolMode == StageToolMode.CUTOUT && cutoutMode == CutoutEngine.CutoutMode.QUICK_SELECT)
        )
        if (showBrushCircle && brushIndicatorPt != null) {
            drawBrushIndicatorOverlay(canvas, brushIndicatorPt!!, invScale)
        }

        canvas.restore()
        canvas.drawRect(0f, 0f, stageW, stageH, stageBorderPaint)
        if (project.background.circular || hasBgCorners) {
            canvas.save()
            canvas.scale(baseScale, baseScale)
            if (PixelLabRenderer.buildArtboardClipPath(project.background, pw, ph, tempPath, tempStageRect, tempStageRadii8)) {
                val prevW = stageBorderPaint.strokeWidth
                stageBorderPaint.strokeWidth = 1.5f * invScale
                canvas.drawPath(tempPath, stageBorderPaint)
                stageBorderPaint.strokeWidth = prevW
            }
            canvas.restore()
        }
        canvas.restore()

        if (toolMode == StageToolMode.EYEDROPPER && eyedropperX >= 0f && eyedropperY >= 0f) {
            val vx = stageLeft + eyedropperX * baseScale
            val vy = stageTop + eyedropperY * baseScale
            val snap = eyedropperBitmap
            if (snap != null && !snap.isRecycled) {
                val ix = ((eyedropperX / pw.coerceAtLeast(1f)) * (snap.width - 1)).roundToInt().coerceIn(0, snap.width - 1)
                val iy = ((eyedropperY / ph.coerceAtLeast(1f)) * (snap.height - 1)).roundToInt().coerceIn(0, snap.height - 1)
                loupeDrawer.drawLoupeWithCrosshair(
                    canvas = canvas,
                    viewWidth = width.toFloat(),
                    viewHeight = height.toFloat(),
                    bitmap = snap,
                    sampleBmpX = ix,
                    sampleBmpY = iy,
                    touchX = vx,
                    touchY = vy,
                    sampledColor = eyedropperColor,
                    showLoupe = eyedropperDragging,
                    flipThresholdTopPx = 56f * resources.displayMetrics.density
                )
            } else {
                loupeDrawer.drawCrosshairOnly(canvas, vx, vy, eyedropperColor)
            }
        }
    }

    private fun drawCropOverlay(canvas: Canvas, imgW: Float, imgH: Float, invScale: Float) {
        val r = cropRect
        tempPath.reset()
        tempPath.fillType = Path.FillType.EVEN_ODD
        tempPath.addRect(0f, 0f, imgW, imgH, Path.Direction.CW)
        tempPath.addRect(r, Path.Direction.CW)
        canvas.drawPath(tempPath, cropMaskPaint)
        tempPath.fillType = Path.FillType.WINDING

        cropBorderShadowPaint.strokeWidth = 3.5f * invScale
        cropBorderPaint.strokeWidth = 2f * invScale
        cropGridPaint.strokeWidth = 1f * invScale
        handleBorderPaint.strokeWidth = 1.8f * invScale

        canvas.drawRect(r, cropBorderShadowPaint)
        canvas.drawRect(r, cropBorderPaint)

        val rw = r.width()
        val rh = r.height()
        for (i in 1..2) {
            val gx = r.left + rw * i / 3f
            val gy = r.top + rh * i / 3f
            canvas.drawLine(gx, r.top, gx, r.bottom, cropGridPaint)
            canvas.drawLine(r.left, gy, r.right, gy, cropGridPaint)
        }

        val cx = r.centerX()
        val cy = r.centerY()
        val hr = 11f * invScale
        val handleCoords = floatArrayOf(
            r.left, r.top,
            r.right, r.top,
            r.left, r.bottom,
            r.right, r.bottom,
            cx, r.top,
            cx, r.bottom,
            r.left, cy,
            r.right, cy
        )
        var idx = 0
        while (idx < handleCoords.size) {
            val hx = handleCoords[idx]
            val hy = handleCoords[idx + 1]
            canvas.drawCircle(hx, hy, hr, handleFillPaint)
            canvas.drawCircle(hx, hy, hr, handleBorderPaint)
            idx += 2
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        val hw = layer.width / 2f
        val hh = layer.height / 2f

        selShadowPaint.strokeWidth = 3.2f * invScale
        selStrokePaint.strokeWidth = 1.8f * invScale
        handleBorderPaint.strokeWidth = 1.8f * invScale
        rotArcShadowPaint.strokeWidth = 5.4f * invScale
        rotArcPaint.strokeWidth = 3.0f * invScale

        canvas.drawRect(-hw, -hh, hw, hh, selShadowPaint)
        canvas.drawRect(-hw, -hh, hw, hh, selStrokePaint)

        if (layer is TextLayer) {
            // PixelLab Text selection: bottom-left scale dot, right-center wrap dot, bottom-center smile rotation arc
            val dotR = 11f * invScale
            canvas.drawCircle(-hw, hh, dotR, handleFillPaint)
            canvas.drawCircle(-hw, hh, dotR, handleBorderPaint)

            canvas.drawCircle(hw, 0f, dotR, handleFillPaint)
            canvas.drawCircle(hw, 0f, dotR, handleBorderPaint)

            val arcR = 16f * invScale
            tempArcRect.set(-arcR, hh + 6f * invScale, arcR, hh + 6f * invScale + arcR * 1.4f)
            canvas.drawArc(tempArcRect, 20f, 140f, false, rotArcShadowPaint)
            canvas.drawArc(tempArcRect, 20f, 140f, false, rotArcPaint)
        } else {
            // PixelLab Shape/Image selection: bottom-right main corner dot, right/bottom edge dots, bottom-left corner rotation arc
            val cornerR = 14f * invScale
            val edgeR = 7.5f * invScale

            canvas.drawCircle(hw, hh, cornerR, handleFillPaint)
            canvas.drawCircle(hw, hh, cornerR, handleBorderPaint)

            canvas.drawCircle(hw, 0f, edgeR, handleFillPaint)
            canvas.drawCircle(hw, 0f, edgeR, handleBorderPaint)

            canvas.drawCircle(0f, hh, edgeR, handleFillPaint)
            canvas.drawCircle(0f, hh, edgeR, handleBorderPaint)

            val arcR = 20f * invScale
            val cx = -hw + 8f * invScale
            val cy = hh - 8f * invScale
            tempArcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            canvas.drawArc(tempArcRect, 90f, 90f, false, rotArcShadowPaint)
            canvas.drawArc(tempArcRect, 90f, 90f, false, rotArcPaint)
        }
        canvas.restore()
    }

    private fun drawPerspectiveOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        val pts = when (layer) {
            is TextLayer -> layer.perspectivePoints
            is ShapeLayer -> layer.perspectivePoints
            is ImageLayer -> layer.perspectivePoints
            else -> return
        }
        if (pts.size != 4) return
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 3f * invScale
        handleWarnPaint.strokeWidth = 3.5f * invScale

        for (i in 0..3) {
            tempPerspPts[i * 2] = (pts[i].x - 0.5f) * layer.width
            tempPerspPts[i * 2 + 1] = (pts[i].y - 0.5f) * layer.height
        }
        tempPath.reset()
        tempPath.moveTo(tempPerspPts[0], tempPerspPts[1])
        for (i in 1..3) tempPath.lineTo(tempPerspPts[i * 2], tempPerspPts[i * 2 + 1])
        tempPath.close()
        canvas.drawPath(tempPath, overlayWirePaint)

        val hr = 16f * invScale
        for (i in 0..3) {
            val cx = tempPerspPts[i * 2]
            val cy = tempPerspPts[i * 2 + 1]
            canvas.drawCircle(cx, cy, hr, handleFillPaint)
            canvas.drawCircle(cx, cy, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    private fun drawMaskOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        val pts = when (layer) {
            is TextLayer -> layer.maskPoints
            is ShapeLayer -> layer.maskPoints
            is ImageLayer -> layer.maskPoints
            else -> return
        }
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 3f * invScale
        handleWarnPaint.strokeWidth = 3f * invScale
        if (pts.size >= 2) {
            tempPath.reset()
            tempPath.moveTo((pts[0].x - 0.5f) * layer.width, (pts[0].y - 0.5f) * layer.height)
            for (i in 1 until pts.size) {
                tempPath.lineTo((pts[i].x - 0.5f) * layer.width, (pts[i].y - 0.5f) * layer.height)
            }
            if (pts.size >= 3) tempPath.close()
            canvas.drawPath(tempPath, overlayWirePaint)
        }
        val hr = 14f * invScale
        for (pt in pts) {
            val lx = (pt.x - 0.5f) * layer.width
            val ly = (pt.y - 0.5f) * layer.height
            canvas.drawCircle(lx, ly, hr, handleFillPaint)
            canvas.drawCircle(lx, ly, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    private fun drawBezierOverlay(canvas: Canvas, layer: BezierLayer, invScale: Float) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 2.5f * invScale
        handleWarnPaint.strokeWidth = 3f * invScale
        val hr = 14f * invScale
        for (anc in layer.anchors) {
            val ax = (anc.x - 0.5f) * layer.width
            val ay = (anc.y - 0.5f) * layer.height
            val ix = (anc.ctrlInX - 0.5f) * layer.width
            val iy = (anc.ctrlInY - 0.5f) * layer.height
            val ox = (anc.ctrlOutX - 0.5f) * layer.width
            val oy = (anc.ctrlOutY - 0.5f) * layer.height
            canvas.drawLine(ix, iy, ox, oy, overlayWirePaint)
            canvas.drawCircle(ix, iy, hr * 0.65f, handleFillPaint)
            canvas.drawCircle(ox, oy, hr * 0.65f, handleFillPaint)
            canvas.drawCircle(ax, ay, hr, handleFillPaint)
            canvas.drawCircle(ax, ay, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawMixedGridOverlay(canvas: Canvas, pw: Float, ph: Float, invScale: Float) {
        val baseAlpha = ((grid.opacity.coerceIn(5, 100) / 100f) * 255f).roundToInt().coerceIn(8, 255)
        val rC = Color.red(grid.color)
        val gC = Color.green(grid.color)
        val bC = Color.blue(grid.color)

        canvas.save()
        canvas.clipRect(0f, 0f, pw, ph)
        if (grid.showPixelGrid) {
            var step = grid.size.coerceIn(8, 400).toFloat()
            while (step * baseScale < 8f) step *= 2f
            val subAlpha = if (grid.showGuides) (baseAlpha * 0.5f).roundToInt().coerceIn(8, 255) else baseAlpha
            gridPaint.color = Color.argb(subAlpha, rC, gC, bC)
            gridPaint.pathEffect = null
            gridPaint.strokeWidth = 1f * invScale
            var gx = step
            while (gx < pw) {
                canvas.drawLine(gx, 0f, gx, ph, gridPaint)
                gx += step
            }
            var gy = step
            while (gy < ph) {
                canvas.drawLine(0f, gy, pw, gy, gridPaint)
                gy += step
            }
        }

        if (grid.showGuides) {
            val activeGuideColor = Color.parseColor("#F59E0B")
            for (i in grid.vGuides.indices) {
                val gx = grid.vGuides[i] * pw
                val isSel = gridEditMode && !selectedGuideIsHorizontal && selectedGuideIndex == i
                gridPaint.color = if (isSel) activeGuideColor else Color.argb(baseAlpha, rC, gC, bC)
                gridPaint.pathEffect = null
                gridPaint.strokeWidth = (if (isSel) 2.2f else 1.25f) * invScale
                canvas.drawLine(gx, 0f, gx, ph, gridPaint)
            }
            for (i in grid.hGuides.indices) {
                val gy = grid.hGuides[i] * ph
                val isSel = gridEditMode && selectedGuideIsHorizontal && selectedGuideIndex == i
                gridPaint.color = if (isSel) activeGuideColor else Color.argb(baseAlpha, rC, gC, bC)
                gridPaint.pathEffect = null
                gridPaint.strokeWidth = (if (isSel) 2.2f else 1.25f) * invScale
                canvas.drawLine(0f, gy, pw, gy, gridPaint)
            }
        }
        canvas.restore()

        // PixelLab circular drag handles along the canvas perimeter when gridEditMode is ON
        if (gridEditMode && grid.showGuides) {
            val hr = 9f * invScale
            val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * invScale
                color = Color.parseColor("#F59E0B")
            }
            handleBorderPaint.strokeWidth = 1.6f * invScale
            for (i in grid.vGuides.indices) {
                val gx = grid.vGuides[i] * pw
                val isSel = !selectedGuideIsHorizontal && selectedGuideIndex == i
                val strokeP = if (isSel) selStroke else handleBorderPaint
                canvas.drawCircle(gx, 0f, hr, handleFillPaint)
                canvas.drawCircle(gx, 0f, hr, strokeP)
                canvas.drawCircle(gx, ph, hr, handleFillPaint)
                canvas.drawCircle(gx, ph, hr, strokeP)
            }
            for (i in grid.hGuides.indices) {
                val gy = grid.hGuides[i] * ph
                val isSel = selectedGuideIsHorizontal && selectedGuideIndex == i
                val strokeP = if (isSel) selStroke else handleBorderPaint
                canvas.drawCircle(0f, gy, hr, handleFillPaint)
                canvas.drawCircle(0f, gy, hr, strokeP)
                canvas.drawCircle(pw, gy, hr, handleFillPaint)
                canvas.drawCircle(pw, gy, hr, strokeP)
            }
        }
    }

    private fun drawBrushIndicatorOverlay(canvas: Canvas, pt: DrawPoint, invScale: Float) {
        val diameter = when (toolMode) {
            StageToolMode.WARP -> warpSize
            StageToolMode.RETOUCH -> retouchSize
            StageToolMode.CUTOUT -> cutoutBrushRadius * 2f
            else -> brushConfig.width
        }
        val r = max(3f, diameter * 0.5f)
        val glowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                pt.x, pt.y, r,
                intArrayOf(
                    Color.argb(110, 47, 107, 255),
                    Color.argb(42, 47, 107, 255),
                    Color.argb(12, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val darkRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * invScale
            color = Color.argb(170, 0, 0, 0)
        }
        val lightRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * invScale
            color = Color.argb(240, 255, 255, 255)
        }
        canvas.drawCircle(pt.x, pt.y, r, glowFill)
        canvas.drawCircle(pt.x, pt.y, r + 0.8f * invScale, darkRing)
        canvas.drawCircle(pt.x, pt.y, r, lightRing)

        // Crisp center dot matching HTML Warp brush
        val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.3f * invScale
            color = Color.argb(200, 0, 0, 0)
        }
        canvas.drawCircle(pt.x, pt.y, 2.5f * invScale, dotFill)
        canvas.drawCircle(pt.x, pt.y, 2.5f * invScale, dotStroke)

        val label = brushPinLabel
        if (!label.isNullOrEmpty()) {
            val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f * invScale * resources.displayMetrics.density
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val tw = txtPaint.measureText(label) + 14f * invScale * resources.displayMetrics.density
            val th = 22f * invScale * resources.displayMetrics.density
            val ty = pt.y + r + 10f * invScale * resources.displayMetrics.density
            val boxRect = RectF(pt.x - tw * 0.5f, ty, pt.x + tw * 0.5f, ty + th)
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(185, 18, 18, 22)
            }
            canvas.drawRoundRect(boxRect, 6f * invScale * resources.displayMetrics.density, 6f * invScale * resources.displayMetrics.density, boxPaint)
            canvas.drawText(label, pt.x, ty + th * 0.72f, txtPaint)
        }
    }

    private fun expandedWarpBmpToCanvas(layer: ImageLayer, session: WarpEngine.WarpSession, p: PointF): Pair<Float, Float> {
        val bw = session.workBmp.width.coerceAtLeast(1).toFloat()
        val bh = session.workBmp.height.coerceAtLeast(1).toFloat()
        val u = p.x / bw
        val v = p.y / bh
        val lx = ((if (layer.flipH) 1f - u else u) - 0.5f) * session.paddedCanvasW
        val ly = ((if (layer.flipV) 1f - v else v) - 0.5f) * session.paddedCanvasH
        val rad = layer.rotation * PI / 180.0
        val cx = layer.x + (lx * cos(rad) - ly * sin(rad)).toFloat()
        val cy = layer.y + (lx * sin(rad) + ly * cos(rad)).toFloat()
        return cx to cy
    }

    private fun drawWarpOverlay(canvas: Canvas, layer: ImageLayer, invScale: Float) {
        val sess = warpSession ?: return
        if (warpMode != WarpEngine.WarpMode.PAPER || sess.gridPts.size != WarpEngine.GRID_N * WarpEngine.GRID_N) return
        val pts = sess.gridPts

        val meshLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * invScale
            color = Color.parseColor("#2F6BFF")
        }
        val n = WarpEngine.GRID_N
        for (r in 0 until n) {
            tempPath.reset()
            for (c in 0 until n) {
                val (cx, cy) = expandedWarpBmpToCanvas(layer, sess, pts[r * n + c])
                if (c == 0) tempPath.moveTo(cx, cy) else tempPath.lineTo(cx, cy)
            }
            canvas.drawPath(tempPath, meshLinePaint)
        }
        for (c in 0 until n) {
            tempPath.reset()
            for (r in 0 until n) {
                val (cx, cy) = expandedWarpBmpToCanvas(layer, sess, pts[r * n + c])
                if (r == 0) tempPath.moveTo(cx, cy) else tempPath.lineTo(cx, cy)
            }
            canvas.drawPath(tempPath, meshLinePaint)
        }
        val hr = 10f * invScale
        for (p in pts) {
            val (cx, cy) = expandedWarpBmpToCanvas(layer, sess, p)
            canvas.drawCircle(cx, cy, hr, handleFillPaint)
            canvas.drawCircle(cx, cy, hr, meshLinePaint)
        }
    }

    private fun layerBmpToCanvas(layer: ImageLayer, bw: Float, bh: Float, bx: Float, by: Float): Pair<Float, Float> {
        val u = bx / bw.coerceAtLeast(1f)
        val v = by / bh.coerceAtLeast(1f)
        val lx = ((if (layer.flipH) 1f - u else u) - 0.5f) * layer.width
        val ly = ((if (layer.flipV) 1f - v else v) - 0.5f) * layer.height
        val rad = layer.rotation * PI / 180.0
        val cx = layer.x + (lx * cos(rad) - ly * sin(rad)).toFloat()
        val cy = layer.y + (lx * sin(rad) + ly * cos(rad)).toFloat()
        return cx to cy
    }

    private fun drawCutoutOverlay(canvas: Canvas, layer: ImageLayer, invScale: Float) {
        val sess = cutoutSession ?: return
        if (sess.hasMask && !sess.overlayBmp.isRecycled) {
            canvas.save()
            canvas.translate(layer.x, layer.y)
            if (layer.rotation != 0f) canvas.rotate(layer.rotation)
            canvas.scale(if (layer.flipH) -1f else 1f, if (layer.flipV) -1f else 1f)
            tempStageRect.set(-layer.width * 0.5f, -layer.height * 0.5f, layer.width * 0.5f, layer.height * 0.5f)
            canvas.drawBitmap(sess.overlayBmp, null, tempStageRect, null)
            canvas.restore()
        }

        val bw = sess.w.toFloat()
        val bh = sess.h.toFloat()
        cutoutAntsPhase = (cutoutAntsPhase + 2f * invScale) % (14f * invScale)
        val darkUnderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * invScale
            color = Color.argb(190, 0, 0, 0)
        }
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * invScale
            color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(8f * invScale, 6f * invScale), cutoutAntsPhase)
        }
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(45, 47, 107, 255)
        }

        if (cutoutPolyPts.isNotEmpty()) {
            tempPath.reset()
            cutoutPolyPts.forEachIndexed { idx, pt ->
                val (cx, cy) = layerBmpToCanvas(layer, bw, bh, pt.x, pt.y)
                if (idx == 0) tempPath.moveTo(cx, cy) else tempPath.lineTo(cx, cy)
            }
            canvas.drawPath(tempPath, darkUnderP)
            canvas.drawPath(tempPath, lineP)
            if (cutoutMode == CutoutEngine.CutoutMode.POLYGON) {
                val p0 = layerBmpToCanvas(layer, bw, bh, cutoutPolyPts[0].x, cutoutPolyPts[0].y)
                canvas.drawCircle(p0.first, p0.second, 9f * invScale, handleFillPaint)
                canvas.drawCircle(p0.first, p0.second, 9f * invScale, lineP)
                for (i in 1 until cutoutPolyPts.size) {
                    val pi = layerBmpToCanvas(layer, bw, bh, cutoutPolyPts[i].x, cutoutPolyPts[i].y)
                    canvas.drawCircle(pi.first, pi.second, 5f * invScale, handleFillPaint)
                }
            }
        }

        val d0 = cutoutDragStartBmp
        val d1 = cutoutDragCurBmp
        if (d0 != null && d1 != null) {
            val minX = min(d0.x, d1.x)
            val minY = min(d0.y, d1.y)
            val maxX = max(d0.x, d1.x)
            val maxY = max(d0.y, d1.y)
            val c0 = layerBmpToCanvas(layer, bw, bh, minX, minY)
            val c1 = layerBmpToCanvas(layer, bw, bh, maxX, maxY)
            val rect = RectF(min(c0.first, c1.first), min(c0.second, c1.second), max(c0.first, c1.first), max(c0.second, c1.second))
            if (cutoutMode == CutoutEngine.CutoutMode.RECT) {
                canvas.drawRect(rect, fillP)
                canvas.drawRect(rect, darkUnderP)
                canvas.drawRect(rect, lineP)
            } else if (cutoutMode == CutoutEngine.CutoutMode.ELLIPSE) {
                canvas.drawOval(rect, fillP)
                canvas.drawOval(rect, darkUnderP)
                canvas.drawOval(rect, lineP)
            }
        }
    }

    private fun cancelSingleFingerInteractions() {
        dragMode = DragMode.NONE
        dragSnapshotSent = false
        activeGuideX = null
        activeGuideY = null
        activeCropHandle = CropHandle.NONE
        activePerspectiveIndex = -1
        activeVertexIndex = -1
        warpActiveVertex = -1
        warpLastBmpPt = null
        brushIndicatorPt = null
        activeDrawPoints.clear()
        activeEraserPoints.clear()
        eraserStrokeAdded = false
        eyedropperDragging = false
    }

    private fun syncActivePointers(event: MotionEvent) {
        val count = event.pointerCount
        for (i in 0 until count) {
            val pid = event.getPointerId(i)
            activePointers[pid] = event.getX(i) to event.getY(i)
        }
        val iter = activePointers.keys.iterator()
        while (iter.hasNext()) {
            val pid = iter.next()
            var stillPresent = false
            for (i in 0 until count) {
                if (event.getPointerId(i) == pid) {
                    stillPresent = true
                    break
                }
            }
            if (!stillPresent) iter.remove()
        }
    }

    private fun firstTwoPointers(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val iter = activePointers.values.iterator()
        if (!iter.hasNext()) return null
        val a = iter.next()
        if (!iter.hasNext()) return null
        val b = iter.next()
        return a to b
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointers.clear()
                activePointers[event.getPointerId(0)] = event.x to event.y
                gestureActive = false
                gestureWaitingAllUp = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                syncActivePointers(event)
                if (activePointers.size >= 2 && !gestureActive) {
                    cancelSingleFingerInteractions()
                    val pair = firstTwoPointers() ?: return true
                    val a = pair.first
                    val b = pair.second
                    gestureInitialDist = max(1f, hypot(a.first - b.first, a.second - b.second))
                    gestureInitialZoom = zoom
                    gestureInitialPanX = panX
                    gestureInitialPanY = panY
                    val midX = (a.first + b.first) * 0.5f
                    val midY = (a.second + b.second) * 0.5f
                    gestureStartMidX = midX
                    gestureStartMidY = midY
                    val (ux, uy) = CropZoomMath.viewToImage(
                        midX, midY,
                        width.toFloat().coerceAtLeast(1f),
                        height.toFloat().coerceAtLeast(1f),
                        project.width.toFloat().coerceAtLeast(1f),
                        project.height.toFloat().coerceAtLeast(1f),
                        zoom, panX, panY
                    )
                    gestureAnchorImgX = ux
                    gestureAnchorImgY = uy
                    gestureActive = true
                    gestureWaitingAllUp = false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                syncActivePointers(event)
                if (gestureActive && activePointers.size >= 2) {
                    val pair = firstTwoPointers() ?: return true
                    val a = pair.first
                    val b = pair.second
                    val dist = hypot(a.first - b.first, a.second - b.second)
                    val midX = (a.first + b.first) * 0.5f
                    val midY = (a.second + b.second) * 0.5f
                    val vw = width.toFloat().coerceAtLeast(1f)
                    val vh = height.toFloat().coerceAtLeast(1f)
                    val iw = project.width.toFloat().coerceAtLeast(1f)
                    val ih = project.height.toFloat().coerceAtLeast(1f)
                    val res = if (zoomModeEnabled) {
                        CropZoomMath.pinchPanTransform(
                            anchorImageX = gestureAnchorImgX,
                            anchorImageY = gestureAnchorImgY,
                            initialZoom = gestureInitialZoom,
                            initialDist = gestureInitialDist,
                            currentDist = dist,
                            midViewX = midX,
                            midViewY = midY,
                            viewportW = vw,
                            viewportH = vh,
                            imageW = iw,
                            imageH = ih
                        )
                    } else {
                        CropZoomMath.pinchZoomOnlyTransform(
                            focusViewX = gestureStartMidX,
                            focusViewY = gestureStartMidY,
                            initialZoom = gestureInitialZoom,
                            initialPanX = gestureInitialPanX,
                            initialPanY = gestureInitialPanY,
                            initialDist = gestureInitialDist,
                            currentDist = dist,
                            viewportW = vw,
                            viewportH = vh,
                            imageW = iw,
                            imageH = ih
                        )
                    }
                    zoom = res.zoom
                    val (cx, cy) = CropZoomMath.clampPan(res.panX, res.panY, vw, vh, iw, ih, zoom)
                    panX = cx
                    panY = cy
                    autoFit = false
                    notifyZoomChanged()
                    invalidate()
                    return true
                }
                if (gestureWaitingAllUp) {
                    if (zoomModeEnabled && event.pointerCount == 1) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        autoFit = false
                        clampPan()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        notifyZoomChanged()
                        invalidate()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                activePointers.remove(liftedId)
                if (gestureActive && activePointers.size < 2) {
                    gestureActive = false
                    gestureWaitingAllUp = true
                    val rem = activePointers.values.firstOrNull()
                    if (rem != null) {
                        lastTouchX = rem.first
                        lastTouchY = rem.second
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                gestureActive = false
                if (gestureWaitingAllUp) {
                    gestureWaitingAllUp = false
                    dragMode = DragMode.NONE
                    return true
                }
            }
        }


        if (zoomModeEnabled && toolMode != StageToolMode.CROP) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = DragMode.PAN
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragMode == DragMode.PAN && !gestureActive) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        autoFit = false
                        clampPan()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        notifyZoomChanged()
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
            }
            return true
        }

        val (px, py) = viewToProject(event.x, event.y)

        when (toolMode) {
            StageToolMode.CROP -> {
                handleCropTouch(event, px, py)
                return true
            }
            StageToolMode.DRAW -> {
                handleFreehandDraw(event, px, py)
                return true
            }
            StageToolMode.PERSPECTIVE -> {
                handlePerspectiveTouch(event, px, py)
                return true
            }
            StageToolMode.MASK -> {
                handleMaskTouch(event, px, py)
                return true
            }
            StageToolMode.BEZIER -> {
                handleBezierTouch(event, px, py)
                return true
            }
            StageToolMode.BITMAP_ERASER -> {
                handleBitmapEraserTouch(event, px, py)
                return true
            }
            StageToolMode.WARP -> {
                handleWarpTouch(event, px, py)
                return true
            }
            StageToolMode.CUTOUT -> {
                handleCutoutTouch(event, px, py)
                return true
            }
            StageToolMode.RETOUCH -> {
                handleRetouchTouch(event, px, py)
                return true
            }
            StageToolMode.EYEDROPPER -> {
                val clampedPx = px.coerceIn(0f, project.width.toFloat().coerceAtLeast(1f))
                val clampedPy = py.coerceIn(0f, project.height.toFloat().coerceAtLeast(1f))
                eyedropperX = clampedPx
                eyedropperY = clampedPy
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> eyedropperDragging = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> eyedropperDragging = false
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN || eyedropperBitmap == null || eyedropperBitmap?.isRecycled == true || eyedropperBitmapDirty) {
                    eyedropperBitmap?.recycle()
                    val maxSide = max(project.width, project.height).coerceAtLeast(1)
                    val snapScale = min(1f, 1080f / maxSide.toFloat())
                    val sw = (project.width * snapScale).roundToInt().coerceIn(64, 1080)
                    val sh = (project.height * snapScale).roundToInt().coerceIn(64, 1080)
                    eyedropperBitmap = PixelLabRenderer.exportToBitmap(context, project, sw, sh, includeBackground = true)
                    eyedropperBitmapDirty = false
                }
                eyedropperBitmap?.let { snap ->
                    val ix = ((clampedPx / project.width.coerceAtLeast(1)) * (snap.width - 1)).roundToInt().coerceIn(0, snap.width - 1)
                    val iy = ((clampedPy / project.height.coerceAtLeast(1)) * (snap.height - 1)).roundToInt().coerceIn(0, snap.height - 1)
                    eyedropperColor = snap.getPixel(ix, iy)
                    onEyedropperColor?.invoke(eyedropperColor)
                }
                invalidate()
                return true
            }
            StageToolMode.NORMAL -> Unit
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragSnapshotSent = false
                touchStartProjX = px
                touchStartProjY = py

                // Priority 1: Dragging an editable Grid Guide when gridEditMode is ON
                if (grid.enabled && gridEditMode && grid.showGuides) {
                    val hitDist = max(28f / baseScale, 20f)
                    val pw = project.width.toFloat().coerceAtLeast(1f)
                    val ph = project.height.toFloat().coerceAtLeast(1f)
                    var bestH = -1
                    var bestHD = hitDist
                    for (i in grid.hGuides.indices) {
                        val d = abs(py - grid.hGuides[i] * ph)
                        if (d < bestHD) {
                            bestHD = d; bestH = i
                        }
                    }
                    var bestV = -1
                    var bestVD = hitDist
                    for (i in grid.vGuides.indices) {
                        val d = abs(px - grid.vGuides[i] * pw)
                        if (d < bestVD) {
                            bestVD = d; bestV = i
                        }
                    }
                    if (bestH >= 0 || bestV >= 0) {
                        val pickH = bestH >= 0 && (bestV < 0 || bestHD <= bestVD)
                        selectedGuideIsHorizontal = pickH
                        selectedGuideIndex = if (pickH) bestH else bestV
                        dragMode = DragMode.MOVE_GUIDE
                        val normPos = if (pickH) grid.hGuides[bestH] else grid.vGuides[bestV]
                        onGuideSelected?.invoke(pickH, selectedGuideIndex, normPos)
                        invalidate()
                        return true
                    }
                }

                val sel = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden }
                if (sel != null && !sel.locked) {
                    val handleHit = checkHandleHit(sel, px, py)
                    if (handleHit != DragMode.NONE) {
                        dragMode = handleHit
                        origLayerX = sel.x
                        origLayerY = sel.y
                        origLayerW = sel.width
                        origLayerH = sel.height
                        origRot = sel.rotation
                        origFontSize = (sel as? TextLayer)?.fontSize ?: 0f
                        origAspect = (sel.width / sel.height.coerceAtLeast(1f)).coerceAtLeast(0.05f)
                        origTouchAngleDeg = (atan2(py - sel.y, px - sel.x) * 180.0 / PI).toFloat()
                        val (lx, ly) = projectToLayerLocal(sel, px, py)
                        origLocalX = lx
                        origLocalY = ly
                        origHandleEdgeOffsetX = lx - sel.width / 2f
                        origHandleEdgeOffsetY = ly - sel.height / 2f
                        return true
                    }
                }

                val hit = hitTestLayer(px, py)
                if (hit != null) {
                    onLayerSelected?.invoke(hit.id)
                    if (!hit.locked) {
                        dragMode = DragMode.MOVE
                        origLayerX = hit.x
                        origLayerY = hit.y
                    } else {
                        dragMode = DragMode.NONE
                    }
                } else {
                    onLayerSelected?.invoke(null)
                    dragMode = DragMode.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.PAN && !gestureActive) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    autoFit = false
                    clampPan()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    notifyZoomChanged()
                    invalidate()
                    return true
                }
                if (dragMode == DragMode.MOVE_GUIDE) {
                    val pw = project.width.toFloat().coerceAtLeast(1f)
                    val ph = project.height.toFloat().coerceAtLeast(1f)
                    if (selectedGuideIsHorizontal && selectedGuideIndex in grid.hGuides.indices) {
                        val norm = (py / ph).coerceIn(0.01f, 0.99f)
                        val updated = grid.hGuides.toMutableList().apply { set(selectedGuideIndex, norm) }
                        grid = grid.copy(hGuides = updated)
                        onGridChanged?.invoke(grid)
                        onGuideSelected?.invoke(true, selectedGuideIndex, norm)
                    } else if (!selectedGuideIsHorizontal && selectedGuideIndex in grid.vGuides.indices) {
                        val norm = (px / pw).coerceIn(0.01f, 0.99f)
                        val updated = grid.vGuides.toMutableList().apply { set(selectedGuideIndex, norm) }
                        grid = grid.copy(vGuides = updated)
                        onGridChanged?.invoke(grid)
                        onGuideSelected?.invoke(false, selectedGuideIndex, norm)
                    }
                    return true
                }
                val sel = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden && !it.locked }
                    ?: return true
                if (dragMode == DragMode.NONE) return true

                if (!dragSnapshotSent) {
                    dragSnapshotSent = true
                    onBeforeTransform?.invoke()
                }

                when (dragMode) {
                    DragMode.MOVE -> {
                        var nx = origLayerX + (px - touchStartProjX)
                        var ny = origLayerY + (py - touchStartProjY)
                        if (grid.snap) {
                            val (snappedX, guideX) = snapAxisMagnetic(
                                center = nx,
                                sizeW = sel.width,
                                sizeH = sel.height,
                                rotationDeg = sel.rotation,
                                total = project.width.toFloat(),
                                isXAxis = true
                            )
                            val (snappedY, guideY) = snapAxisMagnetic(
                                center = ny,
                                sizeW = sel.width,
                                sizeH = sel.height,
                                rotationDeg = sel.rotation,
                                total = project.height.toFloat(),
                                isXAxis = false
                            )
                            nx = snappedX
                            ny = snappedY
                            activeGuideX = guideX
                            activeGuideY = guideY
                        } else {
                            activeGuideX = null
                            activeGuideY = null
                        }
                        onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny))
                    }
                    DragMode.RESIZE_CORNER -> {
                        val (lx, ly) = projectToOriginLocal(px, py)
                        if (sel is TextLayer) {
                            // Text bottom-left handle anchors opposite top-right corner (+origLayerW/2, -origLayerH/2)
                            val ax = origLayerW / 2f
                            val ay = -origLayerH / 2f
                            val origDx = origLocalX - ax
                            val origDy = origLocalY - ay
                            val lenSq = (origDx * origDx + origDy * origDy).coerceAtLeast(64f)
                            val scale = (((lx - ax) * origDx + (ly - ay) * origDy) / lenSq).coerceIn(0.12f, 12f)
                            val nextFont = (origFontSize * scale).coerceIn(14f, 450f)
                            val probe = sel.copy(fontSize = nextFont)
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(probe, context)
                            val (nx, ny) = originLocalShiftToCanvas((origLayerW - nw) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(probe.copy(x = nx, y = ny, width = nw, height = nh))
                        } else {
                            // Bottom-right corner handle anchors opposite top-left corner (-origLayerW/2, -origLayerH/2)
                            val ax = -origLayerW / 2f
                            val ay = -origLayerH / 2f
                            val origDx = origLocalX - ax
                            val origDy = origLocalY - ay
                            val lenSq = (origDx * origDx + origDy * origDy).coerceAtLeast(64f)
                            val scale = (((lx - ax) * origDx + (ly - ay) * origDy) / lenSq).coerceIn(0.12f, 12f)
                            val nw = (origLayerW * scale).coerceAtLeast(36f)
                            val nh = (nw / origAspect).coerceAtLeast(28f)
                            val (nx, ny) = originLocalShiftToCanvas((nw - origLayerW) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, width = nw, height = nh))
                        }
                    }
                    DragMode.RESIZE_WIDTH -> {
                        val (lx, _) = projectToOriginLocal(px, py)
                        val rightEdge = lx - origHandleEdgeOffsetX
                        val minW = if (sel is TextLayer) 64f else 36f
                        val targetW = (rightEdge - (-origLayerW / 2f)).coerceAtLeast(minW)
                        if (sel is TextLayer) {
                            val (nw, nh) = PixelLabRenderer.measureWrappedTextBounds(sel, targetW, context)
                            val (nx, ny) = originLocalShiftToCanvas((nw - origLayerW) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(sel.copy(x = nx, y = ny, width = nw, height = nh))
                        } else {
                            val (nx, ny) = originLocalShiftToCanvas((targetW - origLayerW) / 2f, 0f)
                            onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, width = targetW))
                        }
                    }
                    DragMode.RESIZE_HEIGHT -> {
                        val (_, ly) = projectToOriginLocal(px, py)
                        val bottomEdge = ly - origHandleEdgeOffsetY
                        val nh = (bottomEdge - (-origLayerH / 2f)).coerceAtLeast(32f)
                        val (nx, ny) = originLocalShiftToCanvas(0f, (nh - origLayerH) / 2f)
                        onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, height = nh))
                    }
                    DragMode.ROTATE -> {
                        val curAngleDeg = (atan2(py - sel.y, px - sel.x) * 180.0 / PI).toFloat()
                        var deg = origRot + (curAngleDeg - origTouchAngleDeg)
                        while (deg > 180f) deg -= 360f
                        while (deg < -180f) deg += 360f
                        deg = snapAngle(deg)
                        onLayerTransform?.invoke(sel.copyBase(rotation = deg))
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeGuideX = null
                activeGuideY = null
                if (dragSnapshotSent) {
                    onTransformEnd?.invoke()
                }
                dragMode = DragMode.NONE
                dragSnapshotSent = false
                invalidate()
            }
        }
        return true
    }

    private fun handleCropTouch(event: MotionEvent, px: Float, py: Float) {
        val (cx, cy) = if (cropTarget == CropTarget.IMAGE_LAYER) {
            val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer ?: return
            val (lx, ly) = projectToLayerLocal(sel, px, py)
            (lx + sel.width * 0.5f) to (ly + sel.height * 0.5f)
        } else {
            px to py
        }
        val (imgW, imgH) = getCropImageDimensions()
        val invScale = (1f / baseScale).coerceAtLeast(0.5f)
        val hitRadius = max(34f * invScale, 24f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = CropBounds(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
                val hit = CropZoomMath.hitCropHandle(bounds, cx, cy, hitRadius)
                if (hit != CropHandle.NONE) {
                    activeCropHandle = hit
                    cropDragStartX = cx
                    cropDragStartY = cy
                    cropRectStart = bounds
                } else {
                    activeCropHandle = CropHandle.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCropHandle != CropHandle.NONE) {
                    val next = CropZoomMath.resizeCrop(
                        handle = activeCropHandle,
                        dx = cx - cropDragStartX,
                        dy = cy - cropDragStartY,
                        start = cropRectStart,
                        imageW = imgW,
                        imageH = imgH,
                        aspectRatio = cropAspectRatio
                    )
                    cropRect.set(next.left, next.top, next.right, next.bottom)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCropHandle = CropHandle.NONE
            }
        }
    }

    private fun handleFreehandDraw(event: MotionEvent, px: Float, py: Float) {
        val preset = BrushEngine.presetOf(brushConfig.presetId)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDrawPoints.clear()
                activeDrawPoints.add(DrawPoint(px, py))
                drawLastPt = DrawPoint(px, py)
                brushIndicatorPt = DrawPoint(px, py)
                currentStrokeSeed = liveDrawStampCounter
                val bmp = ensureLiveDrawBitmap()
                val c = Canvas(bmp)
                c.save()
                c.scale(liveDrawScale, liveDrawScale)
                liveDrawStampCounter = BrushEngine.stampSegment(
                    canvas = c,
                    preset = preset,
                    diameter = brushConfig.width,
                    hardness = brushConfig.hardness,
                    color = brushConfig.color,
                    opacityPct = brushConfig.opacity,
                    flowPct = brushConfig.flow,
                    eraser = brushConfig.eraser,
                    x0 = px,
                    y0 = py,
                    x1 = px,
                    y1 = py,
                    stampCounterStart = liveDrawStampCounter
                )
                c.restore()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val prev = drawLastPt ?: DrawPoint(px, py)
                activeDrawPoints.add(DrawPoint(px, py))
                drawLastPt = DrawPoint(px, py)
                brushIndicatorPt = DrawPoint(px, py)
                val bmp = ensureLiveDrawBitmap()
                val c = Canvas(bmp)
                c.save()
                c.scale(liveDrawScale, liveDrawScale)
                liveDrawStampCounter = BrushEngine.stampSegment(
                    canvas = c,
                    preset = preset,
                    diameter = brushConfig.width,
                    hardness = brushConfig.hardness,
                    color = brushConfig.color,
                    opacityPct = brushConfig.opacity,
                    flowPct = brushConfig.flow,
                    eraser = brushConfig.eraser,
                    x0 = prev.x,
                    y0 = prev.y,
                    x1 = px,
                    y1 = py,
                    stampCounterStart = liveDrawStampCounter
                )
                c.restore()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeDrawPoints.isNotEmpty()) {
                    pendingStrokes.add(
                        DrawingStroke(
                            points = activeDrawPoints.toList(),
                            color = brushConfig.color,
                            width = brushConfig.width,
                            blur = brushConfig.blur,
                            dashed = brushConfig.dashed,
                            neon = brushConfig.neon,
                            fill = brushConfig.fill,
                            eraser = brushConfig.eraser,
                            presetId = brushConfig.presetId,
                            hardness = brushConfig.hardness,
                            opacity = brushConfig.opacity,
                            flow = brushConfig.flow,
                            seed = currentStrokeSeed
                        )
                    )
                    activeDrawPoints.clear()
                    onDrawStrokeCountChanged?.invoke(pendingStrokes.size)
                }
                drawLastPt = null
                brushIndicatorPt = null
                invalidate()
            }
        }
    }

    private fun handleWarpTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == warpTargetLayerId } as? ImageLayer ?: return
        val sess = warpSession ?: return
        val work = sess.workBmp
        val bw = work.width.coerceAtLeast(1).toFloat()
        val bh = work.height.coerceAtLeast(1).toFloat()

        // Map canvas (px, py) into expanded padded bitmap coordinates
        val dx = px - sel.x
        val dy = py - sel.y
        val rad = -sel.rotation * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val nx = (lx / sess.paddedCanvasW.coerceAtLeast(1f)) + 0.5f
        val ny = (ly / sess.paddedCanvasH.coerceAtLeast(1f)) + 0.5f
        val u = if (sel.flipH) 1f - nx else nx
        val v = if (sel.flipV) 1f - ny else ny
        val bx = u * bw
        val by = v * bh

        if (warpMode == WarpEngine.WarpMode.BRUSH) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    warpLastBmpPt = PointF(bx, by)
                    brushPinLabel = null
                    brushIndicatorPt = DrawPoint(px, py)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val last = warpLastBmpPt ?: PointF(bx, by)
                    val bmpRadius = max(4f, (warpSize * 0.5f) * (bw / sess.paddedCanvasW.coerceAtLeast(1f)))
                    WarpEngine.warpBrushSegment(work, last.x, last.y, bx, by, bmpRadius, warpStrength)
                    warpLastBmpPt = PointF(bx, by)
                    brushPinLabel = null
                    brushIndicatorPt = DrawPoint(px, py)
                    warpHasChanges = true
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    warpLastBmpPt = null
                    brushIndicatorPt = null
                    invalidate()
                }
            }
        } else {
            val pts = sess.gridPts
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hitThresh = max(42f / baseScale, 32f) * (bw / sess.paddedCanvasW.coerceAtLeast(1f))
                    var bestIdx = -1
                    var bestDist = hitThresh
                    for (i in pts.indices) {
                        val d = hypot(pts[i].x - bx, pts[i].y - by)
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = i
                        }
                    }
                    warpActiveVertex = bestIdx
                }
                MotionEvent.ACTION_MOVE -> {
                    if (warpActiveVertex in pts.indices) {
                        pts[warpActiveVertex].set(bx.coerceIn(0f, bw), by.coerceIn(0f, bh))
                        WarpEngine.renderPaperMesh(sess.paperSrcBmp, work, pts)
                        warpHasChanges = true
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    warpActiveVertex = -1
                }
            }
        }
    }

    private fun canvasToCutoutBmp(layer: ImageLayer, sess: CutoutEngine.CutoutSession, px: Float, py: Float): PointF {
        val np = projectToLayerNormalized(layer, px, py)
        val u = (if (layer.flipH) 1f - np.x else np.x).coerceIn(0f, 1f)
        val v = (if (layer.flipV) 1f - np.y else np.y).coerceIn(0f, 1f)
        return PointF(u * sess.w.toFloat(), v * sess.h.toFloat())
    }

    private fun handleCutoutTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == cutoutTargetLayerId } as? ImageLayer ?: return
        val sess = cutoutSession ?: return
        val bp = canvasToCutoutBmp(sel, sess, px, py)

        when (cutoutMode) {
            CutoutEngine.CutoutMode.LASSO -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cutoutPolyPts.clear()
                        cutoutPolyPts.add(bp)
                        onPolygonPointsChanged?.invoke(1)
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val last = cutoutPolyPts.lastOrNull()
                        if (last == null || hypot(bp.x - last.x, bp.y - last.y) >= 2f) {
                            cutoutPolyPts.add(bp)
                            invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (cutoutPolyPts.size >= 3) {
                            val pts = cutoutPolyPts.toList()
                            CutoutEngine.applyPathSelection(sess, cutoutOp) { c, p ->
                                val path = Path()
                                path.moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                                path.close()
                                c.drawPath(path, p)
                            }
                            applyPendingCutoutModifiers(sess)
                            onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                        }
                        cutoutPolyPts.clear()
                        onPolygonPointsChanged?.invoke(0)
                        invalidate()
                    }
                }
            }
            CutoutEngine.CutoutMode.POLYGON -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (cutoutPolyPts.size >= 3) {
                        val first = cutoutPolyPts[0]
                        val hitPx = hypot(bp.x - first.x, bp.y - first.y) * (sel.width / sess.w.coerceAtLeast(1))
                        if (hitPx < max(28f / baseScale, 18f)) {
                            closePolygonCutoutSelection()
                            return
                        }
                    }
                    cutoutPolyPts.add(bp)
                    onPolygonPointsChanged?.invoke(cutoutPolyPts.size)
                    invalidate()
                }
            }
            CutoutEngine.CutoutMode.RECT, CutoutEngine.CutoutMode.ELLIPSE -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cutoutDragStartBmp = bp
                        cutoutDragCurBmp = bp
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        cutoutDragCurBmp = bp
                        invalidate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val d0 = cutoutDragStartBmp
                        val d1 = bp
                        cutoutDragStartBmp = null
                        cutoutDragCurBmp = null
                        if (d0 != null && abs(d1.x - d0.x) >= 3f && abs(d1.y - d0.y) >= 3f) {
                            val rect = RectF(min(d0.x, d1.x), min(d0.y, d1.y), max(d0.x, d1.x), max(d0.y, d1.y))
                            CutoutEngine.applyPathSelection(sess, cutoutOp) { c, p ->
                                if (cutoutMode == CutoutEngine.CutoutMode.RECT) c.drawRect(rect, p)
                                else c.drawOval(rect, p)
                            }
                            applyPendingCutoutModifiers(sess)
                            onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                        }
                        invalidate()
                    }
                }
            }
            CutoutEngine.CutoutMode.MAGIC_WAND -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    CutoutEngine.selectMagicWand(sess, bp.x.roundToInt(), bp.y.roundToInt(), cutoutTolerance, cutoutOp)
                    applyPendingCutoutModifiers(sess)
                    onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                    invalidate()
                }
            }
            CutoutEngine.CutoutMode.COLOR_RANGE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    CutoutEngine.selectColorRange(sess, bp.x.roundToInt(), bp.y.roundToInt(), cutoutTolerance, cutoutOp)
                    applyPendingCutoutModifiers(sess)
                    onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                    invalidate()
                }
            }
            CutoutEngine.CutoutMode.QUICK_SELECT -> {
                val bmpRad = max(3f, cutoutBrushRadius * (sess.w.toFloat() / sel.width.coerceAtLeast(1f)))
                val isSub = cutoutOp == CutoutEngine.SelectionOp.SUB
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cutoutLastBrushBmp = bp
                        brushPinLabel = null
                        brushIndicatorPt = DrawPoint(px, py)
                        CutoutEngine.paintQuickSelection(sess, bp.x, bp.y, bp.x, bp.y, bmpRad, isSub)
                        onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val last = cutoutLastBrushBmp ?: bp
                        CutoutEngine.paintQuickSelection(sess, last.x, last.y, bp.x, bp.y, bmpRad, isSub)
                        cutoutLastBrushBmp = bp
                        brushIndicatorPt = DrawPoint(px, py)
                        onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                        invalidate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cutoutLastBrushBmp = null
                        brushIndicatorPt = null
                        invalidate()
                    }
                }
            }
            CutoutEngine.CutoutMode.AUTO_SELECT -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    CutoutEngine.selectAutoSubject(sess, bp.x.roundToInt(), bp.y.roundToInt(), cutoutTolerance, cutoutOp)
                    applyPendingCutoutModifiers(sess)
                    onCutoutStateChanged?.invoke(sess.hasMask, sess.hasImageChanges)
                    invalidate()
                }
            }
        }
    }

    private fun handleRetouchTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == retouchTargetLayerId } as? ImageLayer ?: return
        val sess = retouchSession ?: return
        val np = projectToLayerNormalized(sel, px, py)
        val u = (if (sel.flipH) 1f - np.x else np.x).coerceIn(0f, 1f)
        val v = (if (sel.flipV) 1f - np.y else np.y).coerceIn(0f, 1f)
        val bx = u * sess.w.toFloat()
        val by = v * sess.h.toFloat()
        val bmpRad = max(3f, (retouchSize * 0.5f) * (sess.w.toFloat() / sel.width.coerceAtLeast(1f)))

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                retouchLastBmpPt = PointF(bx, by)
                brushPinLabel = null
                brushIndicatorPt = DrawPoint(px, py)
                RetouchEngine.retouchSegment(sess.workBmp, retouchMode, bx, by, bx, by, bmpRad, retouchStrength)
                sess.hasChanges = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val last = retouchLastBmpPt ?: PointF(bx, by)
                RetouchEngine.retouchSegment(sess.workBmp, retouchMode, last.x, last.y, bx, by, bmpRad, retouchStrength)
                retouchLastBmpPt = PointF(bx, by)
                brushIndicatorPt = DrawPoint(px, py)
                sess.hasChanges = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                retouchLastBmpPt = null
                brushIndicatorPt = null
                invalidate()
            }
        }
    }

    private fun handlePerspectiveTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } ?: return
        val pts = when (sel) {
            is TextLayer -> sel.perspectivePoints
            is ShapeLayer -> sel.perspectivePoints
            is ImageLayer -> sel.perspectivePoints
            else -> return
        }
        if (pts.size != 4) return
        val np = projectToLayerNormalized(sel, px, py)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePerspectiveIndex = pts.indices.minByOrNull { idx ->
                    hypot(pts[idx].x - np.x, pts[idx].y - np.y)
                } ?: -1
                if (activePerspectiveIndex >= 0) {
                    val dist = hypot((pts[activePerspectiveIndex].x - np.x) * sel.width, (pts[activePerspectiveIndex].y - np.y) * sel.height)
                    if (dist > max(64f / baseScale, 48f)) activePerspectiveIndex = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePerspectiveIndex in 0..3) {
                    val updatedPts = pts.toMutableList()
                    updatedPts[activePerspectiveIndex] = DrawPoint(np.x.coerceIn(-0.6f, 1.6f), np.y.coerceIn(-0.6f, 1.6f))
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        is ShapeLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        is ImageLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePerspectiveIndex = -1
        }
    }

    private fun handleMaskTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } ?: return
        val pts = when (sel) {
            is TextLayer -> sel.maskPoints
            is ShapeLayer -> sel.maskPoints
            is ImageLayer -> sel.maskPoints
            else -> return
        }
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitIdx = pts.indexOfFirst {
                    hypot((it.x - np.x) * sel.width, (it.y - np.y) * sel.height) < max(42f / baseScale, 32f)
                }
                if (hitIdx >= 0) {
                    activeVertexIndex = hitIdx
                } else {
                    val nextPts = pts + np
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        is ShapeLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        is ImageLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                    activeVertexIndex = nextPts.lastIndex
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeVertexIndex in pts.indices) {
                    val nextPts = pts.toMutableList()
                    nextPts[activeVertexIndex] = np
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(maskPoints = nextPts)
                        is ShapeLayer -> sel.copy(maskPoints = nextPts)
                        is ImageLayer -> sel.copy(maskPoints = nextPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeVertexIndex = -1
        }
    }

    private fun handleBezierTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? BezierLayer ?: return
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitIdx = sel.anchors.indexOfFirst {
                    hypot((it.x - np.x) * sel.width, (it.y - np.y) * sel.height) < max(44f / baseScale, 32f)
                }
                if (hitIdx >= 0) {
                    activeVertexIndex = hitIdx
                } else if (bezierActionMode == 0) {
                    val prev = sel.anchors.lastOrNull()
                    val offset = 0.08f
                    val newAnchor = if (prev != null) {
                        val dx = (np.x - prev.x) * 0.25f
                        val dy = (np.y - prev.y) * 0.25f
                        BezierAnchor(np.x, np.y, np.x - dx, np.y - dy, np.x + dx, np.y + dy)
                    } else {
                        BezierAnchor(np.x, np.y, np.x - offset, np.y, np.x + offset, np.y)
                    }
                    onLayerTransform?.invoke(sel.copy(anchors = sel.anchors + newAnchor))
                    activeVertexIndex = sel.anchors.size
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeVertexIndex in sel.anchors.indices) {
                    val list = sel.anchors.toMutableList()
                    val old = list[activeVertexIndex]
                    val dx = np.x - old.x
                    val dy = np.y - old.y
                    list[activeVertexIndex] = old.copy(
                        x = np.x,
                        y = np.y,
                        ctrlInX = old.ctrlInX + dx,
                        ctrlInY = old.ctrlInY + dy,
                        ctrlOutX = old.ctrlOutX + dx,
                        ctrlOutY = old.ctrlOutY + dy
                    )
                    onLayerTransform?.invoke(sel.copy(anchors = list))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeVertexIndex = -1
        }
    }

    private fun handleBitmapEraserTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer ?: return
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeEraserPoints.clear()
                activeEraserPoints.add(np)
                eraserStrokeAdded = false
            }
            MotionEvent.ACTION_MOVE -> {
                activeEraserPoints.add(np)
                val previewStroke = DrawingStroke(
                    points = activeEraserPoints.toList(),
                    color = Color.TRANSPARENT,
                    width = bitmapEraserWidth,
                    eraser = true
                )
                val base = if (eraserStrokeAdded) sel.eraserStrokes.dropLast(1) else sel.eraserStrokes
                eraserStrokeAdded = true
                onLayerTransform?.invoke(sel.copy(eraserStrokes = base + previewStroke))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeEraserPoints.clear()
                eraserStrokeAdded = false
            }
        }
    }

    private fun snapAngle(deg: Float): Float {
        val snapTargets = floatArrayOf(0f, 90f, -90f, 180f, -180f)
        for (target in snapTargets) {
            if (abs(deg - target) <= 4f) return target
        }
        return deg
    }

    private fun snapAxisMagnetic(
        center: Float,
        sizeW: Float,
        sizeH: Float,
        rotationDeg: Float,
        total: Float,
        isXAxis: Boolean
    ): Pair<Float, Float?> {
        val (halfW, halfH) = LayerTransformMath.rotatedHalfExtents(sizeW, sizeH, rotationDeg)
        val half = if (isXAxis) halfW else halfH
        val targets = ArrayList<Float>(24)
        targets.add(0f)
        targets.add(total * 0.5f)
        targets.add(total)
        if (grid.enabled && grid.showGuides) {
            val guides = if (isXAxis) grid.vGuides else grid.hGuides
            for (gNorm in guides) {
                targets.add(gNorm * total)
            }
        }
        if (grid.enabled && grid.showPixelGrid) {
            val step = grid.size.coerceIn(8, 400).toFloat()
            var g = step
            var count = 0
            while (g < total && count < 200) {
                targets.add(g)
                g += step
                count++
            }
        }
        val field = grid.magneticField.coerceIn(2, 60).toFloat() / baseScale.coerceAtLeast(0.05f)
        val strength = grid.snapStrength.coerceIn(0, 100) / 100f
        val anchors = floatArrayOf(center - half, center, center + half)

        var bestDist = Float.MAX_VALUE
        var bestDelta = 0f
        var bestTarget: Float? = null
        for (a in anchors) {
            for (t in targets) {
                val d = t - a
                val ad = abs(d)
                if (ad < field && ad < bestDist) {
                    bestDist = ad
                    bestDelta = d
                    bestTarget = t
                }
            }
        }
        if (bestTarget == null) return center to null
        val k = strength + (1f - strength) * (1f - bestDist / field)
        return (center + bestDelta * k) to bestTarget
    }

    private fun projectToOriginLocal(px: Float, py: Float): Pair<Float, Float> {
        val dx = px - origLayerX
        val dy = py - origLayerY
        val rad = -origRot * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return lx to ly
    }

    private fun originLocalShiftToCanvas(cxLocal: Float, cyLocal: Float): Pair<Float, Float> {
        val rad = origRot * PI / 180.0
        val nx = origLayerX + (cxLocal * cos(rad) - cyLocal * sin(rad)).toFloat()
        val ny = origLayerY + (cxLocal * sin(rad) + cyLocal * cos(rad)).toFloat()
        return nx to ny
    }

    private fun projectToLayerLocal(layer: CanvasLayer, px: Float, py: Float): Pair<Float, Float> {
        val dx = px - layer.x
        val dy = py - layer.y
        val rad = -layer.rotation * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return lx to ly
    }

    private fun checkHandleHit(layer: CanvasLayer, px: Float, py: Float): DragMode {
        val (lx, ly) = projectToLayerLocal(layer, px, py)
        val invScale = (1f / baseScale).coerceAtLeast(0.5f)

        val hw = layer.width / 2f
        val hh = layer.height / 2f
        val hitR = (30f / baseScale).coerceAtLeast(24f)

        if (layer is TextLayer) {
            // Bottom-center smile rotation arc
            val rotY = hh + 24f * invScale
            if (hypot(lx, ly - rotY) <= hitR * 1.15f) return DragMode.ROTATE
            // Bottom-left proportional scale handle
            if (hypot(lx + hw, ly - hh) <= hitR) return DragMode.RESIZE_CORNER
            // Right-center width wrap handle
            if (hypot(lx - hw, ly) <= hitR) return DragMode.RESIZE_WIDTH
        } else {
            // Bottom-left outer corner rotation arc
            val rotX = -hw - 12f * invScale
            val rotY = hh + 12f * invScale
            if (hypot(lx - rotX, ly - rotY) <= hitR * 1.2f || hypot(lx + hw, ly - hh) <= hitR * 0.9f) {
                return DragMode.ROTATE
            }
            // Bottom-right main proportional scale corner
            if (hypot(lx - hw, ly - hh) <= hitR) return DragMode.RESIZE_CORNER
            // Right-center width handle
            if (hypot(lx - hw, ly) <= hitR) return DragMode.RESIZE_WIDTH
            // Bottom-center height handle
            if (hypot(lx, ly - hh) <= hitR) return DragMode.RESIZE_HEIGHT
        }

        return DragMode.NONE
    }

    private fun hitTestLayer(px: Float, py: Float): CanvasLayer? {
        for (i in project.layers.indices.reversed()) {
            val l = project.layers[i]
            if (l.hidden) continue
            val dx = px - l.x
            val dy = py - l.y
            val rad = -l.rotation * PI / 180.0
            val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
            val pad = max(14f / baseScale, 10f)
            if (abs(lx) <= l.width / 2f + pad && abs(ly) <= l.height / 2f + pad) {
                return l
            }
        }
        return null
    }
}
