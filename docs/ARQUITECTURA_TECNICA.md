# RAÍZ — Documento técnico completo

> Fuente de verdad del **estado real implementado** (no de la visión). Todo lo
> aquí descrito está leído directamente del código en `contracts/` y `android/`
> y verificado contra el despliegue en `deployments.json` (Stellar Testnet).
> Última revisión: 2026-07-31.

---

## 0. TL;DR — ¿qué es y qué tan on-chain es?

**RAÍZ** es una red de pagos turísticos sobre **Stellar/Soroban**. Un turista
paga en USDC a un comercio local; un porcentaje configurable (**Tip Barrio**, 2%
por defecto) se desvía automáticamente a un **fondo comunitario** custodiado por
un contrato y **gobernado por los residentes** del barrio mediante tokens
*soulbound* (no transferibles). Las propuestas aprobadas se ejecutan de forma
**trustless**.

**¿Es completamente on-chain?** La **lógica de valor y estado es 100% on-chain**:

| On-chain (Soroban + Stellar) | Off-chain / mock / atajo de demo |
|---|---|
| Pagos USDC turista→comercio (SAC token transfer real) | KYC de residentes (admin mintea a mano; **no** SEP-12) |
| Split del Tip Barrio y custodia del pool | On-ramp fiat→USDC (faucet del admin simula SEP-24) |
| Acumulación de puntos (cross-contract) | Imágenes de premios (URLs, no IPFS) |
| Tokens de residencia soulbound | Aprobación de comercios (en demo firma el admin embebido) |
| Propuestas, votos, quórum, tally, ejecución | Relayer/indexer del passkey (infra pública de Soneso testnet, no propia) |
| Canje de puntos por premios + claim del artesano | `tx_hash` de la `Execution` (sha256 determinístico, no el hash real de Stellar) |
| Smart wallets passkey (WebAuthn/secp256r1, contrato `C…`) | APY del vault vía REST de DeFindex (opcional; muere con F1) |
| Yield del fondo en el vault DeFindex (cross-contract) | — |
| Log de ejecuciones auditable | — |

La app Android es un **cliente delgado**: lee por simulación vía Soroban RPC (sin
firmar) y escribe enviando transacciones firmadas con la clave del usuario. **No
hay backend propio**: no existe servidor de RAÍZ, todo el estado vive en los 4
contratos y en Horizon (la infra del passkey usa el relayer/indexer públicos de
Soneso, no un backend de RAÍZ).

---

## 1. Stack tecnológico

| Capa | Tecnología | Versión / nota |
|---|---|---|
| Contratos | Rust + `soroban-sdk` | **22.x → 26.1.1 (migración F1 en curso)**, `#![no_std]`, workspace Cargo con 4 crates (+ `yield_adapter` en F1) |
| Toolchain | rustc/cargo **1.97.1 pineado** (`contracts/rust-toolchain.toml`) · stellar-cli **23.2.1** | upgrade a stellar-cli 27.1.0 pendiente antes del re-deploy F1 |
| CI | GitHub Actions (`.github/workflows/contracts.yml`) | `cargo test --workspace` en cada push/PR que toque `contracts/` |
| Red | Stellar **Testnet** (Protocol 27) + Soroban RPC | Horizon + RPC público |
| Token | USDC de **Blend** testnet vía **Stellar Asset Contract (SAC)** | `USDC:GATALTGT…`, SAC `CAQCFVLO…RCJU` — no es un asset propio; se fondea con el faucet de Blend |
| Yield | Vault **DeFindex** (desplegado) → **Blend v2 directo tras `YieldAdapter`** (F1) | ver §12 |
| App | Android nativo, Kotlin + Jetpack Compose | minSdk 26, target 35, Material 3 |
| DI | Hilt (Dagger) + KSP | módulo `DataModule` |
| SDK Stellar | **kmp-stellar-sdk** (Soneso) | 1.6.0 — Horizon, Soroban RPC, `ContractClient`, SEP-05 |
| Mapas | Mapbox Maps SDK + maps-compose | 11.x |
| Wallet | **Passkey WebAuthn (smart account `C…`)** + fallback seed **BIP-39 / SEP-05** | `OZSmartAccountKit` de Soneso; operativo end-to-end en dispositivo |
| Concurrencia | Coroutines + StateFlow | MVVM por pantalla |
| Async stream | Ktor (CIO) para friendbot/Horizon | ver gotcha TLS |

---

## 2. Arquitectura general

