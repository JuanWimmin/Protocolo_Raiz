# Estado del proyecto RAÍZ — 2026-07-31

> Documento de estado canónico, redactado sobre la auditoría profunda del repo del 2026-07-31
> (contratos, app Android, docs/scripts/deploy, historial git y crítica de completitud cruzada).
> Fotografía del proyecto **justo antes de arrancar la fase F1 ("independencia de DeFindex")**
> de la nueva propuesta (`docs/NuevaPropuesta/propuesta_raiz_ahorro_enjambre.md` +
> `docs/NuevaPropuesta/plan_trabajo_raiz.md`).
>
> Repo: `C:/Blockota/Proyectos/Protocolo_Raiz` · rama `main` @ `961af79`, sincronizada con `origin/main`.

---

## 1. Resumen ejecutivo

**RAÍZ** es una red de pagos turísticos sobre Stellar: el turista paga a un comercio en USDC y un
**Tip Barrio** (2% por defecto, configurable en basis points) se redirige a un fondo comunitario
on-chain gobernado por los residentes del barrio mediante soulbound tokens. Cuatro contratos
Soroban (Pool, Governance, Treasury, Rewards) desplegados en testnet, una app Android nativa
(Kotlin + Jetpack Compose, wallet passkey WebAuthn con fallback de frase semilla), un dashboard
público de transparencia y una landing (`raizapp.xyz`) que muestra datos on-chain en vivo. El
fondo ocioso de cada barrio genera rendimiento depositado en un vault de **DeFindex** (estrategia
Blend) — exactamente la dependencia que F1 elimina.

**Lo demostrado en el hackathon PULSO** (MVP cerrado en `961af79`, 2026-07-06):

- Flujo completo on-chain: pago con tip → fondo del barrio → propuesta → voto soulbound → quórum
  30% → ejecución trustless del Treasury → dashboard de transparencia con links a Stellar Expert.
- **Passkey end-to-end en dispositivo real**: crear smart wallet WebAuthn, pagar, votar, crear
  propuesta, faucet y saldo vía SAC (commits `8260d0e`…`1094863`).
- Seed demo en testnet: 3 barrios, 9 comercios con lat/lng reales (Cartagena/Bogotá), 9 residentes,
  propuestas, votos, 6 premios y depósito de yield "Camino A" al vault DeFindex.
- Guion de demo de 90 segundos (`DEMO.md`) + landing V2 "El recibo del barrio" + deck V2.
- **58/58 tests de contratos en verde** (línea base real verificada por la auditoría; el README
  dice 55 y está desactualizado — ver §6.1).

**Qué arranca ahora: F1 — independencia de DeFindex.** Sustituir el vault DeFindex por integración
directa con **Blend V2** detrás de un trait `YieldAdapter` (crate `yield_adapter` + `BlendAdapter`
en contratos; `BlendClient` en la app, retirando `DefindexClient` y su API key REST), junto con la
migración soroban-sdk 22.x → 26.x y el quick-win de multisig 2-de-3 para el admin
(`docs/NuevaPropuesta/plan_trabajo_raiz.md`, MES 1).

> **Prioridad de roadmaps:** el roadmap por fases **F1–F6** de la nueva propuesta
> (`propuesta_raiz_ahorro_enjambre.md` §8 + `plan_trabajo_raiz.md`: F1 Blend directo, F2 Cadena de
> Barrio, F3 custodia enjambre + atestación, F4 metas/retos/sorteo, F5 enjambre frontera, F6 capa
> ZK de voto secreto) **SUSTITUYE como prioridad al roadmap del README §9** (L362–369: anchors,
> KYC, IPFS, mainnet). El README §9 sigue siendo válido como lista de pendientes de largo plazo,
> pero la secuencia de trabajo es la de la nueva propuesta. Ojo: todo `docs/NuevaPropuesta/` está
> **sin commitear** al momento de la auditoría — **RESUELTO el mismo 2026-07-31**: commit `5220c84` (§6.12).

---

## 2. Crónica del desarrollo

Fuente: auditoría 2026-07-31 del historial git.

- **64 commits** totales, historia **lineal sin merge commits** (las ramas `feat/` se integraron
  por fast-forward/rebase). Un solo autor con dos identidades (`juanpacomo`, 63 commits;
  `Juan Pablo Conrado Molina`, 1 commit vía GitHub web: `6331d74`). **Sin tags**.
- **5 días activos = 3 maratones + 2 commits de pulido**: 2026-05-28 (26 commits), 2026-06-29 (13),
  2026-06-30 (23), 2026-07-05 (1), 2026-07-06 (1). Pausa completa del 05-29 al 06-28.

| Etapa | Fecha | Qué pasó | Commits representativos |
|---|---|---|---|
| 1. Contratos Soroban | 05-28 (madrugada) | Scaffolding → Pool con tests → Governance (soulbound, voto, tally) → Treasury (execute trustless) → Rewards | `2005e94`, `b4df211`, `7437294`, `e85d1a7`, `ebd571e` |
| 2. Deploy + seed testnet | 05-28 | Primer deploy y seed con 3 barrios reales on-chain | `04a953d` |
| 3. App Android base | 05-28 | Gradle+Compose+Hilt, capa data Stellar, WalletManager, round-trip verificado en 2 dispositivos, HorizonStream, pago end-to-end | `02f7d20`, `cb4c139`, `9bde2e7`, `86de84a`, `df13e86` |
| 4. Las 6 pantallas + onboarding | 05-28 (tarde/noche) | QR/historial/perfil, voto firmado, Rewards, seed phrase, Mapbox, dashboard transparencia, onboarding on-chain, pitch v1 — MVP en un día | `f9ce17d`, `0852bfe`, `3ebe82b`, `5e6ccbb`, `c3e5a31` |
| 5. DeFindex + seguridad + RBAC | 06-29 | Integración DeFindex Camino A+B, bloqueo biométrico, `Pool.list_barrios`, RoleResolver on-chain, passkey→smart account, **redeploy testnet vigente** | `da769ef`, `0f113aa`, `fd518e6`, `8687b9b`, `53eacb7` |
| 6. Roles/gobernanza in-app + passkey operativa | 06-29/30 | Pantallas por rol, orden de cobro QR, Tesorería per-barrio, **passkey operativa end-to-end**, historial desde eventos Soroban, login resuelve rol on-chain | `727d8f4`, `3575b74`, `8260d0e`, `1094863`, `a847b44` |
| 7. Landing + pitch | 06-30 → 07-06 | raizapp.xyz, /verificar-residente, deck 14 slides, video demo, landing V2, deck V2 (= HEAD) | `aad1f29`, `26606ab`, `30964af`, `961af79` |

