# RAÍZ v2 — Especificación de contratos Soroban y modelo de datos

> Base técnica para el desarrollo en Claude Code. Arquitectura: Android (Kotlin/Compose) + kmp-stellar-sdk + 4 contratos Soroban en Rust. Todo on-chain.

---

## Decisiones de arquitectura tomadas

| Decisión | Elección | Razón |
|---|---|---|
| Plataforma app | Kotlin nativo (Jetpack Compose) | Control nativo Android, mejor rendimiento, NFC/cámara directos |
| SDK Stellar | kmp-stellar-sdk (Soneso) | Soporta Horizon, Soroban RPC, smart accounts con passkey |
| Wallet | Passkey (WebAuthn) + fallback frase semilla | Máxima accesibilidad sin sacrificar custodia |
| On/off ramp | SEP-24 (hosted) + SEP-38 (RFQ tasas) | Estándar resuelto, no se construye desde cero |
| Swaps | Path Payments + DEX nativo Stellar | Conversión USDC→local sin intermediario |
| Auth con anchors | SEP-10 | Sesiones autenticadas con la wallet |
| KYC (cuando aplique) | SEP-12 | Solo para montos altos / off-ramp |
| Puntos de recompensa | Dentro de contrato Soroban (Rewards) | Unificado on-chain, no especulable |
| Gobernanza | Soulbound tokens en Soroban | Voto no comprable |
| Yield del fondo (F1) | **Blend v2 directo tras `YieldAdapter` propio** (muere el vault DeFindex) | Sin intermediario ni API key; la fuente de yield se vuelve intercambiable (`set_yield_adapter`) y a futuro gobernada por los residentes |

---

## Contrato 1: `Pool` — pagos y fondo del barrio

### Storage (DataKey enum)
```rust
#[contracttype]
pub enum DataKey {
    Admin,                          // Address del administrador del protocolo
    Barrio(BytesN<32>),             // id del barrio -> BarrioData
    Merchant(Address),              // comercio -> MerchantData
    UsdcToken,                      // Address del token USDC (SAC)
    ProtocolFeeBps,                 // fee del protocolo en basis points (50 = 0.5%)
    YieldAdapter,                   // Address del contrato yield_adapter (instance). F1: antes DefindexVault
    CushionBps,                     // colchón líquido no invertido en bps (instance, default 2000 = 20%)
    BarrioMerchants(BytesN<32>),    // índice de comercios por barrio (para list_merchants)
    TouristSeen(BytesN<32>, Address), // flag de turista único por barrio
    // F1: la clave VaultShares(BytesN<32>) desaparece de Pool — la contabilidad
    // de shares por barrio vive en el yield_adapter (única fuente de verdad).
    // get_vault_shares/get_vault_value delegan en el adapter.
    // Índice global de barrios (persistent Vec<BytesN<32>>).
    // Alimentado por register_barrio; permite RBAC dinámico desde la app.
    // NOTA: barrios registrados antes de añadir esta clave no aparecen
    // hasta un re-seed. La app debe tener fallback en deployments.json.
    AllBarrios,
}

#[contracttype]
#[derive(Clone)]
pub struct BarrioData {
    pub id: BytesN<32>,
    pub name: String,
    pub pool_balance: i128,         // saldo acumulado del fondo (USDC stroops)
    pub total_collected: i128,      // histórico total recaudado
    pub tx_count: u64,              // número de transacciones
    pub unique_tourists: u32,       // turistas únicos que aportaron
    pub treasury_contract: Address, // contrato Treasury autorizado a retirar
}

#[contracttype]
#[derive(Clone)]
pub struct MerchantData {
    pub address: Address,
    pub name: String,
    pub barrio_id: BytesN<32>,
    pub verified: bool,
    pub lat: i32,                   // latitud * 1e6 (para el mapa)
    pub lng: i32,                   // longitud * 1e6
    pub category: Symbol,           // cafe, artesania, restaurante, etc.
}
```

