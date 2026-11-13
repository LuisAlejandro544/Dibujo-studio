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
import com.trazostudio.engine.TracingAiAnalysis
import com.trazostudio.engine.TracingStrokePreset
import kotlin.math.abs
import kotlin.math.roundToInt
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
    val overlayOnTop: Boolean = false,
    val opacity: Float = 0.45f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isLoading: Boolean = false,
    val aiAnalysis: TracingAiAnalysis? = null,
    val aiAnalyzedScale: Float = 1f,
    val isAnalyzingAi: Boolean = false
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
    val isAdjustingTracing: Boolean = false,
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
    private var pendingEndStroke = false
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
            pendingEndStroke = false
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
                isAdjustingTracing = false,
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
            pendingEndStroke = false
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
                isAdjustingTracing = false,
                tracing = TracingReferenceState(),
                isBusy = true,
                statusBanner = null
            )
        }

        viewModelScope.launch {
            var renderedBitmap = newSession.initialize()
            var loaded = false

            val trazoFile = item.projectFile
            if (trazoFile != null && trazoFile.exists()) {
                val projectBytes = ProjectStorage.readFileBytes(trazoFile)
                if (projectBytes != null && projectBytes.isNotEmpty()) {
                    val (ok, bmp) = newSession.loadProjectBytes(projectBytes)
                    if (ok) {
                        renderedBitmap = bmp
                        loaded = true
                    }
                }
            }

            if (!loaded) {
                val pngBytes = ProjectStorage.readFileBytes(item.previewFile)
                if (pngBytes != null && pngBytes.isNotEmpty()) {
                    val (ok, bmp) = newSession.loadPngBytes(pngBytes)
                    renderedBitmap = bmp
                    loaded = ok
                }
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
                    statusBanner = if (!loaded) "No se pudo leer el contenido guardado" else null
                )
            }
        }
    }

    fun selectTool(tool: ToolType) {
        _uiState.update { state ->
            state.copy(
                brush = state.brush.copy(tool = tool),
                isPanMode = false,
                isAdjustingTracing = false
            )
        }
    }

    fun togglePanMode() {
        _uiState.update { state ->
            state.copy(
                isPanMode = !state.isPanMode,
                isAdjustingTracing = false
            )
        }
    }

    fun setPanMode(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                isPanMode = enabled,
                isAdjustingTracing = if (enabled) false else state.isAdjustingTracing
            )
        }
    }

    fun setTracingAdjustMode(enabled: Boolean) {
        _uiState.update { state ->
            if (enabled && state.tracing.bitmap == null) {
                state
            } else {
                state.copy(
                    isAdjustingTracing = enabled,
                    isPanMode = if (enabled) false else state.isPanMode,
                    tracing = if (enabled) state.tracing.copy(visible = true) else state.tracing
                )
            }
        }
        if (enabled) {
            syncTracingBaseExclusion()
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
            pendingEndStroke = true
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
                pendingEndStroke = false
                pendingStatsRefresh = true
            } else {
                pendingSegmentCoords.clear()
                pendingEndStroke = false
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
                var doEndStroke = false
                var doCancelUndo = false
                var doStatsRefresh = false

                synchronized(strokeQueueLock) {
                    val hasDrawWork = pendingBeginTransaction || pendingHasDab || pendingSegmentCoords.isNotEmpty()
                    if (!hasDrawWork && !pendingEndStroke && !pendingCancelUndo && !pendingStatsRefresh) {
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
                    doEndStroke = pendingEndStroke
                    doCancelUndo = pendingCancelUndo
                    doStatsRefresh = pendingStatsRefresh
                    pendingBeginTransaction = false
                    pendingHasDab = false
                    pendingEndStroke = false
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

                if (doEndStroke) {
                    activeSession.endStroke()
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
            val bmp = activeSession.setActiveLayer(index)
            val stats = activeSession.getStats()
            _uiState.update { it.copy(bitmap = bmp, stats = stats) }
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

    suspend fun saveProjectFiles(context: Context): Boolean {
        val activeSession = session ?: return false
        val title = _uiState.value.projectTitle
        val w = _uiState.value.canvasWidth
        val h = _uiState.value.canvasHeight
        val pngBytes = activeSession.exportPngBytes(includeBackground = true)
        if (pngBytes.isEmpty()) return false
        val projectBytes = activeSession.exportProjectBytes()
        currentSavedFile = ProjectStorage.saveProjectPreview(
            context = context,
            projectTitle = title,
            width = w,
            height = h,
            pngBytes = pngBytes,
            previousFile = currentSavedFile,
            projectBytes = projectBytes
        )
        return true
    }

    fun saveToInternalGallery(context: Context, onComplete: (Boolean) -> Unit) {
        if (session == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val ok = saveProjectFiles(context)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusBanner = if (ok) "Guardado con capas en la galería del estudio" else "Error al guardar"
                )
            }
            onComplete(ok)
        }
    }

    private fun syncTracingBaseExclusion() {
        val activeSession = session ?: return
        val tracing = _uiState.value.tracing
        val excludeBase = tracing.bitmap != null && tracing.visible && !tracing.overlayOnTop
        viewModelScope.launch {
            val bmp = activeSession.setExcludeBaseLayer(excludeBase)
            val stats = activeSession.getStats()
            _uiState.update { it.copy(bitmap = bmp, stats = stats) }
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
                            overlayOnTop = state.tracing.overlayOnTop,
                            opacity = state.tracing.opacity.coerceIn(0.15f, 1f),
                            scale = 1f,
                            rotationDegrees = 0f,
                            offsetX = 0f,
                            offsetY = 0f,
                            isLoading = false
                        ),
                        isAdjustingTracing = true,
                        isPanMode = false,
                        statusBanner = "Mueve y pellizca la imagen con tus dedos; pulsa Listo al terminar"
                    )
                }
                syncTracingBaseExclusion()
                analyzeTracingWithAi(forceRefresh = true)
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
            state.copy(
                tracing = state.tracing.copy(visible = visible),
                isAdjustingTracing = if (!visible) false else state.isAdjustingTracing
            )
        }
        syncTracingBaseExclusion()
    }

    fun toggleTracingVisibility() {
        _uiState.update { state ->
            if (state.tracing.bitmap == null) state
            else {
                val nextVisible = !state.tracing.visible
                state.copy(
                    tracing = state.tracing.copy(visible = nextVisible),
                    isAdjustingTracing = if (!nextVisible) false else state.isAdjustingTracing
                )
            }
        }
        syncTracingBaseExclusion()
    }

    fun setTracingOverlayOnTop(overlayOnTop: Boolean) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(overlayOnTop = overlayOnTop))
        }
        syncTracingBaseExclusion()
    }

    fun updateTracingOpacity(opacity: Float) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(opacity = opacity.coerceIn(0.05f, 1f)))
        }
    }

    fun updateTracingScale(scale: Float) {
        _uiState.update { state ->
            state.copy(tracing = state.tracing.copy(scale = scale.coerceIn(0.15f, 5f)))
        }
    }

    fun updateTracingOffset(offsetX: Float, offsetY: Float) {
        _uiState.update { state ->
            state.copy(
                tracing = state.tracing.copy(
                    offsetX = offsetX.coerceIn(-1.5f, 1.5f),
                    offsetY = offsetY.coerceIn(-1.5f, 1.5f)
                )
            )
        }
    }

    fun transformTracingBy(
        deltaOffsetX: Float,
        deltaOffsetY: Float,
        scaleFactor: Float,
        deltaRotationDegrees: Float
    ) {
        _uiState.update { state ->
            val t = state.tracing
            state.copy(
                tracing = t.copy(
                    offsetX = (t.offsetX + deltaOffsetX).coerceIn(-1.5f, 1.5f),
                    offsetY = (t.offsetY + deltaOffsetY).coerceIn(-1.5f, 1.5f),
                    scale = (t.scale * scaleFactor).coerceIn(0.15f, 5f),
                    rotationDegrees = (t.rotationDegrees + deltaRotationDegrees) % 360f
                )
            )
        }
    }

    fun resetTracingTransform() {
        _uiState.update { state ->
            state.copy(
                tracing = state.tracing.copy(
                    scale = 1f,
                    rotationDegrees = 0f,
                    offsetX = 0f,
                    offsetY = 0f
                )
            )
        }
    }

    fun clearTracingImage() {
        _uiState.update { state ->
            state.copy(
                tracing = TracingReferenceState(),
                isAdjustingTracing = false
            )
        }
        syncTracingBaseExclusion()
    }

    /**
     * Analiza en Rust la imagen de calco actual a la escala vigente sobre el lienzo
     * para sugerir grosores de trazo (ojos/detalles, contorno, grueso) y paleta de coloreado.
     */
    fun analyzeTracingWithAi(forceRefresh: Boolean = false) {
        val activeSession = session ?: return
        val tracing = _uiState.value.tracing
        val bmp = tracing.bitmap ?: return
        if (tracing.isAnalyzingAi) return
        if (!forceRefresh &&
            tracing.aiAnalysis != null &&
            abs(tracing.aiAnalyzedScale - tracing.scale) < 0.04f
        ) {
            return
        }

        val targetScale = tracing.scale
        _uiState.update { it.copy(tracing = it.tracing.copy(isAnalyzingAi = true)) }
        viewModelScope.launch {
            val result = activeSession.analyzeTracingBitmap(bmp, targetScale)
            _uiState.update { state ->
                if (state.tracing.bitmap !== bmp) {
                    state.copy(tracing = state.tracing.copy(isAnalyzingAi = false))
                } else {
                    state.copy(
                        tracing = state.tracing.copy(
                            aiAnalysis = result,
                            aiAnalyzedScale = targetScale,
                            isAnalyzingAi = false
                        )
                    )
                }
            }
        }
    }

    /** Aplica un grosor de trazo recomendado por la IA local directamente al pincel activo. */
    fun applyTracingStrokePreset(
        preset: TracingStrokePreset,
        useDetectedOutlineColor: Boolean = false
    ) {
        val outlineArgb = _uiState.value.tracing.aiAnalysis?.outlineColorArgb
        _uiState.update { state ->
            val nextColor = if (useDetectedOutlineColor && outlineArgb != null) {
                outlineArgb
            } else {
                state.brush.argbColor
            }
            val nextRecent = if (useDetectedOutlineColor && outlineArgb != null) {
                (listOf(outlineArgb) + state.recentColors.filter { it != outlineArgb }).take(10)
            } else {
                state.recentColors
            }
            state.copy(
                brush = state.brush.copy(
                    tool = preset.tool,
                    argbColor = nextColor,
                    radiusPx = preset.radiusPx.coerceIn(1f, 120f),
                    hardness = preset.hardness.coerceIn(0f, 1f),
                    opacity = preset.opacity.coerceIn(0.05f, 1f)
                ),
                recentColors = nextRecent,
                isPanMode = false,
                isAdjustingTracing = false,
                statusBanner = "Trazo IA: ${preset.title} (${preset.radiusPx.roundToInt()} px)"
            )
        }
    }

    /** Aplica un color extraído del calco para trazar o rellenar con Bote de Pintura. */
    fun applyTracingColor(
        argbColor: Int,
        switchToBucket: Boolean = false,
        recommendedTolerance: Int? = null
    ) {
        _uiState.update { state ->
            val updatedRecent = (listOf(argbColor) + state.recentColors.filter { it != argbColor }).take(10)
            val nextTool = when {
                switchToBucket -> ToolType.BUCKET
                state.brush.tool == ToolType.ERASER || state.brush.tool == ToolType.EYEDROPPER -> ToolType.BRUSH
                else -> state.brush.tool
            }
            state.copy(
                brush = state.brush.copy(
                    tool = nextTool,
                    argbColor = argbColor,
                    fillTolerance = recommendedTolerance?.coerceIn(0, 96) ?: state.brush.fillTolerance
                ),
                recentColors = updatedRecent,
                isPanMode = false,
                isAdjustingTracing = false,
                statusBanner = if (switchToBucket) {
                    "Color y Relleno listos para colorear"
                } else {
                    "Color del calco seleccionado"
                }
            )
        }
    }

    /** Guarda todos los colores sugeridos por la IA en los colores recientes del selector HSV. */
    fun saveTracingPaletteToRecents() {
        val palette = _uiState.value.tracing.aiAnalysis?.palette ?: return
        if (palette.isEmpty()) return
        _uiState.update { state ->
            val extracted = palette.map { it.argbColor }
            val merged = (extracted + state.recentColors).distinct().take(12)
            state.copy(
                recentColors = merged,
                statusBanner = "Paleta de ${extracted.size} colores guardada en el selector"
            )
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
