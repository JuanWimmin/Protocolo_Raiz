package com.raiz.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * MaterialTheme de RAÍZ.
 *
 * Mapeo paleta → Material 3 roles:
 *   - primary       = amarillo (CTA dominante)
 *   - onPrimary     = negro (texto sobre amarillo)
 *   - secondary     = púrpura (acentos)
 *   - tertiary      = verde (Tip Barrio, éxito)
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
    primary = RaizYellow,
    onPrimary = RaizBlack,
    secondary = RaizPurple,
    onSecondary = RaizWhite,
    tertiary = RaizGreen,
    onTertiary = RaizWhite,
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
