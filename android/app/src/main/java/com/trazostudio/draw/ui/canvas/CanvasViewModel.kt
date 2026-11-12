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
 */
class CanvasViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CanvasUiState())
    val uiState: StateFlow<CanvasUiState> = _uiState.asStateFlow()

    private var session: RustCanvasSession? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    fun startNewSession(title: String, width: Int, height: Int) {
        session?.close()
        val newSession = RustCanvasSession(width, height)
        session = newSession

        _uiState.update {
            it.copy(
                projectTitle = title,
                canvasWidth = width,
                canvasHeight = height,
                bitmap = null,
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
            state.copy(brush = state.brush.copy(tool = tool))
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
        val currentBrush = _uiState.value.brush
        lastX = canvasX
        lastY = canvasY

        viewModelScope.launch {
            when (currentBrush.tool) {
                ToolType.EYEDROPPER -> {
                    val picked = activeSession.pickColor(canvasX.toInt(), canvasY.toInt())
                    updateColor(picked)
                    _uiState.update {
                        it.copy(
                            brush = it.brush.copy(tool = ToolType.BRUSH, argbColor = picked),
                            statusBanner = "Color capturado con cuentagotas"
                        )
                    }
                }
                ToolType.BUCKET -> {
                    val (changed, bmp) = activeSession.floodFill(
                        canvasX.toInt(),
                        canvasY.toInt(),
                        currentBrush.argbColor,
                        currentBrush.fillTolerance
                    )
                    if (changed) {
                        val stats = activeSession.getStats()
                        _uiState.update { it.copy(bitmap = bmp, stats = stats) }
                    }
                }
                else -> {
                    activeSession.beginStroke()
                    val bmp = activeSession.drawDab(canvasX, canvasY, currentBrush)
                    val stats = activeSession.getStats()
                    _uiState.update { it.copy(bitmap = bmp, stats = stats) }
                }
            }
        }
    }

    fun onCanvasTouchMove(canvasX: Float, canvasY: Float) {
        val activeSession = session ?: return
        val currentBrush = _uiState.value.brush
        if (!currentBrush.tool.isStrokeTool) return

        val x0 = lastX
        val y0 = lastY
        lastX = canvasX
        lastY = canvasY

        viewModelScope.launch {
            val bmp = activeSession.drawSegment(x0, y0, canvasX, canvasY, currentBrush)
            _uiState.update { it.copy(bitmap = bmp) }
        }
    }

    fun onCanvasTouchUp() {
        val activeSession = session ?: return
        viewModelScope.launch {
            val stats = activeSession.getStats()
            _uiState.update { it.copy(stats = stats) }
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
