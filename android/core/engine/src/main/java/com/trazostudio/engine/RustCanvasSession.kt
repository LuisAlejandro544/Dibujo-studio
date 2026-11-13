package com.trazostudio.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Rectángulo mutable acumulado por cada ranura del doble búfer de `Bitmap`
 * para transferir únicamente la subregión modificada desde Rust en cada cuadro.
 */
private class MutableDirtyRect {
    var active: Boolean = false
    var x0: Int = 0
    var y0: Int = 0
    var x1: Int = 0
    var y1: Int = 0

    fun merge(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val nx1 = x + w - 1
        val ny1 = y + h - 1
        if (!active) {
            x0 = x
            y0 = y
            x1 = nx1
            y1 = ny1
            active = true
        } else {
            x0 = minOf(x0, x)
            y0 = minOf(y0, y)
            x1 = maxOf(x1, nx1)
            y1 = maxOf(y1, ny1)
        }
    }

    fun setFull(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            x0 = 0
            y0 = 0
            x1 = width - 1
            y1 = height - 1
            active = true
        }
    }

    fun reset() {
        active = false
    }
}

/**
 * Sesión segura de lienzo respaldada por corrutinas.
 *
 * Lógica de concurrencia y rendimiento:
 * - Usa `Dispatchers.Default.limitedParallelism(1)` para serializar todas las mutaciones
 *   al estado nativo en Rust sin bloquear jamás el hilo principal (UI thread).
 * - Reutiliza un doble búfer de `Bitmap` y un arreglo `IntArray` de transferencia regional:
 *   en cada lote de trazo se copian desde Rust únicamente los píxeles de la subregión sucia
 *   (`dirty_rect`), evitando copiar el lienzo completo en cada cuadro.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RustCanvasSession(
    val width: Int,
    val height: Int
) : Closeable {

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var nativeHandle: Long = 0L

    // Búfer reutilizable para copiar únicamente el rectángulo sucio en formato ARGB_8888.
    private val regionArgbBuffer: IntArray = IntArray(width * height)
    private val dirtyRectScratch: IntArray = IntArray(4)

    // Doble búfer reutilizable para evitar crear un Bitmap nuevo en cada movimiento del dedo.
    private val renderBitmaps: Array<Bitmap> = Array(2) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    private val slotDirtyRects: Array<MutableDirtyRect> = Array(2) { MutableDirtyRect() }
    private var nextBitmapSlot: Int = 0
    private var closed = false

    private fun markAllSlotsFullDirty() {
        slotDirtyRects[0].setFull(width, height)
        slotDirtyRects[1].setFull(width, height)
    }

    suspend fun initialize(): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle == 0L) {
            nativeHandle = NativeEngineBridge.nativeCreateEngine(width, height)
            markAllSlotsFullDirty()
        }
        renderBitmapInternal()
    }

    suspend fun beginStroke() = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeBeginStroke(nativeHandle)
        }
    }

    suspend fun endStroke() = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeEndStroke(nativeHandle)
        }
    }

    suspend fun setExcludeBaseLayer(exclude: Boolean): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeSetExcludeBaseLayer(nativeHandle, exclude)
        }
        renderBitmapInternal()
    }

    suspend fun drawDab(x: Float, y: Float, brush: BrushSettings): Bitmap =
        withContext(engineDispatcher) {
            if (nativeHandle != 0L && brush.tool.isStrokeTool) {
                NativeEngineBridge.nativeApplyDab(
                    handlePtr = nativeHandle,
                    x = x,
                    y = y,
                    brushKind = brush.tool.rustBrushId,
                    argbColor = brush.argbColor,
                    radius = brush.radiusPx,
                    opacity = brush.opacity,
                    hardness = brush.hardness
                )
            }
            renderBitmapInternal()
        }

    suspend fun drawSegment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        brush: BrushSettings
    ): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L && brush.tool.isStrokeTool) {
            NativeEngineBridge.nativeApplySegment(
                handlePtr = nativeHandle,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                brushKind = brush.tool.rustBrushId,
                argbColor = brush.argbColor,
                radius = brush.radiusPx,
                opacity = brush.opacity,
                hardness = brush.hardness
            )
        }
        renderBitmapInternal()
    }

    /**
     * Ejecuta en un único paso por el hilo nativo el inicio de trazo, el punto inicial (opcional)
     * y todos los segmentos acumulados antes de transferir únicamente el rectángulo modificado.
     */
    suspend fun drawStrokeBatch(
        beginTransaction: Boolean,
        hasDab: Boolean,
        dabX: Float,
        dabY: Float,
        segmentCoords: FloatArray,
        floatCount: Int,
        brush: BrushSettings
    ): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L && brush.tool.isStrokeTool) {
            if (beginTransaction) {
                NativeEngineBridge.nativeBeginStroke(nativeHandle)
            }
            if (hasDab) {
                NativeEngineBridge.nativeApplyDab(
                    handlePtr = nativeHandle,
                    x = dabX,
                    y = dabY,
                    brushKind = brush.tool.rustBrushId,
                    argbColor = brush.argbColor,
                    radius = brush.radiusPx,
                    opacity = brush.opacity,
                    hardness = brush.hardness
                )
            }
            if (floatCount >= 4) {
                NativeEngineBridge.nativeApplySegmentsBatch(
                    handlePtr = nativeHandle,
                    coords = segmentCoords,
                    floatCount = floatCount,
                    brushKind = brush.tool.rustBrushId,
                    argbColor = brush.argbColor,
                    radius = brush.radiusPx,
                    opacity = brush.opacity,
                    hardness = brush.hardness
                )
            }
        }
        renderBitmapInternal()
    }

    suspend fun floodFill(x: Int, y: Int, argbColor: Int, tolerance: Int): Pair<Boolean, Bitmap> =
        withContext(engineDispatcher) {
            val changed = if (nativeHandle != 0L) {
                NativeEngineBridge.nativeFloodFill(nativeHandle, x, y, argbColor, tolerance)
            } else {
                false
            }
            changed to renderBitmapInternal()
        }

    suspend fun pickColor(x: Int, y: Int): Int = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativePickColor(nativeHandle, x, y)
        } else {
            0xFF000000.toInt()
        }
    }

    suspend fun clearActiveLayer(): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeClearActiveLayer(nativeHandle)
        }
        renderBitmapInternal()
    }

    suspend fun undo(): Pair<Boolean, Bitmap> = withContext(engineDispatcher) {
        val ok = if (nativeHandle != 0L) NativeEngineBridge.nativeUndo(nativeHandle) else false
        ok to renderBitmapInternal()
    }

    suspend fun redo(): Pair<Boolean, Bitmap> = withContext(engineDispatcher) {
        val ok = if (nativeHandle != 0L) NativeEngineBridge.nativeRedo(nativeHandle) else false
        ok to renderBitmapInternal()
    }

    suspend fun addLayer(name: String): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeAddLayer(nativeHandle, name)
        }
        renderBitmapInternal()
    }

    suspend fun deleteLayer(index: Int): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeDeleteLayer(nativeHandle, index)
        }
        renderBitmapInternal()
    }

    suspend fun setLayerVisibility(index: Int, visible: Boolean): Bitmap =
        withContext(engineDispatcher) {
            if (nativeHandle != 0L) {
                NativeEngineBridge.nativeSetLayerVisibility(nativeHandle, index, visible)
            }
            renderBitmapInternal()
        }

    suspend fun setLayerOpacity(index: Int, opacity: Float): Bitmap =
        withContext(engineDispatcher) {
            if (nativeHandle != 0L) {
                NativeEngineBridge.nativeSetLayerOpacity(nativeHandle, index, opacity)
            }
            renderBitmapInternal()
        }

    suspend fun setActiveLayer(index: Int): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeSetActiveLayer(nativeHandle, index)
        }
        renderBitmapInternal()
    }

    suspend fun mergeLayerDown(index: Int): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeMergeLayerDown(nativeHandle, index)
        }
        renderBitmapInternal()
    }

    suspend fun getStats(): CanvasStats = withContext(engineDispatcher) {
        if (nativeHandle == 0L) return@withContext CanvasStats()
        val raw = NativeEngineBridge.nativeGetStats(nativeHandle)
        if (raw.size < 5) return@withContext CanvasStats()
        CanvasStats(
            layerCount = raw[0],
            activeLayerIndex = raw[1],
            canUndo = raw[2] == 1,
            canRedo = raw[3] == 1,
            strokeCount = raw[4],
            baseLayerVisible = raw.getOrElse(5) { 1 } == 1,
            baseLayerModified = raw.getOrElse(6) { 0 } == 1
        )
    }

    suspend fun getLayers(): List<LayerInfo> = withContext(engineDispatcher) {
        if (nativeHandle == 0L) return@withContext emptyList()
        val manifest = NativeEngineBridge.nativeGetLayersManifest(nativeHandle)
        if (manifest.isBlank()) return@withContext emptyList()
        manifest.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 5) return@mapNotNull null
            LayerInfo(
                index = parts[0].toIntOrNull() ?: return@mapNotNull null,
                id = parts[1].toIntOrNull() ?: 0,
                name = parts[2],
                visible = parts[3] == "1",
                opacity = parts[4].replace(',', '.').toFloatOrNull() ?: 1f
            )
        }
    }

    suspend fun exportPngBytes(includeBackground: Boolean): ByteArray =
        withContext(engineDispatcher) {
            if (nativeHandle == 0L) return@withContext ByteArray(0)
            NativeEngineBridge.nativeExportPng(nativeHandle, includeBackground)
        }

    suspend fun exportProjectBytes(): ByteArray = withContext(engineDispatcher) {
        if (nativeHandle == 0L) return@withContext ByteArray(0)
        NativeEngineBridge.nativeExportProject(nativeHandle)
    }

    /** Carga un proyecto multicapa `.trazo` en el motor Rust y devuelve el Bitmap actualizado. */
    suspend fun loadProjectBytes(projectBytes: ByteArray): Pair<Boolean, Bitmap> =
        withContext(engineDispatcher) {
            val ok = if (nativeHandle != 0L && projectBytes.isNotEmpty()) {
                NativeEngineBridge.nativeLoadProject(nativeHandle, projectBytes)
            } else {
                false
            }
            if (ok) {
                markAllSlotsFullDirty()
            }
            ok to renderBitmapInternal()
        }

    /** Carga los bytes de un PNG guardado en el motor Rust y devuelve el Bitmap actualizado. */
    suspend fun loadPngBytes(pngBytes: ByteArray): Pair<Boolean, Bitmap> =
        withContext(engineDispatcher) {
            val ok = if (nativeHandle != 0L && pngBytes.isNotEmpty()) {
                NativeEngineBridge.nativeLoadPng(nativeHandle, pngBytes)
            } else {
                false
            }
            if (ok) {
                markAllSlotsFullDirty()
            }
            ok to renderBitmapInternal()
        }

    suspend fun getRustArchInfo(): String = withContext(Dispatchers.Default) {
        NativeEngineBridge.nativeGetArchInfo()
    }

    /**
     * Ejecuta el analizador inteligente local en Rust (`tracing_ai.rs`) sobre la imagen de calco,
     * calculando los radios de pincel reales según el tamaño del calco en el lienzo y la paleta
     * de colores principales para colorear.
     */
    suspend fun analyzeTracingBitmap(
        tracingBitmap: Bitmap,
        tracingScale: Float
    ): TracingAiAnalysis? = withContext(Dispatchers.Default) {
        val srcW = tracingBitmap.width
        val srcH = tracingBitmap.height
        if (srcW <= 0 || srcH <= 0) return@withContext null

        // Limitar muestra a 640px máximo para minimizar memoria JNI en dispositivos de 32 bits.
        val maxSide = 640
        val sampleBmp: Bitmap
        val createdTemp: Boolean
        if (srcW > maxSide || srcH > maxSide) {
            val ratio = minOf(maxSide.toFloat() / srcW, maxSide.toFloat() / srcH)
            val tw = (srcW * ratio).toInt().coerceAtLeast(16)
            val th = (srcH * ratio).toInt().coerceAtLeast(16)
            sampleBmp = Bitmap.createScaledBitmap(tracingBitmap, tw, th, true)
            createdTemp = true
        } else {
            sampleBmp = tracingBitmap
            createdTemp = false
        }

        val sampleW = sampleBmp.width
        val sampleH = sampleBmp.height
        val pixels = IntArray(sampleW * sampleH)
        sampleBmp.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (createdTemp && sampleBmp !== tracingBitmap) {
            sampleBmp.recycle()
        }

        val baseFit = minOf(
            width.toFloat() / srcW.toFloat().coerceAtLeast(1f),
            height.toFloat() / srcH.toFloat().coerceAtLeast(1f)
        )
        val renderedWidthOnCanvas = (srcW.toFloat() * baseFit * tracingScale.coerceAtLeast(0.15f))
            .coerceAtLeast(32f)

        val manifest = NativeEngineBridge.nativeAnalyzeTracingImage(
            argbPixels = pixels,
            width = sampleW,
            height = sampleH,
            renderedWidthOnCanvas = renderedWidthOnCanvas
        )
        parseTracingAiManifest(manifest)
    }

    private fun parseTracingAiManifest(manifest: String): TracingAiAnalysis? {
        if (manifest.isBlank()) return null
        var style = TracingStyleKind.LOW_DETAIL
        var fineRadius = 3f
        var mainRadius = 6.5f
        var boldRadius = 14f
        var hardness = 0.88f
        var fillTolerance = 28
        var outlineArgb = 0xFF141414.toInt()
        var inkRatioPct = 0
        val palette = ArrayList<TracingPaletteColor>(8)

        manifest.lines().forEach { line ->
            val parts = line.split("|")
            when (parts.firstOrNull()) {
                "META" -> if (parts.size >= 9) {
                    style = TracingStyleKind.fromCode(parts[1].toIntOrNull() ?: 3)
                    fineRadius = parts[2].replace(',', '.').toFloatOrNull() ?: 3f
                    mainRadius = parts[3].replace(',', '.').toFloatOrNull() ?: 6.5f
                    boldRadius = parts[4].replace(',', '.').toFloatOrNull() ?: 14f
                    hardness = parts[5].replace(',', '.').toFloatOrNull() ?: 0.88f
                    fillTolerance = parts[6].toIntOrNull() ?: 28
                    outlineArgb = parts[7].toIntOrNull() ?: 0xFF141414.toInt()
                    inkRatioPct = parts[8].toIntOrNull() ?: 0
                }
                "COLOR" -> if (parts.size >= 5) {
                    val argb = parts[1].toIntOrNull() ?: return@forEach
                    val pct = parts[2].toIntOrNull() ?: 1
                    val isOutline = parts[3] == "1"
                    val roleCode = parts[4].toIntOrNull() ?: 4
                    val roleLabel = when (roleCode) {
                        0 -> "Contorno / Tinta"
                        1 -> "Color principal"
                        2 -> "Color secundario"
                        3 -> "Base / Claro"
                        else -> "Detalle / Acento"
                    }
                    palette.add(
                        TracingPaletteColor(
                            argbColor = argb,
                            coveragePercent = pct,
                            isOutline = isOutline,
                            roleLabel = roleLabel
                        )
                    )
                }
            }
        }

        val mainTool = if (style == TracingStyleKind.LINE_ART && mainRadius >= 7f) {
            ToolType.MARKER
        } else {
            ToolType.BRUSH
        }
        val presets = listOf(
            TracingStrokePreset(
                title = "Detalles y ojos",
                subtitle = "Líneas finas, pupilas, pestañas y rasgos internos",
                tool = ToolType.BRUSH,
                radiusPx = fineRadius,
                hardness = (hardness + 0.04f).coerceIn(0.6f, 0.96f),
                opacity = 1f
            ),
            TracingStrokePreset(
                title = "Contorno principal",
                subtitle = "Grosor promedio detectado en el borde del dibujo",
                tool = mainTool,
                radiusPx = mainRadius,
                hardness = hardness,
                opacity = 1f
            ),
            TracingStrokePreset(
                title = "Trazo grueso / Sombra",
                subtitle = "Bordes exteriores marcados o bloqueado de color",
                tool = ToolType.MARKER,
                radiusPx = boldRadius,
                hardness = 0.92f,
                opacity = 1f
            )
        )

        return TracingAiAnalysis(
            style = style,
            strokePresets = presets,
            recommendedFillTolerance = fillTolerance,
            outlineColorArgb = outlineArgb,
            inkRatioPercent = inkRatioPct,
            palette = palette
        )
    }

    private fun renderBitmapInternal(): Bitmap {
        val slot = nextBitmapSlot
        val bmp = renderBitmaps[slot]
        nextBitmapSlot = 1 - nextBitmapSlot
        if (nativeHandle != 0L) {
            val hasDirty = NativeEngineBridge.nativePrepareRender(nativeHandle, dirtyRectScratch)
            if (hasDirty) {
                val rx = dirtyRectScratch[0]
                val ry = dirtyRectScratch[1]
                val rw = dirtyRectScratch[2]
                val rh = dirtyRectScratch[3]
                slotDirtyRects[0].merge(rx, ry, rw, rh)
                slotDirtyRects[1].merge(rx, ry, rw, rh)
            }

            val targetDirty = slotDirtyRects[slot]
            if (targetDirty.active) {
                val rx = targetDirty.x0.coerceIn(0, width - 1)
                val ry = targetDirty.y0.coerceIn(0, height - 1)
                val rx1 = targetDirty.x1.coerceIn(rx, width - 1)
                val ry1 = targetDirty.y1.coerceIn(ry, height - 1)
                val rw = rx1 - rx + 1
                val rh = ry1 - ry + 1

                val copied = NativeEngineBridge.nativeCopyRegionArgb(
                    handlePtr = nativeHandle,
                    x = rx,
                    y = ry,
                    w = rw,
                    h = rh,
                    outArgb = regionArgbBuffer
                )
                if (copied) {
                    bmp.setPixels(regionArgbBuffer, 0, rw, rx, ry, rw, rh)
                    targetDirty.reset()
                }
            }
        }
        return bmp
    }

    override fun close() {
        if (!closed && nativeHandle != 0L) {
            closed = true
            val ptr = nativeHandle
            nativeHandle = 0L
            NativeEngineBridge.nativeDestroyEngine(ptr)
        }
    }
}
