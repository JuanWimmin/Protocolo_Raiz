# CLAUDE.md — Proyecto RAÍZ

> Lee primero `docs/raiz_v2_spec_contratos.md` y `docs/RaizModels.kt`. Son la fuente de verdad.
> Esta guía es para orientarte rápido y respetar las convenciones del proyecto.

---

## Qué es RAÍZ

Red de pagos turísticos sobre **Stellar** que redirige un porcentaje (Tip Barrio, 2% por defecto)
de cada pago a un **fondo comunitario gobernado por los residentes del barrio** vía soulbound
tokens. Todo on-chain. Es un MVP para hackathon — prioriza simplicidad y demo funcional sobre
optimizaciones.

**Flujo central:** turista paga al comercio → 2% al pool del barrio → residentes votan
propuestas → treasury ejecuta trustless si pasa → dashboard muestra todo on-chain.

---

## Stack

| Capa | Tecnología | Notas |
|---|---|---|
| Contratos | Rust + `soroban-sdk` 22.x | 4 crates en un workspace bajo `contracts/` |
| App | Android nativo, Kotlin, Jetpack Compose | minSdk 26, target 35, Material 3, Hilt |
| Stellar SDK | `kmp-stellar-sdk` (Soneso) | Horizon, Soroban RPC, smart accounts |
| Mapas | Mapbox Maps SDK 11.x + maps-compose | Ver `docs/raiz_mapbox_setup.md` |
| Wallet | Passkey (WebAuthn) + fallback frase semilla (BIP-39) | `WalletManager` con ambos |
| Anchors | SEP-10 auth, SEP-24 on/off ramp, SEP-38 RFQ | |

---

## Estructura del monorepo

```
Protocolo_Raiz/
├── contracts/                  # Workspace Cargo
│   ├── Cargo.toml              # workspace + soroban-sdk dep
│   ├── pool/                   # pagos y fondo del barrio  ← borrador inicial ya escrito
│   ├── governance/             # soulbound + votación
│   ├── treasury/               # ejecución trustless
│   └── rewards/                # puntos + premios
├── android/                    # App Kotlin (vacío, se genera con Android Studio)
├── scripts/                    # deploy_testnet.sh, seed.ts
├── docs/                       # Fuente de verdad y guías
│   ├── raiz_v2_spec_contratos.md
│   ├── RaizModels.kt           # modelos Kotlin espejo de los structs Rust
│   ├── raiz_mapbox_setup.md
│   ├── raiz_prompt_claude_code.md   # prompt maestro original
│   └── pre_vistas/             # HTMLs con specs visuales de pantallas
├── deployments.json            # IDs de contratos tras deploy (se versiona)
├── DEMO.md                     # guion de 90 segundos
├── README.md                   # setup en español
└── .claude/
    ├── agents/                 # subagentes especializados (soroban-, kmp-stellar-, etc.)
    ├── commands/               # slash commands
    └── settings.json           # permisos pre-aprobados
```

---

## Convenciones críticas

### Montos USDC

- **Siempre en stroops (i128 / Long)**, nunca en floats. 1 USDC = 10_000_000 stroops (7 decimales).
- En Kotlin: `Long` + helpers `.toUsdc()` / `.toStroops()` (ya están en `RaizModels.kt`).
- En Rust: `i128`. Multiplica/divide en orden correcto (`amount * bps / 10_000`) para evitar precisión.

### Tip y fee

- `tip_bps`: basis points (200 = 2%, 10_000 = 100%). Validar `tip_bps <= 10_000`.
- `ProtocolFeeBps`: configurable, 50 (0.5%) por defecto.
- En `pay_merchant`: el comercio recibe `amount - fee`, el pool recibe `tip`, el admin recibe `fee`.

### Puntos

- 1 punto por cada **0.01 USDC de tip** = `tip_stroops / 100_000`.
- Solo el contrato Pool puede llamar `rewards.accrue_points` (validar caller).
- Los puntos NO son token transferible, viven como `Points(Address) -> u64` en storage.

### IDs y direcciones

- `barrio_id`: `BytesN<32>` en Rust, `String` (hex de 64 chars) en Kotlin.
- Direcciones Stellar: `G...` (cuentas), `C...` (contratos). Siempre String en Kotlin.
- `lat`/`lng` se guardan como `i32` (lat * 1e6) en el contrato para evitar floats en Soroban.

