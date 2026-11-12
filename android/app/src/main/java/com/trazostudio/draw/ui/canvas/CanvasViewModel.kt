package com.trazostudio.draw.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trazostudio.draw.data.ProjectStorage
import com.trazostudio.engine.BrushSettings
import com.trazostudio.engine.CanvasStats
import com.trazostudio.engine.LayerInfo
import com.trazostudio.engine.RustCanvasSession
import com.trazostudio.engine.ToolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado observable del estudio de dibujo consumido por CanvasScreen y ExportScreen.
 */
data class CanvasUiState(
    val projectTitle: String = "Boceto",
    val canvasWidth: Int = 1080,
    val canvasHeight: Int = 1080,
    val bitmap: Bitmap? = null,
    val brush: BrushSettings = BrushSettings(),
    val isPanMode: Boolean = false,
    val recentColors: List<Int> = listOf(
        0xFF1F2937.toInt(),
        0xFFEF4444.toInt(),
        0xFFF59E0B.toInt(),
        0xFF10B981.toInt(),
        0xFF3B82F6.toInt(),
        0xFF8B5CF6.toInt(),
        0xFFEC4899.toInt(),
        0xFFFFFFFF.toInt()
    ),
    val layers: List<LayerInfo> = emptyList(),
    val stats: CanvasStats = CanvasStats(),
    val archInfo: String = "Rust Native",
    val isBusy: Boolean = false,
    val statusBanner: String? = null
)

/**
 * ViewModel del lienzo que coordina todas las llamadas hacia `RustCanvasSession`
 * usando `viewModelScope` y corrutinas en segundo plano (sin bloquear el hilo UI).
 * Agrupa segmentos pendientes en lotes para eliminar latencia durante trazos rápidos.
 */
class CanvasViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CanvasUiState())
    val uiState: StateFlow<CanvasUiState> = _uiState.asStateFlow()

    private var session: RustCanvasSession? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    // Cola sincronizada para agrupar eventos táctiles de trazo en un único render por cuadro.
    private val strokeQueueLock = Any()
    private var pendingBeginTransaction = false
    private var pendingHasDab = false
    private var pendingDabX = 0f
    private var pendingDabY = 0f
    private val pendingSegmentCoords = ArrayList<Float>(128)
    private var pendingBrush: BrushSettings? = null
    private var pendingCancelUndo = false
    private var pendingStatsRefresh = false
    private var flushRunning = false
    private var strokeStartedInGesture = false

    fun startNewSession(title: String, width: Int, height: Int) {
        synchronized(strokeQueueLock) {
            pendingBeginTransaction = false
            pendingHasDab = false
            pendingSegmentCoords.clear()
            pendingBrush = null
            pendingCancelUndo = false
            pendingStatsRefresh = false
            strokeStartedInGesture = false
        }
        session?.close()
        val newSession = RustCanvasSession(width, height)
        session = newSession

        _uiState.update {
            it.copy(
                projectTitle = title,
                canvasWidth = width,
                canvasHeight = height,
                bitmap = null,
                isPanMode = false,
                isBusy = true,
                statusBanner = null
            )
        }

        viewModelScope.launch {
            val initialBitmap = newSession.initialize()
            val layers = newSession.getLayers()
            val stats = newSession.getStats()
            val arch = newSession.getRustArchInfo()
            _uiState.update {
                it.copy(
                    bitmap = initialBitmap,
                    layers = layers,
                    stats = stats,
                    archInfo = arch,
                    isBusy = false
                )
            }
        }
    }

    fun selectTool(tool: ToolType) {
        _uiState.update { state ->
            state.copy(
                brush = state.brush.copy(tool = tool),
                isPanMode = false
            )
        }
    }

    fun togglePanMode() {
        _uiState.update { state ->
            state.copy(isPanMode = !state.isPanMode)
        }
    }

    fun setPanMode(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(isPanMode = enabled)
        }
    }

    fun updateColor(argbColor: Int) {
        _uiState.update { state ->
            val updatedRecent = (listOf(argbColor) + state.recentColors.filter { it != argbColor }).take(10)
            state.copy(
                brush = state.brush.copy(argbColor = argbColor),
                recentColors = updatedRecent
            )
        }
    }

    fun updateBrushParameters(
        radiusPx: Float = _uiState.value.brush.radiusPx,
        opacity: Float = _uiState.value.brush.opacity,
        hardness: Float = _uiState.value.brush.hardness,
        tolerance: Int = _uiState.value.brush.fillTolerance
    ) {
        _uiState.update { state ->
            state.copy(
                brush = state.brush.copy(
                    radiusPx = radiusPx.coerceIn(1f, 120f),
                    opacity = opacity.coerceIn(0.05f, 1f),
                    hardness = hardness.coerceIn(0f, 1f),
                    fillTolerance = tolerance.coerceIn(0, 96)
                )
            )
        }
    }

    fun onCanvasTouchDown(canvasX: Float, canvasY: Float) {
        val activeSession = session ?: return
        if (_uiState.value.isPanMode) return
        val currentBrush = _uiState.value.brush
        lastX = canvasX
        lastY = canvasY

        when (currentBrush.tool) {
            ToolType.EYEDROPPER -> {
                strokeStartedInGesture = false
                viewModelScope.launch {
                    val picked = activeSession.pickColor(canvasX.toInt(), canvasY.toInt())
                    updateColor(picked)
                    _uiState.update {
                        it.copy(
                            brush = it.brush.copy(tool = ToolType.BRUSH, argbColor = picked),
                            statusBanner = "Color capturado con cuentagotas"
                        )
                    }
                }
            }
            ToolType.BUCKET -> {
                strokeStartedInGesture = true
                viewModelScope.launch {
                    val (changed, bmp) = activeSession.floodFill(
                        canvasX.toInt(),
                        canvasY.toInt(),
                        currentBrush.argbColor,
                        currentBrush.fillTolerance
                    )
                    if (changed) {
                        val stats = activeSession.getStats()
                        _uiState.update { it.copy(bitmap = bmp, stats = stats) }
                    } else {
                        strokeStartedInGesture = false
                    }
                }
            }
            else -> {
                strokeStartedInGesture = true
                synchronized(strokeQueueLock) {
                    pendingBeginTransaction = true
                    pendingHasDab = true
                    pendingDabX = canvasX
                    pendingDabY = canvasY
                    pendingBrush = currentBrush
                    pendingCancelUndo = false
                }
                scheduleStrokeFlush(activeSession)
            }
        }
    }

    fun onCanvasTouchMove(canvasX: Float, canvasY: Float) {
        val activeSession = session ?: return
        if (_uiState.value.isPanMode) return
        val currentBrush = _uiState.value.brush
        if (!currentBrush.tool.isStrokeTool) return

        val x0 = lastX
        val y0 = lastY
        lastX = canvasX
        lastY = canvasY

        synchronized(strokeQueueLock) {
            pendingSegmentCoords.add(x0)
            pendingSegmentCoords.add(y0)
            pendingSegmentCoords.add(canvasX)
            pendingSegmentCoords.add(canvasY)
            pendingBrush = currentBrush
        }
        scheduleStrokeFlush(activeSession)
    }

    fun onCanvasTouchUp() {
        val activeSession = session ?: return
        strokeStartedInGesture = false
        synchronized(strokeQueueLock) {
            pendingStatsRefresh = true
        }
        scheduleStrokeFlush(activeSession)
    }

    /**
     * Si el usuario apoya un segundo dedo inmediatamente tras tocar con el primero para hacer zoom/pan,
     * revierte el punto accidental creado al inicio del gesto.
     */
    fun cancelAccidentalTwoFingerStroke() {
        val activeSession = session ?: return
        if (!strokeStartedInGesture) return
        strokeStartedInGesture = false

        synchronized(strokeQueueLock) {
            if (pendingBeginTransaction && pendingHasDab) {
                pendingBeginTransaction = false
                pendingHasDab = false
                pendingSegmentCoords.clear()
                pendingStatsRefresh = true
            } else {
                pendingSegmentCoords.clear()
                pendingCancelUndo = true
                pendingStatsRefresh = true
            }
        }
        scheduleStrokeFlush(activeSession)
    }

    private fun scheduleStrokeFlush(activeSession: RustCanvasSession) {
        synchronized(strokeQueueLock) {
            if (flushRunning) return
            flushRunning = true
        }

        viewModelScope.launch {
            while (true) {
                var beginTx = false
                var hasDab = false
                var dabX = 0f
                var dabY = 0f
                var coords = FloatArray(0)
                var brush: BrushSettings? = null
                var doCancelUndo = false
                var doStatsRefresh = false

                synchronized(strokeQueueLock) {
                    val hasDrawWork = pendingBeginTransaction || pendingHasDab || pendingSegmentCoords.isNotEmpty()
                    if (!hasDrawWork && !pendingCancelUndo && !pendingStatsRefresh) {
                        flushRunning = false
                        return@launch
                    }
                    beginTx = pendingBeginTransaction
                    hasDab = pendingHasDab
                    dabX = pendingDabX
                    dabY = pendingDabY
                    if (pendingSegmentCoords.isNotEmpty()) {
                        coords = pendingSegmentCoords.toFloatArray()
                        pendingSegmentCoords.clear()
                    }
                    brush = pendingBrush
                    doCancelUndo = pendingCancelUndo
                    doStatsRefresh = pendingStatsRefresh
                    pendingBeginTransaction = false
                    pendingHasDab = false
                    pendingCancelUndo = false
                    pendingStatsRefresh = false
                }

                if (beginTx || hasDab || coords.isNotEmpty()) {
                    val targetBrush = brush ?: _uiState.value.brush
                    val bmp = activeSession.drawStrokeBatch(
                        beginTransaction = beginTx,
                        hasDab = hasDab,
                        dabX = dabX,
                        dabY = dabY,
                        segmentCoords = coords,
                        floatCount = coords.size,
                        brush = targetBrush
                    )
                    _uiState.update { state ->
                        state.copy(
                            bitmap = bmp,
                            stats = if (beginTx) state.stats.copy(canUndo = true) else state.stats
                        )
                    }
                }

                if (doCancelUndo) {
                    val (ok, bmp) = activeSession.undo()
                    if (ok) {
                        val stats = activeSession.getStats()
                        _uiState.update { it.copy(bitmap = bmp, stats = stats) }
                        doStatsRefresh = false
                    }
                }

                if (doStatsRefresh) {
                    val stats = activeSession.getStats()
                    _uiState.update { it.copy(stats = stats) }
                }
            }
        }
    }

    fun undo() {
        val activeSession = session ?: return
        viewModelScope.launch {
            val (ok, bmp) = activeSession.undo()
            if (ok) {
                val stats = activeSession.getStats()
                _uiState.update { it.copy(bitmap = bmp, stats = stats) }
            }
        }
    }

    fun redo() {
        val activeSession = session ?: return
        viewModelScope.launch {
            val (ok, bmp) = activeSession.redo()
            if (ok) {
                val stats = activeSession.getStats()
                _uiState.update { it.copy(bitmap = bmp, stats = stats) }
            }
        }
    }

    fun clearActiveLayer() {
        val activeSession = session ?: return
        viewModelScope.launch {
            val bmp = activeSession.clearActiveLayer()
            val stats = activeSession.getStats()
            _uiState.update { it.copy(bitmap = bmp, stats = stats) }
        }
    }

    fun addLayer() {
        val activeSession = session ?: return
        viewModelScope.launch {
            val nextNum = _uiState.value.layers.size
            val bmp = activeSession.addLayer("Capa $nextNum")
            refreshLayersAndBitmap(activeSession, bmp)
        }
    }

    fun deleteLayer(index: Int) {
        val activeSession = session ?: return
        viewModelScope.launch {
            val bmp = activeSession.deleteLayer(index)
            refreshLayersAndBitmap(activeSession, bmp)
        }
    }

    fun toggleLayerVisibility(index: Int, visible: Boolean) {
        val activeSession = session ?: return
        viewModelScope.launch {
            val bmp = activeSession.setLayerVisibility(index, visible)
            refreshLayersAndBitmap(activeSession, bmp)
        }
    }

    fun setLayerOpacity(index: Int, opacity: Float) {
        val activeSession = session ?: return
        viewModelScope.launch {
            val bmp = activeSession.setLayerOpacity(index, opacity)
            refreshLayersAndBitmap(activeSession, bmp)
        }
    }

    fun selectActiveLayer(index: Int) {
        val activeSession = session ?: return
        viewModelScope.launch {
            activeSession.setActiveLayer(index)
            val stats = activeSession.getStats()
            _uiState.update { it.copy(stats = stats) }
        }
    }

    fun mergeLayerDown(index: Int) {
        val activeSession = session ?: return
        viewModelScope.launch {
            val bmp = activeSession.mergeLayerDown(index)
            refreshLayersAndBitmap(activeSession, bmp)
        }
    }

    private suspend fun refreshLayersAndBitmap(activeSession: RustCanvasSession, bmp: Bitmap) {
        val layers = activeSession.getLayers()
        val stats = activeSession.getStats()
        _uiState.update { it.copy(bitmap = bmp, layers = layers, stats = stats) }
    }

    suspend fun exportPngBytes(includeBackground: Boolean): ByteArray {
        val activeSession = session ?: return ByteArray(0)
        return activeSession.exportPngBytes(includeBackground)
    }

    fun saveToInternalGallery(context: Context, onComplete: (Boolean) -> Unit) {
        val activeSession = session ?: return
        val title = _uiState.value.projectTitle
        val w = _uiState.value.canvasWidth
        val h = _uiState.value.canvasHeight

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val pngBytes = activeSession.exportPngBytes(includeBackground = true)
            val ok = pngBytes.isNotEmpty()
            if (ok) {
                ProjectStorage.saveProjectPreview(context, title, w, h, pngBytes)
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusBanner = if (ok) "Guardado en la galería del estudio" else "Error al guardar"
                )
            }
            onComplete(ok)
        }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    override fun onCleared() {
        super.onCleared()
        session?.close()
        session = null
    }
}