### Funciones
```rust
// Pago principal: turista paga al comercio con Tip Barrio opcional
pub fn pay_merchant(
    env: Env,
    tourist: Address,
    merchant: Address,
    amount: i128,        // monto base en USDC stroops
    tip_bps: u32,        // tip en basis points (200 = 2%)
) -> Result<(), Error>;
// → tourist.require_auth()
// → transfiere amount al merchant
// → calcula tip = amount * tip_bps / 10000
// → transfiere tip al pool del barrio del merchant
// → actualiza BarrioData (balance, tx_count, unique_tourists)
// → llama a Rewards.accrue_points(tourist, tip)  [cross-contract]
// → emite evento: payment(tourist, merchant, amount, tip, barrio_id)

pub fn register_merchant(env: Env, admin: Address, data: MerchantData) -> Result<(), Error>;
// → admin.require_auth(), marca verified

pub fn get_pool_balance(env: Env, barrio_id: BytesN<32>) -> i128;
pub fn get_barrio(env: Env, barrio_id: BytesN<32>) -> BarrioData;
pub fn list_merchants(env: Env, barrio_id: BytesN<32>) -> Vec<MerchantData>;  // para el mapa

// Devuelve todos los barrio_id registrados, en orden de inserción.
// Solo lectura, sin auth. Permite RBAC dinámico: la app no hardcodea IDs.
// NOTA: barrios registrados antes de que existiera DataKey::AllBarrios no
// aparecen aquí hasta re-seed. La app debe tener fallback en deployments.json.
pub fn list_barrios(env: Env) -> Vec<BytesN<32>>;

// ── Yield sobre fondos ociosos (F1: vía YieldAdapter, muere DeFindex) ────────
//
// NOTA DE COMPATIBILIDAD: los nombres deposit_idle_to_vault / redeem_from_vault /
// get_vault_shares / get_vault_value y los eventos vault_dep / vault_red SE
// CONSERVAN aunque ya no exista "el vault DeFindex" — "vault" pasa a significar
// "la fuente de yield tras el adapter". Así Treasury, la app y el dashboard de
// transparencia no cambian de ABI ni de parser de eventos.

// BREAKING CHANGE en initialize (re-deploy F1): el 5° parámetro pasa a ser el
// contrato yield_adapter (antes era defindex_vault).
pub fn initialize(env: Env, admin: Address, usdc_token: Address, rewards_contract: Address,
    protocol_fee_bps: u32, yield_adapter: Address) -> Result<(), Error>;

// Cambia el adapter en caliente sin re-desplegar Pool. Solo admin (v-actual);
// cuando la gobernanza pueda invocarlo, será una propuesta votable ("¿el fondo
// va conservador o 70/30?"). GUARDA: solo permitido si adapter.total_shares() == 0
// (sin posiciones activas) — migrar con posiciones exige rescatar todo antes.
pub fn set_yield_adapter(env: Env, admin: Address, adapter: Address) -> Result<(), Error>;

// Colchón líquido gobernable (default 2000 bps = 20%): fracción del fondo del
// barrio que NUNCA se invierte, para que las ejecuciones de Treasury se sirvan
// primero del colchón. Solo admin (futuro: gobernanza).
pub fn set_cushion_bps(env: Env, admin: Address, bps: u32) -> Result<(), Error>;  // bps <= 10_000

// Deposita fondos ociosos en la fuente de yield vía el adapter.
// caller debe ser admin O treasury_contract del barrio.
// Flujo: usdc.transfer(pool → adapter, amount) [invocación directa, invoker auth]
//        + adapter.deposit(pool, barrio_id, amount) -> shares.
// (El authorize_as_current_contract para la sub-invocación a Blend vive ahora
//  DENTRO del adapter, no en Pool.)
// VALIDA el colchón: tras depositar, pool_balance restante * 10_000 >=
//   (pool_balance + adapter.value_of(barrio_id)) * cushion_bps
//   → error InsufficientLiquidity si lo rompe.
// Evento: (symbol_short!("vault_dep"), barrio_id), (amount, shares)   [sin cambio]
pub fn deposit_idle_to_vault(env: Env, caller: Address, barrio_id: BytesN<32>, amount: i128)
    -> Result<(), Error>;

// Rescata shares vía el adapter de vuelta a pool_balance (realiza el yield).
// caller debe ser admin O treasury_contract del barrio.
// Flujo: adapter.withdraw(pool, barrio_id, shares, to = pool) -> got;
//        pool_balance += got.
// El adapter valida shares <= shares_of(barrio_id) — corrige el bug pre-F1 de
// rescatar shares contablemente ajenas (VaultShares podía quedar negativo).
// Evento: (symbol_short!("vault_red"), barrio_id), (shares, got)      [sin cambio]
pub fn redeem_from_vault(env: Env, caller: Address, barrio_id: BytesN<32>, shares: i128)
    -> Result<(), Error>;

// Lecturas (delegan en el adapter; Pool ya no guarda shares propias)
pub fn get_vault_shares(env: Env, barrio_id: BytesN<32>) -> i128;  // = adapter.shares_of(barrio_id)
pub fn get_vault_value(env: Env, barrio_id: BytesN<32>) -> i128;   // = adapter.value_of(barrio_id)
```

