# RAÍZ — Documento técnico completo

> Fuente de verdad del **estado real implementado** (no de la visión). Todo lo
> aquí descrito está leído directamente del código en `contracts/` y `android/`
> y verificado contra el despliegue en `deployments.json` (Stellar Testnet).
> Última revisión: 2026-05-28.

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
| Propuestas, votos, quórum, tally, ejecución | Wallet passkey (stub; solo funciona seed phrase BIP-39) |
| Canje de puntos por premios + claim del artesano | `tx_hash` de la `Execution` (sha256 determinístico, no el hash real de Stellar) |
| Log de ejecuciones auditable | — |

La app Android es un **cliente delgado**: lee por simulación vía Soroban RPC (sin
firmar) y escribe enviando transacciones firmadas con la clave del usuario. **No
hay backend propio**: no existe servidor de RAÍZ, todo el estado vive en los 4
contratos y en Horizon.

---

## 1. Stack tecnológico

| Capa | Tecnología | Versión / nota |
|---|---|---|
| Contratos | Rust + `soroban-sdk` | 22.x, `#![no_std]`, workspace Cargo con 4 crates |
| Red | Stellar **Testnet** + Soroban RPC | Horizon + RPC público |
| Token | USDC vía **Stellar Asset Contract (SAC)** | desplegado como `USDC:raiz-admin` |
| App | Android nativo, Kotlin + Jetpack Compose | minSdk 26, target 35, Material 3 |
| DI | Hilt (Dagger) + KSP | módulo `DataModule` |
| SDK Stellar | **kmp-stellar-sdk** (Soneso) | Horizon, Soroban RPC, `ContractClient`, SEP-05 |
| Mapas | Mapbox Maps SDK + maps-compose | 11.x |
| Wallet | Seed phrase **BIP-39 / SEP-05** (12 palabras) | passkey pendiente |
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

**4 contratos Soroban** (IDs en `deployments.json`):

| Contrato | Address (testnet) | Rol |
|---|---|---|
| `pool` | `CDA3SOHH…5NMD` | Pagos + custodia del Tip Barrio + índice de comercios |
| `governance` | `CDQHXY4A…JGMD` | Tokens soulbound + propuestas + votación |
| `treasury` | `CDUPHOCD…POTM` | Ejecución trustless de propuestas aprobadas |
| `rewards` | `CBCXDREH…CO2E` | Puntos no transferibles + catálogo de premios |
| USDC SAC | `CD6ZJGYK…GZE7` | Stellar Asset Contract del USDC de testnet |
| admin | `GBLS7PL5…CYC2P` | `raiz-admin`, protocol_fee_bps = 50 |

**Grafo de dependencias entre contratos:**
- `Pool` → llama `Rewards.accrue_points` (cross-contract, vía `contractimport!`).
- `Treasury` → llama `Governance.tally`, `Governance.get_proposal`, `Governance.mark_executed`, y `Pool.withdraw_to` (vía `#[contractclient]` declarado a mano).
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
`ProtocolFeeBps` (instance); `Barrio(id)`, `Merchant(addr)`,
`BarrioMerchants(id)` (índice para el mapa), `TouristSeen(barrio, tourist)`
(para contar turistas únicos) (persistent).

