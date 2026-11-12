package com.trazostudio.draw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.trazostudio.draw.data.StudioThemeMode

// Paleta 1: Estudio Grafito (Oscuro cálido para resaltar los colores del lienzo sin fatiga visual)
private val DarkAtelierColors = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF1F1600),
    primaryContainer = Color(0xFF452E05),
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF002436),
    secondaryContainer = Color(0xFF0E3950),
    onSecondaryContainer = Color(0xFFBBE8FF),
    tertiary = Color(0xFFFB7185),
    background = Color(0xFF121318),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF1B1D24),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF262934),
    onSurfaceVariant = Color(0xFFC2C7D6),
    outline = Color(0xFF3E4456)
)

// Paleta 2: Pizarra Nocturna (Azul pizarra con acento cobalto)
private val MidnightSlateColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF061E3E),
    primaryContainer = Color(0xFF1B3A63),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF002B1C),
    secondaryContainer = Color(0xFF0C4631),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFF472B6),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF131C31),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155)
)

// Paleta 3: Taller Luminoso (Papel crema artesanal con acento terracota)
private val WarmPaperColors = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3D1100),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF1C1917),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFECE6DC),
    onSurfaceVariant = Color(0xFF57534E),
    outline = Color(0xFFD6CFC2)
)

/**
 * Tema raíz de TrazoStudio que aplica la paleta seleccionada por el usuario.
 */
@Composable
fun TrazoStudioTheme(
    themeMode: StudioThemeMode,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        StudioThemeMode.DARK_ATELIER -> DarkAtelierColors
        StudioThemeMode.MIDNIGHT_SLATE -> MidnightSlateColors
        StudioThemeMode.WARM_PAPER -> WarmPaperColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
