package com.trazostudio.draw.ui.canvas

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.unit.sp
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.ui.canvas.popups.BrushSettingsPopup
import com.trazostudio.draw.ui.canvas.popups.ColorPickerPopup
import com.trazostudio.draw.ui.canvas.popups.LayersPopup
import com.trazostudio.draw.ui.canvas.popups.TracingAiPopup
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
    var showTracingAiPopup by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var lastSavedStrokeCount by remember { mutableStateOf(state.stats.strokeCount) }

    val handleBackNavigation = {
        if (state.stats.strokeCount > lastSavedStrokeCount) {
            showExitConfirm = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = !showExitConfirm) {
        handleBackNavigation()
    }

    // Estado de zoom, rotación y desplazamiento de la vista del lienzo
    var zoom by remember { mutableFloatStateOf(1f) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var currentCanvasToScreenScale by remember { mutableFloatStateOf(1f) }
    val currentIsPanMode by rememberUpdatedState(state.isPanMode)
    val currentIsAdjustingTracing by rememberUpdatedState(state.isAdjustingTracing)
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

    // Al cargar una imagen o activar el ajuste de calco, ocultamos el panel inferior para dejar libre el lienzo.
    LaunchedEffect(state.isAdjustingTracing, state.tracing.bitmap) {
        if (state.isAdjustingTracing) {
            showTracingPopup = false
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
                        onClick = handleBackNavigation,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver a galería",
                            modifier = Modifier.size(21.dp)
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
                            text = if (state.isAdjustingTracing) {
                                "Calco • ${(state.tracing.scale * 100).roundToInt()}%"
                            } else if (state.isPanMode) {
                                "Mover • ${roundedAngle}°"
                            } else if (roundedAngle != 0) {
                                "${state.canvasWidth}×${state.canvasHeight} • ${roundedAngle}°"
                            } else {
                                "${state.canvasWidth}×${state.canvasHeight} • C${state.stats.activeLayerIndex + 1}/${state.stats.layerCount}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                state.isAdjustingTracing -> MaterialTheme.colorScheme.secondary
                                state.isPanMode -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = "Deshacer",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.stats.canRedo,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Redo,
                            contentDescription = "Rehacer",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showLayersPopup = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Layers,
                            contentDescription = "Capas",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = "Limpiar capa",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.saveToInternalGallery(context) { ok ->
                                if (ok) {
                                    lastSavedStrokeCount = state.stats.strokeCount
                                }
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = "Guardar proyecto",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onNavigateToExport,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.FileDownload,
                            contentDescription = "Exportar obra",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            AnimatedContent(
                targetState = state.isAdjustingTracing,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(260)) { it / 2 } + fadeIn(tween(220)))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(200)) { it / 2 } + fadeOut(tween(180))
                        )
                },
                label = "studioBottomDockTransition"
            ) { adjustingTracing ->
                if (adjustingTracing) {
                    TracingAdjustDock(
                        scale = state.tracing.scale,
                        rotationDegrees = state.tracing.rotationDegrees,
                        opacity = state.tracing.opacity,
                        onChangeOpacity = { viewModel.updateTracingOpacity(it) },
                        onResetTracing = { viewModel.resetTracingTransform() },
                        onDone = { viewModel.setTracingAdjustMode(false) }
                    )
                } else {
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
                        onAdjustTracing = { viewModel.setTracingAdjustMode(true) },
                        onOpenTracingAi = {
                            viewModel.analyzeTracingWithAi()
                            showTracingAiPopup = true
                        },
                        onResetViewport = {
                            zoom = 1f
                            rotationDegrees = 0f
                            panOffset = Offset.Zero
                        }
                    )
                }
            }
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
                                val adjustTracingMode = currentIsAdjustingTracing

                                if (adjustTracingMode) {
                                    // Ajuste directo del calco con los dedos: 1 dedo mueve; 2 dedos escalan, giran y mueven.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount == 0) break
                                        val panDelta = event.calculatePan()
                                        val zoomDelta = if (pressedCount >= 2) event.calculateZoom() else 1f
                                        val rotDelta = if (pressedCount >= 2) event.calculateRotation() else 0f

                                        val rad = rotationDegrees.toDouble() * (PI / 180.0)
                                        val cosA = cos(rad).toFloat()
                                        val sinA = sin(rad).toFloat()
                                        val unrotX = panDelta.x * cosA + panDelta.y * sinA
                                        val unrotY = -panDelta.x * sinA + panDelta.y * cosA
                                        val deltaNormX = (unrotX / zoom) / baseRenderW.coerceAtLeast(1f)
                                        val deltaNormY = (unrotY / zoom) / baseRenderH.coerceAtLeast(1f)

                                        viewModel.transformTracingBy(
                                            deltaOffsetX = deltaNormX,
                                            deltaOffsetY = deltaNormY,
                                            scaleFactor = zoomDelta,
                                            deltaRotationDegrees = rotDelta
                                        )
                                        event.changes.forEach { it.consume() }
                                    }
                                    return@awaitEachGesture
                                }

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

                        val isTracingVisible = tracingImageBitmap != null && state.tracing.visible
                        val drawTracingUnderlay = isTracingVisible &&
                            !state.tracing.overlayOnTop &&
                            !state.stats.baseLayerModified &&
                            state.stats.activeLayerIndex != 0

                        fun drawTracingOverlay(blendMode: BlendMode) {
                            val refBmp = tracingImageBitmap ?: return
                            clipRect {
                                val refW = refBmp.width.toFloat().coerceAtLeast(1f)
                                val refH = refBmp.height.toFloat().coerceAtLeast(1f)
                                val baseFit = min(size.width / refW, size.height / refH)
                                val drawW = (refW * baseFit * state.tracing.scale).coerceAtLeast(1f)
                                val drawH = (refH * baseFit * state.tracing.scale).coerceAtLeast(1f)
                                val centerX = size.width / 2f + state.tracing.offsetX * size.width
                                val centerY = size.height / 2f + state.tracing.offsetY * size.height
                                val left = centerX - drawW / 2f
                                val top = centerY - drawH / 2f

                                withTransform({
                                    rotate(
                                        degrees = state.tracing.rotationDegrees,
                                        pivot = Offset(centerX, centerY)
                                    )
                                }) {
                                    drawImage(
                                        image = refBmp,
                                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                        dstSize = IntSize(
                                            drawW.roundToInt().coerceAtLeast(1),
                                            drawH.roundToInt().coerceAtLeast(1)
                                        ),
                                        alpha = state.tracing.opacity,
                                        filterQuality = FilterQuality.High,
                                        blendMode = blendMode
                                    )
                                    if (state.isAdjustingTracing) {
                                        drawRect(
                                            color = Color(0xFF38BDF8),
                                            topLeft = Offset(left, top),
                                            size = Size(drawW, drawH),
                                            style = Stroke(
                                                width = 2.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(
                                                    floatArrayOf(14f, 10f),
                                                    0f
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (drawTracingUnderlay) {
                            // Papel base debajo del calco para que las capas superiores pinten nítidamente encima.
                            if (state.stats.baseLayerVisible) {
                                drawRect(color = Color.White)
                            }
                            drawTracingOverlay(BlendMode.SrcOver)
                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.Low
                            )
                        } else {
                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.Low
                            )
                            // Cuando el calco va encima (o si se dibujó sobre el Fondo), Multiplicar vuelve transparente el blanco.
                            if (isTracingVisible) {
                                drawTracingOverlay(BlendMode.Multiply)
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
            onChangeOverlayOnTop = { onTop -> viewModel.setTracingOverlayOnTop(onTop) },
            onEnterFingerAdjustMode = { viewModel.setTracingAdjustMode(true) },
            onOpenAiAssistant = {
                viewModel.analyzeTracingWithAi()
                showTracingAiPopup = true
            },
            onChangeScale = { scale -> viewModel.updateTracingScale(scale) },
            onChangeOpacity = { opacity -> viewModel.updateTracingOpacity(opacity) },
            onChangeOffset = { x, y -> viewModel.updateTracingOffset(x, y) },
            onResetTransform = { viewModel.resetTracingTransform() },
            onClearImage = { viewModel.clearTracingImage() },
            onDismiss = { showTracingPopup = false }
        )
    }

    if (showTracingAiPopup) {
        TracingAiPopup(
            analysis = state.tracing.aiAnalysis,
            isAnalyzing = state.tracing.isAnalyzingAi,
            tracingScale = state.tracing.scale,
            canvasToScreenScale = currentCanvasToScreenScale,
            activeColorArgb = state.brush.argbColor,
            onReanalyze = { viewModel.analyzeTracingWithAi(forceRefresh = true) },
            onApplyStroke = { preset, useOutline ->
                viewModel.applyTracingStrokePreset(preset, useOutline)
            },
            onApplyColor = { argb, switchToBucket, tolerance ->
                viewModel.applyTracingColor(argb, switchToBucket, tolerance)
            },
            onSavePaletteToRecents = { viewModel.saveTracingPaletteToRecents() },
            onDismiss = { showTracingAiPopup = false }
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

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("¿Guardar antes de salir?") },
            text = { Text("Tienes trazos recientes en el lienzo. Puedes guardar el proyecto con todas sus capas antes de volver a la galería.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        viewModel.saveToInternalGallery(context) {
                            onNavigateBack()
                        }
                    }
                ) {
                    Text("Guardar y salir")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            onNavigateBack()
                        }
                    ) {
                        Text("Salir sin guardar")
                    }
                    TextButton(onClick = { showExitConfirm = false }) {
                        Text("Seguir")
                    }
                }
            }
        )
    }
}