---

## Contrato 2: `Governance` — soulbound y votación

### Storage
```rust
#[contracttype]
pub enum DataKey {
    Admin(BytesN<32>),              // barrio_id -> Address admin del barrio
    Resident(Address),              // residente -> ResidentToken (soulbound)
    Proposal(u64),                  // proposal_id -> Proposal
    ProposalCount,                  // contador global de propuestas
    ResidentCount(BytesN<32>),      // barrio_id -> número de residentes (para quórum)
    Vote(u64, Address),             // (proposal_id, resident) -> bool (ya votó)
}

#[contracttype]
#[derive(Clone)]
pub struct ResidentToken {
    pub resident: Address,
    pub barrio_id: BytesN<32>,
    pub issued_at: u64,             // ledger timestamp
    // NO hay campo transferable. NO existe función transfer(). Es soulbound.
}

#[contracttype]
#[derive(Clone)]
pub struct Proposal {
    pub id: u64,
    pub barrio_id: BytesN<32>,
    pub proposer: Address,
    pub description: String,
    pub amount: i128,               // monto solicitado del pool
    pub recipient: Address,         // quién recibe si pasa
    pub votes_for: u32,
    pub votes_against: u32,
    pub created_at: u64,
    pub closes_at: u64,             // ledger timestamp de cierre
    pub status: ProposalStatus,
}

#[contracttype]
#[derive(Clone, PartialEq)]
pub enum ProposalStatus {
    Active,
    Passed,
    Rejected,
    Executed,
}
```

### Funciones
```rust
pub fn mint_resident(env: Env, barrio_admin: Address, resident: Address, barrio_id: BytesN<32>) -> Result<(), Error>;
// → barrio_admin.require_auth(); verifica que sea admin del barrio
// → crea ResidentToken; incrementa ResidentCount
// → NO permite re-mint al mismo address (1 residente = 1 voto)

pub fn create_proposal(env: Env, proposer: Address, barrio_id: BytesN<32>, description: String, amount: i128, recipient: Address, duration_days: u32) -> Result<u64, Error>;
// → proposer.require_auth(); verifica que tenga ResidentToken del barrio
// → crea Proposal con status Active

pub fn vote(env: Env, resident: Address, proposal_id: u64, support: bool) -> Result<(), Error>;
// → resident.require_auth(); verifica ResidentToken
// → verifica que no haya votado ya (DataKey::Vote)
// → incrementa votes_for o votes_against

pub fn tally(env: Env, proposal_id: u64) -> ProposalStatus;
// → si pasó closes_at: calcula si (votes_for + votes_against) >= 30% de ResidentCount (quórum)
//   y votes_for > votes_against → Passed, si no Rejected
// → actualiza status

pub fn get_proposal(env: Env, proposal_id: u64) -> Proposal;
pub fn list_active_proposals(env: Env, barrio_id: BytesN<32>) -> Vec<Proposal>;
```

---

## Contrato 3: `Treasury` — ejecución auditable

### Storage
```rust
#[contracttype]
pub enum DataKey {
    PoolContract,                   // Address del contrato Pool
    GovernanceContract,             // Address del contrato Governance
    Execution(u64),                 // execution_id -> Execution
    ExecutionCount(BytesN<32>),     // barrio_id -> contador
}

#[contracttype]
#[derive(Clone)]
pub struct Execution {
    pub proposal_id: u64,
    pub barrio_id: BytesN<32>,
    pub amount: i128,
    pub recipient: Address,
    pub executed_at: u64,
    pub tx_hash: BytesN<32>,        // referencia auditable
}
```

