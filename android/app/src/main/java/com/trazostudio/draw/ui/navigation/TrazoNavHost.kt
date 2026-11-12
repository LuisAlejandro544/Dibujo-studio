package com.trazostudio.draw.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.data.StudioPreferencesRepository
import com.trazostudio.draw.ui.canvas.CanvasScreen
import com.trazostudio.draw.ui.canvas.CanvasViewModel
import com.trazostudio.draw.ui.export.ExportScreen
import com.trazostudio.draw.ui.gallery.GalleryScreen
import com.trazostudio.draw.ui.settings.SettingsScreen

/**
 * Rutas de navegación entre las pantallas dedicadas de TrazoStudio:
 * 1. `gallery` -> Galería de proyectos, plantillas rápidas y acceso a ajustes.
 * 2. `canvas/{title}/{width}/{height}` -> Estudio completo de dibujo respaldado por Rust.
 * 3. `export` -> Pantalla dedicada de vista previa final, guardado en galería y compartido.
 * 4. `settings` -> Ajustes visuales, tamaño predeterminado y diagnóstico del motor Rust.
 */
object TrazoRoutes {
    const val GALLERY = "gallery"
    const val CANVAS = "canvas/{title}/{width}/{height}"
    const val EXPORT = "export"
    const val SETTINGS = "settings"

    fun canvasRoute(title: String, width: Int, height: Int): String {
        val encoded = Uri.encode(title.ifBlank { "Mi Boceto" })
        return "canvas/$encoded/$width/$height"
    }
}

@Composable
fun TrazoNavHost(
    preferencesRepo: StudioPreferencesRepository,
    preferences: StudioPreferences
) {
    val navController = rememberNavController()
    // ViewModel compartido entre CanvasScreen y ExportScreen para transferir la sesión Rust activa sin perder el lienzo.
    val canvasViewModel: CanvasViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = TrazoRoutes.GALLERY
    ) {
        composable(TrazoRoutes.GALLERY) {
            GalleryScreen(
                preferences = preferences,
                onOpenNewCanvas = { title, width, height ->
                    canvasViewModel.startNewSession(title, width, height)
                    navController.navigate(TrazoRoutes.canvasRoute(title, width, height))
                },
                onOpenSavedArtwork = { item ->
                    canvasViewModel.openSavedDrawing(item)
                    navController.navigate(TrazoRoutes.canvasRoute(item.title, item.width, item.height))
                },
                onOpenSettings = {
                    navController.navigate(TrazoRoutes.SETTINGS)
                }
            )
        }

        composable(
            route = TrazoRoutes.CANVAS,
            arguments = listOf(
                navArgument("title") { type = NavType.StringType },
                navArgument("width") { type = NavType.IntType },
                navArgument("height") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val title = backStackEntry.arguments?.getString("title") ?: "Boceto"
            val width = backStackEntry.arguments?.getInt("width") ?: preferences.defaultWidth
            val height = backStackEntry.arguments?.getInt("height") ?: preferences.defaultHeight

            val uiState by canvasViewModel.uiState.collectAsState()
            androidx.compose.runtime.LaunchedEffect(title, width, height) {
                if (
                    !uiState.isBusy &&
                    uiState.bitmap == null &&
                    (uiState.projectTitle != title || uiState.canvasWidth != width || uiState.canvasHeight != height)
                ) {
                    canvasViewModel.startNewSession(title, width, height)
                }
            }

            CanvasScreen(
                viewModel = canvasViewModel,
                preferences = preferences,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToExport = {
                    navController.navigate(TrazoRoutes.EXPORT)
                }
            )
        }

        composable(TrazoRoutes.EXPORT) {
            ExportScreen(
                viewModel = canvasViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TrazoRoutes.SETTINGS) {
            SettingsScreen(
                preferences = preferences,
                preferencesRepo = preferencesRepo,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
