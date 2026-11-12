package com.trazostudio.engine

/**
 * Modelos de datos compartidos entre la UI en Jetpack Compose y el motor nativo en Rust.
 */

/** Herramientas disponibles en el estudio de dibujo. */
enum class ToolType(val rustBrushId: Int, val labelEs: String) {
    BRUSH(0, "Pincel"),
    MARKER(1, "Marcador"),
    AIRBRUSH(2, "Aerógrafo"),
    ERASER(3, "Borrador"),
    BUCKET(-1, "Relleno"),
    EYEDROPPER(-2, "Cuentagotas");

    val isStrokeTool: Boolean
        get() = rustBrushId >= 0
}

/** Configuración actual del pincel enviada a cada llamada de rasterización en Rust. */
data class BrushSettings(
    val tool: ToolType = ToolType.BRUSH,
    // Color inicial negro puro por ser el más habitual al empezar a dibujar.
    val argbColor: Int = 0xFF000000.toInt(),
    val radiusPx: Float = 14f,
    val opacity: Float = 1f,
    val hardness: Float = 0.85f,
    val fillTolerance: Int = 28
)

/** Representación inmutable de una capa leída desde el manifiesto del motor Rust. */
data class LayerInfo(
    val index: Int,
    val id: Int,
    val name: String,
    val visible: Boolean,
    val opacity: Float
)

/** Estadísticas en vivo del estado interno del lienzo en Rust. */
data class CanvasStats(
    val layerCount: Int = 2,
    val activeLayerIndex: Int = 1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val strokeCount: Int = 0
)
