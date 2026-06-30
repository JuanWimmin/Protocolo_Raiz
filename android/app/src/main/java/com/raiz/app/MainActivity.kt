package com.raiz.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import kotlinx.coroutines.delay
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
import com.raiz.app.ui.become_resident.BecomeResidentScreen
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
import com.raiz.app.ui.welcome.CrearCuentaScreen
import com.raiz.app.ui.welcome.IniciarSesionScreen
import com.raiz.app.ui.welcome.ImportWalletScreen
import com.raiz.app.ui.welcome.WelcomeScreen
import com.raiz.app.data.model.RoleContext
import com.raiz.app.data.stellar.RoleResolver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
    @Inject lateinit var roleResolver: RoleResolver

    /** true = la app está bloqueada y requiere desbloqueo ahora. */
    private var locked by mutableStateOf(false)

    /** true = mostrar la intro de arranque (se auto-descarta ~1.2 s después). */
    private var showIntro by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = appLock.isActive() && walletManager.hasUsableWallet()
        setContent {
            RaizTheme {
                Box(modifier = Modifier.fillMaxSize()) {

                    // App principal + gate de lock (lógica intacta)
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
                            // Persiste el barrio elegido en el onboarding de residente.
                            onSavePendingResidentBarrio = { hex ->
                                walletManager.savePendingResidentBarrio(hex)
                            },
                            // Address del wallet activo para resolver el rol on-chain tras login.
                            getAccountId = { walletManager.currentAccountId() },
                            // Resolución on-chain: delega en RoleResolver (Hilt singleton).
                            resolveRole = { address -> roleResolver.resolve(address) },
                            onLogout = { walletManager.logout() },
                            passkeyEnabled = passkeyManager.isAvailable,
                        )
                    }

                    // Intro de arranque: overlay encima de RaizApp, no encima del lock.
                    // El contenido (SplashIntro) maneja su propio fade-in y scale-in;
                    // el AnimatedVisibility maneja el fade-out al descartarse.
                    AnimatedVisibility(
                        visible = showIntro && !locked,
                        enter = EnterTransition.None,
                        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
                    ) {
                        SplashIntro()
                    }

                    // Temporizador del intro: descarta después de 1.2 s (solo fuera del lock)
                    if (!locked) {
                        LaunchedEffect(Unit) {
                            delay(1_200L)
                            showIntro = false
                        }
                    }
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
                    showIntro = false  // No mostrar la intro después de un desbloqueo
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
    /** Persiste el barrio (hex) elegido en el onboarding de residente. */
    onSavePendingResidentBarrio: (String) -> Unit = {},
    /** Address del wallet activo (para resolver el rol on-chain tras login). */
    getAccountId: () -> String? = { null },
    /** Suspend lambda que consulta el rol on-chain a partir del address. */
    resolveRole: suspend (String) -> RoleContext = { RoleContext(role = UserRole.TOURIST) },
    onLogout: () -> Unit = {},
    passkeyEnabled: Boolean = false,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    /** Si hay wallet guardada o demo activo, la app arranca en la home. */
    var hasWallet by remember { mutableStateOf(initiallyHasWallet) }

    /**
     * Rol "activo" que controla qué tabs muestra el bottom nav en TODAS las
     * pantallas. Se actualiza en 3 casos:
     *   1. Login → resolución on-chain (navigateAfterLogin).
     *   2. Registro exitoso en BecomeMerchantScreen (onboarding).
     *   3. DemoRoleSwitch en Perfil (solo isDemoMode, sin persistir).
     */
    var currentRole by remember { mutableStateOf(initialRole) }

    /**
     * true mientras navigateAfterLogin está consultando el rol on-chain.
     * Un overlay de loading cubre la pantalla en ese intervalo (~1-3 s).
     */
    var resolvingRoleAfterLogin by remember { mutableStateOf(false) }

    /** Navega a un destino de la app reemplazando el top del backstack. */
    fun goTo(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.WALLET) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * Navega tras un login exitoso (passkey sign-in o importar semilla).
     *
     * Resuelve el rol del usuario CONSULTANDO LA BLOCKCHAIN con [RoleResolver]:
     *   - Residente si tiene ResidentToken en Governance.
     *   - Comerciante si está registrado en Pool.
     *   - Turista por defecto.
     *
     * Siempre navega a [Routes.WALLET] — NUNCA a [Routes.CHOOSE_ROLE].
     * CHOOSE_ROLE queda exclusivamente para el REGISTRO de cuenta nueva
     * (flujos CREATE_WALLET / CREATE_PASSKEY_WALLET / IMPORT_WALLET).
     */
    fun navigateAfterLogin() {
        hasWallet = true
        resolvingRoleAfterLogin = true
        scope.launch {
            val address = getAccountId() ?: ""
            val resolved = if (address.isNotBlank()) {
                try {
                    resolveRole(address)
                } catch (e: Exception) {
                    // Red caída u otro fallo → default TOURIST para no bloquear al usuario.
                    RoleContext(role = UserRole.TOURIST)
                }
            } else {
                RoleContext(role = UserRole.TOURIST)
            }
            currentRole = resolved.role
            onSetPreferredRole(resolved.role)
            nav.navigate(Routes.WALLET) {
                popUpTo(Routes.WELCOME) { inclusive = true }
            }
            resolvingRoleAfterLogin = false
        }
    }

    // startDestination FIJO: se computa UNA sola vez desde initiallyHasWallet.
    // Si se recalculara con `hasWallet` (mutable), al crear una wallet el flag
    // pasa a true → el grafo del NavHost se reconstruye con start=WALLET y
    // RESETEA la navegación a Inicio, saltándose ChooseRoleScreen. Por eso lo
    // fijamos con remember y navegamos siempre de forma explícita con nav.navigate.
    val start = remember { if (initiallyHasWallet) Routes.WALLET else Routes.WELCOME }

    // Envuelve el NavHost en un Box para poder superponer el loading overlay
    // sin interferir con el árbol de navegación.
    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = start) {

        // ── Welcome flow ──────────────────────────────────────────────────

        composable(Routes.WELCOME) {
            WelcomeScreen(
                demoEnabled = BuildConfig.DEMO_TOURIST_SECRET.isNotBlank(),
                onSignIn = { nav.navigate(Routes.LOGIN) },
                onCreateAccount = { nav.navigate(Routes.REGISTER) },
                onUseDemo = {
                    // Modo demo: turista sin guardar wallet ni pasar por ChooseRole.
                    currentRole = UserRole.TOURIST
                    hasWallet = true
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onSeeDashboard = { nav.navigate(Routes.DASHBOARD) },
            )
        }

        // ── Flujo REGISTRO (cuenta nueva) ─────────────────────────────────

        composable(Routes.REGISTER) {
            // Usuario elige entre passkey o frase semilla para crear su cuenta.
            CrearCuentaScreen(
                passkeyEnabled = passkeyEnabled,
                onBack = { nav.popBackStack() },
                onPasskey = { nav.navigate(Routes.CREATE_PASSKEY_WALLET) },
                onSeedPhrase = { nav.navigate(Routes.CREATE_WALLET) },
            )
        }

        // ── Flujo LOGIN (cuenta existente) ────────────────────────────────

        composable(Routes.LOGIN) {
            // Usuario ya tiene cuenta: passkey (si disponible) o importar semilla.
            IniciarSesionScreen(
                passkeyEnabled = passkeyEnabled,
                onBack = { nav.popBackStack() },
                // Login exitoso: resuelve el rol on-chain y navega a WALLET.
                onWalletReady = { navigateAfterLogin() },
                onImportSeed = { nav.navigate(Routes.IMPORT_WALLET_LOGIN) },
                // Passkey no encontrada: lleva al flujo de REGISTRO (cuenta nueva).
                onNoAccount = { nav.navigate(Routes.REGISTER) },
            )
        }

        composable(Routes.IMPORT_WALLET_LOGIN) {
            // Importar semilla en contexto de LOGIN: comportamiento igual que LOGIN passkey.
            ImportWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = { navigateAfterLogin() },
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
         *   Residente → navega a BECOME_RESIDENT para elegir barrio; ahí se
         *               persiste RESIDENT + el barrio elegido y entra a la app.
         *               El token soulbound lo mintea el admin al pulsar
         *               "Verificar como residente" en Propuestas.
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
                    // Navega a la pantalla de elección de barrio sin sacar
                    // ChooseRole del backstack — si cancela, vuelve aquí.
                    nav.navigate(Routes.BECOME_RESIDENT)
                },
            )
        }

        // Onboarding de residente: elige barrio → persiste RESIDENT + barrio
        // pendiente y entra a la app. El admin mintea el soulbound más tarde,
        // cuando el usuario pulsa "Verificar como residente" en Propuestas.
        composable(Routes.BECOME_RESIDENT) {
            BecomeResidentScreen(
                onBack = { nav.popBackStack() },
                onConfirm = { barrioId ->
                    onSetPreferredRole(UserRole.RESIDENT)
                    currentRole = UserRole.RESIDENT
                    onSavePendingResidentBarrio(barrioId)
                    nav.navigate(Routes.WALLET) {
                        // CHOOSE_ROLE es la raíz del onboarding presente en el
                        // backstack (WELCOME ya fue removido al entrar aquí), así
                        // que pop inclusive deja un backstack limpio en [WALLET].
                        popUpTo(Routes.CHOOSE_ROLE) { inclusive = true }
                    }
                },
            )
        }

        // ── App principal ─────────────────────────────────────────────────

        composable(Routes.WALLET) {
            WalletScreen(
                // Flujo legacy: QR con solo la dirección G... (cobro abierto)
                onPayMerchant = { merchantAddress ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress")
                },
                // Flujo orden de cobro: QR generado por CobrosScreen con monto fijo
                onPayMerchantConMonto = { merchantAddress, amountStroops ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress?amount=$amountStroops")
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
        // Ruta PAY: merchant_address obligatorio, amount_stroops opcional.
        // Si amount_stroops > 0 llega desde un QR de orden de cobro.
        // Si viene sin ?amount=... el navArgument usa defaultValue 0L (cobro abierto).
        composable(
            route = "${Routes.PAY_PREFIX}/{merchant_address}?amount={amount_stroops}",
            arguments = listOf(
                navArgument("merchant_address") { type = NavType.StringType },
                navArgument("amount_stroops") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
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
    } // fin NavHost

    // Overlay de loading mientras se resuelve el rol on-chain tras login.
    // Cubre la pantalla de login por ~1-3 s hasta que RoleResolver devuelve el rol.
    if (resolvingRoleAfterLogin) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = RaizYellow)
                Text(
                    text = "Verificando tu cuenta...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            }
        }
    }
    } // fin Box(fillMaxSize) que envuelve NavHost + overlay
} // fin RaizApp

