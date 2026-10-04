# RAÍZ v2 — Especificación de contratos Soroban y modelo de datos

> Base técnica para el desarrollo en Claude Code. Arquitectura: Android (Kotlin/Compose) + kmp-stellar-sdk + 5 contratos Soroban en Rust (los 4 originales + `yield_adapter` desde F1). Todo on-chain.

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
    RewardsContract,                // Address del contrato Rewards (instance); destino de accrue_points
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
    pub lat_e6: i32,                // latitud * 1e6 (para el mapa)
    pub lng_e6: i32,                // longitud * 1e6
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
// → tourist.require_auth(); amount > 0 (InvalidAmount); tip_bps <= 10_000 (InvalidTipBps)
// → merchant debe existir (MerchantNotFound) y estar verified (MerchantNotVerified)
// → tip = amount * tip_bps / 10_000;  fee = amount * ProtocolFeeBps / 10_000
// → usdc.transfer: tourist → merchant (amount - fee); tourist → contrato Pool (tip, custodia del fondo,
//   solo si tip > 0); tourist → admin (fee, solo si fee > 0). El turista paga en total amount + tip.
// → actualiza BarrioData (pool_balance += tip, total_collected += tip, tx_count += 1,
//   unique_tourists += 1 la primera vez que ese turista aporta al barrio, vía TouristSeen)
// → si tip > 0: Rewards.accrue_points(env.current_contract_address(), tourist, tip)  [cross-contract]
// → emite evento: topics (symbol_short!("payment"), barrio_id), data (tourist, merchant, amount, tip)

pub fn register_barrio(env: Env, id: BytesN<32>, name: String, treasury_contract: Address) -> Result<(), Error>;
// → admin (DataKey::Admin).require_auth()
// → crea BarrioData con contadores en 0, añade el id a AllBarrios (sin duplicar) y deja BarrioMerchants(id) vacío
// → OJO: re-registrar un id existente SOBRESCRIBE su BarrioData a cero y vacía su índice de comercios
//   (no hay guarda AlreadyExists; pendiente H3)

pub fn register_merchant(env: Env, data: MerchantData) -> Result<(), Error>;
// → admin (DataKey::Admin).require_auth(); BarrioNotFound si data.barrio_id no está registrado
// → guarda `data` TAL CUAL: el contrato NO fuerza verified = true (quien llama — relayer o seed —
//   debe enviar verified: true; pay_merchant exige verified, si no MerchantNotVerified)
// → añade el address a BarrioMerchants(barrio_id) sin duplicar

// Retiro del fondo hacia un beneficiario. Solo el Treasury registrado del barrio
// (lo llama Treasury.execute_proposal tras un voto aprobado).
pub fn withdraw_to(env: Env, caller: Address, barrio_id: BytesN<32>, recipient: Address, amount: i128)
    -> Result<(), Error>;
// → caller.require_auth() y caller == barrio.treasury_contract (si no Unauthorized)
// → 0 < amount <= pool_balance (si no InvalidAmount); usdc.transfer(pool → recipient, amount); pool_balance -= amount

pub fn get_pool_balance(env: Env, barrio_id: BytesN<32>) -> i128;
pub fn get_barrio(env: Env, barrio_id: BytesN<32>) -> Result<BarrioData, Error>;      // BarrioNotFound
pub fn get_merchant(env: Env, merchant: Address) -> Result<MerchantData, Error>;      // MerchantNotFound
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
    ProtocolAdmin,                  // Address admin del protocolo (instance); único que llama set_barrio_admin
    TreasuryContract,               // Address del Treasury (instance); único que llama mark_executed
    Admin(BytesN<32>),              // barrio_id -> Address admin del barrio
    Resident(Address),              // residente -> ResidentToken (soulbound)
    Proposal(u64),                  // proposal_id -> Proposal
    ProposalCount,                  // contador global de propuestas
    ResidentCount(BytesN<32>),      // barrio_id -> número de residentes (para quórum)
    BarrioProposals(BytesN<32>),    // barrio_id -> Vec<u64> ids de propuestas (alimenta list_active_proposals)
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
pub fn initialize(env: Env, protocol_admin: Address, treasury_contract: Address) -> Result<(), Error>;
// → protocol_admin.require_auth(); una sola vez (AlreadyInitialized)

pub fn set_barrio_admin(env: Env, barrio_id: BytesN<32>, barrio_admin: Address) -> Result<(), Error>;
// → protocol_admin (DataKey::ProtocolAdmin).require_auth()
// → guarda Admin(barrio_id) e inicializa ResidentCount(barrio_id) en 0 si no existía