```
┌─────────────────────────────────────────────────────────────────┐
│                        App Android (Kotlin)                       │
│                                                                   │
│  UI (Compose) ── ViewModel (StateFlow) ── Repositorio/data layer  │
│                                              │                    │
│   ┌──────────────────────────────────────────┼─────────────────┐ │
│   │ WalletManager   SorobanClient   HorizonStream   RoleResolver│ │
│   │  (claves)        (4 contratos)   (balances/      (rol on-    │ │
│   │                                   friendbot)      chain)     │ │
│   └──────────────────────────────────────────┼─────────────────┘ │
└───────────────────────────────────────────────┼─────────────────┘
              firma tx │                         │ simula (read)
                       ▼                         ▼
            ┌───────────────────┐      ┌───────────────────┐
            │   Soroban RPC      │      │      Horizon       │
            └─────────┬─────────┘      └─────────┬─────────┘
                      │                          │
        ┌─────────────┴──────────────────────────┴───────────┐
        │              Stellar Testnet (ledger)               │
        │                                                     │
        │   Pool ──accrue_points──▶ Rewards                   │
        │    │ ▲                                              │
        │    │ └──withdraw_to── Treasury ──tally/mark──▶ Gov  │
        │    ▼                                                │
        │   USDC SAC (token)                                  │
        └─────────────────────────────────────────────────────┘
```

**4 contratos Soroban** (deploy vigente: 2026-06-29T23:12:24Z):

| Contrato | Address (testnet) | Rol |
|---|---|---|
| `pool` | `CAKYU5HW…N2FK` | Pagos + custodia del Tip Barrio + índice de comercios y barrios |
| `governance` | `CAENXDX7…7PVE` | Tokens soulbound + propuestas + votación |
| `treasury` | `CDGGFSV7…GPXA` | Ejecución trustless de propuestas aprobadas |
| `rewards` | `CD5OET7F…6PPT` | Puntos no transferibles + catálogo de premios |
| USDC SAC | `CAQCFVLO…RCJU` | SAC del USDC de **Blend** testnet (issuer `GATALTGT…`) |
| vault DeFindex | `CBMVK2JK…DWHN` | Fuente de yield actual (muere con F1, ver §12) |
| admin | `GBLS7PL5…CYC2P` | `raiz-admin`, protocol_fee_bps = 50 |

> **La fuente canónica es `deployments.json`** — estos IDs cambian con cada
> re-deploy (el de F1 es inminente). Tras cada deploy el JSON se copia
> manualmente a `android/app/src/main/assets/`.

**Grafo de dependencias entre contratos:**
- `Pool` → llama `Rewards.accrue_points` (cross-contract, vía `contractimport!`) y a la fuente de yield — hoy el vault DeFindex, en F1 el `yield_adapter` — vía `#[contractclient]` declarado a mano.
- `Treasury` → llama `Governance.tally`, `Governance.get_proposal`, `Governance.mark_executed`, y `Pool.withdraw_to` / `Pool.get_vault_shares` / `Pool.redeem_from_vault` (vía `#[contractclient]` declarado a mano).
- `Governance` y `Rewards` no llaman a nadie (son llamados).

---

## 3. Convenciones de datos (críticas)

- **Montos USDC:** siempre `i128` en **stroops** (7 decimales). `1 USDC = 10_000_000 stroops`. Nunca floats.
- **Basis points (bps):** `tip_bps` y `protocol_fee_bps`. `200 = 2%`, `10_000 = 100%`. Cálculo en orden `amount * bps / 10_000` para no perder precisión con enteros.
- **`barrio_id`:** `BytesN<32>` en Rust ↔ hex de 64 chars (String) en Kotlin.
- **Direcciones:** `G…` cuentas, `C…` contratos. Siempre String en Kotlin.
- **Coordenadas:** `lat_e6` / `lng_e6` como `i32` (grados × 1e6) para evitar floats en Soroban.
- **Puntos:** `u64`, no transferibles, `1 punto = 0.01 USDC de tip = 100_000 stroops`.
- **Storage Soroban:** `instance()` para config global (admin, tokens, fee), `persistent()` para datos de negocio (barrios, comercios, propuestas, puntos).

---

## 4. Los 4 contratos en detalle

### 4.1 Pool (`contracts/pool/src/lib.rs`) — el corazón

**Structs almacenados:**
```rust
struct BarrioData {
    id: BytesN<32>, name: String,
    pool_balance: i128,        // saldo actual custodiado
    total_collected: i128,     // histórico acumulado de tips
    tx_count: u64,
    unique_tourists: u32,
    treasury_contract: Address // quién puede retirar de este pool
}
struct MerchantData {
    address: Address, name: String, barrio_id: BytesN<32>,
    verified: bool, lat_e6: i32, lng_e6: i32, category: Symbol
}
```

**Claves de storage (`DataKey`):** `Admin`, `UsdcToken`, `RewardsContract`,
`ProtocolFeeBps`, `DefindexVault` (instance); `Barrio(id)`, `Merchant(addr)`,
`BarrioMerchants(id)` (índice para el mapa), `TouristSeen(barrio, tourist)`
(para contar turistas únicos), `VaultShares(id)` (shares de yield por barrio),
`AllBarrios` (índice global para RBAC dinámico) (persistent).

