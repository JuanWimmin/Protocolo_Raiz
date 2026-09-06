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
| Contratos | Rust + `soroban-sdk` 26.1.1 | 5 crates en un workspace bajo `contracts/` · Rust pineado 1.97.1 (`contracts/rust-toolchain.toml`) · CI en `.github/workflows/contracts.yml` |
| App | Android nativo, Kotlin, Jetpack Compose | minSdk 26, target 35, Material 3, Hilt |
| Stellar SDK | `kmp-stellar-sdk` (Soneso) | Horizon, Soroban RPC, smart accounts |
| Mapas | Mapbox Maps SDK 11.x + maps-compose | Ver `docs/raiz_mapbox_setup.md` |
| Wallet | Passkey (WebAuthn) + fallback frase semilla (BIP-39) | `WalletManager` con ambos |
| Anchors | SEP-10 auth, SEP-24 on/off ramp, SEP-38 RFQ | |

---

## Estructura del monorepo

```
Protocolo_Raiz/
├── contracts/                  # Workspace Cargo (Rust pineado 1.97.1 vía rust-toolchain.toml)
│   ├── Cargo.toml              # workspace + soroban-sdk dep
│   ├── pool/                   # pagos y fondo del barrio
│   ├── governance/             # soulbound + votación
│   ├── treasury/               # ejecución trustless
│   ├── rewards/                # puntos + premios
│   └── yield_adapter/          # BlendAdapter: yield del fondo contra el pool USDC de Blend v2
├── android/                    # App Kotlin (Jetpack Compose + Hilt)
├── scripts/                    # deploy_testnet.sh, seed_testnet.sh, setup_admin_multisig.sh
├── docs/                       # Fuente de verdad y guías
│   ├── raiz_v2_spec_contratos.md
│   ├── RaizModels.kt           # modelos Kotlin espejo de los structs Rust
│   ├── raiz_mapbox_setup.md
│   ├── raiz_prompt_claude_code.md   # prompt maestro original
│   ├── NuevaPropuesta/         # roadmap canónico F1–F6 (propuesta_raiz_ahorro_enjambre.md §8 + plan_trabajo_raiz.md)
│   ├── ESTADO_PROYECTO_2026-07-31.md  # foto del estado para nuevos colaboradores
│   └── pre_vistas/             # HTMLs con specs visuales de pantallas
├── .github/workflows/contracts.yml  # CI: build + tests de los contratos
├── deployments.json            # IDs de contratos tras deploy (se versiona — fuente canónica)
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

### Colchón líquido (yield)

- `CushionBps` en Pool: por defecto 2000 (20%) del fondo se queda líquido. `set_cushion_bps` solo admin.
- `deposit_idle_to_vault` falla con `InsufficientLiquidity` si el depósito violaría el colchón.
- El yield fluye Pool → `yield_adapter` (BlendAdapter) → pool USDC de Blend v2. Nunca Blend directo desde Pool.

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
- `Pool.deposit_idle_to_vault` → `(symbol_short!("vault_dep"), barrio_id), (amount, shares)`
- `Pool.redeem_from_vault` → `(symbol_short!("vault_red"), barrio_id), (shares, got)`
- `Treasury.execute_proposal` → `(symbol_short!("execution"), barrio_id), (proposal_id, amount, recipient)`
- `Rewards.redeem` → `(symbol_short!("redeem"), barrio_id), (tourist, reward_id, redemption_id)` — ojo: "redemption" nunca cupo en `symbol_short` (máx. 9 chars)
- `YieldAdapter.deposit` / `withdraw` → `(symbol_short!("supply") / symbol_short!("withdrw"), barrio_id)`

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
/seed-testnet               # corre scripts/seed_testnet.sh (3 barrios, comercios, etc.)
```

Comandos directos útiles:

```bash
# Build de un contrato puntual (en contracts/<crate>/)
cargo build --release --target wasm32-unknown-unknown -p pool

# Test de un contrato puntual
cargo test -p pool

# Stellar CLI (instalada 23.2.1 — funciona; upgrade a 27.1.0 recomendado, pendiente)
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
- ❌ Implementar el contrato Rewards a medias dejando Pool roto (Pool lo llama vía `#[contractclient]`).
- ❌ Cambiar la paleta o el flujo de las 6 pantallas sin avisar — ya están aprobadas.
- ❌ Usar Google Maps "de paso" si Mapbox da guerra; primero discutir.
- ❌ Implementar `transfer()` en Governance (es soulbound — viola la tesis).
- ❌ Hardcodear `pk.*` o `sk.*` de Mapbox en el repo (van en `~/.gradle/gradle.properties`).
- ❌ Llamar a Blend directamente desde Pool — SIEMPRE vía la interfaz `YieldAdapter`.

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

- **`contractimport!` ya no existe en el repo** (eliminado en F1: Pool→Rewards
  es un `#[contractclient]` a mano y murió el build en dos pasos). Si algún día
  vuelve, recuerda: resuelve rutas relativas al Cargo.toml del crate
  (CARGO_MANIFEST_DIR), no al archivo fuente.

- **Logs Android Info/Debug filtrados**: en algunos dispositivos
  (especialmente Vivo) los logs `Log.i` se filtran por defecto. Forzar
  visibilidad con: `adb shell setprop log.tag.RAIZ VERBOSE` antes de ejecutar.