pub fn mint_resident(env: Env, barrio_admin: Address, resident: Address, barrio_id: BytesN<32>) -> Result<(), Error>;
// → barrio_admin.require_auth(); verifica que sea admin del barrio
// → crea ResidentToken; incrementa ResidentCount
// → NO permite re-mint al mismo address (1 residente = 1 voto)
// → emite evento: topics (symbol_short!("resident"), barrio_id), data resident

pub fn create_proposal(env: Env, proposer: Address, barrio_id: BytesN<32>, description: String, amount: i128, recipient: Address, duration_days: u32) -> Result<u64, Error>;
// → proposer.require_auth(); verifica que tenga ResidentToken del barrio
// → crea Proposal con status Active
// → emite evento: topics (symbol_short!("proposal"), barrio_id), data (id, proposer)

pub fn vote(env: Env, resident: Address, proposal_id: u64, support: bool) -> Result<(), Error>;
// → resident.require_auth(); verifica ResidentToken
// → verifica que no haya votado ya (DataKey::Vote)
// → incrementa votes_for o votes_against
// → emite evento: topics (symbol_short!("vote"), barrio_id), data (proposal_id, resident, support)

pub fn tally(env: Env, proposal_id: u64) -> Result<ProposalStatus, Error>;         // ProposalNotFound
// → idempotente: si la propuesta ya no está Active devuelve su estado sin escribir;
//   si aún no pasó closes_at devuelve Active sin escribir
// → si pasó closes_at: calcula si (votes_for + votes_against) >= 30% de ResidentCount (quórum)
//   y votes_for > votes_against → Passed, si no Rejected
// → actualiza status
// → emite evento (solo al pasar de Active a Passed/Rejected):
//   topics (symbol_short!("tally"), barrio_id), data (proposal_id, status)

pub fn mark_executed(env: Env, treasury_caller: Address, proposal_id: u64) -> Result<(), Error>;
// → treasury_caller.require_auth() y treasury_caller == DataKey::TreasuryContract (si no Unauthorized)
// → exige status == Passed (si no ProposalNotActive) → Executed. Lo llama Treasury.execute_proposal

pub fn get_proposal(env: Env, proposal_id: u64) -> Result<Proposal, Error>;        // ProposalNotFound
pub fn get_resident(env: Env, resident: Address) -> Result<ResidentToken, Error>;  // NotAResident
pub fn get_resident_count(env: Env, barrio_id: BytesN<32>) -> u32;
pub fn list_active_proposals(env: Env, barrio_id: BytesN<32>) -> Vec<Proposal>;    // solo las de status Active
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
    BarrioExecutions(BytesN<32>),   // barrio_id -> Vec<u64> execution_ids (alimenta get_execution_log)
    TotalExecutions,                // contador global de execution_ids
}

#[contracttype]
#[derive(Clone)]
pub struct Execution {
    pub proposal_id: u64,
    pub barrio_id: BytesN<32>,
    pub amount: i128,
    pub recipient: Address,
    pub executed_at: u64,
    pub tx_hash: BytesN<32>,        // ID de auditoría reproducible — NO es el hash de la tx (ver nota)
}
```

> **Nota sobre `tx_hash` (D2 del SOW, 2026-09-12).** Un contrato Soroban no puede conocer el
> hash de la transacción que lo está ejecutando, así que `tx_hash` es
> `sha256(proposal_id ‖ barrio_id ‖ executed_at)` — `proposal_id` y `executed_at` como u64 big-endian
> (8 bytes cada uno) y `barrio_id` como sus 32 bytes crudos —: un **ID de auditoría reproducible** (cualquiera
> puede recalcularlo desde el registro) y único por ejecución. **No es buscable en Stellar Expert**
> y la UI no debe presentarlo como hash de transacción. El hash real de la transacción se obtiene
> **fuera del contrato**, del evento `execution` que emite `execute_proposal`: el RPC (`getEvents`)
> devuelve `txHash` por evento y el cliente lo correlaciona con `get_execution_log` por
> `proposal_id` (único en Governance; una propuesta se ejecuta una sola vez). Cuando es la propia
> app la que ejecuta la propuesta, el hash se fija al firmar/enviar y queda en una caché local del
> dispositivo. Si el evento ya salió de la ventana de retención del RPC (~7 días en testnet,
> `getHealth.ledgerRetentionWindow`) y el dispositivo no lo tenía en caché, el cliente recurre al
> archivo versionado `android/app/src/main/assets/execution_hashes.json` (la UI lo rotula
> "Verificada (archivo)", `Execution.realTxHashFromArchive = true`); si tampoco está ahí, la
> ejecución se muestra como "histórica" sin enlace. Eliminar `tx_hash` o renombrarlo a
> `audit_id` es una decisión de redeploy futuro, no del sprint SOW. Espejo Kotlin (solo cliente,
> no on-chain): `Execution.realTxHash`, `Execution.realTxHashFromArchive`, `Execution.verified`
> (derivado: `realTxHash` no vacío) y `ExecutionEvent`, en `RaizModels.kt` y `data/model/Execution.kt`.

### Funciones
```rust
pub fn initialize(env: Env, pool_contract: Address, governance_contract: Address) -> Result<(), Error>;
// → una sola vez (AlreadyInitialized); guarda PoolContract y GovernanceContract y pone TotalExecutions en 0

