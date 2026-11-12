package com.trazostudio.draw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.trazostudio.draw.ui.canvas.popups.TracingReferencePopup
import com.trazostudio.draw.ui.theme.studioTopBarWindowInsets
import com.trazostudio.engine.ToolType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    var showTracingPopup by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Estado de zoom, rotación y desplazamiento de la vista del lienzo
    var zoom by remember { mutableFloatStateOf(1f) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var currentCanvasToScreenScale by remember { mutableFloatStateOf(1f) }
    val currentIsPanMode by rememberUpdatedState(state.isPanMode)
    val currentTwoFingerPanZoom by rememberUpdatedState(preferences.twoFingerPanZoom)

    // Patrón de cuadrícula reutilizable en un solo drawRect (evita miles de llamadas por cuadro).
    val checkerBrush = remember {
        val cell = 24
        val tile = cell * 2
        val pixels = IntArray(tile * tile)
        val cLight = 0xFFE5E7EB.toInt()
        val cDark = 0xFFD1D5DB.toInt()
        for (y in 0 until tile) {
            for (x in 0 until tile) {
                val isLight = ((x / cell) + (y / cell)) % 2 == 0
                pixels[y * tile + x] = if (isLight) cLight else cDark
            }
        }
        val tileBmp = android.graphics.Bitmap.createBitmap(
            pixels,
            tile,
            tile,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        ShaderBrush(ImageShader(tileBmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }

    LaunchedEffect(state.statusBanner) {
        val banner = state.statusBanner
        if (banner != null) {
            snackbarHostState.showSnackbar(banner)
            viewModel.clearStatusBanner()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = studioTopBarWindowInsets(),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver a galería"
                        )
                    }
                },
                title = {
                    val displayAngle = ((rotationDegrees % 360f) + 360f) % 360f
                    val roundedAngle = displayAngle.roundToInt() % 360
                    Column {
                        Text(
                            text = state.projectTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (state.isPanMode) {
                                "Modo mover • ${roundedAngle}°"
                            } else if (roundedAngle != 0) {
                                "${state.canvasWidth}×${state.canvasHeight} • ${roundedAngle}°"
                            } else {
                                "${state.canvasWidth}×${state.canvasHeight} • C${state.stats.activeLayerIndex + 1}/${state.stats.layerCount}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isPanMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.stats.canUndo,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Deshacer")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.stats.canRedo,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Rehacer")
                    }
                    IconButton(
                        onClick = { showLayersPopup = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.Layers, contentDescription = "Capas")
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Limpiar capa")
                    }
                    IconButton(
                        onClick = { viewModel.saveToInternalGallery(context) {} },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Guardar proyecto")
                    }
                    IconButton(
                        onClick = onNavigateToExport,
                        modifier = Modifier.size(38.dp)
                    ) {
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
                isPanMode = state.isPanMode,
                hasTracingImage = state.tracing.bitmap != null,
                isTracingVisible = state.tracing.visible,
                activeColorArgb = state.brush.argbColor,
                brushRadius = state.brush.radiusPx,
                zoomPercent = (zoom * 100).roundToInt(),
                rotationDegrees = rotationDegrees,
                onSelectTool = { viewModel.selectTool(it) },
                onTogglePanMode = { viewModel.togglePanMode() },
                onOpenColorPicker = { showColorPopup = true },
                onOpenBrushSettings = { showBrushPopup = true },
                onOpenTracing = { showTracingPopup = true },
                onToggleTracingVisibility = { viewModel.toggleTracingVisibility() },
                onResetViewport = {
                    zoom = 1f
                    rotationDegrees = 0f
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
            SideEffect {
                currentCanvasToScreenScale = fitScale * zoom
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
                val tracingImageBitmap = remember(state.tracing.bitmap) {
                    state.tracing.bitmap?.asImageBitmap()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            viewportW,
                            viewportH,
                            fitScale
                        ) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val panOnlyMode = currentIsPanMode

                                if (panOnlyMode) {
                                    // Modo Mover libre: 1 dedo desplaza y 2 dedos hacen zoom, rotación y desplazamiento sin pintar.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount == 0) break
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        val rotDelta = if (pressedCount >= 2) event.calculateRotation() else 0f
                                        zoom = (zoom * zoomDelta).coerceIn(0.4f, 6f)
                                        rotationDegrees = (rotationDegrees + rotDelta) % 360f
                                        panOffset += panDelta
                                        event.changes.forEach { it.consume() }
                                    }
                                    return@awaitEachGesture
                                }

                                var isTransforming = false
                                var activeStroke = false
                                val downTimeMs = System.currentTimeMillis()
                                val firstDownPos = firstDown.position
                                var maxTravelPx = 0f

                                fun screenToCanvas(pos: Offset): Offset {
                                    val totalScale = fitScale * zoom
                                    val centerX = viewportW / 2f + panOffset.x
                                    val centerY = viewportH / 2f + panOffset.y
                                    val dx = pos.x - centerX
                                    val dy = pos.y - centerY
                                    val rad = rotationDegrees.toDouble() * (PI / 180.0)
                                    val cosA = cos(rad).toFloat()
                                    val sinA = sin(rad).toFloat()
                                    val unrotX = dx * cosA + dy * sinA
                                    val unrotY = -dx * sinA + dy * cosA
                                    return Offset(
                                        x = canvasW / 2f + unrotX / totalScale,
                                        y = canvasH / 2f + unrotY / totalScale
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

                                    if (pressedCount >= 2 && currentTwoFingerPanZoom) {
                                        if (!isTransforming && activeStroke) {
                                            val elapsed = System.currentTimeMillis() - downTimeMs
                                            if (elapsed < 220L && maxTravelPx < 16f) {
                                                viewModel.cancelAccidentalTwoFingerStroke()
                                            }
                                        }
                                        isTransforming = true
                                        activeStroke = false
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        zoom = (zoom * zoomDelta).coerceIn(0.4f, 6f)
                                        panOffset += panDelta
                                        event.changes.forEach { it.consume() }
                                    } else if (!isTransforming && pressedCount == 1) {
                                        val change = event.changes.first()
                                        maxTravelPx = max(
                                            maxTravelPx,
                                            (change.position - firstDownPos).getDistance()
                                        )
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
                                rotationZ = rotationDegrees
                                translationX = panOffset.x
                                translationY = panOffset.y
                            }
                    ) {
                        // Fondo cuadriculado optimizado en una única llamada de dibujo.
                        if (preferences.showGridCheckers) {
                            drawRect(brush = checkerBrush)
                        }

                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Low
                        )

                        // Imagen de calco desde galería: guía semitransparente encima del lienzo sin alterar capas Rust.
                        if (tracingImageBitmap != null && state.tracing.visible) {
                            clipRect {
                                val refW = tracingImageBitmap.width.toFloat().coerceAtLeast(1f)
                                val refH = tracingImageBitmap.height.toFloat().coerceAtLeast(1f)
                                val baseFit = min(size.width / refW, size.height / refH)
                                val drawW = (refW * baseFit * state.tracing.scale).roundToInt().coerceAtLeast(1)
                                val drawH = (refH * baseFit * state.tracing.scale).roundToInt().coerceAtLeast(1)
                                val centerX = size.width / 2f + state.tracing.offsetX * size.width
                                val centerY = size.height / 2f + state.tracing.offsetY * size.height
                                val left = (centerX - drawW / 2f).roundToInt()
                                val top = (centerY - drawH / 2f).roundToInt()
                                drawImage(
                                    image = tracingImageBitmap,
                                    dstOffset = IntOffset(left, top),
                                    dstSize = IntSize(drawW, drawH),
                                    alpha = state.tracing.opacity,
                                    filterQuality = FilterQuality.Medium
                                )
                            }
                        }
                    }

                    // Indicador 1:1 directo sobre el lienzo mientras el usuario ajusta el pincel en la hoja inferior
                    if (showBrushPopup && state.brush.tool.isStrokeTool) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = (state.brush.radiusPx * fitScale * zoom).coerceAtLeast(1.5f)
                            val previewCenter = Offset(size.width / 2f, size.height * 0.24f)
                            val baseColor = if (state.brush.tool == ToolType.ERASER) {
                                Color(0xFFD1D5DB)
                            } else {
                                Color(state.brush.argbColor)
                            }
                            when (state.brush.tool) {
                                ToolType.AIRBRUSH -> {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            0f to baseColor.copy(alpha = state.brush.opacity * 0.72f),
                                            0.55f to baseColor.copy(alpha = state.brush.opacity * 0.25f),
                                            1f to baseColor.copy(alpha = 0f),
                                            center = previewCenter,
                                            radius = r
                                        ),
                                        radius = r,
                                        center = previewCenter
                                    )
                                }
                                ToolType.MARKER -> {
                                    drawCircle(
                                        color = baseColor.copy(alpha = state.brush.opacity),
                                        radius = r,
                                        center = previewCenter
                                    )
                                }
                                ToolType.ERASER -> {
                                    drawCircle(
                                        color = baseColor.copy(alpha = state.brush.opacity * 0.7f),
                                        radius = r,
                                        center = previewCenter
                                    )
                                }
                                else -> {
                                    val innerStop = (0.2f + 0.75f * state.brush.hardness).coerceIn(0.2f, 0.97f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            0f to baseColor.copy(alpha = state.brush.opacity),
                                            innerStop to baseColor.copy(alpha = state.brush.opacity),
                                            1f to baseColor.copy(alpha = 0f),
                                            center = previewCenter,
                                            radius = r
                                        ),
                                        radius = r,
                                        center = previewCenter
                                    )
                                }
                            }
                            drawCircle(
                                color = Color.White.copy(alpha = 0.85f),
                                radius = r + 1.dp.toPx(),
                                center = previewCenter,
                                style = Stroke(width = 1.dp.toPx())
                            )
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.55f),
                                radius = r,
                                center = previewCenter,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBrushPopup) {
        BrushSettingsPopup(
            brush = state.brush,
            canvasToScreenScale = currentCanvasToScreenScale,
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

    if (showTracingPopup) {
        TracingReferencePopup(
            tracing = state.tracing,
            onPickImageUri = { uri -> viewModel.loadTracingImage(context, uri) },
            onToggleVisibility = { visible -> viewModel.setTracingVisible(visible) },
            onChangeScale = { scale -> viewModel.updateTracingScale(scale) },
            onChangeOpacity = { opacity -> viewModel.updateTracingOpacity(opacity) },
            onChangeOffset = { x, y -> viewModel.updateTracingOffset(x, y) },
            onResetTransform = { viewModel.resetTracingTransform() },
            onClearImage = { viewModel.clearTracingImage() },
            onDismiss = { showTracingPopup = false }
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
    isPanMode: Boolean,
    hasTracingImage: Boolean,
    isTracingVisible: Boolean,
    activeColorArgb: Int,
    brushRadius: Float,
    zoomPercent: Int,
    rotationDegrees: Float,
    onSelectTool: (ToolType) -> Unit,
    onTogglePanMode: () -> Unit,
    onOpenColorPicker: () -> Unit,
    onOpenBrushSettings: () -> Unit,
    onOpenTracing: () -> Unit,
    onToggleTracingVisibility: () -> Unit,
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

                // Botón para alternar entre mover libremente el lienzo y dibujar
                Surface(
                    onClick = onTogglePanMode,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isPanMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = if (isPanMode) {
                        Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PanTool,
                            contentDescription = if (isPanMode) "Volver a dibujar" else "Mover lienzo",
                            tint = if (isPanMode) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isPanMode) "Dibujar" else "Mover",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isPanMode) FontWeight.Bold else FontWeight.Medium,
                            color = if (isPanMode) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                // Botón para abrir la herramienta de calco con imagen de la galería
                Surface(
                    onClick = onOpenTracing,
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasTracingImage) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = if (hasTracingImage) {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.secondary,
                            RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = "Calcar imagen",
                            tint = if (hasTracingImage) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Calco",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (hasTracingImage) FontWeight.Bold else FontWeight.Medium,
                            color = if (hasTracingImage) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                // Acceso rápido para mostrar/ocultar el calco sin abrir el panel cuando hay una imagen cargada
                if (hasTracingImage) {
                    Surface(
                        onClick = onToggleTracingVisibility,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isTracingVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = if (isTracingVisible) "Ocultar calco" else "Mostrar calco",
                                tint = if (isTracingVisible) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                tools.forEach { entry ->
                    val selected = !isPanMode && entry.tool == activeTool
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

                // Botón rápido para restablecer zoom, rotación y posición
                val normalizedDeg = (((rotationDegrees % 360f) + 360f) % 360f).roundToInt() % 360
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
                            text = if (normalizedDeg != 0) "$zoomPercent% · ${normalizedDeg}°" else "$zoomPercent%",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