**Funciones:**

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(admin, usdc, rewards, fee_bps, defindex_vault)` | `admin` | Una sola vez. Guarda config (en F1 el 5.º parámetro pasa a ser el `yield_adapter`). |
| `register_barrio(id, name, treasury)` | admin | Crea un barrio + índice de comercios vacío; lo añade a `AllBarrios`. |
| `register_merchant(data)` | admin | Registra/verifica comercio; lo añade al índice del barrio. Falla `BarrioNotFound` si el barrio no existe. |
| `pay_merchant(tourist, merchant, amount, tip_bps)` | `tourist` | **Núcleo.** Ver pipeline §6.1. |
| `withdraw_to(caller, barrio, recipient, amount)` | `caller` | Solo el `treasury_contract` registrado del barrio puede retirar. Usado por Treasury. |
| `set_defindex_vault(admin, vault)` | admin | Cambia la fuente de yield en caliente (en F1: `set_yield_adapter`). |
| `deposit_idle_to_vault(caller, barrio, amount)` | admin o treasury del barrio | Deposita fondo ocioso en la fuente de yield. Ver §12. |
| `redeem_from_vault(caller, barrio, shares)` | admin o treasury del barrio | Rescata shares al `pool_balance` (realiza el yield). Ver §12. |
| `get_pool_balance / get_barrio / get_merchant / list_merchants / list_barrios / get_vault_shares / get_vault_value` | — | Lecturas. |

**Eventos:** `payment` → topics `(symbol_short!("payment"), barrio_id)`, data `(tourist, merchant, amount, tip)`; `vault_dep` → `(amount, shares)`; `vault_red` → `(shares, got)`.

**Errores:** `NotInitialized(1)`, `AlreadyInitialized(2)`, `Unauthorized(3)`,
`MerchantNotFound(4)`, `MerchantNotVerified(5)`, `BarrioNotFound(6)`,
`InvalidAmount(7)`, `InvalidTipBps(8)`.

### 4.2 Governance (`contracts/governance/src/lib.rs`) — democracia del barrio

**Soulbound por diseño:** el struct `ResidentToken { resident, barrio_id,
issued_at }` **no tiene** función `transfer` ni campo `transferable`. Un residente
nunca puede ceder su voto. Re-mint al mismo `Address` falla con `AlreadyResident`.

```rust
enum ProposalStatus { Active, Passed, Rejected, Executed }
struct Proposal {
    id, barrio_id, proposer, description, amount, recipient,
    votes_for, votes_against, created_at, closes_at, status
}
```

**Constantes:** `MIN_DURATION_DAYS=3`, `MAX_DURATION_DAYS=14`, `QUORUM_PCT=30`, `SECONDS_PER_DAY=86_400`.

**Jerarquía de admin (dos niveles):**
- `ProtocolAdmin` (global) — registra admins de barrio.
- `Admin(barrio_id)` — el admin de cada barrio, único que puede mintear residentes.

**Funciones:**

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(protocol_admin, treasury)` | protocol_admin | Una vez. |
| `set_barrio_admin(barrio, admin)` | protocol_admin | Asigna admin de barrio + inicializa `ResidentCount`. |
| `mint_resident(barrio_admin, resident, barrio)` | barrio_admin | Mintea soulbound. Verifica que el caller sea el admin del barrio. No re-mint. Incrementa `ResidentCount`. |
| `create_proposal(proposer, barrio, desc, amount, recipient, days)` | proposer | Solo residente **de ese barrio**. Valida `amount>0` y `3≤days≤14`. |
| `vote(resident, proposal_id, support)` | resident | Residente del barrio de la propuesta. Rechaza doble voto (`Vote(pid, addr)`), propuesta cerrada o no-activa. |
| `tally(proposal_id)` | — (cualquiera) | **Idempotente.** Si `now < closes_at` → `Active`. Si cerró: quórum `(for+against)*100 ≥ 30*resident_count` y mayoría `for > against` → `Passed`/`Rejected`. Persiste el status. |
| `mark_executed(treasury_caller, proposal_id)` | treasury | Solo el contrato Treasury. Pasa `Passed`→`Executed`. |
| `get_proposal / get_resident / get_resident_count / list_active_proposals` | — | Lecturas. |

**Eventos:** `resident`, `proposal`, `vote`, `tally`.

### 4.3 Treasury (`contracts/treasury/src/lib.rs`) — ejecución trustless

No custodia fondos: **orquesta**. `execute_proposal` puede llamarlo **cualquiera**
(no requiere rol). La confianza está en que el contrato verifica el estado en
Governance antes de mover nada.

```rust
struct Execution {
    proposal_id, barrio_id, amount, recipient,
    executed_at, tx_hash  // sha256(proposal_id || barrio_id || executed_at)
}
```

**`execute_proposal(proposal_id)` — pipeline:**
1. `governance.tally(id)` → si **no** es `Passed` → error `ProposalNotPassed(3)`.
2. `governance.get_proposal(id)` → carga amount/recipient/barrio.
3. Si `pool.get_vault_shares(barrio) > 0` → `pool.redeem_from_vault` con **todas**
   las shares del barrio (rescata el yield al `pool_balance` antes del retiro).
