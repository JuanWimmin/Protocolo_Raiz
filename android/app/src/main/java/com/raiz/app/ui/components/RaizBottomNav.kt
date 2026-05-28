package com.raiz.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite

enum class RaizDestination(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Outlined.Home),
    Map("Mapa", Icons.Outlined.Map),
    Rewards("Premios", Icons.Outlined.CardGiftcard),
    Profile("Perfil", Icons.Outlined.AccountCircle),
}

/**
 * Bottom nav fija de 4 destinos. El destino seleccionado se resalta con
 * un fondo amarillo (RaizYellow) sobre el indicador de Material 3.
 *
 * Por ahora `selected` es state-only — sin NavController. Cuando montemos
 * NavHost se reemplaza por integración real con la backstack.
 */
@Composable
fun RaizBottomNav(
    selected: RaizDestination,
    onSelect: (RaizDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = RaizWhite,
    ) {
        RaizDestination.entries.forEach { dest ->
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
