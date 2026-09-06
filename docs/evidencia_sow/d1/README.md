# Evidencia SOW — D1: Admin Relayer

**Entregable D1 (SOW Instaward):** backend open-source que firma server-side los flujos admin de
RAÍZ; la app Android lo consume; APK release **sin secretos**, verificable por descompilación;
regresión en dispositivo físico.

**En una frase:** antes, el APK llevaba la clave privada del admin del protocolo para poder
registrar comercios, emitir el soulbound de residente y repartir USDC de prueba. Desde la versión
**0.2.0** esa clave vive únicamente en el servicio `raiz-relayer` (variable de entorno del
servidor); el APK solo tiene direcciones públicas y una API key de aplicación.

## Tabla entregable → evidencia

| Entregable SOW D1 | Evidencia | Dónde / enlace | Estado |
|---|---|---|---|
| Repositorio público del relayer (open source) | Código TypeScript + Fastify + `@stellar/stellar-sdk`, README en español con endpoints, modelo de amenazas y runbook; 150 tests (`vitest`, 7 archivos) + integración contra testnet; CI | https://github.com/JuanWimmin/raiz-relayer | Publicado |
| Servicio corriendo en testnet | `GET /v1/live` y `GET /v1/health` (contratos = `deployments.json`, `network: testnet`, admin `GBLS7PL5…YC2P`) | `<<pendiente: URL Fly>>` · `raiz-relayer/docs/evidencia/deploy_fly.md` | `<<pendiente>>` |
| Transacción real firmada por el relayer | Hash del mint/faucet de prueba del deploy | `https://stellar.expert/explorer/testnet/tx/<<pendiente>>` | `<<pendiente>>` |
| App migrada al relayer (faucet, comercio, residente, vault) | Cliente HTTP `android/app/src/main/java/com/raiz/app/data/relayer/RelayerClient.kt` + test unitario de mapeo de errores (`RelayerClientTest`, MockEngine); ViewModels sin clave admin | Este repo, rama `feat/wp1-app-relayer` | En esta rama |
| APK release sin secretos | `app-release.apk` 0.2.0 (`versionCode 2`), firmado con la clave debug de Android para que sea instalable (H9; keystore propio en WP4) | GitHub Release del monorepo `<<pendiente>>` | `<<pendiente>>` |
| Verificable por descompilación | Comandos y salida del `unzip` + `grep` (0 claves `S…`; la `G…` pública del admin solo como dato público en `assets/deployments.json` y en una preview de UI) | [`verificacion_apk.md`](verificacion_apk.md) | **Verificado 2026-09-06** (`app-release.apk` firmado con clave debug, 97 731 596 bytes, SHA-256 `95fd9b90…cad7c`, 0 claves; 24 tests JVM verdes) |
| Regresión en dispositivo físico | Checklist de 4 flujos (+ negativo sin relayer) en Motorola G04 con capturas y hashes | [`regresion_dispositivo.md`](regresion_dispositivo.md) · `capturas/` | `<<pendiente>>` |

## Cómo lo verifica un revisor en 10 minutos

1. **Servicio vivo (1 min):** abrir `<<URL Fly>>/v1/health` en el navegador → `ok: true`,
   `network: "testnet"`, y los IDs de contratos coinciden con `deployments.json` de este repo.
2. **Código abierto (2 min):** en https://github.com/JuanWimmin/raiz-relayer leer el README
   (§ Endpoints y § Modelo de amenazas) y comprobar que la clave admin solo entra por
   `RELAYER_ADMIN_SECRET` (`src/` no contiene ninguna `S…`: `git grep -E "S[A-Z0-9]{55}"` → 0).
3. **APK sin secretos (4 min):** seguir `verificacion_apk.md` §1–§4 con el APK de la Release
   (descomprimir + dos `grep`). Resultado esperado: 0 claves privadas; una única aparición de la
   dirección pública del admin en `assets/deployments.json`.
4. **Funciona en un teléfono (3 min):** abrir las capturas de `regresion_dispositivo.md` y pulsar
   2–3 de los enlaces de Stellar Expert de la tabla final: el firmante es el admin
   `GBLS7PL5…YC2P` (el relayer), el destinatario es la wallet del usuario.

## Notas honestas

- **La `G…` del admin sí está en el APK** (`assets/deployments.json` y, como texto de ejemplo,
  en una `@Preview` de `BalanceCard.kt` que el release no elimina). Es la dirección pública,
  necesaria como *source account* de las lecturas por simulación de Soroban; no permite firmar.
  Detalle en `verificacion_apk.md` §4.
- **La API key de la app viaja en el APK** a propósito (rate-limit por app, no autenticación
  fuerte). El relayer lo documenta como modelo de amenazas de **testnet**; la custodia comunal
  real es F3 del roadmap.
- **Wallets demo:** `DEMO_TOURIST_SECRET` / `DEMO_RESIDENT_SECRET` siguen existiendo como opción
  de `local.properties` **solo para el debug** (son wallets de prueba, no autoridad). El
  `buildType` `release` las fija a `""` en `build.gradle.kts`, así que el APK release tiene 0
  claves `S…` independientemente del `local.properties` de quien compile.
- **Coincidencias en `libmapbox-common.so`:** un `grep` de forma `S[A-Z0-9]{55}` sobre TODO el
  APK da 32 cadenas dentro del binario nativo de Mapbox; ninguna pasa el checksum StrKey (detalle
  en `verificacion_apk.md` §2). En `classes*.dex`, `assets/` y `res/` el conteo es 0.
- **Contratos vigentes:** los IDs son los del redeploy post-F1 del 31-jul-2026
  (`deployments.json`), no los del Annex A original del SOW; la tabla vieja→nueva va en el
  README general de `docs/evidencia_sow/` (WP4).

## Cambios en la app (resumen técnico, versión 0.2.0)

| Antes (0.1.0, firmaba el APK) | Ahora (0.2.0, firma el relayer) |
|---|---|
| `BuildConfig.DEMO_ADMIN_SECRET` + `WalletManager.demoAdminKeyPair()` | Eliminados |
| `HorizonStream.sendUsdcFromAdmin` (faucet `G…`) y `SorobanClient.fundContractUsdc` (faucet `C…`) | `RelayerClient.faucet(address)` → `POST /v1/faucet` |
| `SorobanClient.registerMerchant(admin, …)` | `RelayerClient.registerMerchant(…)` → `POST /v1/register-merchant` |
| `SorobanClient.mintResident(admin, …)` | `RelayerClient.mintResident(…)` → `POST /v1/mint-resident` (idempotente) |
| `SorobanClient.depositIdleToVault / redeemFromVault(admin, …)` | `RelayerClient.vaultDeposit / vaultRedeem` → `POST /v1/vault/{deposit,redeem}` |

Lo que el usuario sigue firmando con su propia wallet (sin cambios): pagar a un comercio, votar,
crear propuestas, canjear premios, ejecutar propuestas aprobadas (trustless), friendbot y
trustline USDC.