### Funciones
```rust
pub fn execute_proposal(env: Env, proposal_id: u64) -> Result<(), Error>;
// → consulta Governance.tally(proposal_id) [cross-contract]
// → solo si status == Passed
// → consulta Governance.get_proposal para amount y recipient
// Si pool.get_vault_shares(barrio_id) > 0, llama pool.redeem_from_vault
//        para rescatar todo el yield de vuelta al pool antes del retiro.
//        (F1: misma ABI — por debajo Pool delega en el yield_adapter; Treasury
//         no cambia de código.)
// → llama a Pool.withdraw_to para transferir del pool al recipient
// → registra Execution; marca proposal como Executed en Governance
// → emite evento: execution(proposal_id, barrio_id, amount, recipient)
// → cualquiera puede llamar esto (es trustless): si pasó, se ejecuta

pub fn get_execution_log(env: Env, barrio_id: BytesN<32>) -> Vec<Execution>;
```

---

## Contrato 4: `Rewards` — puntos y premios (idea de tu compañera)

### Storage
```rust
#[contracttype]
pub enum DataKey {
    Admin,
    PoolContract,                   // solo Pool puede acumular puntos
    Points(Address),                // turista -> saldo de puntos (no transferible)
    Reward(u64),                    // reward_id -> Reward
    RewardCount,
    Redemption(u64),                // redemption_id -> Redemption
    RedemptionCount,
}

#[contracttype]
#[derive(Clone)]
pub struct Reward {
    pub id: u64,
    pub barrio_id: BytesN<32>,
    pub name: String,               // "Mochila artesanal wayuu"
    pub artisan: Address,           // quién la entrega
    pub points_cost: u64,
    pub stock: u32,
    pub image_ref: String,          // IPFS hash o URL
}

#[contracttype]
#[derive(Clone)]
pub struct Redemption {
    pub id: u64,
    pub tourist: Address,
    pub reward_id: u64,
    pub redeemed_at: u64,
    pub claimed: bool,              // el artesano marca cuando entrega
}
```

### Funciones
```rust
pub fn accrue_points(env: Env, tourist: Address, tip_amount: i128) -> Result<(), Error>;
// → solo PoolContract puede llamar (verifica caller)
// → puntos = tip_amount / FACTOR (ej: 1 punto por cada 0.01 USDC de tip)
// → suma al saldo Points(tourist)

pub fn list_rewards(env: Env, barrio_id: BytesN<32>) -> Vec<Reward>;
pub fn get_points(env: Env, tourist: Address) -> u64;

pub fn redeem(env: Env, tourist: Address, reward_id: u64) -> Result<u64, Error>;
// → tourist.require_auth()
// → verifica puntos suficientes y stock > 0
// → quema puntos, decrementa stock, crea Redemption
// → emite evento: redemption(tourist, reward_id) → notifica al artesano

pub fn claim_redemption(env: Env, artisan: Address, redemption_id: u64) -> Result<(), Error>;
// → artisan.require_auth(); marca claimed=true cuando entrega el premio físico
```

---

## Contrato 5: `yield_adapter` — la fuente de yield tras una interfaz propia (F1)

> Referencia de diseño: paper `docs/NuevaPropuesta/raiz_paper.tex` §4.2 y propuesta §3.2.
> El Pool no conoce a Blend: conoce ESTA interfaz. Cambiar de fuente de yield
> (RWA, renta fija, estrategia mixta) es desplegar otro adapter y un
> `set_yield_adapter` — no un re-deploy de Pool.

### Interfaz estándar (toda implementación la expone con estas firmas exactas)