**Las 3 ramas `feat/` están totalmente mergeadas en main** (local y remota; verificado con
`git branch --merged` y `git log main..origin/feat/X` vacío) — borrables sin pérdida:

| Rama | Tip | Fecha | En main |
|---|---|---|---|
| `feat/defindex-integration` | `da769ef` | 2026-06-29 | Sí |
| `feat/security-hardening` | `53eacb7` | 2026-06-29 | Sí |
| `feat/role-flows` | `f6d0121` | 2026-06-30 | Sí |

La última ola (`53eacb7` → `961af79`) movió **41 archivos, +9.653/−1.157 líneas**, concentrada en
passkey/Soroban (`PasskeyWalletManager.kt` +575, `SorobanClient.kt` +664), gobernanza in-app
(`ui/governance/` ~1.470 líneas nuevas), la UI de yield (`YieldScreen.kt` 325) y la landing
(`landing/index.html` 1.488). Nota: `MEMORY.md` del asistente dice "TODO en main (53eacb7)" —
desactualizado, main está 31 commits por delante.

---

## 3. Contratos Soroban

Workspace en `contracts/Cargo.toml` (miembros `pool`, `governance`, `treasury`, `rewards`; perfil
release con `opt-level="z"`, LTO y `overflow-checks=true`, `contracts/Cargo.toml:23-31`).
**soroban-sdk declarado `22.0.8`** (`contracts/Cargo.toml:20`), **resuelto `22.0.11`** en
`Cargo.lock` (soroban-env 22.1.3). Única feature: `testutils` (solo dev).

### 3.1 Resumen por crate

| Crate | Funciones públicas clave | Storage | Eventos | Tests |
|---|---|---|---|---|
| **pool** | `initialize` (5º param `defindex_vault`, lib.rs:183), `set_defindex_vault` (211), `register_barrio` (222), `register_merchant` (264), `pay_merchant` (299), `withdraw_to` (401), `deposit_idle_to_vault` (454), `redeem_from_vault` (541), lecturas `get_pool_balance`/`get_vault_shares`/`get_vault_value`/`get_barrio`/`get_merchant`/`list_merchants`/`list_barrios` (598–676) | Instance: `Admin`, `UsdcToken`, `RewardsContract`, `ProtocolFeeBps`, `DefindexVault`. Persistent: `Barrio`, `Merchant`, `BarrioMerchants`, `TouristSeen`, `VaultShares(bid)`, `AllBarrios` (lib.rs:73-96) | `("payment", bid)→(tourist, merchant, amount, tip)` (391); `("vault_dep", bid)→(amount, shares)` (526); `("vault_red", bid)→(shares, got)` (589) | **19** |
| **governance** | `initialize` (113), `set_barrio_admin` (135), `mint_resident` (155, soulbound, no re-mint), `create_proposal` (202, duración 3–14 días), `vote` (273), `tally` (330, quórum 30% sin división entera, idempotente), `mark_executed` (377, solo Treasury), lecturas (406–427). **No existe `transfer()`** — soulbound respetado | Instance: `ProtocolAdmin`, `TreasuryContract`. Persistent: `Admin(bid)`, `Resident`, `Proposal`, `ProposalCount`, `ResidentCount(bid)`, `BarrioProposals(bid)`, `Vote(id, addr)` | `resident`, `proposal`, `vote`, `tally` | **21** |
| **treasury** | `initialize` (134), `execute_proposal` (156, trustless — cualquiera puede llamar), `get_execution_log`/`get_execution`/`get_execution_count` (248–273) | Instance: `PoolContract`, `GovernanceContract`. Persistent: `Execution`, `ExecutionCount(bid)`, `BarrioExecutions(bid)`, `TotalExecutions` | `("execution", bid)→(proposal_id, amount, recipient)` (239); tx_hash interno = sha256 (285-296) | **6** |
| **rewards** | `initialize` (93), `register_reward` (116), `accrue_points` (169, solo el Pool registrado; 1 punto por 0.01 USDC de tip = `tip/100_000`), `redeem` (201, atómico), `claim_redemption` (259), lecturas (295–316) | Instance: `Admin`, `PoolContract`. Persistent: `Points(Address)`, `Reward`, `RewardCount`, `Redemption`, `RedemptionCount`, `BarrioRewards(bid)` | `("redeem", bid)→(tourist, reward_id, red_id)` (251); `("claim", bid)→(artisan, redemption_id)` (286) — ver drift vs CLAUDE.md en §6.8 | **12** |

**Total: 58 tests, 58/58 verdes** — verificado por ejecución en la auditoría 2026-07-31
(`cargo test --workspace` → exit 0; governance reconfirmado por separado). El `README.md:299,355`
dice "55/55": está mal — los 3 tests extra son los de `list_barrios` (commit `0f113aa`,
2026-06-29) y el README se editó después (2026-07-05) sin corregir la cifra. **La línea base que
debe seguir verde tras F1 es 58** (el plan apunta a ~70 con el crate `yield_adapter`).