### Quórum y votación

- Quórum: 30% de residentes del barrio (`votes_total * 100 / resident_count >= 30`).
- Mayoría simple: `votes_for > votes_against`.
- Duración de propuesta: configurable 3-14 días.
- Soulbound: NUNCA implementes `transfer()` en Governance. 1 residente = 1 voto, sin re-mint.

### Eventos (importantes para el dashboard de transparencia)

- `Pool.pay_merchant` → `(symbol_short!("payment"), barrio_id), (tourist, merchant, amount, tip)`
- `Treasury.execute_proposal` → `(symbol_short!("execution"), barrio_id), (proposal_id, amount, recipient)`
- `Rewards.redeem` → `(symbol_short!("redemption"), barrio_id), (tourist, reward_id)`

### Paleta UI (no negociable)

| Color | Hex | Uso |
|---|---|---|
| Negro principal | `#1a1a1a` | Cards primarias, balance USDC |
| Amarillo | `#FBBF24` | CTA, botón "Escanear y pagar" |
| Púrpura | `#534AB7` | Acentos secundarios, badges |
| Verde | `#0F6E56` | Tip Barrio, estados de éxito |
| Fondo | `#FAFAF7` | Background general |

---

## Sub-agentes disponibles

Lánzalos con la herramienta Agent cuando la tarea calce:

| Agente | Cuándo usarlo |
|---|---|
| `soroban-contract-dev` | Implementar/modificar contratos Rust, fix de errores rustc, patrones soroban-sdk |
| `kmp-stellar-integration` | WalletManager, SorobanClient, conversión SCVal, llamadas a contratos desde Android |
| `spec-auditor` | Verificar que el código coincide con `docs/raiz_v2_spec_contratos.md` y `RaizModels.kt` |
| `compose-ui-builder` | Pantallas Compose, navegación, Hilt, Material 3, paleta RAÍZ |

---

## Comandos clave

```bash
# Contratos
/test-contracts             # cargo test todo el workspace
/build-contracts            # cargo build --release --target wasm32-unknown-unknown
/spec-check                 # lanza spec-auditor contra el código actual

# Despliegue (cuando estemos listos)
/deploy-testnet             # corre scripts/deploy_testnet.sh
/seed-testnet               # corre scripts/seed.ts (3 barrios, 6 comercios, etc.)
```

Comandos directos útiles:

```bash
# Build de un contrato puntual (en contracts/<crate>/)
cargo build --release --target wasm32-unknown-unknown -p pool

# Test de un contrato puntual
cargo test -p pool

# Stellar CLI (instalado v23.2.1)
stellar contract deploy --wasm target/wasm32-unknown-unknown/release/pool.wasm --network testnet
```

---

## Decisiones tomadas (no re-discutir)

1. Mapas → **Mapbox**, no Google Maps (decisión del prompt maestro). Plan B documentado.
2. SDK Stellar → **kmp-stellar-sdk de Soneso**. Si no resuelve por Gradle, ver `docs/raiz_mapbox_setup.md` para patrón de repo Maven privado (adaptar a Soneso).
3. Wallet → **Passkey con fallback semilla**. No solo seed.
4. Imágenes de premios → **URLs** para MVP, IPFS en v2 (no implementar IPFS ahora).
5. KYC residentes → **mock** (admin del barrio mintea manualmente). SEP-12 es v2.

---

## Lo que NO hacer

- ❌ Inventar campos en los structs. Si falta algo, **propónlo** y actualiza la spec antes.
- ❌ Crear archivos .md de planificación/decisiones a menos que el usuario lo pida.
- ❌ Implementar el contrato Rewards a medias dejando Pool roto (Pool lo importa).
- ❌ Cambiar la paleta o el flujo de las 6 pantallas sin avisar — ya están aprobadas.
- ❌ Usar Google Maps "de paso" si Mapbox da guerra; primero discutir.
- ❌ Implementar `transfer()` en Governance (es soulbound — viola la tesis).
- ❌ Hardcodear `pk.*` o `sk.*` de Mapbox en el repo (van en `~/.gradle/gradle.properties`).

## Gotchas conocidos del proyecto