**Funciones:**

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(admin, usdc, rewards, fee_bps)` | `admin` | Una sola vez. Guarda config. |
| `register_barrio(id, name, treasury)` | admin | Crea un barrio + índice de comercios vacío. |
| `register_merchant(data)` | admin | Registra/verifica comercio; lo añade al índice del barrio. Falla `BarrioNotFound` si el barrio no existe. |
| `pay_merchant(tourist, merchant, amount, tip_bps)` | `tourist` | **Núcleo.** Ver pipeline §6.1. |
| `withdraw_to(caller, barrio, recipient, amount)` | `caller` | Solo el `treasury_contract` registrado del barrio puede retirar. Usado por Treasury. |
| `get_pool_balance / get_barrio / get_merchant / list_merchants` | — | Lecturas. |

**Eventos:** `payment` → topics `(symbol_short!("payment"), barrio_id)`, data `(tourist, merchant, amount, tip)`.

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
3. `pool.withdraw_to(treasury, barrio, recipient, amount)` → mueve USDC del pool.
4. Registra `Execution` + contador global e índice por barrio.
5. `governance.mark_executed(treasury, id)` → status `Executed`.
6. Emite evento `execution`.

**Clientes cross-contract** declarados a mano con `#[contractclient]`:
`GovernanceClient` (tally, get_proposal, mark_executed) y `PoolClient`
(withdraw_to). Hay que mantenerlos en sync con las firmas reales — el
`spec-auditor` lo vigila.

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
| `WalletManager` | Custodia de claves. Prioridad: wallet guardada > demo (`BuildConfig`) > placeholder. |
| `SecureWalletStore` | Persistencia cifrada de la seed phrase en el dispositivo. |
| `RoleResolver` | Deriva el rol on-chain: residente (`getResident`) → comerciante (`listMerchants` en los 3 barrios) → turista. Cachea por address. |
| `ScvalParse` | Parsea SCVal Map → tipos Kotlin con type-safety (`asLong`, `asStruct`, `asAddressString`, `asHex`, `asEnumSymbol`, …). |
| `DeploymentsLoader` | Carga `deployments.json` desde assets. |

**Modelos (`data/model/`):** espejo Kotlin de los structs Rust (`Barrio`,
`Merchant`, `Proposal`, `Reward`, `Execution`, `ResidentToken`), con helpers
`.toUsdc()` / `.toStroops()` y `RaizConstants` (RPC URL, divisores).

**6 pantallas (`ui/`):** Wallet (home + RAÍZ Passport), Pay, Rewards, BarrioMap
(Mapbox), Profile (roles + voto + QR), Dashboard (transparencia). Más el flujo
Welcome (crear/importar wallet) y become_merchant.

**Conversión de tipos en llamadas:** los `u32`/`u64` del contrato se mandan como
`UInt`/`ULong` Kotlin (`tipBps.toUInt()`, `proposalId.toULong()`); los `i128` de
montos como `Long` stroops; `BytesN<32>` como `ByteArray` de 32 (helper
`hexToBytes`).

---

## 8. Wallet, claves y SEP

- **Implementado:** derivación **BIP-39 / SEP-05** (12 palabras → KeyPair índice 0)
  vía `Mnemonic` del SDK Soneso. Crear, importar y borrar wallet. La seed se
  guarda en `SecureWalletStore`.
- **Stub:** `createWithPasskey()` devuelve error ("pendiente Credentials API").
  El passkey/WebAuthn está en la visión pero **no operativo**.
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
3. Despliega USDC SAC + los 4 contratos.
4. `initialize` en orden: Rewards y Pool referencian sus dependencias, Treasury
   apunta a Pool+Governance, Governance apunta a Treasury.
5. Guarda IDs en `deployments.json` (versionado, y copiado a assets de la app).

**Seed:** `scripts/seed.ts` puebla 3 barrios (Centro Histórico, Barrio Norte,
Costa Vieja), 9 comercios, residentes, premios y propuestas demo.

**`deployments.json` actual:** network testnet, fee 50 bps, deployed
2026-05-28T06:44Z.

---

## 10. Estado real, limitaciones y roadmap

**Verificado end-to-end en testnet (Motorola G04 / Android 14):**
- Lectura de pool balance y balances USDC vía Horizon.
- Pago con Tip Barrio + acumulación de puntos.
- Onboarding de wallet nueva (friendbot → trustline → faucet 20 USDC).
- Alta de comercio on-chain (ej. "SalsonBacano" en Barrio Norte, `get_merchant` lo lee de vuelta).

**Limitaciones conocidas:**
- **KYC mock:** el admin mintea residentes y verifica comercios a mano.
- **Faucet ≠ anchor:** el on-ramp es un Payment del admin, no SEP-24 real.
- **`tx_hash` de Execution** es un sha256 determinístico, no el hash de la tx de Stellar.
- **Passkey** no operativo (solo seed phrase).
- **TLS en algunos OEMs** (ej. Vivo): el cert `*.stellar.org` (Sectigo) no siempre
  valida; Conscrypt instalado en `RaizApplication` lo resuelve en la mayoría.
