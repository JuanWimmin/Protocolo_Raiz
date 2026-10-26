# RAÍZ — Documento técnico completo

> Fuente de verdad del **estado real implementado** (no de la visión). Todo lo
> aquí descrito está leído directamente del código en `contracts/` y `android/`
> y verificado contra el despliegue en `deployments.json` (Stellar Testnet).
> Última revisión: 2026-10-26 (tras F1, el sprint SOW — D1 relayer, D2 tx real, D3 SEP-10/24 — y
> HackMeridian 2026: pago diferido por enjambre, §13; rama `feat/pago-diferido`).

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
| Pago diferido por enjambre (HackMeridian 2026): la autorización de `pay_merchant` firmada **sin red** se ejecuta on-chain con nonce y expiración por ledger, enviada por cualquier cuenta con XLM (§13) | Verificación sin red, estados "en tránsito" y colas de liquidación viven en el teléfono o en el navegador de la pasarela; **no hay finalidad offline** |

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
| Anchor (on-ramp) | SEP-1 + SEP-10 + SEP-24 (depósito) contra `testanchor.stellar.org` | `data/anchor/AnchorClient.kt` + `ui/deposit/` (D3); todas las wallets (las passkey, a través de su cuenta de depósito: `data/stellar/DepositAccountManager.kt`) |
| App | Android nativo, Kotlin + Jetpack Compose | minSdk 26, target 35, Material 3 · versión 0.4.0 (`versionCode 4`) |
| DI | Hilt (Dagger) + KSP | módulo `DataModule` |
| SDK Stellar | **kmp-stellar-sdk** (Soneso) | 1.6.0 — Horizon, Soroban RPC, `ContractClient`, SEP-05, SEP-01/10/24 |
| Mapas | Mapbox Maps SDK + maps-compose | 11.x |
| Wallet | **Passkey WebAuthn (smart account `C…`)** + fallback seed **BIP-39 / SEP-05** | `OZSmartAccountKit` de Soneso; operativo end-to-end en dispositivo |
| Concurrencia | Coroutines + StateFlow | MVVM por pantalla |
| Async stream | Ktor (CIO) para friendbot/Horizon, el relayer y el anchor | ver gotcha TLS |
| Pago diferido | `SorobanAuthorizationEntry` de `pay_merchant` firmada **sin red** (credenciales legacy, preimage tipo 9) con `kmp-stellar-sdk` 1.6.0 · pasarela web estática con `@stellar/stellar-sdk` 17.0.1, `html5-qrcode` 2.3.8 y `qrcode-generator` 1.4.4 por CDN | `data/deferred/`, `landing/pasarela.html`, `scripts/pasarela_cli.mjs` (HackMeridian 2026, rama `feat/pago-diferido`; ver §13) |

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
│   │ + DeferredPaymentManager · SobreSettler (pago diferido, §13)│ │
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
  Pasarela web (cualquier navegador) ── raizapp.xyz/pasarela.html, estática, sin servidor propio ──tx con la auth del pagador──▶ Soroban RPC   (pago diferido, §13)
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
  contra `testanchor.stellar.org`. Para todas las wallets desde 0.4.0: una wallet
  passkey (`C…`) no puede firmar SEP-10 clásico, así que opera a través de su
  *cuenta de depósito* — una cuenta clásica generada y guardada cifrada en el
  teléfono (`DepositAccountManager`) — hasta que la app implemente SEP-45. El
  USDC que llega es **el del anchor de prueba** (otro emisor que el USDC de Blend
  del fondo): la app lo convierte sola con una `PathPaymentStrictSend`
  (`HorizonStream.quoteStrictSend` / `pathPaymentStrictSend`) si la cotización
  entrega al menos el 97 % de lo enviado (`SwapMath.quoteGuard`) y, en passkey,
  lo envía al smart account con `transfer` del SAC (`SorobanClient.sacTransfer`).
  El cierre es reanudable: mira los saldos on-chain y resuelve por hash la
  transacción que hubiera quedado en vuelo (`DepositPlan`).
- **Faucet demo (contingencia):** `RelayerClient.faucet` → `POST /v1/faucet` del
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

### 6.6 Pago diferido por enjambre (HackMeridian 2026)

Variante del flujo 6.1 para cuando el pagador, el comercio o ambos están **sin
señal**: el pagador firma sin red la `SorobanAuthorizationEntry` de
`Pool.pay_merchant` (`DeferredPaymentManager.sign`) y la entrega como **sobre**
(`raiz:sobre:1:` + XDR firmado) por QR o texto; quien lo recibe lo verifica sin
red (`SobreSettler.receive`) y, en cuanto cualquier teléfono con la app o la
pasarela web recupera la red, la invocación se envía **tal cual** desde esa
cuenta, que paga el fee (`SorobanClient.submitSobre`). El contrato no cambia:
`pay_merchant` ejecuta el mismo pipeline de 6.1 con `tourist` autorizado por la
firma del sobre. Roles, pieza del protocolo, componentes, reglas de cierre y
pruebas: **§13**.

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
| `data/deferred/` (`DeferredPaymentManager`, `SobreSettler`, …) | Pago diferido por enjambre (HackMeridian 2026): firma sin red, sobres propios y recibidos, liquidación automática al recuperar señal. Tabla clase por clase en §13.4. |
| `ConnectivityMonitor` (`data/network/`) | Fuente única de "¿hay red?" (`online`, `offline`, `forceOffline` solo en debug): decide "Firmar sin señal" **antes** de intentar el pago en línea y gobierna el envío automático de sobres. |
| `MerchantCache` (`data/local/`) | Última foto de los comercios que la red devolvió (`list_merchants` / `get_merchant`), para elegir comercio sin señal; nada de mocks. |

**Modelos (`data/model/`):** espejo Kotlin de los structs Rust (`Barrio`,
`Merchant`, `Proposal`, `Reward`, `Execution`, `ResidentToken`), con helpers
`.toUsdc()` / `.toStroops()` y `RaizConstants` (RPC URL, divisores).

**24 pantallas (`*Screen.kt` en `ui/`)** en grupos: onboarding/auth (welcome,
registro passkey/seed, login, import, elección de rol), roles (become_resident,
become_merchant con Mapbox), núcleo (Wallet con RAÍZ Passport, Pay, Rewards,
BarrioMap con Mapbox, Profile), on-ramp (**Depositar**: SEP-24 + conversión al
USDC del fondo, D3), gobernanza (proposals + crear propuesta), comercio
(cobros, con "Escanear/Pegar sobre" y "Pagos en tránsito" desde HackMeridian
2026), pago diferido (**Mis sobres** + Recibidos, **Recibir sobre** y la
pantalla del sobre firmado dentro de Pagar; §13), público sin login (Dashboard
de transparencia → Yield) y LockScreen biométrico. El bottom nav cambia según el rol resuelto on-chain por
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
  con la wallet semilla o, en passkey, con su cuenta de depósito (JWT solo en
  memoria); SEP-24 abre la web interactiva en una Custom Tab y sondea hasta
  `completed`. El USDC del anchor es otro activo que el USDC de Blend del fondo;
  la app lo convierte sola con una `PathPaymentStrictSend` (guarda de precio del
  97 %) y, en passkey, lo reenvía al smart account. La autenticación directa del
  smart account (SEP-45) está planificada (WP7).
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
  desde la app contra el anchor de prueba para todas las wallets, que termina
  en USDC del fondo (conversión automática + envío al smart account en passkey).