4. `pool.withdraw_to(treasury, barrio, recipient, amount)` → mueve USDC del pool.
5. Registra `Execution` + contador global e índice por barrio.
6. `governance.mark_executed(treasury, id)` → status `Executed`.
7. Emite evento `execution`.

**Clientes cross-contract** declarados a mano con `#[contractclient]`:
`GovernanceClient` (tally, get_proposal, mark_executed) y `PoolClient`
(withdraw_to, get_vault_shares, redeem_from_vault). Hay que mantenerlos en sync
con las firmas reales — el `spec-auditor` lo vigila.

**Lecturas:** `get_execution_log(barrio)`, `get_execution(id)`, `get_execution_count(barrio)`.

### 4.4 Rewards (`contracts/rewards/src/lib.rs`) — puntos + premios

```rust
struct Reward { id, barrio_id, name, artisan, points_cost, stock, image_ref }
struct Redemption { id, tourist, reward_id, redeemed_at, claimed }
```

`POINTS_PER_STROOP_DIVISOR = 100_000` → `puntos = tip_stroops / 100_000`.

**Funciones:**

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(admin, pool)` | admin | Una vez. Guarda quién es el Pool autorizado. |
| `register_reward(barrio, name, artisan, cost, stock, img)` | admin | Alta de artesanía. `cost>0`, `stock>0`. |
| `accrue_points(caller_pool, tourist, tip)` | caller_pool | **Solo el Pool registrado** (`caller_pool == stored_pool`). Suma puntos. tip≤0 = no-op. |
| `redeem(tourist, reward_id)` | tourist | **Atómico:** quema puntos → decrementa stock → crea `Redemption`. Errores: `InsufficientPoints(5)`, `OutOfStock(6)`. |
| `claim_redemption(artisan, redemption_id)` | artisan | Solo el artesano dueño del reward marca `claimed=true`. |
| `get_points / get_reward / get_redemption / list_rewards` | — | Lecturas. |

**Eventos:** `redeem`, `claim`.

**Punto fino de seguridad cross-contract:** el Pool llama
`rewards.accrue_points(self_address, tourist, tip)` pasándose a sí mismo como
`caller_pool`. Soroban autoriza automáticamente cuando un contrato se referencia
con `env.current_contract_address()`, y Rewards valida `caller_pool ==
stored_pool`. Así **ningún otro contrato/cuenta puede inflar puntos**.

---

## 5. Modelo de autorización (quién puede llamar qué)

Cada escritura usa `require_auth()` sobre la Address responsable:

| Acción | Firma exigida | Verificación extra |
|---|---|---|
| `pay_merchant` | turista | comercio existe y `verified` |
| `register_merchant` / `register_barrio` | admin Pool | barrio existe |
| `withdraw_to` | treasury del barrio | `caller == barrio.treasury_contract` |
| `mint_resident` | admin del barrio | `barrio_admin == Admin(barrio)` |
| `create_proposal` / `vote` | residente | token soulbound del **mismo** barrio |
| `tally` | nadie (público) | calcula sobre estado on-chain |
| `execute_proposal` | nadie (público) | Governance debe decir `Passed` |
| `mark_executed` | treasury | `caller == TreasuryContract` |
| `accrue_points` | el propio Pool | `caller == PoolContract` |
| `redeem` | turista | puntos y stock suficientes |
| `claim_redemption` | artesano | `reward.artisan == artisan` |

Las **lecturas** se hacen por simulación con `signer = null` y `source =
admin` (cuenta que existe en testnet) — no gastan gas ni firman.

---

## 6. Flujos / pipelines completos

### 6.1 Pago con Tip Barrio (el flujo central)

`PayViewModel` → `SorobanClient.payMerchant(tourist, merchant, amount, tipBps)`
→ contrato `Pool.pay_merchant`:

```
tourist.require_auth()
validar amount>0, tip_bps≤10_000
cargar merchant (debe existir y estar verified)
cargar barrio del merchant

tip      = amount * tip_bps / 10_000
fee      = amount * protocol_fee_bps / 10_000     (50 bps = 0.5%)
to_merch = amount - fee

USDC.transfer(tourist → merchant, to_merch)       # 1
USDC.transfer(tourist → pool,     tip)             # 2  (si tip>0)
USDC.transfer(tourist → admin,    fee)             # 3  (si fee>0)

barrio.pool_balance += tip; total_collected += tip; tx_count++
si turista nuevo en el barrio: unique_tourists++

Rewards.accrue_points(pool_self, tourist, tip)     # cross-contract
emitir evento payment(tourist, merchant, amount, tip)
```

> **Nota de diseño:** el comercio recibe `amount - fee`; el tip es **adicional**
> al `amount` (se transfiere aparte desde el turista al pool). El turista paga
> `to_merchant + tip + fee` en total. El desglose se muestra en `PayScreen`.

### 6.2 Gobernanza → ejecución trustless

```
(admin barrio) mint_resident ─────────────▶ residentes con token soulbound
        │