```rust
// Deposita `amount` (USDC stroops) en la fuente de yield, contabilizado al barrio.
// caller.require_auth() + caller == PoolContract almacenado (mismo patrón que
// Rewards.accrue_points). El USDC ya debe estar en el balance del adapter
// (Pool hace usdc.transfer(pool → adapter, amount) justo antes, invocación
// directa = invoker auth; sin auth anidada en Pool).
// Devuelve las shares acreditadas al barrio.
pub fn deposit(env: Env, caller: Address, barrio_id: BytesN<32>, amount: i128) -> Result<i128, Error>;

// Retira `shares` del barrio y envía el USDC resultante a `to`.
// caller.require_auth() + caller == PoolContract.
// VALIDA shares > 0 && shares <= shares_of(barrio_id)  → InsufficientShares.
// Devuelve el USDC (stroops) efectivamente enviado a `to`.
pub fn withdraw(env: Env, caller: Address, barrio_id: BytesN<32>, shares: i128, to: Address)
    -> Result<i128, Error>;

// Lecturas puras (sin auth)
pub fn shares_of(env: Env, barrio_id: BytesN<32>) -> i128;   // shares del barrio
pub fn total_shares(env: Env) -> i128;                        // suma de todos los barrios (invariante)
pub fn value_of(env: Env, barrio_id: BytesN<32>) -> i128;     // valor actual en USDC stroops
pub fn apy_hint(env: Env) -> u32;                             // APY estimado en bps (informativo)
```

### Implementación 1: `BlendAdapter` (Blend v2 testnet, prestamista puro)

```rust
#[contracttype]
pub enum DataKey {
    Admin,                 // Address admin del protocolo (instance)
    PoolContract,          // Address del Pool de RAÍZ — único autorizado a deposit/withdraw (instance)
    BlendPool,             // Address del pool USDC de Blend v2 (instance)
    UsdcToken,             // Address del USDC SAC de Blend (instance)
    Shares(BytesN<32>),    // barrio_id -> bTokens del barrio (persistent)
    TotalShares,           // suma de bTokens de todos los barrios (instance)
}

pub fn initialize(env: Env, admin: Address, pool_contract: Address,
    blend_pool: Address, usdc_token: Address) -> Result<(), Error>;

// Solo admin: reclama emisiones BLND del lado supply hacia `to`.
// (En TestnetV2 el lado supply de USDC puede no tener emisiones — no-op seguro.)
pub fn claim_blnd(env: Env, admin: Address, to: Address) -> Result<i128, Error>;
```

Decisiones de implementación (verificadas contra blend-contracts-v2, jul-2026):

| Tema | Decisión |
|---|---|
| shares | **shares ≡ bTokens de Blend** (sin capa extra de contabilidad). `deposit` acredita el delta de bTokens que reporta `submit`; `withdraw` descuenta el delta real quemado |
| Requests Blend | `Supply = 0` / `Withdraw = 1` (u32 planos) — prestamista puro, NUNCA SupplyCollateral (2/3): no entra al health factor ni a max_positions. Patrón del fee-vault oficial (script3) |
| Cliente Blend | `#[contractclient]` + structs `#[contracttype]` espejo declarados a mano (Request, Positions, Reserve, ReserveConfig, ReserveData) — NO `blend-contract-sdk` (su última versión es para soroban-sdk 25; chocaría con nuestro 26.1.1). Mismo patrón que el viejo DefindexVaultClient |
| Valoración | `value_of = shares × b_rate / 1e12` con `get_reserve(usdc)` (b_rate viene acumulado al ledger actual). ESCALA 1e12 (Blend v2; en v1 era 1e9) |
| Auth a Blend | `deposit`: `env.authorize_as_current_contract` para la sub-invocación `usdc.transfer(adapter → blend_pool, amount)` + `submit(from=spender=to=adapter)`. `withdraw`/`claim`: SIN auth extra (tokens salen del pool de Blend; invoker auth cubre el require_auth) |
| Retiro | request `Withdraw` con `amount = shares × b_rate / 1e12` (floor); Blend quema `ceil` — el dust de redondeo (≤1 bToken) queda acreditado al barrio, nunca se pierde entre barrios |
| Índice de reserva | Leerlo de `get_reserve(usdc).config.index` en runtime (TestnetV2: USDC = 3) — NO hardcodear |
| Eventos | `(symbol_short!("supply"), barrio_id), (amount, shares)` y `(symbol_short!("withdrw"), barrio_id), (shares, amount)` — los eventos canónicos del dashboard siguen siendo los `vault_dep`/`vault_red` de Pool |
| Direcciones testnet | Blend pool TestnetV2 `CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF`; USDC SAC `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU` (el mismo que ya custodia el fondo); BLND `CB22KRA3YZVCNCQI64JQ5WE7UY2VAV7WFLK6A2JN3HEX56T2EDAFO7QF`. Van como parámetros de deploy (env vars del script), no hardcodeadas en el contrato |
| Riesgo (lección YieldBlox) | El pool objetivo se parametriza en el adapter, no en Pool; el colchón líquido (20% gobernable) vive en Pool; el dashboard debe exponer pool exacto + backstop + oráculo |