- **HackMeridian 2026 — pago diferido por enjambre** (rama `feat/pago-diferido`,
  25–26 oct): 4 sobres firmados por el pagador **fuera de la transacción** y
  liquidados en testnet por una cuenta **distinta del pagador** — prueba de
  integración Kotlin, pasarela de terminal, pasarela web en Chrome headless y la
  app como portador en el emulador. Hashes verificados en §13.7. La cadena no
  prueba el momento de la firma: de esos 4 sobres, solo el de `cec2388a…` salió de
  la app del emulador en modo "Firmar sin señal" (commit `d391756`); el de la IT
  Kotlin y los dos de `scripts/deferred_envelope_demo.mjs` nacieron con red (ambos
  leen el ledger con `getLatestLedger` antes de firmar). La firma sin dependencia
  de red la cubre el código (`DeferredAuthSigner` / `AuthTreeBuilder`, byte a byte
  contra el fixture de `stellar-sdk` 17) y el video pendiente (B5). Todavía sin
  prueba en dispositivo físico.
- **85 tests de contratos en verde** (CI en GitHub Actions) + **315 tests JVM**
  de la app (26-oct-2026, tras B4 de HackMeridian; eran 131 el 4-oct).

**Limitaciones conocidas:**
- **Pago diferido sin finalidad offline:** hasta que el sobre llega a la red el
  comercio tiene una promesa firmada, no dinero; el doble gasto se acota con la
  expiración (12 h garantizadas), el tope por cobro (20 USDC por defecto) y la
  identidad firmada, no se elimina (bolsillo prefondeado: v1). Solo firman y
  envían sin red las wallets **semilla**; las passkey liquidan por la pasarela
  web. Detalle en §13.8.
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
android/app/.../data/local/MerchantCache.kt      → comercios sin señal: última respuesta de la red (pago diferido)
android/app/.../data/network/ConnectivityMonitor.kt → ¿hay red? decide "Firmar sin señal"; forceOffline solo en debug
android/app/.../data/deferred/   → pago diferido por enjambre (HackMeridian 2026, §13): AuthTreeBuilder,
                                   DeferredAuthSigner, SobreCodec, SobreValidator, LedgerEstimator (LedgerMath),
                                   DeferredStore (DeferredPolicy), DeferredPaymentManager, ReceivedSobre* (record,
                                   verifier, policy, store), SobreInbox, SobrePaymentsCache, SobreSubmit/SobreErrors,
                                   SobreSettler
android/app/.../data/stellar/EventsPaging.kt · SobreLookup.kt → paginación completa de getEvents y candidatas al hash de un sobre
android/app/src/main/assets/execution_hashes.json → archivo versionado de hashes (D2)
android/app/src/test/resources/deferred_auth_fixture.json → referencia byte a byte de la auth entry (stellar-sdk 17)
android/app/.../ui/       → 24 pantallas Compose (onboarding, roles, núcleo, depositar, gobernanza,
                            cobros, dashboard → yield, pago diferido: pay/DeferredSignedScreen,
                            deferred/ = Mis sobres + Recibir sobre)
landing/pasarela.html     → pasarela web pública del pago diferido (raizapp.xyz/pasarela.html), sin servidor
scripts/deploy_testnet.sh → despliegue (copia deployments.json a los assets de la app)
scripts/seed_testnet.sh   → datos demo
scripts/treasury_ttl.js   → estado del TTL de las entradas del Treasury (solo lectura) + claves XDR para restore/extend
scripts/gen-auth-fixture.mjs      → fixture byte a byte de la auth entry de pay_merchant (--check, --verify-tree)
scripts/pasarela_cli.mjs          → pasarela de terminal: liquida un sobre desde cualquier cuenta con señal
scripts/deferred_envelope_demo.mjs → sobre real de prueba firmado por una wallet de testnet
scripts/pasarela_web_e2e.mjs      → prueba end-to-end headless (CDP) de landing/pasarela.html
deployments.json          → IDs de contratos en testnet (fuente canónica)
docs/evidencia_sow/       → evidencia del SOW Instaward (D1, D2, D3)
docs/pago_diferido_v0.md  → spec congelada del pago diferido (HackMeridian 2026)
docs/evidencia_hackmeridian/ → evidencia de HackMeridian 2026 (hashes, verificación en 5 min)
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

---

## 13. Pago diferido por enjambre (HackMeridian 2026)

> **Estado (2026-10-26):** construido durante HackMeridian 2026 (Lisboa, 25–26 oct)
> en la rama `feat/pago-diferido`; todo lo posterior al tag `hackmeridian-start`
> (`1361b38`) es trabajo del evento (`git log --oneline hackmeridian-start..HEAD`).
> **Cero contratos nuevos, cero redeploy:** usa `Pool.pay_merchant` tal como está
> desplegado (§4.1). Spec congelada: `docs/pago_diferido_v0.md`; plan y resultado de
> cada bloque (B1–B4): `docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md`; evidencia (hashes,
> verificación en 5 minutos): `docs/evidencia_hackmeridian/README.md`. Todo lo que
> sigue está leído del código de `android/app/src/main/java/com/raiz/app/data/deferred/`,
> `data/stellar/SorobanClient.kt`, `landing/pasarela.html` y `scripts/`.

### 13.1 Qué resuelve y qué no

**Resuelve** que un visitante pague a un comercio del territorio cuando uno de los
dos (o ambos) no tiene señal, y que el pago se liquide en la red en cuanto
**cualquier** dispositivo del enjambre la recupere: el propio pagador, el comercio,
un tercero con la app o una página web.

**No promete finalidad offline.** Mientras el sobre no llega a la red, el comercio
tiene una **promesa firmada, no dinero**; el doble gasto es posible dentro de la
ventana y se acota con expiración por ledger, tope por cobro e identidad firmada
(y, en v1, un bolsillo prefondeado por territorio). La UI lo dice con esas
palabras («"En tránsito" es una promesa firmada, no dinero confirmado»,
`ui/deferred/DeferredListScreen.kt`) y los textos hablan siempre de **"pago
diferido"**, nunca de "pago offline" a secas (regla H5 del plan).

### 13.2 Roles del enjambre