(residente) create_proposal(amount, recipient, days) ─▶ Proposal Active
        │
(residentes) vote(support) ──────────────▶ votes_for / votes_against
        │   ... pasa closes_at ...
(cualquiera) tally ──▶ quórum 30% + mayoría simple ─▶ Passed | Rejected
        │
(cualquiera) Treasury.execute_proposal ─▶ tally Passed?
        │                                 ├─ Pool.withdraw_to(recipient, amount)
        │                                 ├─ registra Execution + tx_hash
        │                                 └─ Governance.mark_executed
        ▼
   evento execution  ──▶  Dashboard de transparencia
```

En la app: `ProfileViewModel` (voto del residente) + `DashboardViewModel`
(`tally` + `executeProposal` + `getExecutionLog`).

### 6.3 Canje de puntos

`RewardsViewModel` → `listRewards(barrio)` para el catálogo →
`SorobanClient.redeem(tourist, rewardId)` → contrato quema puntos, baja stock,
crea `Redemption`, emite `redeem`. El artesano luego hace `claim_redemption`.

### 6.4 Onboarding on-chain de wallet nueva (3 pasos)

`WalletViewModel.refreshSetupStep()` detecta el paso pendiente y muestra un
banner. Cada acción re-chequea tras 1.5 s:

```
accountExists?  ── no ──▶ FUND_XLM        → friendbot fondea XLM (testnet)
   │ sí
hasUsdcTrustline? ─ no ─▶ ACTIVATE_TRUST  → ChangeTrust USDC firmado por el user
   │ sí
getUsdcBalance==0? ─ sí ▶ REQUEST_USDC    → admin envía 20 USDC (faucet demo)
   │ no
   ▼ DONE (banner oculto)
