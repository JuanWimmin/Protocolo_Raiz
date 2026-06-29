package com.raiz.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Selección de intención al registrarse (tras crear/importar wallet).
 *
 * Importante: el ROL REAL se verifica/gana on-chain — esta pantalla solo
 * enruta al flujo correcto. "Turista" entra directo; "Comerciante" abre el
 * alta de negocio; "Residente" entra (la residencia la mintea el admin del
 * barrio, no es auto-asignable).
 */
@Composable
fun ChooseRoleScreen(
    onTourist: () -> Unit,
    onMerchant: () -> Unit,
    onResident: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "¿Cómo usarás RAÍZ?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
            Text(
                text = "Elige tu camino. Tu rol real se verifica on-chain y puedes cambiarlo después desde tu perfil.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.65f),
            )

            RoleCard(
                icon = Icons.Outlined.Person,
                accent = RaizYellow,
                title = "Soy turista",
                desc = "Paga en comercios locales, aporta al barrio con el Tip y gana puntos canjeables por artesanías.",
                cta = "Entrar como turista",
                onClick = onTourist,
            )
            RoleCard(
                icon = Icons.Outlined.Storefront,
                accent = RaizGreen,
                title = "Tengo un negocio",
                desc = "Regístralo para recibir pagos en USDC, aparecer en el mapa del barrio y sumar al fondo comunitario.",
                cta = "Registrar mi negocio",
                onClick = onMerchant,
            )
            RoleCard(
                icon = Icons.Outlined.HowToVote,
                accent = RaizPurple,
                title = "Soy residente",
                desc = "Propón y vota cómo se usa el fondo del barrio. Requiere que el admin de tu barrio te registre (token de residencia).",
                cta = "Entrar y pedir acceso",
                onClick = onResident,
            )
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    desc: String,
    cta: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizWhite)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RaizBlack,
            )
        }
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Text(
            text = "$cta  →",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
