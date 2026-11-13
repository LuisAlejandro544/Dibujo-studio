package com.trazostudio.draw.ui.canvas.popups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.ui.canvas.TracingReferenceState
import com.trazostudio.draw.ui.theme.StudioImmersiveSheetWindowEffect
import kotlin.math.roundToInt

/**
 * Popup modular de hoja inferior para gestionar la imagen de calco (referencia desde galería):
 * - Seleccionar o cambiar una imagen desde la galería del dispositivo.
 * - Mostrar u ocultar el calco en cualquier momento sin perder su escala ni posición.
 * - Ajustar tamaño (más grande/pequeño), opacidad y posición sobre el lienzo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracingReferencePopup(
    tracing: TracingReferenceState,
    onPickImageUri: (android.net.Uri) -> Unit,
    onToggleVisibility: (Boolean) -> Unit,
    onChangeOverlayOnTop: (Boolean) -> Unit,
    onEnterFingerAdjustMode: () -> Unit,
    onOpenAiAssistant: () -> Unit,
    onChangeScale: (Float) -> Unit,
    onChangeOpacity: (Float) -> Unit,
    onChangeOffset: (Float, Float) -> Unit,
    onResetTransform: () -> Unit,
    onClearImage: () -> Unit,
    onDismiss: () -> Unit
) {
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onPickImageUri(uri)
        }
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Imagen para calcar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Guía visual (no se guarda al exportar)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    enabled = !tracing.isLoading
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (tracing.bitmap == null) "Galería" else "Cambiar")
                }
            }

            if (tracing.isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Cargando imagen desde la galería...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            val bmp = tracing.bitmap
            if (bmp == null && !tracing.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Elige una foto o boceto de tu galería para colocarlo debajo de tus trazos (sin opacar tus colores) o encima en modo multiplicar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (bmp != null) {
                val previewBitmap = remember(bmp) { bmp.asImageBitmap() }

                // Vista previa + interruptor rápido para mostrar/ocultar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Miniatura de calco",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (tracing.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (tracing.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (tracing.visible) "Calco visible" else "Calco oculto",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${bmp.width}×${bmp.height} px • ${(tracing.scale * 100).roundToInt()}% tamaño",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = tracing.visible,
                        onCheckedChange = onToggleVisibility
                    )
                }

                // Botón principal para manipular el calco directamente en el lienzo con los dedos
                Button(
                    onClick = {
                        onEnterFingerAdjustMode()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenWith,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mover y escalar con los dedos")
                }

                // Asistente IA local en Rust: analiza grosores de trazo (ojos, contorno) y paleta para colorear
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onOpenAiAssistant()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Asistente IA: grosor de trazos y colores")
                }

                // Posición del calco respecto a los trazos del usuario
                Text("Posición del calco", fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !tracing.overlayOnTop,
                        onClick = { onChangeOverlayOnTop(false) },
                        label = { Text("Debajo del trazo") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = tracing.overlayOnTop,
                        onClick = { onChangeOverlayOnTop(true) },
                        label = { Text("Encima (Multiplicar)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Tamaño (más grande o pequeño) con botones de paso fino y barra deslizante
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tamaño del calco", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(tracing.scale * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onChangeScale((tracing.scale - 0.1f).coerceAtLeast(0.15f)) }
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Hacer más pequeño")
                    }
                    Slider(
                        value = tracing.scale,
                        onValueChange = onChangeScale,
                        valueRange = 0.15f..5f,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onChangeScale((tracing.scale + 0.1f).coerceAtMost(5f)) }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Hacer más grande")
                    }
                }

                // Opacidad
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Opacidad", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(tracing.opacity * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = tracing.opacity,
                    onValueChange = onChangeOpacity,
                    valueRange = 0.05f..1f
                )

                // Posición horizontal y vertical para encuadrar la imagen donde se necesite
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Posición horizontal", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(tracing.offsetX * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = tracing.offsetX,
                    onValueChange = { onChangeOffset(it, tracing.offsetY) },
                    valueRange = -1.5f..1.5f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Posición vertical", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${(tracing.offsetY * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = tracing.offsetY,
                    onValueChange = { onChangeOffset(tracing.offsetX, it) },
                    valueRange = -1.5f..1.5f
                )

                // Acciones secundarias: centrar/restablecer o quitar calco
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetTransform,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Centrar")
                    }
                    OutlinedButton(
                        onClick = {
                            onClearImage()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Quitar",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