### 3.2 Patrón de clientes cross-contract

- **Pool → Rewards**: único `contractimport!` del repo (`pool/src/lib.rs:108-110`, apunta a
  `../target/wasm32-unknown-unknown/release/rewards.wasm` → exige compilar rewards ANTES de pool).
  En tests se usa el crate real vía dev-dependency, no el wasm.
- **Pool → Vault DeFindex**: `#[contractclient]` sobre trait declarado a mano
  (`pool/src/lib.rs:132-166`), con el hack del 3er elemento `Val` comodín en la tupla de `deposit`.
- **Treasury → Governance**: `#[contractclient]` a mano que **duplica** los tipos `Proposal` y
  `ProposalStatus` (`treasury/src/lib.rs:29-66`) — sync manual, riesgo de drift silencioso.
- **Treasury → Pool**: `#[contractclient]` a mano (`treasury/src/lib.rs:68-83`) con `withdraw_to`,
  `get_vault_shares`, `redeem_from_vault`.
- Governance y Rewards no importan a nadie; **ninguno de los dos toca DeFindex**.

### 3.3 Puntos de integración DeFindex — la superficie exacta que F1 toca

| # | Qué | Ubicación |
|---|---|---|
| 1 | Trait `DefindexVault` + `DefindexVaultClient` (`deposit(Vec<i128>, Vec<i128>, Address, bool) -> (Vec<i128>, i128, Val)`, `withdraw`, `balance`, `get_asset_amounts_per_shares`) | `pool/src/lib.rs:132-166` |
| 2 | Storage `DataKey::DefindexVault` (instance, vault compartido entre barrios) | `pool/src/lib.rs:81` (escrito en 203-205, 217) |
| 3 | Storage `DataKey::VaultShares(BytesN<32>)` (persistent, shares por barrio) | `pool/src/lib.rs:89` (usos 520-524, 583-587, 607-622) |
| 4 | `initialize` con `defindex_vault` como 5º parámetro (breaking al re-deploy) | `pool/src/lib.rs:183-190` |
| 5 | `set_defindex_vault` (cambio de vault en caliente, solo admin) | `pool/src/lib.rs:211-219` |
| 6 | `deposit_idle_to_vault`: caller admin/treasury (469), pre-autoriza la sub-invocación `usdc.transfer(pool, vault, amount)` con `env.authorize_as_current_contract` (492-507), `vault.deposit(..., invest=true)` (509-513), emite `vault_dep` | `pool/src/lib.rs:454-531` |
| 7 | `redeem_from_vault`: `vault.withdraw(shares, [0], pool)` (570-572), `pool_balance += got`, `VaultShares -= shares`, emite `vault_red`. **Sin chequeo `shares <= VaultShares`** (§6.2) | `pool/src/lib.rs:541-594` |
| 8 | `get_vault_shares` (lectura pura) y `get_vault_value` (cross-contract → sujeta al gotcha "lectura tratada como WRITE" con TTL expirado) | `pool/src/lib.rs:607-612, 617-633` |
| 9 | Treasury rescata antes de pagar: tras `tally == Passed`, si `pool.get_vault_shares(bid) > 0` → `pool.redeem_from_vault(treasury, bid, TODAS las shares)` (rescate total; el yield queda en `pool_balance`), luego `pool.withdraw_to` | `treasury/src/lib.rs:171-195` |
| 10 | Trait `Pool` en Treasury expone `get_vault_shares`/`redeem_from_vault` — **si F1 conserva estas firmas, Treasury no requiere cambios** | `treasury/src/lib.rs:68-83` |
| 11 | Mocks del vault en tests, duplicados (`MockVault` con `set_price` para simular yield) | `pool/src/test.rs:40-151`, `treasury/src/test.rs:26-99` |
| 12 | Direcciones reales en testnet: vault `CBMVK2JK6NTOT2O4HNQAIQFJY232BHKGLIMXDVQVHIIZKDACXDFZDWHN`, USDC de Blend `CAQCFVLOBK…` | `deployments.json:5,13-14` |

Sin protección de slippage: `amounts_min = [0]` (lib.rs:511) y `min_amounts_out = [0]` (lib.rs:571)
— decidir en F1 si el `YieldAdapter` de Blend lo mantiene. Los riesgos concretos de la migración
soroban-sdk 22→26 (ruta del `contractimport!` al target `wasm32v1-none`, `#[contractevent]` vs
`publish` con tuplas, `testutils::Events`, MSRV, la duplicación de tipos de Treasury como canario)
están inventariados en la auditoría 2026-07-31 de contratos; el punto más delicado es el patrón
`authorize_as_current_contract` del deposit, que Blend también necesitará y que hoy solo está
testeado con `mock_all_auths`.

---

## 4. App Android

Raíz del código: `android/app/src/main/java/com/raiz/app/` (abreviado `<APP>` abajo).

### 4.1 Capa data

**Versiones** (`android/gradle/libs.versions.toml`): Kotlin 2.1.21, KSP 2.1.21-2.0.2, Hilt 2.56,
AGP 8.7.3, **kmp-stellar-sdk (Soneso) 1.6.0**, Ktor 3.3.2, coroutines 1.9.0. Recordar el
acoplamiento Kotlin↔KSP↔Hilt documentado en CLAUDE.md si algún bump lo toca.

