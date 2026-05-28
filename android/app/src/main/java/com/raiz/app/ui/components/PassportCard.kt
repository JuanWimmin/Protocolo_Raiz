package com.raiz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.data.model.PassportData
import com.raiz.app.data.model.PassportLevel
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * RAÍZ Passport — card grande con la "identidad turística" del usuario.
 *
 * Estructura (de arriba a abajo):
 *   1. Header verde: "RAÍZ Passport" + nombre + rol/ubicación + chip
 *      "Nivel X" + saldo en pts + "X pts para [siguiente nivel]".
 *   2. Banda de stats con fondo verde y 3 tiles amarillo-claro
 *      (aportado / tx locales / barrios visitados).
 *   3. "Sellos coleccionados": fila de círculos. Cada barrio del catálogo
 *      conocido aparece verde (con su icono) si lo visitaste; gris con
 *      candado si no.
 *
 * No incluye scroll propio — debe ir dentro de un Column o LazyColumn padre.
 */
@Composable
fun PassportCard(
    data: PassportData,
    sellos: List<SelloBarrio>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RaizGreen),
    ) {
        // Header
        PassportHeader(data = data)
        // Stats con fondo amarillo claro casi transparente sobre el verde
        StatsRow(data = data)
        // Sellos
        SellosBlock(sellos = sellos, barriosVisitados = data.barriosVisitados)
    }
}

@Composable
private fun PassportHeader(data: PassportData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RAÍZ Passport",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizWhite.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = data.nombre,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
                color = RaizWhite,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Viajer@ responsable · ${data.ubicacion}",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizWhite.copy(alpha = 0.85f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            NivelChip(level = data.nivel)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "%,d pts".format(data.saldoStroops / 10_000_000L),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                ),
                color = RaizWhite,
            )
            if (data.nivel.next() != null) {
                Text(
                    text = "%,d pts para %s".format(
                        data.ptsParaSiguienteNivel / 10_000_000L,
                        data.nivel.next()?.label.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = RaizWhite.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun NivelChip(level: PassportLevel) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, RaizWhite.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Nivel ${level.label}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = RaizWhite,
        )
    }
}

@Composable
private fun StatsRow(data: PassportData) {
    val aportadoUsdc = data.aportadoAlBarrioStroops / 10_000_000.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(
            valueText = "$%.2f".format(aportadoUsdc),
            label = "aportados al barrio",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            valueText = data.transaccionesLocales.toString(),
            label = "transacciones locales",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            valueText = data.barriosVisitados.size.toString(),
            label = "barrios visitados",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    valueText: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            // Amarillo claro casi transparente sobre el verde — como pediste.
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            color = RaizWhite,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = RaizWhite.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun SellosBlock(
    sellos: List<SelloBarrio>,
    barriosVisitados: Set<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Sellos coleccionados",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizWhite.copy(alpha = 0.85f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sellos.forEach { sello ->
                Sello(
                    sello = sello,
                    // Solo visitado si: tiene barrioId asignado Y ese id está
                    // en el set de barrios donde el usuario sí pagó.
                    visitado = sello.barrioId != null && sello.barrioId in barriosVisitados,
                )
            }
        }
    }
}

@Composable
private fun Sello(sello: SelloBarrio, visitado: Boolean) {
    val outline = if (visitado) RaizWhite else RaizGrayLight.copy(alpha = 0.4f)
    val fill = if (visitado) RaizGreen.copy(alpha = 0.0f) else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(66.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (visitado) sello.icon else Icons.Outlined.Lock,
                contentDescription = sello.label,
                tint = if (visitado) RaizWhite else RaizGrayLight.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = sello.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = if (visitado) RaizWhite else RaizWhite.copy(alpha = 0.5f),
        )
    }
}

/** Catálogo visual de barrios. `barrioId == null` → slot placeholder bloqueado. */
data class SelloBarrio(
    val label: String,
    val icon: ImageVector,
    val barrioId: String?,
)

object SellosCatalog {
    /**
     * Los 3 barrios reales del seed + 2 placeholders para mostrar el sistema
     * en aspiracional (otros barrios que adoptarán RAÍZ).
     */
    val DEFAULT: List<SelloBarrio> = listOf(
        SelloBarrio(
            "Centro",
            Icons.Outlined.Apartment,
            "ce47120000000000000000000000000000000000000000000000000000000001",
        ),
        SelloBarrio(
            "Norte",
            Icons.Outlined.Park,
            "bba17e0000000000000000000000000000000000000000000000000000000002",
        ),
        SelloBarrio(
            "Costa",
            Icons.Outlined.BeachAccess,
            "c057a9000000000000000000000000000000000000000000000000000000000a",
        ),
        SelloBarrio("Getsemaní", Icons.Outlined.Star, barrioId = null),
        SelloBarrio("+más", Icons.Outlined.Lock, barrioId = null),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAFAF7)
@Composable
private fun PassportPreview() {
    RaizTheme {
        PassportCard(
            data = PassportData(
                nombre = "Viajer@ responsable",
                ubicacion = "Colombia 2026",
                nivel = PassportLevel.SEMILLA,
                saldoStroops = 12_400_000_000L,        // 1240 USDC
                ptsParaSiguienteNivel = 37_600_000_000L,
                aportadoAlBarrioStroops = 4_800_000L,
                transaccionesLocales = 12,
                barriosVisitados = setOf(
                    "ce47120000000000000000000000000000000000000000000000000000000001",
                    "bba17e0000000000000000000000000000000000000000000000000000000002",
                ),
            ),
            sellos = SellosCatalog.DEFAULT,
            modifier = Modifier.padding(16.dp),
        )
    }
}