```
         SIN SEÑAL                                          SIN SEÑAL                                  CON SEÑAL
┌────────────────────────┐                        ┌────────────────────────┐                ┌────────────────────────────────┐
│ PAGADOR (visitante)    │  QR o "Copiar sobre"   │ COBRADOR (comercio)    │   el sobre     │ PASARELA (cualquier G… con XLM)│
│ wallet semilla (G…)    │ ─────────────────────▶ │ verifica sin red,      │ ─────────────▶ │ · la app (semilla) de quien lo │
│ firma sin red la auth  │  raiz:sobre:1:<XDR>    │ guarda EN_TRANSITO,    │   viaja tal    │   tenga: pagador, comercio o   │
│ entry de pay_merchant: │                        │ entrega la mercancía   │   cual, sin    │   portador                     │
│ nonce + exp. estimada  │ ─────────────────────▶ │ contra una promesa     │   cambios      │ · raizapp.xyz/pasarela.html    │
└────────────────────────┘           │            └────────────────────────┘                │ · scripts/pasarela_cli.mjs     │
                                     │            ┌────────────────────────┐                │ reconstruye la tx como FUENTE, │
                                     └──────────▶ │ PORTADOR (otro tel.)   │ ─────────────▶ │ simula ENFORCE, paga el fee,   │
                                                  │ transporta el sobre;   │                │ envía y devuelve el hash       │
                                                  │ no puede alterarlo ni  │                └───────────────┬────────────────┘
                                                  │ redirigirlo            │                                │
                                                  └────────────────────────┘                                ▼
                               Stellar testnet · Pool.pay_merchant(tourist, merchant, amount, tip_bps)
                               auth = pagador · fuente = pasarela · el nonce se consume una sola vez
```

| Rol | Quién | Hace | No puede |
|---|---|---|---|
| **Pagador** | Visitante con wallet **semilla** (`G…`); passkey queda para v1 (`DeferredPaymentManager.availability`) | Construye y firma **sin red** la `SorobanAuthorizationEntry` de `pay_merchant` (nonce del CSPRNG, expiración estimada) y la muestra como QR o la copia como texto | Retirar la promesa una vez entregada: solo expira o se liquida |
| **Cobrador** | Teléfono del comercio destinatario (`args.merchant == owner` → `RolRecibido.COBRO`) | Verifica el sobre sin red, lo guarda `EN_TRANSITO`, aplica el tope por cobro y entrega contra una promesa firmada; con señal lo liquida solo | Alterar monto, destinatario, aporte ni tarifa (están firmados); dar por cobrado sin un hash cuya auth sea el sobre |
| **Portador** | Cualquier otra wallet con la app (residente, otro comercio) (`RolRecibido.PORTADOR`) | Transporta el sobre (QR o texto) y lo liquida solo al tener señal; puede aceptarlo sin poder comprobar su vigencia | Gastarlo, redirigirlo ni alterarlo; solo retrasarlo o perderlo (replicarlo es gratis y seguro: la red consume el nonce una vez) |
| **Pasarela** | Cualquier cuenta `G…` con XLM y señal: la app con wallet semilla, `landing/pasarela.html` en cualquier navegador, `scripts/pasarela_cli.mjs` o (v1) el relayer | Reconstruye la tx con su cuenta como **fuente**, simula en modo `ENFORCE`, paga el fee, envía y devuelve el hash | Cambiar nada de la invocación: la firma del pagador la fija byte a byte (`Error(Auth, InvalidAction)` si algo cambia) |

Propiedad clave: **la pasarela no necesita ser de confianza**. Solo aporta
conectividad y XLM para el fee; por eso un iPhone sin la app, con una página web,
sirve de pasarela.

### 13.3 La pieza del protocolo

**Qué se firma.** Una `SorobanAuthorizationEntry` con credenciales de dirección
firma `sha256(XDR(HashIdPreimage::SorobanAuthorization { network_id, nonce,
signature_expiration_ledger, invocation }))` (`ENVELOPE_TYPE_SOROBAN_AUTHORIZATION`,
tipo 9). La firma cubre **el árbol completo de invocación** (función raíz +
sub-invocaciones), **no** el footprint ni la transacción: por eso se puede firmar
sin red y enviar después desde **otra cuenta**, que es la fuente de la tx y paga
el fee. El host rechaza cualquier cambio del árbol con `Error(Auth, InvalidAction)`.
Credenciales **legacy** (`SorobanCredentialsXdr.Address`): son las únicas que
conoce `kmp-stellar-sdk` 1.6.0 y Protocol 29 las sigue aceptando (medido el
25-oct-2026; las V2 de CAP-71 cuando el SDK Kotlin las tenga — cambia el
preimage, no el flujo).

**Árbol de `pay_merchant`** (espejo de `contracts/pool/src/lib.rs`, `AuthTreeBuilder`;
el fixture `scripts/gen-auth-fixture.mjs --verify-tree` comprueba contra la
simulación del host que es el mismo árbol, 3 sub-invocaciones):

```
tip  = amount * tip_bps / 10_000        fee = amount * fee_bps / 10_000        to_m = amount - fee   (i128, trunca)

root:  Pool.pay_merchant(tourist, merchant, amount, tip_bps)       [require_auth(tourist)]
  ├─ USDC.transfer(tourist, merchant, to_m)
  ├─ USDC.transfer(tourist, Pool,     tip)     solo si tip > 0
  └─ USDC.transfer(tourist, admin,    fee)     solo si fee > 0
```

`Rewards.accrue_points` lo autoriza el propio Pool (`current_contract_address()`) y
no entra en el árbol del turista. Las ramas se deciden por el valor calculado
(`tip > 0`, `fee > 0`), no por los bps: con montos pequeños el contrato trunca a 0 y
omite el transfer. Direcciones que fija el árbol (`DeferredIds.from(deployments)`,
fuente `deployments.json`; la pasarela web lleva su copia en `DEPLOYMENTS`):

| Pieza | Dirección (testnet) |
|---|---|
| Pool (raíz `pay_merchant`) | `CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2` |
| USDC SAC de Blend (sub-invocaciones `transfer`) | `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU` |
| Admin (destino del fee; `protocol_fee_bps = 50`) | `GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P` |

Si el Pool cambiara `ProtocolFeeBps` o el admin, la firma dejaría de coincidir y la
red rechazaría el sobre (`InvalidAction`): fallo seguro, nunca un cobro distinto del
firmado.

**Nonce y expiración.**
- `nonce`: 64 bits del CSPRNG del teléfono (`DeferredAuthSigner.newNonce`). El host
  guarda el nonce consumido como entrada **temporal** `LedgerKeyNonce(nonce)` bajo la
  cuenta del firmante, viva hasta `signature_expiration_ledger`: un segundo envío
  del mismo sobre falla con `Error(Auth, ExistingValue)`; una lectura
  `getLedgerEntries` de esa clave (`SorobanClient.nonceConsumed`) prueba que una tx
  con ESE sobre se ejecutó, sin depender de la pasarela.
- `signature_expiration_ledger` E = L̂ + W, estimado sin red. La red exige
  `E − L ≤ max_entry_ttl − 1 = 3 110 399` (≈ 180 días; medido: 3 110 399 aceptado,
  3 110 400 rechazado) y rechaza E < L con `Error(Auth, InvalidInput)`.

**Estimación del ledger sin red** (`LedgerMath`, spec §3.1). El pagador guarda,
cada vez que tiene red, el ancla (L0, t0): último ledger visto y el instante
**monotónico** (`SystemClock.elapsedRealtime()`, nunca la hora de pared), más
`BOOT_COUNT` para detectar reinicios y la hora de pared solo para detectar saltos
de reloj (> 15 min invalida el ancla):

