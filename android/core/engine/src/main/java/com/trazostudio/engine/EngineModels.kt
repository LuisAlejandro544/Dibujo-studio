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
    val strokeCount: Int = 0,
    val baseLayerVisible: Boolean = true,
    val baseLayerModified: Boolean = false
)

/** Estilo visual detectado por el analizador nativo en Rust sobre la imagen de calco. */
enum class TracingStyleKind(val code: Int, val labelEs: String, val tipEs: String) {
    LINE_ART(
        0,
        "Contorno definido",
        "Ideal para calcar líneas limpias en capa superior y rellenar con Bote o Marcador."
    ),
    SKETCH(
        1,
        "Boceto o trazo suave",
        "Usa Pincel con dureza media-alta para seguir las líneas de lápiz o sombreado."
    ),
    PAINTING_OR_PHOTO(
        2,
        "Foto o pintura sin contorno",
        "Sigue los bordes principales con trazo fino y bloquea colores base con Marcador."
    ),
    LOW_DETAIL(
        3,
        "Pocos trazos / imagen clara",
        "Se sugieren grosores equilibrados según el tamaño actual del calco en el lienzo."
    );

    companion object {
        fun fromCode(code: Int): TracingStyleKind = entries.firstOrNull { it.code == code } ?: LOW_DETAIL
    }
}

/** Recomendación de grosor de trazo lista para aplicar con un toque al pincel activo. */
data class TracingStrokePreset(
    val title: String,
    val subtitle: String,
    val tool: ToolType,
    val radiusPx: Float,
    val hardness: Float,
    val opacity: Float = 1f
)

/** Color extraído por K-Means++ en Rust para trazar contorno o colorear el dibujo. */
data class TracingPaletteColor(
    val argbColor: Int,
    val coveragePercent: Int,
    val isOutline: Boolean,
    val roleLabel: String
)

/** Resultado completo del asistente local de calco generado nativamente en Rust. */
data class TracingAiAnalysis(
    val style: TracingStyleKind,
    val strokePresets: List<TracingStrokePreset>,
    val recommendedFillTolerance: Int,
    val outlineColorArgb: Int,
    val inkRatioPercent: Int,
    val palette: List<TracingPaletteColor>
)
