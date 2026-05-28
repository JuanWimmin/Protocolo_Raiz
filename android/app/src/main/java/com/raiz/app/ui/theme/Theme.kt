package com.raiz.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * MaterialTheme de RAÍZ.
 *
 * Mapeo paleta → Material 3 roles:
 *   - primary       = verde (CTA dominante, indicadores, bottom nav activo)
 *   - onPrimary     = blanco (texto sobre verde)
 *   - secondary     = púrpura (acentos)
 *   - tertiary      = amarillo (acento decorativo, sellos del passport,
 *                    chip de Turista — solo decorativo, no llamada a acción)
 *   - background    = #FAFAF7 (fondo de pantalla)
 *   - surface       = blanco (cards normales)
 *   - onSurface     = negro
 *
 * Para cards "negras" como la del Balance USDC, usar `RaizBlack` directamente
 * en lugar de un role del colorScheme — no choca con Material y es explícito.
 *
 * Solo light scheme. El diseño aprobado no contempla dark mode para el MVP.
 *
 * NOTA: el setup de edge-to-edge (status bar translúcida) se hace en
 * MainActivity.onCreate() vía `enableEdgeToEdge()` ANTES de `setContent`,
 * que es el patrón canónico de Activity en Compose.
 */
private val RaizLightColorScheme = lightColorScheme(
    primary = RaizGreen,
    onPrimary = RaizWhite,
    secondary = RaizPurple,
    onSecondary = RaizWhite,
    tertiary = RaizYellow,
    onTertiary = RaizBlack,
    background = RaizBackground,
    onBackground = RaizBlack,
    surface = RaizWhite,
    onSurface = RaizBlack,
    error = RaizError,
    onError = RaizWhite,
    outline = RaizGrayLight,
)

@Composable
fun RaizTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RaizLightColorScheme,
        typography = RaizTypography,
        content = content,
    )
}
