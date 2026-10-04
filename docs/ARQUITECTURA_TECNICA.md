# RAÍZ — Documento técnico completo

> Fuente de verdad del **estado real implementado** (no de la visión). Todo lo
> aquí descrito está leído directamente del código en `contracts/` y `android/`
> y verificado contra el despliegue en `deployments.json` (Stellar Testnet).
> Última revisión: 2026-10-04 (tras F1 y el sprint SOW: D1 relayer, D2 tx real, D3 SEP-10/24).

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
| Pagos USDC turista→comercio (SAC token transfer real) | KYC de residentes (el admin mintea a petición vía `raiz-relayer`, sin validar documentos; **no** SEP-12) |
| Split del Tip Barrio y custodia del pool | On-ramp fiat→USDC: depósito SEP-24 real contra el **anchor de prueba** del SDF (D3) + faucet demo vía `raiz-relayer`; anchors de producción pendientes |
| Acumulación de puntos (cross-contract) | Imágenes de premios (URLs, no IPFS) |
| Tokens de residencia soulbound | Aprobación de comercios (la firma `raiz-relayer` server-side, sin revisión; el APK no lleva claves) |
| Propuestas, votos, quórum, tally, ejecución | Relayer/indexer del passkey (infra pública de Soneso testnet, no propia) |
| Canje de puntos por premios + claim del artesano | Enlace a la transacción real de cada `Execution`: el `tx_hash` on-chain es un ID de auditoría (sha256); el hash real lo enlaza el cliente (D2, ver §6.2) |
| Smart wallets passkey (WebAuthn/secp256r1, contrato `C…`) | — |
| Yield del fondo en Blend v2 vía `yield_adapter` (cross-contract; APY on-chain con `apy_hint`) | — |
| Log de ejecuciones auditable | — |