### Modelo Kotlin espejo (añadir a `docs/RaizModels.kt`)

```kotlin
/** Posición de yield de un barrio vía el YieldAdapter (F1: Blend directo). */
data class YieldPosition(
    val barrioId: String,      // hex de 64 chars
    val shares: Long,          // bTokens (stroops de bToken)
    val valueUsdc: Long,       // valor actual en stroops de USDC (shares × bRate / 1e12)
    val apyBps: Int            // APY estimado en basis points (informativo, variable)
)
```

---

## Las 3 features de tu compañera, mapeadas

| Idea | Dónde vive | Cómo funciona |
|---|---|---|
| 🗺️ Mapa de locales | `Pool.list_merchants()` + Compose Maps | Cada MerchantData tiene lat/lng. La app dibuja pines. Tocar un pin muestra nombre, categoría, y cuánto ha aportado al barrio. |
| 🎁 Puntos + premios | Contrato `Rewards` completo | Pagar con Tip acumula puntos vía `accrue_points`. El turista ve premios (artesanías) y canjea con `redeem`. El artesano confirma entrega con `claim_redemption`. |
| 🚌 Descuentos transporte | Roadmap v2 (no MVP) | Requiere alianzas con empresas. Se modela después como otro tipo de Reward o un contrato Partnership. |

---

## Modelo de la app Android (capas Kotlin)

```
app/
├── data/
│   ├── stellar/
│   │   ├── WalletManager.kt        // passkey + fallback semilla, kmp-stellar-sdk
│   │   ├── SorobanClient.kt        // invoca los 4 contratos
│   │   ├── AnchorService.kt        // SEP-24/SEP-38 on/off ramp + swaps
│   │   └── HorizonStream.kt        // SSE para balances en tiempo real
│   ├── repository/
│   │   ├── PaymentRepository.kt
│   │   ├── GovernanceRepository.kt
│   │   ├── RewardsRepository.kt
│   │   └── MerchantRepository.kt   // alimenta el mapa
│   └── model/                      // data classes espejo de los structs Rust
├── ui/
│   ├── wallet/      WalletScreen.kt, PayScreen.kt, QrScannerScreen.kt
│   ├── map/         BarrioMapScreen.kt   // Google Maps Compose o Mapbox
│   ├── rewards/     RewardsScreen.kt, RedeemScreen.kt
│   ├── governance/  ProposalsScreen.kt, VoteScreen.kt
│   └── transparency/ DashboardScreen.kt
├── domain/          // use cases
└── di/              // Hilt modules
```

---

## Lo que falta definir antes de codear (decisiones abiertas)

1. **Token de puntos**: ¿1 punto = X USDC de tip? Propongo: 1 punto por cada 0.01 USDC aportado (así un tip de $1 da 100 puntos).
2. **Quórum**: confirmado 30% de residentes verificados. ¿Mayoría simple o supermayoría 60%? Propongo mayoría simple para el MVP.
3. **Duración de propuestas**: ¿7 días fijos o configurable? Propongo configurable (3-14 días).
4. **Mapa**: ¿Google Maps Compose (más fácil, requiere API key) o Mapbox (mejor look, también key)? 
5. **Verificación de residencia**: para el MVP, ¿mock (el admin del barrio mintea manualmente) o algo más? Propongo mock para MVP, SEP-12 KYC en v2.
6. **Imágenes de premios**: ¿IPFS (descentralizado, coherente con la tesis) o URLs normales (más simple)? Propongo URLs para MVP, IPFS en v2.
```
