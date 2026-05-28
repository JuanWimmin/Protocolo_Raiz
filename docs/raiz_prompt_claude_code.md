# PROMPT MAESTRO — Claude Code · Proyecto RAÍZ

> Pega TODO este contenido en Claude Code, en una carpeta vacía.
> Adjunta también los archivos `raiz_v2_spec_contratos.md` y `RaizModels.kt`.
> Claude Code los usará como fuente de verdad del modelo de datos.

---

Construye un monorepo para **RAÍZ**, una red de pagos turísticos sobre Stellar que redirige un porcentaje de cada pago a un fondo comunitario gobernado por los residentes del barrio. Es un MVP para un hackathon. Todo on-chain.

Adjunté dos archivos de contexto que son la FUENTE DE VERDAD:
- `raiz_v2_spec_contratos.md` — especificación de los 4 contratos Soroban y el modelo de datos.
- `RaizModels.kt` — las data classes Kotlin que son espejo exacto de los structs Rust. Respétalas.

## Estructura del monorepo

```
raiz/
├── contracts/              # Contratos Soroban en Rust
│   ├── pool/
│   ├── governance/
│   ├── treasury/
│   ├── rewards/
│   └── Cargo.toml          # workspace
├── android/                # App Kotlin (Jetpack Compose)
│   └── app/src/main/java/com/raiz/app/
├── scripts/                # Deploy y seed
│   ├── deploy_testnet.sh
│   └── seed.ts
├── deployments.json        # IDs de contratos tras deploy
├── DEMO.md                 # Guion de demo de 90 segundos
└── README.md               # En español
```

## PARTE 1 — Contratos Soroban (Rust)

Usa `soroban-sdk` 26.x (la última estable). Workspace con 4 crates. Implementa EXACTAMENTE los structs y funciones de `raiz_v2_spec_contratos.md`:

### Contrato `pool`
- Storage: Admin, Barrio(id), Merchant(addr), UsdcToken, ProtocolFeeBps
- `pay_merchant(tourist, merchant, amount, tip_bps)`: transfiere 98% al merchant, el tip al pool del barrio, llama cross-contract a `rewards.accrue_points`, emite evento `payment`.
- `register_merchant`, `get_pool_balance`, `get_barrio`, `list_merchants` (para el mapa, incluye lat/lng).
- Cross-contract call al contrato Rewards (recibe su Address en init).

### Contrato `governance`
- Soulbound tokens: `mint_resident` (solo admin del barrio), SIN función transfer.
- `create_proposal`, `vote` (1 residente = 1 voto, previene doble voto), `tally` (quórum 30%, mayoría simple).
- `get_proposal`, `list_active_proposals`.
- Duración de propuesta configurable (3-14 días).

### Contrato `treasury`
- `execute_proposal`: trustless. Consulta `governance.tally`, si Passed transfiere del pool al recipient, registra Execution, marca proposal Executed.
- `get_execution_log`.

### Contrato `rewards`
- Ratio: 1 punto por cada 0.01 USDC de tip (tip_stroops / 100_000). USDC tiene 7 decimales.
- `accrue_points` (solo callable por el contrato Pool), `list_rewards`, `get_points`, `redeem` (quema puntos, decrementa stock), `claim_redemption` (el artesano marca entrega).

Escribe tests unitarios con `soroban-sdk` testutils para CADA contrato: pago con tip, mint+vote+tally, ejecución de propuesta aprobada, acumular+canjear puntos.

## PARTE 2 — App Android (Kotlin + Jetpack Compose)

- minSdk 26, targetSdk 35, Kotlin 2.x, Compose BOM reciente, Material 3.
- DI con Hilt. Arquitectura MVVM + repositories + use cases.
- SDK Stellar: `kmp-stellar-sdk` de Soneso (soporta Horizon, Soroban RPC, smart accounts con passkey). Si no resuelve por Gradle, documenta el repositorio Maven.
- Mapas: **Mapbox** (Mapbox Maps SDK for Android). Documenta dónde poner el token de Mapbox.
- Wallet: passkey (WebAuthn / androidx.credentials) con FALLBACK a frase semilla (BIP-39). Implementa `WalletManager` con ambos métodos.

### Estructura (sigue RaizModels.kt para los modelos)
```
data/
  stellar/  WalletManager.kt, SorobanClient.kt, AnchorService.kt (SEP-24/38), HorizonStream.kt
  repository/  PaymentRepository, GovernanceRepository, RewardsRepository, MerchantRepository
  model/  (usa RaizModels.kt adjunto)
ui/
  wallet/  WalletScreen, PayScreen, QrScannerScreen
  map/  BarrioMapScreen
  rewards/  RewardsScreen, RedeemScreen
  governance/  ProposalsScreen, VoteScreen
  transparency/  DashboardScreen
domain/  use cases
di/  Hilt modules
```

### Pantallas (6) — diseño aprobado, implementa en Compose:
1. **Home/Wallet**: balance USDC card negro, puntos + aporte en 2 stats, botón "Escanear y pagar", bottom nav (Inicio/Mapa/Premios/Perfil). Balance vía HorizonStream SSE.
2. **PayScreen**: merchant card con badge verificado, toggle Tip Barrio (2%, muestra puntos a ganar), desglose subtotal/tip/total, botón "Confirmar con huella" (passkey). Usa el model PaymentPreview.
3. **RewardsScreen**: LazyColumn de Reward con barra de progreso (points/pointsCost), botón canjear si alcanza, estado agotado.
4. **BarrioMapScreen**: Mapbox con pines por categoría desde Pool.listMerchants(). Tap → bottom sheet con aporte acumulado y botón pagar.
5. **ProposalsScreen/VoteScreen**: LazyColumn de Proposal, barra de votos, botones a favor/en contra vía Governance.vote(), check de quórum.
6. **DashboardScreen** (transparencia, pública): stats del barrio, barra de uso del fondo, ejecuciones on-chain, link a Stellar Expert.

Paleta: negro #1a1a1a, amarillo #FBBF24, púrpura #534AB7, verde #0F6E56, fondo #FAFAF7. Mobile-first.

## PARTE 3 — Scripts y demo

- `scripts/deploy_testnet.sh`: compila los 4 contratos, los despliega en Testnet con stellar-cli, guarda IDs en `deployments.json`.
- `scripts/seed.ts`: crea 3 barrios (Centro Histórico, Barrio Norte, Costa Vieja), registra ~6 comercios con lat/lng reales por barrio, mintea soulbound a 5 residentes por barrio, crea 2 propuestas activas por barrio, simula 20 pagos con tip, crea 3 rewards (artesanías) por barrio.
- `DEMO.md`: guion de 90 segundos — turista paga con tip → pool crece → residente vota → propuesta pasa → treasury ejecuta → dashboard se actualiza.
- `README.md` en español con setup, deploy, arquitectura (diagrama Mermaid).

## Cómo proceder

1. Empieza por el workspace de contratos y el contrato `pool` con sus tests. Compila y corre tests antes de seguir.
2. Luego governance, treasury, rewards, cada uno con tests.
3. Deploy a Testnet, llena deployments.json.
4. Después la app Android: primero data layer (WalletManager, SorobanClient), luego las pantallas una por una.
5. Pregúntame antes de decisiones de arquitectura grandes. Muévete rápido en boilerplate.

Empieza ahora con el workspace Cargo y el contrato pool.
