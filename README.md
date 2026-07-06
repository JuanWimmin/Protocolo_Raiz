# 🌱 RAÍZ
> **Tu paga, el barrio crece.**

🌐 [raizapp.xyz](https://raizapp.xyz) · 🎤 [Entrevistas a comercios y residentes (Drive)](https://drive.google.com/drive/folders/1vd_3RpL_2eZphFwEavG5rx7ZF3PYScdp?usp=sharing) · 📽️ [Video demo](https://www.youtube.com/watch?v=y-9pglgVnnA)

**RAÍZ** es una red de pagos turísticos sobre **Stellar** que redirige un **"Tip Barrio"** (2% por defecto) de cada pago a un **fondo comunitario gobernado por los residentes del barrio** mediante tokens *soulbound* (no transferibles). El turista paga al comercio en USDC, un porcentaje se desvía automáticamente al pool del barrio, y los residentes votan en qué se reinvierte — todo **on-chain**, sin backend propio y sin que nadie tenga la llave del fondo.

App Android nativa + 4 contratos Soroban (Rust) desplegados y poblados en **Stellar Testnet**.

---

## 1. El problema y la solución 🧭

### El problema: turismo extractivo

El turismo **extrae** valor de los barrios con encanto. Las plataformas, las cadenas y la plusvalía se llevan el margen; quien **crea** el atractivo —los residentes, los artesanos, el comercio de esquina— captura una fracción mínima y **no decide nada** sobre cómo se reinvierte. El resultado: gentrificación, barrios vaciados de su gente y cero transparencia sobre a dónde fue el dinero.

### La solución: RAÍZ

Tres ideas, una sola app:

| Pilar | Qué hace | Por qué importa |
|---|---|---|
| **Tip Barrio automático** | El 2% (configurable) de cada pago va al pool del barrio, vía contrato. | Valor que se queda donde se genera, sin intermediarios ni confianza ciega. |
| **Gobernanza de residentes (soulbound)** | 1 residente = 1 voto intransferible. Proponen y votan en qué se invierte el fondo. | El voto **no se puede comprar** ni ceder. Democracia de barrio verificable. |
| **Ejecución trustless + transparencia** | Si una propuesta pasa quórum y mayoría, el Treasury la ejecuta **sin que nadie tenga la llave**. Todo se ve en un dashboard público. | Cada pago, voto y ejecución es auditable en la cadena. |

El turista gana **puntos canjeables** por artesanías locales; el comercio cobra al instante en dólar digital; el barrio acumula, **rinde** (yield DeFindex) y decide.

---

## 2. Arquitectura 🏗️

RAÍZ no tiene servidor propio: **todo el estado vive on-chain** en 4 contratos Soroban + el vault de DeFindex. La app Android es un cliente delgado que **lee por simulación** (Soroban RPC, sin firmar) y **escribe** enviando transacciones firmadas con la clave del usuario.

```mermaid
graph TD
    subgraph APP["📱 App Android (Kotlin · Jetpack Compose · Hilt)"]
        UI["Capa UI — 7 pantallas Compose + onboarding"]
        subgraph DATA["Capa data/ (servicios singleton)"]
            WM["WalletManager<br/>(seed BIP-39 + passkey)"]
            SC["SorobanClient<br/>(fachada 4 contratos)"]
            HS["HorizonStream<br/>(balances / friendbot / faucet)"]
            DC["DefindexClient<br/>(yield: TVL / APY)"]
            RR["RoleResolver<br/>(rol on-chain)"]
        end
        UI --> DATA
    end

    APP -->|"escribe (tx firmada)"| RPC["Soroban RPC"]
    APP -->|"lee (simula)"| HOR["Horizon"]
    RPC --> LEDGER
    HOR --> LEDGER

    subgraph LEDGER["⛓️ Stellar Testnet (Soroban)"]
        POOL["Pool"]
        GOV["Governance"]
        TRE["Treasury"]
        REW["Rewards"]
        USDC["USDC SAC (Blend)"]
        VAULT["Vault DeFindex"]

        POOL -->|"accrue_points"| REW
        POOL -->|"deposit / withdraw"| VAULT
        TRE -->|"tally · get_proposal · mark_executed"| GOV
        TRE -->|"withdraw_to · redeem_from_vault"| POOL
        POOL -->|"transfer"| USDC
    end
```

### Los 4 contratos

| Contrato | Crate | Rol en una línea |
|---|---|---|
| **Pool** | `contracts/pool` | El corazón. Recibe el pago, separa el monto del comercio, el Tip Barrio (al pool) y el fee (al admin); custodia el fondo; gestiona el índice de comercios para el mapa y deposita/rescata el fondo ocioso en el vault DeFindex. |
| **Governance** | `contracts/governance` | Democracia del barrio. Mintea tokens de residencia *soulbound*, gestiona propuestas, votación, quórum (30%) y *tally* idempotente. **Nunca implementa `transfer()`.** |
| **Treasury** | `contracts/treasury` | Ejecución trustless. Cualquiera puede llamar `execute_proposal`: el contrato verifica en Governance que la propuesta pasó, rescata el yield del vault y ordena al Pool transferir al beneficiario. No custodia fondos: orquesta. |
| **Rewards** | `contracts/rewards` | Puntos no transferibles + catálogo de premios (artesanías). Solo el Pool puede acumular puntos; el turista canjea (`redeem`) y el artesano confirma la entrega (`claim`). |

### Relaciones cross-contract

- `Pool → Rewards.accrue_points` — vía `contractimport!` (Soroban autoriza al Pool referenciándose a sí mismo; Rewards valida `caller == pool` para que nadie infle puntos).
- `Pool → Vault DeFindex` (`deposit` / `withdraw`) — cliente declarado a mano con `#[contractclient]`; el depósito usa `authorize_as_current_contract` para la sub-transferencia que hace el vault.
- `Treasury → Governance` (`tally`, `get_proposal`, `mark_executed`) y `Treasury → Pool` (`withdraw_to`, `get_vault_shares`, `redeem_from_vault`) — clientes `#[contractclient]` mantenidos en sync con las firmas reales.

### Capa Android (`data/`)

| Servicio | Responsabilidad |
|---|---|
| `WalletManager` | Custodia de claves. Prioridad: wallet guardada > demo (`BuildConfig`) > placeholder. Deriva BIP-39 / SEP-05. |
| `PasskeyWalletManager` | Smart accounts secp256r1 (WebAuthn) vía `OZSmartAccountKit` de Soneso. |
| `SorobanClient` | Fachada de los 4 contratos. Lecturas con `signer=null` (simulación), escrituras firmadas. Cachea un `ContractClient` por contrato. |
| `HorizonStream` | Balances USDC/XLM (polling + `distinctUntilChanged`), trustlines, friendbot, faucet de USDC. |
| `DefindexClient` | Lee precio-por-share, TVL y posición del vault; deposita/rescata firmando como tesorería; APY en vivo (REST opcional). |
| `RoleResolver` | Deriva el rol on-chain (residente → comerciante → turista). |
| `SecureWalletStore` · `ScvalParse` · `DeploymentsLoader` | Persistencia cifrada de la seed · parseo SCVal→Kotlin · carga de `deployments.json`. |

---

## 3. Flujos principales 🔄

### (a) Pago con Tip Barrio — el flujo central

`PayScreen` → `SorobanClient.payMerchant(...)` → `Pool.pay_merchant`:

```
tourist.require_auth()
validar amount > 0  y  tip_bps ≤ 10_000
cargar comercio (debe existir y estar verified)  → cargar su barrio

  tip      = amount * tip_bps / 10_000        (200 bps = 2%)
  fee      = amount * fee_bps  / 10_000        (50 bps  = 0.5%)
  to_merch = amount - fee

  USDC.transfer(turista → comercio, to_merch)     # 1
  USDC.transfer(turista → pool,     tip)          # 2  (si tip > 0)
  USDC.transfer(turista → admin,    fee)          # 3  (si fee > 0)

  barrio.pool_balance += tip ; total_collected += tip ; tx_count++
  si turista nuevo en el barrio → unique_tourists++

  Rewards.accrue_points(pool_self, turista, tip)  # cross-contract: +puntos
  emitir evento  payment(tourist, merchant, amount, tip)
```

> El comercio recibe `amount − fee`; el tip es **adicional** (sale aparte del turista hacia el pool). 1 punto = 0,01 USDC de tip.

### (b) Gobernanza → ejecución trustless

```
(admin barrio) mint_resident ──────────▶ residentes con token soulbound (1 = 1 voto)
       │
(residente) create_proposal(amount, recipient, 3–14 días) ──▶ Proposal Active
       │
(residentes) vote(support) ─────────────▶ votes_for / votes_against   (sin doble voto)
       │   ... pasa closes_at ...
(cualquiera) tally ──▶ quórum (for+against)·100 ≥ 30·residentes  +  mayoría for>against
       │                                                   └─▶ Passed | Rejected
(cualquiera) Treasury.execute_proposal ──▶ ¿tally == Passed?
       │            ├─ rescata yield del vault (si hay shares)
       │            ├─ Pool.withdraw_to(recipient, amount)
       │            ├─ registra Execution (auditable)
       │            └─ Governance.mark_executed
       ▼
   evento execution ──▶ Dashboard de transparencia
```

### (c) Yield con DeFindex — dos caminos

El fondo ocioso del barrio **rinde** depositándose en un vault de DeFindex (yield sobre Soroban).

- **Camino A — cross-contract (on-chain):** el contrato **Pool** deposita y rescata directamente.
  - `Pool.deposit_idle_to_vault(caller, barrio, amount)` → mueve USDC del pool al vault (con `authorize_as_current_contract` para la transferencia anidada del vault) y guarda `VaultShares(barrio)`.
  - `Treasury.execute_proposal` → llama `Pool.redeem_from_vault` para realizar el yield **antes** de pagar al beneficiario.
- **Camino B — app (`ui/treasury` + `DefindexClient`):** la pantalla **"Tesorería que rinde"** lee TVL / precio-por-share / posición (on-chain) y permite depositar/rescatar firmando como tesorería; muestra APY en vivo (REST opcional).

```
Pool (fondo ocioso)  ──deposit_idle_to_vault──▶  Vault DeFindex  ──(yield)──▶
       ▲                                                │
       └──────────── redeem_from_vault ◀────────────────┘  (rescata yield al pool_balance)
```

### (d) Onboarding de wallet nueva

Dos formas de crear cuenta, más selección de rol:

```
┌─ Passkey (WebAuthn) ──▶ smart account secp256r1 (C...) vía OZSmartAccountKit (Soneso)
│                          relayer público patrocina el deploy en testnet · Android 9+
└─ Frase semilla ──────▶ KeyPair BIP-39 / SEP-05 (G...) · seed cifrada en el dispositivo

luego: elegir rol → 🧳 Turista · 🏪 Comerciante · 🏡 Residente
```

Para wallets nuevas, un banner guía el alta on-chain en 3 pasos (re-chequea tras 1,5 s):

```
¿cuenta existe?      ── no ─▶ FUND_XLM        → friendbot fondea XLM (testnet)
¿tiene trustline USDC? ─ no ─▶ ACTIVATE_TRUST  → ChangeTrust firmado por el usuario
¿balance USDC == 0?  ── sí ─▶ REQUEST_USDC    → faucet envía USDC (simula on-ramp SEP-24)
   └─ todo ok ─▶ DONE (banner oculto)
```

### (e) RBAC dinámico — `RoleResolver`

El rol no se hardcodea: se detecta on-chain consultando los contratos.

```
resolve(address):
   1. getResident(address)           → ¿ResidentToken? → RESIDENT  (prioritario)
   2. listBarrios() → por cada barrio: listMerchants() → ¿está? → MERCHANT
   3. si nada → TOURIST
   (cachea por address; fallback a los barrios del seed si list_barrios viene vacío)
```

---

## 4. Stack tecnológico 🧰

| Capa | Tecnología | Notas |
|---|---|---|
| Contratos | **Rust + `soroban-sdk` 22.x** | `#![no_std]`, workspace Cargo con 4 crates |
| Red | **Stellar Testnet** + Soroban RPC | Horizon (lecturas/balances) + Soroban RPC (contratos) |
| Token | **USDC** vía Stellar Asset Contract (SAC) | USDC de Blend (compatible con el vault DeFindex) |
| App | **Kotlin + Jetpack Compose + Material 3** | `minSdk 26`, `targetSdk 35`, JDK 17 |
| DI | **Hilt (Dagger) + KSP** | módulo `DataModule` |
| SDK Stellar | **kmp-stellar-sdk 1.6.0 (Soneso)** | Horizon, Soroban RPC, `ContractClient`, SEP-05, smart accounts |
| Mapas | **Mapbox Maps SDK 11.x** + maps-compose | comercios geolocalizados (`lat/lng × 1e6`) |
| Wallet | **Passkey (WebAuthn secp256r1)** + fallback **seed BIP-39** | smart account OZ (Soneso) o KeyPair clásico |
| Yield | **DeFindex** (vault Soroban) | Camino A (cross-contract) + Camino B (app) |
| Concurrencia | Coroutines + StateFlow | MVVM por pantalla |
| QR | ZXing (core + embedded) | generar/escanear códigos de pago |
| TLS | **Conscrypt** | provider de Google instalado en `RaizApplication` para testnet |
| Seguridad | **EncryptedSharedPreferences + Android Keystore + BiometricPrompt** | seed cifrada, bloqueo biométrico/PIN |

### Convenciones críticas

- **Montos USDC:** siempre `i128` (Rust) / `Long` (Kotlin) en **stroops**. `1 USDC = 10_000_000 stroops` (7 decimales). Nunca floats.
- **Basis points:** `tip_bps = 200` (2%), `protocol_fee_bps = 50` (0,5%). Cálculo en orden `amount * bps / 10_000`.
- **`barrio_id`:** `BytesN<32>` (Rust) ↔ hex de 64 chars (Kotlin). Direcciones: `G…` cuentas, `C…` contratos.
- **Quórum 30%** de residentes + **mayoría simple**. Duración de propuesta: 3–14 días.
- **Puntos:** `u64`, no transferibles, `1 punto = 0,01 USDC de tip = 100_000 stroops`.

---

## 5. Integraciones 🔌

| Integración | Estado | Detalle |
|---|---|---|
| **Stellar / Soroban** | ✅ En testnet | Horizon (balances, friendbot, trustlines) + Soroban RPC (4 contratos: read por simulación, write firmado). |
| **DeFindex** | ✅ Camino A + B | Vault de yield sobre Soroban. El vault solo acepta el **USDC de Blend**, por eso los contratos custodian ese USDC. Cuentas fondeadas con el faucet de Blend. |
| **Mapbox** | ✅ | Mapa de comercios del barrio sobre Maps SDK 11.x + maps-compose. |
| **Passkey / WebAuthn (smart accounts)** | ✅ Implementado y demostrado | `OZSmartAccountKit` de Soneso (contrato OpenZeppelin). Infra pública testnet: **relayer** (patrocina fees de deploy), **indexer** y **verifier** WebAuthn. Requiere Android 9 (API 28). |
| **Anchors SEP (on/off ramp)** | 🟡 **Planeado** | SEP-10 (auth), SEP-24 (deposit/withdraw fiat↔USDC interactivo), SEP-38 (quotes RFQ). En el roadmap: anchors como **MoneyGram Access** (efectivo) o **Vibrant/Anclap** (LatAm). **Hoy** el on-ramp se simula con el faucet de Blend en testnet. |

---

## 6. Seguridad 🔐

- **Bloqueo de la app** con `BiometricPrompt` (huella/rostro **o** PIN/patrón del dispositivo, vía `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). Se re-bloquea al volver de background. Opt-in desde Perfil.
- **Biometría al confirmar pago** — la autorización del usuario se exige antes de firmar.
- **Soulbound NO transferible** — Governance jamás implementa `transfer()`; el voto no se puede comprar ni ceder (es la tesis del proyecto).
- **Montos siempre en stroops** (`i128`/`Long`) — sin floats, sin pérdida de precisión.
- **Secrets fuera del repo** — claves demo, token de Mapbox y config de passkey viven en `local.properties` (no versionado), inyectados como `BuildConfig`.
- **Seed cifrada** en el dispositivo con `EncryptedSharedPreferences` + clave del Android Keystore.
- **Validación de inputs** en cada escritura on-chain (`require_auth`, montos > 0, `tip_bps ≤ 10_000`, comercio `verified`, residencia del barrio correcto, sin doble voto, stock/puntos suficientes).

### ⚠️ Limitación conocida (bloqueante de mainnet)

La **clave del admin demo va embebida en el APK** (`BuildConfig.DEMO_ADMIN_SECRET`) para poder demostrar el alta de comercios y el mint de residentes sin coordinación offline. **No publicar como release.** El fix post-hackathon es mover esa autoridad a un **backend/relayer** que firme las operaciones de admin (registro de comercios, mint de residencia, faucet), de modo que la app del usuario nunca tenga la clave del admin.

---

## 7. Contratos desplegados (Stellar Testnet) 📜

| Contrato | Dirección | Explorer |
|---|---|---|
| **Pool** | `CAKYU5HW5QPLAE5YBHH5L5P433VE3RMA7OGAZV2OQCSATD57TXEVN2FK` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CAKYU5HW5QPLAE5YBHH5L5P433VE3RMA7OGAZV2OQCSATD57TXEVN2FK) |
| **Governance** | `CAENXDX77SHDLNPXTQDV4M6W43SVHEJWOGOBQT5XHXDPFEO6PNB77PVE` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CAENXDX77SHDLNPXTQDV4M6W43SVHEJWOGOBQT5XHXDPFEO6PNB77PVE) |
| **Treasury** | `CDGGFSV74EGBEUQWLZ5OMQZJUPXBI7BYCNZJRMCGYEKZEPN3QBWQGPXA` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CDGGFSV74EGBEUQWLZ5OMQZJUPXBI7BYCNZJRMCGYEKZEPN3QBWQGPXA) |
| **Rewards** | `CD5OET7FPJAWPID5DCBYRHDJXNICXAPLAWDRDA3NCS5IIBACEW2I6PPT` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CD5OET7FPJAWPID5DCBYRHDJXNICXAPLAWDRDA3NCS5IIBACEW2I6PPT) |
| USDC SAC (Blend) | `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU) |
| DeFindex Vault | `CBMVK2JK6NTOT2O4HNQAIQFJY232BHKGLIMXDVQVHIIZKDACXDFZDWHN` | [↗ ver](https://stellar.expert/explorer/testnet/contract/CBMVK2JK6NTOT2O4HNQAIQFJY232BHKGLIMXDVQVHIIZKDACXDFZDWHN) |

> Red: **testnet** · `protocol_fee_bps = 50` (0,5%) · desplegados el **2026-06-29** con la identidad `raiz-admin` (`GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P`). El Pool expone **`list_barrios`** (RBAC dinámico). USDC = el de **Blend** (el mismo que acepta el vault DeFindex), no un USDC propio.

### Eventos on-chain (alimentan el dashboard de transparencia)

| Contrato | Evento | Topics → Data |
|---|---|---|
| Pool | `payment` | `(symbol_short!("payment"), barrio_id)` → `(tourist, merchant, amount, tip)` |
| Pool | `vault_dep` / `vault_red` | `(…, barrio_id)` → `(amount, shares)` / `(shares, got)` |
| Governance | `resident` · `proposal` · `vote` · `tally` | altas de residencia, propuestas, votos y resultados |
| Treasury | `execution` | `(symbol_short!("execution"), barrio_id)` → `(proposal_id, amount, recipient)` |
| Rewards | `redeem` · `claim` | canjes y entregas de premios |

---

## 8. Pipeline / Cómo correr 🚀

### Requisitos

- Rust toolchain con target `wasm32-unknown-unknown` + **Stellar CLI 23.x**.
- Android Studio (JDK **17/21** — no 25; usa el JBR de Android Studio).
- Node (para parsear `deployments.json` en los scripts de seed).

### 1) Contratos — build y test

```bash
cd contracts

# Build a wasm. IMPORTANTE: usar `stellar contract build` (target wasm32v1-none).
# `cargo build --target wasm32-unknown-unknown` NO sirve para deploy: emite
# instrucciones reference-types que el host de Soroban rechaza.
# (rewards se compila también a wasm32-unknown-unknown para el contractimport! del Pool)
cargo build --release --target wasm32-unknown-unknown -p rewards
stellar contract build

# Tests del workspace (55/55 pasando)
cargo test
```

> Slash commands equivalentes: `/build-contracts`, `/test-contracts`.

### 2) Deploy + seed a testnet

```bash
# Asegura la identidad raiz-admin, compila, despliega los 4 contratos +
# referencia el USDC SAC de Blend + el vault DeFindex, inicializa en orden
# (Rewards → Pool → Governance → Treasury) y escribe deployments.json.
scripts/deploy_testnet.sh

# Pobla: 3 barrios, 9 comercios (lat/lng reales), 9 residentes soulbound,
# 6 pagos con tip, depósito de prueba al vault (Camino A), 3 propuestas con
# votos y 6 rewards. Fondea cuentas con el faucet de USDC de Blend.
scripts/seed_testnet.sh
```

> Slash commands: `/deploy-testnet`, `/seed-testnet`. Los deploys a testnet son *flaky* en ráfaga (propagación RPC + rate-limit): ambos scripts **reintentan** cada operación.

### 3) App Android

```bash
cd android
./gradlew :app:assembleDebug
```

Crea `android/local.properties` con tus claves (no se versiona). Solo **nombres**, sin valores:

```properties
# Claves demo (secrets S...) para demostrar los 3 roles sin coordinar offline
raiz.tourist.secret=
raiz.resident.secret=
raiz.admin.secret=

# Mapbox public token (pk.*) para descargar tiles del mapa
mapbox.access.token=

# DeFindex API key (OPCIONAL) — solo para APY en vivo; sin ella la pantalla
# Yield funciona igual con datos on-chain
defindex.api.key=

# Passkey / smart account (WebAuthn). rpId debe coincidir con el dominio de
# assetlinks.json en producción. Vacío → el botón de passkey se oculta.
passkey.rp.id=
passkey.rp.name=RAIZ
```

---

## 9. Estado actual y roadmap 📍

### ✅ Hecho (código corriendo, no promesas)

- **4 contratos** desplegados en testnet + **55 tests** pasando.
- **App Android** con **7 pantallas**: Wallet (+ RAÍZ Passport), Pagar, Premios, Mapa (Mapbox), Dashboard de transparencia, Tesorería/Yield y Perfil — más onboarding (Welcome / crear / importar / passkey / elegir rol) y alta de comercio.
- **Flujos verificados end-to-end on-chain:** pago con Tip Barrio + puntos, votación, ejecución trustless de propuesta, alta de comercio, onboarding de wallet nueva con rampa de USDC.
- **DeFindex** Camino A (cross-contract Pool↔vault, auth anidada) + Camino B (app deposita/rescata, muestra TVL/APY).
- **RBAC dinámico** (`RoleResolver` on-chain) + **seguridad fase 1** (bloqueo biométrico/PIN, seed cifrada).
- **Passkey smart-wallet** (`OZSmartAccountKit` de Soneso) **implementado y demostrado**.

### 🛣️ Roadmap post-hackathon

- **Admin → backend/relayer** (quitar la clave admin del APK) — requisito de **mainnet**.
- **Anchors SEP** reales: SEP-10/24/38 para on/off ramp fiat↔USDC (MoneyGram, Vibrant/Anclap).
- **Passkey con dominio propio** — hoy `github.io` choca con la Public Suffix List para el `rpId`; se resuelve con dominio propio + `assetlinks.json`.
- **KYC de residencia (SEP-12)** en vez del mint manual del admin.
- **IPFS** para las imágenes de premios (hoy URLs).
- **`tx_hash` real** de Stellar en las `Execution` (hoy es un sha256 determinístico) y **mainnet**.

---

## 10. Estructura del repositorio 🗂️

```
Protocolo_Raiz/
├── contracts/                       # Workspace Cargo (Rust + soroban-sdk)
│   ├── pool/        src/lib.rs       # pagos, tip split, pool, comercios, vault DeFindex
│   ├── governance/  src/lib.rs       # soulbound, propuestas, voto, tally, quórum
│   ├── treasury/    src/lib.rs       # execute_proposal trustless, log de ejecuciones
│   └── rewards/     src/lib.rs       # puntos, premios, redeem, claim
├── android/                          # App Kotlin (Jetpack Compose + Hilt)
│   └── app/src/main/java/com/raiz/app/
│       ├── data/
│       │   ├── stellar/              # WalletManager, PasskeyWalletManager,
│       │   │                         #   SorobanClient, HorizonStream,
│       │   │                         #   DefindexClient, RoleResolver, ScvalParse…
│       │   ├── security/             # AppLock (biométrico/PIN)
│       │   └── model/                # data classes espejo de los structs Rust
│       └── ui/                       # wallet, pay, rewards, map, dashboard,
│                                     #   treasury(yield), profile, welcome,
│                                     #   become_merchant, security
├── scripts/
│   ├── deploy_testnet.sh             # despliegue de los 4 contratos
│   └── seed_testnet.sh               # datos demo (barrios, comercios, residentes…)
├── docs/                             # spec, arquitectura técnica, pitch, guías
│   ├── raiz_v2_spec_contratos.md     # spec canónica de los 4 contratos
│   ├── ARQUITECTURA_TECNICA.md       # estado real implementado, verificado vs código
│   ├── RaizModels.kt                 # modelos Kotlin espejo de los structs Rust
│   └── presentacion/pitch.md         # guion del pitch (7–10 min)
├── deployments.json                  # IDs de contratos en testnet (versionado)
├── CLAUDE.md                         # convenciones, comandos y subagentes del proyecto
└── README.md
```

---

## Licencia

MIT.

---

> **"2% de cada pago, gobernado por el barrio, verificable en la cadena."**
> RAÍZ pone la decisión on-chain, en manos de quien vive ahí — pago, gobernanza y transparencia, en una sola app, sobre Stellar. 🌱
