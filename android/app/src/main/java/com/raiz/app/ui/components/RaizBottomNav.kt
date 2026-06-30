package com.raiz.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.raiz.app.data.model.UserRole
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite

/**
 * Todos los destinos posibles del bottom nav — unión de los 3 roles.
 *
 *   Turista    → Home · Rewards  · Map · Profile
 *   Comerciante→ Home · Cobros   · Map · Profile
 *   Residente  → Home · Proposals· Map · Profile
 */
enum class RaizDestination(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Outlined.Home),
    Map("Mapa", Icons.Outlined.Map),
    Rewards("Premios", Icons.Outlined.CardGiftcard),
    Profile("Perfil", Icons.Outlined.AccountCircle),
    // Slot variable para rol Residente
    Proposals("Propuestas", Icons.Outlined.HowToVote),
    // Slot variable para rol Comerciante
    Cobros("Cobros", Icons.Outlined.Payments),
}

/**
 * Devuelve los 4 tabs correspondientes al rol del usuario.
 *
 * El slot en posición 2 (entre Inicio y Mapa) varía según el rol:
 *   TOURIST   → Premios
 *   MERCHANT  → Cobros
 *   RESIDENT  → Propuestas
 */
fun raizTabsForRole(role: UserRole): List<RaizDestination> = when (role) {
    UserRole.TOURIST  -> listOf(RaizDestination.Home, RaizDestination.Rewards,   RaizDestination.Map, RaizDestination.Profile)
    UserRole.MERCHANT -> listOf(RaizDestination.Home, RaizDestination.Cobros,    RaizDestination.Map, RaizDestination.Profile)
    UserRole.RESIDENT -> listOf(RaizDestination.Home, RaizDestination.Proposals, RaizDestination.Map, RaizDestination.Profile)
}

/**
 * Bottom nav de 4 destinos adaptada al rol del usuario.
 *
 * Pasa [role] para que el slot central muestre el tab correcto. El destino
 * seleccionado se resalta con el indicador verde (RaizGreen).
 *
 * Las pantallas que no forman parte de los tabs del rol activo simplemente
 * no aparecen en la barra — la navegación hacia ellas se hace a través de
 * botones internos (ej. "Pagar aquí" en el mapa).
 */
@Composable
fun RaizBottomNav(
    selected: RaizDestination,
    onSelect: (RaizDestination) -> Unit,
    role: UserRole = UserRole.TOURIST,
    modifier: Modifier = Modifier,
) {
    val tabs = raizTabsForRole(role)
    NavigationBar(
        modifier = modifier,
        containerColor = RaizWhite,
    ) {
        tabs.forEach { dest ->
            NavigationBarItem(
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RaizWhite,
                    selectedTextColor = RaizBlack,
                    indicatorColor = RaizGreen,
                    unselectedIconColor = RaizBlack.copy(alpha = 0.5f),
                    unselectedTextColor = RaizBlack.copy(alpha = 0.5f),
                ),
            )
        }
    }
}