- **`<APP>data/stellar/WalletManager.kt`** (273 líneas): custodia de claves. Resolución: seed
  guardada (EncryptedSharedPreferences) > passkey (contractId `C...`) > demo de BuildConfig >
  placeholder. **Cadena del admin secret**: `local.properties` (no versionado, key
  `raiz.admin.secret`) → `buildConfigField DEMO_ADMIN_SECRET` (`app/build.gradle.kts:40`) →
  `WalletManager.demoAdminKeyPair()` (`WalletManager.kt:254-261`, cache L265). No hay secreto en
  el código fuente, pero **sí queda embebido en el APK compilado** (BuildConfig es texto plano en
  el dex) — limitación aceptada del modo demo; el quick-win de F1 es multisig 2-de-3. Consumidores
  de `demoAdminKeyPair()`: `YieldViewModel`, `BecomeMerchantViewModel`, `ProposalsViewModel`,
  `WalletViewModel`.
- **`<APP>data/stellar/PasskeyWalletManager.kt`**: smart wallets secp256r1 (WebAuthn) sobre el kit
  OpenZeppelin de Soneso (`OZSmartAccountKit`); requiere Activity y API ≥ 28; usa infra pública
  Soneso testnet (relayer `smart-account-relayer-proxy.soneso.workers.dev`, indexer
  `smart-account-indexer.sdf-ecosystem.workers.dev`). Sin relación con yield — F1 no lo toca.
- **`<APP>data/stellar/SorobanClient.kt`**: patrón `ContractClient.forContract` del SDK con
  clientes cacheados por contrato; lecturas con `signer=null`. Funciones vault:
  `getVaultShares(barrioId)` (L141-158, lectura pura del storage del Pool — consumida por
  `YieldViewModel` y `DashboardViewModel`) y `getVaultValue` (L173-190, **sin consumidores**,
  atada al gotcha de TTL). El parser de eventos ignora los topics `vault_dep`/`vault_red`
  (L1198, 1225).
- **`<APP>data/stellar/DefindexClient.kt`** (339 líneas, único cliente que habla con el vault):
  `getVaultStats()` (TVL y precio/share calculados off-chain con `total_supply` +
  `fetch_total_managed_funds`, esquivando `get_asset_amounts_per_shares`), `getPosition(holder)`,
  `deposit(signer, amount)`, `withdraw(signer, shares)`, y `getApyBps()` — **REST best-effort a
  `api.defindex.io`** con API key. **Cadena de la API key**: `local.properties` key
  `defindex.api.key` → `buildConfigField DEFINDEX_API_KEY` (`app/build.gradle.kts:43-46`) →
  `DefindexClient.kt:295`; opcional (sin key, APY = null). **Único consumidor directo:
  `YieldViewModel`** (verificado por grep en todo `android/`). Con Blend directo, el APY se lee
  on-chain y la API key desaparece.
- Modelos: `<APP>data/model/DefindexModels.kt` (`VaultStats`, `VaultPosition`),
  `Deployments.kt:23-24` (campos `defindexVault`/`defindexUsdc`), `RaizConstants.kt:25` (RPC
  `https://soroban-testnet.stellar.org`). DI: `di/DataModule.kt` es un módulo Hilt **vacío** —
  todo se resuelve por `@Inject constructor` + `@Singleton`; `DefindexClient` se inyecta como
  clase concreta (sin interfaz), así que el `BlendClient` de F1 requerirá o un `@Binds` nuevo o
  swap directo de la clase.

### 4.2 Capa UI

**21 pantallas** (`*Screen.kt`) + 12 ViewModels, un solo NavHost en `<APP>MainActivity.kt:298-596`
(rutas en `object Routes`, L688-724). Grupos: onboarding/auth (`welcome`, registro passkey/seed,
login, import, elección de rol), roles (`become_resident`, `become_merchant` en 2 pasos con
Mapbox), núcleo (`wallet`, `pay/{merchant_address}?amount={amount_stroops}`, `profile`, `rewards`,
`map` con Mapbox), gobernanza (`proposals`, `proposals/create`), comercio (`cobros`), público sin
login (`dashboard` → `yield`), y `LockScreen` biométrico como overlay.

**Navegación por rol**: no hay grafos separados. En onboarding se elige intención de rol; tras
login el rol se resuelve **on-chain** vía `RoleResolver.resolve(address)` (residente si tiene
ResidentToken en Governance, comerciante si registrado en Pool, turista por defecto). `currentRole`
controla el bottom nav (`<APP>ui/components/RaizBottomNav.kt:50-52`): TOURIST →
Home/Rewards/Map/Profile; MERCHANT → Home/Cobros/Map/Profile; RESIDENT → Home/Proposals/Map/Profile.
**No existe rol ADMIN en la UI** (`UserRole.kt:16-20`) — las funciones admin se guardan detrás de
`demoAdminKeyPair()`. Paleta y tema conformes al CLAUDE.md (`ui/theme/Color.kt:17-28`; solo light
scheme por diseño).

