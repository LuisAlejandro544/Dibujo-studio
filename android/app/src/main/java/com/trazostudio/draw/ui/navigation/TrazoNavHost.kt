package com.trazostudio.draw.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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

/** Curva suave de desaceleración tipo Material para evitar cortes bruscos al cambiar de apartado. */
private val StudioEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val ENTER_DURATION_MS = 320
private const val EXIT_DURATION_MS = 260

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

    fun navigateToRootTab(route: String) {
        navController.navigate(route) {
            popUpTo(TrazoRoutes.GALLERY) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val slideSpec = tween<IntOffset>(durationMillis = ENTER_DURATION_MS, easing = StudioEaseOut)
    val exitSlideSpec = tween<IntOffset>(durationMillis = EXIT_DURATION_MS, easing = StudioEaseOut)
    val fadeInSpec = tween<Float>(durationMillis = ENTER_DURATION_MS, easing = StudioEaseOut)
    val fadeOutSpec = tween<Float>(durationMillis = EXIT_DURATION_MS, easing = StudioEaseOut)
    val scaleInSpec = tween<Float>(durationMillis = ENTER_DURATION_MS, easing = StudioEaseOut)
    val scaleOutSpec = tween<Float>(durationMillis = EXIT_DURATION_MS, easing = StudioEaseOut)

    NavHost(
        navController = navController,
        startDestination = TrazoRoutes.GALLERY,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = slideSpec,
                initialOffset = { it / 4 }
            ) + scaleIn(initialScale = 0.96f, animationSpec = scaleInSpec) +
                fadeIn(animationSpec = fadeInSpec)
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = exitSlideSpec,
                targetOffset = { it / 6 }
            ) + scaleOut(targetScale = 0.96f, animationSpec = scaleOutSpec) +
                fadeOut(animationSpec = fadeOutSpec)
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = slideSpec,
                initialOffset = { it / 6 }
            ) + scaleIn(initialScale = 0.96f, animationSpec = scaleInSpec) +
                fadeIn(animationSpec = fadeInSpec)
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = exitSlideSpec,
                targetOffset = { it / 4 }
            ) + scaleOut(targetScale = 0.96f, animationSpec = scaleOutSpec) +
                fadeOut(animationSpec = fadeOutSpec)
        }
    ) {
        composable(
            route = TrazoRoutes.GALLERY,
            enterTransition = {
                val fromSettings = initialState.destination.route == TrazoRoutes.SETTINGS
                if (fromSettings) {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = slideSpec,
                        initialOffset = { it / 5 }
                    ) + fadeIn(animationSpec = fadeInSpec)
                } else {
                    fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.97f, animationSpec = scaleInSpec)
                }
            },
            exitTransition = {
                val toSettings = targetState.destination.route == TrazoRoutes.SETTINGS
                if (toSettings) {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = exitSlideSpec,
                        targetOffset = { it / 5 }
                    ) + fadeOut(animationSpec = fadeOutSpec)
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = exitSlideSpec,
                        targetOffset = { it / 6 }
                    ) + scaleOut(targetScale = 0.96f, animationSpec = scaleOutSpec) +
                        fadeOut(animationSpec = fadeOutSpec)
                }
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = slideSpec,
                    initialOffset = { it / 6 }
                ) + scaleIn(initialScale = 0.96f, animationSpec = scaleInSpec) +
                    fadeIn(animationSpec = fadeInSpec)
            }
        ) {
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
                    navigateToRootTab(TrazoRoutes.SETTINGS)
                },
                bottomBar = {
                    StudioRootBottomBar(
                        selectedRoute = TrazoRoutes.GALLERY,
                        onSelectGallery = { navigateToRootTab(TrazoRoutes.GALLERY) },
                        onSelectSettings = { navigateToRootTab(TrazoRoutes.SETTINGS) }
                    )
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
            LaunchedEffect(title, width, height) {
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

        composable(
            route = TrazoRoutes.SETTINGS,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = slideSpec,
                    initialOffset = { it / 5 }
                ) + fadeIn(animationSpec = fadeInSpec)
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = exitSlideSpec,
                    targetOffset = { it / 5 }
                ) + fadeOut(animationSpec = fadeOutSpec)
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = exitSlideSpec,
                    targetOffset = { it / 5 }
                ) + fadeOut(animationSpec = fadeOutSpec)
            }
        ) {
            SettingsScreen(
                preferences = preferences,
                preferencesRepo = preferencesRepo,
                onNavigateBack = {
                    navigateToRootTab(TrazoRoutes.GALLERY)
                },
                bottomBar = {
                    StudioRootBottomBar(
                        selectedRoute = TrazoRoutes.SETTINGS,
                        onSelectGallery = { navigateToRootTab(TrazoRoutes.GALLERY) },
                        onSelectSettings = { navigateToRootTab(TrazoRoutes.SETTINGS) }
                    )
                }
            )
        }
    }
}

/**
 * Barra inferior compartida entre las pantallas raíz (Galería y Ajustes) para navegar
 * cómodamente con el pulgar sin acumular copias repetidas en el historial.
 */
@Composable
private fun StudioRootBottomBar(
    selectedRoute: String,
    onSelectGallery: () -> Unit,
    onSelectSettings: () -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        NavigationBarItem(
            selected = selectedRoute == TrazoRoutes.GALLERY,
            onClick = onSelectGallery,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Collections,
                    contentDescription = "Galería"
                )
            },
            label = { Text("Galería") }
        )
        NavigationBarItem(
            selected = selectedRoute == TrazoRoutes.SETTINGS,
            onClick = onSelectSettings,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Ajustes"
                )
            },
            label = { Text("Ajustes") }
        )
    }
}
