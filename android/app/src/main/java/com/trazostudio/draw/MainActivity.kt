package com.trazostudio.draw

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.data.StudioPreferencesRepository
import com.trazostudio.draw.ui.navigation.TrazoNavHost
import com.trazostudio.draw.ui.theme.TrazoStudioTheme

/**
 * Actividad principal de TrazoStudio.
 * Inicializa el repositorio DataStore, activa modo inmersivo sin barra de estado ni botones de
 * navegación del sistema, observa preferencias reactivamente y monta la navegación en Compose.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyImmersiveFullscreen()

        setContent {
            val prefsRepo = remember { StudioPreferencesRepository(applicationContext) }
            val preferences by prefsRepo.preferencesFlow.collectAsState(initial = StudioPreferences())

            TrazoStudioTheme(themeMode = preferences.themeMode) {
                TrazoNavHost(
                    preferencesRepo = prefsRepo,
                    preferences = preferences
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    /**
     * Oculta la barra de notificaciones (status bar) y la barra de navegación del sistema
     * usando modo inmersivo temporal al deslizar desde los bordes.
     */
    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
