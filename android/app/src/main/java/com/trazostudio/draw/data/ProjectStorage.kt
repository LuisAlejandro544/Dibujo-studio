package com.trazostudio.draw.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Metadatos de un dibujo guardado localmente en la galería interna del estudio. */
data class SavedDrawingItem(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val previewFile: File,
    val modifiedAtMs: Long
)

/**
 * Capa de persistencia en disco (Dispatchers.IO) para:
 * 1. Guardar capturas PNG de los proyectos creados dentro de `filesDir/projects`.
 * 2. Exportar el PNG codificado por Rust hacia la galería pública (`MediaStore.Images`).
 * 3. Preparar URIs seguras mediante `FileProvider` para compartir hacia otras apps.
 */
object ProjectStorage {

    private fun projectsDir(context: Context): File {
        val dir = File(context.filesDir, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun listSavedDrawings(context: Context): List<SavedDrawingItem> =
        withContext(Dispatchers.IO) {
            val dir = projectsDir(context)
            val files = dir.listFiles { file -> file.extension.equals("png", ignoreCase = true) }
                ?: return@withContext emptyList()

            files.mapNotNull { file ->
                val baseName = file.nameWithoutExtension
                val parts = baseName.split("__")
                val title = parts.getOrNull(0)?.replace('_', ' ') ?: baseName
                val w = parts.getOrNull(1)?.toIntOrNull() ?: 1080
                val h = parts.getOrNull(2)?.toIntOrNull() ?: 1080
                SavedDrawingItem(
                    id = baseName,
                    title = title,
                    width = w,
                    height = h,
                    previewFile = file,
                    modifiedAtMs = file.lastModified()
                )
            }.sortedByDescending { it.modifiedAtMs }
        }

    suspend fun saveProjectPreview(
        context: Context,
        projectTitle: String,
        width: Int,
        height: Int,
        pngBytes: ByteArray,
        previousFile: File? = null
    ): File = withContext(Dispatchers.IO) {
        val safeTitle = projectTitle
            .trim()
            .ifBlank { "Obra" }
            .replace(Regex("[^A-Za-z0-9áéíóúÁÉÍÓÚñÑ -]"), "")
            .replace(' ', '_')
            .take(28)
        val stamp = System.currentTimeMillis()
        val fileName = "${safeTitle}__${width}__${height}__${stamp}.png"
        val outFile = File(projectsDir(context), fileName)
        outFile.writeBytes(pngBytes)
        if (previousFile != null && previousFile.absolutePath != outFile.absolutePath && previousFile.exists()) {
            previousFile.delete()
        }
        outFile
    }

    suspend fun readFileBytes(file: File): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) file.readBytes() else null
        }.getOrNull()
    }

    suspend fun deleteSavedDrawing(file: File): Boolean = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete() else false
    }

    suspend fun exportPngToGallery(
        context: Context,
        projectTitle: String,
        pngBytes: ByteArray
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cleanName = projectTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Trazo" }
            val displayName = "${cleanName}_${timestamp}.png"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + "TrazoStudio"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el archivo en la galería del sistema.")

            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(pngBytes)
                stream.flush()
            } ?: error("No se pudo abrir el flujo de escritura hacia MediaStore.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        }
    }

    suspend fun createShareablePngUri(
        context: Context,
        projectTitle: String,
        pngBytes: ByteArray
    ): Uri = withContext(Dispatchers.IO) {
        val exportCache = File(context.cacheDir, "exports")
        if (!exportCache.exists()) exportCache.mkdirs()
        val cleanName = projectTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Trazo" }
        val file = File(exportCache, "${cleanName}_share.png")
        file.writeBytes(pngBytes)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Decodifica en segundo plano una imagen de la galería del dispositivo reduciendo su tamaño
     * si excede `maxDimension`, evitando picos de memoria en dispositivos de 32 y 64 bits.
     */
    suspend fun decodeTracingBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1600
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val maxSide = max(srcW, srcH)
                    if (maxSide > maxDimension && maxSide > 0) {
                        val ratio = maxDimension.toFloat() / maxSide.toFloat()
                        decoder.setTargetSize(
                            (srcW * ratio).roundToInt().coerceAtLeast(1),
                            (srcH * ratio).roundToInt().coerceAtLeast(1)
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val srcW = bounds.outWidth
                val srcH = bounds.outHeight
                if (srcW <= 0 || srcH <= 0) return@runCatching null

                var sampleSize = 1
                while ((srcW / sampleSize) > maxDimension || (srcH / sampleSize) > maxDimension) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            }
        }.getOrNull()
    }
}