pub fn execute_proposal(env: Env, proposal_id: u64) -> Result<(), Error>;
// → consulta Governance.tally(proposal_id) [cross-contract]
// → solo si status == Passed (si no ProposalNotPassed)
// → consulta Governance.get_proposal para amount y recipient
// Si pool.get_vault_shares(barrio_id) > 0, llama pool.redeem_from_vault
//        para rescatar todo el yield de vuelta al pool antes del retiro.
//        (F1: misma ABI — por debajo Pool delega en el yield_adapter; Treasury
//         no cambia de código.)
// → llama a Pool.withdraw_to para transferir del pool al recipient
// → registra Execution; marca proposal como Executed en Governance
// → emite evento: topics (symbol_short!("execution"), barrio_id), data (proposal_id, amount, recipient)
// → cualquiera puede llamar esto (es trustless): si pasó, se ejecuta

pub fn get_execution_log(env: Env, barrio_id: BytesN<32>) -> Vec<Execution>;
pub fn get_execution(env: Env, execution_id: u64) -> Option<Execution>;      // execution_id = contador global (TotalExecutions)
pub fn get_execution_count(env: Env, barrio_id: BytesN<32>) -> u64;
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
    BarrioRewards(BytesN<32>),      // barrio_id -> Vec<u64> reward_ids (alimenta list_rewards)
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
pub fn initialize(env: Env, admin: Address, pool_contract: Address) -> Result<(), Error>;
// → admin.require_auth(); una sola vez (AlreadyInitialized)

pub fn register_reward(env: Env, barrio_id: BytesN<32>, name: String, artisan: Address,
    points_cost: u64, stock: u32, image_ref: String) -> Result<u64, Error>;
// → admin (DataKey::Admin).require_auth(); points_cost > 0 y stock > 0 (si no InvalidParams)
// → crea el Reward, lo añade a BarrioRewards(barrio_id) y devuelve su id

pub fn accrue_points(env: Env, caller_pool: Address, tourist: Address, tip_amount: i128) -> Result<(), Error>;
// → caller_pool.require_auth() y caller_pool == DataKey::PoolContract, si no Unauthorized
//   (Pool pasa env.current_contract_address() como caller_pool: solo él puede acumular puntos)
// → tip_amount <= 0: no-op (Ok)
// → puntos = tip_amount / 100_000 (POINTS_PER_STROOP_DIVISOR): 1 punto por cada 0.01 USDC de tip
// → suma al saldo Points(tourist)

pub fn list_rewards(env: Env, barrio_id: BytesN<32>) -> Vec<Reward>;
pub fn get_points(env: Env, tourist: Address) -> u64;
pub fn get_reward(env: Env, reward_id: u64) -> Result<Reward, Error>;              // RewardNotFound
pub fn get_redemption(env: Env, redemption_id: u64) -> Result<Redemption, Error>;  // RedemptionNotFound

pub fn redeem(env: Env, tourist: Address, reward_id: u64) -> Result<u64, Error>;
// → tourist.require_auth()
// → verifica puntos suficientes y stock > 0
// → quema puntos, decrementa stock, crea Redemption
// → emite evento: topics (symbol_short!("redeem"), barrio_id), data (tourist, reward_id, redemption_id)
//   ("redemption" no cabe en symbol_short: máx. 9 caracteres) → notifica al artesano