La app Android es un **cliente delgado**: lee por simulación vía Soroban RPC (sin
firmar) y escribe enviando transacciones firmadas con la clave del usuario. Las
operaciones de admin (alta de comercio, soulbound de residente, faucet de USDC y
depósito/rescate del vault de yield) las firma server-side **`raiz-relayer`**
(https://github.com/JuanWimmin/raiz-relayer, desplegado en
https://raiz-relayer.fly.dev/v1/health), el **único servicio propio** de RAÍZ. **Todo el
estado vive on-chain** (5 contratos + Horizon). La clave del admin vive solo en el
servidor del relayer; el APK no la lleva desde 0.2.0 (rotación de la clave: ver
`docs/evidencia_sow/d1/`). La infra del passkey usa el relayer/indexer públicos
de Soneso, no un backend de RAÍZ.

---

## 1. Stack tecnológico

| Capa | Tecnología | Versión / nota |
|---|---|---|
| Contratos | Rust + `soroban-sdk` | **26.1.1**, `#![no_std]`, workspace Cargo con 5 crates (`pool`, `governance`, `treasury`, `rewards`, `yield_adapter`) |
| Toolchain | rustc/cargo **1.97.1 pineado** (`contracts/rust-toolchain.toml`) · stellar-cli **23.2.1** | upgrade a stellar-cli 27.1.0 recomendado, pendiente |
| CI | GitHub Actions (`.github/workflows/contracts.yml`) | `cargo test --workspace` en cada push/PR que toque `contracts/` |
| Red | Stellar **Testnet** (Protocol 29) + Soroban RPC | Horizon + RPC público |
| Token | USDC de **Blend** testnet vía **Stellar Asset Contract (SAC)** | `USDC:GATALTGT…`, SAC `CAQCFVLO…RCJU` — no es un asset propio; se fondea con el faucet de Blend |
| Yield | **Blend v2 directo tras `YieldAdapter`** (desplegado 2026-07-31) | ver §12 |
| Backend admin | **`raiz-relayer`** (TypeScript + Fastify + stellar-sdk 17, Fly.io; repo aparte) | firma server-side `register_merchant` / `mint_resident` / faucet / vault; la app lo consume vía `data/relayer/RelayerClient.kt` (D1) |
| Anchor (on-ramp) | SEP-1 + SEP-10 + SEP-24 (depósito) contra `testanchor.stellar.org` | `data/anchor/AnchorClient.kt` + `ui/deposit/` (D3); solo wallets semilla `G…` |
| App | Android nativo, Kotlin + Jetpack Compose | minSdk 26, target 35, Material 3 · versión 0.3.0 (`versionCode 3`) |
| DI | Hilt (Dagger) + KSP | módulo `DataModule` |
| SDK Stellar | **kmp-stellar-sdk** (Soneso) | 1.6.0 — Horizon, Soroban RPC, `ContractClient`, SEP-05, SEP-01/10/24 |
| Mapas | Mapbox Maps SDK + maps-compose | 11.x |
| Wallet | **Passkey WebAuthn (smart account `C…`)** + fallback seed **BIP-39 / SEP-05** | `OZSmartAccountKit` de Soneso; operativo end-to-end en dispositivo |
| Concurrencia | Coroutines + StateFlow | MVVM por pantalla |
| Async stream | Ktor (CIO) para friendbot/Horizon, el relayer y el anchor | ver gotcha TLS |

---

## 2. Arquitectura general

```
┌─────────────────────────────────────────────────────────────────┐
│                        App Android (Kotlin)                       │
│                                                                   │
│  UI (Compose) ── ViewModel (StateFlow) ── data layer (singletons) │
│                                              │                    │
│   ┌──────────────────────────────────────────┼─────────────────┐ │
│   │ WalletManager   SorobanClient   HorizonStream   RoleResolver│ │
│   │  (claves)        (contratos)     (balances/      (rol on-    │ │
│   │                                   friendbot)      chain)     │ │
│   │ + RelayerClient · AnchorClient · BlendClient                │ │
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
        │    ├──deposit/withdraw──▶ yield_adapter ──▶ Blend v2│
        │    ▼                                                │
        │   USDC SAC (token)                                  │
        └─────────────────────────────────────────────────────┘

Servicios fuera del teléfono (además de RPC y Horizon):

  RelayerClient ──HTTPS──▶ raiz-relayer (Fly.io) ──tx de admin firmadas──▶ Soroban RPC
  AnchorClient  ──HTTPS──▶ testanchor.stellar.org (SEP-1/10/24) ──paga el USDC del anchor──▶ cuenta G… del usuario
```

**5 contratos Soroban** (deploy vigente: 2026-07-31T20:34:53Z — re-deploy F1; el deploy original del 29-jun quedó obsoleto):

| Contrato | Address (testnet) | Rol |
|---|---|---|
| `pool` | `CD775D33…KBE2` | Pagos + custodia del Tip Barrio + índice de comercios y barrios |
| `governance` | `CBBYI45J…AL32` | Tokens soulbound + propuestas + votación |
| `treasury` | `CACZWU3B…PATB` | Ejecución trustless de propuestas aprobadas |
| `rewards` | `CDTTEZX2…5DZJ` | Puntos no transferibles + catálogo de premios |
| `yield_adapter` | `CA5J6YVH…PJUC` | BlendAdapter: el fondo ocioso presta en el pool USDC de Blend v2 (ver §12) |
| USDC SAC | `CAQCFVLO…RCJU` | SAC del USDC de **Blend** testnet (issuer `GATALTGT…`) |
| pool Blend v2 | `CCEBVDYM…4HGF` | Destino del yield (TestnetV2) |
| admin | `GBLS7PL5…CYC2P` | `raiz-admin`, protocol_fee_bps = 50 |

> **La fuente canónica es `deployments.json`** — estos IDs cambian con cada
> re-deploy. `scripts/deploy_testnet.sh` copia el JSON a
> `android/app/src/main/assets/` al terminar; la landing lleva su propia copia en
> el objeto `DEPLOYMENTS` de cada HTML (sincronizarla a mano en cada re-deploy).

**Grafo de dependencias entre contratos:**
- `Pool` → llama `Rewards.accrue_points` y al `yield_adapter` (`deposit` / `withdraw` / `shares_of` / `total_shares` / `value_of`), ambos vía `#[contractclient]` declarado a mano (ya no hay `contractimport!` en el repo).
- `yield_adapter` → llama al pool USDC de Blend v2 (`submit`, `get_positions`, `get_reserve`, `get_config`, `claim`), también con cliente declarado a mano.
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

## 4. Los 5 contratos en detalle

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
`ProtocolFeeBps`, `YieldAdapter` (address del contrato de yield), `CushionBps`
(colchón líquido, default 2000 = 20%) (instance); `Barrio(id)`, `Merchant(addr)`,
`BarrioMerchants(id)` (índice para el mapa), `TouristSeen(barrio, tourist)`
(para contar turistas únicos), `AllBarrios` (índice global para RBAC dinámico)
(persistent). Las shares de yield por barrio **ya no viven en Pool**: las guarda
el `yield_adapter` (`Shares(barrio_id)`), única fuente de verdad.

**Funciones:**

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(admin, usdc, rewards, fee_bps, yield_adapter)` | `admin` | Una sola vez. Guarda config y fija `CushionBps` en 2000. |
| `register_barrio(id, name, treasury)` | admin | Crea un barrio + índice de comercios vacío; lo añade a `AllBarrios`. |
| `register_merchant(data)` | admin | Registra el comercio tal cual llega (el llamador envía `verified: true`); lo añade al índice del barrio. Falla `BarrioNotFound` si el barrio no existe. |
| `pay_merchant(tourist, merchant, amount, tip_bps)` | `tourist` | **Núcleo.** Ver pipeline §6.1. |
| `withdraw_to(caller, barrio, recipient, amount)` | `caller` | Solo el `treasury_contract` registrado del barrio puede retirar. Usado por Treasury. |
| `set_yield_adapter(admin, adapter)` | admin | Cambia la fuente de yield en caliente; solo si el adapter actual no tiene posiciones (`total_shares() == 0`, si no `AdapterHasPositions`). |
| `set_cushion_bps(admin, bps)` | admin | Ajusta el colchón líquido (`bps ≤ 10_000`, si no `InvalidBps`). |
| `deposit_idle_to_vault(caller, barrio, amount)` | admin o treasury del barrio | Deposita fondo ocioso en la fuente de yield respetando el colchón (`InsufficientLiquidity` si lo violaría). Ver §12. |
| `redeem_from_vault(caller, barrio, shares)` | admin o treasury del barrio | Rescata shares al `pool_balance` (realiza el yield). Ver §12. |
| `get_pool_balance / get_barrio / get_merchant / list_merchants / list_barrios / get_vault_shares / get_vault_value` | — | Lecturas (`get_vault_shares` / `get_vault_value` delegan en el adapter). |

**Eventos:** `payment` → topics `(symbol_short!("payment"), barrio_id)`, data `(tourist, merchant, amount, tip)`; `vault_dep` → `(amount, shares)`; `vault_red` → `(shares, got)`.

**Errores:** `NotInitialized(1)`, `AlreadyInitialized(2)`, `Unauthorized(3)`,
`MerchantNotFound(4)`, `MerchantNotVerified(5)`, `BarrioNotFound(6)`,
`InvalidAmount(7)`, `InvalidTipBps(8)`, `AdapterNotConfigured(9)`,
`InsufficientLiquidity(10)`, `AdapterHasPositions(11)`, `InvalidBps(12)`.

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

> El campo `tx_hash` es un **ID de auditoría** reproducible (sha256), **no** el
> hash de la transacción de Stellar: un contrato no puede leer el hash de la
> transacción que lo invoca. El enlace a la transacción real lo resuelve el
> cliente (D2, ver §6.2).

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

### 4.5 YieldAdapter (`contracts/yield_adapter/src/lib.rs`) — BlendAdapter

Puente contable **por barrio** entre el Pool y el pool USDC de Blend v2. Diseño
y decisiones en §12; aquí solo la superficie del contrato.

**Claves de storage (`DataKey`):** `Admin`, `PoolContract` (único autorizado a
`deposit`/`withdraw`), `BlendPool`, `UsdcToken`, `TotalShares` (instance);
`Shares(barrio_id)` (bTokens del barrio, persistent).

| Función | Auth | Qué hace |
|---|---|---|
| `initialize(admin, pool_contract, blend_pool, usdc_token)` | admin | Una vez. |
| `deposit(caller, barrio, amount) -> shares` | `caller` = Pool registrado | Presta en Blend el USDC que el Pool acaba de transferir al adapter; acredita los bTokens al barrio. |
| `withdraw(caller, barrio, shares, to) -> got` | `caller` = Pool registrado | Retira de Blend y envía el USDC a `to`; valida `shares ≤ shares_of(barrio)` (`InsufficientShares`). |
| `claim_blnd(admin, to)` | admin | Reclama emisiones BLND del lado supply (no-op si no hay). |
| `shares_of / total_shares / value_of / apy_hint` | — | Lecturas puras (`value_of = shares × b_rate / 1e12`; `apy_hint` en bps). |

**Eventos:** `supply` → topics `(symbol_short!("supply"), barrio_id)`, data `(amount, shares)`; `withdrw` → `(shares, got)`.

**Errores:** `NotInitialized(1)`, `AlreadyInitialized(2)`, `Unauthorized(3)`, `InvalidAmount(4)`, `InsufficientShares(5)`.

---

## 5. Modelo de autorización (quién puede llamar qué)

Cada escritura usa `require_auth()` sobre la Address responsable:

| Acción | Firma exigida | Verificación extra |
|---|---|---|
| `pay_merchant` | turista | comercio existe y `verified` |
| `register_merchant` / `register_barrio` | admin Pool | barrio existe |
| `withdraw_to` | treasury del barrio | `caller == barrio.treasury_contract` |
| `deposit_idle_to_vault` / `redeem_from_vault` | admin o treasury del barrio | colchón líquido (`CushionBps`) en el depósito |
| `yield_adapter.deposit` / `withdraw` | el propio Pool | `caller == PoolContract` |
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

Las acciones cuya firma exigida es la del **admin** (`register_merchant`,
`mint_resident`, `deposit_idle_to_vault` / `redeem_from_vault` y el faucet de
USDC) no se firman en el teléfono: la app las pide por HTTP a `raiz-relayer`
(`data/relayer/RelayerClient.kt`), que las firma server-side. La clave del admin
vive solo en el servidor del relayer; el APK no la lleva desde 0.2.0 (rotación
de la clave: ver `docs/evidencia_sow/d1/`).

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
        │                                 ├─ rescata el yield de Blend (si hay shares)
        │                                 ├─ Pool.withdraw_to(recipient, amount)
        │                                 ├─ registra Execution + tx_hash (ID de auditoría)
        │                                 └─ Governance.mark_executed
        ▼
   evento execution  ──▶  Dashboard de transparencia
```

En la app: `ProposalsViewModel` (verificación de residente vía relayer + voto
del residente) + `DashboardViewModel` (`tally` + `executeProposal` +
`getExecutionLog` + `executionEvents`). El Dashboard ofrece "Ejecutar trustless"
en propuestas ya cerradas y aún `Active` (el Treasury hace el `tally` dentro de
`execute_proposal`).

**Enlace a la transacción real de cada ejecución (D2).** El `tx_hash` que guarda
el contrato es un ID de auditoría, así que el hash real de la transacción lo
resuelve el cliente, por `proposal_id` y en este orden:

1. **Caché local del dispositivo** (`data/local/ExecutionHashStore`): el hash que
   la app captura al firmar `execute_proposal` y los que ya llegaron por evento.
2. **Archivo versionado** `assets/execution_hashes.json` (la UI lo rotula
   "Verificada (archivo)"): cubre ejecuciones cuyo evento ya salió del RPC.
3. **Eventos `execution` del Treasury** vía `getEvents`
   (`SorobanClient.executionEvents`), solo para ejecuciones recientes sin hash
   conocido — la retención del RPC de testnet es ≈ 7 días.

Con hash real, la fila enlaza a Stellar Expert; sin él se muestra como
"histórica" y **no se inventa enlace**. La landing (`landing/index.html`, bloque
"Ejecuciones del fondo") hace la misma correlación con un snapshot + `getEvents`.

### 6.3 Canje de puntos

`RewardsViewModel` → `listRewards(barrio)` para el catálogo →
`SorobanClient.redeem(tourist, rewardId)` → contrato quema puntos, baja stock,
crea `Redemption`, emite `redeem`. El artesano luego hace `claim_redemption`.

### 6.4 Onboarding on-chain de wallet nueva (3 pasos)

`WalletViewModel.refreshSetupStep()` detecta el paso pendiente y muestra un
banner. Cada acción re-chequea tras 1.5 s:

```
accountExists?  ── no ──▶ FUND_XLM            → friendbot fondea XLM (testnet)
   │ sí
hasUsdcTrustline? ─ no ─▶ ACTIVATE_TRUSTLINE  → ChangeTrust USDC firmado por el user
   │ sí
getUsdcBalance==0? ─ sí ▶ REQUEST_USDC        → "Consigue USDC": depósito SEP-24 con el
   │ no                                         anchor de prueba, o faucet "USDC demo
   ▼ DONE (banner oculto)                       (Blend)" de 20 USDC vía raiz-relayer
```

Implementado en `HorizonStream` (`accountExists`, `fundWithFriendbot`,
`enableUsdcTrustline`, `getUsdcBalance`) y `WalletViewModel`. El paso 3 tiene dos
caminos:

- **Depósito SEP-24 (primario, D3):** pantalla "Depositar" (`ui/deposit/`) →
  `AnchorClient` (SEP-1 `stellar.toml` → SEP-10 challenge firmado con la wallet →
  SEP-24 `deposit/interactive` en una Custom Tab + polling hasta `completed`)
  contra `testanchor.stellar.org`. Solo wallets semilla `G…` (passkey espera
  SEP-45). El USDC que llega es **el del anchor de prueba** (otro emisor que el
  USDC de Blend del fondo): se muestra aparte y, para pagar en comercios, se
  convierte con "Convertir a USDC del fondo" (`PathPaymentStrictSend` firmada por
  el usuario, `HorizonStream.quoteStrictSend` / `pathPaymentStrictSend`).
- **Faucet demo (secundario):** `RelayerClient.faucet` → `POST /v1/faucet` del
  relayer, que envía 20 USDC de Blend firmando server-side (`payment` clásico a
  cuentas `G…`, `transfer` del SAC a smart accounts `C…`). Ya no existe
  `sendUsdcFromAdmin` en la app.

### 6.5 Alta de comercio ("Soy comerciante")

`BecomeMerchantViewModel.submit()` → `RelayerClient.registerMerchant(…)` →
`POST /v1/register-merchant` → `raiz-relayer` firma `Pool.register_merchant`.
Las coordenadas son las que el comerciante elige (tocando el mapa o buscando una
dirección) o, si no elige, el centro del barrio. Tras éxito invalida
`RoleResolver` y el usuario pasa a `MERCHANT`; un `409 MERCHANT_EXISTS` se trata
como éxito idempotente.

> **Atajo de demo:** el relayer aprueba el alta al instante, sin revisión. La
> app ya no firma con ninguna clave de admin (`demoAdminKeyPair` y
> `BuildConfig.DEMO_ADMIN_SECRET` se eliminaron en 0.2.0, D1). En producción
> esto pasaría por un flujo de aprobación del admin del barrio o KYC SEP-12 —
> un usuario no debería poder auto-verificarse.

---

## 7. Capa Android en detalle

**Arquitectura:** MVVM + Hilt. Una pantalla = `Screen` (Compose) + `ViewModel`
(StateFlow) + acceso al *data layer*. **Sin repositorio intermedio formal**: los
ViewModels usan directamente los servicios singleton de `data/`.

**Data layer (`data/`; salvo que se indique otro paquete, en `data/stellar/`):**

| Clase | Responsabilidad |
|---|---|
| `SorobanClient` | Fachada de los contratos RAÍZ que la app invoca directamente (Pool, Governance, Treasury, Rewards; el `yield_adapter` se alcanza a través del Pool y de `BlendClient`). Lecturas con `signer=null` (simulación), escrituras firmadas por el usuario. Cachea un `ContractClient` por contrato (cada uno cuesta 2 round-trips al construirse). Incluye `executionEvents` (eventos `execution` del Treasury vía `getEvents`, D2). |
| `HorizonStream` | Balances de cualquier asset clásico (polling + `distinctUntilChanged`), historial de pagos, trustlines, friendbot y cotización/envío de path payments (D3; aritmética en `SwapMath`). El faucet ya no vive aquí: es `RelayerClient.faucet`. |
| `RelayerClient` (`data/relayer/`) | Cliente HTTP (Ktor) de `raiz-relayer`: `registerMerchant`, `mintResident`, `faucet`, `vaultDeposit` / `vaultRedeem`, `health`. Las operaciones de admin se firman en el servidor (D1). |
| `AnchorClient` (`data/anchor/`) | SEP-1 (`stellar.toml`) + SEP-10 (JWT solo en memoria) + SEP-24 (depósito interactivo y polling) contra el anchor de prueba del SDF (D3). |
| `ExecutionHashStore` (`data/local/`) | Hash real de cada ejecución por `proposal_id`: caché local (`SharedPreferences`) + archivo versionado `assets/execution_hashes.json` (D2). |
| `WalletManager` | Custodia de claves. Prioridad: seed guardada > passkey (contractId `C…`) > demo (`BuildConfig`) > placeholder. |
| `PasskeyWalletManager` | Smart wallets WebAuthn/secp256r1 sobre `OZSmartAccountKit` (kit OpenZeppelin de Soneso). Crea la smart account `C…` y firma con la passkey del dispositivo; usa el relayer/indexer públicos de Soneso testnet. Requiere Activity y API ≥ 28. |
| `BlendClient` | Lecturas puras del yield, sin API key: `get_reserve(usdc)` del pool de Blend v2 (TVL / utilización) y `apy_hint()` del `yield_adapter` (APY estimado). La posición por barrio viene de `Pool` (`get_vault_shares` / `get_vault_value`). Ver §12. |
| `SecureWalletStore` | Persistencia cifrada de la seed phrase en el dispositivo. |
| `RoleResolver` | Deriva el rol on-chain: residente (`getResident`) → comerciante (`listBarrios` → `listMerchants` por barrio; fallback a los barrios del seed) → turista. Cachea por address. |
| `ScvalParse` | Parsea SCVal Map → tipos Kotlin con type-safety (`asLong`, `asStruct`, `asAddressString`, `asHex`, `asEnumSymbol`, …). |
| `DeploymentsLoader` | Carga `deployments.json` desde assets. |

**Modelos (`data/model/`):** espejo Kotlin de los structs Rust (`Barrio`,
`Merchant`, `Proposal`, `Reward`, `Execution`, `ResidentToken`), con helpers
`.toUsdc()` / `.toStroops()` y `RaizConstants` (RPC URL, divisores).

**21 pantallas (`*Screen.kt` en `ui/`)** en grupos: onboarding/auth (welcome,
registro passkey/seed, login, import, elección de rol), roles (become_resident,
become_merchant con Mapbox), núcleo (Wallet con RAÍZ Passport, Pay, Rewards,
BarrioMap con Mapbox, Profile), on-ramp (**Depositar**: SEP-24 + conversión al
USDC del fondo, D3), gobernanza (proposals + crear propuesta), comercio
(cobros), público sin login (Dashboard de transparencia → Yield) y LockScreen
biométrico. El bottom nav cambia según el rol resuelto on-chain por
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
- **Implementado (D3) — anchors SEP-1 / SEP-10 / SEP-24 (depósito):** contra el
  anchor de prueba del SDF (`testanchor.stellar.org`), con `kmp-stellar-sdk`
  1.6.0 (`data/anchor/AnchorClient.kt`, `ui/deposit/`). SEP-10 firma el challenge
  con la wallet semilla (JWT solo en memoria); SEP-24 abre la web interactiva en
  una Custom Tab y sondea hasta `completed`. Solo wallets semilla `G…`: las
  passkey (`C…`) esperan SEP-45. El USDC del anchor es otro activo que el USDC
  de Blend del fondo; se convierte con una `PathPaymentStrictSend` firmada por el
  usuario ("Convertir a USDC del fondo").
- **Pendiente:** **SEP-38** (quotes), retiro (off-ramp), **SEP-12** (KYC),
  **SEP-45** (auth de smart accounts) y anchors de producción. El faucet demo
  (20 USDC de Blend) sigue disponible vía `raiz-relayer` como camino secundario.
- **Claves demo:** solo `DEMO_TOURIST_SECRET` y `DEMO_RESIDENT_SECRET` (wallets
  de prueba, sin autoridad) se inyectan vía `BuildConfig` desde
  `local.properties` (no se versionan) y **solo en el build debug**: el
  `buildType` `release` las fuerza a `""`, así que el APK release no lleva
  ninguna clave privada `S…` ni modo demo. `DEMO_ADMIN_SECRET` ya no existe: la
  clave del admin vive solo en el servidor del relayer; el APK no la lleva desde
  0.2.0 (rotación de la clave: ver `docs/evidencia_sow/d1/`).

---

## 9. Despliegue

**Script:** `scripts/deploy_testnet.sh`. Orden importante por las dependencias:

1. Asegura identidad `raiz-admin` (genera + fondea si falta).
2. Compila wasm en **un solo paso**: `stellar contract build` (target
   **`wasm32v1-none`**, el único que acepta el host de Soroban —
   `wasm32-unknown-unknown` emite `reference-types` que el host rechaza). Ya no
   hay build en dos pasos: todos los cross-contract usan `#[contractclient]`
   declarado a mano.
3. Referencia el USDC SAC de **Blend** testnet (no despliega un token propio) y
   despliega los 5 contratos (Pool, Governance, Treasury, Rewards, yield_adapter).
4. `initialize` en orden: Rewards → yield_adapter → Pool → Governance →
   Treasury (cada `initialize` solo necesita la Address del otro contrato, ya
   desplegado).
5. Guarda IDs en `deployments.json` (versionado) y lo copia a
   `android/app/src/main/assets/`.

**Seed:** `scripts/seed_testnet.sh` puebla 3 barrios (Centro Histórico, Barrio
Norte, Costa Vieja), 9 comercios, 9 residentes soulbound, pagos con tip,
propuestas, votos, 6 premios y un depósito de yield demo. El turista demo se
fondea con el **faucet de Blend** (el admin no puede acuñar ese USDC). Ambos
scripts reintentan cada operación — testnet es flaky en ráfaga.

**`deployments.json` actual:** network testnet, fee 50 bps, deployed
2026-07-31T20:34:53Z (re-deploy F1). El script lo copia a
`android/app/src/main/assets/` al terminar; la landing (`landing/*.html`, objeto
`DEPLOYMENTS`) y el relayer (`/v1/health` lista los mismos IDs) se sincronizan a
mano en cada re-deploy.

**Mantenimiento on-chain (TTL):** ni Treasury ni Governance extienden el TTL de
sus entradas (`Execution(n)`, `Proposal(n)` nacen con ~7 días). Pasado ese plazo
la lectura deja de ser pura y la app falla con "Signer required for write
call"; el remedio operativo es `stellar contract restore` + `extend`
(`scripts/treasury_ttl.js` muestra el estado de cada clave del Treasury y su
XDR; detalle en `CLAUDE.md` y `docs/evidencia_sow/d2/`). Se corrige en el
próximo re-deploy (H2).

---

## 10. Estado real, limitaciones y roadmap

**Verificado end-to-end en testnet (dispositivos reales):**
- Lectura de pool balance y balances USDC vía Horizon.
- Pago con Tip Barrio + acumulación de puntos.
- Onboarding de wallet nueva (friendbot → trustline → faucet de 20 USDC vía
  relayer, o depósito SEP-24 con el anchor de prueba).
- Alta de comercio on-chain (ej. "SalsonBacano" en Barrio Norte, `get_merchant` lo lee de vuelta).
- **Passkey end-to-end**: crear smart wallet WebAuthn, pagar con tip, votar,
  crear propuesta, faucet y saldo.
- Gobernanza in-app (propuesta → voto → tally → ejecución) + dashboard de transparencia.
- Yield en **Blend v2** vía `yield_adapter`: 0,2 USDC del Centro Histórico en
  bTokens tras el re-deploy F1 (2026-07-31); depósito desde la app vía relayer
  con el colchón del 20 % comprobado, y rescate por el endpoint del relayer
  (2026-09-06, `docs/evidencia_sow/d1/regresion_dispositivo.md`).
- **Sprint SOW Instaward** (evidencia en `docs/evidencia_sow/`): **D1** los
  flujos de admin pasan por `raiz-relayer` y el APK release no lleva claves
  privadas; **D2** 8 ejecuciones del Treasury enlazadas a su transacción real
  en Stellar Expert (app y landing); **D3** depósito SEP-10 + SEP-24 completo
  desde la app contra el anchor de prueba, más la conversión al USDC del fondo.
- **85 tests de contratos en verde** (CI en GitHub Actions) + 61 tests JVM de la app.

**Limitaciones conocidas:**
- **Clave admin única:** la autoridad admin del protocolo sigue siendo una sola
  cuenta. Ya no viaja en el APK: la clave del admin vive solo en el servidor del
  relayer; el APK no la lleva desde 0.2.0 (rotación de la clave: ver
  `docs/evidencia_sow/d1/`). Junto con el KYC mock, es una de las **2
  limitaciones grandes**; su eliminación es exactamente la fase **F3** (custodia
  enjambre + atestación vecinal). Existe un script para llevar el admin a
  multisig 2-de-3 (`scripts/setup_admin_multisig.sh`).
- **KYC mock:** el admin (vía relayer) mintea residentes y verifica comercios
  sin validar documentos (la otra limitación grande; también la elimina F3 vía
  atestación vecinal).
- **Anchor de prueba ≠ anchor de producción:** el depósito SEP-24 es real en
  protocolo, pero contra `testanchor.stellar.org` (KYC simulado, sin dinero
  real) y entrega un USDC distinto al de Blend; faltan SEP-38, retiro y anchors
  reales fiat↔USDC.
- **`tx_hash` de Execution** es un ID de auditoría (sha256), no el hash de la tx
  de Stellar; el hash real se enlaza fuera del contrato (D2, §6.2). Quitar el
  campo o renombrarlo es decisión de un re-deploy futuro.
- **TTL de las entradas on-chain:** Treasury y Governance no extienden el TTL de
  `Execution(n)` / `Proposal(n)` (ver §9, "Mantenimiento on-chain").
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
| **F1 — Blend directo + `YieldAdapter`** | Crate `yield_adapter` + `BlendAdapter`, `BlendClient` en la app, fuera DeFindex y su API key; migración soroban-sdk 22.x → 26.1.1 | **Completada (2026-07-31)** |
| **F2 — Cadena de Barrio** | Contrato `savings_circle` (tandas): cuotas, sorteo commit-reveal, reputación soulbound, yield del bote vía YieldAdapter | Siguiente (en pausa hasta cerrar el SOW; solo avanza su spec) |
| **F3 — Custodia enjambre + atestación vecinal** | Smart account comunal (passkeys + policies, P27) + contrato `attestation` — elimina la clave admin y el KYC mock | Planificada (candidata SCF) |
| **F4 — Metas + retos + sorteo** | `goal_vault` sobre la infra de F2; producto de ahorro completo | Planificada |
| **F5 — Enjambre frontera** | Mesh store-and-forward, `stellar-light-verify`, `swarm_rewards`, piloto FROST | Pista paralela (investigación) |
| **F6 — Capa ZK: voto secreto** | Verificador Groth16/BN254 en Soroban + Semaphore v4 (`vote_private`) | Pista paralela (tras F2 estable) |

Entre F1 y F2 se intercaló el **sprint SOW Instaward** (agosto–octubre 2026):
D1 relayer admin, D2 transacción real por ejecución y D3 SEP-10/24 — los tres
hechos (ver "Verificado end-to-end" arriba y `docs/evidencia_sow/`).

Los pendientes de largo plazo del roadmap anterior que siguen vigentes (SEP-38,
retiro y anchors de producción, SEP-45 para passkey, KYC SEP-12, IPFS para
imágenes de premios, mainnet) quedan como lista, pero la secuencia de trabajo es
la de F1–F6.

---

## 11. Mapa rápido archivo → responsabilidad

```
contracts/
  pool/src/lib.rs          → pagos, tip split, pool, comercios, yield vía adapter (colchón), list_barrios
  governance/src/lib.rs    → soulbound, propuestas, voto, tally, quórum
  treasury/src/lib.rs      → execute_proposal trustless (+ rescate de yield), log de ejecuciones
  rewards/src/lib.rs       → puntos, premios, redeem, claim
  yield_adapter/src/lib.rs → BlendAdapter: contable por barrio hacia el pool USDC de Blend v2

android/app/.../data/stellar/
  SorobanClient.kt        → fachada de Pool/Governance/Treasury/Rewards (read sim + write firmado) + executionEvents
  HorizonStream.kt        → balances por asset, trustlines, friendbot, path payments
  SwapMath.kt             → aritmética en stroops de la conversión (D3)
  BlendClient.kt          → lecturas puras del yield (get_reserve de Blend + apy_hint del adapter)
  WalletManager.kt        → claves (seed BIP-39, passkey, demo keys solo en debug)
  PasskeyWalletManager.kt → smart wallets WebAuthn (OZSmartAccountKit)
  RoleResolver.kt         → rol on-chain (resident/merchant/tourist)
  ScvalParse.kt           → SCVal → tipos Kotlin
android/app/.../data/relayer/RelayerClient.kt   → operaciones de admin vía raiz-relayer (D1)
android/app/.../data/anchor/AnchorClient.kt     → SEP-1/10/24 contra el anchor de prueba (D3)
android/app/.../data/local/ExecutionHashStore.kt → hash real de cada ejecución (D2)
android/app/src/main/assets/execution_hashes.json → archivo versionado de hashes (D2)
android/app/.../ui/       → 21 pantallas Compose (onboarding, roles, núcleo,
                            depositar, gobernanza, cobros, dashboard → yield)
scripts/deploy_testnet.sh → despliegue (copia deployments.json a los assets de la app)
scripts/seed_testnet.sh   → datos demo
scripts/treasury_ttl.js   → estado del TTL de las entradas del Treasury (solo lectura) + claves XDR para restore/extend
deployments.json          → IDs de contratos en testnet (fuente canónica)
docs/evidencia_sow/       → evidencia del SOW Instaward (D1, D2, D3)
```

---

## 12. Yield del fondo — F1: Blend directo tras `YieldAdapter`

> **Estado (2026-10-04):** F1 está **desplegado en testnet desde el 2026-07-31**
> (soroban-sdk 26.1.1; contrato `yield_adapter` `CA5J6YVH…PJUC` contra el pool
> USDC de Blend v2 `CCEBVDYM…4HGF`). DeFindex se eliminó por completo de
> contratos, app y scripts (§12.2 lo conserva como histórico).
> Spec completa: `docs/raiz_v2_spec_contratos.md`, "Contrato 5: `yield_adapter`".

### 12.1 El diseño F1 (desplegado)

El fondo ocioso del barrio rinde **prestándose directo en Blend v2**
(pool USDC TestnetV2), sin intermediario ni API key. El Pool no conoce a Blend:
conoce la interfaz `YieldAdapter` — un **contrato contable por barrio**. Cambiar
de fuente de yield (RWA, renta fija, estrategia mixta) es desplegar otro adapter
y un `set_yield_adapter`, no un re-deploy de Pool.

```rust
// Interfaz estándar (solo el Pool puede llamar deposit/withdraw —
// mismo patrón de caller autorizado que Rewards.accrue_points):
deposit(caller, barrio_id, amount) -> shares
withdraw(caller, barrio_id, shares, to) -> got   // valida shares <= shares_of(barrio_id)
claim_blnd(admin, to) -> claimed                 // solo admin: emisiones BLND del lado supply
shares_of(barrio_id) / total_shares() / value_of(barrio_id) / apy_hint()  // lecturas puras
```

Decisiones clave del `BlendAdapter` (verificadas contra blend-contracts-v2):

- **shares ≡ bTokens de Blend** (sin capa extra de contabilidad). La posición de
  cada barrio vive en `Shares(barrio_id)` dentro del adapter — única fuente de
  verdad (Pool ya no guarda `VaultShares`).
- **Prestamista puro:** requests de Blend `Supply = 0` / `Withdraw = 1` — nunca
  SupplyCollateral, no entra al health factor (patrón del fee-vault oficial).
- **Valoración:** `value_of = shares × b_rate / 1e12` leyendo `get_reserve(usdc)`
  (escala 1e12 en Blend v2). El APY se deriva on-chain (`apy_hint`) — sin la API
  key REST que exigía DeFindex.
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

**En la app (F1):** la pantalla **"Tesorería que rinde"** (`ui/treasury/`,
`YieldViewModel`) muestra "Pool Blend v2 · USDC" con lecturas puras —
`BlendClient` (`get_reserve` del pool de Blend + `apy_hint` del adapter) y la
posición por barrio desde `Pool` (`get_vault_shares` / `get_vault_value`) — sin
API key. Depositar y rescatar ya no se firman en el teléfono: van por
`RelayerClient.vaultDeposit` / `vaultRedeem` (`POST /v1/vault/{deposit,redeem}`
de `raiz-relayer`, D1).

### 12.2 Histórico pre-F1: integración DeFindex (hasta el 2026-07-31)

> Nada de esta sección está desplegado ni vive en el código hoy: se conserva
> como registro de lo que corrió durante el hackathon y de por qué se migró.

Hasta el re-deploy F1, lo que corría en testnet era la integración con el
**vault USDC de DeFindex** (`CBMVK2JK…DWHN`, PaltaLabs, auditado por OtterSec),
en dos niveles:

- **Camino A (contratos, cross-contract):** `Pool.deposit_idle_to_vault` /
  `Pool.redeem_from_vault` movían el fondo del barrio al vault y de vuelta
  (con `authorize_as_current_contract` para el `transfer` anidado);
  `Treasury.execute_proposal` rescataba las shares del barrio antes de pagar.
  **Verificado on-chain el 2026-06-29** (el depósito desde la app movió la
  posición de tesorería 90→100 USDC).
- **Camino B (app):** pantalla "Tesorería que rinde" + `DefindexClient.kt`.
  Leía precio-por-share / TVL / posición del vault (on-chain, vía
  `total_supply` + `fetch_total_managed_funds` — NO
  `get_asset_amounts_per_shares`, que el host trata como write) y permitía
  depositar/rescatar firmando como tesorería; APY en vivo opcional vía REST
  (`api.defindex.io`, `BuildConfig.DEFINDEX_API_KEY`). En F1 se sustituyó por
  `BlendClient`; `DefindexClient` y su API key se borraron.
- **USDC:** el vault solo aceptaba el USDC de **Blend** testnet
  (`USDC:GATALTGT…`, SAC `CAQCFVLO…RCJU`), así que los contratos custodiaban ESE
  USDC, no uno propio — y eso **se conservó en F1** (Blend directo usa el mismo
  asset). Las cuentas se fondean con el faucet de Blend; el admin no puede
  acuñarlo. `deployments.json` registraba además `defindex_vault` y
  `defindex_usdc`, que desaparecieron con el re-deploy F1 (hoy: `usdc_sac`,
  `usdc_issuer` y `blend_pool`).