```
L̂(t) = L0 + floor((t − t0) / τ)                      τ = 5 s (medido 25-oct: 5,000 s exactos en 199 ledgers)
E     = L̂(t) + W
W     = T_des / τ_min + Δ_max · (1/τ_min − 1/τ)       T_des = 12 h, τ_min = 4,5 s, Δ_max = 24 h sin señal
      = 9 600 + 1 920 = 11 520 ledgers                (LedgerMath.WINDOW_12H)
```

El primer término cubre la ventana al cierre más rápido; el segundo, la deriva
acumulada entre la última sincronización y la firma. Peor caso (red a 4,5 s tras
24 h sin señal): exactamente 12 h; con el cierre medido hoy, 16 h. La UI muestra
la ventana garantizada: "Válido ~12 h (hasta ~hh:mm, estimado)". Sin ancla, con
ancla de otro arranque, de más de 24 h o con el reloj saltado **no se firma**
(`LedgerEstimator.readiness` → `Readiness.NoLista(motivo)`).

**El sobre que viaja** (`SobreCodec`): `raiz:sobre:1:` + base64url **sin relleno** del
XDR de la `SorobanAuthorizationEntry` firmada. Nada más: comercio, monto, aporte y
reparto se **derivan** de la invocación firmada (`PayArgs.from`), así que hay una
sola fuente de verdad y no existe la clase de error "lo que se enseña no es lo
que se firmó". Tamaño con 3 transfers: 912 bytes de XDR → 1 216 caracteres + 13 de
prefijo = **1 229** (QR versión 29, corrección M). El JSON de la spec §5 quedó como
descripción de campos, no como formato de transporte.

### 13.4 Componentes de la app

Capa de datos (`android/app/src/main/java/com/raiz/app/data/deferred/` salvo que se
indique otro paquete). Las clases marcadas **puro** no tocan Android ni la red y se
prueban en JVM.