- **TLS en Android contra Stellar testnet**: el cert de `*.stellar.org`
  está firmado por "Sectigo Public Server Authentication CA DV R36". La
  mayoría de Android lo valida bien con Conscrypt instalado en
  `RaizApplication.installSecurity()`. CONFIRMADO funciona end-to-end en
  Motorola G04 / Android 14 (lee Pool balance Centro = 0.3 USDC + balance
  USDC vía Horizon).
  Falla en algunos OEMs (Vivo V2110 / Android 13) con "Trust anchor for
  certification path not found" porque su trust store no incluye Sectigo
  Y Ktor con engine CIO ignora el SSLContext default + setea para el
  TrustManager custom. No vale la pena perseguir esos casos hasta tener
  ciclos: probar primero en otro dispositivo.

- **Versiones Kotlin / KSP / Hilt acopladas**: Stellar SDK 1.6.0 trae
  kotlinx-serialization con metadata Kotlin 2.2 (no leíble por K2.0). Si
  subes Kotlin, KSP debe ir al pin equivalente (`kotlin-X.Y.Z` ↔
  `ksp-X.Y.Z-X.X.X`) y Hilt ≥ 2.56 para que su procesador KSP no falle con
  "Expected @AndroidEntryPoint to have a value".

- **wasm32-unknown-unknown vs wasm32v1-none**: para deploy a Soroban hay que
  usar `stellar contract build` (target `wasm32v1-none`) y NO `cargo build
  --target wasm32-unknown-unknown`. El segundo emite instrucciones
  `reference-types` que el host de Soroban rechaza.

- **`contractimport!` resuelve rutas relativas al Cargo.toml del crate**
  (CARGO_MANIFEST_DIR), no al archivo. Desde `contracts/pool/Cargo.toml` a
  `target/` del workspace son DOS niveles arriba en la jerarquía de archivos
  pero UN solo `../` en la ruta del macro.

- **Logs Android Info/Debug filtrados**: en algunos dispositivos
  (especialmente Vivo) los logs `Log.i` se filtran por defecto. Forzar
  visibilidad con: `adb shell setprop log.tag.RAIZ VERBOSE` antes de ejecutar.

- **DeFindex / Blend testnet USDC**: la integración de yield usa el vault USDC de
  DeFindex (`CBMVK2JK…`), que SOLO acepta el USDC de **Blend** (`USDC:GATALTGT…`,
  SAC `CAQCFVLOBK…`) — no el USDC propio de RAÍZ. Desde el re-deploy de Camino A
  los contratos custodian ese USDC; fondear cuentas con el faucet de Blend
  (`GET https://ewqw4hx7oa.execute-api.us-east-1.amazonaws.com/getAssets?userId=<G>`
  → firmar el XDR → enviar). El admin NO puede acuñarlo.

- **Lecturas que el host trata como WRITE**: `get_asset_amounts_per_shares` del
  vault DeFindex (y cualquier lectura sobre entradas con TTL expirado de Soroban,
  ~1 mes) añade footprint de restore → `invoke(signer=null)` falla con "Signer
  required for write call". Calcular valores desde lecturas puras (`total_supply`
  + `fetch_total_managed_funds`); para datos viejos, un reseed con TTL fresco lo arregla.

- **Deploys/invokes a testnet son flaky en ráfaga**: `deploy_testnet.sh` y
  `seed_testnet.sh` reintentan cada operación (propagación RPC + rate-limit). Si
  un deploy "se cuelga" o un init da "Contract not found", es propagación — reintenta.

---

## Estado actual (al iniciar el proyecto)

- Workspace Cargo creado con los 4 crates.
- `contracts/pool/src/lib.rs` y `test.rs` ya tienen un **borrador funcional** del contrato Pool (escrito antes en el prompt).
- `contracts/{governance,treasury,rewards}/` son stubs `ping()` que compilan, listos para implementar.
- Android: carpeta vacía. Empezaremos cuando los 4 contratos estén testeados.

### Próximos pasos sugeridos

1. Verificar que el workspace compila (`cargo check --workspace`).
2. Resolver el `contractimport!` del Pool que apunta al wasm de Rewards (definir cliente manualmente con `#[contractclient]` o cambiar a build de dos pasos).
3. Correr los tests del Pool.
4. Implementar Governance → Treasury → Rewards completos.
5. Deploy a testnet, llenar `deployments.json`.
6. Setup Android, generar proyecto con Android Studio, conectar con `kmp-stellar-sdk`.
