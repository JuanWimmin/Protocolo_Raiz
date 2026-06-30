package com.raiz.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.security.AppLock
import com.raiz.app.data.stellar.PasskeyWalletManager
import com.raiz.app.data.stellar.WalletManager
import com.raiz.app.ui.become_merchant.BecomeMerchantScreen
import com.raiz.app.ui.cobros.CobrosScreen
import com.raiz.app.ui.dashboard.DashboardScreen
import com.raiz.app.ui.governance.CreateProposalScreen
import com.raiz.app.ui.governance.ProposalsScreen
import com.raiz.app.ui.map.BarrioMapScreen
import com.raiz.app.ui.pay.PayScreen
import com.raiz.app.ui.profile.ProfileScreen
import com.raiz.app.ui.rewards.RewardsScreen
import com.raiz.app.ui.security.LockScreen
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.treasury.YieldScreen
import com.raiz.app.ui.wallet.WalletScreen
import com.raiz.app.ui.welcome.ChooseRoleScreen
import com.raiz.app.ui.welcome.CreatePasskeyWalletScreen
import com.raiz.app.ui.welcome.CreateWalletScreen
import com.raiz.app.ui.welcome.ImportWalletScreen
import com.raiz.app.ui.welcome.WelcomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Única Activity de RAÍZ.
 *
 * Responsabilidades:
 *   - Inicializar el estado de bloqueo biométrico (AppLock).
 *   - Resolver el rol preferido persistido en WalletManager para arrancar
 *     con la nav correcta en reaperturas.
 *   - Pasar lambdas de WalletManager a RaizApp (setPreferredRole, isDemoMode)
 *     sin exponer el objeto completo al composable.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var passkeyManager: PasskeyWalletManager
    @Inject lateinit var appLock: AppLock

    /** true = la app está bloqueada y requiere desbloqueo ahora. */
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = appLock.isActive() && walletManager.hasUsableWallet()
        setContent {
            RaizTheme {
                if (locked) {
                    LockScreen(onUnlock = ::requestUnlock)
                    LaunchedEffect(Unit) { requestUnlock() }
                } else {
                    RaizApp(
                        initiallyHasWallet = walletManager.hasUsableWallet(),
                        // Rol persistido en reaperturas (null → default TOURIST).
                        initialRole = if (walletManager.hasUsableWallet()) {
                            walletManager.preferredRole() ?: UserRole.TOURIST
                        } else {
                            UserRole.TOURIST
                        },
                        isDemoMode = walletManager.isDemoMode,
                        onSetPreferredRole = { role -> walletManager.setPreferredRole(role) },
                        onLogout = { walletManager.logout() },
                        passkeyEnabled = passkeyManager.isAvailable,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-bloquea al ir a segundo plano si el bloqueo aplica.
        if (appLock.isActive() && walletManager.hasUsableWallet()) {
            locked = true
        }
    }

    private fun requestUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
                // En error/cancelación queda bloqueado; el botón de LockScreen reintenta.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear RAÍZ")
            .setSubtitle("Confirma con huella, rostro o el PIN de tu dispositivo")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

/**
 * Composable raíz de la app.
 *
 * Mantiene el [currentRole] como MutableState para que todos los screens del
 * NavHost compartan la misma referencia — cambios (demo switch, registro como
 * comerciante) se propagan automáticamente a toda la nav inferior.
 *
 * @param initialRole Rol persistido leído en MainActivity al arrancar.
 * @param isDemoMode  Modo demo (no hay wallet real; el juez explora 3 roles).
 * @param onSetPreferredRole Lambda que persiste el rol elegido en WalletManager.
 */
@Composable
private fun RaizApp(
    initiallyHasWallet: Boolean,
    initialRole: UserRole = UserRole.TOURIST,
    isDemoMode: Boolean = false,
    onSetPreferredRole: (UserRole) -> Unit = {},
    onLogout: () -> Unit = {},
    passkeyEnabled: Boolean = false,
) {
    val nav = rememberNavController()

    /** Si hay wallet guardada o demo activo, la app arranca en la home. */
    var hasWallet by remember { mutableStateOf(initiallyHasWallet) }

    /**
     * Rol "activo" que controla qué tabs muestra el bottom nav en TODAS las
     * pantallas. Se actualiza en 3 casos:
     *   1. Onboarding → ChooseRoleScreen (turista/residente/comerciante).
     *   2. Registro exitoso en BecomeMerchantScreen (onboarding).
     *   3. DemoRoleSwitch en Perfil (solo isDemoMode, sin persistir).
     */
    var currentRole by remember { mutableStateOf(initialRole) }

    /** Navega a un destino de la app reemplazando el top del backstack. */
    fun goTo(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.WALLET) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val start = if (hasWallet) Routes.WALLET else Routes.WELCOME

    NavHost(navController = nav, startDestination = start) {

        // ── Welcome flow ──────────────────────────────────────────────────

        composable(Routes.WELCOME) {
            WelcomeScreen(
                demoEnabled = BuildConfig.DEMO_TOURIST_SECRET.isNotBlank(),
                onCreateWallet = { nav.navigate(Routes.CREATE_WALLET) },
                onImportWallet = { nav.navigate(Routes.IMPORT_WALLET) },
                onUseDemo = {
                    // Modo demo entra como turista sin pasar por ChooseRole.
                    currentRole = UserRole.TOURIST
                    hasWallet = true
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onSeeDashboard = { nav.navigate(Routes.DASHBOARD) },
                passkeyEnabled = passkeyEnabled,
                onCreatePasskeyWallet = { nav.navigate(Routes.CREATE_PASSKEY_WALLET) },
            )
        }
        composable(Routes.CREATE_PASSKEY_WALLET) {
            CreatePasskeyWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.CHOOSE_ROLE) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CREATE_WALLET) {
            CreateWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.CHOOSE_ROLE) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IMPORT_WALLET) {
            ImportWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.CHOOSE_ROLE) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }

        /**
         * Selección de rol en el onboarding.
         *
         *   Turista   → persiste TOURIST → entra directo en la app.
         *   Residente → persiste RESIDENT → entra (el admin del barrio mintea
         *               el token; el residente ya puede ir a Propuestas aunque
         *               aún no tenga token, la pantalla le explica qué hacer).
         *   Comerciante → navega a BECOME_MERCHANT_ONBOARDING; la app SOLO
         *                 persiste MERCHANT y entra si el registro on-chain
         *                 termina con éxito. Si el usuario cancela vuelve aquí.
         */
        composable(Routes.CHOOSE_ROLE) {
            ChooseRoleScreen(
                onTourist = {
                    onSetPreferredRole(UserRole.TOURIST)
                    currentRole = UserRole.TOURIST
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.CHOOSE_ROLE) { inclusive = true }
                    }
                },
                onMerchant = {
                    // Navega a BecomeMerchant sin sacar ChooseRole del backstack —
                    // si el usuario cancela/retrocede, vuelve aquí.
                    nav.navigate(Routes.BECOME_MERCHANT_ONBOARDING)
                },
                onResident = {
                    onSetPreferredRole(UserRole.RESIDENT)
                    currentRole = UserRole.RESIDENT
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.CHOOSE_ROLE) { inclusive = true }
                    }
                },
            )
        }

        // ── App principal ─────────────────────────────────────────────────

        composable(Routes.WALLET) {
            WalletScreen(
                onPayMerchant = { merchantAddress ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress")
                },
                onNavigateProfile   = { goTo(Routes.PROFILE) },
                onNavigateRewards   = { goTo(Routes.REWARDS) },
                onNavigateMap       = { goTo(Routes.MAP) },
                onNavigateDashboard = { nav.navigate(Routes.DASHBOARD) },
                onNavigateProposals = { goTo(Routes.PROPOSALS) },
                onNavigateCobros    = { goTo(Routes.COBROS) },
                currentRole = currentRole,
            )
        }
        composable(
            route = "${Routes.PAY_PREFIX}/{merchant_address}",
            arguments = listOf(navArgument("merchant_address") { type = NavType.StringType }),
        ) {
            PayScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onNavigateHome      = { goTo(Routes.WALLET) },
                onNavigateRewards   = { goTo(Routes.REWARDS) },
                onNavigateMap       = { goTo(Routes.MAP) },
                onNavigateProposals = { goTo(Routes.PROPOSALS) },
                onNavigateCobros    = { goTo(Routes.COBROS) },
                onBecomeMerchant    = { nav.navigate(Routes.BECOME_MERCHANT) },
                onLogout = {
                    onLogout()
                    hasWallet = false
                    currentRole = UserRole.TOURIST
                    nav.navigate(Routes.WELCOME) {
                        popUpTo(Routes.WALLET) { inclusive = true }
                    }
                },
                currentRole = currentRole,
                onDemoRoleChange = { role ->
                    // En modo demo: cambia la nav global SIN persistir.
                    // El juez puede mostrar los 3 flows en tiempo real.
                    currentRole = role
                },
            )
        }

        // BecomeMerchant desde Perfil (mid-session).
        // onSuccess solo actualiza el rol; no navega fuera de Perfil.
        composable(Routes.BECOME_MERCHANT) {
            BecomeMerchantScreen(
                onBack = { nav.popBackStack() },
                onSuccess = {
                    onSetPreferredRole(UserRole.MERCHANT)
                    currentRole = UserRole.MERCHANT
                    nav.popBackStack()
                },
            )
        }

        // BecomeMerchant desde el onboarding (ChooseRole).
        // onSuccess persiste el rol y lleva directo a la app.
        // onBack vuelve a ChooseRole (ChooseRole sigue en el backstack).
        composable(Routes.BECOME_MERCHANT_ONBOARDING) {
            BecomeMerchantScreen(
                onBack = { nav.popBackStack() },
                onSuccess = {
                    onSetPreferredRole(UserRole.MERCHANT)
                    currentRole = UserRole.MERCHANT
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.CHOOSE_ROLE) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.REWARDS) {
            RewardsScreen(
                onNavigateHome      = { goTo(Routes.WALLET) },
                onNavigateProfile   = { goTo(Routes.PROFILE) },
                onNavigateMap       = { goTo(Routes.MAP) },
                onNavigateProposals = { goTo(Routes.PROPOSALS) },
                onNavigateCobros    = { goTo(Routes.COBROS) },
                currentRole = currentRole,
            )
        }
        composable(Routes.MAP) {
            BarrioMapScreen(
                onPayMerchant = { merchantAddress ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress")
                },
                onNavigateHome      = { goTo(Routes.WALLET) },
                onNavigateProfile   = { goTo(Routes.PROFILE) },
                onNavigateRewards   = { goTo(Routes.REWARDS) },
                onNavigateProposals = { goTo(Routes.PROPOSALS) },
                onNavigateCobros    = { goTo(Routes.COBROS) },
                currentRole = currentRole,
            )
        }

        // Propuestas — rol RESIDENT
        composable(Routes.PROPOSALS) {
            ProposalsScreen(
                onNavigateHome    = { goTo(Routes.WALLET) },
                onNavigateMap     = { goTo(Routes.MAP) },
                onNavigateProfile = { goTo(Routes.PROFILE) },
                onCreateProposal  = { nav.navigate(Routes.CREATE_PROPOSAL) },
                currentRole = currentRole,
            )
        }
        composable(Routes.CREATE_PROPOSAL) {
            CreateProposalScreen(
                onBack    = { nav.popBackStack() },
                onSuccess = {
                    // Vuelve a la lista de propuestas.
                    nav.popBackStack()
                },
            )
        }

        // Cobros — rol MERCHANT
        composable(Routes.COBROS) {
            CobrosScreen(
                onNavigateHome    = { goTo(Routes.WALLET) },
                onNavigateMap     = { goTo(Routes.MAP) },
                onNavigateProfile = { goTo(Routes.PROFILE) },
                currentRole = currentRole,
            )
        }

        // Dashboard de transparencia — accesible sin login
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onBack       = { nav.popBackStack() },
                onOpenYield  = { nav.navigate(Routes.YIELD) },
            )
        }
        composable(Routes.YIELD) {
            YieldScreen(onBack = { nav.popBackStack() })
        }
    }
}

/** Rutas de navegación de la app. */
private object Routes {
    // Welcome flow
    const val WELCOME                    = "welcome"
    const val CREATE_WALLET              = "welcome/create"
    const val IMPORT_WALLET              = "welcome/import"
    const val CHOOSE_ROLE                = "welcome/role"
    const val CREATE_PASSKEY_WALLET      = "welcome/passkey"

    // App principal
    const val WALLET                     = "wallet"
    const val PAY_PREFIX                 = "pay"
    const val PROFILE                    = "profile"
    const val REWARDS                    = "rewards"
    const val MAP                        = "map"
    const val DASHBOARD                  = "dashboard"
    const val YIELD                      = "yield"

    // Registrar comerciante — dos rutas por contexto de origen
    const val BECOME_MERCHANT            = "become_merchant"
    const val BECOME_MERCHANT_ONBOARDING = "become_merchant_onboarding"

    // Pantallas de rol
    const val PROPOSALS                  = "proposals"
    const val CREATE_PROPOSAL            = "proposals/create"
    const val COBROS                     = "cobros"
}
