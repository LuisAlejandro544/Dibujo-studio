package com.trazostudio.draw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore by preferencesDataStore(name = "trazo_studio_prefs")

/** Temas visuales disponibles en el estudio. */
enum class StudioThemeMode(val id: String, val label: String, val subtitle: String) {
    DARK_ATELIER("dark_atelier", "Estudio Grafito", "Superficies oscuras con acento ámbar cálido"),
    MIDNIGHT_SLATE("midnight_slate", "Pizarra Nocturna", "Azul profundo con contraste azul cobalto"),
    WARM_PAPER("warm_paper", "Taller Luminoso", "Fondo crema de papel artístico con acento terracota");

    companion object {
        fun fromId(id: String?): StudioThemeMode =
            entries.firstOrNull { it.id == id } ?: DARK_ATELIER
    }
}

/** Preferencias persistentes de la aplicación almacenadas con Jetpack DataStore. */
data class StudioPreferences(
    val themeMode: StudioThemeMode = StudioThemeMode.DARK_ATELIER,
    val defaultWidth: Int = 1080,
    val defaultHeight: Int = 1080,
    val twoFingerPanZoom: Boolean = true,
    val showGridCheckers: Boolean = true
)

/**
 * Repositorio reactivo sobre DataStore para leer y guardar ajustes del usuario sin bloquear el hilo principal.
 */
class StudioPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_WIDTH = intPreferencesKey("default_width")
        val DEFAULT_HEIGHT = intPreferencesKey("default_height")
        val TWO_FINGER_PAN_ZOOM = booleanPreferencesKey("two_finger_pan_zoom")
        val SHOW_GRID_CHECKERS = booleanPreferencesKey("show_grid_checkers")
    }

    val preferencesFlow: Flow<StudioPreferences> = context.studioDataStore.data.map { prefs ->
        StudioPreferences(
            themeMode = StudioThemeMode.fromId(prefs[Keys.THEME_MODE]),
            defaultWidth = prefs[Keys.DEFAULT_WIDTH] ?: 1080,
            defaultHeight = prefs[Keys.DEFAULT_HEIGHT] ?: 1080,
            twoFingerPanZoom = prefs[Keys.TWO_FINGER_PAN_ZOOM] ?: true,
            showGridCheckers = prefs[Keys.SHOW_GRID_CHECKERS] ?: true
        )
    }

    suspend fun setThemeMode(mode: StudioThemeMode) {
        context.studioDataStore.edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setDefaultCanvasSize(width: Int, height: Int) {
        context.studioDataStore.edit {
            it[Keys.DEFAULT_WIDTH] = width.coerceIn(256, 2560)
            it[Keys.DEFAULT_HEIGHT] = height.coerceIn(256, 2560)
        }
    }

    suspend fun setTwoFingerPanZoom(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.TWO_FINGER_PAN_ZOOM] = enabled }
    }

    suspend fun setShowGridCheckers(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.SHOW_GRID_CHECKERS] = enabled }
    }
}