```

Implementado en `HorizonStream`: `accountExists`, `fundWithFriendbot`,
`enableUsdcTrustline`, `sendUsdcFromAdmin`, `getUsdcBalance`. El faucet
(`sendUsdcFromAdmin`) es un **Payment classic** firmado por el admin — **simula
un on-ramp SEP-24**, no es producción.

### 6.5 Alta de comercio ("Soy comerciante")

`BecomeMerchantViewModel.submit()` → `SorobanClient.registerMerchant(admin, …)`
→ `Pool.register_merchant`. Hereda lat/lng del barrio elegido + jitter ~20 m.
Tras éxito invalida `RoleResolver` y el usuario pasa a `MERCHANT`.

> **Atajo de demo:** la app firma con el `demoAdminKeyPair` (clave del admin
> embebida vía `BuildConfig.DEMO_ADMIN_SECRET`). En producción esto pasaría por
> un flujo de aprobación del admin del barrio o KYC SEP-12 — un usuario no
> debería poder auto-verificarse.

---

## 7. Capa Android en detalle

**Arquitectura:** MVVM + Hilt. Una pantalla = `Screen` (Compose) + `ViewModel`
(StateFlow) + acceso al *data layer*. **Sin repositorio intermedio formal**: los
ViewModels usan directamente los 4 servicios singleton.

**Data layer (`data/stellar/`):**

| Clase | Responsabilidad |
|---|---|
| `SorobanClient` | Fachada de los 4 contratos. Lecturas con `signer=null` (simulación), escrituras firmadas. Cachea un `ContractClient` por contrato (cada uno cuesta 2 round-trips al construirse). |
| `HorizonStream` | Balances USDC/XLM (polling + `distinctUntilChanged`), trustlines, friendbot, faucet. |
| `WalletManager` | Custodia de claves. Prioridad: seed guardada > passkey (contractId `C…`) > demo (`BuildConfig`) > placeholder. |
| `PasskeyWalletManager` | Smart wallets WebAuthn/secp256r1 sobre `OZSmartAccountKit` (kit OpenZeppelin de Soneso). Crea la smart account `C…` y firma con la passkey del dispositivo; usa el relayer/indexer públicos de Soneso testnet. Requiere Activity y API ≥ 28. |
| `DefindexClient` | Cliente del vault de yield DeFindex: TVL / precio-por-share / posición (on-chain) + APY REST opcional. En F1 se sustituye por `BlendClient` (ver §12). |
| `SecureWalletStore` | Persistencia cifrada de la seed phrase en el dispositivo. |
| `RoleResolver` | Deriva el rol on-chain: residente (`getResident`) → comerciante (`listMerchants` en los 3 barrios) → turista. Cachea por address. |
| `ScvalParse` | Parsea SCVal Map → tipos Kotlin con type-safety (`asLong`, `asStruct`, `asAddressString`, `asHex`, `asEnumSymbol`, …). |
| `DeploymentsLoader` | Carga `deployments.json` desde assets. |

**Modelos (`data/model/`):** espejo Kotlin de los structs Rust (`Barrio`,
`Merchant`, `Proposal`, `Reward`, `Execution`, `ResidentToken`), con helpers
`.toUsdc()` / `.toStroops()` y `RaizConstants` (RPC URL, divisores).

**21 pantallas (`ui/`)** en grupos: onboarding/auth (welcome, registro
passkey/seed, login, import, elección de rol), roles (become_resident,
become_merchant con Mapbox), núcleo (Wallet con RAÍZ Passport, Pay, Rewards,
BarrioMap con Mapbox, Profile), gobernanza (proposals + crear propuesta),
comercio (cobros), público sin login (Dashboard de transparencia → Yield) y
LockScreen biométrico. El bottom nav cambia según el rol resuelto on-chain por
`RoleResolver`.

**Conversión de tipos en llamadas:** los `u32`/`u64` del contrato se mandan como
`UInt`/`ULong` Kotlin (`tipBps.toUInt()`, `proposalId.toULong()`); los `i128` de
montos como `Long` stroops; `BytesN<32>` como `ByteArray` de 32 (helper
`hexToBytes`).

---

## 8. Wallet, claves y SEP

- **Implementado — passkey (WebAuthn):** smart wallets secp256r1 vía
  `PasskeyWalletManager` sobre el kit OpenZeppelin de Soneso
  (`OZSmartAccountKit`). La wallet es un contrato `C…` (smart account); la firma
  la hace la passkey del dispositivo y la transacción viaja por el relayer
  público de Soneso testnet. **Operativo end-to-end, verificado en dispositivo
  real**: crear smart wallet, pagar con tip, votar, crear propuesta, faucet y
  saldo vía SAC. Requiere Activity y API ≥ 28.
- **Implementado — fallback seed:** derivación **BIP-39 / SEP-05** (12 palabras →
  KeyPair índice 0) vía `Mnemonic` del SDK Soneso. Crear, importar y borrar
  wallet. La seed se guarda en `SecureWalletStore`.
- **No implementado (roadmap):** anchors **SEP-10** (auth), **SEP-24** (on/off
  ramp fiat↔USDC), **SEP-38** (RFQ), **SEP-12** (KYC). El faucet del admin ocupa
  el lugar del SEP-24 en la demo.
- **Claves demo:** `DEMO_TOURIST_SECRET`, `DEMO_RESIDENT_SECRET`,
  `DEMO_ADMIN_SECRET` se inyectan vía `BuildConfig` desde `local.properties`
  (no se versionan). Permiten demostrar los 3 roles sin coordinar offline. En el
  APK debug van embebidas → **no publicar como release**.

---

## 9. Despliegue

**Script:** `scripts/deploy_testnet.sh`. Orden importante por las dependencias:

1. Asegura identidad `raiz-admin` (genera + fondea si falta).
2. Compila wasm. **Doble target:** `cargo build --target wasm32-unknown-unknown -p rewards`
   (para que el `contractimport!` del Pool encuentre el wasm de Rewards) y luego
   `stellar contract build` (target **`wasm32v1-none`**, el único que acepta el
   host de Soroban — `wasm32-unknown-unknown` emite `reference-types` que el host
   rechaza).
3. Referencia el USDC SAC de **Blend** testnet (no despliega un token propio) y
   despliega los 4 contratos.
4. `initialize` en orden: Rewards y Pool referencian sus dependencias, Treasury
   apunta a Pool+Governance, Governance apunta a Treasury.
5. Guarda IDs en `deployments.json` (versionado, y copiado a assets de la app).

**Seed:** `scripts/seed_testnet.sh` puebla 3 barrios (Centro Histórico, Barrio
Norte, Costa Vieja), 9 comercios, 9 residentes soulbound, pagos con tip,
propuestas, votos, 6 premios y un depósito de yield demo. El turista demo se
fondea con el **faucet de Blend** (el admin no puede acuñar ese USDC). Ambos
scripts reintentan cada operación — testnet es flaky en ráfaga.

**`deployments.json` actual:** network testnet, fee 50 bps, deployed
2026-06-29T23:12:24Z. Tras cada deploy el JSON se copia manualmente a
`android/app/src/main/assets/` (paso documentado en `DeploymentsLoader.kt`).

---

## 10. Estado real, limitaciones y roadmap

**Verificado end-to-end en testnet (dispositivos reales):**
- Lectura de pool balance y balances USDC vía Horizon.
- Pago con Tip Barrio + acumulación de puntos.
- Onboarding de wallet nueva (friendbot → trustline → faucet 20 USDC).
- Alta de comercio on-chain (ej. "SalsonBacano" en Barrio Norte, `get_merchant` lo lee de vuelta).
- **Passkey end-to-end**: crear smart wallet WebAuthn, pagar con tip, votar,
  crear propuesta, faucet y saldo.
- Gobernanza in-app (propuesta → voto → tally → ejecución) + dashboard de transparencia.
- Yield: `deposit_idle_to_vault` / `redeem_from_vault` cross-contract y depósito
  desde la app (verificado 2026-06-29).
- **58/58 tests de contratos en verde** (CI en GitHub Actions).

**Limitaciones conocidas:**
- **Clave admin embebida en el APK** (vía `BuildConfig`, texto plano en el dex):
  junto con el KYC mock, una de las **2 limitaciones grandes**. Su eliminación
  es exactamente la fase **F3** (custodia enjambre + atestación vecinal); como
  quick-win está **en curso** la migración del admin a multisig 2-de-3
  (`scripts/setup_admin_multisig.sh`).
- **KYC mock:** el admin mintea residentes y verifica comercios a mano (la otra
  limitación grande; también la elimina F3 vía atestación vecinal).
- **Faucet ≠ anchor:** el on-ramp es un Payment del admin, no SEP-24 real.
- **`tx_hash` de Execution** es un sha256 determinístico, no el hash de la tx de Stellar.
- **rpId del passkey:** para mainnet-readiness falta consolidar dominio propio +
  `assetlinks.json` (en github.io el rpId choca con la Public Suffix List).
- **TLS en algunos OEMs** (ej. Vivo): el cert `*.stellar.org` (Sectigo) no siempre
  valida; Conscrypt instalado en `RaizApplication` lo resuelve en la mayoría.
- **Acoplamiento de versiones** Kotlin/KSP/Hilt con el SDK Stellar 1.6.0 (metadata Kotlin 2.2).
- **JDK:** compilar con JDK 17/21 (no 25; AGP no lo soporta — usar el JBR de Android Studio).

**Roadmap — F1–F6 (canónico):**

El roadmap canónico vive en `docs/NuevaPropuesta/propuesta_raiz_ahorro_enjambre.md`
§8 y en el plan mes a mes `docs/NuevaPropuesta/plan_trabajo_raiz.md`
(agosto 2026 → enero 2027). Resumen de las 6 fases:

| Fase | Qué | Estado |
|---|---|---|
| **F1 — Blend directo + `YieldAdapter`** | Crate `yield_adapter` + `BlendAdapter`, `BlendClient` en la app, fuera DeFindex y su API key; migración soroban-sdk 22.x → 26.1.1; quick-win multisig 2-de-3 | **En curso** |
| **F2 — Cadena de Barrio** | Contrato `savings_circle` (tandas): cuotas, sorteo commit-reveal, reputación soulbound, yield del bote vía YieldAdapter | Siguiente (1–2 meses) |
| **F3 — Custodia enjambre + atestación vecinal** | Smart account comunal (passkeys + policies, P27) + contrato `attestation` — elimina la clave admin y el KYC mock | Planificada (candidata SCF) |
| **F4 — Metas + retos + sorteo** | `goal_vault` sobre la infra de F2; producto de ahorro completo | Planificada |
| **F5 — Enjambre frontera** | Mesh store-and-forward, `stellar-light-verify`, `swarm_rewards`, piloto FROST | Pista paralela (investigación) |
| **F6 — Capa ZK: voto secreto** | Verificador Groth16/BN254 en Soroban + Semaphore v4 (`vote_private`) | Pista paralela (tras F2 estable) |

Los pendientes de largo plazo del roadmap anterior (anchors SEP-10/24/38, KYC
SEP-12, IPFS para imágenes de premios, `tx_hash` real, mainnet) siguen vigentes
como lista, pero la secuencia de trabajo es la de F1–F6.

---

## 11. Mapa rápido archivo → responsabilidad

```
contracts/
  pool/src/lib.rs         → pagos, tip split, pool, comercios, yield (vault), list_barrios
  governance/src/lib.rs   → soulbound, propuestas, voto, tally, quórum
  treasury/src/lib.rs     → execute_proposal trustless (+ rescate de yield), log de ejecuciones
  rewards/src/lib.rs       → puntos, premios, redeem, claim

