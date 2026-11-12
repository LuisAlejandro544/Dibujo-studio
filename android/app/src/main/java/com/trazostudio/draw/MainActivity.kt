package com.trazostudio.draw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.trazostudio.draw.data.StudioPreferences
import com.trazostudio.draw.data.StudioPreferencesRepository
import com.trazostudio.draw.ui.navigation.TrazoNavHost
import com.trazostudio.draw.ui.theme.TrazoStudioTheme

/**
 * Actividad principal de TrazoStudio.
 * Inicializa el repositorio DataStore, observa las preferencias reactivamente y monta
 * la navegación multipantalla sobre Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
