package com.trazostudio.engine

import java.nio.ByteBuffer

/**
 * Puente directo JNI hacia la librería compilada en Rust (`libtrazo_engine.so`).
 * No existen implementaciones de respaldo en Kotlin: todo el dibujado, capas,
 * relleno y exportación PNG ocurren exclusivamente en Rust.
 */
internal object NativeEngineBridge {
    init {
        System.loadLibrary("trazo_engine")
    }

    external fun nativeCreateEngine(width: Int, height: Int): Long
    external fun nativeDestroyEngine(handlePtr: Long)

    external fun nativeBeginStroke(handlePtr: Long)
    external fun nativeApplyDab(
        handlePtr: Long,
        x: Float,
        y: Float,
        brushKind: Int,
        argbColor: Int,
        radius: Float,
        opacity: Float,
        hardness: Float
    )

    external fun nativeApplySegment(
        handlePtr: Long,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        brushKind: Int,
        argbColor: Int,
        radius: Float,
        opacity: Float,
        hardness: Float
    )

    external fun nativeApplySegmentsBatch(
        handlePtr: Long,
        coords: FloatArray,
        floatCount: Int,
        brushKind: Int,
        argbColor: Int,
        radius: Float,
        opacity: Float,
        hardness: Float
    )

    external fun nativeFloodFill(
        handlePtr: Long,
        x: Int,
        y: Int,
        argbColor: Int,
        tolerance: Int
    ): Boolean

    external fun nativePickColor(handlePtr: Long, x: Int, y: Int): Int
    external fun nativeClearActiveLayer(handlePtr: Long)

    external fun nativeUndo(handlePtr: Long): Boolean
    external fun nativeRedo(handlePtr: Long): Boolean

    external fun nativeAddLayer(handlePtr: Long, name: String): Int
    external fun nativeDeleteLayer(handlePtr: Long, index: Int): Boolean
    external fun nativeSetLayerVisibility(handlePtr: Long, index: Int, visible: Boolean)
    external fun nativeSetLayerOpacity(handlePtr: Long, index: Int, opacity: Float)
    external fun nativeSetActiveLayer(handlePtr: Long, index: Int)
    external fun nativeMergeLayerDown(handlePtr: Long, index: Int): Boolean

    external fun nativeRenderToDirectBuffer(handlePtr: Long, directBuffer: ByteBuffer): Boolean
    external fun nativeExportPng(handlePtr: Long, includeBackground: Boolean): ByteArray
    external fun nativeGetStats(handlePtr: Long): IntArray
    external fun nativeGetLayersManifest(handlePtr: Long): String
    external fun nativeGetArchInfo(): String
}
