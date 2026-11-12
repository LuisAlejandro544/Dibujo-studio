package com.trazostudio.draw.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trazostudio.draw.data.ProjectStorage
import com.trazostudio.draw.data.SavedDrawingItem
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
import java.io.File

/**
 * Estado de la imagen de referencia ("calco") cargada desde la galería del teléfono.
 * Se muestra solo como guía visual sobre el lienzo y no forma parte del PNG exportado.
 */
data class TracingReferenceState(
    val bitmap: Bitmap? = null,
    val visible: Boolean = true,
    val opacity: Float = 0.45f,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isLoading: Boolean = false
)

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
    val tracing: TracingReferenceState = TracingReferenceState(),
    val recentColors: List<Int> = listOf(
        0xFF000000.toInt(),
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
    private var currentSavedFile: File? = null
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
        currentSavedFile = null
        val newSession = RustCanvasSession(width, height)
        session = newSession

        _uiState.update {
            it.copy(
                projectTitle = title,
                canvasWidth = width,
                canvasHeight = height,
                bitmap = null,
                isPanMode = false,
                tracing = TracingReferenceState(),
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

    /**
     * Abre una obra guardada previamente desde el disco y la decodifica en Rust
     * para continuar dibujando sobre el mismo contenido sin perder trazos.
     */
    fun openSavedDrawing(item: SavedDrawingItem) {
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
        currentSavedFile = item.previewFile
        val newSession = RustCanvasSession(item.width, item.height)
        session = newSession

        _uiState.update {
            it.copy(
                projectTitle = item.title,
                canvasWidth = item.width,
                canvasHeight = item.height,
                bitmap = null,
                isPanMode = false,
                tracing = TracingReferenceState(),
                isBusy = true,
                statusBanner = null
            )
        }

        viewModelScope.launch {
            var renderedBitmap = newSession.initialize()
            val pngBytes = ProjectStorage.readFileBytes(item.previewFile)
            var loadError = false
            if (pngBytes != null && pngBytes.isNotEmpty()) {
                val (loaded, bmp) = newSession.loadPngBytes(pngBytes)
                renderedBitmap = bmp
                loadError = !loaded
            } else {
                loadError = true
            }
            val layers = newSession.getLayers()
            val stats = newSession.getStats()
            val arch = newSession.getRustArchInfo()
            _uiState.update {
                it.copy(
                    bitmap = renderedBitmap,
                    layers = layers,
                    stats = stats,
                    archInfo = arch,
                    isBusy = false,
                    statusBanner = if (loadError) "No se pudo leer el contenido guardado" else null
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
                currentSavedFile = ProjectStorage.saveProjectPreview(
                    context = context,
                    projectTitle = title,
                    width = w,
                    height = h,
                    pngBytes = pngBytes,
                    previousFile = currentSavedFile
                )
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

    fun loadTracingImage(context: Context, uri: Uri) {
        _uiState.update { it.copy(tracing = it.tracing.copy(isLoading = true)) }
        viewModelScope.launch {
            val decoded = ProjectStorage.decodeTracingBitmap(context, uri)
            if (decoded != null) {
                _uiState.update { state ->
                    state.copy(
                        tracing = TracingReferenceState(
                            bitmap = decoded,
                            visible = true,
                            opacity = state.tracing.opacity.coerceIn(0.15f, 1f),
                            scale = 1f,
                            offsetX = 0f,
                            offsetY = 0f,
                            isLoading = false
                        ),
                        statusBanner = "Imagen cargada para calcar"
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        tracing = state.tracing.copy(isLoading = false),
                        statusBanner = "No se pudo cargar la imagen seleccionada"
                    )
                }
            }
        }
    }

    fun setTracingVisible(visible: Boolean) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(visible = visible))
        }
    }

    fun toggleTracingVisibility() {
        _uiState.update { state ->
            if (state.tracing.bitmap == null) state
            else state.copy(tracing = state.tracing.copy(visible = !state.tracing.visible))
        }
    }

    fun updateTracingOpacity(opacity: Float) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(opacity = opacity.coerceIn(0.05f, 1f)))
        }
    }

    fun updateTracingScale(scale: Float) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(scale = scale.coerceIn(0.2f, 3f)))
        }
    }

    fun updateTracingOffset(offsetX: Float, offsetY: Float) {
        _uiState.update { state ->
            state.copy(
                tracing = state.tracing.copy(
                    offsetX = offsetX.coerceIn(-0.75f, 0.75f),
                    offsetY = offsetY.coerceIn(-0.75f, 0.75f)
                )
            )
        }
    }

    fun resetTracingTransform() {
        _uiState.update { state ->
            state.copy(
                tracing = state.tracing.copy(
                    scale = 1f,
                    offsetX = 0f,
                    offsetY = 0f
                )
            )
        }
    }

    fun clearTracingImage() {
        _uiState.update { state ->
            state.copy(tracing = TracingReferenceState())
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