android/app/.../data/stellar/
  SorobanClient.kt        → fachada de los 4 contratos (read sim + write firmado)
  HorizonStream.kt        → balances, trustline, friendbot, faucet
  WalletManager.kt        → claves (seed BIP-39, passkey, demo keys)
  PasskeyWalletManager.kt → smart wallets WebAuthn (OZSmartAccountKit)
  DefindexClient.kt       → cliente del vault de yield (F1: → BlendClient)
  RoleResolver.kt         → rol on-chain (resident/merchant/tourist)
  ScvalParse.kt           → SCVal → tipos Kotlin
android/app/.../ui/       → 21 pantallas Compose (onboarding, roles, núcleo,
                            gobernanza, cobros, dashboard → yield)
scripts/deploy_testnet.sh → despliegue
scripts/seed_testnet.sh   → datos demo
deployments.json          → IDs de contratos en testnet (fuente canónica; copiar a assets)
```

---

## 12. Yield del fondo — F1: Blend directo tras `YieldAdapter` (en implementación)

> **Estado honesto al cierre de esta revisión (2026-07-31):** los contratos están
> en migración a soroban-sdk 26.1.1 y el adapter está en implementación;
> **DeFindex sigue siendo lo desplegado en testnet hasta el re-deploy F1.**
> Spec completa: `docs/raiz_v2_spec_contratos.md`, "Contrato 5: `yield_adapter`".

### 12.1 El diseño F1

El fondo ocioso del barrio pasa a rendir **prestándose directo en Blend v2**
(pool USDC TestnetV2), sin intermediario ni API key. El Pool no conoce a Blend:
conoce la interfaz `YieldAdapter` — un **contrato contable por barrio**. Cambiar
de fuente de yield (RWA, renta fija, estrategia mixta) es desplegar otro adapter
y un `set_yield_adapter`, no un re-deploy de Pool.

```rust
// Interfaz estándar (solo el Pool puede llamar deposit/withdraw —
// mismo patrón de caller autorizado que Rewards.accrue_points):
deposit(caller, barrio_id, amount) -> shares
withdraw(caller, barrio_id, shares, to) -> got   // valida shares <= shares_of(barrio_id)
shares_of(barrio_id) / total_shares() / value_of(barrio_id) / apy_hint()  // lecturas puras
```

Decisiones clave del `BlendAdapter` (verificadas contra blend-contracts-v2):

- **shares ≡ bTokens de Blend** (sin capa extra de contabilidad). La posición de
  cada barrio vive en `Shares(barrio_id)` dentro del adapter — única fuente de
  verdad (Pool deja de guardar `VaultShares`).
- **Prestamista puro:** requests de Blend `Supply = 0` / `Withdraw = 1` — nunca
  SupplyCollateral, no entra al health factor (patrón del fee-vault oficial).
- **Valoración:** `value_of = shares × b_rate / 1e12` leyendo `get_reserve(usdc)`
  (escala 1e12 en Blend v2). El APY se deriva on-chain — muere la API key REST
  de DeFindex.
- **Colchón líquido en Pool:** `CushionBps` (default 2000 = **20%**, gobernable)
  — fracción del fondo del barrio que nunca se invierte, para que las
  ejecuciones del Treasury se sirvan primero del colchón.
- **`set_yield_adapter`:** cambia la fuente de yield en caliente, solo admin y
  solo con `total_shares() == 0`; a futuro, propuesta votable por los residentes.
- **Nombres y eventos de Pool CONSERVADOS:** `deposit_idle_to_vault`,
  `redeem_from_vault`, `get_vault_shares`, `get_vault_value` y los eventos
  `vault_dep`/`vault_red` mantienen su firma — "vault" pasa a significar "la
  fuente de yield tras el adapter". Treasury, la app y el dashboard de
  transparencia no cambian de ABI ni de parser de eventos.
- El adapter valida `shares <= shares_of(barrio_id)` — corrige el bug pre-F1 de
  poder rescatar shares contablemente atribuidas a otro barrio.
- Cliente Blend declarado a mano (`#[contractclient]` + structs espejo), no
  `blend-contract-sdk` (su última versión apunta a soroban-sdk 25 y chocaría con
  26.1.1). Direcciones Blend V2 testnet como parámetros de deploy, no
  hardcodeadas en el contrato.