pub fn claim_redemption(env: Env, artisan: Address, redemption_id: u64) -> Result<(), Error>;
// → artisan.require_auth(); marca claimed=true cuando entrega el premio físico
// → solo el artesano del reward (si no Unauthorized); AlreadyClaimed si ya estaba entregado
// → emite evento: topics (symbol_short!("claim"), barrio_id), data (artisan, redemption_id)
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
| Cliente Blend | `#[contractclient]` + structs `#[contracttype]` espejo declarados a mano (Request, Positions, Reserve, ReserveConfig, ReserveData, PoolConfig) — NO `blend-contract-sdk` (su última versión es para soroban-sdk 25; chocaría con nuestro 26.1.1). Mismo patrón que el viejo DefindexVaultClient |
| Valoración | `value_of = shares × b_rate / 1e12` con `get_reserve(usdc)` (b_rate viene acumulado al ledger actual). ESCALA 1e12 (Blend v2; en v1 era 1e9) |
| Auth a Blend | `deposit`: `env.authorize_as_current_contract` para la sub-invocación `usdc.transfer(adapter → blend_pool, amount)` + `submit(from=spender=to=adapter)`. `withdraw`/`claim`: SIN auth extra (tokens salen del pool de Blend; invoker auth cubre el require_auth) |
| Retiro | request `Withdraw` con `amount = shares × b_rate / 1e12` (floor); Blend quema `ceil` — el dust de redondeo (≤1 bToken) queda acreditado al barrio, nunca se pierde entre barrios |
| Índice de reserva | Leerlo de `get_reserve(usdc).config.index` en runtime (TestnetV2: USDC = 3) — NO hardcodear |
| Eventos | `(symbol_short!("supply"), barrio_id), (amount, shares)` y `(symbol_short!("withdrw"), barrio_id), (shares, amount)` — los eventos canónicos del dashboard siguen siendo los `vault_dep`/`vault_red` de Pool |
| Direcciones testnet | Blend pool TestnetV2 `CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF`; USDC SAC `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU` (el mismo que ya custodia el fondo); BLND `CB22KRA3YZVCNCQI64JQ5WE7UY2VAV7WFLK6A2JN3HEX56T2EDAFO7QF`. Van como parámetros de deploy (env vars del script), no hardcodeadas en el contrato |
| Riesgo (lección YieldBlox) | El pool objetivo se parametriza en el adapter, no en Pool; el colchón líquido (20% gobernable) vive en Pool; el dashboard debe exponer pool exacto + backstop + oráculo |

### Modelo Kotlin espejo (ya en `docs/RaizModels.kt`)

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

## Códigos de error por contrato

Cada contrato declara su `#[contracterror] enum Error` (`#[repr(u32)]`); el cliente lo recibe como
`Error(Contract, #N)`. **El mismo número significa cosas distintas según el contrato** (p. ej. #5 es
`MerchantNotVerified` en Pool, `AlreadyResident` en Governance e `InsufficientShares` en el adapter):
hay que atribuir el error al contrato que lo emitió.

| # | Pool | Governance | Treasury | Rewards | yield_adapter |
|---|---|---|---|---|---|
| 1 | NotInitialized | NotInitialized | NotInitialized | NotInitialized | NotInitialized |
| 2 | AlreadyInitialized | AlreadyInitialized | AlreadyInitialized | AlreadyInitialized | AlreadyInitialized |
| 3 | Unauthorized | Unauthorized | ProposalNotPassed | Unauthorized | Unauthorized |
| 4 | MerchantNotFound | BarrioAdminNotSet | — | RewardNotFound | InvalidAmount |
| 5 | MerchantNotVerified | AlreadyResident | — | InsufficientPoints | InsufficientShares |
| 6 | BarrioNotFound | NotAResident | — | OutOfStock | — |
| 7 | InvalidAmount | ProposalNotFound | — | RedemptionNotFound | — |
| 8 | InvalidTipBps | InvalidDuration | — | AlreadyClaimed | — |
| 9 | AdapterNotConfigured | InvalidAmount | — | InvalidParams | — |
| 10 | InsufficientLiquidity | AlreadyVoted | — | — | — |
| 11 | AdapterHasPositions | ProposalClosed | — | — | — |
| 12 | InvalidBps | ProposalNotActive | — | — | — |

Treasury #3 `ProposalNotPassed` cubre cualquier estado distinto de `Passed`: la propuesta sigue
`Active`, fue `Rejected` o ya está `Executed` (por eso una propuesta no se ejecuta dos veces).

---

## Limitaciones conocidas del deploy vigente: TTL de las entradas