| Clase | Responsabilidad y firmas |
|---|---|
| `DeferredIds` · `PayMerchantSplit` · `AuthTreeBuilder` (`object`, **puro**) | Árbol §13.3. `split(amount: Long, tipBps: Int, feeBps: Int): PayMerchantSplit` (misma aritmética entera que el contrato) · `payMerchantInvocation(payer, merchant, amount, tipBps, feeBps, ids: DeferredIds): SorobanAuthorizedInvocationXdr` · `transfer(usdcSac, from, to, amount)`. Constantes `BPS_DENOMINATOR = 10_000`, `FN_PAY_MERCHANT`, `FN_TRANSFER`. Bytes comparados con el fixture en `DeferredAuthSpikeTest`. |
| `DeferredAuthSigner` (`object`) · `SignedDeferredAuth` | Firma y verificación **sin red** de la entrada, como la produce `Auth.authorizeEntry` del SDK pero fijando nonce y expiración: `newNonce(): Long` · `unsignedEntry(payer, nonce, expirationLedger, invocation)` · `preimage(networkId, nonce, expirationLedger, invocation): HashIDPreimageXdr` · `suspend payloadHash(network, nonce, expirationLedger, invocation): ByteArray` · `signatureScVal(publicKey, signature)` (`Vec[Map{public_key, signature}]`) · `suspend sign(entry, keyPair, network): SignedDeferredAuth` · `suspend verify(entry, network): Boolean` (una sola firma, de la clave de la dirección; no mira expiración ni nonce). Solo credenciales legacy. |
| `SobreCodec` (`object`, **puro**) | Formato del sobre: `PREFIX = "raiz:sobre:1:"`, `MAX_CHARS = 4_096` · `encode(entry): String` · `isSobre(text): Boolean` · `decode(text): SorobanAuthorizationEntryXdr?` (nunca lanza). |
| `PayArgs` · `SobreCheck` · `SobreValidator` (`object`, **puro**) | Verificación sin red de cualquier sobre: `suspend validateText(text, ids, feeBps, network): SobreCheck` · `suspend validate(entry, ids, feeBps, network)`. Comprueba, en orden: es un sobre RAÍZ; lo firma una cuenta `G…`; la raíz es `pay_merchant` de **nuestro** Pool con 4 argumentos; el firmante es el pagador del árbol; el árbol reconstruido con nuestro `fee_bps` es **idéntico byte a byte**; la firma ed25519 vale para la red → `SobreCheck.Ok(entry, args, split, nonce, expirationLedger)` o `Rechazado(motivo)`. **No** comprueba expiración, nonce visto, destinatario ni tope (eso depende de quién verifica). |
| `LedgerMath` (`object`, **puro**) · `LedgerAnchor` · `AnchorStorage` · `Readiness` · `LedgerEstimator` (`@Singleton`) | Estimador §13.3. `LedgerMath`: `TAU_MS = 5_000`, `TAU_MIN_MS = 4_500`, `MAX_ANCHOR_AGE_MS = 24 h`, `MAX_CLOCK_DRIFT_MS = 15 min`, `WINDOW_12H` (11 520), `window(desiredMs, tauMs, tauMinMs, deltaMaxMs)`, `estimate(l0, t0Ms, nowMs)`, `expiration(...)`, `isExpiredLocally(exp, estimate)`. `LedgerEstimator`: `record(sequence, closeTimeSec?)` (lo alimentan `SorobanClient.refreshLedgerAnchor()` y `latestLedgerSequence()` cada vez que hay red) · `readiness(): Readiness.Lista(anchor, estimate) \| NoLista(motivo)` · `estimate()` · `expiration(windowLedgers)` · `validUntilWallMs()`. |
| `DeferredStatus` · `DeferredRecord` · `DeferredRecordCodec` · `DeferredPolicy` (`object`, **puro**) · `DeferredStore` (`@Singleton`, implementa `AnchorStorage`) | Sobres **propios** del pagador. Estados `FIRMADO, ENTREGADO, CONFIRMADO, EXPIRADO, RECHAZADO`. Registro con `id` = nonce en decimal, `sobreQr`, `authXdr`, comercio, montos, `exp`, `l0`/`t0`, `txHash`, `motivo` y el diario de envío (`sendAttempts`, `inFlightHash`, `inFlightValidUntilSec`, `lastQuotedFeeStroops`…). Almacén en SharedPreferences `raiz_deferred` (`records_v2` + ancla): `saveDurable(record): Boolean` (`commit()` y relectura: si falla **no se muestra el QR**) · `mutate(id, f)` (lee-modifica-escribe el registro VIVO bajo candado) · `refreshAll(f)` · `recordsFor(payer)` · `pendingCount(payer)`. `DeferredPolicy`: `MAX_SEND_ATTEMPTS = 3`, `RETRY_BACKOFF_MS = 5 min`, `MIN_LEDGERS_BEFORE_EXP = 3`, `canSign`, `refresh` (vencimiento local), `markDelivered`, `markConfirmed(r, txHash?)`, `markExpiredByNetwork`, `markRejected`, `markInFlight`/`clearInFlight`/`markSendFailed`/`markFeeTooHigh`, `shouldAutoSend(r, ledgerHint, nowMs)`, `consulta(r, nonceConsumed)`, `whenPastExpiration(r, prueba)`, `eventsSinceLedger(r) = l0 − 20`. |
| `DeferredPaymentManager` (`@Singleton`) | Orquestador del **pagador**. `availability(): Disponible \| NoDisponible(motivo)` (solo wallet semilla — nunca la clave demo ni passkey — y estimador listo) · `suspend sign(merchant: Merchant, amountStroops: Long, tipBps: Int): RaizResult<DeferredRecord>` (nonce CSPRNG, `exp = L̂ + WINDOW_12H`, árbol, firma, **autocomprobación** con `SobreValidator` y round-trip del codec, `saveDurable` **antes** de mostrar el QR) · `markDelivered(id)` · `refreshStatuses()` (sin red: EXPIRADO local por estimación) · `suspend syncWithNetwork(payer): Int` (con red: nonce consumido → CONFIRMADO con el hash del pago si se encuentra; ledger real > `exp` → busca el hash antes de dar EXPIRADO) · `suspend confirmByHash(id, hash)` (solo si la auth de esa tx es exactamente este sobre). |
| `RolRecibido` · `RecibidoStatus` · `ReceivedSobreRecord` · `RecibidoCheck` (`ReceivedSobre.kt`) | Sobres **recibidos** de otro teléfono. `RolRecibido { COBRO, PORTADOR }`, `RecibidoStatus { EN_TRANSITO, CONFIRMADO, EXPIRADO, RECHAZADO }`; registro con `id = "<payer>:<nonce>"`, `owner`, `sobreQr`, `authXdr`, `rol`, `receivedAtLedger`, `vigenciaComprobada` y el mismo diario de envío que el propio. `RecibidoCheck { Aceptado(record), YaRegistrado(record), Rechazado(motivo) }`. |
| `ReceivedSobreVerifier` (`object`, **puro**) | `suspend verify(text, owner, topeStroops, ledgerActual: Long?, estimate: Long?, anchorAgeMs: Long?, vistos, ids, feeBps, network, nowMs): RecibidoCheck`. Orden: (1) `SobreValidator`; (2) índice `(owner, payer, nonce)`: mismo XDR → `YaRegistrado` (idempotente), otro XDR con el mismo nonce → rechazo por **nonce reutilizado** (posible fraude); (3) rol: `COBRO` si `args.merchant == owner`, si no `PORTADOR`; (4) tope solo en `COBRO` (`TOPE_DEFAULT_STROOPS = 200_000_000` = 20 USDC); (5) vigencia: ledger real si hay red, si no el estimado más `margen(anchorAgeMs) = MARGEN_LEDGERS (360) + deriva por la edad del ancla`; un `COBRO` **nunca se acepta sin referencia de ledger**, un `PORTADOR` sí con `vigenciaComprobada = false`. |
| `ReceivedSobrePolicy` (`object`, **puro**) | Transiciones anti fraude del receptor: `MAX_SEND_ATTEMPTS = 3`, `RETRY_BACKOFF_MS = 300_000`, `EVENTS_DRIFT_MARGIN_LEDGERS = 3_000` · `Prueba { Hash(txHash), Ninguna, NoComprobable }` · `markConfirmed(r, txHash)` (**hash obligatorio**; pisa EXPIRADO/RECHAZADO previos) · `markNonceConsumidoSinHash` · `whenNonceConsumed(r, prueba, nonceEnOtraTx)` · `whenPastExpiration(r, prueba)` · `consulta(r, nonceConsumed)` · `reaccion(outcome: SobreSubmit): Reaccion { Confirmar, Nada, RevisarNonce, RevisarExpiracion, Rechazar, Aplazar, FeeAlta }` · `shouldAutoSend` · `eventsSinceLedger(r) = exp − 11 520 − 3 000`. |
| `ReceivedSobreStore` (`@Singleton`) | SharedPreferences `raiz_received_sobres` (`records_v1`, `tope_<owner>`): `saveDurable(record): Boolean` (nunca pisa un registro; idempotente solo para el mismo sobre de la misma wallet) · `mutate(id, f)` · `refreshAll(f)` · `seen(owner, payer, nonce)` · `recordsFor(owner)` · `pendingCount(owner)` · `topeStroops(owner)` / `setTopeStroops(owner, stroops)` (0 = no aceptar cobros sin señal). |
| `SobreInbox` (`@Singleton`) | Buzón en memoria entre el escáner de Inicio y "Recibir sobre": `pendingText`, `take()` (una sola vez; un sobre de ≈ 1,2 KB no viaja en la ruta de navegación). |
| `SobrePaymentsCache` | Una lectura de pagos del Pool por pagador y pasada: `payments(payer, sinceLedger): List<PaymentRecord>?` (`null` = "no se pudo comprobar", nunca "no hay"). |
| `SobreSubmit` (sealed) · `SobreErrors` (`object`, **puro**) (`SobreSubmit.kt`) | Veredicto de enviar un sobre: `Liquidado(txHash)`, `EnVuelo(txHash, validUntilSec)`, `NonceUsado`, `Expirada`, `RechazadaDefinitiva(motivo)`, `RechazadaTransitoria(motivo)`, `FallidaEnRed(txHash)`, `SinFondosFuente(motivo)`, `FeeAlta(feeStroops, maxFeeStroops)`, `SinRed(motivo)`. `SobreErrors.classifySimulation(error)`: `Error(Auth, ExistingValue)` → `NonceUsado`; `Error(Auth, InvalidInput)` → `Expirada`; `Error(Contract, #10/#13/#5/#4)` y `#6/#7/#8` → transitoria; `Error(Auth, InvalidAction)` → **única** definitiva; otro `Auth` → transitoria. `classifySend(code)`: `txINSUFFICIENT_BALANCE`/`txINSUFFICIENT_FEE` → `SinFondosFuente`; `txBAD_SEQ`/`txTOO_LATE` → reconstruir. |
| `SobreSettler` (`@Singleton`) | **Orquestador único que liquida** sobres propios y recibidos desde la wallet de la app. `liquidador(): DesdeLaApp \| Pasarela(motivo)` (passkey o sin semilla → pasarela web) · `pasarelaUrl(sobreQr)` → `https://raizapp.xyz/pasarela.html#sobre=…` · `start(scope)` (idempotente; cada vez que la conectividad pasa a "con señal", y al arrancar si la hay, espera 2 s y lanza `settlePending`; lo llama `MainActivity` tras desbloquear, con alcance de proceso) · `suspend settlePending(owner): Resumen(confirmados, expirados, rechazados, aplazados, enviados)`: candado por pasada y wallet; **primero consulta** (`syncWithNetwork` + política de recibidos), **después envía** solo con red, wallet semilla y siendo la wallet activa: recibidos de inmediato y antes que los propios; para un sobre propio ya mostrado (`ENTREGADO`) espera `PAYER_JITTER_MS = 10 s` y vuelve a consultar, por si el cobrador ya liquida · `suspend settleOwn(id, manual)` / `suspend settleReceived(id, manual): Resultado { Actualizado, NoEnviable(motivo, pasarelaUrl), Ocupado, SinSenal, FeeAlta }` ("Liquidar ahora" = `manual = true`: salta backoff y tope de intentos, no la simulación) · `suspend receive(text): RecibidoCheck` (verifica con el ledger real si hay red o la estimación y la edad del ancla si no; guarda con `saveDurable`) · `refreshStatuses()`. Topes: `AUTO_MAX_FEE_PROPIOS = 1_000_000_000` (100 XLM), `AUTO_MAX_FEE_RECIBIDOS = 20_000_000` (2 XLM), `MANUAL_MAX_FEE = 1_000_000_000` (100 XLM). Mutex por sobre (`tryLock` → `Ocupado`) y por cuenta fuente (dos envíos de la misma cuenta darían `txBAD_SEQ`), siempre en ese orden; diario `TxJournal` → `markInFlight`/`clearInFlight`; reanudación por hash con la hora de la red. |
| `SorobanClient` (`data/stellar/`) | `suspend submitSobre(entry: SorobanAuthorizationEntryXdr, signer: KeyPair, maxFeeStroops: Long, journal: TxJournal? = null): SobreSubmit`: (1) `InvokeHostFunctionOperation(hostFunction = InvokeContract(fn raíz), auth = listOf(entry))`, `maxTime` = hora de la **red** + `SOBRE_TX_TIMEOUT_SEC = 90`, `SOBRE_BASE_FEE = 500_000` stroops, simulación `AuthMode.ENFORCE` **antes** de firmar (gratis); cotización = fee de la tx + `minResourceFee`, por encima de `maxFeeStroops` → `FeeAlta` sin enviar; `prepareTransaction` conserva la auth; (2) firma **una** vez y `journal.signed(hash, validUntil, sequence)` antes de enviar (si el diario lanza, no se envía); (3) justo antes de enviar vuelve a mirar `nonceConsumed` (carrera pagador/cobrador); (4) envía el **mismo** sobre (reintenta solo `TRY_AGAIN_LATER`); (5) confirma por hash; sin veredicto → `EnVuelo` con el diario abierto. Nunca lanza. · `suspend nonceConsumed(account, nonce): Boolean?` (`getLedgerEntries` de `LedgerKeyNonce`, `TEMPORARY`) · `suspend latestLedgerSequence(): Long?` y `suspend refreshLedgerAnchor(): Boolean` (alimentan el estimador) · `suspend transactionAuthEntries(hash): TxAuthLookup(status, authEntriesXdr)` · `suspend sobrePaymentEvents(addr, sinceLedger): RaizResult<List<PaymentRecord>>` (lectura **completa o error**; sigue el cursor hasta que se detiene) · `suspend lookupSobreHash(payer, merchant, authXdr, events?, sinceLedger?): SobreHashLookup { Found(txHash), NotFound, Unavailable }` · `suspend sobreNonceEnOtraTx(payer, merchant, nonce, authXdr, events?, sinceLedger?): String?`. Apoyo **puro**: `EventsPaging` (`PAGE_LIMIT = 100`, `cursorLedger`, `isLastPage`, `readAll`, `covers`) y `SobreLookup` (`MAX_CANDIDATES = 24`, `hashCandidates`, `nonceCandidates`). |
| `ConnectivityMonitor` (`data/network/`) | `online: StateFlow<Boolean>`, `forceOffline: StateFlow<Boolean>` (solo debug), `offline: Flow<Boolean>`, `isOnline()`, `isOffline()`, `setForceOffline(value)`, `refresh()`. Recalcula con `activeNetwork` + `NET_CAPABILITY_INTERNET` (sin exigir `VALIDATED`) y vuelve a comprobar 600 ms después. |
| `MerchantCache` (`data/local/`) | `putBarrio(barrioId, merchants)`, `putMerchant(merchant)`, `merchant(address)`, `barrio(barrioId)`, `updatedAt(barrioId)`: la llena `SorobanClient` cada vez que `list_merchants` / `get_merchant` responden; sin señal, Pagar elige comercio de aquí. |

