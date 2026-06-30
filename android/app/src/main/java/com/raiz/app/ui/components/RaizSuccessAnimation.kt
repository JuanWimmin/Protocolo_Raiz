package com.raiz.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animación nativa Compose de éxito para pantallas RAÍZ.
 *
 * Reutilizable en: pago confirmado, voto registrado, depósito exitoso,
 * propuesta creada — cualquier momento de celebración en la app.
 *
 * Secuencia de animación (~900 ms en total):
 *  1. Círculo verde cuyo trazo se dibuja con Animatable 0→1 (500 ms tween).
 *  2. Checkmark (✓) que escala con spring medium-bouncy al terminar el círculo.
 *  3. Título + subtítulo entran con fadeIn + slideInVertically (300 ms, 200 ms después).
 *  4. [Opcional] Cápsula "+X USDC al barrio" con scaleIn bouncy + fade (350 ms después del texto).
 *     El borde de la cápsula pulsa verde 3 veces para reforzar la tesis de RAÍZ:
 *     "Tu pago hace crecer el barrio".
 *
 * @param titulo         Línea principal. Ej: "¡Pago exitoso!"
 * @param subtitulo      Segunda línea opcional. Ej: "5.00 USDC a Café Don Aurelio"
 * @param tipBarrioUsdc  Monto del tip ya formateado (usar [Long.formatUsdc]).
 *                       Ej: "0.10 USDC". Null si no hay tip activo.
 * @param modifier       Modificador externo para posicionar el composable.
 */
@Composable
fun RaizSuccessAnimation(
    titulo: String,
    subtitulo: String? = null,
    tipBarrioUsdc: String? = null,
    modifier: Modifier = Modifier,
) {
    // Progreso del trazo del círculo: 0.0 (sin dibujar) → 1.0 (vuelta completa)
    val circuloProgress = remember { Animatable(0f) }

    // Escala del checkmark: 0.0 (invisible) → 1.0 (tamaño natural)
    val checkScale = remember { Animatable(0f) }

    // Visibilidad del bloque de texto (título + subtítulo)
    var showTexto by remember { mutableStateOf(false) }

    // Visibilidad de la cápsula de aporte al barrio
    var showBarrio by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Fase 1: traza el círculo verde en 500 ms
        circuloProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500),
        )
        // Fase 2: checkmark aparece con spring (el círculo ya completó su vuelta)
        launch {
            checkScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        // Fase 3: texto principal 200 ms después del check
        delay(200L)
        showTexto = true
        // Fase 4: cápsula barrio 350 ms después del texto
        delay(350L)
        showBarrio = true
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        // Ícono central: círculo verde animado + checkmark escalado desde el centro
        Canvas(modifier = Modifier.size(100.dp)) {
            val trazo = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val margen = trazo.width / 2f
            val arcOffset = Offset(margen, margen)
            val arcSize = Size(size.width - margen * 2f, size.height - margen * 2f)

            // Pista del círculo (verde muy suave — referencia visual mientras anima)
            drawArc(
                color = RaizGreen.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = trazo,
            )

            // Arco verde que avanza de 0° → 360° a medida que circuloProgress sube
            drawArc(
                color = RaizGreen,
                startAngle = -90f,
                sweepAngle = 360f * circuloProgress.value,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = trazo,
            )

            // Checkmark (✓): dos trazos dibujados con el ancho escalado por checkScale
            val ck = checkScale.value
            if (ck > 0f) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Brazo proporcional al scale actual — escala desde el centro
                val r = size.width * 0.22f * ck
                val grosor = 5.dp.toPx()

                // Trazo izquierdo del ✓ (baja hacia la derecha)
                drawLine(
                    color = RaizGreen,
                    start = Offset(cx - r * 1.1f, cy - r * 0.05f),
                    end = Offset(cx - r * 0.05f, cy + r * 0.85f),
                    strokeWidth = grosor,
                    cap = StrokeCap.Round,
                )
                // Trazo derecho del ✓ (sube hacia la derecha)
                drawLine(
                    color = RaizGreen,
                    start = Offset(cx - r * 0.05f, cy + r * 0.85f),
                    end = Offset(cx + r * 1.35f, cy - r * 0.65f),
                    strokeWidth = grosor,
                    cap = StrokeCap.Round,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Bloque de texto: título + subtítulo con fadeIn + slideUp
        AnimatedVisibility(
            visible = showTexto,
            enter = fadeIn(animationSpec = tween(durationMillis = 300)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 300),
                    initialOffsetY = { it / 3 },
                ),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = titulo,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = RaizBlack,
                )
                if (subtitulo != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = subtitulo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack.copy(alpha = 0.65f),
                    )
                }
            }
        }

        // Cápsula de aporte al barrio — solo si hay tip activo en este pago
        if (tipBarrioUsdc != null) {
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(
                visible = showBarrio,
                enter = fadeIn(animationSpec = tween(durationMillis = 400)) +
                    scaleIn(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                        initialScale = 0.7f,
                    ) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 400),
                        initialOffsetY = { it / 2 },
                    ),
            ) {
                BarrioContribucionCard(tipBarrioUsdc = tipBarrioUsdc)
            }
        }
    }
}

/**
 * Cápsula verde que comunica la contribución al barrio.
 *
 * El borde pulsa su alpha 3 veces (1.0 → 0.3 → 1.0) para enfatizar el momento
 * "tu pago hace crecer el barrio" — la tesis central de RAÍZ.
 */
@Composable
private fun BarrioContribucionCard(tipBarrioUsdc: String) {
    // 3 pulsaciones del borde: ~760 ms cada latido
    val pulso = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            pulso.animateTo(0.3f, animationSpec = tween(durationMillis = 380))
            pulso.animateTo(1f, animationSpec = tween(durationMillis = 380))
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(RaizGreen.copy(alpha = 0.10f))
            .border(
                width = 1.5.dp,
                color = RaizGreen.copy(alpha = pulso.value * 0.8f),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Eco,
            contentDescription = "Aporte al barrio",
            tint = RaizGreen,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = "+$tipBarrioUsdc al barrio",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = RaizGreen,
            )
            Text(
                text = "Tu pago hace crecer el barrio",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen.copy(alpha = 0.75f),
            )
        }
    }
}