Ningún contrato extiende el TTL de sus entradas (no hay `extend_ttl` en el código). Medido en testnet
(sep–oct 2026): las entradas persistentes nuevas — `Execution(n)`, contadores e índices por barrio del
Treasury, y `Proposal(n)` de Governance — nacen con el TTL mínimo de la red (≈ 7 días, 120 960 ledgers)
y se archivan si nadie las extiende. Desde Protocol 23 una entrada archivada se auto-restaura dentro de
la transacción, así que `get_execution_log` / `list_active_proposals` dejan de ser lecturas puras y un
cliente que simula sin firmante falla con `Signer required for write call`. Remedio operativo hoy:
`stellar contract restore` + `stellar contract extend --ledgers-to-extend 1500000` sobre cada clave
(detalle y comandos en `docs/evidencia_sow/d2/ejecuciones_2026-09-12.md`). La corrección de fondo
— gestión de TTL on-touch en los contratos — queda para el próximo redeploy (hallazgo H2 de
`docs/REVISION_2026-08-27.md`); todo contrato nuevo debe nacer con ella.

---

## Las 3 features de tu compañera, mapeadas

| Idea | Dónde vive | Cómo funciona |
|---|---|---|
| 🗺️ Mapa de locales | `Pool.list_merchants()` + Compose Maps | Cada MerchantData tiene lat/lng. La app dibuja pines. Tocar un pin muestra nombre, categoría, y cuánto ha aportado al barrio. |
| 🎁 Puntos + premios | Contrato `Rewards` completo | Pagar con Tip acumula puntos vía `accrue_points`. El turista ve premios (artesanías) y canjea con `redeem`. El artesano confirma entrega con `claim_redemption`. |
| 🚌 Descuentos transporte | Roadmap v2 (no MVP) | Requiere alianzas con empresas. Se modela después como otro tipo de Reward o un contrato Partnership. |

---

## Modelo de la app Android (capas Kotlin)

> Estructura real de `android/app/src/main/java/com/raiz/app/` al 2026-10-04. El árbol que traía esta
> spec en su versión original (`data/repository/*`, `domain/`, `AnchorService.kt`, `ui/transparency/`)
> era la estructura objetivo y no se implementó así: los ViewModels hablan directo con los clientes de `data/`.

```
app/
├── data/
│   ├── stellar/     WalletManager.kt, PasskeyWalletManager.kt, SecureWalletStore.kt   // semilla + passkey
│   │                SorobanClient.kt, ScvalParse.kt       // fachada de los contratos + parseo SCVal
│   │                HorizonStream.kt, SwapMath.kt         // balances, trustlines, path payments (Horizon)
│   │                BlendClient.kt, RoleResolver.kt, DeploymentsLoader.kt
│   ├── relayer/     RelayerClient.kt, RelayerModels.kt    // operaciones admin vía raiz-relayer (D1 del SOW)
│   ├── anchor/      AnchorClient.kt, AnchorModels.kt      // SEP-1/10/24 contra el anchor de prueba (D3)
│   ├── local/       ExecutionHashStore.kt                 // caché + archivo de hashes reales de ejecuciones (D2)
│   ├── security/    AppLock.kt
│   └── model/       // data classes espejo de los structs Rust + modelos solo-cliente
├── ui/
│   ├── wallet/  pay/  rewards/  map/  governance/  dashboard/  treasury/  deposit/  profile/  cobros/
│   ├── welcome/  become_merchant/  become_resident/  security/  qr/
│   └── components/  theme/  util/
├── di/              DataModule.kt                         // Hilt
├── MainActivity.kt                                        // navegación
└── RaizApplication.kt
```

---

## Decisiones del MVP (cerradas)

> Eran las preguntas abiertas antes de escribir código. Todas se cerraron y así están implementadas
> (ver también "Decisiones tomadas" en `CLAUDE.md`).

1. **Token de puntos**: 1 punto por cada 0.01 USDC de tip (`tip_stroops / 100_000`; un tip de $1 da 100 puntos).
2. **Quórum**: 30% de los residentes del barrio + **mayoría simple** (`votes_for > votes_against`).
3. **Duración de propuestas**: configurable entre 3 y 14 días.
4. **Mapa**: **Mapbox** (Maps SDK 11.x + maps-compose); Google Maps Compose queda como plan B documentado en `docs/raiz_mapbox_setup.md`.
5. **Verificación de residencia**: mock para el MVP (el admin del barrio mintea el soulbound, sin validar documentos); SEP-12 KYC en v2.
6. **Imágenes de premios**: URLs en el MVP; IPFS en v2.