Capa UI (`ui/`):

| Pantalla / ruta | Qué hace |
|---|---|
| **Pagar** — `ui/pay/PayScreen.kt` + `PayViewModel` | La decisión "sin señal" se toma por conectividad **antes** de confirmar (`connectivity.offline` con debounce), nunca como respaldo tras un pago fallido (podría cobrar dos veces). Sin red el botón pasa a **"Firmar sin señal"** (`PayUiState.Editing.sinSenal`; si no se puede firmar, `sinSenalNoDisponible` dice por qué); en debug, un switch "Firmar sin señal (debug)" fuerza el modo con red (`forceOffline`). Al confirmar → `DeferredPaymentManager.sign` → `PayUiState.Signed(record, validUntilLabel)`. |
| **Sobre firmado** — `ui/pay/DeferredSignedScreen.kt` | QR del sobre al ancho exacto disponible (`QrUtils.generateCrisp`, módulos enteros), detalle honesto (comercio, monto, aporte, fee del protocolo), "válido hasta ~hh:mm (estimado)" y **"Copiar sobre"**. Al mostrarse, `markDelivered` (FIRMADO → ENTREGADO). La reutiliza el historial. |
| **Mis sobres** — `ui/deferred/DeferredListScreen.kt`, ruta `sobres`; detalle `sobres/{id}` (`DeferredDetailRoute`) y `recibido/{id}` (`RecibidoDetailRoute`) | Sobres firmados sin señal con su estado («"En tránsito" es una promesa firmada, no dinero confirmado») y, debajo, **"Recibidos"** (cobros y portes) con "Liquidar ahora" o "Copiar sobre y abrir la pasarela" según `SobreSettler.liquidador()`; botón "Recibir sobre". |
| **Recibir sobre** — `ui/deferred/RecibirSobreScreen.kt`, ruta `recibir` | Para **cualquier** wallet: recoge del `SobreInbox` el sobre que el escáner de Inicio reconoció (`SobreCodec.isSobre`) o acepta el pegado manual, muestra el veredicto (`RecibidoCheck`) con el rol (p. ej. «Lo llevas para GA7CDZ…4TSN»), y lo deja `EN_TRANSITO` para que `SobreSettler` lo envíe solo. |
| **Cobros** — `ui/cobros/CobrosScreen.kt` + `CobrosViewModel`, ruta `cobros` | Además del cobro con QR en línea: **"Escanear sobre"** / **"Pegar sobre"**, veredicto (`VeredictoDialog`), lista **"Pagos en tránsito"** con estado y motivo, tope por pago sin señal editable (`TopeDialog`), detalle con **"Liquidar ahora"** (wallet semilla; deshabilitado sin señal) o "Copiar sobre y abrir la pasarela" (passkey), banner sin señal (`SinSenalCobrosBanner`). La pantalla se pinta sin red. |
| **Inicio** — `ui/wallet/WalletScreen.kt` | Fila siempre visible «Sobres sin señal · Mis sobres» / «Tienes N sobres en tránsito · Ver» (propios + recibidos pendientes); el escáner de Inicio reconoce un sobre y navega a `recibir`. |
| `MainActivity` | `sobreSettler.start(procesoScope)` tras desbloquear la app (alcance de proceso, no de Activity); rutas `DEFERRED = "sobres"`, `RECIBIR = "recibir"`, `RECIBIDO = "recibido"`, `COBROS = "cobros"`. |

