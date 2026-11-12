package com.trazostudio.draw.ui.canvas.popups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trazostudio.engine.BrushSettings
import com.trazostudio.engine.ToolType
import kotlin.math.roundToInt

/**
 * Popup tipo hoja inferior para ajustar tamaño, opacidad, dureza del pincel o tolerancia del relleno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushSettingsPopup(
    brush: BrushSettings,
    onUpdate: (radius: Float, opacity: Float, hardness: Float, tolerance: Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        text = "Rasterizado nativo en Rust",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Vista previa en tiempo real del diámetro del pincel
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val previewColor = if (brush.tool == ToolType.ERASER) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color(brush.argbColor)
                    }
                    Canvas(modifier = Modifier.size(56.dp)) {
                        val visualRadius = (brush.radiusPx / 2f).coerceIn(2f, size.minDimension / 2.2f)
                        drawCircle(
                            color = previewColor.copy(alpha = brush.opacity),
                            radius = visualRadius
                        )
                    }
                }
            }

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
                    Text("${brush.radiusPx.roundToInt()} px", color = MaterialTheme.colorScheme.primary)
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
                    Text("${(brush.opacity * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
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
                    Text("${(brush.hardness * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
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
