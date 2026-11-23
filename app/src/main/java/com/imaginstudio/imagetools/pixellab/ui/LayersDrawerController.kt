package com.imaginstudio.imagetools.pixellab.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.imaginstudio.imagetools.pixellab.R
import com.imaginstudio.imagetools.pixellab.databinding.ActivityMainBinding
import com.imaginstudio.imagetools.pixellab.databinding.ItemLayerBinding
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LayersDrawerController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val getProject: () -> ProjectState,
    private val getSelectedId: () -> String?,
    private val onSelectLayer: (String?) -> Unit,
    private val onMutateProject: ((ProjectState) -> ProjectState) -> Unit,
    private val onUpdateProjectLive: (ProjectState) -> Unit,
    private val onPushHistory: (ProjectState) -> Unit
) {
    private var multiSelectMode = false
    private val checkedIds = LinkedHashSet<String>()
    private val previewCache = object : LruCache<String, Bitmap>(48) {}
    private val copySeq = AtomicInteger(0)

    private var isDragging = false
    private var projectBeforeDrag: ProjectState? = null
    private val mergeExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isMerging = false
    private lateinit var adapter: LayerAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    init {
        adapter = LayerAdapter()
        binding.layersRecycler.layoutManager = LinearLayoutManager(context)
        binding.layersRecycler.adapter = adapter
        binding.layersRecycler.itemAnimator?.moveDuration = 120L

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            // Dragging is initiated explicitly via dragHandle so long-pressing a layer enters selection mode.
            override fun isLongPressDragEnabled(): Boolean = false

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false
                }
                adapter.moveItem(from, to)
                val updatedOrder = adapter.items.reversed()
                val cur = getProject()
                if (cur.layers != updatedOrder) {
                    onUpdateProjectLive(cur.copy(layers = updatedOrder))
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    if (!isDragging) {
                        isDragging = true
                        projectBeforeDrag = getProject()
                    }
                    viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.94f).setDuration(90L).start()
                    viewHolder.itemView.elevation = 16f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(90L).start()
                viewHolder.itemView.elevation = 0f
                if (isDragging) {
                    isDragging = false
                    val before = projectBeforeDrag
                    projectBeforeDrag = null
                    val after = getProject()
                    if (before != null && before.layers.map { it.id } != after.layers.map { it.id }) {
                        onPushHistory(before)
                    }
                    refresh()
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(binding.layersRecycler)

        binding.btnCloseLayers.setOnClickListener {
            exitMultiSelectMode(refreshList = false)
            binding.layersDrawer.visibility = View.GONE
        }

        binding.btnLayersMultiSelect.setOnClickListener {
            if (multiSelectMode) {
                exitMultiSelectMode()
            } else {
                enterMultiSelectMode(initialLayerId = null)
            }
        }

        binding.btnLayersSelectAll.setOnClickListener {
            val allIds = getProject().layers.map { it.id }
            if (allIds.isEmpty()) return@setOnClickListener
            if (checkedIds.size == allIds.size) {
                checkedIds.clear()
            } else {
                checkedIds.clear()
                checkedIds.addAll(allIds)
            }
            syncMultiSelectUi()
            refresh()
        }

        binding.btnBatchHide.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            onMutateProject { proj ->
                val anyVisible = proj.layers.any { it.id in checkedIds && !it.hidden }
                proj.copy(layers = proj.layers.map {
                    if (it.id in checkedIds) it.copyBase(hidden = anyVisible) else it
                })
            }
        }

        binding.btnBatchLock.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            onMutateProject { proj ->
                val anyUnlocked = proj.layers.any { it.id in checkedIds && !it.locked }
                proj.copy(layers = proj.layers.map {
                    if (it.id in checkedIds) it.copyBase(locked = anyUnlocked) else it
                })
            }
        }

        binding.btnBatchCopy.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            val baseTime = System.currentTimeMillis()
            val newSelectedIds = LinkedHashSet<String>()
            onMutateProject { proj ->
                val selectedInOrder = proj.layers.filter { it.id in checkedIds }
                val clones = selectedInOrder.map { src ->
                    val cloneId = "copy_${baseTime}_${copySeq.incrementAndGet()}"
                    newSelectedIds.add(cloneId)
                    src.copyBase(
                        id = cloneId,
                        name = "${src.name} Copy",
                        x = (src.x + 32f).coerceAtMost(proj.width.toFloat()),
                        y = (src.y + 32f).coerceAtMost(proj.height.toFloat())
                    )
                }
                proj.copy(layers = proj.layers + clones)
            }
            if (newSelectedIds.isNotEmpty()) {
                checkedIds.clear()
                checkedIds.addAll(newSelectedIds)
                syncMultiSelectUi()
                refresh()
                Toast.makeText(context, "Duplicated ${newSelectedIds.size} layer(s)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBatchFront.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            onMutateProject { proj ->
                val unselected = proj.layers.filterNot { it.id in checkedIds }
                val selected = proj.layers.filter { it.id in checkedIds }
                proj.copy(layers = unselected + selected)
            }
        }

        binding.btnBatchBack.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            onMutateProject { proj ->
                val selected = proj.layers.filter { it.id in checkedIds }
                val unselected = proj.layers.filterNot { it.id in checkedIds }
                proj.copy(layers = selected + unselected)
            }
        }

        binding.btnBatchAlign.setOnClickListener { anchor ->
            if (checkedIds.isEmpty()) return@setOnClickListener
            val menu = PopupMenu(context, anchor)
            menu.menu.add(0, 1, 0, "Align Left")
            menu.menu.add(0, 2, 1, "Center Horizontally")
            menu.menu.add(0, 3, 2, "Align Right")
            menu.menu.add(0, 4, 3, "Align Top")
            menu.menu.add(0, 5, 4, "Center Vertically")
            menu.menu.add(0, 6, 5, "Align Bottom")
            menu.setOnMenuItemClickListener { item ->
                applyBatchAlign(item.itemId)
                true
            }
            menu.show()
        }

        binding.btnBatchDelete.setOnClickListener {
            if (checkedIds.isEmpty()) return@setOnClickListener
            val deleteSet = HashSet(checkedIds)
            val prevSelected = getSelectedId()
            onMutateProject { proj ->
                proj.copy(layers = proj.layers.filterNot { it.id in deleteSet })
            }
            checkedIds.clear()
            if (prevSelected != null && prevSelected in deleteSet) {
                onSelectLayer(null)
            }
            if (getProject().layers.isEmpty()) {
                exitMultiSelectMode()
            } else {
                syncMultiSelectUi()
                refresh()
            }
        }

        binding.btnBatchMerge.setOnClickListener {
            if (isMerging) return@setOnClickListener
            if (checkedIds.size < 2) {
                Toast.makeText(context, "Select 2 or more layers to merge", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val proj = getProject()
            val mergeIds = LinkedHashSet(checkedIds)
            val selectedLayers = proj.layers.filter { it.id in mergeIds }
            if (selectedLayers.size < 2) return@setOnClickListener

            isMerging = true
            binding.btnBatchMerge.isEnabled = false
            val tempProject = proj.copy(layers = selectedLayers)
            val appCtx = context.applicationContext
            mergeExecutor.execute {
                val mergeFile = runCatching {
                    val mergedBmp = PixelLabRenderer.exportToBitmap(
                        appCtx,
                        tempProject,
                        proj.width,
                        proj.height,
                        includeBackground = false
                    )
                    val outFile = File(appCtx.cacheDir, "merged_${System.currentTimeMillis()}.png")
                    FileOutputStream(outFile).use { out ->
                        mergedBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    mergedBmp.recycle()
                    outFile
                }.getOrNull()

                binding.layersDrawer.post {
                    isMerging = false
                    binding.btnBatchMerge.isEnabled = true
                    if (mergeFile == null) {
                        Toast.makeText(context, "Failed to merge layers", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    val mergedLayer = ImageLayer(
                        id = "merged_${System.currentTimeMillis()}",
                        name = "Merged Layer",
                        x = proj.width / 2f,
                        y = proj.height / 2f,
                        width = proj.width.toFloat(),
                        height = proj.height.toFloat(),
                        uriString = mergeFile.absolutePath
                    )
                    onMutateProject { p ->
                        val topIdx = p.layers.indexOfLast { it.id in mergeIds }
                        val beforeCount = if (topIdx >= 0) {
                            p.layers.subList(0, topIdx + 1).count { it.id !in mergeIds }
                        } else {
                            p.layers.count { it.id !in mergeIds }
                        }
                        val remaining = p.layers.filterNot { it.id in mergeIds }.toMutableList()
                        remaining.add(beforeCount.coerceIn(0, remaining.size), mergedLayer)
                        p.copy(layers = remaining)
                    }
                    exitMultiSelectMode(refreshList = false)
                    onSelectLayer(mergedLayer.id)
                }
            }
        }
    }

    private fun applyBatchAlign(mode: Int) {
        onMutateProject { proj ->
            val targets = proj.layers.filter { it.id in checkedIds }
            if (targets.isEmpty()) return@onMutateProject proj

            // Single selected layer aligns to the canvas; 2+ selected layers align to their group bounds.
            val minLeft: Float
            val maxRight: Float
            val minTop: Float
            val maxBottom: Float
            if (targets.size == 1) {
                minLeft = 0f
                maxRight = proj.width.toFloat()
                minTop = 0f
                maxBottom = proj.height.toFloat()
            } else {
                minLeft = targets.minOf { it.x - it.width / 2f }
                maxRight = targets.maxOf { it.x + it.width / 2f }
                minTop = targets.minOf { it.y - it.height / 2f }
                maxBottom = targets.maxOf { it.y + it.height / 2f }
            }
            val centerX = (minLeft + maxRight) / 2f
            val centerY = (minTop + maxBottom) / 2f

            proj.copy(layers = proj.layers.map { l ->
                if (l.id !in checkedIds) l else {
                    when (mode) {
                        1 -> l.copyBase(x = minLeft + l.width / 2f)
                        2 -> l.copyBase(x = centerX)
                        3 -> l.copyBase(x = maxRight - l.width / 2f)
                        4 -> l.copyBase(y = minTop + l.height / 2f)
                        5 -> l.copyBase(y = centerY)
                        6 -> l.copyBase(y = maxBottom - l.height / 2f)
                        else -> l
                    }
                }
            })
        }
    }

    private fun enterMultiSelectMode(initialLayerId: String?) {
        multiSelectMode = true
        checkedIds.clear()
        if (initialLayerId != null) {
            checkedIds.add(initialLayerId)
        }
        syncMultiSelectUi()
        refresh()
    }

    private fun exitMultiSelectMode(refreshList: Boolean = true) {
        multiSelectMode = false
        checkedIds.clear()
        syncMultiSelectUi()
        if (refreshList) refresh()
    }

    private fun syncMultiSelectUi() {
        val project = getProject()
        val existingIds = project.layers.mapTo(HashSet()) { it.id }
        checkedIds.retainAll(existingIds)

        if (!multiSelectMode) {
            binding.layersTitle.text = "Layers"
            binding.btnLayersSelectAll.visibility = View.GONE
            binding.btnLayersMultiSelect.text = "Select"
            binding.layersBatchBar.visibility = View.GONE
            return
        }

        val count = checkedIds.size
        binding.layersTitle.text = "$count selected"
        binding.btnLayersSelectAll.visibility = if (project.layers.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnLayersSelectAll.text = if (project.layers.isNotEmpty() && count == project.layers.size) "None" else "All"
        binding.btnLayersMultiSelect.text = "Done"
        binding.layersBatchBar.visibility = View.VISIBLE

        val selectedLayers = project.layers.filter { it.id in checkedIds }
        val hasSelection = selectedLayers.isNotEmpty()
        val allHidden = hasSelection && selectedLayers.all { it.hidden }
        val allLocked = hasSelection && selectedLayers.all { it.locked }

        binding.tvBatchHide.text = if (allHidden) "Show" else "Hide"
        binding.tvBatchLock.text = if (allLocked) "Unlock" else "Lock"
        binding.ivBatchLock.setImageResource(if (allLocked) R.drawable.ic_unlock else R.drawable.ic_lock)

        val actionAlpha = if (hasSelection) 1f else 0.38f
        val batchButtons = listOf(
            binding.btnBatchHide,
            binding.btnBatchLock,
            binding.btnBatchCopy,
            binding.btnBatchDelete,
            binding.btnBatchFront,
            binding.btnBatchBack,
            binding.btnBatchAlign
        )
        for (btn in batchButtons) {
            btn.alpha = actionAlpha
            btn.isEnabled = hasSelection
        }
        binding.btnBatchMerge.alpha = if (count >= 2 && !isMerging) 1f else 0.42f
        binding.btnBatchMerge.isEnabled = !isMerging
    }

    fun destroy() {
        mergeExecutor.shutdownNow()
        previewCache.evictAll()
    }

    fun toggleDrawer() {
        val visible = binding.layersDrawer.visibility == View.VISIBLE
        if (visible) {
            exitMultiSelectMode(refreshList = false)
            binding.layersDrawer.visibility = View.GONE
        } else {
            binding.layersDrawer.visibility = View.VISIBLE
            syncMultiSelectUi()
            refresh()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshTheme() {
        syncMultiSelectUi()
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        if (binding.layersDrawer.visibility != View.VISIBLE || isDragging) return
        val project = getProject()
        syncMultiSelectUi()
        adapter.submitList(project.layers.reversed())
        adapter.notifyDataSetChanged()
    }

    private fun getLayerThumbnail(layer: CanvasLayer): Bitmap {
        val visualNorm = layer.copyBase(x = 0f, y = 0f, hidden = false, locked = false)
        val key = "${layer.id}_${visualNorm.hashCode()}"
        previewCache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = PixelLabRenderer.renderLayerPreviewBitmap(context, layer, 96)
        previewCache.put(key, bmp)
        return bmp
    }

    inner class LayerViewHolder(val itemBinding: ItemLayerBinding) :
        RecyclerView.ViewHolder(itemBinding.root)

    inner class LayerAdapter : RecyclerView.Adapter<LayerViewHolder>() {
        val items = ArrayList<CanvasLayer>()

        fun submitList(newList: List<CanvasLayer>) {
            items.clear()
            items.addAll(newList)
        }

        fun moveItem(from: Int, to: Int) {
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(items, i, i - 1)
                }
            }
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
            val vb = ItemLayerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return LayerViewHolder(vb)
        }

        override fun getItemCount(): Int = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
            val layer = items[position]
            val item = holder.itemBinding
            val selectedId = getSelectedId()
            val isChecked = layer.id in checkedIds
            val isHighlighted = if (multiSelectMode) isChecked else (layer.id == selectedId)

            val dark = ThemeManager.isDark(context)
            item.layerName.text = when (layer) {
                is TextLayer -> layer.text.replace("\n", " ").ifBlank { "Text" }
                else -> layer.name
            }
            item.layerName.setTextColor(ThemeManager.textPrimary(dark))

            val bg = (item.root.background as? GradientDrawable) ?: GradientDrawable().also {
                it.cornerRadius = 10f
                item.root.background = it
            }
            bg.setColor(if (isHighlighted) ThemeManager.accentSoftBg(dark) else ThemeManager.cardElevatedBg(dark))
            bg.setStroke(2, if (isHighlighted) ThemeManager.accentPrimary(dark) else ThemeManager.borderColor(dark))

            item.layerPreview.setImageBitmap(getLayerThumbnail(layer))
            item.layerPreview.alpha = if (layer.hidden) 0.45f else 1f

            item.dragHandle.setColorFilter(ThemeManager.iconInactive(dark))
            item.dragHandle.visibility = if (multiSelectMode) View.GONE else View.VISIBLE
            item.dragHandle.setOnTouchListener { _, event ->
                if (!multiSelectMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }

            item.layerCheck.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
            item.layerCheck.buttonTintList = android.content.res.ColorStateList.valueOf(ThemeManager.accentPrimary(dark))
            item.layerCheck.setOnCheckedChangeListener(null)
            item.layerCheck.isChecked = isChecked
            item.layerCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) checkedIds.add(layer.id) else checkedIds.remove(layer.id)
                bg.setColor(if (checked) ThemeManager.accentSoftBg(dark) else ThemeManager.cardElevatedBg(dark))
                bg.setStroke(2, if (checked) ThemeManager.accentPrimary(dark) else ThemeManager.borderColor(dark))
                syncMultiSelectUi()
            }

            val rowActionVis = if (multiSelectMode) View.GONE else View.VISIBLE
            item.btnLayerHide.visibility = rowActionVis
            item.btnLayerLock.visibility = rowActionVis
            item.btnLayerDelete.visibility = rowActionVis

            item.btnLayerHide.setColorFilter(ThemeManager.iconInactive(dark))
            item.btnLayerLock.setColorFilter(if (layer.locked) ThemeManager.accentPrimary(dark) else ThemeManager.iconInactive(dark))
            item.btnLayerDelete.setColorFilter(ThemeManager.dangerColor(dark))
            item.btnLayerHide.alpha = if (layer.hidden) 0.35f else 1f
            item.btnLayerLock.alpha = if (layer.locked) 1f else 0.4f

            item.root.isLongClickable = true
            item.root.setOnClickListener {
                if (multiSelectMode) {
                    item.layerCheck.isChecked = !item.layerCheck.isChecked
                } else {
                    onSelectLayer(layer.id)
                }
            }
            item.root.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!multiSelectMode) {
                    enterMultiSelectMode(initialLayerId = layer.id)
                } else {
                    if (layer.id in checkedIds) checkedIds.remove(layer.id) else checkedIds.add(layer.id)
                    syncMultiSelectUi()
                    refresh()
                }
                true
            }

            item.btnLayerHide.setOnClickListener {
                onMutateProject { p ->
                    p.copy(layers = p.layers.map {
                        if (it.id == layer.id) it.copyBase(hidden = !it.hidden) else it
                    })
                }
            }

            item.btnLayerLock.setOnClickListener {
                onMutateProject { p ->
                    p.copy(layers = p.layers.map {
                        if (it.id == layer.id) it.copyBase(locked = !it.locked) else it
                    })
                }
            }

            item.btnLayerDelete.setOnClickListener {
                onMutateProject { p ->
                    p.copy(layers = p.layers.filterNot { it.id == layer.id })
                }
                if (selectedId == layer.id) onSelectLayer(null)
            }
        }
    }
}
