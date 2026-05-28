package com.raiz.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta RAÍZ.
 *
 * - Negro principal: cards primarias (Balance USDC, QR scanner).
 * - Verde: PRIMARY del color scheme — CTA principal (botón "Escanear y pagar",
 *   "Confirmar pago"), Tip Barrio, indicadores de tab/nav, estados de éxito,
 *   header del RAÍZ Passport.
 * - Amarillo: acento decorativo (sellos del passport, chip de Turista, fondos
 *   sutiles con alpha bajo). Antes era CTA; hoy queda como sub-acento.
 * - Púrpura: acentos secundarios, badges (puntos, residente).
 * - Fondo: background general de pantallas.
 */
val RaizBlack = Color(0xFF1A1A1A)
val RaizYellow = Color(0xFFFBBF24)
val RaizPurple = Color(0xFF534AB7)
val RaizGreen = Color(0xFF0F6E56)
val RaizBackground = Color(0xFFFAFAF7)

// Variaciones útiles para estados / contornos.
val RaizBlack80 = Color(0xCC1A1A1A)
val RaizGray = Color(0xFF707070)
val RaizGrayLight = Color(0xFFEAEAE6)
val RaizError = Color(0xFFB00020)
val RaizWhite = Color(0xFFFFFFFF)
