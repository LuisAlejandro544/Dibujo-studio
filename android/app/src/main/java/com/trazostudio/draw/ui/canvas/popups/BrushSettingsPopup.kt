package com.trazostudio.draw.ui.canvas.popups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.ui.theme.StudioImmersiveSheetWindowEffect
import com.trazostudio.engine.BrushSettings
import com.trazostudio.engine.ToolType
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Popup tipo hoja inferior para ajustar tamaño, opacidad, dureza del pincel o tolerancia del relleno.
 * Incluye una vista previa de alto contraste a escala real del lienzo para visualizar grosor,
 * transparencia, dureza del borde y comportamiento de cada herramienta mientras se deslizan los controles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushSettingsPopup(
    brush: BrushSettings,
    canvasToScreenScale: Float = 1f,
    onUpdate: (radius: Float, opacity: Float, hardness: Float, tolerance: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeScale = canvasToScreenScale.coerceAtLeast(0.05f)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        StudioImmersiveSheetWindowEffect()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ajustes de ${brush.tool.labelEs}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (brush.tool == ToolType.BUCKET) {
                            "Relleno por tolerancia de color en Rust"
                        } else {
                            "Diámetro en lienzo: ${(brush.radiusPx * 2f).roundToInt()} px • Escala en vivo"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Panel panorámico de vista previa real (trazo curvo + huella circular a escala real)
            BrushLivePreviewStrip(
                brush = brush,
                canvasToScreenScale = safeScale
            )

            if (brush.tool == ToolType.BUCKET) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tolerancia de relleno", fontWeight = FontWeight.Medium)
                    Text("${brush.fillTolerance}", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = brush.fillTolerance.toFloat(),
                    onValueChange = {
                        onUpdate(brush.radiusPx, brush.opacity, brush.hardness, it.roundToInt())
                    },
                    valueRange = 0f..96f
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tamaño (Radio)", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${brush.radiusPx.roundToInt()} px (Ø ${(brush.radiusPx * 2f).roundToInt()})",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = brush.radiusPx,
                    onValueChange = {
                        onUpdate(it, brush.opacity, brush.hardness, brush.fillTolerance)
                    },
                    valueRange = 1f..90f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Opacidad", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(brush.opacity * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = brush.opacity,
                    onValueChange = {
                        onUpdate(brush.radiusPx, it, brush.hardness, brush.fillTolerance)
                    },
                    valueRange = 0.05f..1f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Dureza de borde", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(brush.hardness * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = brush.hardness,
                    onValueChange = {
                        onUpdate(brush.radiusPx, brush.opacity, it, brush.fillTolerance)
                    },
                    valueRange = 0f..1f
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BrushLivePreviewStrip(
    brush: BrushSettings,
    canvasToScreenScale: Float
) {
    val realRadiusScreenPx = brush.radiusPx * canvasToScreenScale
    val maxPreviewRadiusPx = with(LocalDensity.current) { (92.dp * 0.40f).toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        ) {
            // Fondo claro con cuadrícula sutil para que el negro puro, el blanco y la opacidad sean 100% visibles.
            val cell = 12.dp.toPx()
            val cols = (size.width / cell).toInt() + 1
            val rows = (size.height / cell).toInt() + 1
            drawRect(color = Color.White)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if ((r + c) % 2 == 1) {
                        drawRect(
                            color = Color(0xFFE5E7EB),
                            topLeft = Offset(c * cell, r * cell),
                            size = Size(cell, cell)
                        )
                    }
                }
            }
            // Capa blanca semitransparente que simula la hoja del lienzo sobre la cuadrícula
            drawRect(color = Color.White.copy(alpha = 0.72f))

            if (brush.tool == ToolType.BUCKET) {
                drawRoundRect(
                    color = Color(brush.argbColor),
                    topLeft = Offset(size.width * 0.08f, size.height * 0.18f),
                    size = Size(size.width * 0.84f, size.height * 0.64f)
                )
                return@Canvas
            }

            val maxPreviewRadius = size.height * 0.40f
            val r = min(realRadiusScreenPx, maxPreviewRadius).coerceAtLeast(1.2f)
            val baseColor = if (brush.tool == ToolType.ERASER) {
                Color(0xFF9CA3AF)
            } else {
                Color(brush.argbColor)
            }

            // Trazo curvo representativo en el lado izquierdo
            val strokePath = Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.66f)
                cubicTo(
                    size.width * 0.24f,
                    size.height * 0.16f,
                    size.width * 0.44f,
                    size.height * 0.84f,
                    size.width * 0.64f,
                    size.height * 0.34f
                )
            }

            when (brush.tool) {
                ToolType.ERASER -> {
                    // Simula borrado revelando la cuadrícula del fondo + contorno guía
                    drawPath(
                        path = strokePath,
                        color = Color(0xFFD1D5DB).copy(alpha = brush.opacity),
                        style = Stroke(
                            width = r * 2f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    drawPath(
                        path = strokePath,
                        color = Color(0xFF4B5563),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                ToolType.AIRBRUSH -> {
                    val passes = 5
                    for (i in passes downTo 1) {
                        val factor = i / passes.toFloat()
                        val passAlpha = (brush.opacity * 0.22f * (1.15f - factor * 0.75f)).coerceIn(0.01f, 1f)
                        drawPath(
                            path = strokePath,
                            color = baseColor.copy(alpha = passAlpha),
                            style = Stroke(
                                width = (r * 2f * factor).coerceAtLeast(1f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
                ToolType.MARKER -> {
                    drawPath(
                        path = strokePath,
                        color = baseColor.copy(alpha = brush.opacity),
                        style = Stroke(
                            width = r * 2f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                else -> {
                    // Pincel: simula dureza del borde mediante pasos concéntricos que coinciden con Rust
                    val innerFrac = (0.2f + 0.75f * brush.hardness).coerceIn(0.2f, 0.98f)
                    if (brush.hardness >= 0.92f) {
                        drawPath(
                            path = strokePath,
                            color = baseColor.copy(alpha = brush.opacity),
                            style = Stroke(
                                width = r * 2f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    } else {
                        val bands = 4
                        for (i in 0 until bands) {
                            val t = i / (bands - 1).toFloat()
                            val widthFrac = 1f - t * (1f - innerFrac)
                            val bandAlpha = (brush.opacity / bands.toFloat() * (0.55f + 0.65f * t)).coerceIn(0.02f, 1f)
                            drawPath(
                                path = strokePath,
                                color = baseColor.copy(alpha = bandAlpha),
                                style = Stroke(
                                    width = (r * 2f * widthFrac).coerceAtLeast(1f),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                        drawPath(
                            path = strokePath,
                            color = baseColor.copy(alpha = brush.opacity * (0.45f + 0.55f * brush.hardness)),
                            style = Stroke(
                                width = (r * 2f * innerFrac).coerceAtLeast(1f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }

            // Muestra circular exacta a la derecha con gradiente radial real
            val tipCenter = Offset(size.width * 0.83f, size.height * 0.50f)
            when (brush.tool) {
                ToolType.ERASER -> {
                    drawCircle(
                        color = Color(0xFFD1D5DB).copy(alpha = brush.opacity),
                        radius = r,
                        center = tipCenter
                    )
                    drawCircle(
                        color = Color(0xFF374151),
                        radius = r,
                        center = tipCenter,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
                ToolType.AIRBRUSH -> {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to baseColor.copy(alpha = brush.opacity * 0.72f),
                            0.55f to baseColor.copy(alpha = brush.opacity * 0.25f),
                            1f to baseColor.copy(alpha = 0f),
                            center = tipCenter,
                            radius = r.coerceAtLeast(1f)
                        ),
                        radius = r,
                        center = tipCenter
                    )
                }
                ToolType.MARKER -> {
                    drawCircle(
                        color = baseColor.copy(alpha = brush.opacity),
                        radius = r,
                        center = tipCenter
                    )
                }
                else -> {
                    val innerStop = (0.2f + 0.75f * brush.hardness).coerceIn(0.2f, 0.97f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to baseColor.copy(alpha = brush.opacity),
                            innerStop to baseColor.copy(alpha = brush.opacity),
                            1f to baseColor.copy(alpha = 0f),
                            center = tipCenter,
                            radius = r.coerceAtLeast(1f)
                        ),
                        radius = r,
                        center = tipCenter
                    )
                }
            }

            // Aro guía sutil para distinguir el límite exacto incluso con opacidad baja o color blanco
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = r,
                center = tipCenter,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Vista previa de trazo y punta",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (realRadiusScreenPx <= maxPreviewRadiusPx) {
                    "Escala real 1:1 en pantalla"
                } else {
                    "Vista ajustada (pincel muy grande)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
