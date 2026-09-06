# D1 — Regresión en dispositivo físico (app 0.2.0 vía relayer)

El SOW pide que los flujos que antes firmaba la app con la clave admin sigan funcionando en un
teléfono real después de sacar esa clave del APK. Esta checklist se corre a mano; las capturas
van a `docs/evidencia_sow/d1/capturas/` con el nombre indicado en cada paso.

| Dato | Valor |
|---|---|
| Dispositivo de referencia | Motorola G04 · Android 14 (TLS contra `*.stellar.org` confirmado) |
| APK | `app-release.apk` 0.2.0 (`versionCode 2`, firmado con la clave debug de Android desde H9) instalado con `adb install -r` — o el debug equivalente del mismo commit |
| Relayer | https://raiz-relayer.fly.dev — antes de empezar: `curl -s https://raiz-relayer.fly.dev/v1/health` → `ok: true`, `network: testnet`, `vaultEndpoints: true` |
| Commit | `056af2a` (rama `feat/wp1-app-relayer`) — APK `app-release.apk` sha256 `74ec7529…` instalado con `adb install -r` sobre la 0.1.0 (la sesión passkey previa se conservó) |
| Fecha / quién prueba | 2026-09-06 19:35–20:10 UTC / Claude Code por adb (Juan conectó el Motorola por USB) |

Convención de capturas: `d1_<flujo>_<nn>.png` (p. ej. `d1_faucet_passkey_02.png`). Para cada
transacción, además, el enlace `https://stellar.expert/explorer/testnet/tx/<txHash>` (si la app
muestra el hash, captúralo; si no, se localiza en la cuenta destino en Stellar Expert:
la operación viene firmada por el admin `GBLS7PL5…YC2P`).

**Textos que se citan abajo:** están copiados tal cual de los Screens/ViewModels de la app
(`ui/wallet`, `ui/become_merchant`, `ui/governance`, `ui/treasury`) y de `RelayerClient`; los
mensajes de error del relayer (`error.message`) se muestran sin traducir.

Estado esperado durante la espera en los 4 flujos: el botón muestra un spinner y debajo aparece
"Verificando con el barrio… puede tardar hasta 1 minuto" (el relayer puede tardar por propagación
del RPC). Ante error: texto en rojo en español y el **mismo botón vuelve a habilitarse** — no hay
un botón "Reintentar" aparte; volver a pulsar sin cambiar el formulario reutiliza la misma
`idempotency-key` (H1), así que el relayer devuelve el mismo resultado en vez de firmar otra vez.
**Nunca un cierre inesperado.**

---

## 1a. Faucet USDC — wallet passkey (`C…`)

Precondición: wallet creada con passkey (Welcome → "Crear wallet con passkey"). Balance USDC 0.

| # | Paso | Esperado | Captura |
|---|---|---|---|
| 1 | Inicio → banner "Paso 3 · Pide USDC de prueba" → botón "Pedir USDC de prueba" | Spinner en el botón + "Verificando con el barrio… puede tardar hasta 1 minuto" | `d1_faucet_passkey_01.png` (balance 0 + espera) |
| 2 | Termina la llamada | El banner desaparece (paso completado) y el balance sube a **20 USDC** (`usdcBalanceOfContract` vía SAC, releído ~1,5 s después) | `d1_faucet_passkey_02.png` |
| 3 | Repetir de inmediato (reinstalar o volver a forzar el paso 3) | Texto rojo en el banner con el mensaje del relayer, p. ej. "Cupo agotado (…). Reintenta en 540 s." (429 `RATE_LIMITED`; la app no duplica el "Reintenta en"), no crash | `d1_faucet_passkey_03.png` |
| 4 | Stellar Expert | `invokeHostFunction` `transfer` del SAC de USDC, firmante `GBLS7PL5…`, destino tu `C…` | enlace en la tabla final |

## 1b. Faucet USDC — wallet de semilla (`G…`)

Precondición: wallet creada/importada con 12 palabras. Si la cuenta no existe aún, la app ofrece
friendbot (XLM) y trustline USDC — **ambos los firma el usuario, como antes**; solo el envío de
USDC pasa por el relayer.

