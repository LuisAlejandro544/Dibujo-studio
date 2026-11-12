package com.trazostudio.draw.ui.canvas.popups

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.ui.theme.StudioImmersiveSheetWindowEffect

private val STUDIO_SWATCHES = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFEF4444.toInt(),
    0xFFF97316.toInt(),
    0xFFF59E0B.toInt(),
    0xFF84CC16.toInt(),
    0xFF10B981.toInt(),
    0xFF06B6D4.toInt(),
    0xFF3B82F6.toInt(),
    0xFF6366F1.toInt(),
    0xFFA855F7.toInt(),
    0xFFEC4899.toInt()
)

/**
 * Popup selector de color HSV interactivo + muestras rápidas del estudio y colores recientes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerPopup(
    currentColorArgb: Int,
    recentColors: List<Int>,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = remember(currentColorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(currentColorArgb, it) }
    }
    var hue by remember(currentColorArgb) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(currentColorArgb) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(currentColorArgb) { mutableFloatStateOf(initialHsv[2]) }

    val activeArgb = remember(hue, sat, value) {
        AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    }
    val hexText = remember(activeArgb) {
        String.format("#%06X", 0xFFFFFF and activeArgb)
    }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(activeArgb))
                            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Selector de Color",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = hexText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Barra visual del espectro de matiz (Hue)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red
                            )
                        )
                    )
            )

            Text("Matiz (${hue.toInt()}°)", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    onColorSelected(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
                },
                valueRange = 0f..360f
            )

            Text("Saturación (${(sat * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = sat,
                onValueChange = {
                    sat = it
                    onColorSelected(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(activeArgb),
                    activeTrackColor = Color(activeArgb)
                )
            )

            Text("Luminosidad (${(value * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    onColorSelected(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
                },
                valueRange = 0f..1f
            )

            Text(
                text = "Paleta del estudio",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val combined = (recentColors + STUDIO_SWATCHES).distinct().take(16)
                combined.forEach { swatchArgb ->
                    val isSelected = swatchArgb == activeArgb
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(swatchArgb))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable {
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(swatchArgb, hsv)
                                hue = hsv[0]
                                sat = hsv[1]
                                value = hsv[2]
                                onColorSelected(swatchArgb)
                            }
                    )
                }
            }
        }
    }
}
