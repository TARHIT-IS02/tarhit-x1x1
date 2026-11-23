package com.imaginstudio.imagetools.pixellab.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updateLayoutParams
import com.google.android.material.switchmaterial.SwitchMaterial
import com.imaginstudio.imagetools.pixellab.R
import com.imaginstudio.imagetools.pixellab.databinding.ActivityMainBinding
import com.imaginstudio.imagetools.pixellab.engine.BrushEngine
import com.imaginstudio.imagetools.pixellab.engine.CutoutEngine
import com.imaginstudio.imagetools.pixellab.engine.LayerTransformMath
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.engine.RetouchEngine
import com.imaginstudio.imagetools.pixellab.engine.ShapePathBuilder
import com.imaginstudio.imagetools.pixellab.engine.ShapeToImageExporter
import com.imaginstudio.imagetools.pixellab.engine.WarpEngine
import com.imaginstudio.imagetools.pixellab.model.AppDefaults
import com.imaginstudio.imagetools.pixellab.model.ArrowHeadStyle
import com.imaginstudio.imagetools.pixellab.model.ArrowLayer
import com.imaginstudio.imagetools.pixellab.model.AssetRepository
import com.imaginstudio.imagetools.pixellab.model.BackgroundType
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.BoundedShapeLabel
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.DrawPoint
import com.imaginstudio.imagetools.pixellab.model.EffectsState
import com.imaginstudio.imagetools.pixellab.model.FillType
import com.imaginstudio.imagetools.pixellab.model.GradientDirection
import com.imaginstudio.imagetools.pixellab.model.GradientFill
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.JsonPresetCodec
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.SavedBackgroundPreset
import com.imaginstudio.imagetools.pixellab.model.ShadowConfig
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.StrokeConfig
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import com.imaginstudio.imagetools.pixellab.view.CanvasStageView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class OptionExpandController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val canvasStage: CanvasStageView,
    private val getProject: () -> ProjectState,
    private val getSelectedLayer: () -> CanvasLayer?,
    private val pushHistory: (ProjectState) -> Unit,
    private val updateProjectLive: (ProjectState) -> Unit,
    private val updateSelectedLayerLive: (CanvasLayer) -> Unit,
    private val addLayer: (CanvasLayer) -> Unit,
    private val onPickBgImage: () -> Unit,
    private val onPickBgPresetImage: () -> Unit,
    private val onPickTextureImage: () -> Unit,
    private val onRequestImportJson: ((String) -> Unit) -> Unit,
    private val onRequestExportJson: (String, String) -> Unit,
    private val onToolClosed: () -> Unit,
    private val onRestoreSelection: (String?) -> Unit,
    private val addLayerKeepSelection: ((CanvasLayer) -> Unit)? = null
) {

    var activeToolId: String? = null
        private set

    var onFloatingToolVisibilityChanged: ((Boolean) -> Unit)? = null

    private var snapshotBeforeEdit: ProjectState? = null
    private var snapshotSelectedId: String? = null
    private var lastEyedropperColor: Int = Color.WHITE
    private var animGen: Int = 0
    private var cropFeatureEnabled: Boolean = true
    private var cropStraightenBarOpen: Boolean = false
    private var warpControlMode: String = "size"
    private var cutoutSubPanel: String = "op"
    private var cutoutModifyTab: String = "feather"
    private var retouchControlMode: String = "size"
    private var hidePreviewToken: Runnable? = null

    private fun isDark(): Boolean = ThemeManager.isDark(context)
    private val tealColor: Int get() = ThemeManager.accentPrimary(isDark())
    private val accentBlue: Int get() = ThemeManager.accentBlue(isDark())
    private val sheetBg: Int get() = ThemeManager.sheetBg(isDark())
    private val sageColBg: Int get() = ThemeManager.actionColBg(isDark())
    private val sageDivider: Int get() = ThemeManager.actionColDivider(isDark())

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()

    fun discardOrFinishForExternalAction(commitLiveEdits: Boolean) {
        if (!isOpen() && activeToolId == null) return
        val tool = activeToolId
        val prevSel = snapshotSelectedId
        if (tool == "draw") {
            if (commitLiveEdits) {
                val drawing = canvasStage.finishDrawingLayer()
                if (drawing != null) {
                    addLayer(drawing)
                }
            } else {
                canvasStage.clearPendingDrawing()
            }
        } else if (tool == "bezier_edit") {
            val curBez = getSelectedLayer() as? BezierLayer
            if (curBez != null && curBez.anchors.isEmpty()) {
                snapshotBeforeEdit?.let { prev -> updateProjectLive(prev) }
                onRestoreSelection(prevSel)
            } else if (commitLiveEdits) {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "img_crop" || tool == "bg_crop") {
            canvasStage.clearCropState()
            if (commitLiveEdits) {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            } else {
                snapshotBeforeEdit?.let { prev -> updateProjectLive(prev) }
            }
        } else if (tool == "img_warp") {
            if (commitLiveEdits) {
                val res = canvasStage.commitWarp()
                if (res != null) {
                    val curImg = getSelectedLayer() as? ImageLayer
                    if (curImg != null) {
                        PixelLabRenderer.clearCache()
                        snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                        updateSelectedLayerLive(
                            curImg.copy(
                                uriString = res.uriString,
                                x = res.newX,
                                y = res.newY,
                                width = res.newW,
                                height = res.newH
                            )
                        )
                    }
                }
            } else {
                canvasStage.clearWarpState()
                snapshotBeforeEdit?.let { prev -> updateProjectLive(prev) }
            }
        } else if (tool == "img_cutout") {
            if (commitLiveEdits) {
                val res = canvasStage.commitCutout()
                if (res != null) {
                    val curImg = getSelectedLayer() as? ImageLayer
                    if (curImg != null) {
                        PixelLabRenderer.clearCache()
                        snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                        updateSelectedLayerLive(
                            curImg.copy(
                                uriString = res.uriString,
                                x = res.newX,
                                y = res.newY,
                                width = res.newW,
                                height = res.newH
                            )
                        )
                    }
                }
            } else {
                canvasStage.clearCutoutState()
                snapshotBeforeEdit?.let { prev -> updateProjectLive(prev) }
            }
        } else if (tool == "img_retouch") {
            if (commitLiveEdits) {
                val savedPath = canvasStage.commitRetouch()
                if (savedPath != null) {
                    val curImg = getSelectedLayer() as? ImageLayer
                    if (curImg != null) {
                        PixelLabRenderer.clearCache()
                        snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                        updateSelectedLayerLive(curImg.copy(uriString = savedPath))
                    }
                }
            } else {
                canvasStage.clearRetouchState()
                snapshotBeforeEdit?.let { prev -> updateProjectLive(prev) }
            }
        } else if (commitLiveEdits) {
            val prev = snapshotBeforeEdit
            if (prev != null && prev != getProject()) {
                pushHistory(prev)
            }
        }
        snapshotBeforeEdit = null
        snapshotSelectedId = null
        closePanel(immediate = true)
    }

    private fun cancelCurrentTool() {
        val prevSel = snapshotSelectedId
        if (activeToolId == "draw") {
            canvasStage.clearPendingDrawing()
        } else {
            when (activeToolId) {
                "img_crop", "bg_crop" -> canvasStage.clearCropState()
                "img_warp" -> canvasStage.clearWarpState()
                "img_cutout" -> canvasStage.clearCutoutState()
                "img_retouch" -> canvasStage.clearRetouchState()
            }
            snapshotBeforeEdit?.let { prev ->
                updateProjectLive(prev)
            }
        }
        canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
        onRestoreSelection(prevSel)
        closePanel()
    }

    private fun commitCurrentTool() {
        val tool = activeToolId
        val prevSel = snapshotSelectedId
        if (tool == "draw") {
            val drawing = canvasStage.finishDrawingLayer()
            canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
            if (drawing != null) {
                addLayer(drawing)
            }
        } else if (tool == "img_warp") {
            val res = canvasStage.commitWarp()
            canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
            if (res != null) {
                val curImg = getSelectedLayer() as? ImageLayer
                if (curImg != null) {
                    PixelLabRenderer.clearCache()
                    snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                    updateSelectedLayerLive(
                        curImg.copy(
                            uriString = res.uriString,
                            x = res.newX,
                            y = res.newY,
                            width = res.newW,
                            height = res.newH
                        )
                    )
                }
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "img_cutout") {
            val res = canvasStage.commitCutout()
            canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
            if (res != null) {
                val curImg = getSelectedLayer() as? ImageLayer
                if (curImg != null) {
                    PixelLabRenderer.clearCache()
                    snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                    updateSelectedLayerLive(
                        curImg.copy(
                            uriString = res.uriString,
                            x = res.newX,
                            y = res.newY,
                            width = res.newW,
                            height = res.newH
                        )
                    )
                }
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "img_retouch") {
            val savedPath = canvasStage.commitRetouch()
            canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
            if (savedPath != null) {
                val curImg = getSelectedLayer() as? ImageLayer
                if (curImg != null) {
                    PixelLabRenderer.clearCache()
                    snapshotBeforeEdit?.let { prev -> pushHistory(prev) }
                    updateSelectedLayerLive(curImg.copy(uriString = savedPath))
                }
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "img_crop") {
            val applied = if (cropFeatureEnabled) applyImageLayerCrop() else false
            canvasStage.clearCropState()
            if (applied) {
                canvasStage.fitToScreen()
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "bg_crop") {
            val applied = if (cropFeatureEnabled) applyCanvasCrop() else false
            canvasStage.clearCropState()
            if (applied) {
                canvasStage.fitToScreen()
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else if (tool == "bezier_edit") {
            val curBez = getSelectedLayer() as? BezierLayer
            if (curBez != null && curBez.anchors.isEmpty()) {
                snapshotBeforeEdit?.let { prev ->
                    updateProjectLive(prev)
                }
                onRestoreSelection(prevSel)
            } else {
                val prev = snapshotBeforeEdit
                if (prev != null && prev != getProject()) {
                    pushHistory(prev)
                }
            }
        } else {
            val prev = snapshotBeforeEdit
            if (prev != null && prev != getProject()) {
                pushHistory(prev)
            }
        }
        snapshotBeforeEdit = null
        snapshotSelectedId = null
        closePanel()
    }

    fun isOpen(): Boolean = binding.optionExpandPanel.visibility == View.VISIBLE

    fun refreshActiveTool() {
        val id = activeToolId ?: return
        if (!isOpen()) return
        binding.optionExpandBody.removeAllViews()
        syncFloatingBars(id)
        populateTool(id)
    }

    fun applyTheme() {
        if (isOpen()) {
            refreshActiveTool()
        } else {
            syncFloatingBars(null)
        }
    }

    private fun usesFloatingTopBar(toolId: String?): Boolean =
        toolId == "img_warp" || toolId == "img_cutout" || toolId == "img_retouch"

    private fun syncFloatingBars(toolId: String?) {
        val dark = isDark()
        if (!usesFloatingTopBar(toolId)) {
            binding.floatingToolBar.visibility = View.GONE
            binding.floatingCutoutHud.visibility = View.GONE
            return
        }

        binding.floatingToolBar.visibility = View.VISIBLE
        binding.floatingToolBar.background = ThemeManager.floatingBarDrawable(context, dark, radiusDp = 999)
        binding.btnToolApply.background = GradientDrawable().apply {
            setColor(Color.parseColor("#2F6BFF"))
            cornerRadius = dp(999).toFloat()
        }
        binding.btnToolApply.setColorFilter(Color.WHITE)
        binding.btnToolCancel.setColorFilter(Color.parseColor("#EF4444"))
        binding.btnToolReset.setColorFilter(ThemeManager.textPrimary(dark))

        binding.btnToolApply.setOnClickListener { commitCurrentTool() }
        binding.btnToolCancel.setOnClickListener { cancelCurrentTool() }
        binding.btnToolReset.setOnClickListener {
            when (activeToolId) {
                "img_warp" -> canvasStage.resetWarp()
                "img_cutout" -> canvasStage.resetCutout()
                "img_retouch" -> canvasStage.resetRetouch()
            }
        }

        if (toolId == "img_cutout") {
            binding.floatingCutoutHud.visibility = View.VISIBLE
            binding.floatingCutoutHudCard.background = ThemeManager.floatingBarDrawable(context, dark, radiusDp = 999)
            syncCutoutHudVisuals()

            binding.btnHudClosePoly.setOnClickListener {
                if (canvasStage.closePolygonCutoutSelection()) {
                    syncCutoutHudVisuals()
                }
            }
            binding.btnHudKeepIn.setOnClickListener {
                if (canvasStage.keepInsideCutout()) {
                    syncCutoutHudVisuals()
                }
            }
            binding.btnHudEraseIn.setOnClickListener {
                if (canvasStage.eraseInsideCutout()) {
                    syncCutoutHudVisuals()
                }
            }
            binding.btnHudCopyLayer.setOnClickListener {
                val extracted = canvasStage.extractCutoutAsNewImageLayer()
                if (extracted != null) {
                    if (addLayerKeepSelection != null) {
                        addLayerKeepSelection.invoke(extracted)
                    } else {
                        val cur = getProject()
                        pushHistory(cur)
                        updateProjectLive(cur.copy(layers = cur.layers + extracted))
                    }
                    Toast.makeText(context, "Copied selection to new layer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Make a selection first", Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnHudCutLayer.setOnClickListener {
                val extracted = canvasStage.cutToNewImageLayer()
                if (extracted != null) {
                    if (addLayerKeepSelection != null) {
                        addLayerKeepSelection.invoke(extracted)
                    } else {
                        val cur = getProject()
                        pushHistory(cur)
                        updateProjectLive(cur.copy(layers = cur.layers + extracted))
                    }
                    syncCutoutHudVisuals()
                    Toast.makeText(context, "Cut selection to new layer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Make a selection first", Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnHudInvert.setOnClickListener {
                canvasStage.invertCutoutSelection()
                syncCutoutHudVisuals()
            }
            binding.btnHudClear.setOnClickListener {
                canvasStage.clearCutoutSelection()
                syncCutoutHudVisuals()
            }
        } else {
            binding.floatingCutoutHud.visibility = View.GONE
        }
    }

    private fun syncCutoutHudVisuals() {
        val dark = isDark()
        val hasMask = canvasStage.hasCutoutSelection()
        val polyCount = canvasStage.cutoutPolyPointCount()
        val accent = Color.parseColor("#2F6BFF")
        val textPri = ThemeManager.textPrimary(dark)
        val textMuted = ThemeManager.textSecondary(dark)

        fun styleHudChip(tv: TextView, enabled: Boolean, primary: Boolean = false) {
            tv.isEnabled = enabled
            tv.alpha = if (enabled) 1f else 0.38f
            tv.setTextColor(if (primary && enabled) Color.WHITE else if (enabled) textPri else textMuted)
            tv.background = if (primary && enabled) {
                GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(999).toFloat()
                }
            } else {
                ThemeManager.pillDrawable(context, dark, active = false)
            }
        }

        val showClosePoly = canvasStage.cutoutMode == CutoutEngine.CutoutMode.POLYGON && polyCount >= 3
        binding.btnHudClosePoly.visibility = if (showClosePoly) View.VISIBLE else View.GONE
        styleHudChip(binding.btnHudClosePoly, enabled = showClosePoly, primary = true)

        styleHudChip(binding.btnHudKeepIn, enabled = hasMask, primary = hasMask)
        styleHudChip(binding.btnHudEraseIn, enabled = hasMask)
        styleHudChip(binding.btnHudCopyLayer, enabled = hasMask)
        styleHudChip(binding.btnHudCutLayer, enabled = hasMask)
        styleHudChip(binding.btnHudInvert, enabled = hasMask)
        styleHudChip(binding.btnHudClear, enabled = hasMask || polyCount > 0)
    }

    fun closePanel(immediate: Boolean = false) {
        hidePreviewToken?.let { binding.optionExpandPanel.removeCallbacks(it) }
        hidePreviewToken = null
        canvasStage.clearCropState()
        canvasStage.clearWarpState()
        canvasStage.clearCutoutState()
        canvasStage.clearRetouchState()
        canvasStage.onCutoutStateChanged = null
        canvasStage.onPolygonPointsChanged = null
        canvasStage.toolMode = CanvasStageView.StageToolMode.NORMAL
        canvasStage.onEyedropperColor = null
        activeToolId = null
        snapshotBeforeEdit = null
        cropFeatureEnabled = true
        cropStraightenBarOpen = false
        syncFloatingBars(null)
        onFloatingToolVisibilityChanged?.invoke(false)
        val gen = ++animGen
        binding.optionExpandPanel.animate().cancel()
        binding.bottomControls.animate().cancel()

        if (immediate || binding.optionExpandPanel.visibility != View.VISIBLE) {
            binding.optionExpandBody.removeAllViews()
            binding.optionExpandPanel.visibility = View.GONE
            binding.optionExpandPanel.translationY = 0f
            binding.optionExpandPanel.alpha = 1f
            binding.bottomControls.visibility = View.VISIBLE
            binding.bottomControls.translationY = 0f
            binding.bottomControls.alpha = 1f
            onToolClosed()
            return
        }

        val targetHeight = binding.optionExpandPanel.height.takeIf { it > 0 }
            ?: binding.bottomControls.height.takeIf { it > 0 }
            ?: dp(146)
        binding.optionExpandPanel.translationY = 0f
        binding.optionExpandPanel.alpha = 1f
        binding.bottomControls.visibility = View.VISIBLE
        binding.bottomControls.translationY = targetHeight.toFloat()
        binding.bottomControls.alpha = 1f
        binding.bottomControls.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                if (animGen == gen) {
                    binding.optionExpandBody.removeAllViews()
                    binding.optionExpandPanel.visibility = View.GONE
                    binding.optionExpandPanel.translationY = 0f
                    binding.optionExpandPanel.alpha = 1f
                }
            }
            .start()

        onToolClosed()
    }

    fun openTool(
        toolId: String,
        @Suppress("UNUSED_PARAMETER") title: String,
        snapshotOverride: ProjectState? = null,
        selectedIdOverride: String? = null
    ) {
        if (toolId == "img_crop" && getSelectedLayer() !is ImageLayer) return
        if (toolId == "img_warp" && getSelectedLayer() !is ImageLayer) return
        if (toolId == "img_cutout" && getSelectedLayer() !is ImageLayer) return
        if (toolId == "img_retouch" && getSelectedLayer() !is ImageLayer) return

        val wasOpen = isOpen() && activeToolId != null
        val sameCropTool = wasOpen && activeToolId == toolId && (toolId == "img_crop" || toolId == "bg_crop")
        val sameWarpTool = wasOpen && activeToolId == "img_warp" && toolId == "img_warp"
        val sameCutoutTool = wasOpen && activeToolId == "img_cutout" && toolId == "img_cutout"
        val sameRetouchTool = wasOpen && activeToolId == "img_retouch" && toolId == "img_retouch"
        if (!wasOpen) {
            snapshotBeforeEdit = snapshotOverride ?: getProject()
            snapshotSelectedId = selectedIdOverride ?: getSelectedLayer()?.id
        }

        binding.optionExpandPanel.animate().cancel()
        binding.bottomControls.animate().cancel()

        val targetHeight = binding.bottomControls.height.takeIf { it > 0 } ?: dp(146)
        binding.optionExpandPanel.updateLayoutParams { height = targetHeight }

        if (toolId == "img_crop" || toolId == "bg_crop") {
            canvasStage.clearWarpState()
            canvasStage.clearCutoutState()
            canvasStage.clearRetouchState()
            if (!sameCropTool) {
                cropFeatureEnabled = true
                cropStraightenBarOpen = false
                canvasStage.initCrop(
                    if (toolId == "img_crop") CanvasStageView.CropTarget.IMAGE_LAYER
                    else CanvasStageView.CropTarget.CANVAS
                )
            }
        } else if (toolId == "img_warp") {
            cropFeatureEnabled = true
            cropStraightenBarOpen = false
            canvasStage.clearCropState()
            canvasStage.clearCutoutState()
            canvasStage.clearRetouchState()
            if (!sameWarpTool) {
                val img = getSelectedLayer() as? ImageLayer ?: return
                if (!canvasStage.initWarp(img)) {
                    Toast.makeText(context, "Unable to decode image for Warp", Toast.LENGTH_SHORT).show()
                    return
                }
                warpControlMode = "size"
            }
        } else if (toolId == "img_cutout") {
            cropFeatureEnabled = true
            cropStraightenBarOpen = false
            canvasStage.clearCropState()
            canvasStage.clearWarpState()
            canvasStage.clearRetouchState()
            if (!sameCutoutTool) {
                val img = getSelectedLayer() as? ImageLayer ?: return
                if (!canvasStage.initCutout(img)) {
                    Toast.makeText(context, "Unable to decode image for Cutout", Toast.LENGTH_SHORT).show()
                    return
                }
                cutoutSubPanel = "op"
            }
        } else if (toolId == "img_retouch") {
            cropFeatureEnabled = true
            cropStraightenBarOpen = false
            canvasStage.clearCropState()
            canvasStage.clearWarpState()
            canvasStage.clearCutoutState()
            if (!sameRetouchTool) {
                val img = getSelectedLayer() as? ImageLayer ?: return
                if (!canvasStage.initRetouch(img)) {
                    Toast.makeText(context, "Unable to decode image for Retouch", Toast.LENGTH_SHORT).show()
                    return
                }
                retouchControlMode = "size"
            }
        } else {
            cropFeatureEnabled = true
            cropStraightenBarOpen = false
            canvasStage.clearCropState()
            canvasStage.clearWarpState()
            canvasStage.clearCutoutState()
            canvasStage.clearRetouchState()
        }

        activeToolId = toolId
        binding.optionExpandBody.setPadding(0, 0, 0, 0)
        binding.optionExpandBody.removeAllViews()

        if (!wasOpen) {
            val gen = ++animGen
            // Keep optionExpandPanel stationary behind bottomControls while bottomControls slides smoothly downward
            binding.optionExpandPanel.visibility = View.VISIBLE
            binding.optionExpandPanel.translationY = 0f
            binding.optionExpandPanel.alpha = 1f

            binding.bottomControls.visibility = View.VISIBLE
            binding.bottomControls.translationY = 0f
            binding.bottomControls.alpha = 1f
            binding.bottomControls.animate()
                .translationY(targetHeight.toFloat())
                .alpha(1f)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    if (animGen == gen && binding.optionExpandPanel.visibility == View.VISIBLE) {
                        binding.bottomControls.visibility = View.INVISIBLE
                    }
                }
                .start()
        } else {
            binding.optionExpandPanel.visibility = View.VISIBLE
            binding.optionExpandPanel.translationY = 0f
            binding.optionExpandPanel.alpha = 1f
            binding.bottomControls.visibility = View.INVISIBLE
        }

        canvasStage.toolMode = when (toolId) {
            "draw" -> CanvasStageView.StageToolMode.DRAW
            "layer_perspective" -> CanvasStageView.StageToolMode.PERSPECTIVE
            "layer_mask" -> CanvasStageView.StageToolMode.MASK
            "bezier_edit" -> CanvasStageView.StageToolMode.BEZIER
            "img_eraser" -> CanvasStageView.StageToolMode.BITMAP_ERASER
            "img_warp" -> CanvasStageView.StageToolMode.WARP
            "img_cutout" -> CanvasStageView.StageToolMode.CUTOUT
            "img_retouch" -> CanvasStageView.StageToolMode.RETOUCH
            "eyedropper" -> CanvasStageView.StageToolMode.EYEDROPPER
            "img_crop", "bg_crop" -> if (cropFeatureEnabled) CanvasStageView.StageToolMode.CROP else CanvasStageView.StageToolMode.NORMAL
            else -> CanvasStageView.StageToolMode.NORMAL
        }

        if (toolId == "img_cutout") {
            canvasStage.onCutoutStateChanged = { _, _ -> syncCutoutHudVisuals() }
            canvasStage.onPolygonPointsChanged = { _ -> syncCutoutHudVisuals() }
        } else {
            canvasStage.onCutoutStateChanged = null
            canvasStage.onPolygonPointsChanged = null
        }

        syncFloatingBars(toolId)
        onFloatingToolVisibilityChanged?.invoke(true)
        populateTool(toolId)
    }

    private inner class ConditionalScrollView(
        ctx: Context,
        private val preferCenter: Boolean = true
    ) : ScrollView(ctx) {
        var canScrollVerticallyNow: Boolean = false

        init {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return if (canScrollVerticallyNow) super.onInterceptTouchEvent(ev) else false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!canScrollVerticallyNow && ev.actionMasked == MotionEvent.ACTION_UP) {
                performClick()
            }
            return if (canScrollVerticallyNow) super.onTouchEvent(ev) else true
        }

        fun updateScrollability() {
            val child = getChildAt(0) as? LinearLayout ?: return
            val h = height
            if (h <= 0) return
            var contentH = child.paddingTop + child.paddingBottom
            for (i in 0 until child.childCount) {
                val c = child.getChildAt(i)
                if (c.visibility != View.GONE) {
                    val lp = c.layoutParams as? LinearLayout.LayoutParams
                    contentH += c.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                }
            }
            val overflow = contentH > h + 2
            val desiredGravity = if (overflow) Gravity.TOP else if (preferCenter) Gravity.CENTER_VERTICAL else Gravity.TOP
            if (child.gravity != desiredGravity) {
                child.gravity = desiredGravity
            }
            if (overflow != canScrollVerticallyNow) {
                canScrollVerticallyNow = overflow
                isVerticalScrollBarEnabled = overflow
                overScrollMode = if (overflow) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER
                if (!overflow && scrollY != 0) scrollTo(0, 0)
            }
        }
    }

    private fun buildPopupShell(
        body: LinearLayout,
        centerVertically: Boolean = true,
        showActionCol: Boolean = true,
        buildLeft: (LinearLayout) -> Unit
    ) {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(sheetBg)
        }

        val scroll = ConditionalScrollView(context, centerVertically).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val leftCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (centerVertically) Gravity.CENTER_VERTICAL else Gravity.TOP
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        buildLeft(leftCol)
        scroll.addView(leftCol)

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            scroll.updateScrollability()
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (scroll.viewTreeObserver.isAlive) {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                }
            }
        })

        outer.addView(scroll)

        if (showActionCol) {
            val actionCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(sageColBg)
            }
            val btnOk = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                background = null
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener { commitCurrentTool() }
            }
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(sageDivider)
            }
            val btnCancel = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                background = null
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener { cancelCurrentTool() }
            }
            actionCol.addView(btnOk)
            actionCol.addView(divider)
            actionCol.addView(btnCancel)
            outer.addView(actionCol)
        }
        body.addView(outer)
    }

    private fun populateTool(toolId: String) {
        val body = binding.optionExpandBody
        val sel = getSelectedLayer()
        val proj = getProject()

        when (toolId) {
            "text_styles" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    val assetStyles = AssetRepository.loadTextStyles(context)
                    if (assetStyles.isNotEmpty()) {
                        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        for (style in assetStyles) {
                            val thumbBmp = style.thumbUri?.let {
                                PixelLabRenderer.loadBitmap(context, it)
                            }
                            if (thumbBmp != null) {
                                val iv = ImageView(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(46)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    background = cardPillDrawable(false)
                                    setPadding(dp(4), dp(4), dp(4), dp(4))
                                    setImageBitmap(thumbBmp)
                                    setOnClickListener {
                                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                                            val s = style.template
                                            val applied = cur.copy(
                                                fontName = s.fontName,
                                                bold = s.bold,
                                                italic = s.italic,
                                                underline = s.underline,
                                                strikethrough = s.strikethrough,
                                                letterSpacing = s.letterSpacing,
                                                fillType = s.fillType,
                                                color = s.color,
                                                gradient = s.gradient,
                                                textureUri = s.textureUri,
                                                stroke = s.stroke,
                                                shadow = s.shadow,
                                                innerShadow = s.innerShadow,
                                                emboss = s.emboss,
                                                background = s.background,
                                                threeD = s.threeD,
                                                reflection = s.reflection
                                            )
                                            val (nw, nh) = PixelLabRenderer.measureTextBounds(applied, context)
                                            updateSelectedLayerLive(applied.copy(width = nw, height = nh))
                                        }
                                    }
                                }
                                row.addView(iv)
                            }
                        }
                        hs.addView(row)
                        left.addView(hs)
                    }
                    addPillButtonRow(
                        left,
                        listOf(
                            "Default" to {
                                val reset = tl.copy(
                                    fontName = "Sans Regular",
                                    bold = false,
                                    italic = false,
                                    underline = false,
                                    strikethrough = false,
                                    fillType = FillType.COLOR,
                                    color = Color.WHITE,
                                    textureUri = null,
                                    stroke = tl.stroke.copy(enabled = false),
                                    shadow = tl.shadow.copy(enabled = false),
                                    innerShadow = tl.innerShadow.copy(enabled = false),
                                    emboss = tl.emboss.copy(enabled = false),
                                    background = tl.background.copy(enabled = false),
                                    threeD = tl.threeD.copy(enabled = false),
                                    reflection = tl.reflection.copy(enabled = false)
                                )
                                val (nw, nh) = PixelLabRenderer.measureTextBounds(reset, context)
                                updateSelectedLayerLive(reset.copy(width = nw, height = nh))
                            },
                            "Gradient Pop" to {
                                updateSelectedLayerLive(
                                    tl.copy(
                                        fillType = FillType.GRADIENT,
                                        gradient = AppDefaults.defaultGradients[8],
                                        stroke = tl.stroke.copy(enabled = true, color = Color.BLACK, width = 5f)
                                    )
                                )
                            },
                            "3D Block" to {
                                updateSelectedLayerLive(
                                    tl.copy(threeD = tl.threeD.copy(enabled = true, depth = 18, colorAuto = true))
                                )
                            },
                            "Neon" to {
                                updateSelectedLayerLive(
                                    tl.copy(
                                        fillType = FillType.COLOR,
                                        color = Color.parseColor("#F0ABFC"),
                                        shadow = tl.shadow.copy(
                                            enabled = true, color = Color.parseColor("#E879F9"),
                                            blur = 28f, offsetX = 0f, offsetY = 0f
                                        )
                                    )
                                )
                            }
                        )
                    )
                }
            }

            "position" -> {
                if (sel == null) return
                var step = 7
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "move by", 1, 60, step, { "${it}px" }) { step = it }
                    val btnRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(8), 0, dp(2))
                    }
                    btnRow.addView(createCircleIconButton(R.drawable.ic_chevron_left) {
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(x = it.x - step)) }
                    })
                    btnRow.addView(createCircleIconButton(R.drawable.ic_chevron_right) {
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(x = it.x + step)) }
                    })
                    btnRow.addView(createCircleIconButton(R.drawable.ic_chevron_up) {
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(y = it.y - step)) }
                    })
                    btnRow.addView(createCircleIconButton(R.drawable.ic_chevron_down) {
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(y = it.y + step)) }
                    })
                    left.addView(btnRow)
                }
            }

            "rel_position" -> {
                if (sel == null) return
                buildPopupShell(body) { left ->
                    fun align(target: LayerTransformMath.AlignTarget) {
                        getSelectedLayer()?.let { cur ->
                            val (nx, ny) = LayerTransformMath.alignLayerToCanvasBounds(
                                curX = cur.x,
                                curY = cur.y,
                                width = cur.width,
                                height = cur.height,
                                rotationDeg = cur.rotation,
                                canvasWidth = proj.width,
                                canvasHeight = proj.height,
                                target = target
                            )
                            updateSelectedLayerLive(cur.copyBase(x = nx, y = ny))
                        }
                    }

                    val row1 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(2), 0, dp(6))
                    }
                    row1.addView(createCircleIconButton(R.drawable.ic_align_edge_left) {
                        align(LayerTransformMath.AlignTarget.LEFT)
                    })
                    row1.addView(createCircleIconButton(R.drawable.ic_align_center_h) {
                        align(LayerTransformMath.AlignTarget.CENTER_H)
                    })
                    row1.addView(createCircleIconButton(R.drawable.ic_align_edge_right) {
                        align(LayerTransformMath.AlignTarget.RIGHT)
                    })

                    val row2 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(4), 0, dp(2))
                    }
                    row2.addView(createCircleIconButton(R.drawable.ic_align_edge_top) {
                        align(LayerTransformMath.AlignTarget.TOP)
                    })
                    row2.addView(createCircleIconButton(R.drawable.ic_align_center_v) {
                        align(LayerTransformMath.AlignTarget.CENTER_V)
                    })
                    row2.addView(createCircleIconButton(R.drawable.ic_align_edge_bottom) {
                        align(LayerTransformMath.AlignTarget.BOTTOM)
                    })

                    left.addView(row1)
                    left.addView(row2)
                }
            }

            "rel_size" -> {
                val l = sel ?: return
                val maxPct = 250
                var maintainRatio = true
                var lockedAspect = (l.width / l.height.coerceAtLeast(1f)).coerceAtLeast(0.01f)
                var wPct = ((l.width / proj.width.coerceAtLeast(1)) * 100f).roundToInt().coerceIn(1, maxPct)
                var hPct = ((l.height / proj.height.coerceAtLeast(1)) * 100f).roundToInt().coerceIn(1, maxPct)
                var syncing = false

                buildPopupShell(body, centerVertically = true) { left ->
                    lateinit var wSeek: SeekBar
                    lateinit var hSeek: SeekBar
                    lateinit var wValTv: TextView
                    lateinit var hValTv: TextView
                    lateinit var btnFreeMode: TextView
                    lateinit var btnLockMode: TextView
                    lateinit var btnSquareLock: TextView

                    fun applyResize(newW: Float, newH: Float) {
                        getSelectedLayer()?.let { cur ->
                            val updated = if (cur is TextLayer) {
                                val hScale = newH / cur.height.coerceAtLeast(1f)
                                cur.copy(
                                    fontSize = (cur.fontSize * hScale).coerceIn(8f, 1200f),
                                    width = newW,
                                    height = newH
                                )
                            } else {
                                cur.copyBase(width = newW, height = newH)
                            }
                            updateSelectedLayerLive(updated)
                        }
                    }

                    fun refreshModeButtons() {
                        val d = isDark()
                        val txtPri = ThemeManager.textPrimary(d)
                        val cardBgCol = ThemeManager.cardBg(d)
                        val borderCol = ThemeManager.borderColor(d)
                        btnFreeMode.setTextColor(if (!maintainRatio) Color.WHITE else txtPri)
                        btnFreeMode.background = GradientDrawable().apply {
                            setColor(if (!maintainRatio) tealColor else cardBgCol)
                            cornerRadius = dp(6).toFloat()
                            setStroke(dp(1), if (!maintainRatio) tealColor else borderCol)
                        }
                        btnLockMode.setTextColor(if (maintainRatio) Color.WHITE else txtPri)
                        btnLockMode.background = GradientDrawable().apply {
                            setColor(if (maintainRatio) tealColor else cardBgCol)
                            cornerRadius = dp(6).toFloat()
                            setStroke(dp(1), if (maintainRatio) tealColor else borderCol)
                        }
                        val isOneToOne = maintainRatio && abs(lockedAspect - 1f) < 0.02f
                        btnSquareLock.setTextColor(if (isOneToOne) Color.WHITE else tealColor)
                        btnSquareLock.background = GradientDrawable().apply {
                            setColor(if (isOneToOne) accentBlue else cardBgCol)
                            cornerRadius = dp(6).toFloat()
                            setStroke(dp(1), if (isOneToOne) accentBlue else tealColor)
                        }
                    }

                    val modeBar = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), 0, dp(2), dp(4))
                    }
                    btnFreeMode = TextView(context).apply {
                        text = "Free Resizing"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(4)
                        }
                        setOnClickListener {
                            maintainRatio = false
                            refreshModeButtons()
                        }
                    }
                    btnLockMode = TextView(context).apply {
                        text = "Locked Ratio"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(4)
                        }
                        setOnClickListener {
                            maintainRatio = true
                            getSelectedLayer()?.let { cur ->
                                lockedAspect = (cur.width / cur.height.coerceAtLeast(1f)).coerceAtLeast(0.01f)
                            }
                            refreshModeButtons()
                        }
                    }
                    btnSquareLock = TextView(context).apply {
                        text = "1:1 Lock"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        setOnClickListener {
                            maintainRatio = true
                            lockedAspect = 1f
                            getSelectedLayer()?.let { cur ->
                                val side = cur.width.coerceAtLeast(20f)
                                wPct = ((side / proj.width.coerceAtLeast(1)) * 100f).roundToInt().coerceIn(1, maxPct)
                                hPct = ((side / proj.height.coerceAtLeast(1)) * 100f).roundToInt().coerceIn(1, maxPct)
                                syncing = true
                                wSeek.progress = wPct - 1
                                hSeek.progress = hPct - 1
                                wValTv.text = "${wPct}%"
                                hValTv.text = "${hPct}%"
                                syncing = false
                                applyResize(side, side)
                            }
                            refreshModeButtons()
                        }
                    }
                    modeBar.addView(btnFreeMode)
                    modeBar.addView(btnLockMode)
                    modeBar.addView(btnSquareLock)
                    left.addView(modeBar)
                    refreshModeButtons()

                    fun applyFromWidthPct(targetW: Int) {
                        val cur = getSelectedLayer() ?: return
                        val res = LayerTransformMath.resizeFromWidthPct(
                            widthPct = targetW,
                            curHeightPx = cur.height,
                            canvasWidth = proj.width,
                            canvasHeight = proj.height,
                            maintainRatio = maintainRatio,
                            lockedAspect = lockedAspect,
                            maxPct = maxPct
                        )
                        wPct = res.widthPct
                        hPct = res.heightPct
                        syncing = true
                        wSeek.progress = wPct - 1
                        wValTv.text = "${wPct}%"
                        if (maintainRatio) {
                            hSeek.progress = hPct - 1
                            hValTv.text = "${hPct}%"
                        }
                        syncing = false
                        applyResize(res.widthPx, res.heightPx)
                    }

                    fun applyFromHeightPct(targetH: Int) {
                        val cur = getSelectedLayer() ?: return
                        val res = LayerTransformMath.resizeFromHeightPct(
                            heightPct = targetH,
                            curWidthPx = cur.width,
                            canvasWidth = proj.width,
                            canvasHeight = proj.height,
                            maintainRatio = maintainRatio,
                            lockedAspect = lockedAspect,
                            maxPct = maxPct
                        )
                        wPct = res.widthPct
                        hPct = res.heightPct
                        syncing = true
                        hSeek.progress = hPct - 1
                        hValTv.text = "${hPct}%"
                        if (maintainRatio) {
                            wSeek.progress = wPct - 1
                            wValTv.text = "${wPct}%"
                        }
                        syncing = false
                        applyResize(res.widthPx, res.heightPx)
                    }

                    fun buildCompactPctRow(
                        label: String,
                        initPct: Int,
                        onStep: (Int) -> Unit,
                        onBind: (SeekBar, TextView) -> Unit
                    ) {
                        val block = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, dp(1), 0, dp(2))
                        }
                        val d = isDark()
                        val lblRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(2), 0, dp(2), 0)
                        }
                        lblRow.addView(TextView(context).apply {
                            text = "$label : "
                            textSize = 12f
                            setTextColor(ThemeManager.textSecondary(d))
                        })
                        val tvVal = TextView(context).apply {
                            text = "${initPct}%"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(accentBlue)
                        }
                        lblRow.addView(tvVal)
                        block.addView(lblRow)

                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val seek = SeekBar(context).apply {
                            max = maxPct - 1
                            progress = (initPct - 1).coerceIn(0, maxPct - 1)
                            minimumHeight = 0
                            ThemeManager.applySeekBarTheme(this, d)
                            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                                    if (fromUser && !syncing) {
                                        onStep(p + 1)
                                    }
                                }
                                override fun onStartTrackingTouch(sb: SeekBar?) {
                                    sb?.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                override fun onStopTrackingTouch(sb: SeekBar?) {
                                    sb?.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            })
                        }
                        val btnMinus = ImageButton(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                            background = ThemeManager.circleButtonDrawable(context, d)
                            elevation = dp(1).toFloat()
                            setImageResource(R.drawable.ic_minus)
                            setColorFilter(ThemeManager.textPrimary(d))
                            setPadding(dp(5), dp(5), dp(5), dp(5))
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setOnClickListener {
                                val next = (seek.progress + 1 - 1).coerceIn(1, maxPct)
                                onStep(next)
                            }
                        }
                        val btnPlus = ImageButton(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                            background = ThemeManager.circleButtonDrawable(context, d)
                            elevation = dp(1).toFloat()
                            setImageResource(R.drawable.ic_plus_simple)
                            setColorFilter(ThemeManager.textPrimary(d))
                            setPadding(dp(5), dp(5), dp(5), dp(5))
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setOnClickListener {
                                val next = (seek.progress + 1 + 1).coerceIn(1, maxPct)
                                onStep(next)
                            }
                        }
                        row.addView(btnMinus)
                        row.addView(seek)
                        row.addView(btnPlus)
                        block.addView(row)
                        left.addView(block)
                        onBind(seek, tvVal)
                    }

                    buildCompactPctRow("width", wPct, ::applyFromWidthPct) { s, tv ->
                        wSeek = s
                        wValTv = tv
                    }
                    buildCompactPctRow("height", hPct, ::applyFromHeightPct) { s, tv ->
                        hSeek = s
                        hValTv = tv
                    }
                }
            }

            "text_size" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "size", 16, 450, tl.fontSize.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = cur.copy(fontSize = v.toFloat())
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                }
            }

            "text_padding" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "left padding", 0, 100, tl.paddingLeft.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = cur.copy(paddingLeft = v.toFloat())
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                    addPixelLabSlider(left, "right padding", 0, 100, tl.paddingRight.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = cur.copy(paddingRight = v.toFloat())
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                }
            }

            "text_color", "shape_color" -> {
                openFillSheet(body, isBackground = false)
            }

            "layer_radius" -> {
                openRadiusSheet(body, isBackground = false)
            }

            "bg_radius" -> {
                openRadiusSheet(body, isBackground = true)
            }

            "opacity" -> {
                val l = sel ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "opacity", 0, 100, l.opacity, { "$it%" }) { v ->
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(opacity = v)) }
                    }
                }
            }

            "rotate" -> {
                val l = sel ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "angle", -180, 180, l.rotation.roundToInt(), { "${it}°" }) { v ->
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(rotation = v.toFloat())) }
                    }
                }
            }

            "3d_rotate" -> {
                val l = sel ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "x axis", -60, 60, l.rotX.roundToInt(), { "${it}°" }) { v ->
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(rotX = v.toFloat())) }
                    }
                    addPixelLabSlider(left, "y axis", -60, 60, l.rotY.roundToInt(), { "${it}°" }) { v ->
                        getSelectedLayer()?.let { updateSelectedLayerLive(it.copyBase(rotY = v.toFloat())) }
                    }
                }
            }

            "text_style" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    fun applyStyleUpdate(transform: (TextLayer) -> TextLayer) {
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = transform(cur)
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                    fun rebuild() {
                        row.removeAllViews()
                        val cur = getSelectedLayer() as? TextLayer ?: return
                        row.addView(createCircleTextButton("B", cur.bold, Typeface.BOLD) {
                            applyStyleUpdate { it.copy(bold = !it.bold) }
                            rebuild()
                        })
                        row.addView(createCircleTextButton("I", cur.italic, Typeface.ITALIC) {
                            applyStyleUpdate { it.copy(italic = !it.italic) }
                            rebuild()
                        })
                        row.addView(createCircleTextButton("U", cur.underline, Typeface.NORMAL) {
                            applyStyleUpdate { it.copy(underline = !it.underline) }
                            rebuild()
                        })
                        row.addView(createCircleTextButton("S", cur.strikethrough, Typeface.NORMAL) {
                            applyStyleUpdate { it.copy(strikethrough = !it.strikethrough) }
                            rebuild()
                        })
                    }
                    rebuild()
                    left.addView(row)
                }
            }

            "text_curve" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "bending", -100, 100, tl.curve, { "${it}°" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(curve = v)) }
                    }
                }
            }

            "text_bg" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, tl.background.enabled) { en ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(background = it.background.copy(enabled = en))) }
                    }
                    addSwatchStrip(content, "Background Color") { c ->
                        (getSelectedLayer() as? TextLayer)?.let {
                            updateSelectedLayerLive(it.copy(background = it.background.copy(enabled = true, fillType = FillType.COLOR, color = c)))
                        }
                    }
                    addPixelLabSlider(content, "corner radius", 0, 60, tl.background.radius.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(background = it.background.copy(radius = v.toFloat()))) }
                    }
                }
            }

            "text_align" -> {
                if (sel !is TextLayer) return
                buildPopupShell(body) { left ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    fun rebuild() {
                        row.removeAllViews()
                        val cur = getSelectedLayer() as? TextLayer ?: return
                        row.addView(createCircleIconButton(R.drawable.ic_align_edge_left, cur.align == 0) {
                            (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(align = 0)) }
                            rebuild()
                        })
                        row.addView(createCircleIconButton(R.drawable.ic_align_center_h, cur.align == 1) {
                            (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(align = 1)) }
                            rebuild()
                        })
                        row.addView(createCircleIconButton(R.drawable.ic_align_edge_right, cur.align == 2) {
                            (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(align = 2)) }
                            rebuild()
                        })
                    }
                    rebuild()
                    left.addView(row)
                }
            }

            "text_spacing" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "letter spacing", -10, 80, (tl.letterSpacing * 100f).roundToInt(), { "$it" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = cur.copy(letterSpacing = v / 100f)
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                    addPixelLabSlider(left, "line spacing", 70, 220, (tl.lineSpacing * 100f).roundToInt(), { "$it" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { cur ->
                            val updated = cur.copy(lineSpacing = v / 100f)
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(updated, context)
                            updateSelectedLayerLive(updated.copy(width = nw, height = nh))
                        }
                    }
                }
            }

            "text_stroke", "shape_stroke" -> {
                val st = when (sel) {
                    is TextLayer -> sel.stroke
                    is ShapeLayer -> sel.stroke
                    is ArrowLayer -> sel.stroke
                    is ImageLayer -> sel.stroke
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, st.enabled) { en ->
                        updateStroke { it.copy(enabled = en) }
                    }
                    addSwatchStrip(content, "Stroke Color") { c ->
                        updateStroke { it.copy(enabled = true, color = c) }
                    }
                    addPixelLabSlider(content, "stroke width", 1, 32, st.width.toInt(), { "${it}px" }) { v ->
                        updateStroke { it.copy(enabled = true, width = v.toFloat()) }
                    }
                }
            }

            "text_shadow", "shape_shadow" -> {
                val sh = when (sel) {
                    is TextLayer -> sel.shadow
                    is ShapeLayer -> sel.shadow
                    is ArrowLayer -> sel.shadow
                    is ImageLayer -> sel.shadow
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, sh.enabled) { en ->
                        updateShadow { it.copy(enabled = en) }
                    }
                    addSwatchStrip(content, "Shadow Color") { c ->
                        updateShadow { it.copy(enabled = true, color = c) }
                    }
                    addPixelLabSlider(content, "blur radius", 0, 50, sh.blur.toInt(), { "$it" }) { v ->
                        updateShadow { it.copy(enabled = true, blur = v.toFloat()) }
                    }
                    addPixelLabSlider(content, "offset x", -40, 40, sh.offsetX.toInt(), { "$it" }) { v ->
                        updateShadow { it.copy(enabled = true, offsetX = v.toFloat()) }
                    }
                    addPixelLabSlider(content, "offset y", -40, 40, sh.offsetY.toInt(), { "$it" }) { v ->
                        updateShadow { it.copy(enabled = true, offsetY = v.toFloat()) }
                    }
                }
            }

            "text_3d", "shape_3d" -> {
                val td = when (sel) {
                    is TextLayer -> sel.threeD
                    is ShapeLayer -> sel.threeD
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, td.enabled) { en ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = en)))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = en)))
                            else -> Unit
                        }
                    }
                    addPixelLabSlider(content, "depth", 1, 45, td.depth, { "$it" }) { v ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, depth = v)))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, depth = v)))
                            else -> Unit
                        }
                    }
                    addPixelLabSlider(content, "angle", 0, 360, td.angle, { "${it}°" }) { v ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, angle = v)))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, angle = v)))
                            else -> Unit
                        }
                    }
                    addPixelLabSlider(content, "darken", 10, 85, td.darken, { "$it%" }) { v ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, darken = v)))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(threeD = cur.threeD.copy(enabled = true, darken = v)))
                            else -> Unit
                        }
                    }
                }
            }

            "text_emboss" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, tl.emboss.enabled) { en ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(emboss = it.emboss.copy(enabled = en))) }
                    }
                    addPixelLabSlider(content, "light angle", 0, 360, tl.emboss.lightAngle, { "${it}°" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(emboss = it.emboss.copy(enabled = true, lightAngle = v))) }
                    }
                    addPixelLabSlider(content, "bevel", 5, 60, tl.emboss.bevel, { "$it" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(emboss = it.emboss.copy(enabled = true, bevel = v))) }
                    }
                }
            }

            "text_reflection" -> {
                val tl = sel as? TextLayer ?: return
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, tl.reflection.enabled) { en ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(reflection = it.reflection.copy(enabled = en))) }
                    }
                    addPixelLabSlider(content, "vertical offset", -40, 80, tl.reflection.offset.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(reflection = it.reflection.copy(enabled = true, offset = v.toFloat()))) }
                    }
                    addPixelLabSlider(content, "opacity", 10, 90, tl.reflection.opacity, { "$it%" }) { v ->
                        (getSelectedLayer() as? TextLayer)?.let { updateSelectedLayerLive(it.copy(reflection = it.reflection.copy(enabled = true, opacity = v))) }
                    }
                }
            }

            "pick_shape" -> {
                val existingShape = sel as? ShapeLayer
                buildPopupShell(body) { left ->
                    addPillButtonRow(
                        left,
                        BoundedShapeLabel.entries.map { lbl ->
                            lbl.label to {
                                val cur = getSelectedLayer() as? ShapeLayer
                                if (cur != null) {
                                    updateSelectedLayerLive(cur.copy(shapeLabel = lbl, name = lbl.label))
                                } else {
                                    addLayer(
                                        ShapeLayer(
                                            id = "shape_${System.currentTimeMillis()}",
                                            name = lbl.label,
                                            x = proj.width / 2f,
                                            y = proj.height / 2f,
                                            shapeLabel = lbl
                                        )
                                    )
                                }
                            }
                        }
                    )
                    if (existingShape != null) {
                        addPixelLabSlider(left, "corner radius", 0, 100, existingShape.cornerRadius, { "$it%" }) { v ->
                            (getSelectedLayer() as? ShapeLayer)?.let {
                                updateSelectedLayerLive(it.copy(cornerRadius = v))
                            }
                        }
                    }
                }
            }

            "arrow_edit" -> {
                val al = sel as? ArrowLayer ?: return
                buildPopupShell(body) { left ->
                    addPillButtonRow(
                        left,
                        ArrowHeadStyle.entries.map { style ->
                            "End: ${style.name}" to {
                                (getSelectedLayer() as? ArrowLayer)?.let { updateSelectedLayerLive(it.copy(endStyle = style)) }
                            }
                        }
                    )
                    addPillButtonRow(
                        left,
                        ArrowHeadStyle.entries.map { style ->
                            "Start: ${style.name}" to {
                                (getSelectedLayer() as? ArrowLayer)?.let { updateSelectedLayerLive(it.copy(startStyle = style)) }
                            }
                        }
                    )
                    addPixelLabSlider(left, "stem width", 4, 60, al.stemWidth.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? ArrowLayer)?.let { updateSelectedLayerLive(it.copy(stemWidth = v.toFloat())) }
                    }
                    addPixelLabSlider(left, "head size", 16, 120, al.headSize.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? ArrowLayer)?.let { updateSelectedLayerLive(it.copy(headSize = v.toFloat())) }
                    }
                }
            }

            "draw" -> {
                val brush = canvasStage.brushConfig
                buildPopupShell(body) { left ->
                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 0, 0, dp(6))
                    }

                    val d = isDark()
                    val cardBgCol = ThemeManager.cardBg(d)
                    val borderCol = ThemeManager.borderColor(d)
                    val txtPri = ThemeManager.textPrimary(d)

                    val curPreset = BrushEngine.presetOf(brush.presetId)
                    val presetBtn = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            setColor(cardBgCol)
                            cornerRadius = dp(7).toFloat()
                            setStroke(dp(1), borderCol)
                        }
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                            marginEnd = dp(5)
                        }
                    }
                    val previewBmp = BrushEngine.drawBrushStrokePreview(dp(44), dp(22), curPreset, brush.color, brush.hardness)
                    presetBtn.addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(22)).apply { marginEnd = dp(6) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(previewBmp)
                    })
                    presetBtn.addView(TextView(context).apply {
                        text = curPreset.name
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(txtPri)
                        isSingleLine = true
                    })
                    presetBtn.setOnClickListener {
                        AdvanceDialogs.showBrushPickerDialog(context, brush.presetId, brush.color) { picked ->
                            brush.presetId = picked.id
                            brush.hardness = picked.defaultHardness
                            brush.eraser = false
                            canvasStage.toolMode = CanvasStageView.StageToolMode.DRAW
                            refreshActiveTool()
                        }
                    }
                    topRow.addView(presetBtn)

                    val btnMode = TextView(context).apply {
                        text = if (brush.eraser) "Erase" else "Paint"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (brush.eraser) Color.WHITE else txtPri)
                        background = GradientDrawable().apply {
                            setColor(if (brush.eraser) Color.parseColor("#EF4444") else cardBgCol)
                            cornerRadius = dp(7).toFloat()
                            setStroke(dp(1), borderCol)
                        }
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                            marginEnd = dp(5)
                        }
                        setOnClickListener {
                            brush.eraser = !brush.eraser
                            canvasStage.toolMode = CanvasStageView.StageToolMode.DRAW
                            refreshActiveTool()
                        }
                    }
                    topRow.addView(btnMode)

                    val btnColor = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(5) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(brush.color)
                            setStroke(dp(2), if (d) Color.parseColor("#52525B") else Color.WHITE)
                        }
                        elevation = dp(1).toFloat()
                        setOnClickListener {
                            ColorAndGradientDialogs.showColorPickerDialog(
                                context = context,
                                initialColor = brush.color,
                                project = proj,
                                onLiveChange = { c -> brush.color = c }
                            ) { c ->
                                brush.color = c
                                brush.eraser = false
                                refreshActiveTool()
                            }
                        }
                    }
                    topRow.addView(btnColor)

                    val btnEye = ImageButton(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(5) }
                        val eyeOn = canvasStage.toolMode == CanvasStageView.StageToolMode.EYEDROPPER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (eyeOn) tealColor else cardBgCol)
                            setStroke(dp(1), borderCol)
                        }
                        setImageResource(R.drawable.ic_eyedropper)
                        setColorFilter(if (eyeOn) Color.WHITE else txtPri)
                        setPadding(dp(7), dp(7), dp(7), dp(7))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setOnClickListener {
                            if (canvasStage.toolMode == CanvasStageView.StageToolMode.EYEDROPPER) {
                                canvasStage.onEyedropperColor = null
                                canvasStage.toolMode = CanvasStageView.StageToolMode.DRAW
                            } else {
                                canvasStage.onEyedropperColor = { c ->
                                    brush.color = c
                                    brush.eraser = false
                                    (btnColor.background as? GradientDrawable)?.setColor(c)
                                }
                                canvasStage.toolMode = CanvasStageView.StageToolMode.EYEDROPPER
                            }
                            refreshActiveTool()
                        }
                    }
                    topRow.addView(btnEye)

                    val btnUndo = TextView(context).apply {
                        text = "Undo"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(txtPri)
                        background = GradientDrawable().apply {
                            setColor(cardBgCol)
                            cornerRadius = dp(7).toFloat()
                            setStroke(dp(1), borderCol)
                        }
                        gravity = Gravity.CENTER
                        setPadding(dp(9), dp(6), dp(9), dp(6))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
                        setOnClickListener { canvasStage.undoPendingStroke() }
                    }
                    topRow.addView(btnUndo)
                    left.addView(topRow)

                    addPixelLabSlider(left, "size", 1, 250, brush.width.roundToInt(), { "${it}px" }) { v ->
                        brush.width = v.toFloat()
                    }
                    addPixelLabSlider(left, "hardness", 0, 100, brush.hardness, { "$it%" }) { v ->
                        brush.hardness = v
                    }
                    addPixelLabSlider(left, "opacity", 1, 100, brush.opacity, { "$it%" }) { v ->
                        brush.opacity = v
                    }
                    addPixelLabSlider(left, "flow", 1, 100, brush.flow, { "$it%" }) { v ->
                        brush.flow = v
                    }
                }
            }

            "img_crop" -> {
                buildCropToolUi(body, isImageLayer = true)
            }

            "img_filters" -> {
                val il = sel as? ImageLayer ?: return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "brightness", -100, 100, il.brightness, { "$it" }) { v ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(brightness = v)) }
                    }
                    addPixelLabSlider(left, "contrast", -80, 150, il.contrast, { "$it" }) { v ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(contrast = v)) }
                    }
                    addPixelLabSlider(left, "saturation", -100, 150, il.saturation, { "$it" }) { v ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(saturation = v)) }
                    }
                    addPixelLabSlider(left, "hue", -180, 180, il.hue, { "${it}°" }) { v ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(hue = v)) }
                    }
                }
            }

            "img_remove_color" -> {
                val il = sel as? ImageLayer ?: return
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, il.removeColorEnabled) { en ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(removeColorEnabled = en)) }
                    }
                    addPixelLabSlider(content, "tolerance", 1, 80, il.removeColorTolerance, { "$it%" }) { v ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(removeColorEnabled = true, removeColorTolerance = v)) }
                    }
                    addSwatchStrip(content, "Target Color") { c ->
                        (getSelectedLayer() as? ImageLayer)?.let { updateSelectedLayerLive(it.copy(removeColorEnabled = true, removeColorTarget = c)) }
                    }
                }
            }

            "bg_color" -> {
                openFillSheet(body, isBackground = true)
            }

            "bg_crop" -> {
                buildCropToolUi(body, isImageLayer = false)
            }

            "bg_presets" -> {
                buildPopupShell(body, centerVertically = true) { left ->
                    val actionHs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                    val actionRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 0, 0, dp(4))
                    }
                    fun addCompactAction(title: String, onClick: () -> Unit) {
                        val pill = TextView(context).apply {
                            text = title
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(accentBlue)
                            gravity = Gravity.CENTER
                            background = cardPillDrawable(false)
                            elevation = dp(1).toFloat()
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(dp(3), dp(1), dp(3), dp(1)) }
                            setOnClickListener { onClick() }
                        }
                        actionRow.addView(pill)
                    }

                    addCompactAction("+ Image") { onPickBgPresetImage() }
                    addCompactAction("+ Color") {
                        val prevBg = getProject().background
                        Dialogs.showColorPickerDialog(
                            context = context,
                            initialColor = prevBg.color,
                            project = getProject(),
                            onLiveChange = { picked ->
                                val p = getProject()
                                updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.COLOR, color = picked)))
                            },
                            onCancel = {
                                val p = getProject()
                                updateProjectLive(p.copy(background = prevBg))
                            }
                        ) { picked ->
                            val preset = SavedBackgroundPreset(
                                id = "bg_c_${System.currentTimeMillis()}",
                                name = "Color",
                                type = BackgroundType.COLOR,
                                color = picked,
                                isCustom = true
                            )
                            JsonPresetCodec.saveBgPreset(context, preset)
                            val p = getProject()
                            updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.COLOR, color = picked)))
                            refreshActiveTool()
                        }
                    }
                    addCompactAction("+ Gradient") {
                        val prevBg = getProject().background
                        Dialogs.showGradientMakerDialog(
                            context = context,
                            initial = prevBg.gradient,
                            project = getProject(),
                            onLiveChange = { grad ->
                                val p = getProject()
                                updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.GRADIENT, gradient = grad)))
                            },
                            onCancel = {
                                val p = getProject()
                                updateProjectLive(p.copy(background = prevBg))
                            }
                        ) { grad ->
                            val preset = SavedBackgroundPreset(
                                id = "bg_g_${System.currentTimeMillis()}",
                                name = "Gradient",
                                type = BackgroundType.GRADIENT,
                                gradient = grad,
                                isCustom = true
                            )
                            JsonPresetCodec.saveBgPreset(context, preset)
                            val p = getProject()
                            updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.GRADIENT, gradient = grad)))
                            refreshActiveTool()
                        }
                    }
                    addCompactAction("+ Save Current") {
                        val bg = getProject().background
                        when (bg.type) {
                            BackgroundType.TRANSPARENT -> {
                                Toast.makeText(context, "Set a color, gradient, or image background first", Toast.LENGTH_SHORT).show()
                            }
                            BackgroundType.COLOR -> {
                                JsonPresetCodec.saveBgPreset(
                                    context,
                                    SavedBackgroundPreset(
                                        id = "bg_cur_${System.currentTimeMillis()}",
                                        name = "Saved Color",
                                        type = BackgroundType.COLOR,
                                        color = bg.color,
                                        isCustom = true
                                    )
                                )
                                Toast.makeText(context, "Saved to Background Presets", Toast.LENGTH_SHORT).show()
                                refreshActiveTool()
                            }
                            BackgroundType.GRADIENT -> {
                                JsonPresetCodec.saveBgPreset(
                                    context,
                                    SavedBackgroundPreset(
                                        id = "bg_cur_${System.currentTimeMillis()}",
                                        name = "Saved Gradient",
                                        type = BackgroundType.GRADIENT,
                                        gradient = bg.gradient,
                                        isCustom = true
                                    )
                                )
                                Toast.makeText(context, "Saved to Background Presets", Toast.LENGTH_SHORT).show()
                                refreshActiveTool()
                            }
                            BackgroundType.IMAGE -> {
                                val srcPath = bg.imageUri
                                val savedPath = if (srcPath != null && File(srcPath).exists()) {
                                    val dest = File(context.filesDir, "bg_presets/preset_${System.currentTimeMillis()}.png").apply {
                                        parentFile?.mkdirs()
                                    }
                                    runCatching { File(srcPath).copyTo(dest, overwrite = true); dest.absolutePath }.getOrDefault(srcPath)
                                } else srcPath
                                if (savedPath != null) {
                                    JsonPresetCodec.saveBgPreset(
                                        context,
                                        SavedBackgroundPreset(
                                            id = "bg_cur_${System.currentTimeMillis()}",
                                            name = "Saved Image",
                                            type = BackgroundType.IMAGE,
                                            imageUri = savedPath,
                                            isCustom = true
                                        )
                                    )
                                    Toast.makeText(context, "Saved to Background Presets", Toast.LENGTH_SHORT).show()
                                    refreshActiveTool()
                                }
                            }
                        }
                    }
                    actionHs.addView(actionRow)
                    left.addView(actionHs)

                    val hs = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        setPadding(0, dp(4), 0, 0)
                    }
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val customPresets = JsonPresetCodec.loadSavedBgPresets(context)
                    val builtInPresets = AppDefaults.defaultBgPresets
                    val allPresets = customPresets + builtInPresets
                    val curBg = getProject().background

                    for (item in allPresets) {
                        val isActive = when (item.type) {
                            BackgroundType.COLOR -> curBg.type == BackgroundType.COLOR && curBg.color == item.color
                            BackgroundType.GRADIENT -> curBg.type == BackgroundType.GRADIENT &&
                                JsonPresetCodec.gradToWebJson(curBg.gradient).toString() == JsonPresetCodec.gradToWebJson(item.gradient).toString()
                            BackgroundType.IMAGE -> curBg.type == BackgroundType.IMAGE && curBg.imageUri == item.imageUri
                            else -> false
                        }

                        val cardWrap = FrameLayout(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                                setMargins(dp(4), dp(2), dp(4), dp(2))
                            }
                        }

                        val previewView = ImageView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            val strokeCol = if (isActive) accentBlue else Color.parseColor("#CBD5E1")
                            val strokeW = dp(if (isActive) 2 else 1)
                            when (item.type) {
                                BackgroundType.COLOR -> {
                                    background = GradientDrawable().apply {
                                        setColor(item.color)
                                        cornerRadius = dp(6).toFloat()
                                        setStroke(strokeW, strokeCol)
                                    }
                                }
                                BackgroundType.GRADIENT -> {
                                    val cols = if (item.gradient.stops.size >= 2) {
                                        item.gradient.stops.map { it.color }.toIntArray()
                                    } else intArrayOf(Color.BLACK, Color.WHITE)
                                    val orient = when (item.gradient.type) {
                                        GradientDirection.LINEAR_H -> GradientDrawable.Orientation.LEFT_RIGHT
                                        GradientDirection.LINEAR_DIAG -> GradientDrawable.Orientation.TL_BR
                                        else -> GradientDrawable.Orientation.TOP_BOTTOM
                                    }
                                    background = GradientDrawable(orient, cols).apply {
                                        cornerRadius = dp(6).toFloat()
                                        setStroke(strokeW, strokeCol)
                                    }
                                }
                                BackgroundType.IMAGE -> {
                                    background = GradientDrawable().apply {
                                        setColor(Color.parseColor("#E2E8F0"))
                                        cornerRadius = dp(6).toFloat()
                                        setStroke(strokeW, strokeCol)
                                    }
                                    clipToOutline = true
                                    item.imageUri?.let { uri ->
                                        PixelLabRenderer.loadBitmap(context, uri)?.let { setImageBitmap(it) }
                                    }
                                }
                                else -> Unit
                            }
                            setOnClickListener {
                                val p = getProject()
                                updateProjectLive(
                                    p.copy(
                                        background = p.background.copy(
                                            type = item.type,
                                            color = if (item.type == BackgroundType.COLOR) item.color else p.background.color,
                                            gradient = if (item.type == BackgroundType.GRADIENT) item.gradient else p.background.gradient,
                                            imageUri = if (item.type == BackgroundType.IMAGE) item.imageUri else p.background.imageUri
                                        )
                                    )
                                )
                                refreshActiveTool()
                            }
                        }
                        cardWrap.addView(previewView)

                        if (item.isCustom) {
                            val delBadge = TextView(context).apply {
                                text = "×"
                                textSize = 11f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                                gravity = Gravity.CENTER
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.argb(200, 220, 38, 38))
                                }
                                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                                    setMargins(0, dp(2), dp(2), 0)
                                }
                                setOnClickListener {
                                    JsonPresetCodec.deleteBgPreset(context, item.id)
                                    refreshActiveTool()
                                }
                            }
                            cardWrap.addView(delBadge)
                        }

                        row.addView(cardWrap)
                    }

                    val legacyBgs = AssetRepository.loadBackgrounds(context)
                    for (item in legacyBgs) {
                        val bmp = PixelLabRenderer.loadBitmap(context, item.thumbUri)
                        if (bmp != null) {
                            val iv = ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setImageBitmap(bmp)
                                setOnClickListener {
                                    val p = getProject()
                                    updateProjectLive(
                                        p.copy(background = p.background.copy(type = BackgroundType.IMAGE, imageUri = item.fullUri))
                                    )
                                    refreshActiveTool()
                                }
                            }
                            row.addView(iv)
                        }
                    }

                    hs.addView(row)
                    left.addView(hs)
                }
            }

            "fx_vignette" -> {
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, proj.effects.vignetteEnabled) { en ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(vignetteEnabled = en)))
                    }
                    addPixelLabSlider(content, "alpha", 0, 255, proj.effects.vignetteAlpha, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(vignetteEnabled = true, vignetteAlpha = v)))
                    }
                    addSwatchStrip(content, "Vignette Color") { c ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(vignetteEnabled = true, vignetteColor = c)))
                    }
                }
            }

            "fx_noise" -> {
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, proj.effects.noiseEnabled) { en ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(noiseEnabled = en)))
                    }
                    addPixelLabSlider(content, "alpha", 0, 200, proj.effects.noiseAlpha, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(noiseEnabled = true, noiseAlpha = v)))
                    }
                    addPixelLabSlider(content, "grain scale", 2, 20, proj.effects.noiseScale, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(noiseEnabled = true, noiseScale = v)))
                    }
                }
            }

            "fx_stripes" -> {
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, proj.effects.stripesEnabled) { en ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(stripesEnabled = en)))
                    }
                    addPixelLabSlider(content, "alpha", 0, 200, proj.effects.stripesAlpha, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(stripesEnabled = true, stripesAlpha = v)))
                    }
                    addPixelLabSlider(content, "repeat count", 2, 40, proj.effects.stripesRepeat, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(stripesEnabled = true, stripesRepeat = v)))
                    }
                }
            }

            "fx_brightness" -> {
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "brightness", -100, 100, proj.effects.brightness, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(brightness = v)))
                    }
                }
            }

            "fx_contrast" -> {
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "contrast", -80, 150, proj.effects.contrast, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(contrast = v)))
                    }
                }
            }

            "fx_hue" -> {
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "hue", -180, 180, proj.effects.hue, { "${it}°" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(hue = v)))
                    }
                }
            }

            "text_inner_shadow", "shape_inner_shadow" -> {
                val sh = when (sel) {
                    is TextLayer -> sel.innerShadow
                    is ShapeLayer -> sel.innerShadow
                    is ImageLayer -> sel.innerShadow
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, sh.enabled) { en ->
                        updateInnerShadow { it.copy(enabled = en) }
                    }
                    addSwatchStrip(content, "Inner Shadow Color") { c ->
                        updateInnerShadow { it.copy(enabled = true, color = c) }
                    }
                    addPixelLabSlider(content, "blur radius", 0, 40, sh.blur.toInt(), { "$it" }) { v ->
                        updateInnerShadow { it.copy(enabled = true, blur = v.toFloat()) }
                    }
                    addPixelLabSlider(content, "offset x", -30, 30, sh.offsetX.toInt(), { "$it" }) { v ->
                        updateInnerShadow { it.copy(enabled = true, offsetX = v.toFloat()) }
                    }
                    addPixelLabSlider(content, "offset y", -30, 30, sh.offsetY.toInt(), { "$it" }) { v ->
                        updateInnerShadow { it.copy(enabled = true, offsetY = v.toFloat()) }
                    }
                }
            }

            "text_texture", "shape_texture" -> {
                if (sel !is TextLayer && sel !is ShapeLayer) return
                buildPopupShell(body) { left ->
                    addPillButtonRow(
                        left,
                        listOf(
                            "Pick Texture Image" to { onPickTextureImage() },
                            "Remove Texture" to {
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(textureUri = null))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(textureUri = null))
                                    else -> Unit
                                }
                            }
                        )
                    )
                }
            }

            "layer_mask" -> {
                val (en, inv) = when (sel) {
                    is TextLayer -> sel.maskEnabled to sel.maskInvert
                    is ShapeLayer -> sel.maskEnabled to sel.maskInvert
                    is ImageLayer -> sel.maskEnabled to sel.maskInvert
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, en) { checked ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = checked))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = checked))
                            is ImageLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = checked))
                            else -> Unit
                        }
                    }
                    addPillButtonRow(
                        content,
                        listOf(
                            (if (inv) "Mode: Out (Inverted)" else "Mode: In") to {
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(maskInvert = !cur.maskInvert))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(maskInvert = !cur.maskInvert))
                                    is ImageLayer -> updateSelectedLayerLive(cur.copy(maskInvert = !cur.maskInvert))
                                    else -> Unit
                                }
                            },
                            "Undo Point" to {
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(maskPoints = cur.maskPoints.dropLast(1)))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(maskPoints = cur.maskPoints.dropLast(1)))
                                    is ImageLayer -> updateSelectedLayerLive(cur.copy(maskPoints = cur.maskPoints.dropLast(1)))
                                    else -> Unit
                                }
                            },
                            "Clear Mask" to {
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = false, maskPoints = emptyList()))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = false, maskPoints = emptyList()))
                                    is ImageLayer -> updateSelectedLayerLive(cur.copy(maskEnabled = false, maskPoints = emptyList()))
                                    else -> Unit
                                }
                            }
                        )
                    )
                }
            }

            "layer_perspective" -> {
                val en = when (sel) {
                    is TextLayer -> sel.perspectiveEnabled
                    is ShapeLayer -> sel.perspectiveEnabled
                    is ImageLayer -> sel.perspectiveEnabled
                    else -> return
                }
                buildPopupShell(body) { left ->
                    val content = addEnabledSection(left, en) { checked ->
                        when (val cur = getSelectedLayer()) {
                            is TextLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = checked))
                            is ShapeLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = checked))
                            is ImageLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = checked))
                            else -> Unit
                        }
                    }
                    addPillButtonRow(
                        content,
                        listOf(
                            "Reset Corners" to {
                                val reset = listOf(
                                    DrawPoint(0f, 0f),
                                    DrawPoint(1f, 0f),
                                    DrawPoint(1f, 1f),
                                    DrawPoint(0f, 1f)
                                )
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = false, perspectivePoints = reset))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = false, perspectivePoints = reset))
                                    is ImageLayer -> updateSelectedLayerLive(cur.copy(perspectiveEnabled = false, perspectivePoints = reset))
                                    else -> Unit
                                }
                            }
                        )
                    )
                }
            }

            "bezier_edit" -> {
                val bl = sel as? BezierLayer ?: return
                buildPopupShell(body) { left ->
                    addPillButtonRow(
                        left,
                        listOf(
                            "Add Point" to { canvasStage.bezierActionMode = 0 },
                            "Drag Point" to { canvasStage.bezierActionMode = 1 },
                            "Close Path" to {
                                (getSelectedLayer() as? BezierLayer)?.let { updateSelectedLayerLive(it.copy(closed = !it.closed)) }
                            },
                            "Fill" to {
                                (getSelectedLayer() as? BezierLayer)?.let { updateSelectedLayerLive(it.copy(fillEnabled = !it.fillEnabled)) }
                            },
                            "Undo Point" to {
                                (getSelectedLayer() as? BezierLayer)?.let { updateSelectedLayerLive(it.copy(anchors = it.anchors.dropLast(1))) }
                            }
                        )
                    )
                    addPixelLabSlider(left, "stroke width", 1, 36, bl.stroke.width.toInt(), { "${it}px" }) { v ->
                        (getSelectedLayer() as? BezierLayer)?.let {
                            updateSelectedLayerLive(it.copy(stroke = it.stroke.copy(enabled = true, width = v.toFloat())))
                        }
                    }
                    addSwatchStrip(left, "Bezier Color") { c ->
                        (getSelectedLayer() as? BezierLayer)?.let {
                            updateSelectedLayerLive(it.copy(color = c, stroke = it.stroke.copy(color = c)))
                        }
                    }
                }
            }

            "img_eraser" -> {
                if (sel !is ImageLayer) return
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "eraser size", 6, 120, canvasStage.bitmapEraserWidth.toInt(), { "${it}px" }) { v ->
                        canvasStage.bitmapEraserWidth = v.toFloat()
                    }
                    addPillButtonRow(
                        left,
                        listOf(
                            "Undo Erase" to {
                                (getSelectedLayer() as? ImageLayer)?.let {
                                    updateSelectedLayerLive(it.copy(eraserStrokes = it.eraserStrokes.dropLast(1)))
                                }
                            },
                            "Reset Image" to {
                                (getSelectedLayer() as? ImageLayer)?.let {
                                    updateSelectedLayerLive(it.copy(eraserStrokes = emptyList()))
                                }
                            }
                        )
                    )
                }
            }

            "img_warp" -> {
                if (sel !is ImageLayer) return
                buildWarpToolUi(body)
            }

            "img_cutout" -> {
                if (sel !is ImageLayer) return
                buildCutoutToolUi(body)
            }

            "img_retouch" -> {
                if (sel !is ImageLayer) return
                buildRetouchToolUi(body)
            }

            "eyedropper" -> {
                buildPopupShell(body) { left ->
                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 0, 0, dp(8))
                    }
                    val d = isDark()
                    val previewDot = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(lastEyedropperColor)
                            setStroke(dp(1), ThemeManager.borderColor(d))
                        }
                    }
                    val tvHint = TextView(context).apply {
                        text = "Drag canvas to sample color"
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ThemeManager.textPrimary(d))
                    }
                    topRow.addView(previewDot)
                    topRow.addView(tvHint)
                    left.addView(topRow)

                    canvasStage.onEyedropperColor = { picked ->
                        lastEyedropperColor = picked
                        previewDot.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(picked)
                            setStroke(dp(2), ThemeManager.borderColor(d))
                        }
                        tvHint.text = "${PixelLoupeDrawer.formatHexRgb(picked)}  •  Drag canvas to sample"
                    }

                    addPillButtonRow(
                        left,
                        listOf(
                            "Apply to Layer" to {
                                when (val cur = getSelectedLayer()) {
                                    is TextLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = lastEyedropperColor))
                                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = lastEyedropperColor))
                                    is ArrowLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = lastEyedropperColor))
                                    is ImageLayer -> updateSelectedLayerLive(cur.copy(colorOverlayEnabled = true, colorOverlay = lastEyedropperColor))
                                    is BezierLayer -> updateSelectedLayerLive(cur.copy(color = lastEyedropperColor, stroke = cur.stroke.copy(color = lastEyedropperColor)))
                                    else -> Unit
                                }
                            },
                            "Apply to Background" to {
                                val p = getProject()
                                updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.COLOR, color = lastEyedropperColor)))
                            }
                        )
                    )
                }
            }

            "fx_saturation" -> {
                buildPopupShell(body) { left ->
                    addPixelLabSlider(left, "saturation", -100, 150, proj.effects.saturation, { "$it" }) { v ->
                        val p = getProject()
                        updateProjectLive(p.copy(effects = p.effects.copy(saturation = v)))
                    }
                }
            }
        }
    }

    private fun buildCropToolUi(body: LinearLayout, isImageLayer: Boolean) {
        val ratios: List<Triple<String, Int, Float?>> = listOf(
            Triple("Free", R.drawable.ic_crop_tool, null),
            Triple("1:1", R.drawable.ic_aspect_1_1, 1f),
            Triple("3:4", R.drawable.ic_aspect_3_4, 3f / 4f),
            Triple("4:3", R.drawable.ic_aspect_4_3, 4f / 3f),
            Triple("16:9", R.drawable.ic_aspect_16_9, 16f / 9f),
            Triple("9:16", R.drawable.ic_aspect_9_16, 9f / 16f)
        )

        val d = isDark()
        val inactiveTint = ThemeManager.iconInactive(d)
        val selectedTint = ThemeManager.iconActive(d)
        val dividerColor = ThemeManager.dividerColor(d)

        fun formatDeg(deg: Float): String {
            val clean = if (abs(deg) < 0.05f) 0f else deg
            return String.format(Locale.US, "%.1f°", clean)
        }

        data class OptTile(
            val root: LinearLayout,
            val icon: ImageView,
            val label: TextView
        )

        fun styleOptTile(tile: OptTile, selected: Boolean) {
            if (selected) {
                tile.root.background = GradientDrawable().apply {
                    setColor(ThemeManager.selectedStripBubble(d))
                    cornerRadius = dp(10).toFloat()
                    if (d) setStroke(dp(1), ThemeManager.accentPrimary(true))
                }
                tile.icon.setColorFilter(selectedTint)
                tile.label.setTextColor(selectedTint)
            } else {
                tile.root.background = null
                tile.icon.setColorFilter(inactiveTint)
                tile.label.setTextColor(inactiveTint)
            }
        }

        fun createOptTile(
            iconRes: Int,
            labelText: String,
            minW: Int = dp(64),
            endMargin: Int = dp(4),
            onClick: () -> Unit
        ): OptTile {
            val tileLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = minW
                setPadding(dp(8), dp(8), dp(8), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = endMargin
                }
            }
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(23), dp(23))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(iconRes)
                setColorFilter(inactiveTint)
            }
            val tv = TextView(context).apply {
                text = labelText
                textSize = 10.5f
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(inactiveTint)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
            }
            tileLayout.addView(iv)
            tileLayout.addView(tv)
            val holder = OptTile(tileLayout, iv, tv)
            tileLayout.setOnClickListener { onClick() }
            return holder
        }

        buildPopupShell(body, centerVertically = true) { leftCol ->
            leftCol.setPadding(dp(8), dp(4), dp(4), dp(4))

            var currentAngle = canvasStage.straightenAngle.coerceIn(-45f, 45f)
            var suppressSlider = false

            // Row 1 container: toggles between "Enabled : [Switch]" and inline Straighten slider bar
            val topSlot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                )
            }

            // Default Row 1: centered "Enabled : [Switch]"
            val enabledHeaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val enabledLabel = TextView(context).apply {
                text = "Enabled : "
                textSize = 13f
                setTextColor(ThemeManager.textSecondary(d))
            }
            val cropContentBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                ).apply {
                    topMargin = dp(2)
                }
                visibility = if (cropFeatureEnabled) View.VISIBLE else View.GONE
            }
            val enabledSwitch = SwitchMaterial(context).apply {
                isChecked = cropFeatureEnabled
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                ThemeManager.applySwitchTheme(this, d)
                setOnCheckedChangeListener { _, isChecked ->
                    cropFeatureEnabled = isChecked
                    cropContentBox.visibility = if (isChecked) View.VISIBLE else View.GONE
                    if (!isChecked) {
                        cropStraightenBarOpen = false
                    }
                    canvasStage.toolMode = if (isChecked) {
                        CanvasStageView.StageToolMode.CROP
                    } else {
                        CanvasStageView.StageToolMode.NORMAL
                    }
                }
            }
            enabledHeaderRow.addView(enabledLabel)
            enabledHeaderRow.addView(enabledSwitch)

            // Alternate Row 1: inline Straighten slider bar
            val straightenBarRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }

            val tvStraightenVal = TextView(context).apply {
                text = formatDeg(currentAngle)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tealColor)
                minWidth = dp(44)
                gravity = Gravity.CENTER
            }

            val btnMinus = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                background = ThemeManager.circleButtonDrawable(context, d)
                setImageResource(R.drawable.ic_minus)
                setColorFilter(ThemeManager.textPrimary(d))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            val seekStraighten = SeekBar(context).apply {
                max = 180
                minimumHeight = 0
                ThemeManager.applySeekBarTheme(this, d)
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
            }

            val btnPlus = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                background = ThemeManager.circleButtonDrawable(context, d)
                setImageResource(R.drawable.ic_plus_simple)
                setColorFilter(ThemeManager.textPrimary(d))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            val btnAngleReset = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(4)
                }
                background = ThemeManager.circleButtonDrawable(context, d)
                setImageResource(R.drawable.ic_reset)
                setColorFilter(ThemeManager.textPrimary(d))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            straightenBarRow.addView(tvStraightenVal)
            straightenBarRow.addView(btnMinus)
            straightenBarRow.addView(seekStraighten)
            straightenBarRow.addView(btnPlus)
            straightenBarRow.addView(btnAngleReset)

            topSlot.addView(enabledHeaderRow)
            topSlot.addView(straightenBarRow)
            leftCol.addView(topSlot)

            // Row 2: Sticky Straighten tile + vertical divider + HorizontalScrollView of ratio & action tiles
            val straightenWrap = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lateinit var straightenTile: OptTile
            val straightenModDot = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2F6BFF"))
                }
                visibility = View.GONE
            }

            fun syncTopAndStraightenUi(angleDeg: Float) {
                currentAngle = angleDeg.coerceIn(-45f, 45f)
                val modified = abs(currentAngle) > 0.05f
                tvStraightenVal.text = formatDeg(currentAngle)
                btnAngleReset.visibility = if (modified) View.VISIBLE else View.GONE
                straightenModDot.visibility = if (modified && !cropStraightenBarOpen) View.VISIBLE else View.GONE

                val showSlider = cropFeatureEnabled && cropStraightenBarOpen
                straightenBarRow.visibility = if (showSlider) View.VISIBLE else View.GONE
                enabledHeaderRow.visibility = if (showSlider) View.GONE else View.VISIBLE
                styleOptTile(straightenTile, showSlider)
            }

            straightenTile = createOptTile(
                R.drawable.ic_straighten_cw,
                "Straighten",
                minW = dp(62),
                endMargin = 0
            ) {
                cropStraightenBarOpen = !cropStraightenBarOpen
                syncTopAndStraightenUi(currentAngle)
            }
            straightenWrap.addView(straightenTile.root)
            straightenWrap.addView(straightenModDot)
            cropContentBox.addView(straightenWrap)

            // Vertical divider matching reference screenshot
            cropContentBox.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(42)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(6)
                }
                setBackgroundColor(dividerColor)
            })

            val optionsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
            }
            val optionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
            }
            optionsScroll.addView(optionsRow)
            cropContentBox.addView(optionsScroll)
            leftCol.addView(cropContentBox)

            suppressSlider = true
            seekStraighten.progress = ((currentAngle + 45f) * 2f).roundToInt().coerceIn(0, 180)
            suppressSlider = false
            syncTopAndStraightenUi(currentAngle)

            fun applyAngleFromStepper(nextDeg: Float) {
                val clamped = nextDeg.coerceIn(-45f, 45f)
                suppressSlider = true
                seekStraighten.progress = ((clamped + 45f) * 2f).roundToInt().coerceIn(0, 180)
                suppressSlider = false
                canvasStage.setStraightenAngle(clamped)
                syncTopAndStraightenUi(clamped)
            }

            seekStraighten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (suppressSlider) return
                    val deg = -45f + progress * 0.5f
                    if (fromUser) {
                        canvasStage.setStraightenAngle(deg)
                    }
                    syncTopAndStraightenUi(deg)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })

            btnMinus.setOnClickListener { applyAngleFromStepper(currentAngle - 0.5f) }
            btnPlus.setOnClickListener { applyAngleFromStepper(currentAngle + 0.5f) }
            btnAngleReset.setOnClickListener { applyAngleFromStepper(0f) }

            val ratioTiles = mutableListOf<Pair<Float?, OptTile>>()
            fun syncRatioHighlights() {
                val curRatio = canvasStage.cropAspectRatio
                for ((r, tile) in ratioTiles) {
                    val isSel = (r == null && curRatio == null) ||
                        (r != null && curRatio != null && abs(r - curRatio) < 0.001f)
                    styleOptTile(tile, isSel)
                }
            }

            for ((label, iconRes, ratio) in ratios) {
                val tile = createOptTile(iconRes, label) {
                    canvasStage.setCropAspectRatio(ratio)
                    syncRatioHighlights()
                }
                ratioTiles.add(ratio to tile)
                optionsRow.addView(tile.root)
            }
            syncRatioHighlights()

            optionsRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                    setMargins(dp(4), dp(4), dp(6), dp(4))
                }
                setBackgroundColor(dividerColor)
            })

            fun addActionTile(
                iconRes: Int,
                label: String,
                activeProvider: () -> Boolean = { false },
                onClick: () -> Unit
            ) {
                lateinit var tile: OptTile
                tile = createOptTile(iconRes, label) {
                    onClick()
                    styleOptTile(tile, activeProvider())
                }
                styleOptTile(tile, activeProvider())
                optionsRow.addView(tile.root)
            }

            if (isImageLayer) {
                addActionTile(
                    R.drawable.ic_circle_outline,
                    "Circle",
                    { (getSelectedLayer() as? ImageLayer)?.circularCrop == true }
                ) {
                    (getSelectedLayer() as? ImageLayer)?.let {
                        updateSelectedLayerLive(it.copy(circularCrop = !it.circularCrop))
                    }
                }
                addActionTile(
                    R.drawable.ic_flip_h,
                    "Flip H",
                    { (getSelectedLayer() as? ImageLayer)?.flipH == true }
                ) {
                    (getSelectedLayer() as? ImageLayer)?.let {
                        updateSelectedLayerLive(it.copy(flipH = !it.flipH))
                    }
                }
                addActionTile(
                    R.drawable.ic_flip_v,
                    "Flip V",
                    { (getSelectedLayer() as? ImageLayer)?.flipV == true }
                ) {
                    (getSelectedLayer() as? ImageLayer)?.let {
                        updateSelectedLayerLive(it.copy(flipV = !it.flipV))
                    }
                }
            } else {
                addActionTile(
                    R.drawable.ic_circle_outline,
                    "Circle",
                    { getProject().background.circular }
                ) {
                    val p = getProject()
                    updateProjectLive(p.copy(background = p.background.copy(circular = !p.background.circular)))
                }
                addActionTile(
                    R.drawable.ic_flip_h,
                    "Flip H",
                    { getProject().background.flipH }
                ) {
                    val p = getProject()
                    updateProjectLive(p.copy(background = p.background.copy(flipH = !p.background.flipH)))
                }
                addActionTile(
                    R.drawable.ic_flip_v,
                    "Flip V",
                    { getProject().background.flipV }
                ) {
                    val p = getProject()
                    updateProjectLive(p.copy(background = p.background.copy(flipV = !p.background.flipV)))
                }
                addActionTile(R.drawable.ic_rotate_bg, "Rotate 90°") {
                    val p = getProject()
                    val rot = (p.background.imageRotation + 90) % 360
                    updateProjectLive(p.copy(background = p.background.copy(imageRotation = rot)))
                }
                addActionTile(R.drawable.ic_image, "Gallery") { onPickBgImage() }
                addActionTile(R.drawable.ic_bg_presets, "Presets") { openTool("bg_presets", "Background Presets") }
            }

            addActionTile(R.drawable.ic_reset, "Reset") {
                canvasStage.resetCrop()
                suppressSlider = true
                seekStraighten.progress = 90
                suppressSlider = false
                syncTopAndStraightenUi(0f)
                syncRatioHighlights()
            }
        }
    }

    private fun applyImageLayerCrop(): Boolean {
        val sel = getSelectedLayer() as? ImageLayer ?: return false
        val rect = RectF(canvasStage.cropRect)
        val angle = canvasStage.straightenAngle
        val fullW = sel.width.coerceAtLeast(1f)
        val fullH = sel.height.coerceAtLeast(1f)

        val isNoOp = rect.left <= 0.5f &&
            rect.top <= 0.5f &&
            abs(rect.width() - fullW) < 0.5f &&
            abs(rect.height() - fullH) < 0.5f &&
            abs(angle) < 0.05f
        if (isNoOp) {
            return false
        }

        val srcBmp = PixelLabRenderer.loadBitmap(context, sel.uriString) ?: return false
        val layerAspect = (fullW / fullH).coerceIn(0.05f, 20f)
        val maxBase = max(srcBmp.width, srcBmp.height).coerceIn(256, 2048).toFloat()
        val tmpW: Int
        val tmpH: Int
        if (layerAspect >= 1f) {
            tmpW = maxBase.roundToInt().coerceAtLeast(1)
            tmpH = (maxBase / layerAspect).roundToInt().coerceAtLeast(1)
        } else {
            tmpH = maxBase.roundToInt().coerceAtLeast(1)
            tmpW = (maxBase * layerAspect).roundToInt().coerceAtLeast(1)
        }

        val tmpBmp = Bitmap.createBitmap(tmpW, tmpH, Bitmap.Config.ARGB_8888)
        val tmpCanvas = Canvas(tmpBmp)
        tmpCanvas.save()
        tmpCanvas.translate(tmpW * 0.5f, tmpH * 0.5f)
        if (abs(angle) > 0.01f) {
            tmpCanvas.rotate(angle)
        }
        tmpCanvas.scale(if (sel.flipH) -1f else 1f, if (sel.flipV) -1f else 1f)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dstRect = RectF(-tmpW * 0.5f, -tmpH * 0.5f, tmpW * 0.5f, tmpH * 0.5f)
        tmpCanvas.drawBitmap(srcBmp, null, dstRect, drawPaint)
        tmpCanvas.restore()

        val sx = tmpW.toFloat() / fullW
        val sy = tmpH.toFloat() / fullH
        val cx = (rect.left * sx).roundToInt().coerceIn(0, tmpW - 1)
        val cy = (rect.top * sy).roundToInt().coerceIn(0, tmpH - 1)
        val cw = (rect.width() * sx).roundToInt().coerceIn(1, tmpW - cx)
        val ch = (rect.height() * sy).roundToInt().coerceIn(1, tmpH - cy)

        val croppedBmp = Bitmap.createBitmap(tmpBmp, cx, cy, cw, ch)
        if (croppedBmp !== tmpBmp && !tmpBmp.isRecycled) {
            tmpBmp.recycle()
        }

        val outFile = File(context.filesDir, "media/crop_${System.currentTimeMillis()}.png").apply {
            parentFile?.mkdirs()
        }
        val savedOk = runCatching {
            FileOutputStream(outFile).use { out ->
                croppedBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }.getOrDefault(false)
        if (!croppedBmp.isRecycled) {
            croppedBmp.recycle()
        }
        if (!savedOk) return false

        val localDx = rect.centerX() - fullW * 0.5f
        val localDy = rect.centerY() - fullH * 0.5f
        val rad = sel.rotation * PI / 180.0
        val nx = sel.x + (localDx * cos(rad) - localDy * sin(rad)).toFloat()
        val ny = sel.y + (localDx * sin(rad) + localDy * cos(rad)).toFloat()

        val prev = snapshotBeforeEdit ?: getProject()
        pushHistory(prev)

        val updatedLayer = sel.copy(
            uriString = outFile.absolutePath,
            x = nx,
            y = ny,
            width = rect.width().coerceAtLeast(24f),
            height = rect.height().coerceAtLeast(24f),
            flipH = false,
            flipV = false
        )
        val curProj = getProject()
        updateProjectLive(curProj.copy(layers = curProj.layers.map { if (it.id == sel.id) updatedLayer else it }))
        onRestoreSelection(sel.id)
        return true
    }

    private fun applyCanvasCrop(): Boolean {
        val proj = getProject()
        val rect = RectF(canvasStage.cropRect)
        val angle = canvasStage.straightenAngle
        val oldW = proj.width.toFloat().coerceAtLeast(1f)
        val oldH = proj.height.toFloat().coerceAtLeast(1f)

        val isNoOp = rect.left <= 0.5f &&
            rect.top <= 0.5f &&
            abs(rect.width() - oldW) < 0.5f &&
            abs(rect.height() - oldH) < 0.5f &&
            abs(angle) < 0.05f
        if (isNoOp) {
            return false
        }

        val cx = rect.left.roundToInt().coerceIn(0, (proj.width - 24).coerceAtLeast(0))
        val cy = rect.top.roundToInt().coerceIn(0, (proj.height - 24).coerceAtLeast(0))
        val nw = rect.width().roundToInt().coerceIn(1, (proj.width - cx).coerceAtLeast(1))
        val nh = rect.height().roundToInt().coerceIn(1, (proj.height - cy).coerceAtLeast(1))

        var nextBg = proj.background
        if (proj.background.type == BackgroundType.IMAGE && proj.background.imageUri != null) {
            val outBmp = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            val c = Canvas(outBmp)
            c.save()
            c.translate(-cx.toFloat(), -cy.toFloat())
            if (abs(angle) > 0.01f) {
                c.rotate(angle, oldW * 0.5f, oldH * 0.5f)
            }
            val neutralProj = proj.copy(
                effects = EffectsState(),
                background = proj.background.copy(circular = false)
            )
            PixelLabRenderer.renderBackground(context, c, neutralProj, oldW, oldH)
            c.restore()

            val outFile = File(context.filesDir, "media/bg_crop_${System.currentTimeMillis()}.png").apply {
                parentFile?.mkdirs()
            }
            val saved = runCatching {
                FileOutputStream(outFile).use { out ->
                    outBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            }.getOrDefault(false)
            if (!outBmp.isRecycled) outBmp.recycle()
            if (saved) {
                nextBg = proj.background.copy(
                    imageUri = outFile.absolutePath,
                    imageRotation = 0,
                    flipH = false,
                    flipV = false
                )
            }
        }

        val rad = angle * PI / 180.0
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val halfOldW = oldW * 0.5f
        val halfOldH = oldH * 0.5f
        val newLayers = proj.layers.map { l ->
            val dx = l.x - halfOldW
            val dy = l.y - halfOldH
            val rx = halfOldW + dx * cosA - dy * sinA
            val ry = halfOldH + dx * sinA + dy * cosA
            l.copyBase(
                x = rx - cx,
                y = ry - cy,
                rotation = l.rotation + angle
            )
        }

        val prev = snapshotBeforeEdit ?: proj
        pushHistory(prev)

        updateProjectLive(
            proj.copy(
                width = nw,
                height = nh,
                background = nextBg,
                layers = newLayers
            )
        )
        onRestoreSelection(getSelectedLayer()?.id)
        return true
    }

    private fun openRadiusSheet(body: LinearLayout, isBackground: Boolean = false) {
        val proj = getProject()
        val layer = if (isBackground) null else getSelectedLayer()
        val isImage = layer is ImageLayer
        val isRectShape = layer is ShapeLayer && layer.shapeLabel == BoundedShapeLabel.RECT
        if (!isBackground && !isImage && !isRectShape) return

        val maxR = if (isBackground) {
            (min(proj.width, proj.height) / 2f).roundToInt().coerceAtLeast(1)
        } else {
            (min(layer!!.width, layer.height) / 2f).roundToInt().coerceAtLeast(1)
        }

        val radii = when {
            isBackground -> proj.background.cornerRadii.toMutableList()
            layer is ImageLayer -> layer.cornerRadii.toMutableList()
            layer is ShapeLayer -> (layer.cornerRadii ?: listOf(0f, 0f, 0f, 0f)).toMutableList()
            else -> mutableListOf(0f, 0f, 0f, 0f)
        }
        while (radii.size < 4) radii.add(0f)

        val enabled = when {
            isBackground -> proj.background.cornerEnabled.toMutableList()
            layer is ImageLayer -> layer.cornerEnabled.toMutableList()
            layer is ShapeLayer -> layer.cornerEnabled.toMutableList()
            else -> mutableListOf(true, true, true, true)
        }
        while (enabled.size < 4) enabled.add(true)

        var locked = when {
            isBackground -> proj.background.cornerLocked
            layer is ImageLayer -> layer.cornerLocked
            layer is ShapeLayer -> layer.cornerLocked
            else -> true
        }

        fun applyRadiusChanges() {
            if (isBackground) {
                val curProj = getProject()
                updateProjectLive(
                    curProj.copy(
                        background = curProj.background.copy(
                            cornerRadii = radii.toList(),
                            cornerEnabled = enabled.toList(),
                            cornerLocked = locked
                        )
                    )
                )
                return
            }
            when (val cur = getSelectedLayer()) {
                is ImageLayer -> updateSelectedLayerLive(
                    cur.copy(
                        cornerRadii = radii.toList(),
                        cornerEnabled = enabled.toList(),
                        cornerLocked = locked
                    )
                )
                is ShapeLayer -> updateSelectedLayerLive(
                    cur.copy(
                        cornerRadii = radii.toList(),
                        cornerEnabled = enabled.toList(),
                        cornerLocked = locked
                    )
                )
                else -> Unit
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(sheetBg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            setPadding(dp(4), 0, dp(2), dp(2))
        }

        val d = isDark()
        val radiusBlue = if (d) Color.parseColor("#2F6BFF") else Color.parseColor("#1E88E5")
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setImageResource(R.drawable.ic_radius)
            setColorFilter(ThemeManager.textPrimary(d))
        }
        header.addView(iconView)
        header.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        val lockBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(26)).apply { marginEnd = dp(8) }
        }
        val lockIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        }
        lockBtn.addView(lockIcon)

        fun refreshLockUi() {
            lockBtn.background = GradientDrawable().apply {
                setColor(if (locked) radiusBlue else ThemeManager.borderColor(d))
                cornerRadius = dp(999).toFloat()
            }
            lockIcon.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
            lockIcon.setColorFilter(if (locked) Color.WHITE else ThemeManager.textPrimary(d))
        }
        refreshLockUi()

        val resetBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
            background = null
            setImageResource(R.drawable.ic_reset)
            setColorFilter(ThemeManager.textPrimary(d))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val checkCircleBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (d) radiusBlue else sageColBg)
            }
            setImageResource(R.drawable.ic_check)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { commitCurrentTool() }
        }

        header.addView(lockBtn)
        header.addView(resetBtn)
        header.addView(checkCircleBtn)
        container.addView(header)

        val seekBars = arrayOfNulls<SeekBar>(4)
        val valLabels = arrayOfNulls<TextView>(4)
        val switches = arrayOfNulls<SwitchMaterial>(4)
        var updatingUi = false

        fun syncCardControls() {
            updatingUi = true
            for (i in 0..3) {
                val r = radii[i].roundToInt().coerceIn(0, maxR)
                seekBars[i]?.progress = r
                seekBars[i]?.isEnabled = enabled[i]
                valLabels[i]?.text = r.toString()
                switches[i]?.isChecked = enabled[i]
            }
            updatingUi = false
        }

        val switchThumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.WHITE)
        )
        val switchTrackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(radiusBlue, if (d) Color.parseColor("#3F3F46") else Color.parseColor("#B0BEC5"))
        )

        fun buildCornerCard(cornerIdx: Int): LinearLayout {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = false
                isSoundEffectsEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                }
                background = GradientDrawable().apply {
                    setColor(ThemeManager.cardBg(d))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), ThemeManager.borderColor(d))
                }
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isSoundEffectsEnabled = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val diagram = object : View(context) {
                private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (d) Color.parseColor("#6B7280") else Color.parseColor("#B0B7C3")
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f * context.resources.displayMetrics.density
                }
                private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = radiusBlue
                    style = Paint.Style.STROKE
                    strokeWidth = 2.2f * context.resources.displayMetrics.density
                    strokeCap = Paint.Cap.ROUND
                }
                private val rect = RectF()
                private val arcRect = RectF()
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val pad = 2.5f * context.resources.displayMetrics.density
                    val r = 5f * context.resources.displayMetrics.density
                    rect.set(pad, pad, width - pad, height - pad)
                    canvas.drawRoundRect(rect, r, r, basePaint)
                    val dia = r * 2f
                    when (cornerIdx) {
                        0 -> arcRect.set(rect.left, rect.top, rect.left + dia, rect.top + dia)
                        1 -> arcRect.set(rect.right - dia, rect.top, rect.right, rect.top + dia)
                        2 -> arcRect.set(rect.right - dia, rect.bottom - dia, rect.right, rect.bottom)
                        else -> arcRect.set(rect.left, rect.bottom - dia, rect.left + dia, rect.bottom)
                    }
                    val startAngle = when (cornerIdx) {
                        0 -> 180f
                        1 -> 270f
                        2 -> 0f
                        else -> 90f
                    }
                    canvas.drawArc(arcRect, startAngle, 90f, false, activePaint)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
            }
            topRow.addView(diagram)
            topRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

            val swWrap = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(20))
                clipChildren = false
                clipToPadding = false
            }
            val sw = SwitchMaterial(context).apply {
                isChecked = enabled[cornerIdx]
                scaleX = 0.74f
                scaleY = 0.74f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                thumbTintList = switchThumbStates
                trackTintList = switchTrackStates
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setOnCheckedChangeListener { _, isChecked ->
                    if (updatingUi) return@setOnCheckedChangeListener
                    enabled[cornerIdx] = isChecked
                    syncCardControls()
                    applyRadiusChanges()
                }
            }
            swWrap.addView(sw)
            switches[cornerIdx] = sw
            topRow.addView(swWrap)
            card.addView(topRow)

            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isSoundEffectsEnabled = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val tvVal = TextView(context).apply {
                text = radii[cornerIdx].roundToInt().toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ThemeManager.textPrimary(d))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val sb = SeekBar(context).apply {
                max = maxR
                progress = radii[cornerIdx].roundToInt().coerceIn(0, maxR)
                isEnabled = enabled[cornerIdx]
                minimumHeight = 0
                thumbTintList = ColorStateList.valueOf(radiusBlue)
                progressTintList = ColorStateList.valueOf(radiusBlue)
                progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.seekTrackColor(d))
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(22), 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser || updatingUi) return
                        if (locked) {
                            for (i in 0..3) radii[i] = p.toFloat()
                        } else {
                            radii[cornerIdx] = p.toFloat()
                        }
                        syncCardControls()
                        applyRadiusChanges()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            }
            seekBars[cornerIdx] = sb
            valLabels[cornerIdx] = tvVal
            bottomRow.addView(sb)
            bottomRow.addView(tvVal)
            card.addView(bottomRow)

            return card
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        row1.addView(buildCornerCard(0))
        row1.addView(buildCornerCard(1))

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        row2.addView(buildCornerCard(3))
        row2.addView(buildCornerCard(2))

        lockBtn.setOnClickListener {
            locked = !locked
            if (locked) {
                val baseVal = radii[0]
                for (i in 0..3) radii[i] = baseVal
                syncCardControls()
            }
            refreshLockUi()
            applyRadiusChanges()
        }

        resetBtn.setOnClickListener {
            for (i in 0..3) {
                radii[i] = 0f
                enabled[i] = true
            }
            syncCardControls()
            applyRadiusChanges()
        }

        container.addView(row1)
        container.addView(row2)
        body.addView(container)
    }

    private fun applyTealSwitchColors(sw: SwitchMaterial) {
        ThemeManager.applySwitchTheme(sw, isDark())
    }

    private fun openFillSheet(body: LinearLayout, isBackground: Boolean) {
        val sel = if (isBackground) null else getSelectedLayer() ?: return
        val proj = getProject()

        var isGradientMode = when {
            isBackground -> proj.background.type == BackgroundType.GRADIENT
            sel is TextLayer -> sel.fillType == FillType.GRADIENT
            sel is ShapeLayer -> sel.fillType == FillType.GRADIENT
            sel is ArrowLayer -> sel.fillType == FillType.GRADIENT
            sel is ImageLayer -> sel.colorOverlayFillType == FillType.GRADIENT
            else -> false
        }

        var tintEnabled = (sel as? ImageLayer)?.colorOverlayEnabled ?: true

        fun currentColor(): Int = when {
            isBackground -> getProject().background.color
            sel is TextLayer -> (getSelectedLayer() as? TextLayer)?.color ?: sel.color
            sel is ShapeLayer -> (getSelectedLayer() as? ShapeLayer)?.color ?: sel.color
            sel is ArrowLayer -> (getSelectedLayer() as? ArrowLayer)?.color ?: sel.color
            sel is ImageLayer -> (getSelectedLayer() as? ImageLayer)?.colorOverlay ?: sel.colorOverlay
            else -> Color.WHITE
        }

        fun currentGrad(): GradientFill = when {
            isBackground -> getProject().background.gradient
            sel is TextLayer -> (getSelectedLayer() as? TextLayer)?.gradient ?: sel.gradient
            sel is ShapeLayer -> (getSelectedLayer() as? ShapeLayer)?.gradient ?: sel.gradient
            sel is ArrowLayer -> (getSelectedLayer() as? ArrowLayer)?.gradient ?: sel.gradient
            sel is ImageLayer -> (getSelectedLayer() as? ImageLayer)?.colorOverlayGradient ?: sel.colorOverlayGradient
            else -> GradientFill()
        }

        fun applyColor(c: Int) {
            if (isBackground) {
                val p = getProject()
                updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.COLOR, color = c)))
            } else {
                when (val cur = getSelectedLayer()) {
                    is TextLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = c))
                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = c))
                    is ArrowLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.COLOR, color = c))
                    is ImageLayer -> updateSelectedLayerLive(
                        cur.copy(colorOverlayEnabled = true, colorOverlayFillType = FillType.COLOR, colorOverlay = c)
                    )
                    else -> Unit
                }
            }
        }

        fun applyGradient(g: GradientFill) {
            if (isBackground) {
                val p = getProject()
                updateProjectLive(p.copy(background = p.background.copy(type = BackgroundType.GRADIENT, gradient = g)))
            } else {
                when (val cur = getSelectedLayer()) {
                    is TextLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.GRADIENT, gradient = g))
                    is ShapeLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.GRADIENT, gradient = g))
                    is ArrowLayer -> updateSelectedLayerLive(cur.copy(fillType = FillType.GRADIENT, gradient = g))
                    is ImageLayer -> updateSelectedLayerLive(
                        cur.copy(colorOverlayEnabled = true, colorOverlayFillType = FillType.GRADIENT, colorOverlayGradient = g)
                    )
                    else -> Unit
                }
            }
        }

        fun createGradientDotView(g: GradientFill, isSel: Boolean, onTap: () -> Unit): View {
            val dotSize = dp(34)
            return object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val strokeW = dp(if (isSel) 3 else 1).toFloat()
                    val r = (min(width, height) / 2f - strokeW).coerceAtLeast(1f)
                    val s = r * 2f
                    paint.shader = ShapePathBuilder.createShader(g, cx - r, cy - r, s, s)
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.shader = null
                    strokePaint.strokeWidth = strokeW
                    strokePaint.color = if (isSel) Color.WHITE else Color.parseColor("#64748B")
                    canvas.drawCircle(cx, cy, r, strokePaint)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dp(4), dp(2), dp(4), dp(2))
                }
                setOnClickListener { onTap() }
            }
        }

        buildPopupShell(body, centerVertically = true) { leftCol ->
            val tintBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (sel !is ImageLayer || tintEnabled) View.VISIBLE else View.GONE
            }

            lateinit var renderSwatchesRef: () -> Unit

            val d = isDark()
            if (sel is ImageLayer) {
                val enableRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(4))
                }
                enableRow.addView(TextView(context).apply {
                    text = "Enabled : "
                    textSize = 12.5f
                    setTextColor(ThemeManager.textSecondary(d))
                })
                val sw = SwitchMaterial(context).apply {
                    isChecked = tintEnabled
                    scaleX = 0.85f
                    scaleY = 0.85f
                    minHeight = 0
                    minimumHeight = 0
                    applyTealSwitchColors(this)
                    setOnCheckedChangeListener { _, checked ->
                        tintEnabled = checked
                        tintBody.visibility = if (checked) View.VISIBLE else View.GONE
                        (getSelectedLayer() as? ImageLayer)?.let { cur ->
                            updateSelectedLayerLive(cur.copy(colorOverlayEnabled = checked))
                        }
                        renderSwatchesRef()
                    }
                }
                enableRow.addView(sw)
                leftCol.addView(enableRow)
            }

            val segAccent = if (d) Color.parseColor("#2F6BFF") else Color.parseColor("#EC2166")
            val segWrap = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(6))
            }
            val segBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
                background = GradientDrawable().apply {
                    setColor(ThemeManager.cardBg(d))
                    setStroke(dp(1), segAccent)
                    cornerRadius = dp(4).toFloat()
                }
                setPadding(dp(1), dp(1), dp(1), dp(1))
            }
            val btnSegColor = TextView(context).apply {
                text = "color"
                textSize = 12.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, dp(3), 0, dp(3))
            }
            val btnSegGrad = TextView(context).apply {
                text = "gradient"
                textSize = 12.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, dp(3), 0, dp(3))
            }
            segBox.addView(btnSegColor)
            segBox.addView(btnSegGrad)
            segWrap.addView(segBox)
            tintBody.addView(segWrap)

            val outerStripRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val stripFrame = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    if (d) intArrayOf(Color.parseColor("#2A2A30"), Color.parseColor("#202026"))
                    else intArrayOf(Color.parseColor("#BDBDBD"), Color.parseColor("#8C8C8C"))
                ).apply {
                    cornerRadius = dp(4).toFloat()
                }
                setPadding(dp(5), dp(3), dp(5), dp(3))
            }
            val swatchScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val swatchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            swatchScroll.addView(swatchRow)
            stripFrame.addView(swatchScroll)

            val plusBtn = TextView(context).apply {
                text = "+"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(accentBlue)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    marginStart = dp(8)
                }
                background = ThemeManager.circleButtonDrawable(context, d)
                elevation = dp(2).toFloat()
                setOnClickListener {
                    if (isGradientMode) {
                        val prevGrad = currentGrad()
                        Dialogs.showGradientMakerDialog(
                            context = context,
                            initial = prevGrad,
                            project = getProject(),
                            onLiveChange = { liveGrad ->
                                applyGradient(liveGrad)
                            },
                            onCancel = {
                                applyGradient(prevGrad)
                            },
                            onConfirm = { g ->
                                JsonPresetCodec.saveCustomGradient(context, g)
                                applyGradient(g)
                                renderSwatchesRef()
                                swatchScroll.post { swatchScroll.smoothScrollTo(0, 0) }
                            }
                        )
                    } else {
                        val prevCol = currentColor()
                        Dialogs.showColorPickerDialog(
                            context = context,
                            initialColor = prevCol,
                            project = getProject(),
                            onLiveChange = { liveCol ->
                                applyColor(liveCol)
                            },
                            onCancel = {
                                applyColor(prevCol)
                            },
                            onConfirm = { picked ->
                                JsonPresetCodec.saveCustomColor(context, picked)
                                applyColor(picked)
                                renderSwatchesRef()
                                swatchScroll.post { swatchScroll.smoothScrollTo(0, 0) }
                            }
                        )
                    }
                }
            }

            outerStripRow.addView(stripFrame)
            outerStripRow.addView(plusBtn)
            tintBody.addView(outerStripRow)

            fun renderSwatches() {
                val r = dp(3).toFloat()
                val segOffBg = ThemeManager.cardBg(d)
                btnSegColor.background = GradientDrawable().apply {
                    setColor(if (!isGradientMode) segAccent else segOffBg)
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                btnSegColor.setTextColor(if (!isGradientMode) Color.WHITE else ThemeManager.textSecondary(d))

                btnSegGrad.background = GradientDrawable().apply {
                    setColor(if (isGradientMode) segAccent else segOffBg)
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                }
                btnSegGrad.setTextColor(if (isGradientMode) Color.WHITE else segAccent)

                swatchRow.removeAllViews()

                if (!isGradientMode) {
                    val activeCol = currentColor()
                    val allColors = (JsonPresetCodec.loadCustomColors(context) + AppDefaults.defaultColors).distinct()
                    for (c in allColors) {
                        val isSel = activeCol == c
                        val dot = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                                setMargins(dp(4), dp(2), dp(4), dp(2))
                            }
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(c)
                                setStroke(dp(if (isSel) 3 else 1), if (isSel) Color.WHITE else Color.parseColor("#64748B"))
                            }
                            setOnClickListener {
                                applyColor(c)
                                renderSwatches()
                            }
                        }
                        swatchRow.addView(dot)
                    }
                } else {
                    val allGrads = JsonPresetCodec.loadCustomGradients(context) + AppDefaults.defaultGradients
                    val activeGradKey = JsonPresetCodec.gradToWebJson(currentGrad()).toString()
                    for (g in allGrads) {
                        val isSel = JsonPresetCodec.gradToWebJson(g).toString() == activeGradKey
                        val dot = createGradientDotView(g, isSel) {
                            applyGradient(g)
                            renderSwatches()
                        }
                        swatchRow.addView(dot)
                    }
                }
            }
            renderSwatchesRef = ::renderSwatches

            btnSegColor.setOnClickListener {
                isGradientMode = false
                applyColor(currentColor())
                renderSwatches()
            }
            btnSegGrad.setOnClickListener {
                isGradientMode = true
                applyGradient(currentGrad())
                renderSwatches()
            }

            leftCol.addView(tintBody)
            renderSwatches()
        }
    }

    private fun updateInnerShadow(transform: (ShadowConfig) -> ShadowConfig) {
        when (val cur = getSelectedLayer()) {
            is TextLayer -> updateSelectedLayerLive(cur.copy(innerShadow = transform(cur.innerShadow)))
            is ShapeLayer -> updateSelectedLayerLive(cur.copy(innerShadow = transform(cur.innerShadow)))
            is ImageLayer -> updateSelectedLayerLive(cur.copy(innerShadow = transform(cur.innerShadow)))
            else -> Unit
        }
    }

    private fun updateStroke(transform: (StrokeConfig) -> StrokeConfig) {
        when (val cur = getSelectedLayer()) {
            is TextLayer -> updateSelectedLayerLive(cur.copy(stroke = transform(cur.stroke)))
            is ShapeLayer -> updateSelectedLayerLive(cur.copy(stroke = transform(cur.stroke)))
            is ArrowLayer -> updateSelectedLayerLive(cur.copy(stroke = transform(cur.stroke)))
            is ImageLayer -> updateSelectedLayerLive(cur.copy(stroke = transform(cur.stroke)))
            else -> Unit
        }
    }

    private fun updateShadow(transform: (ShadowConfig) -> ShadowConfig) {
        when (val cur = getSelectedLayer()) {
            is TextLayer -> updateSelectedLayerLive(cur.copy(shadow = transform(cur.shadow)))
            is ShapeLayer -> updateSelectedLayerLive(cur.copy(shadow = transform(cur.shadow)))
            is ArrowLayer -> updateSelectedLayerLive(cur.copy(shadow = transform(cur.shadow)))
            is ImageLayer -> updateSelectedLayerLive(cur.copy(shadow = transform(cur.shadow)))
            else -> Unit
        }
    }

    private fun addEnabledSection(
        parent: LinearLayout,
        initialEnabled: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val d = isDark()
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isSoundEffectsEnabled = false
            setPadding(0, dp(2), 0, dp(6))
        }
        val label = TextView(context).apply {
            text = "Enabled : "
            textSize = 13f
            setTextColor(ThemeManager.textSecondary(d))
        }
        val contentBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isSoundEffectsEnabled = false
            visibility = if (initialEnabled) View.VISIBLE else View.GONE
        }
        val sw = SwitchMaterial(context).apply {
            isChecked = initialEnabled
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            ThemeManager.applySwitchTheme(this, d)
            setOnCheckedChangeListener { _, isChecked ->
                contentBox.visibility = if (isChecked) View.VISIBLE else View.GONE
                onToggle(isChecked)
            }
        }
        headerRow.addView(label)
        headerRow.addView(sw)
        parent.addView(headerRow)
        parent.addView(contentBox)
        return contentBox
    }

    private fun addPixelLabSlider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        current: Int,
        formatValue: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val d = isDark()
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            setPadding(0, dp(3), 0, dp(5))
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isSoundEffectsEnabled = false
            setPadding(dp(2), 0, dp(2), dp(2))
        }
        val tvLabel = TextView(context).apply {
            text = "$label : "
            textSize = 12.5f
            setTextColor(ThemeManager.textSecondary(d))
        }
        val tvVal = TextView(context).apply {
            text = formatValue(current.coerceIn(min, max))
            textSize = 12.5f
            setTextColor(accentBlue)
            typeface = Typeface.DEFAULT_BOLD
        }
        labelRow.addView(tvLabel)
        labelRow.addView(tvVal)
        block.addView(labelRow)

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isSoundEffectsEnabled = false
        }

        val range = (max - min).coerceAtLeast(1)
        var valueState = current.coerceIn(min, max)

        val seek = SeekBar(context).apply {
            this.max = range
            this.progress = valueState - min
            minimumHeight = 0
            ThemeManager.applySeekBarTheme(this, d)
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    valueState = v
                    tvVal.text = formatValue(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(false)
                }
            })
        }

        val btnMinus = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            background = ThemeManager.circleButtonDrawable(context, d)
            elevation = dp(1).toFloat()
            setImageResource(R.drawable.ic_minus)
            setColorFilter(ThemeManager.textPrimary(d))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val next = (valueState - 1).coerceIn(min, max)
                valueState = next
                seek.progress = next - min
                tvVal.text = formatValue(next)
                onChange(next)
            }
        }

        val btnPlus = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            background = ThemeManager.circleButtonDrawable(context, d)
            elevation = dp(1).toFloat()
            setImageResource(R.drawable.ic_plus_simple)
            setColorFilter(ThemeManager.textPrimary(d))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val next = (valueState + 1).coerceIn(min, max)
                valueState = next
                seek.progress = next - min
                tvVal.text = formatValue(next)
                onChange(next)
            }
        }

        sliderRow.addView(btnMinus)
        sliderRow.addView(seek)
        sliderRow.addView(btnPlus)
        block.addView(sliderRow)
        parent.addView(block)
    }

    private fun createCircleIconButton(
        iconRes: Int,
        active: Boolean = false,
        onClick: () -> Unit
    ): ImageButton {
        val d = isDark()
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(7), dp(2), dp(7), dp(2))
            }
            background = ThemeManager.circleButtonDrawable(context, d, active)
            elevation = dp(2).toFloat()
            setImageResource(iconRes)
            setColorFilter(if (active && d) Color.WHITE else accentBlue)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onClick() }
        }
    }

    private fun createCircleTextButton(
        label: String,
        active: Boolean,
        style: Int,
        onClick: () -> Unit
    ): TextView {
        val d = isDark()
        return TextView(context).apply {
            text = label
            textSize = 15.5f
            setTypeface(null, style)
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else accentBlue)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(7), dp(2), dp(7), dp(2))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) accentBlue else ThemeManager.cardElevatedBg(d))
                setStroke(dp(1), if (active) accentBlue else ThemeManager.borderColor(d))
            }
            elevation = dp(2).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun cardPillDrawable(active: Boolean): GradientDrawable {
        return ThemeManager.pillDrawable(context, isDark(), active)
    }

    private fun addPillButtonRow(parent: LinearLayout, items: List<Pair<String, () -> Unit>>) {
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        for ((title, action) in items) {
            val pill = TextView(context).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentBlue)
                gravity = Gravity.CENTER
                background = cardPillDrawable(false)
                elevation = dp(1).toFloat()
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
                setOnClickListener { action() }
            }
            row.addView(pill)
        }
        hs.addView(row)
        parent.addView(hs)
    }

    private fun addSwatchStrip(parent: LinearLayout, label: String, onPick: (Int) -> Unit) {
        val d = isDark()
        parent.addView(TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(ThemeManager.textSecondary(d))
            setPadding(dp(2), dp(4), 0, dp(4))
        })
        val stripFrame = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (d) Color.parseColor("#2A2A30") else Color.parseColor("#A9A9A9"))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val customBtn = TextView(context).apply {
            text = "+"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ThemeManager.textPrimary(d))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(3), dp(2), dp(5), dp(2)) }
            background = ThemeManager.circleButtonDrawable(context, d)
            setOnClickListener {
                Dialogs.showColorPickerDialog(
                    context = context,
                    initialColor = Color.parseColor("#0079FF"),
                    project = getProject()
                ) { picked ->
                    JsonPresetCodec.saveCustomColor(context, picked)
                    onPick(picked)
                }
            }
        }
        row.addView(customBtn)
        for (c in AppDefaults.defaultColors) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1), ThemeManager.borderColor(d))
                }
                setOnClickListener { onPick(c) }
            }
            row.addView(dot)
        }
        hs.addView(row)
        stripFrame.addView(hs)
        parent.addView(stripFrame)
    }

    private fun scheduleHideBrushPreview() {
        hidePreviewToken?.let { binding.optionExpandPanel.removeCallbacks(it) }
        val r = Runnable {
            canvasStage.hideBrushCenterPreview()
        }
        hidePreviewToken = r
        binding.optionExpandPanel.postDelayed(r, 900L)
    }

    private fun buildCompactSliderBar(
        label: String,
        min: Int,
        max: Int,
        current: Int,
        defaultVal: Int,
        formatValue: (Int) -> String,
        onLiveChange: (Int) -> Unit,
        onStopTracking: (() -> Unit)? = null
    ): LinearLayout {
        val d = isDark()
        val accent = Color.parseColor("#2F6BFF")
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        val tvLabel = TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(ThemeManager.textSecondary(d))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvVal = TextView(context).apply {
            text = formatValue(current.coerceIn(min, max))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
        }
        headerRow.addView(tvLabel)
        headerRow.addView(tvVal)
        row.addView(headerRow)

        val ctrlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val range = (max - min).coerceAtLeast(1)
        var valueState = current.coerceIn(min, max)

        val seek = SeekBar(context).apply {
            this.max = range
            this.progress = valueState - min
            minimumHeight = 0
            thumbTintList = ColorStateList.valueOf(accent)
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.seekTrackColor(d))
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
        }

        val btnMinus = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            background = ThemeManager.circleButtonDrawable(context, d)
            setImageResource(R.drawable.ic_minus)
            setColorFilter(ThemeManager.textPrimary(d))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val next = (valueState - 1).coerceIn(min, max)
                valueState = next
                seek.progress = next - min
                tvVal.text = formatValue(next)
                onLiveChange(next)
                onStopTracking?.invoke()
            }
        }

        val btnPlus = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            background = ThemeManager.circleButtonDrawable(context, d)
            setImageResource(R.drawable.ic_plus_simple)
            setColorFilter(ThemeManager.textPrimary(d))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val next = (valueState + 1).coerceIn(min, max)
                valueState = next
                seek.progress = next - min
                tvVal.text = formatValue(next)
                onLiveChange(next)
                onStopTracking?.invoke()
            }
        }

        val btnResetVal = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) }
            background = ThemeManager.circleButtonDrawable(context, d)
            setImageResource(R.drawable.ic_reset)
            setColorFilter(ThemeManager.textPrimary(d))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val next = defaultVal.coerceIn(min, max)
                valueState = next
                seek.progress = next - min
                tvVal.text = formatValue(next)
                onLiveChange(next)
                onStopTracking?.invoke()
            }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress
                valueState = v
                tvVal.text = formatValue(v)
                if (fromUser) onLiveChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                onStopTracking?.invoke()
            }
        })

        ctrlRow.addView(btnMinus)
        ctrlRow.addView(seek)
        ctrlRow.addView(btnPlus)
        ctrlRow.addView(btnResetVal)
        row.addView(ctrlRow)
        return row
    }

    private fun buildStripTile(
        iconRes: Int,
        label: String,
        active: Boolean,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        val d = isDark()
        val accent = Color.parseColor("#2F6BFF")
        val inactiveTint = ThemeManager.iconInactive(d)
        val activeTint = if (d) Color.WHITE else accent

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dp(64)
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            isClickable = enabled
            setPadding(dp(9), dp(7), dp(9), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(64)
            ).apply { marginEnd = dp(4) }
            background = if (active) {
                GradientDrawable().apply {
                    setColor(ThemeManager.selectedStripBubble(d))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), accent)
                }
            } else null

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(iconRes)
                setColorFilter(if (active) activeTint else inactiveTint)
            })
            addView(TextView(context).apply {
                text = label
                textSize = 10.5f
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(if (active) activeTint else inactiveTint)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
            if (enabled) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun buildWarpToolUi(body: LinearLayout) {
        val d = isDark()
        buildPopupShell(body, centerVertically = false, showActionCol = false) { leftCol ->
            leftCol.setPadding(dp(8), dp(4), dp(8), dp(4))
            val isBrush = canvasStage.warpMode == WarpEngine.WarpMode.BRUSH

            val topSlot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
                )
            }
            if (isBrush) {
                if (warpControlMode == "strength") {
                    topSlot.addView(
                        buildCompactSliderBar(
                            label = "Strength",
                            min = 5,
                            max = 100,
                            current = canvasStage.warpStrength,
                            defaultVal = 50,
                            formatValue = { "$it%" },
                            onLiveChange = { v -> canvasStage.warpStrength = v }
                        )
                    )
                } else {
                    topSlot.addView(
                        buildCompactSliderBar(
                            label = "Size",
                            min = 10,
                            max = 400,
                            current = canvasStage.warpSize.roundToInt(),
                            defaultVal = 160,
                            formatValue = { "$it px" },
                            onLiveChange = { v ->
                                canvasStage.warpSize = v.toFloat()
                                canvasStage.showBrushCenterPreview(v.toFloat(), "$v px")
                                scheduleHideBrushPreview()
                            },
                            onStopTracking = { scheduleHideBrushPreview() }
                        )
                    )
                }
            } else {
                topSlot.addView(TextView(context).apply {
                    text = "Paper Warp: drag 4×4 mesh handles directly on image"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(ThemeManager.textSecondary(d))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }
            leftCol.addView(topSlot)

            leftCol.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })

            val hs = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(buildStripTile(R.drawable.ic_quick_brush, "Brush Warp", isBrush) {
                canvasStage.switchWarpMode(WarpEngine.WarpMode.BRUSH)
                refreshActiveTool()
            })
            row.addView(buildStripTile(R.drawable.ic_size, "Size", isBrush && warpControlMode == "size", enabled = isBrush) {
                warpControlMode = "size"
                canvasStage.showBrushCenterPreview(canvasStage.warpSize, "${canvasStage.warpSize.roundToInt()} px")
                scheduleHideBrushPreview()
                refreshActiveTool()
            })
            row.addView(buildStripTile(R.drawable.ic_contrast, "Strength", isBrush && warpControlMode == "strength", enabled = isBrush) {
                warpControlMode = "strength"
                canvasStage.hideBrushCenterPreview()
                refreshActiveTool()
            })
            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(8)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })
            row.addView(buildStripTile(R.drawable.ic_grid, "Paper Warp", !isBrush) {
                canvasStage.hideBrushCenterPreview()
                canvasStage.switchWarpMode(WarpEngine.WarpMode.PAPER)
                refreshActiveTool()
            })

            hs.addView(row)
            leftCol.addView(hs)
        }
    }

    private fun buildCutoutToolUi(body: LinearLayout) {
        val d = isDark()
        buildPopupShell(body, centerVertically = false, showActionCol = false) { leftCol ->
            leftCol.setPadding(dp(8), dp(4), dp(8), dp(4))

            val topSlot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
                )
            }

            val curMode = canvasStage.cutoutMode
            when {
                cutoutSubPanel == "modify" -> {
                    val modBox = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    val tabCol = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    val tabs = listOf(
                        "feather" to "Feather",
                        "expand" to "Expand",
                        "smooth" to "Smooth",
                        "contrast" to "Contrast"
                    )
                    for ((tid, tlabel) in tabs) {
                        val active = cutoutModifyTab == tid
                        tabCol.addView(TextView(context).apply {
                            text = tlabel
                            textSize = 10.5f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            setPadding(dp(8), dp(5), dp(8), dp(5))
                            setTextColor(if (active) Color.WHITE else ThemeManager.textPrimary(d))
                            background = GradientDrawable().apply {
                                setColor(if (active) Color.parseColor("#2F6BFF") else ThemeManager.cardElevatedBg(d))
                                cornerRadius = dp(7).toFloat()
                                setStroke(dp(1), if (active) Color.parseColor("#2F6BFF") else ThemeManager.borderColor(d))
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(4) }
                            setOnClickListener {
                                cutoutModifyTab = tid
                                refreshActiveTool()
                            }
                        })
                    }
                    val sliderBox = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    }
                    val modSlider = when (cutoutModifyTab) {
                        "expand" -> buildCompactSliderBar("Expand", -20, 20, canvasStage.cutoutExpand, 0, { "${it}px" }, { v ->
                            canvasStage.updateCutoutModifiers(expandPx = v)
                        })
                        "smooth" -> buildCompactSliderBar("Smooth", 0, 25, canvasStage.cutoutSmooth, 0, { "${it}px" }, { v ->
                            canvasStage.updateCutoutModifiers(smoothPx = v)
                        })
                        "contrast" -> buildCompactSliderBar("Contrast", 0, 100, canvasStage.cutoutContrast, 0, { "$it%" }, { v ->
                            canvasStage.updateCutoutModifiers(contrastPct = v)
                        })
                        else -> buildCompactSliderBar("Feather", 0, 40, canvasStage.cutoutFeather, 0, { "${it}px" }, { v ->
                            canvasStage.updateCutoutModifiers(featherPx = v)
                        })
                    }
                    sliderBox.addView(modSlider)
                    val hsMod = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        layoutParams = LinearLayout.LayoutParams(dp(156), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = dp(4)
                        }
                        addView(tabCol)
                    }
                    modBox.addView(hsMod)
                    modBox.addView(sliderBox)
                    topSlot.addView(modBox)
                }

                cutoutSubPanel == "param" && (curMode == CutoutEngine.CutoutMode.QUICK_SELECT) -> {
                    topSlot.addView(
                        buildCompactSliderBar(
                            label = "Brush Size",
                            min = 8,
                            max = 150,
                            current = canvasStage.cutoutBrushRadius.roundToInt(),
                            defaultVal = 36,
                            formatValue = { "$it px" },
                            onLiveChange = { v ->
                                canvasStage.cutoutBrushRadius = v.toFloat()
                                canvasStage.showBrushCenterPreview(v * 2f, "$v px")
                                scheduleHideBrushPreview()
                            },
                            onStopTracking = { scheduleHideBrushPreview() }
                        )
                    )
                }

                cutoutSubPanel == "param" && (curMode == CutoutEngine.CutoutMode.MAGIC_WAND || curMode == CutoutEngine.CutoutMode.COLOR_RANGE || curMode == CutoutEngine.CutoutMode.AUTO_SELECT) -> {
                    topSlot.addView(
                        buildCompactSliderBar(
                            label = when (curMode) {
                                CutoutEngine.CutoutMode.AUTO_SELECT -> "Auto Sensitivity"
                                CutoutEngine.CutoutMode.COLOR_RANGE -> "Color Tolerance"
                                else -> "Wand Tolerance"
                            },
                            min = 5,
                            max = 120,
                            current = canvasStage.cutoutTolerance,
                            defaultVal = 36,
                            formatValue = { "$it" },
                            onLiveChange = { v -> canvasStage.cutoutTolerance = v }
                        )
                    )
                }

                else -> {
                    val opRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    opRow.addView(TextView(context).apply {
                        text = "Mode:"
                        textSize = 12f
                        setTextColor(ThemeManager.textSecondary(d))
                        setPadding(0, 0, dp(8), 0)
                    })
                    val ops = listOf(
                        CutoutEngine.SelectionOp.NEW to "New",
                        CutoutEngine.SelectionOp.ADD to "Add",
                        CutoutEngine.SelectionOp.SUB to "Subtract",
                        CutoutEngine.SelectionOp.INT to "Intersect"
                    )
                    for ((op, label) in ops) {
                        val active = canvasStage.cutoutOp == op
                        opRow.addView(TextView(context).apply {
                            text = label
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            setTextColor(if (active) Color.WHITE else ThemeManager.textPrimary(d))
                            background = GradientDrawable().apply {
                                setColor(if (active) Color.parseColor("#2F6BFF") else ThemeManager.cardElevatedBg(d))
                                cornerRadius = dp(999).toFloat()
                                setStroke(dp(1), if (active) Color.parseColor("#2F6BFF") else ThemeManager.borderColor(d))
                            }
                            setPadding(dp(11), dp(6), dp(11), dp(6))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(6) }
                            setOnClickListener {
                                canvasStage.cutoutOp = op
                                refreshActiveTool()
                            }
                        })
                    }
                    topSlot.addView(opRow)
                }
            }
            leftCol.addView(topSlot)

            leftCol.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })

            val hs = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun selectMode(m: CutoutEngine.CutoutMode) {
                if (m != CutoutEngine.CutoutMode.POLYGON) {
                    canvasStage.clearPolygonPoints()
                }
                canvasStage.cutoutMode = m
                cutoutSubPanel = when (m) {
                    CutoutEngine.CutoutMode.QUICK_SELECT,
                    CutoutEngine.CutoutMode.MAGIC_WAND,
                    CutoutEngine.CutoutMode.COLOR_RANGE,
                    CutoutEngine.CutoutMode.AUTO_SELECT -> "param"
                    else -> "op"
                }
                syncCutoutHudVisuals()
                refreshActiveTool()
            }

            row.addView(buildStripTile(R.drawable.ic_lasso, "Lasso", curMode == CutoutEngine.CutoutMode.LASSO) {
                selectMode(CutoutEngine.CutoutMode.LASSO)
            })
            row.addView(buildStripTile(R.drawable.ic_bezier, "Poly", curMode == CutoutEngine.CutoutMode.POLYGON) {
                selectMode(CutoutEngine.CutoutMode.POLYGON)
            })
            row.addView(buildStripTile(R.drawable.ic_marquee_rect, "Rect", curMode == CutoutEngine.CutoutMode.RECT) {
                selectMode(CutoutEngine.CutoutMode.RECT)
            })
            row.addView(buildStripTile(R.drawable.ic_marquee_ellipse, "Ellipse", curMode == CutoutEngine.CutoutMode.ELLIPSE) {
                selectMode(CutoutEngine.CutoutMode.ELLIPSE)
            })
            row.addView(buildStripTile(R.drawable.ic_quick_brush, "Brush", curMode == CutoutEngine.CutoutMode.QUICK_SELECT) {
                selectMode(CutoutEngine.CutoutMode.QUICK_SELECT)
            })
            row.addView(buildStripTile(R.drawable.ic_wand, "Wand", curMode == CutoutEngine.CutoutMode.MAGIC_WAND) {
                selectMode(CutoutEngine.CutoutMode.MAGIC_WAND)
            })
            row.addView(buildStripTile(R.drawable.ic_eyedropper, "Range", curMode == CutoutEngine.CutoutMode.COLOR_RANGE) {
                selectMode(CutoutEngine.CutoutMode.COLOR_RANGE)
            })
            row.addView(buildStripTile(R.drawable.ic_auto_select, "Auto", curMode == CutoutEngine.CutoutMode.AUTO_SELECT) {
                selectMode(CutoutEngine.CutoutMode.AUTO_SELECT)
            })

            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(8)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })

            row.addView(buildStripTile(R.drawable.ic_add, "Op", cutoutSubPanel == "op") {
                cutoutSubPanel = "op"
                refreshActiveTool()
            })
            row.addView(buildStripTile(R.drawable.ic_effects, "Refine", cutoutSubPanel == "modify") {
                cutoutSubPanel = "modify"
                refreshActiveTool()
            })

            hs.addView(row)
            leftCol.addView(hs)
        }
    }

    private fun buildRetouchToolUi(body: LinearLayout) {
        val d = isDark()
        buildPopupShell(body, centerVertically = false, showActionCol = false) { leftCol ->
            leftCol.setPadding(dp(8), dp(4), dp(8), dp(4))

            val topSlot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
                )
            }
            if (retouchControlMode == "strength") {
                topSlot.addView(
                    buildCompactSliderBar(
                        label = "Strength",
                        min = 5,
                        max = 100,
                        current = canvasStage.retouchStrength,
                        defaultVal = 55,
                        formatValue = { "$it%" },
                        onLiveChange = { v -> canvasStage.retouchStrength = v }
                    )
                )
            } else {
                topSlot.addView(
                    buildCompactSliderBar(
                        label = "Brush Size",
                        min = 8,
                        max = 180,
                        current = canvasStage.retouchSize.roundToInt(),
                        defaultVal = 60,
                        formatValue = { "$it px" },
                        onLiveChange = { v ->
                            canvasStage.retouchSize = v.toFloat()
                            canvasStage.showBrushCenterPreview(v.toFloat(), "$v px")
                            scheduleHideBrushPreview()
                        },
                        onStopTracking = { scheduleHideBrushPreview() }
                    )
                )
            }
            leftCol.addView(topSlot)

            leftCol.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })

            val hs = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val cur = canvasStage.retouchMode
            val modes = listOf(
                Triple(RetouchEngine.RetouchMode.HEAL, R.drawable.ic_retouch, "Heal"),
                Triple(RetouchEngine.RetouchMode.DODGE, R.drawable.ic_brightness, "Dodge"),
                Triple(RetouchEngine.RetouchMode.BURN, R.drawable.ic_contrast, "Burn"),
                Triple(RetouchEngine.RetouchMode.BLUR, R.drawable.ic_noise, "Blur"),
                Triple(RetouchEngine.RetouchMode.MOSAIC, R.drawable.ic_grid, "Mosaic")
            )
            for ((mode, iconRes, label) in modes) {
                row.addView(buildStripTile(iconRes, label, cur == mode) {
                    canvasStage.retouchMode = mode
                    refreshActiveTool()
                })
            }

            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(8)
                }
                setBackgroundColor(ThemeManager.dividerColor(d))
            })

            row.addView(buildStripTile(R.drawable.ic_size, "Size", retouchControlMode == "size") {
                retouchControlMode = "size"
                canvasStage.showBrushCenterPreview(canvasStage.retouchSize, "${canvasStage.retouchSize.roundToInt()} px")
                scheduleHideBrushPreview()
                refreshActiveTool()
            })
            row.addView(buildStripTile(R.drawable.ic_opacity, "Strength", retouchControlMode == "strength") {
                retouchControlMode = "strength"
                canvasStage.hideBrushCenterPreview()
                refreshActiveTool()
            })

            hs.addView(row)
            leftCol.addView(hs)
        }
    }
}
