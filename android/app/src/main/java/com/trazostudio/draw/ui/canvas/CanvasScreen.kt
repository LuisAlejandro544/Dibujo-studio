package com.trazostudio.draw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.ui.canvas.popups.BrushSettingsPopup
import com.trazostudio.draw.ui.canvas.popups.ColorPickerPopup
import com.trazostudio.draw.ui.canvas.popups.LayersPopup
import com.trazostudio.engine.ToolType
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pantalla del Estudio de Dibujo.
 *
 * Funcionalidades principales:
 * - Barra superior con acciones rápidas: Volver, Undo/Redo, Capas (popup), Guardar local, Limpiar y Exportar (pantalla dedicada).
 * - Lienzo interactivo con soporte simultáneo de 1 dedo para trazo nativo en Rust y 2 dedos para zoom/panorámica.
 * - Barra inferior completa con herramientas (Material Icons), selector de color y ajustes de pincel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(
    viewModel: CanvasViewModel,
    preferences: StudioPreferences,
    onNavigateBack: () -> Unit,
    onNavigateToExport: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showBrushPopup by remember { mutableStateOf(false) }
    var showColorPopup by remember { mutableStateOf(false) }
    var showLayersPopup by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Estado de zoom y desplazamiento de la vista del lienzo
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(state.statusBanner) {
        val banner = state.statusBanner
        if (banner != null) {
            snackbarHostState.showSnackbar(banner)
            viewModel.clearStatusBanner()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver a galería"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.projectTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.canvasWidth}×${state.canvasHeight} • Capa ${state.stats.activeLayerIndex + 1}/${state.stats.layerCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.stats.canUndo
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Deshacer")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.stats.canRedo
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Rehacer")
                    }
                    IconButton(onClick = { showLayersPopup = true }) {
                        Icon(Icons.Rounded.Layers, contentDescription = "Capas")
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Limpiar capa")
                    }
                    IconButton(
                        onClick = { viewModel.saveToInternalGallery(context) {} }
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Guardar proyecto")
                    }
                    IconButton(onClick = onNavigateToExport) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "Exportar obra")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            StudioBottomDock(
                activeTool = state.brush.tool,
                activeColorArgb = state.brush.argbColor,
                brushRadius = state.brush.radiusPx,
                zoomPercent = (zoom * 100).roundToInt(),
                onSelectTool = { viewModel.selectTool(it) },
                onOpenColorPicker = { showColorPopup = true },
                onOpenBrushSettings = { showBrushPopup = true },
                onResetViewport = {
                    zoom = 1f
                    panOffset = Offset.Zero
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val viewportW = constraints.maxWidth.toFloat()
            val viewportH = constraints.maxHeight.toFloat()
            val canvasW = state.canvasWidth.toFloat().coerceAtLeast(1f)
            val canvasH = state.canvasHeight.toFloat().coerceAtLeast(1f)

            val fitScale = remember(viewportW, viewportH, canvasW, canvasH) {
                min((viewportW * 0.92f) / canvasW, (viewportH * 0.92f) / canvasH)
                    .coerceAtLeast(0.05f)
            }

            val baseRenderW = canvasW * fitScale
            val baseRenderH = canvasH * fitScale

            val bmp = state.bitmap
            if (bmp == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Inicializando motor Rust...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            viewportW,
                            viewportH,
                            fitScale,
                            preferences.twoFingerPanZoom
                        ) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                var isTransforming = false
                                var activeStroke = false

                                fun screenToCanvas(pos: Offset): Offset {
                                    val totalScale = fitScale * zoom
                                    val centerX = viewportW / 2f + panOffset.x
                                    val centerY = viewportH / 2f + panOffset.y
                                    val left = centerX - (canvasW * totalScale) / 2f
                                    val top = centerY - (canvasH * totalScale) / 2f
                                    return Offset(
                                        x = (pos.x - left) / totalScale,
                                        y = (pos.y - top) / totalScale
                                    )
                                }

                                val initialCanvasPos = screenToCanvas(firstDown.position)
                                if (
                                    initialCanvasPos.x in 0f..canvasW &&
                                    initialCanvasPos.y in 0f..canvasH
                                ) {
                                    activeStroke = true
                                    viewModel.onCanvasTouchDown(
                                        initialCanvasPos.x,
                                        initialCanvasPos.y
                                    )
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }

                                    if (pressedCount == 0) {
                                        if (activeStroke) {
                                            viewModel.onCanvasTouchUp()
                                        }
                                        break
                                    }

                                    if (pressedCount >= 2 && preferences.twoFingerPanZoom) {
                                        isTransforming = true
                                        activeStroke = false
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        zoom = (zoom * zoomDelta).coerceIn(0.4f, 6f)
                                        panOffset += panDelta
                                        event.changes.forEach { it.consume() }
                                    } else if (!isTransforming && pressedCount == 1) {
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            val pt = screenToCanvas(change.position)
                                            if (activeStroke) {
                                                viewModel.onCanvasTouchMove(pt.x, pt.y)
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(
                                width = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    baseRenderW.toDp()
                                },
                                height = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    baseRenderH.toDp()
                                }
                            )
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panOffset.x
                                translationY = panOffset.y
                            }
                    ) {
                        // Fondo cuadriculado sutil para visualizar transparencia de capas
                        if (preferences.showGridCheckers) {
                            val cell = 24f
                            val cols = (size.width / cell).toInt() + 1
                            val rows = (size.height / cell).toInt() + 1
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val light = (r + c) % 2 == 0
                                    drawRect(
                                        color = if (light) Color(0xFFE5E7EB) else Color(0xFFD1D5DB),
                                        topLeft = Offset(c * cell, r * cell),
                                        size = Size(cell, cell)
                                    )
                                }
                            }
                        }

                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Low
                        )
                    }
                }
            }
        }
    }

    if (showBrushPopup) {
        BrushSettingsPopup(
            brush = state.brush,
            onUpdate = { r, o, h, tol ->
                viewModel.updateBrushParameters(r, o, h, tol)
            },
            onDismiss = { showBrushPopup = false }
        )
    }

    if (showColorPopup) {
        ColorPickerPopup(
            currentColorArgb = state.brush.argbColor,
            recentColors = state.recentColors,
            onColorSelected = { viewModel.updateColor(it) },
            onDismiss = { showColorPopup = false }
        )
    }

    if (showLayersPopup) {
        LayersPopup(
            layers = state.layers,
            activeLayerIndex = state.stats.activeLayerIndex,
            onSelectLayer = { viewModel.selectActiveLayer(it) },
            onAddLayer = { viewModel.addLayer() },
            onDeleteLayer = { viewModel.deleteLayer(it) },
            onToggleVisibility = { idx, vis -> viewModel.toggleLayerVisibility(idx, vis) },
            onChangeOpacity = { idx, op -> viewModel.setLayerOpacity(idx, op) },
            onMergeDown = { viewModel.mergeLayerDown(it) },
            onDismiss = { showLayersPopup = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Limpiar capa activa") },
            text = { Text("¿Seguro que deseas borrar todo el contenido de la capa actual? Podrás revertirlo con Deshacer.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearActiveLayer()
                        showClearConfirm = false
                    }
                ) {
                    Text("Limpiar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private data class ToolDockEntry(
    val tool: ToolType,
    val icon: ImageVector
)

@Composable
private fun StudioBottomDock(
    activeTool: ToolType,
    activeColorArgb: Int,
    brushRadius: Float,
    zoomPercent: Int,
    onSelectTool: (ToolType) -> Unit,
    onOpenColorPicker: () -> Unit,
    onOpenBrushSettings: () -> Unit,
    onResetViewport: () -> Unit
) {
    val tools = remember {
        listOf(
            ToolDockEntry(ToolType.BRUSH, Icons.Rounded.Brush),
            ToolDockEntry(ToolType.MARKER, Icons.Rounded.Edit),
            ToolDockEntry(ToolType.AIRBRUSH, Icons.Rounded.AutoFixHigh),
            ToolDockEntry(ToolType.ERASER, Icons.Rounded.CleaningServices),
            ToolDockEntry(ToolType.BUCKET, Icons.Rounded.FormatColorFill),
            ToolDockEntry(ToolType.EYEDROPPER, Icons.Rounded.Colorize)
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón muestra de color actual (abre popup HSV)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(activeColorArgb))
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onOpenColorPicker() }
                )

                // Botón ajustes de tamaño/opacidad de pincel (abre popup)
                Surface(
                    onClick = onOpenBrushSettings,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Ajustar pincel",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${brushRadius.roundToInt()}px",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                tools.forEach { entry ->
                    val selected = entry.tool == activeTool
                    Surface(
                        onClick = { onSelectTool(entry.tool) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.tool.labelEs,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = entry.tool.labelEs,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                // Botón rápido para restablecer zoom/pan
                Surface(
                    onClick = onResetViewport,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = "Centrar lienzo",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$zoomPercent%",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