private data class ToolDockEntry(
    val tool: ToolType,
    val icon: ImageVector,
    val shortLabel: String
)

@Composable
private fun TracingAdjustDock(
    scale: Float,
    rotationDegrees: Float,
    opacity: Float,
    onChangeOpacity: (Float) -> Unit,
    onResetTracing: () -> Unit,
    onDone: () -> Unit
) {
    val deg = (((rotationDegrees % 360f) + 360f) % 360f).roundToInt() % 360
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Arrastra, pellizca o gira el calco",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tamaño ${(scale * 100).roundToInt()}% • ${deg}° • Opacidad ${(opacity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onResetTracing) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = "Centrar calco",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Centrar")
                    }
                    Button(onClick = onDone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Listo")
                    }
                }
            }
            Slider(
                value = opacity,
                onValueChange = onChangeOpacity,
                valueRange = 0.05f..1f
            )
        }
    }
}

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
    onAdjustTracing: () -> Unit,
    onOpenTracingAi: () -> Unit,
    onResetViewport: () -> Unit
) {
    val tools = remember {
        listOf(
            ToolDockEntry(ToolType.BRUSH, Icons.Rounded.Brush, "Pincel"),
            ToolDockEntry(ToolType.MARKER, Icons.Rounded.Edit, "Marcador"),
            ToolDockEntry(ToolType.AIRBRUSH, Icons.Rounded.AutoFixHigh, "Aerosol"),
            ToolDockEntry(ToolType.ERASER, Icons.Rounded.CleaningServices, "Borrador"),
            ToolDockEntry(ToolType.BUCKET, Icons.Rounded.FormatColorFill, "Relleno"),
            ToolDockEntry(ToolType.EYEDROPPER, Icons.Rounded.Colorize, "Gotero")
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Fila 1: Controles rápidos de color, tamaño, modo mover, calco/IA y vista 100%.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(activeColorArgb))
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onOpenColorPicker() }
                )

                Surface(
                    onClick = onOpenBrushSettings,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Ajustar pincel",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "${brushRadius.roundToInt()}px",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    onClick = onTogglePanMode,
                    shape = RoundedCornerShape(10.dp),
                    color = if (isPanMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = if (isPanMode) {
                        Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp)
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
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
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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

                Surface(
                    onClick = onOpenTracing,
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasTracingImage) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = if (hasTracingImage) {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.secondary,
                            RoundedCornerShape(10.dp)
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
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
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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

                if (hasTracingImage) {
                    Surface(
                        onClick = onAdjustTracing,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.OpenWith,
                                contentDescription = "Mover y escalar calco",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onOpenTracingAi,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Asistente IA de calco",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "IA",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        onClick = onToggleTracingVisibility,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Icon(
                                imageVector = if (isTracingVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = if (isTracingVisible) "Ocultar calco" else "Mostrar calco",
                                tint = if (isTracingVisible) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                val normalizedDeg = (((rotationDegrees % 360f) + 360f) % 360f).roundToInt() % 360
                Surface(
                    onClick = onResetViewport,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = "Centrar lienzo",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (normalizedDeg != 0) "$zoomPercent% · ${normalizedDeg}°" else "$zoomPercent%",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            // Fila 2: Barra fija de las 6 herramientas de dibujo (todas visibles a la vez, sin scroll).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tools.forEach { entry ->
                    val selected = !isPanMode && entry.tool == activeTool
                    Surface(
                        onClick = { onSelectTool(entry.tool) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.tool.labelEs,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = entry.shortLabel,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