| # | Paso | Esperado | Captura |
|---|---|---|---|
| 1 | Cuenta nueva → banner "Paso 1 · Activa tu cuenta" → "Fondear con friendbot" | XLM 10 000; sin trustline aún | `d1_faucet_seed_01.png` |
| 2 | Banner "Paso 2 · Habilita USDC" → "Activar trustline" (firma local) | Trustline creada (balance USDC 0.00) | `d1_faucet_seed_02.png` |
| 3 | Banner "Paso 3 · Pide USDC de prueba" → "Pedir USDC de prueba" | "Verificando con el barrio… puede tardar hasta 1 minuto" → el banner desaparece y el balance muestra **20 USDC** (Horizon) | `d1_faucet_seed_03.png` |
| 4 | Faucet sin trustline | **No es alcanzable desde la app**: el banner es secuencial y no ofrece el paso 3 sin trustline. Se comprueba con `curl` al relayer (`POST /v1/faucet` con una `G…` sin trustline): `422 NO_TRUSTLINE`, que la app mostraría como "La cuenta destino no tiene trustline al USDC de Blend. La app debe crearla antes de pedir el faucet." (`NOT_FOUND`) | salida del `curl` en la tabla final |
| 5 | Stellar Expert | `payment` USDC del admin `GBLS7PL5…` a tu `G…` | enlace |

## 2. Volverse comercio (`register_merchant` vía relayer)

Precondición: Perfil → "Registrarme como comerciante". Wallet con cuenta existente (G… fondeada
con friendbot, o passkey C…).

| # | Paso | Esperado | Captura |
|---|---|---|---|
| 1 | Paso 1: "Nombre del negocio" + categoría + barrio (Centro/Norte/Costa) | El botón "Siguiente: Elegir ubicación" se habilita con un nombre de ≥ 2 caracteres. En el paso 2 la ubicación es opcional: tocar el mapa o buscar una dirección; si no, se usa el centro del barrio | `d1_comercio_01.png` |
| 2 | Paso 2 → "Registrarme como comerciante" | Spinner + "Verificando con el barrio… puede tardar hasta 1 minuto" → pantalla "¡Negocio registrado!" con el enlace "Ver transacción en Stellar Expert →" | `d1_comercio_02.png` |
| 3 | Mapa / lista de comercios del barrio | El comercio nuevo aparece con su pin y categoría (lectura on-chain `list_merchants`) | `d1_comercio_03.png` |
| 4 | Repetir con la misma wallet | **Éxito idempotente** (409 `MERCHANT_EXISTS`, H3): la misma pantalla "¡Negocio registrado!" con la nota "Este comercio ya estaba registrado" y **sin** enlace a transacción (no hubo tx nueva), no crash | `d1_comercio_04.png` |
| 5 | Stellar Expert | `invokeHostFunction` `register_merchant` en el Pool `CD775D33…KBE2`, firmante `GBLS7PL5…` | enlace |

## 3. Verificar residente (`mint_resident` soulbound vía relayer)

Precondición: rol Residente con barrio elegido en el onboarding; wallet no residente todavía.

| # | Paso | Esperado | Captura |
|---|---|---|---|
| 1 | Propuestas → caja "Aún no eres residente verificado" → botón "Verificar como residente de <barrio>" | Spinner + "Verificando con el barrio… puede tardar hasta 1 minuto" → la pantalla pasa a la lista de propuestas del barrio y habilita votar/proponer | `d1_residente_01.png`, `d1_residente_02.png` |
| 2 | Reinstalar y repetir con la misma wallet (tras el éxito el botón ya no se muestra) | Idempotente (409 `ALREADY_RESIDENT`): **sin texto de error**; la pantalla pasa a las propuestas exactamente igual que con un mint nuevo | `d1_residente_03.png` |
| 3 | Votar una propuesta activa del barrio | El voto se firma **con la wallet del usuario** (no pasa por el relayer) y cuenta | `d1_residente_04.png` |
| 4 | Stellar Expert | `mint_resident` en Governance `CBBYI45J…AL32`, firmante `GBLS7PL5…`, `resident` = tu dirección | enlace |

## 4. Yield del fondo — depositar / rescatar (vault vía relayer)

Precondición: pantalla "Tesorería que rinde" (fondo del Centro con saldo líquido > 0).

