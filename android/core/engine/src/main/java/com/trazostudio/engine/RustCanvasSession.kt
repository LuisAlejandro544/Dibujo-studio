package com.trazostudio.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Sesión segura de lienzo respaldada por corrutinas.
 *
 * Lógica de concurrencia:
 * - Usa `Dispatchers.Default.limitedParallelism(1)` para serializar todas las mutaciones
 *   al estado nativo en Rust sin bloquear jamás el hilo principal (UI thread).
 * - Mantiene un `ByteBuffer` directo reutilizable del tamaño exacto `width * height * 4`
 *   para transferir los píxeles desde Rust hacia `Bitmap` sin generar basura en el GC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RustCanvasSession(
    val width: Int,
    val height: Int
) : Closeable {

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var nativeHandle: Long = 0L
    private val pixelBuffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
    // Doble búfer reutilizable para evitar crear un Bitmap nuevo en cada movimiento del dedo.
    private val renderBitmaps: Array<Bitmap> = Array(2) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    private var nextBitmapSlot: Int = 0
    private var closed = false

    suspend fun initialize(): Bitmap = withContext(engineDispatcher) {
        if (nativeHandle == 0L) {
            nativeHandle = NativeEngineBridge.nativeCreateEngine(width, height)
        }
        renderBitmapInternal()
    }

    suspend fun beginStroke() = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeBeginStroke(nativeHandle)
        }
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
     * y todos los segmentos acumulados antes de transferir la imagen compuesta una sola vez.
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

    suspend fun setActiveLayer(index: Int) = withContext(engineDispatcher) {
        if (nativeHandle != 0L) {
            NativeEngineBridge.nativeSetActiveLayer(nativeHandle, index)
        }
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
            strokeCount = raw[4]
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

    suspend fun getRustArchInfo(): String = withContext(Dispatchers.Default) {
        NativeEngineBridge.nativeGetArchInfo()
    }

    private fun renderBitmapInternal(): Bitmap {
        val bmp = renderBitmaps[nextBitmapSlot]
        nextBitmapSlot = 1 - nextBitmapSlot
        if (nativeHandle != 0L) {
            pixelBuffer.rewind()
            val ok = NativeEngineBridge.nativeRenderToDirectBuffer(nativeHandle, pixelBuffer)
            if (ok) {
                pixelBuffer.rewind()
                bmp.copyPixelsFromBuffer(pixelBuffer)
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