### 13.5 Máquina de estados y reglas de cierre

```
Pagador  (DeferredStatus):   FIRMADO → ENTREGADO(QR mostrado) → CONFIRMADO(hash) | EXPIRADO | RECHAZADO
Receptor (RecibidoStatus):   ReceivedSobreVerifier → EN_TRANSITO → CONFIRMADO(hash) | EXPIRADO | RECHAZADO
Envío    (submitSobre):      simular ENFORCE → firmar 1 vez → diario → ¿nonce consumido? → enviar el MISMO sobre → confirmar por hash
```

Reglas decididas tras la revisión adversarial de B4 (`SobreSettler`, `DeferredPolicy`,
`ReceivedSobrePolicy`, `SobreErrors`):

1. **La red es la única fuente de verdad del cierre.** Un sobre **propio** se da por
   cobrado con el nonce consumido bajo la cuenta del pagador (`nonceConsumed`) o con
   una tx `SUCCESS` cuya auth sea el sobre; un sobre **recibido** exige **siempre un
   hash cuya auth sea ese sobre** (`ReceivedSobrePolicy.markConfirmed(r, txHash)` no
   acepta un hash en blanco). Nonce consumido sin hash ⇒ sigue `EN_TRANSITO`
   «buscando el comprobante»; el nonce aparece en **otro** pago, o vence sin hash tras
   haberse visto consumido ⇒ `RECHAZADO` (posible fraude). `Error(Auth, ExistingValue)`
   significa "nonce ya usado", **nunca** "cobrado".
2. **Nada se rechaza por causas transitorias.** Solo `Error(Auth, InvalidAction)` (la
   firma no corresponde al árbol) y el nonce reutilizado / en otro pago son
   definitivos. Fondos (#10), trustline (#13), comercio (#4/#5), los códigos de
   resultado de la cuenta fuente y la red **aplazan con motivo visible** y decide la
   red. La verdad on-chain pisa un `RECHAZADO` o `EXPIRADO` previo (`markConfirmed`).
3. **Antes de `EXPIRADO` por ledger > `exp` se busca el hash** en los eventos
   `payment` del Pool desde el ancla (`eventsSinceLedger`, lectura completa o error
   vía `sobrePaymentEvents`), porque la entrada del nonce es **temporal** y
   desaparece al vencer. El `EXPIRADO` por estimación local es provisional
   (`isLocalExpiry`) hasta que la red lo confirma.
4. **Tope de fee.** La simulación es gratis; si la cotización (fee de la tx +
   `minResourceFee`) supera el tope no se envía nada (`FeeAlta`): 100 XLM para sobres
   propios, **2 XLM** en el envío automático de sobres ajenos (un desconocido no puede
   drenar el XLM de un comercio con sobres de un stroop) y 100 XLM con "Liquidar
   ahora". Medido en testnet: el **primer** `pay_merchant` de un turista en un barrio
   costó 28,65 XLM (crea `TouristSeen` y `Points`); los siguientes, ≈ 0,0023 XLM
   (§13.7).
5. **Recepción.** Un cobro (`COBRO`) no se acepta sin referencia de ledger (real o
   estimada con margen `360 + deriva por la edad del ancla`) ni por encima del tope
   por pago (20 USDC por defecto, editable por wallet); un portador sí, con aviso de
   vigencia no comprobada. El mismo sobre dos veces ⇒ «Ya tenías este sobre»
   (idempotente); el mismo nonce con otro XDR ⇒ rechazo.
6. **Candados y diario.** Mutex por sobre y por cuenta fuente; el hash de la tx
   firmada se anota (`commit()`) **antes** de enviarla y la próxima pasada la resuelve
   por hash con la **hora de la red** (`maxTime = closeTime + 90 s`), nunca con el
   reloj del teléfono; ≤ 3 envíos que llegaron a la red por sobre y 5 min de backoff
   en el automático; el "sin señal" forzado de depuración bloquea todo envío
   automático.
7. **Wallets passkey (`C…`)** no firman sin red ni envían desde la app en v0: Pagar
   muestra el motivo; Cobros y Recibidos copian el sobre y abren la pasarela web con
   el fragmento `#sobre=`.

Texto del pagador en la app: «Se liquida sola en cuanto tu teléfono, el del comercio
o el de cualquier usuario de RAÍZ que la lleve tenga señal».

### 13.6 Pasarelas web y de terminal

**Pasarela web** — `landing/pasarela.html`, publicada en
<https://raizapp.xyz/pasarela.html> (se copia a mano al repo Pages, como el resto de
`landing/`). Página **estática, sin servidor propio ni build**: `@stellar/stellar-sdk`
17.0.1, `html5-qrcode` 2.3.8 y `qrcode-generator` 1.4.4 desde jsDelivr; estilo y
objeto `DEPLOYMENTS` de la landing; textos ES/EN en un objeto. Flujo: escanear con
la cámara o pegar → **verificar sin red** con las mismas reglas que
`SobreValidator` (prefijo `raiz:sobre:1:`, XDR, credenciales legacy de una cuenta
`G…`, raíz `pay_merchant` del Pool, árbol reconstruido con `fee_bps` 50 idéntico
byte a byte, firma ed25519 sobre el preimage tipo 9) → estado en la red (ledger
actual, nonce consumido vía `getLedgerEntries`, nombre del comercio con
`get_merchant` simulado desde la cuenta del admin) → cuenta pasarela en
`localStorage` (creada y fondeada por friendbot, o una secret pegada; saldo por
Horizon) → **liquidar**: `invokeHostFunction` con los argumentos firmados y
`auth = [sobre]`, simulación `enforce`, `assembleTransaction` (conserva la auth),
firma, `sendTransaction`, polling de `getTransaction` → hash, enlace a Stellar
Expert y QR del hash; errores traducidos (nonce usado, expirado, firma/árbol, #4,
#5, #10, #13). `?sobre=` o `#sobre=` precargan y verifican (es lo que abre la app
en passkey). Tras liquidar marca el nonce como consumido y bloquea el reenvío sin
gastar. Probada en Chrome headless por CDP con `scripts/pasarela_web_e2e.mjs`
(fixture y sobre real: tx `27c61461…`, §13.7). Pendiente: la aceptación manual
desde Safari en un iPhone escaneando el QR del emulador (la cámara exige HTTPS, ya
cubierto por raizapp.xyz).

**Pasarela de terminal** — `scripts/pasarela_cli.mjs` (Node, `@stellar/stellar-sdk`
≥ 17): `node scripts/pasarela_cli.mjs "<sobre>"` liquida con una cuenta desechable
fondeada por friendbot; acepta también un archivo o `-` (stdin); `--dry-run` solo
verifica y simula; `PASARELA_SECRET=S…` usa una cuenta propia. Es la referencia de la
pasarela web y aplica las mismas reglas (sin claves del proyecto).