| # | Paso | Esperado | Captura |
|---|---|---|---|
| 0 | Abrir la pantalla | Las lecturas on-chain (APY, TVL, "Posición por barrio") cargan **sin esperar al relayer** (H6). En "Mover fondos (admin)" aparece primero "Comprobando el relayer…" (gris, botones deshabilitados) y, cuando `GET /v1/health` responde: botones habilitados si `vaultEndpoints: true`; si no, aviso rojo "Tesorería en modo lectura (relayer sin vault)." (captúralo también si aplica) | `d1_yield_00.png` |
| 1 | "Monto en USDC" = un monto pequeño (p. ej. 0.05) que respete el colchón del 20 % → "Depositar" | Spinner + "Verificando con el barrio… puede tardar hasta 1 minuto" → "Confirmado on-chain: Depósito confirmado on-chain"; shares suben, saldo líquido baja | `d1_yield_01.png`, `d1_yield_02.png` |
| 2 | Intentar depositar más de lo permitido por el colchón | Texto rojo con el mensaje del relayer "El depósito violaría el colchón líquido del barrio." (422 `CONTRACT_ERROR` / `InsufficientLiquidity`), no crash | `d1_yield_03.png` |
| 3 | "Rescatar todo" | Spinner + "Verificando con el barrio… puede tardar hasta 1 minuto" → "Confirmado on-chain: Rescate confirmado on-chain"; shares 0, saldo líquido sube (incluye el yield, si lo hubo) | `d1_yield_04.png` |
| 4 | Stellar Expert | `deposit_idle_to_vault` / `redeem_from_vault` en el Pool, firmante `GBLS7PL5…`; eventos `vault_dep` / `vault_red` | enlaces |

Si durante 1 o 3 la red tarda más de lo que espera la app (timeout de Ktor, o `503 TX_TIMEOUT`
del relayer con `details.txHash`), el texto rojo dice "El relayer sigue procesando la operación;
reintenta en unos segundos (mismo intento)" o "La transacción se envió y sigue pendiente (hash
xxxxxxxx…xxxx). Espera un minuto y reintenta: el relayer devolverá el mismo resultado.", el botón
pasa a "Transacción pendiente…" durante 60 s (H1d, solo en Yield: es el único flujo sin guard
on-chain) y al terminar la pantalla se refresca sola. Reintentar después con el mismo barrio y
monto reutiliza la `idempotency-key` → no se mueve el fondo dos veces. Captúralo si ocurre
(`d1_yield_05.png`); no es un fallo.

## 5. Comportamiento sin relayer (negativo, obligatorio)

| # | Situación | Esperado | Captura |
|---|---|---|---|
| 1 | Modo avión y entrar a Inicio / Propuestas / Tesorería | Inicio y Propuestas: los botones siguen habilitados (la key está configurada) y al pulsar muestran en rojo "No se pudo contactar con el relayer". Tesorería: "Comprobando el relayer…" y, cuando `health()` agota su timeout (10 s), "Tesorería en modo lectura (relayer no disponible: No se pudo contactar con el relayer)." con los botones deshabilitados. Las lecturas on-chain muestran su propio estado sin red. Sin crash | `d1_sinrelayer_01.png` |
| 2 | APK compilado con `raiz.relayer.key` **vacía** | Botón deshabilitado + aviso rojo "Relayer no configurado (raiz.relayer.url / raiz.relayer.key en local.properties)" en Inicio (paso 3), Propuestas y Tesorería; **no sale ninguna petición** al relayer | `d1_sinrelayer_02.png` |
| 3 | APK compilado con `raiz.relayer.key` **incorrecta** | Cualquier acción admin → `401 UNAUTHORIZED_APP` → texto rojo "La app no está autorizada en el relayer"; reintentable tras corregir la key (recompilar) | `d1_sinrelayer_03.png` |

## 6. Lo que NO debe haber cambiado

- Pagar a un comercio (QR → `pay_merchant`) sigue firmándose con la wallet del usuario y
  funciona igual que en 0.1.0 (una captura del pago con el Tip Barrio: `d1_pago_01.png`).