- **USDC del fondo y yield (Blend v2)**: DeFindex fue **eliminado por completo
  en F1** (contratos, app y scripts). El fondo custodia el USDC de **Blend**
  (issuer `GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56`, SAC
  `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU`) — no un USDC
  propio — y el yield va directo al pool USDC de **Blend v2 TestnetV2**
  (`CCEBVDYM32…`) vía el crate `yield_adapter` (BlendAdapter). Fondear cuentas
  con el faucet de Blend
  (`GET https://ewqw4hx7oa.execute-api.us-east-1.amazonaws.com/getAssets?userId=<G>`
  → firmar el XDR → enviar). El admin NO puede acuñarlo.

- **Protocol 23+ (testnet en P28): las entradas archivadas se AUTO-RESTAURAN en la tx**. La
  simulación ya no devuelve `restorePreamble`: mete las entradas caducadas en el `readWrite` del
  footprint con el fee de restauración, y el SDK Soneso (`invoke(signer=null)`) lo rechaza con
  "Signer required for write call" → la app ve 0 comercios / 0 puntos / 0 shares. Remedio
  aplicado el 2026-09-06: una tx firmada por el admin por cada lectura afectada (auto-restore) +
  `ExtendFootprintTtl` de las 74 claves a +1.5M ledgers (script en la sesión, ver
  `docs/evidencia_sow/d1/regresion_dispositivo.md` incidencia 1). Hay que renovar el TTL antes de
  ~3 meses o hacer que las lecturas de la app toleren `readWrite` (H2).
- **Lecturas que el host trata como WRITE**: cualquier lectura sobre entradas
  de Soroban con TTL expirado (~1 mes) añade footprint de restore →
  `invoke(signer=null)` falla con "Signer required for write call". Aplica hoy a
  `get_reserve` / `get_positions` del pool de Blend con TTL vencido (y aplicaba
  al vault DeFindex antes de F1). Preferir lecturas puras y calcular derivados
  en el cliente; para datos viejos, un reseed con TTL fresco lo arregla.

- **Deploys/invokes a testnet son flaky en ráfaga**: `deploy_testnet.sh` y
  `seed_testnet.sh` reintentan cada operación (propagación RPC + rate-limit). Si
  un deploy "se cuelga" o un init da "Contract not found", es propagación — reintenta.

- **`authorize_as_current_contract` en soroban-sdk 26.x**: debe invocarse
  INMEDIATAMENTE antes de la llamada que dispara la sub-invocación autorizada.
  Intercalar lecturas cross-contract (p. ej. `get_reserve` / `get_positions`)
  entre la autorización y el submit produce `Error(Auth, InvalidAction)` en
  tests. Documentado en `contracts/yield_adapter/src/lib.rs`.

---

## Estado actual (2026-09-06)

- **F1 completada** (yield vía BlendAdapter en testnet, DeFindex eliminado). 85 tests verdes.
- **PRIORIDAD ABSOLUTA: sprint SOW Instaward (D1 relayer, D2 tx hash real, D3 SEP-10/24).**
  Plan, decisiones y prompts: `docs/PLAN_CLAUDE_CODE_SOW.md`. Revisión de contexto:
  `docs/REVISION_2026-08-27.md` (hallazgos H1–H10).
- **D1 (WP1):** relayer público en https://github.com/JuanWimmin/raiz-relayer (TS + Fastify +
  stellar-sdk 17, 150 tests, integración real en testnet). La app (0.2.0) consume el relayer vía
  `data/relayer/RelayerClient` y el APK release **no lleva ninguna clave `S…`** (evidencia en
  `docs/evidencia_sow/d1/`). Config de la app: `raiz.relayer.key` (obligatoria) y
  `raiz.relayer.url` (default `https://raiz-relayer.fly.dev`) en `android/local.properties`.
  Pendiente: deploy en Fly (`fly deploy --ha=false`, una sola máquina) y regresión en dispositivo.
- **D2 (WP2):** ejecuciones de evidencia sembradas el 6-sep (`docs/evidencia_sow/d2/`): #1 Norte y
  #2 Costa ejecutadas; #3 Centro y #4 Norte votadas, ejecutables desde el **9-sep**. Código
  pendiente (diseño en `docs/PLAN_CLAUDE_CODE_SOW.md` WP2 + notas de sesión).
- F2 (`savings_circle`) queda EN PAUSA hasta entregar la evidencia del SOW; solo su spec
  puede avanzar (WP5).
- Regla nueva: todo contrato nuevo nace con gestión de TTL, `__constructor`, snapshot de
  censo y strings acotados (herencia de la revisión H2/H4/H8).
- Skills de Stellar disponibles en Claude Code (plugin `stellar-dev`): `smart-contracts`
  (storage/TTL/auth/testing/security), `dapp` (stellar-sdk JS — útil para el relayer D1),
  `data` (RPC/Horizon/getEvents — D2), `standards` (SEPs — D3), `assets`, `agentic-payments`, `zk-proofs`.
- La landing (`landing/`) se publica a mano en el repo Pages `JuanWimmin/JuanWimmin.github.io`
  (raizapp.xyz): tras cada cambio en `landing/` hay que copiar los HTML allí. Los IDs de contratos
  viven en un objeto `DEPLOYMENTS` al inicio del `<script>` de cada HTML — sincronizar con
  `deployments.json` en cada redeploy.

### Próximo paso

- **WP1 en cierre (2026-09-06):** falta deploy del relayer en Fly y regresión en dispositivo
  (`docs/evidencia_sow/d1/regresion_dispositivo.md`). WP activo a partir del 9-sep: **WP2 — D2
  tx hash real** según `docs/PLAN_CLAUDE_CODE_SOW.md`. Al cerrar cada WP, actualizar esta línea.