- **Acoplamiento de versiones** Kotlin/KSP/Hilt con el SDK Stellar 1.6.0 (metadata Kotlin 2.2).
- **JDK:** compilar con JDK 17/21 (no 25; AGP no lo soporta — usar el JBR de Android Studio).

**Roadmap (v2):**
- SEP-10/24/38 (anchors reales), SEP-12 (KYC residentes).
- Passkey/WebAuthn smart accounts.
- IPFS para imágenes de premios.
- Índice global de barrios (hoy `RoleResolver` itera los 3 del seed).
- Mainnet.

---

## 11. Mapa rápido archivo → responsabilidad

```
contracts/
  pool/src/lib.rs         → pagos, tip split, pool, comercios, list_merchants
  governance/src/lib.rs   → soulbound, propuestas, voto, tally, quórum
  treasury/src/lib.rs     → execute_proposal trustless, log de ejecuciones
  rewards/src/lib.rs       → puntos, premios, redeem, claim

android/app/.../data/stellar/
  SorobanClient.kt        → fachada de los 4 contratos (read sim + write firmado)
  HorizonStream.kt        → balances, trustline, friendbot, faucet
  WalletManager.kt        → claves (seed BIP-39, demo keys)
  RoleResolver.kt         → rol on-chain (resident/merchant/tourist)
  ScvalParse.kt           → SCVal → tipos Kotlin
android/app/.../ui/       → 7 pantallas Compose + welcome + become_merchant + treasury(Yield)
scripts/deploy_testnet.sh → despliegue
scripts/seed_testnet.sh   → datos demo
deployments.json          → IDs de contratos en testnet
```

---

## 12. Integración DeFindex (yield del fondo) — añadido en hackathon

El fondo ocioso del barrio rinde depositándolo en un **vault de DeFindex** (yield
sobre Soroban, de PaltaLabs, auditado por OtterSec). Dos niveles:

**Camino B (app):** pantalla **"Tesorería que rinde"** (`ui/treasury/`) +
`data/stellar/DefindexClient.kt`. Lee precio-por-share / TVL / posición del vault
(todo on-chain, vía `total_supply` + `fetch_total_managed_funds` — NO
`get_asset_amounts_per_shares`, que el host trata como write) y permite
depositar/rescatar firmando como tesorería. APY en vivo opcional vía REST
(`api.defindex.io`, `BuildConfig.DEFINDEX_API_KEY`).

**Camino A (contratos, cross-contract):** el contrato **Pool** deposita el fondo
del barrio en el vault y lo rescata al ejecutar propuestas:
- `Pool.deposit_idle_to_vault(caller, barrio_id, amount)` → mueve USDC del pool al
  vault (usa `authorize_as_current_contract` para el `transfer` anidado del Pool)
  y guarda `VaultShares(barrio_id)`.
- `Pool.redeem_from_vault(caller, barrio_id, shares)` → rescata del vault al pool.
- `Pool.get_vault_shares / get_vault_value` → lecturas (el Dashboard muestra
  "Fondo rindiendo en DeFindex").
- `Treasury.execute_proposal` rescata las shares del barrio antes de pagar.

**Cambio de USDC:** el vault solo acepta el USDC de **Blend** testnet
(`USDC:GATALTGT…`, SAC `CAQCFVLOBK…`), así que desde este re-deploy los contratos
custodian ESE USDC (no el USDC propio de RAÍZ). `deployments.json` añade
`usdc_issuer`, `defindex_vault`, `defindex_usdc`. Las cuentas se fondean con el
faucet de Blend (el admin no puede acuñar ese USDC).

**Verificado en testnet (2026-06-29):** `deposit_idle_to_vault` y
`redeem_from_vault` operan on-chain (cross-contract con auth anidada); el depósito
desde la app movió la posición de tesorería 90→100 USDC. 55/55 tests de contratos.