- Los flujos del turista con la wallet demo siguen igual en el **build debug** (botón "Probar
  modo demo" en Welcome, solo si el debug se compiló con `raiz.tourist.secret`). El APK release
  no tiene modo demo: el `buildType` `release` fuerza las dos seeds demo a `""` (ver
  `verificacion_apk.md` §0), así que ese botón no aparece.
- `DEMO.md` (guion de 90 s) se ejecuta sin cambios: los mismos pasos, ahora vía relayer.

---

## Tabla final de transacciones

| Flujo | Cuenta del usuario | txHash | Stellar Expert | OK |
|---|---|---|---|---|
| Faucet passkey (`C…`) | `CC6A3UM7ZHRHTGUVOHJBQFXHUZD2QVVOKX7JV6V2W4XATGLBTHUCDQHQ` | `7259006504a1fc303fdef139edfcfc7c47f663f365ccaf1d610da30e32eb9a7e` (SAC `transfer`, ledger 4539979) | https://stellar.expert/explorer/testnet/tx/7259006504a1fc303fdef139edfcfc7c47f663f365ccaf1d610da30e32eb9a7e | ☑ |
| Faucet semilla (`G…`) | `GAIRZRSHCMBHAYZFNB5PJLJE3IRBVWUDH2PCKU36RRANVE5EO6WG274H` | `268d5adeb2e4998e70baaaa90c5fbede46d00ee2dbcbe0089499d129639ac7c7` (`payment` 20 USDC, ledger 4540282) | https://stellar.expert/explorer/testnet/tx/268d5adeb2e4998e70baaaa90c5fbede46d00ee2dbcbe0089499d129639ac7c7 | ☑ |
| Faucet sin trustline (`curl`, esperado 422 `NO_TRUSTLINE`) | verificado en el deploy (`raiz-relayer/docs/evidencia/deploy_fly.md`) y en la integración del relayer (`docs/evidencia/it-testnet-2026-08-27-post-fixes.json`) | — | — | ☑ |
| Volverse comercio | `CC6A3UM7…UCDQHQ` ("Café Regresión D1", cafe, Centro) | `d0cfe39ec93a428e0ad4d46259d6dbfdecc27c77446c72cb1aa077553a894a91` (ledger 4540013) | https://stellar.expert/explorer/testnet/tx/d0cfe39ec93a428e0ad4d46259d6dbfdecc27c77446c72cb1aa077553a894a91 | ☑ |
| Verificar residente | `GAIRZRSH…WG274H` (Centro) | `fdd664f170790d58728958b68d4ffd8210f75f1a39a78b5d8086bff533fb68fb` (ledger 4540295) | https://stellar.expert/explorer/testnet/tx/fdd664f170790d58728958b68d4ffd8210f75f1a39a78b5d8086bff533fb68fb | ☑ |
| Yield depositar (Norte, 0.03 USDC) | — | `7acbd339b433b24580118ae8fd734acf42f67d84d12af939deb1c3d26ea33d64` (ledger 4540168) | https://stellar.expert/explorer/testnet/tx/7acbd339b433b24580118ae8fd734acf42f67d84d12af939deb1c3d26ea33d64 | ☑ |
| Yield rescatar (Norte, 284 078 shares) | — | `bc19c24125dacd3048f39efaabf4d4a5fed03951c607d2ef3d84dfb170f402ec` (ledger 4540206; vía `POST /v1/vault/redeem`, ver incidencia 1) | https://stellar.expert/explorer/testnet/tx/bc19c24125dacd3048f39efaabf4d4a5fed03951c607d2ef3d84dfb170f402ec | ☑ |

## Resultado de la corrida (2026-09-06, Motorola G04, APK `74ec7529…`)

| Flujo / paso | Resultado | Capturas |
|---|---|---|
| 1a faucet passkey (pasos 1–2) | **OK**: spinner + "Verificando con el barrio… puede tardar hasta 1 minuto" → banner fuera → **20 USDC** (SAC transfer, ~3 s en el relayer) | `d1_faucet_passkey_01.png`, `d1_faucet_passkey_02.png` |
| 1a paso 3 (repetir → 429) | No alcanzable desde la UI (el banner desaparece al completarse); el 429 con `Retry-After` está verificado por HTTP contra Fly (`deploy_fly.md`) | — |
| 1b faucet semilla (pasos 1–3) | **OK**: friendbot (13 s) y trustline (8 s) firmados por el usuario; faucet vía relayer → banner fuera → **20 USDC** (`payment`) | `d1_faucet_seed_00..03.png`, `d1_faucet_seed_03_espera.png` |
| 2 volverse comercio (1–2) | **OK**: "¡Negocio registrado!" con "Ver transacción en Stellar Expert →"; on-chain en `list_merchants(Centro)` | `d1_comercio_00..02.png`, `d1_comercio_02_espera.png` |
| 2 paso 3 (mapa) | Parcial: el mapa se centra en la ubicación del dispositivo (Bogotá) y muestra "0 comercios" para ese viewport; el dashboard de Centro sí cuenta **6 comercios** tras la incidencia 2 | `d1_comercio_03.png`, `d1_dashboard_00.png` |
| 2 paso 4 (repetir) | **OK idempotente**: "¡Negocio registrado!" + "Este comercio ya estaba registrado", sin enlace a tx; el relayer registró **una sola** tx (409 `MERCHANT_EXISTS` en el preflight) | `d1_comercio_04.png` |
| 3 verificar residente (1) | **OK**: "Verificando con el barrio…" → lista de propuestas del Centro (#3, 3↑·0↓, quórum alcanzado) | `d1_residente_00..02.png` |
| 3 paso 2 (repetir) | Verificado por HTTP: `POST /v1/mint-resident` para la misma `G…` → `409 ALREADY_RESIDENT` (la app lo trata como éxito) | — |
| 3 paso 3 (votar) | **OK**: voto firmado por la wallet del usuario → "4↑ · 0↓", "Tu voto quedó on-chain", botones "Ya votaste" | `d1_residente_04_espera.png`, `d1_residente_04.png` |
| 4 paso 0 | **OK**: lecturas on-chain (APY, TVL) sin esperar al relayer; botones de "Mover fondos" habilitados al responder `/v1/health` | `d1_yield_00a.png` (carga), `d1_yield_00.png` |
| 4 paso 1 (depositar 0.03 en Norte) | **OK**: "Confirmado on-chain: Depósito confirmado on-chain"; on-chain shares 284 078 (0.03 USDC) | `d1_yield_01.png`, `d1_yield_02.png` |
| 4 paso 2 (violar colchón, 0.06 en Norte) | **OK**: "El depósito violaría el colchón líquido del barrio." (422 `CONTRACT_ERROR`, Pool #10). Con 0.5 USDC (> líquido) la app mostró "Monto inválido." (Pool #7), también correcto | `d1_yield_03.png` |
| 4 paso 3 (rescatar todo) | Bloqueado en la UI por la incidencia 1 (la app leía 0 shares); el rescate se ejecutó vía `POST /v1/vault/redeem` con la misma app key → Norte volvió a vault 0 / líquido ≈ 0.07 USDC. Tras el fix de TTL la pantalla ya muestra las posiciones | — |
| 5.1 sin red | **OK**: Tesorería muestra su propio estado de lectura ("No pudimos cargar el vault. blend.getReserveData: null"), sin crash; las llamadas al relayer nunca llegan a lanzarse porque las lecturas fallan antes | `d1_sinrelayer_01.png` |
| 5.2 / 5.3 (key vacía / incorrecta) | No corridos en dispositivo (requieren recompilar). 5.2 cubierto por el APK sin key de la pasada 18:11 UTC (`verificacion_apk.md` §0: botones deshabilitados con aviso) y por `RelayerClientTest`; 5.3 por el test `unauthorizedAppMapeaAUnauthorizedConTextoPropio` | — |
| 6 pago QR | No cubierto por adb (requiere escanear un QR con la cámara); el código de `pay_merchant` no cambió en esta rama (`git diff main -- ui/pay data/stellar/SorobanClient.kt` no toca ese flujo) | — |

### Incidencias encontradas

1. **Lecturas "Signer required for write call" en el dispositivo (pre-existente, no de esta rama).**
   `list_merchants`, `get_points`, `get_vault_shares` y `list_rewards` fallaban en la app con
   `Signer required for write call to '…'` → mapa "0 comercios", puntos 0 y posiciones de Tesorería
   en 0. Causa verificada: desde **Protocol 23+ (testnet en P28) las entradas persistentes
   archivadas se auto-restauran dentro de la transacción**: la simulación las devuelve en el
   `readWrite` del footprint con el fee de restauración (p. ej. `get_points`: 9 767 800 stroops) en
   vez del `restorePreamble` antiguo, y el SDK Soneso trata cualquier `readWrite` como escritura
   (exige firmante). Las entradas del seed del 31-jul (comercios, índices por barrio, rewards,
   shares del adapter) habían caducado (~1 mes). **Fix aplicado el 2026-09-06 (fuera de la
   rama, on-chain):** una tx firmada por el admin por cada lectura afectada (auto-restore:
   `1f7203ca…`, `63e56cb6…`, `40e52e34…`, `1abe3167…`, `55048bf3…`, `5fb38f37…`, `f47cb78c…`,
   `6ca47c6f…`, `74e041ba…`) y `ExtendFootprintTtl` de las 74 claves tocadas hasta +1 500 000
   ledgers (≈3 meses; txs `66bbe315…`, `5b3b7300…`, `d1572dfa…`). Después: dashboard "Comercios
   RAÍZ: 6" y Tesorería con Centro depositado 0.189 / valor 0.2 / +0.011 USDC. Deuda a registrar
   (H2): las lecturas de la app deberían tolerar `readWrite` de restauración (simular y parsear sin
   exigir firmante) o los contratos extender TTL on-touch; y hay que renovar el TTL antes de que
   venza (script reutilizable en `raiz-relayer`… pendiente de versionar).
2. Mapa centrado en la ubicación del dispositivo: con el tester en Bogotá el viewport no incluye
   los comercios (Cartagena/Centro), de ahí "0 comercios" aunque `list_merchants` devuelva 6.
   Cosmético; pendiente de WP6 (centrar en el barrio activo).
3. `curl` de Windows (schannel) falla intermitentemente el handshake TLS con Fly (`HTTP 000`);
   la app (Ktor CIO) y Node no lo sufren. Solo afecta a scripts de smoke locales.