### 12.2 Lo desplegado hoy (legado DeFindex, hasta el re-deploy F1)

Lo que corre en testnet es la integración con el **vault USDC de DeFindex**
(`CBMVK2JK…DWHN`, PaltaLabs, auditado por OtterSec), en dos niveles:

- **Camino A (contratos, cross-contract):** `Pool.deposit_idle_to_vault` /
  `Pool.redeem_from_vault` mueven el fondo del barrio al vault y de vuelta
  (con `authorize_as_current_contract` para el `transfer` anidado);
  `Treasury.execute_proposal` rescata las shares del barrio antes de pagar.
  **Verificado on-chain el 2026-06-29** (el depósito desde la app movió la
  posición de tesorería 90→100 USDC).
- **Camino B (app):** pantalla **"Tesorería que rinde"** (`ui/treasury/`) +
  `DefindexClient.kt`. Lee precio-por-share / TVL / posición del vault (on-chain,
  vía `total_supply` + `fetch_total_managed_funds` — NO
  `get_asset_amounts_per_shares`, que el host trata como write) y permite
  depositar/rescatar firmando como tesorería; APY en vivo opcional vía REST
  (`api.defindex.io`, `BuildConfig.DEFINDEX_API_KEY`). Todo esto se sustituye
  por `BlendClient` en F1.
- **USDC:** el vault solo acepta el USDC de **Blend** testnet
  (`USDC:GATALTGT…`, SAC `CAQCFVLO…RCJU`), así que los contratos custodian ESE
  USDC, no uno propio — y eso **se conserva en F1** (Blend directo usa el mismo
  asset). Las cuentas se fondean con el faucet de Blend; el admin no puede
  acuñarlo. `deployments.json` registra `usdc_issuer`, `defindex_vault` y
  `defindex_usdc` (los dos últimos desaparecen con el re-deploy F1).
