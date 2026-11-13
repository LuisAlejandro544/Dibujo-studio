package com.trazostudio.draw.ui.canvas.popups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.ui.theme.StudioImmersiveSheetWindowEffect
import com.trazostudio.engine.TracingAiAnalysis
import com.trazostudio.engine.TracingStrokePreset
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Popup del Asistente IA local de Calco (procesado 100% en Rust).
 *
 * Muestra al usuario:
 * 1. Grosores recomendados en píxeles reales del lienzo (detalles/ojos, contorno principal y trazo grueso)
 *    calculados según el grosor de líneas de la imagen y su escala actual sobre el lienzo.
 * 2. Paleta extraída del dibujo (tinta de contorno y colores principales) para colorear con un toque
 *    usando Pincel o Bote de Relleno con tolerancia óptima.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracingAiPopup(
    analysis: TracingAiAnalysis?,
    isAnalyzing: Boolean,
    tracingScale: Float,
    canvasToScreenScale: Float,
    activeColorArgb: Int,
    onReanalyze: () -> Unit,
    onApplyStroke: (preset: TracingStrokePreset, useOutlineColor: Boolean) -> Unit,
    onApplyColor: (argbColor: Int, switchToBucket: Boolean, recommendedTolerance: Int?) -> Unit,
    onSavePaletteToRecents: () -> Unit,
    onDismiss: () -> Unit
) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Asistente IA de Calco",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Grosores y colores detectados • Calco al ${(tracingScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onReanalyze,
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reanalizar calco"
                    )
                }
            }

            if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Analizando grosores de línea y paleta...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (analysis == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Carga una imagen de calco para analizar el grosor ideal de trazo y sus colores.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Resumen de diagnóstico
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Estilo: ${analysis.style.labelEs}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = analysis.style.tipEs,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 1. Recomendaciones de grosor para calcar (ojos/detalles, contorno, grueso)
                Text(
                    text = "Tamaños de trazo para calcar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                analysis.strokePresets.forEachIndexed { idx, preset ->
                    StrokeRecommendationCard(
                        preset = preset,
                        showOutlineButton = idx < 2,
                        canvasToScreenScale = canvasToScreenScale,
                        onApply = { withOutline ->
                            onApplyStroke(preset, withOutline)
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 2. Paleta extraída del dibujo para colorear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Colores para colorear",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(onClick = onSavePaletteToRecents) {
                        Text("Guardar paleta")
                    }
                }

                Text(
                    text = "Toca Pintar para trazar con ese tono o Bote para rellenar áreas cerradas (tolerancia ${analysis.recommendedFillTolerance}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                analysis.palette.forEach { entry ->
                    val isSelected = entry.argbColor == activeColorArgb
                    val hex = String.format("#%06X", entry.argbColor and 0x00FFFFFF)
                    Surface(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(14.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                onApplyColor(entry.argbColor, false, analysis.recommendedFillTolerance)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(entry.argbColor))
                                    .border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.roleLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$hex • ${entry.coveragePercent}% del dibujo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        onApplyColor(entry.argbColor, false, analysis.recommendedFillTolerance)
                                        onDismiss()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Brush,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pintar", style = MaterialTheme.typography.labelMedium)
                                }
                                if (!entry.isOutline) {
                                    OutlinedButton(
                                        onClick = {
                                            onApplyColor(entry.argbColor, true, analysis.recommendedFillTolerance)
                                            onDismiss()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FormatColorFill,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Bote", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StrokeRecommendationCard(
    preset: TracingStrokePreset,
    showOutlineButton: Boolean,
    canvasToScreenScale: Float,
    onApply: (useOutlineColor: Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vista proporcional del grosor real sobre fondo blanco
            Canvas(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            ) {
                val maxDotR = size.minDimension * 0.42f
                val visualR = min(preset.radiusPx * canvasToScreenScale.coerceAtLeast(0.25f), maxDotR)
                    .coerceAtLeast(1.8f)
                drawCircle(
                    color = Color(0xFF111827),
                    radius = visualR,
                    center = center
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${preset.tool.labelEs} • ${preset.radiusPx.roundToInt()} px (Ø ${(preset.radiusPx * 2f).roundToInt()})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = preset.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                Button(
                    onClick = { onApply(false) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Usar", style = MaterialTheme.typography.labelMedium)
                }
                if (showOutlineButton) {
                    OutlinedButton(
                        onClick = { onApply(true) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("+ Tinta", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