/**
 * Pantalla de intro de arranque (~1.2 s + 300 ms de fade-out).
 *
 * El [AnimatedVisibility] padre (en MainActivity.setContent) gestiona el fade-out.
 * Este composable gestiona el fade-in + scale-in del contenido logo/tagline.
 *
 * Diseño:
 *   - Fondo negro [RaizBlack] — contraste máximo con el ícono amarillo.
 *   - Ícono hoja [Icons.Outlined.Eco] en [RaizYellow] (64 dp).
 *   - Texto "RAÍZ" en displayLarge + letter spacing amplio, color [RaizYellow].
 *   - Tagline "Tu paga, el barrio crece" en blanco suave.
 *   - Todo el bloque escala de 0.75→1.0 con spring y aparece con fadeIn (400 ms).
 */
@Composable
private fun SplashIntro() {
    // Dispara la animación de entrada en el primer frame
    var logoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { logoVisible = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RaizBlack),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = logoVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 400)) +
                scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                    initialScale = 0.75f,
                ),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Eco,
                    contentDescription = null,
                    tint = RaizYellow,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    text = "RAÍZ",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                    ),
                    color = RaizYellow,
                )
                Text(
                    text = "Tu paga, el barrio crece",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizWhite.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** Rutas de navegación de la app. */
private object Routes {
    // Welcome flow — entrada
    const val WELCOME                    = "welcome"
    // Registro (cuenta nueva): usuario elige método de protección
    const val REGISTER                   = "welcome/register"
    // Login (cuenta existente): passkey o importar semilla
    const val LOGIN                      = "welcome/login"
    // Sub-rutas de registro
    const val CREATE_WALLET              = "welcome/create"
    const val CREATE_PASSKEY_WALLET      = "welcome/passkey"
    // Sub-rutas compartidas
    const val IMPORT_WALLET              = "welcome/import"
    // Variante de importar semilla para el flujo login (no fuerza ChooseRole si hay rol guardado)
    const val IMPORT_WALLET_LOGIN        = "welcome/import/login"
    const val CHOOSE_ROLE                = "welcome/role"

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

    // Onboarding de residente — elegir barrio
    const val BECOME_RESIDENT            = "become_resident"

    // Pantallas de rol
    const val PROPOSALS                  = "proposals"
    const val CREATE_PROPOSAL            = "proposals/create"
    const val COBROS                     = "cobros"
}
