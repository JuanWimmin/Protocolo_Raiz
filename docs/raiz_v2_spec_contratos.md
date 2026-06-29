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
    DefindexVault,                  // Address del vault DeFindex (instance)
    BarrioMerchants(BytesN<32>),    // índice de comercios por barrio (para list_merchants)
    TouristSeen(BytesN<32>, Address), // flag de turista único por barrio
    VaultShares(BytesN<32>),        // shares del vault por barrio (persistent)
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

// ── Vault DeFindex (yield sobre fondos ociosos) ──────────────────────────────

// BREAKING CHANGE en initialize: añade defindex_vault como 5° parámetro.
pub fn initialize(env: Env, admin: Address, usdc_token: Address, rewards_contract: Address,
    protocol_fee_bps: u32, defindex_vault: Address) -> Result<(), Error>;

// Cambia el vault en caliente sin re-desplegar Pool. Solo admin.
pub fn set_defindex_vault(env: Env, admin: Address, vault: Address) -> Result<(), Error>;

// Deposita fondos ociosos al vault para generar yield.
// caller debe ser admin O treasury_contract del barrio.
// Requiere pre-autorizar usdc.transfer(pool, vault, amount) vía authorize_as_current_contract.
// Evento: (symbol_short!("vault_dep"), barrio_id), (amount, shares)
pub fn deposit_idle_to_vault(env: Env, caller: Address, barrio_id: BytesN<32>, amount: i128)
    -> Result<(), Error>;

// Rescata shares del vault de vuelta a pool_balance (realiza el yield).
// caller debe ser admin O treasury_contract del barrio.
// NO necesita authorize_as_current_contract (el vault transfiere sus propios fondos).
// Evento: (symbol_short!("vault_red"), barrio_id), (shares, got)
pub fn redeem_from_vault(env: Env, caller: Address, barrio_id: BytesN<32>, shares: i128)
    -> Result<(), Error>;

// Lecturas del vault
pub fn get_vault_shares(env: Env, barrio_id: BytesN<32>) -> i128;
pub fn get_vault_value(env: Env, barrio_id: BytesN<32>) -> i128;  // llama vault.get_asset_amounts_per_shares
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
// NUEVO: si pool.get_vault_shares(barrio_id) > 0, llama pool.redeem_from_vault
//        para rescatar todo el yield de vuelta al pool antes del retiro
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
