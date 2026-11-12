package com.trazostudio.draw.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CropPortrait
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trazostudio.draw.data.ProjectStorage
import com.trazostudio.draw.data.SavedDrawingItem
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.ui.theme.studioTopBarWindowInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CanvasTemplate(
    val title: String,
    val subtitle: String,
    val width: Int,
    val height: Int,
    val icon: ImageVector
)

/**
 * Pantalla principal (Galería e Inicio del estudio).
 * Proporciona acceso directo a plantillas de lienzo, creación personalizada mediante popup,
 * vista de obras guardadas en disco y navegación a Ajustes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    preferences: StudioPreferences,
    onOpenNewCanvas: (title: String, width: Int, height: Int) -> Unit,
    onOpenSavedArtwork: (SavedDrawingItem) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var savedItems by remember { mutableStateOf<List<SavedDrawingItem>>(emptyList()) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }

    val templates = remember(preferences) {
        listOf(
            CanvasTemplate(
                "Predeterminado",
                "${preferences.defaultWidth} × ${preferences.defaultHeight}",
                preferences.defaultWidth,
                preferences.defaultHeight,
                Icons.Rounded.Tune
            ),
            CanvasTemplate("Cuadrado HD", "1080 × 1080 px", 1080, 1080, Icons.Rounded.CropSquare),
            CanvasTemplate("Retrato Ilustración", "1080 × 1440 px", 1080, 1440, Icons.Rounded.CropPortrait),
            CanvasTemplate("Paisaje Cine", "1600 × 900 px", 1600, 900, Icons.Rounded.Landscape),
            CanvasTemplate("Boceto Rápido", "800 × 800 px", 800, 800, Icons.Rounded.AspectRatio)
        )
    }

    LaunchedEffect(Unit) {
        savedItems = ProjectStorage.listSavedDrawings(context)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = studioTopBarWindowInsets(),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "TrazoStudio",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Estudio de dibujo nativo",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Ajustes del estudio"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewCanvasDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Nuevo lienzo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            // Sección superior: Plantillas de creación inmediata
            Text(
                text = "Crear desde plantilla",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates) { tpl ->
                    Card(
                        onClick = {
                            onOpenNewCanvas(tpl.title, tpl.width, tpl.height)
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.width(168.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = tpl.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = tpl.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tpl.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Sección inferior: Obras guardadas localmente
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Obras guardadas (${savedItems.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = { showNewCanvasDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Brush,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lienzo libre")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (savedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Brush,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Tu mesa de trabajo está lista",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Elige una plantilla arriba o pulsa 'Nuevo lienzo' para empezar a dibujar con el motor nativo en Rust.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    onOpenNewCanvas(
                                        "Primer Boceto",
                                        preferences.defaultWidth,
                                        preferences.defaultHeight
                                    )
                                }
                            ) {
                                Text("Abrir lienzo ahora")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(savedItems, key = { it.id }) { item ->
                        SavedArtworkCard(
                            item = item,
                            onDelete = {
                                scope.launch {
                                    ProjectStorage.deleteSavedDrawing(item.previewFile)
                                    savedItems = ProjectStorage.listSavedDrawings(context)
                                }
                            },
                            onOpenTemplate = {
                                onOpenSavedArtwork(item)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewCanvasDialog) {
        NewCanvasPopupDialog(
            defaultWidth = preferences.defaultWidth,
            defaultHeight = preferences.defaultHeight,
            onDismiss = { showNewCanvasDialog = false },
            onCreate = { title, width, height ->
                showNewCanvasDialog = false
                onOpenNewCanvas(title, width, height)
            }
        )
    }
}

@Composable
private fun SavedArtworkCard(
    item: SavedDrawingItem,
    onDelete: () -> Unit,
    onOpenTemplate: () -> Unit
) {
    var thumbnail by remember(item.previewFile) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(item.previewFile) {
        thumbnail = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(item.previewFile.absolutePath)
        }
    }

    val dateStr = remember(item.modifiedAtMs) {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(item.modifiedAtMs))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable { onOpenTemplate() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.width}×${item.height} • $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Popup modal para configurar el nombre y resolución personalizada del nuevo lienzo.
 */
@Composable
private fun NewCanvasPopupDialog(
    defaultWidth: Int,
    defaultHeight: Int,
    onDismiss: () -> Unit,
    onCreate: (title: String, width: Int, height: Int) -> Unit
) {
    var title by remember { mutableStateOf("Ilustración") }
    var widthText by remember { mutableStateOf(defaultWidth.toString()) }
    var heightText by remember { mutableStateOf(defaultHeight.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurar nuevo lienzo", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nombre del proyecto") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Ancho (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Alto (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Rango recomendado en móvil: 512 px a 2048 px.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = (widthText.toIntOrNull() ?: defaultWidth).coerceIn(256, 2560)
                    val h = (heightText.toIntOrNull() ?: defaultHeight).coerceIn(256, 2560)
                    onCreate(title.ifBlank { "Ilustración" }, w, h)
                }
            ) {
                Text("Crear lienzo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