**Scripts de apoyo** — `scripts/gen-auth-fixture.mjs` genera el fixture determinista
`android/app/src/test/resources/deferred_auth_fixture.json` (preimage, hash, firma y
entrada firmada; `--check` verifica el determinismo; `--verify-tree` simula
`pay_merchant` en testnet sin firmar y comprueba que el árbol a mano es el del host);
`scripts/deferred_envelope_demo.mjs` firma un sobre **real** desde una wallet de
testnet (`DEFERRED_PAYER_SECRET` por variable de entorno; por defecto 1 USDC + 2 % a
Cafe Don Aurelio, ventana 11 520) para probar las pasarelas sin pasar por la app.

### 13.7 Pruebas y transacciones verificadas

**JVM** (`./gradlew :app:testDebugUnitTest`): 315 tests en verde tras B4 (26-oct). En
`android/app/src/test/java/com/raiz/app/data/deferred/`: `DeferredAuthSpikeTest` (16:
invocación, entrada sin firmar, preimage, hash, firma y entrada firmada **byte a
byte** contra el fixture de `stellar-sdk` 17, contraste con `Auth.authorizeEntry`,
round-trip, `verify`, variantes tip 0 / fee 0 / truncado / nonce negativo),
`LedgerMathTest`, `SobreCodecTest`, `DeferredPolicyTest`, `DeferredStoreTest`,
`ReceivedSobreVerifierTest`, `ReceivedSobrePolicyTest`, `ReceivedSobreStoreTest`,
`SobreErrorsTest`, `SobrePaymentsCacheTest`; en `data/stellar/`: `EventsPagingTest`,
`SobreLookupTest`. Integración contra testnet, **apagada por defecto** y sin
secretos en el repo: `DeferredAuthTestnetIT` (`RAIZ_DEFERRED_IT=1` +
`RAIZ_DEFERRED_PAYER_MNEMONIC` o `_SECRET`) y `SobreEventsTestnetIT`.

**Transacciones en testnet** (las cuatro con **fuente ≠ pagador** y **auth = pagador**;
verificadas en Horizon el 2026-10-26: `successful = true`, ledger, cuenta fuente y
`fee_charged`):

| Tx | Qué prueba | Ledger · fecha (UTC) | Fuente (pasarela) | Pagador → comercio | Fee cobrado |
|---|---|---|---|---|---|
| [`ebee152c…6d47`](https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47) | **B1**, `DeferredAuthTestnetIT` (Kotlin): sobre firmado localmente por la wallet semilla del emulador (IT en una JVM con red: lee el ledger con `getLatestLedger` y firma con `DeferredAuthSigner`) y enviado por una cuenta desechable fondeada por friendbot | 5 384 473 · 25-oct 16:32 | `GCCXC2E2…2WRH` | `GC4VHSZY…CQQEH` → `GA7CDZDQ…4TSN` (Cafe Don Aurelio), 0,5 USDC + 2 % | 28,65 XLM (primer pago del turista en el barrio) |
| [`cec2388a…1c34`](https://stellar.expert/explorer/testnet/tx/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34) | **Pasarela de terminal** `scripts/pasarela_cli.mjs`: segundo sobre del mismo turista, firmado en la app del emulador en modo "Firmar sin señal" (commit `d391756`; la cadena no prueba ese momento) | 5 388 242 · 25-oct 21:46 | `GD2Q2CZU…JLVF` | `GC4VHSZY…CQQEH` → `GAGVM6AT…DVIN`, 5 USDC + 2 % | 0,0023 XLM |
| [`27c61461…9f47`](https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47) | **B3**, pasarela web `landing/pasarela.html` en Chrome headless (CDP): sobre real de `deferred_envelope_demo.mjs` firmado por `raiz-tourist` (el script fija la expiración con `getLatestLedger`, es decir, con red); la página creó su cuenta con friendbot y liquidó | 5 388 486 · 25-oct 22:06 | `GADSC7Z5…T43MO` | `GDLGYDO4…IZM7` → `GA7CDZDQ…4TSN`, 0,5 USDC + 2 % | 0,0044 XLM |
| [`79a4c858…eee2`](https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2) | **B4**, la app en el emulador como **portador**: sobre de `deferred_envelope_demo.mjs` pegado en "Recibir sobre", verificado sin consultar la red (`SobreValidator`) y enviado solo por `SobreSettler` 11 s después | 5 391 483 · 26-oct 02:16 | `GC4VHSZY…CQQEH` (la wallet del emulador) | `GDLGYDO4…IZM7` → `GA7CDZDQ…4TSN`, 0,5 USDC + 2 % | 0,0023 XLM |

Comprobado también en B1: reenviar un sobre ya liquidado → `Error(Auth,
ExistingValue)`; expirado → `Error(Auth, InvalidInput)`; cierre de ledger 5,000 s
en 199 ledgers consecutivos. En B4, el mismo sobre pegado otra vez → «Ya tenías
este sobre». **Pendiente de probar** (B4): el camino `COBRO` con una wallet de
comercio y la autoliquidación de un sobre propio firmado sin señal; y todo el
flujo en un dispositivo físico (lo probado corre en el emulador y en Chrome
headless).

### 13.8 Límites declarados y deuda

- **Sin finalidad offline.** La promesa firmada no es dinero hasta el hash. Un
  pagador malicioso puede emitir sobres por más de su saldo y decidir qué comercios
  cobran: exposición por comercio = su monto, acotada por la expiración (12 h
  garantizadas), el tope por cobro (20 USDC) y la identidad firmada. El bolsillo
  prefondeado por territorio (`pocket`), la atestación de hardware contra passkeys
  sincronizadas o teléfonos rooteados y los portadores BLE son **v1**.
- **Solo wallets semilla** firman y envían sin red; las passkey (`C…`) copian el sobre
  y abren la pasarela web (SEP-45 y la firma offline del smart account siguen
  pendientes, WP7).
- **Retención del RPC (~7 días):** un sobre más viejo que la retención queda sin
  veredicto terminal («venció y ya no se puede comprobar»).
- **Fee de la pasarela:** en testnet el primer `pay_merchant` de un turista en un
  barrio cuesta ≈ 28,65 XLM (renta inicial de `TouristSeen` y `Points`); quién paga
  ese fee (pasarela voluntaria, comercio, relayer) forma parte del modelo de
  sostenibilidad en evaluación. Las pasarelas de demo se fondean con friendbot.
- **Privacidad:** el sobre expone pagador, comercio y monto a quien lo transporte;
  el sobre cifrado al destino es v1.
- **Deuda de contrato (no se toca en la hack):** el Pool descuenta el fee del
  protocolo del monto del comercio (`to_m = amount − fee`), en tensión con "el
  comercio recibe íntegra su venta"; se resuelve en los contratos v2.
- **Fuera de v0:** pasarela en el relayer (`POST /v1/deferred-payment`), iOS vía KMP,
  historia consultable más allá de 7 días (`raiz-memory`).