**YieldScreen** (`<APP>ui/treasury/YieldScreen.kt`, 487 líneas + `YieldViewModel.kt`, 299; ruta
`yield`, solo alcanzable desde Dashboard): tarjeta global del vault (título hardcodeado
"Vault DeFindex · USDC" L181, TVL, precio/share, badge APY, estrategia "Blend (USDC) · auditado
por OtterSec" L245), posición por barrio (3 métricas: depositado/valor actual/rendimiento) y
"Reserva del protocolo" (depositar / rescatar todo, firmando con la cuenta admin). El ViewModel
mezcla dos fuentes: stats globales y posición admin desde `DefindexClient` (L117-119), y la
posición **por barrio** desde `SorobanClient.getVaultShares` (storage del Pool, con retry ×3),
calculando `valorActual = shares × pricePerShare / 10^7` off-chain para esquivar el gotcha
"Signer required for write call". Riesgo señalado por la auditoría: la semántica
"shares ≈ USDC a precio 1.0" está asumida en `YieldViewModel` y `DashboardViewModel` — Blend usa
bTokens con exchange rate creciente, así que el "depositado" real exigirá trackear principal en el
contrato o cambiar el cálculo en la app.

**Confirmado por la auditoría: no existe nada de ahorro/metas/círculos en la app** (futura F2 parte
de cero en UI).

### 4.3 Superficie app-side completa que F1 toca (12 puntos)

1. `data/stellar/DefindexClient.kt` → reemplazar por `BlendClient.kt` (mismo patrón
   `ContractClient.forContract`); re-mapear stats/posición al pool de Blend; eliminar `getApyBps()`
   REST (APY on-chain); actualizar `mapError` (los códigos `#111`/`#130` son de DeFindex).
2. `data/model/DefindexModels.kt` → renombrar/generalizar `VaultStats`/`VaultPosition` a modelos
   neutrales de yield.
3. `data/model/Deployments.kt:23-24` → sustituir `defindex_vault`/`defindex_usdc` por los campos
   del nuevo deploy.
4. `app/src/main/assets/deployments.json:13-14` → regenerar tras el re-deploy (copia manual del
   root, ver §5.3).
5. `app/build.gradle.kts:43-46` → eliminar `DEFINDEX_API_KEY` (y `defindex.api.key` de
   local.properties).
6. `ui/treasury/YieldViewModel.kt` → swap de inyección (L11, 92) y de las 5 llamadas (L117-119,
   223, 261); strings de `humanError` (L283-284).
7. `ui/treasury/YieldScreen.kt` → strings de UI (L56, 122, 147-148, 160-161, 181, 186).
8. `ui/dashboard/DashboardScreen.kt` → strings "DeFindex" (L715, 744, 753, 776, 781).
9. `ui/dashboard/DashboardViewModel.kt` → KDoc L40; su `getVaultShares` (L178) sigue válida según
   lo que decida el contrato.
10. `data/stellar/SorobanClient.kt` → revisar `getVaultShares` (nombre según nueva spec),
    **eliminar o reimplementar `getVaultValue`** (hoy sin consumidores), verificar los topics
    `vault_dep`/`vault_red` ignorados contra los eventos del Pool nuevo.
11. `data/stellar/HorizonStream.kt:65` → solo KDoc (el `usdc_issuer` sigue siendo el de Blend).
12. **Sin cambios**: `WalletManager`, `PasskeyWalletManager`, `SecureWalletStore`, `DataModule`,
    versiones de `libs.versions.toml` (salvo que F1 fuerce bump del stellar-sdk; 1.6.0 cubre todo
    lo usado hoy).

No hay ningún `BlendClient` aún; "blend" solo aparece implícito en el USDC de deployments.

---

## 5. Infraestructura y deploy

### 5.1 `deployments.json` (raíz, contenido completo del deploy vigente)

```json
{
  "network": "testnet",
  "admin": "GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P",
  "admin_identity": "raiz-admin",
  "usdc_sac": "CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU",
  "pool": "CAKYU5HW5QPLAE5YBHH5L5P433VE3RMA7OGAZV2OQCSATD57TXEVN2FK",
  "governance": "CAENXDX77SHDLNPXTQDV4M6W43SVHEJWOGOBQT5XHXDPFEO6PNB77PVE",
  "treasury": "CDGGFSV74EGBEUQWLZ5OMQZJUPXBI7BYCNZJRMCGYEKZEPN3QBWQGPXA",
  "rewards": "CD5OET7FPJAWPID5DCBYRHDJXNICXAPLAWDRDA3NCS5IIBACEW2I6PPT",
  "protocol_fee_bps": 50,
  "deployed_at": "2026-06-29T23:12:24Z",
  "usdc_issuer": "GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56",
  "defindex_vault": "CBMVK2JK6NTOT2O4HNQAIQFJY232BHKGLIMXDVQVHIIZKDACXDFZDWHN",
  "defindex_usdc": "CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU"
}
```

El USDC es el de **Blend** (no uno propio) — F1 lo conserva; `defindex_vault`/`defindex_usdc`
desaparecen o cambian, y el re-deploy F1 renueva **todos** los IDs `C…`. La copia en
`android/app/src/main/assets/deployments.json` está hoy **idéntica** a la raíz (verificado por la
auditoría), pero la sincronización es manual (§5.3).

### 5.2 Scripts (`scripts/` — solo 2 archivos; **no existe `seed.ts`** pese a que CLAUDE.md y ARQUITECTURA_TECNICA L432 lo citan)

- **`deploy_testnet.sh`** (193 líneas): identidad `raiz-admin` → compila (primero
  `cargo build --target wasm32-unknown-unknown -p rewards` para el `contractimport!` del Pool,
  luego `stellar contract build` → `wasm32v1-none`) → referencia el USDC SAC de Blend, no despliega
  uno propio (L71-75) → despliega los 4 contratos → inicializa Rewards → Pool → Governance →
  Treasury → escribe `deployments.json`. Defaults hardcodeados sobreescribibles por env var en
  L18-22 (`USDC_SAC`, `USDC_ISSUER`, `DEFINDEX_VAULT`). `Pool.initialize` recibe
  `--defindex_vault` (L144-150) — cambia con el YieldAdapter. **Retries**: `deploy_contract()` y
  `invoke()` hasta 5 intentos con `sleep 5` (testnet flaky en ráfaga).
- **`seed_testnet.sh`** (311 líneas): turista demo fondeado vía **faucet de Blend** (L77-98, URL
  `https://ewqw4hx7oa.execute-api.us-east-1.amazonaws.com/getAssets?userId=<G>` → firmar XDR →
  `stellar tx send`); 3 barrios con IDs hex fijos (Centro Histórico `ce4712…01`, Barrio Norte
  `bba17e…02`, Costa Vieja `c057a9…0a`, L142-154); admins de barrio; 9 comercios con trustline; 9
  residentes soulbound; 6 pagos con tip 2%; **§5b "Camino A" (L229-239): `deposit_idle_to_vault`
  de 0.2 USDC del Centro al vault** — el bloque a migrar a la interfaz nueva; 3 propuestas, votos,
  6 rewards. **Retry**: `invoke()` hasta 4 intentos con `sleep 4`, sin `set -e` (idempotencia).
  Bug cosmético: `log` se usa en L44 antes de definirse en L49.

### 5.3 El paso manual deployments → assets

`deploy_testnet.sh` **no copia nada** a `android/`. El único lugar que documenta el paso es el KDoc
de `DeploymentsLoader.kt:11-14` ("copiar manualmente a `android/app/src/main/assets/` tras cada
deploy"). El hito M1 de F1 exige un `deployments.json` nuevo — este paso oculto debería añadirse
al script o al README §8 (§6.9).

### 5.4 Secretos y configuración local

Todas las keys que un build fresco necesita en `local.properties` (`app/build.gradle.kts:38-54`):
`raiz.tourist.secret`, `raiz.resident.secret`, `raiz.admin.secret`, `mapbox.access.token`,
`defindex.api.key` (opcional, muere con F1), `passkey.rp.id` / `passkey.rp.name`. Ninguna está
versionada; todas terminan en BuildConfig (texto plano en el APK — asumido para demo).

### 5.5 CI

**No había CI** al momento de la auditoría: `.github/workflows/` no existía y los "58/58 verdes"
eran verificación local — riesgo directo para la migración de SDK 22→26 (§6.10).
**RESUELTO el mismo 2026-07-31** (commit `7fe2356`): workflow `contracts.yml` corre
`cargo test --workspace` en cada push/PR que toque `contracts/`, con toolchain pineado por
`contracts/rust-toolchain.toml`.

---

## 6. Deuda técnica y hallazgos de la auditoría 2026-07-31

1. **Tests: 58 reales vs 55 del README.** `README.md:299,355` quedó desactualizado tras
   `0f113aa` (3 tests de `list_barrios`). Línea base real: **58/58 verdes**, verificada por
   ejecución. Corregir el README junto con la ola de docs de F1.
2. **Bug en `redeem_from_vault`**: `contracts/pool/src/lib.rs:559` solo valida `shares > 0` y
   L584-587 hace `prev_shares - shares` **sin chequear suficiencia** — como todas las shares de
   todos los barrios viven bajo la misma Address del Pool en el vault, un treasury/admin puede
   rescatar shares contablemente atribuidas a otro barrio y dejar `VaultShares` negativo.
   **Corregir al introducir el `YieldAdapter`.**
3. **`docs/RaizModels.kt` (fuente de verdad según CLAUDE.md) no tenía ni un modelo de yield**:
   grep `Vault|Defindex|Yield` → cero resultados; `VaultStats`/`VaultPosition` vivían solo en
   `android/.../data/model/DefindexModels.kt`. **RESUELTO el 2026-07-31**: se añadió
   `YieldPosition` (espejo del contrato `yield_adapter`) a `RaizModels.kt`.
4. **La spec del trait `YieldAdapter` solo existía como pseudocódigo** en la propuesta
   (`propuesta_raiz_ahorro_enjambre.md:65-71`). **RESUELTO el 2026-07-31**:
   `raiz_v2_spec_contratos.md` ganó el "Contrato 5: `yield_adapter`" con firmas Rust exactas,
   decisiones de implementación Blend v2 verificadas (Supply=0/Withdraw=1, b_rate 1e12, cliente
   a mano por conflicto blend-contract-sdk↔sdk-26) y la re-especificación de la sección vault
   de Pool (colchón líquido `CushionBps`, `set_yield_adapter`, delegación de lecturas). Además
   `docs/raiz_v2_spec_contratos.md` arrastra drift preexistente: no documenta `register_barrio`,
   `withdraw_to`, `set_barrio_admin`, `mark_executed`, `get_resident`, `get_resident_count`,
   `register_reward` ni los `initialize` — cerrar al reescribir la sección vault (L101-127).
5. **Toolchain sin pinear**: no existía `rust-toolchain.toml` (rustc/cargo 1.90.0 al auditar).
   **RESUELTO el 2026-07-31** (commit `7fe2356`): `rustup update` a 1.97.1 y
   `contracts/rust-toolchain.toml` pinea `1.97.1` + targets `wasm32v1-none` y
   `wasm32-unknown-unknown`.
6. **`seed.ts` fantasma**: CLAUDE.md (estructura y comandos) y `ARQUITECTURA_TECNICA.md:432` citan
   `scripts/seed.ts`; el archivo real es `scripts/seed_testnet.sh`.
7. **`.gitignore` con regla inerte**: la línea 48 (` .docs/`) tiene un espacio inicial
   significativo — solo ignoraría un directorio literalmente llamado `" .docs"`. Y la línea 21
   (`gradle.properties` sin ancla) ignora ese nombre en cualquier nivel, incluido un futuro
   `android/gradle.properties`. Tampoco hay regla para `android/.shots/` (los 3 JPG de
   `ErroresPasskey/` son scratch de debugging que no debe commitearse).
8. **Evento `redeem` vs `redemption`**: CLAUDE.md especifica
   `(symbol_short!("redemption"), barrio_id), (tourist, reward_id)` pero "redemption" excede los
   9 chars de `symbol_short!` — la guía es irrealizable tal cual. El código emite
   `("redeem", bid) → (tourist, reward_id, red_id)` (`rewards/src/lib.rs:251-254`). Corregir el
   CLAUDE.md.
9. **Sincronización deployments→assets manual** y documentada solo en un KDoc
   (`DeploymentsLoader.kt:11-14`) — automatizar en `deploy_testnet.sh` o documentar en README §8.
10. **Sin CI** (§5.5) — **RESUELTO el 2026-07-31** (commit `7fe2356`, ver §5.5).
11. **`ARQUITECTURA_TECNICA.md` es un híbrido de dos épocas**: cabecera "2026-05-28" (L6) pero §12
    DeFindex verificada el 2026-06-29. Dice que passkey es un stub (L27, L49, L407-409, L452-454)
    — **falso hoy**; tabla de IDs de contratos §2 (L90-95) del deploy viejo; roadmap §10 lista como
    pendiente cosas ya hechas (passkey, `list_barrios`). Requiere reescritura mayor (ya registrada
    como tarea en `plan_trabajo_raiz.md:31`).
12. **Todo `docs/NuevaPropuesta/` estaba untracked** (5 archivos, 2026-07-31) — **RESUELTO**:
    commit `5220c84` versiona la propuesta, el plan, el paper y los assets de presentación
    (JPEG renombrado a ASCII), y las 3 ramas `feat/` mergeadas fueron borradas en local
    (las remotas quedan pendientes de confirmación del dueño del repo). Texto original:
    el roadmap canónico
    F1–F6 y el plan de trabajo no están en git. Commitear los fuentes (`.md`, `.tex`); el
    `.html`/`.pdf` generados, decisión aparte. También untracked: `docs/presentacion/
    Pitch_Inicial.txt`, `Raiz.pdf` (commitear) y `TurismoXaño3Naciones.jpeg` (renombrar a ASCII
    antes — la `ñ` da problemas de encoding entre plataformas).
13. **`MEMORY.md` desactualizado**: dice "TODO en main (53eacb7)"; main está en `961af79`, 31
    commits después. La memoria `defindex-integration.md` pasará a legado cuando DeFindex salga.
14. **Tipos duplicados Treasury↔Governance** (`treasury/src/lib.rs:29-66`): si al recompilar con
    SDK 26 cambia la representación de enums `#[contracttype]`, el drift sería silencioso hasta
    runtime — usar el test end-to-end de treasury como canario.
15. **Sin slippage** en deposit/redeem del vault (`amounts_min=[0]`, `min_amounts_out=[0]`) —
    decidir política en el `BlendAdapter`.
16. **Clave admin embebida en el APK** vía BuildConfig (§5.4) — aceptada para demo; el quick-win
    de F1 semana 1 es multisig 2-de-3 (solo documentado en el plan untracked).
17. Menores: `mod test;` sin `#[cfg(test)]` en `pool/src/lib.rs:693` (inconsistente con los otros
    3 crates); `DEMO_BARRIOS` duplicado entre `YieldViewModel.kt:293-297` y `RoleResolver`;
    `SorobanClient.getVaultValue` sin consumidores; bug cosmético del `log` en
    `seed_testnet.sh:44`; la sección "app Android" de la spec (L319-343) describe una capa
    `repository/` que nunca existió.

---

## 7. Estado de la red y dependencias externas

**Stellar testnet opera en Protocol 27.** Posición del proyecto frente al ecosistema
(verificaciones de la auditoría 2026-07-31 contra crates.io y fuentes canónicas):

| Componente | Hoy en el repo | Última versión | Nota |
|---|---|---|---|
| soroban-sdk | 22.0.8 declarado / 22.0.11 resuelto | 26.1.1 (última 26.x, 2026-07-21); 27.0.4 estable (publicada 2026-07-31) | **DECIDIDO 2026-07-31: migrar a 26.1.1** (estable, soportada, trae BN254+Poseidon para la futura capa ZK; 27.x solo hace falta para USAR las novedades P27) |
| stellar-cli | 23.2.1 instalado | 27.1.0 | La migración de SDK arrastra un **upgrade de CLI de 4 majors** (afecta simulación/firma contra testnet P27); pendiente de instalar antes del re-deploy F1 |
| rustc/cargo | ~~1.90.0 sin pin~~ → **1.97.1 pineado** (`contracts/rust-toolchain.toml`, 2026-07-31) | — | Resuelto (§6.5) |
| kmp-stellar-sdk (app) | 1.6.0 | — | Cubre todo lo usado; bump solo si F1 lo exige (ojo trío Kotlin/KSP/Hilt) |

**Direcciones Blend V2 en testnet** (fuente canónica `blend-capital/blend-utils`, rama `main`,
`testnet.contracts.json`, fetch 2026-07-31 — **ausentes del repo hasta esta auditoría**; F1 no
puede escribir el `BlendAdapter` sin ellas):

| Contrato | Dirección testnet |
|---|---|
| Pool USDC "TestnetV2" | `CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF` |
| USDC (SAC) | `CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU` |
| BLND token | `CB22KRA3YZVCNCQI64JQ5WE7UY2VAV7WFLK6A2JN3HEX56T2EDAFO7QF` |
| backstopV2 | `CBDVWXT433PRVTUNM56C3JREF3HIZHRBA64NB2C3B2UNCKIS65ZYCLZA` |
| poolFactoryV2 | `CDV6RX4CGPCOKGTBFS52V3LMWQGZN3LCQTXF5RVPOOCG4XVMHXQ4NTF6` |
| oraclemock | `CAZOKR2Y5E2OSWSIBRVZMJ47RUTQPIGVWSAQ2UISGAVC46XKPGDG5PKI` |
| emitter | `CC3WJVJINN4E3LPMNTWKK7LQZLYDQMZHZA7EZGXATPHHBPKNZRIO3KZ6` |

Dos datos derivados: (a) el USDC de blend-utils **coincide exactamente** con
`usdc_sac`/`defindex_usdc` de `deployments.json:5,14` — "custodiamos el USDC de Blend" queda
probado contra fuente canónica; (b) blend-utils `main` solo publica **V2** → el `BlendAdapter`
debe apuntar a la interfaz Blend V2, decisión que ningún doc del repo registra todavía.
Recomendación de la auditoría: pinear estas direcciones como env vars en `deploy_testnet.sh`
(patrón de L18-22) al arrancar F1.

**Otras dependencias externas vivas**: vault DeFindex `CBMVK2JK…` (muere con F1) y su API REST
`api.defindex.io` (API key, muere con F1); faucet de Blend
(`https://ewqw4hx7oa.execute-api.us-east-1.amazonaws.com/getAssets` — se conserva, el asset sigue
siendo el USDC de Blend); infra passkey pública de Soneso (relayer + indexer, workers.dev); Mapbox
(token en local.properties); RPC `soroban-testnet.stellar.org` y Horizon testnet; landing en
GitHub Pages con dominio propio `raizapp.xyz` (repo Pages separado, copia manual, cache ~10 min).

---

## 8. Baseline para F1

### 8.1 Checklist — lo que debe seguir verde/vivo tras F1

- [ ] **`cargo test --workspace` en verde partiendo de 58/58** (no 55). Con el crate
      `yield_adapter` el plan apunta a ~70. Cualquier regresión bajo 58 es bloqueo.
- [ ] **Demo passkey end-to-end en dispositivo**: crear smart wallet WebAuthn, pagar con tip,
      votar, crear propuesta, faucet y saldo — nada de esto toca DeFindex, así que F1 no tiene
      excusa para romperlo (`PasskeyWalletManager` está fuera de la superficie F1, §4.3.12).
- [ ] **Landing `raizapp.xyz` con datos on-chain en vivo**: tras el re-deploy F1 los IDs `C…`
      cambian — actualizar la landing (y recordar su pipeline: repo Pages separado, copia manual,
      no tocar assetlinks/CNAME/.nojekyll).
- [ ] **Los 3 flujos de la demo de `DEMO.md`**: (1) pago con tip 2% y split fee 0.5%; (2)
      gobernanza propuesta→voto→tally→execute con quórum 30%; (3) canje de premio + dashboard de
      transparencia. `DEMO.md` **no menciona yield ni la pantalla Tesorería** — la demo núcleo es
      independiente de F1; a lo sumo añadir una extensión opcional "el fondo rinde en Blend".
- [ ] **`deployments.json` nuevo escrito por el script + copia sincronizada en
      `android/app/src/main/assets/`** (hito M1; automatizar el paso manual de §5.3).
- [ ] Seed reproducible: `seed_testnet.sh` con el bloque §5b migrado a la interfaz nueva y el
      faucet de Blend funcionando.

### 8.2 Docs que F1 obliga a actualizar (auditoría 2026-07-31, docs/scripts/deploy)

1. **`docs/raiz_v2_spec_contratos.md`** — reemplazar la sección "Vault DeFindex" (L101-127) por la
   spec del trait `YieldAdapter` + `BlendAdapter`; ajustar `DataKey::DefindexVault` (L34) y
   `VaultShares` (L37); re-especificar `initialize` (L104-105), `set_defindex_vault` (L108),
   `get_vault_value` (L126); actualizar la nota de Treasury (L237-238). Aprovechar para cerrar el
   drift preexistente (§6.4).
2. **`docs/ARQUITECTURA_TECNICA.md`** — reescritura mayor (fecha, passkey, IDs, §12 DeFindex →
   Blend/YieldAdapter, stack, roadmap, `seed.ts`→`seed_testnet.sh`).
3. **`README.md`** — mermaid (nodo VAULT, `DefindexClient`), §3c "Yield con DeFindex", §4 (fila
   Yield, "soroban-sdk 22.x" L202, "Stellar CLI 23.x" L283), §5 (fila DeFindex), §7 (IDs nuevos),
   §8 (`defindex.api.key` fuera; documentar la copia a assets), §9 (roadmap nuevo F1–F6 como
   prioridad), §10 (`DefindexClient`→`BlendClient`) — y **corregir "55 tests" → 58** (L299, 355).
4. **`deployments.json`** — regenerado por el deploy F1 (sin `defindex_vault`/`defindex_usdc`; con
   pool de Blend / adapter) + copia en assets.
5. **`scripts/deploy_testnet.sh`** — L18-22 (defaults), L144-150 (`--defindex_vault` en
   `Pool.initialize`), L178-179 (campos JSON); posible deploy del crate `yield_adapter`.
6. **`scripts/seed_testnet.sh`** — §5b (L229-239) a la interfaz nueva; L36 (`get_field
   defindex_vault`); el faucet de Blend se conserva.
7. **`CLAUDE.md`** — stack "soroban-sdk 22.x", gotchas "DeFindex / Blend testnet USDC" y
   "get_asset_amounts_per_shares" (obsoletos o cambian), mención a `seed.ts`, y el evento
   "redemption" irrealizable (§6.8).
8. **Memoria del asistente** (`MEMORY.md` / `defindex-integration.md`) — reetiquetar como legado.
9. **`docs/presentacion/deck.md`** (Slide 13) y **`DEMO.md`** — opcional, solo si se quiere
   demostrar yield Blend en pitch.
10. **Formalizar el roadmap** — commitear `docs/NuevaPropuesta/` y promover su roadmap F1–F6 al
    README §9 como canónico (hoy no existe ningún archivo `ROADMAP*` y el roadmap vive disperso
    en 5 sitios).

---

*Documento generado a partir de la auditoría 2026-07-31 (contratos Soroban, capa data y UI de la
app Android, docs/scripts/deploy, historial git y crítica de completitud cruzada). Ante conflicto
entre cifras de docs antiguos y este documento, prevalece este documento; ante conflicto con el
código, prevalece el código.*
