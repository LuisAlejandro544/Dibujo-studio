package com.trazostudio.draw.ui.export

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.data.ProjectStorage
import com.trazostudio.draw.ui.canvas.CanvasViewModel
import com.trazostudio.draw.ui.theme.studioTopBarWindowInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla dedicada de Exportación y Detalles de la Obra.
 * Codifica el lienzo final directamente en Rust (crate `png`) y permite:
 * - Activar o desactivar la capa de fondo (PNG transparente).
 * - Guardar en la galería pública del teléfono (`Pictures/TrazoStudio`).
 * - Compartir mediante el menú nativo de Android (`FileProvider`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: CanvasViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var includeBackground by remember { mutableStateOf(true) }
    var pngBytes by remember { mutableStateOf<ByteArray?>(null) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(includeBackground, state.bitmap, state.stats.strokeCount, state.stats.layerCount) {
        val bytes = viewModel.exportPngBytes(includeBackground)
        pngBytes = bytes
        previewBitmap = withContext(Dispatchers.Default) {
            if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = studioTopBarWindowInsets(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver al lienzo")
                    }
                },
                title = {
                    Text("Exportar obra", fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Vista previa de la imagen PNG generada por Rust
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            (state.canvasWidth.toFloat() / state.canvasHeight.toFloat())
                                .coerceIn(0.6f, 1.8f)
                        )
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Vista previa PNG",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            // Tarjeta de ficha técnica de la obra
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = state.projectTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Resolución: ${state.canvasWidth} × ${state.canvasHeight} px • Capas: ${state.stats.layerCount} • Trazos: ${state.stats.strokeCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val kbSize = (pngBytes?.size ?: 0) / 1024
                    Text(
                        text = "Peso PNG estimado: ${kbSize} KB • Codificador nativo Rust",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Incluir capa base de fondo", fontWeight = FontWeight.Medium)
                            Text(
                                text = "Desactiva para exportar PNG con transparencia",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = includeBackground,
                            onCheckedChange = { includeBackground = it }
                        )
                    }
                }
            }

            // Botones de acción principales
            Button(
                onClick = {
                    val bytes = pngBytes ?: return@Button
                    if (bytes.isEmpty()) return@Button
                    scope.launch {
                        isExporting = true
                        viewModel.saveProjectFiles(context)
                        val result = ProjectStorage.exportPngToGallery(
                            context,
                            state.projectTitle,
                            bytes
                        )
                        isExporting = false
                        snackbarHostState.showSnackbar(
                            if (result.isSuccess) {
                                "Guardado en Imágenes/TrazoStudio"
                            } else {
                                "No se pudo guardar en galería: ${result.exceptionOrNull()?.message}"
                            }
                        )
                    }
                },
                enabled = !isExporting && pngBytes?.isNotEmpty() == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Collections, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardar PNG en Galería", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    val bytes = pngBytes ?: return@OutlinedButton
                    if (bytes.isEmpty()) return@OutlinedButton
                    scope.launch {
                        val uri = ProjectStorage.createShareablePngUri(
                            context,
                            state.projectTitle,
                            bytes
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Compartir dibujo")
                        )
                    }
                },
                enabled = pngBytes?.isNotEmpty() == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Compartir imagen PNG")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Motor activo: ${state.archInfo}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
