# Evidencia SOW — D3: SEP-10 + SEP-24 (on-ramp con el anchor de prueba del SDF)

> Estado al **2026-09-27**: **código listo y en `main`** (compila; 17 tests JVM del
> cliente del anchor en verde; revisión adversarial aplicada el mismo día — ver "Cambios tras la
> revisión"). **Pendiente:** prueba end-to-end en el Motorola G04, grabación del video de 60 s y el tx
> hash del depósito real.

**Entregable D3 (SOW Instaward):** depósito SEP-24 interactivo completado end-to-end desde la app
contra `testanchor.stellar.org` (anchor de prueba de la Stellar Development Foundation), con
autenticación SEP-10 firmada por la wallet del usuario. Evidencia: video 60 s + tx hash.

**En una frase:** hasta ahora la única forma de "conseguir USDC" en RAÍZ era pedirlo al faucet del
relayer (dinero de prueba que regala el admin). Con D3 la app habla con un anchor real por los
estándares de la red (SEP-1 → SEP-10 → SEP-24): descubre sus endpoints, se autentica firmando un
challenge con la wallet, abre la web del anchor para que el usuario "deposite" y espera a que el
anchor envíe el USDC a la cuenta — la misma mecánica que usaría un anchor de producción (MoneyGram,
Anclap…) para convertir efectivo o una transferencia en USDC.

## Tabla entregable → evidencia

| Entregable SOW D3 | Evidencia | Dónde | Estado |
|---|---|---|---|
| Autenticación SEP-10 con la wallet del usuario | `AnchorClient.authenticate` (`WebAuth` del SDK Soneso: challenge → validaciones del SDK → firma con el `KeyPair` de la semilla → JWT). JWT solo en memoria. | `android/app/src/main/java/com/raiz/app/data/anchor/AnchorClient.kt` | Código listo (rama `feat/wp3-sep24`) |
| Depósito SEP-24 interactivo | `AnchorClient.startDeposit` (`POST /sep24/transactions/deposit/interactive`) + Custom Tab con la web del anchor + `pollDeposit` (`GET /sep24/transaction`) hasta `completed` | `AnchorClient.kt`, `ui/deposit/DepositViewModel.kt`, `ui/deposit/DepositScreen.kt`, `ui/deposit/CustomTabs.kt` | Código listo |
| Trustline al USDC del anchor creada por la app | `HorizonStream.enableTrustline` (ChangeTrust firmada por el usuario) antes de abrir la web; reintento si el anchor reporta `pending_trust` | `data/stellar/HorizonStream.kt` | Código listo |
| Saldo del USDC del anchor visible | Card negra "USDC · anchor de prueba" en Depositar; línea "● USDC · anchor de prueba: N USDC" en Inicio (no se suma al saldo USDC de Blend) | `ui/deposit/DepositScreen.kt`, `ui/wallet/WalletScreen.kt` | Código listo |
| Tests | 17 tests JVM con `MockEngine` (el `stellar.toml` real del anchor pegado tal cual, `/info`, multipart del deposit, mapeo de estados y errores incl. 401 y toml sin TLS, polling con backoff y timeout) | `android/app/src/test/java/com/raiz/app/data/anchor/AnchorClientTest.kt` | Verdes (`./gradlew :app:testDebugUnitTest --tests "com.raiz.app.data.anchor.*"`) |
| Prueba en dispositivo físico | Checklist de esta página | Motorola G04 / Android 14 | **Pendiente** |
| Video 60 s | [`guion_video.md`](guion_video.md) | YouTube no listado (el enlace se pega aquí cuando exista) | **Pendiente de grabar** |
| Tx hash del depósito | `stellar_transaction_id` que devuelve el anchor + verificación en Horizon | Esta página, sección "Tx hash del depósito" | **Pendiente** |

## Campos del portal (D3)

| # | Campo del portal (previsto) | Qué se pega | Estado |
|---|---|---|---|
| 1 | Video del depósito SEP-24 (60 s) | Enlace YouTube no listado. Guion: [`guion_video.md`](guion_video.md) | ⏳ Pendiente de grabar |
| 2 | Tx hash del pago del anchor | `https://stellar.expert/explorer/testnet/tx/<hash>` — el hash lo muestra la app en la pantalla "¡Depósito recibido!" y se contrasta en Horizon (ver "De dónde sale el tx hash") | ⏳ Pendiente (se rellena tras la prueba en dispositivo) |
| 3 | Captura de la app con el depósito completado | `capturas/07_deposito_recibido_hash.png` (pantalla de éxito con el chip "Ver en Stellar Expert") | ⏳ Pendiente |

## Alcance decidido (no se re-discute)

- **Asset:** el **USDC del testanchor** (`USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5`)
  llega a la wallet `G…` del usuario. **No es el USDC de Blend** que usa el fondo del barrio (issuer
  `GATALTGT…5V56`): son dos activos distintos con el mismo código. Por eso la UI lo rotula siempre
  **"USDC · anchor de prueba"** y nunca lo suma al saldo principal. Con ese USDC no se puede pagar a un
  comercio de RAÍZ (el swap a USDC-Blend queda como stretch / roadmap).
- **Solo wallets semilla (`G…`).** SEP-10 clásico firma con una cuenta Ed25519; un smart account
  passkey (`C…`) necesita SEP-45, que el anchor de prueba no soporta. Para passkey, la pantalla muestra
  "Disponible pronto para passkey (SEP-45)" sin botón.
- **El faucet del relayer no se borra:** pasa a ser la acción secundaria "USDC demo (Blend) · relayer"
  del paso 3 del onboarding; el CTA principal es "Depositar (anchor de prueba)".
- **Sin dependencias nuevas de Stellar:** todo con `kmp-stellar-sdk` 1.6.0 (módulos `sep.sep01`,
  `sep.sep10`, `sep.sep24`). Única dependencia nueva: `androidx.browser:browser:1.8.0` (Custom Tabs).
- **El JWT SEP-10 vive solo en memoria** (mapa por cuenta dentro de `AnchorClient`), nunca en
  `SharedPreferences` ni en disco.

## Cómo funciona (flujo real del código)

```
Inicio (WalletScreen)
 ├─ botón verde "Depositar · anchor de prueba" (bajo el saldo)
 └─ banner paso 3 "Consigue USDC" → CTA "Depositar (anchor de prueba)"
        │
        ▼
Depositar (DepositScreen / DepositViewModel)
 0. ¿wallet passkey (C…)?  ─ sí ─▶ "Disponible pronto para passkey (SEP-45)"  [fin]
 1. SEP-1  GET https://testanchor.stellar.org/.well-known/stellar.toml
           → WEB_AUTH_ENDPOINT, TRANSFER_SERVER_SEP0024, SIGNING_KEY, issuer USDC
    SEP-24 GET <sep24>/info → depósito USDC: mín 1 · máx 10
 2. ¿la cuenta G… existe en Stellar?  ─ no ─▶ "Fondear con friendbot"
 3. Monto (por defecto 5, validado 1..10 con 2 decimales; la coma vale como separador decimal)
    → "Depositar con el anchor" (revalida el monto justo antes de enviarlo)
 4. PREPARANDO (spinner con el paso en curso):
    a. "Habilitando USDC del anchor en tu cuenta…"  → ChangeTrust firmada por el usuario (si falta)
    b. "Autenticando con el anchor (SEP-10)…"        → GET challenge → validar → firmar → POST → JWT
    c. "Abriendo el depósito (SEP-24)…"              → POST /sep24/transactions/deposit/interactive
                                                       (asset_code, asset_issuer, account, amount,
                                                        lang=es, wallet_name=RAÍZ) → { id, url }
 5. Se abre `url` en una Custom Tab (toolbar negra RAÍZ). El usuario confirma monto y datos de
    prueba en la web del anchor ("SEP-24 Reference UI"). Mientras, la app YA está sondeando.
 6. Polling GET /sep24/transaction?id=… cada 3 s → ×1,5 hasta 10 s (timeout 5 min):
      incomplete / pending_user*                 → "Completa el depósito en la web del anchor"
      pending_anchor / on_hold / pending_stellar
        / pending_external                       → "El anchor está procesando…"
      pending_trust                              → "Falta la trustline: la app la crea ahora" (1 reintento)
      completed                                  → ✔ "¡Depósito recibido!" + hash + "Ver en Stellar Expert"
      refunded / expired / error / no_market
        / too_small / too_large                  → error con el mensaje del anchor + "Reintentar"
      timeout 5 min                              → "El anchor sigue procesando" → "Seguir esperando" / "Volver"
 7. "Volver a la wallet": Inicio muestra "● USDC · anchor de prueba: 5 USDC"; el saldo USDC (Blend) no cambia.
```

Detalles de robustez que sí están en el código: el `id` del depósito se guarda en `SavedStateHandle`
(si Android mata el proceso mientras el usuario está en la web del anchor, al volver a la pantalla se
retoma el polling); al volver a primer plano (`ON_RESUME`) se reanuda el polling si no había uno
activo ni una reanudación en curso; un `401/403` a mitad del polling (JWT caducado) re-autentica
**una vez** y sigue — si el anchor rechaza también la sesión nueva, la pantalla pasa a error ("El
anchor rechazó la sesión dos veces seguidas. Reintenta el depósito.") en vez de reintentar sin fin;
los errores de red transitorios no cortan el polling. "Abrir de nuevo la web del anchor" reabre la
misma URL interactiva (tras una restauración del proceso, donde esa URL no se conserva porque lleva
el token del anchor, usa el `more_info_url` que devuelve el anchor; hasta que llega la primera
respuesta el botón está deshabilitado) y "Cancelar" / "Volver" vuelven al formulario, borran el `id`
guardado y cancelan también la re-autenticación que estuviera en curso. Solo se abren URLs `https`
(las exige SEP-24): si el anchor devolviera otro esquema la app lo rechaza en vez de despacharlo como
intent implícito, y `loadInfo` rechaza un `stellar.toml` cuyos endpoints SEP-10/24 no sean `https`.

## De dónde sale el tx hash

1. **Fuente principal:** el campo `stellar_transaction_id` de `GET /sep24/transaction?id=…` cuando el
   estado es `completed` — es el hash de la transacción con la que el anchor pagó el USDC a la cuenta
   del usuario. La app lo muestra acortado en el chip verde **"Ver en Stellar Expert · abc123…xyz789"**
   de la pantalla de éxito; al pulsarlo se abre `https://stellar.expert/explorer/testnet/tx/<hash>`
   (la barra de direcciones del navegador enseña el hash completo).
2. **Respaldo (`HorizonStream.latestIncomingPayment`):** si el anchor marcara `completed` sin rellenar
   ese campo, la app busca en Horizon `/accounts/<G>/payments?order=desc&limit=20` el último pago
   **entrante** en `USDC` del issuer del anchor y toma su `transaction_hash`.
3. **Verificación independiente por el revisor (sin la app):**
   `https://horizon-testnet.stellar.org/accounts/<G…>/payments?order=desc&limit=5` → el registro con
   `asset_code = "USDC"`, `asset_issuer = "GBBD47IF…LFLA5"`, `to = <G…>` y `transaction_hash` igual al
   del chip. El `from` es una cuenta del anchor, no la del usuario, y el pago **no toca ningún contrato
   de RAÍZ** (es un pago clásico de Stellar).

## Hallazgos de la sonda del 27-sep contra el anchor (node, clave testnet desechable)

| Qué | Resultado | Consecuencia en el código |
|---|---|---|
| `stellar.toml` | `WEB_AUTH_ENDPOINT = https://testanchor.stellar.org/auth`, `TRANSFER_SERVER_SEP0024 = https://testanchor.stellar.org/sep24`, `SIGNING_KEY = GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR`, passphrase de testnet, currencies SRT / USDC (`GBBD47IF…LFLA5`) / native | Los endpoints y el issuer se leen del toml en tiempo real; la constante `ANCHOR_USDC_ISSUER` es solo respaldo si el toml no lo trae (y se avisa por log si difieren) |
| SEP-10 | El challenge trae 2 `manageData` (`testanchor.stellar.org auth` y `web_auth_domain`); el JWT dura **86 400 s (24 h)**, `sub` = G… del usuario, `iss` = auth endpoint | La sesión se cachea en memoria por cuenta mientras falten > 60 s para `exp`; un segundo depósito dentro de las 24 h no repite el challenge ni pide firma nueva |
| SEP-24 `/info` | Depósito USDC `enabled`, **mín 1, máx 10**; `fee.enabled = false`; `features.account_creation = false`; `claimable_balances = false` | Validación del monto 1..10 con 2 decimales (límites leídos del `/info`, no fijos); la cuenta debe existir antes (friendbot) porque el anchor no la crea |
| `POST …/deposit/interactive` | `{"type":"interactive_customer_info_needed","url":"https://anchor-ref-ui-testanchor.stellar.org?transaction_id=<uuid>&token=<jwt de 10 min>","id":"<uuid>"}` | La URL se abre en Custom Tab. El token de la URL caduca a los **10 min**: si el usuario tarda más en la web, hay que "Cancelar" y arrancar un depósito nuevo (arranca en un instante porque el SEP-10 ya está cacheado) |
| `GET …/transaction?id=` | Al inicio `status = incomplete`, `more_info_url`, `to = G…`, `refunded = false`; al completarse rellena `stellar_transaction_id`, `amount_in`, `amount_out`, `completed_at` | De ahí salen el hash del chip y el "N USDC · anchor de prueba" de la pantalla de éxito (`amount_out`) |
| Web interactiva | SPA React "SEP-24 Reference UI": el usuario confirma monto y datos KYC de prueba y el anchor envía el pago | Es el paso que el video debe mostrar sin cortes |
| Sin trustline | La transacción se queda en `pending_trust` | La app crea la trustline **antes** de abrir la URL y, si aun así llega `pending_trust`, la reintenta una vez |
| Estados posibles (enum del SDK) | `incomplete, pending_user_transfer_start, pending_user_transfer_complete, pending_external, pending_anchor, on_hold, pending_stellar, pending_trust, pending_user, completed, refunded, expired, no_market, too_small, too_large, error` | Mapeados a 11 estados propios (`AnchorDepositState`) para que la UI muestre un texto humano por grupo |

## Checklist de prueba en dispositivo (Motorola G04 / Android 14, por adb)

Requisitos: Chrome (o cualquier navegador con Custom Tabs) instalado; `android/local.properties`
con `raiz.relayer.key`, el token de Mapbox y (opcional) la semilla demo; red estable.

```bash
cd android && ./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell setprop log.tag.RAIZ VERBOSE      # en algunos OEM los Log.i se filtran (gotcha de CLAUDE.md)
adb logcat -s RAIZ                           # AnchorClient / DepositViewModel loguean con TAG RAIZ
```

| # | Paso | Resultado esperado | Captura (`capturas/`) |
|---|---|---|---|
| 1 | Wallet **semilla** con XLM (si es nueva: banner paso 1 → friendbot). Abrir **Inicio** | Bajo el saldo, botón verde "Depositar · anchor de prueba". Si la wallet ya tiene trustline USDC-Blend pero 0 USDC: banner "Paso 3 · Consigue USDC" con CTA verde "Depositar (anchor de prueba)" y el texto "USDC demo (Blend) · relayer" debajo | `01_inicio_boton_depositar.png` |
| 2 | Tap "Depositar · anchor de prueba" | Pantalla "Depositar USDC", subtítulo "Anchor de prueba del SDF · testanchor.stellar.org · SEP-10 + SEP-24", card negra "USDC · anchor de prueba **0 USDC**", monto 5, helper "Mín 1 · Máx 10 en el anchor de prueba", 3 pasos explicados | `02_depositar_ready.png` |
| 3 | Probar el validador: monto `0.5` y `11`; con el teclado del dispositivo (separador `,`) teclear `1,5` | "Mínimo 1 USDC" / "Máximo 10 USDC", CTA deshabilitado; `1,5` se muestra como `1.5` y pasa la validación (no muta a `15`). Volver a `5` | `03_validacion_monto.png` |
| 4 | Tap "Depositar con el anchor" | Spinner con "Habilitando USDC del anchor en tu cuenta…" (crea la trustline la primera vez; después pasa en un instante) → "Autenticando con el anchor (SEP-10)…" → "Abriendo el depósito (SEP-24)…". En logcat: `AnchorClient.authenticate: sesión SEP-10 obtenida (exp=…)` | `04_preparando_sep10.png` |
| 5 | Se abre la Custom Tab con la web del anchor | Toolbar negra; dominio `anchor-ref-ui-testanchor.stellar.org` con `transaction_id=…`. Confirmar monto y datos de prueba, enviar | `05_custom_tab_anchor.png` |
| 6 | Volver a la app (atrás / cerrar pestaña) | Card con chip púrpura del estado del anchor (`incomplete` → `pending_anchor` / `pending_stellar` → `completed`) y "Esperando al anchor… Ns" contando | `06_polling_estado.png` |
| 7 | Al llegar `completed` | Pantalla "¡Depósito recibido!" · "5 USDC · anchor de prueba" · chip verde "Ver en Stellar Expert · abc123…xyz789" | `07_deposito_recibido_hash.png` |
| 8 | Tap el chip | Stellar Expert abre la tx: pago de 5 USDC (issuer `GBBD47IF…`) a la G… del dispositivo. **Anotar el hash completo en esta página y en `docs/evidencia_sow/README.md`** | `08_stellar_expert_tx.png` |
| 9 | "Volver a la wallet" | En Inicio, línea "● USDC · anchor de prueba: 5 USDC" bajo el botón; el saldo USDC (Blend) del BalanceCard **no cambió** | `09_inicio_saldo_anchor.png` |
| 10 | Contraste en Horizon (PC) | `https://horizon-testnet.stellar.org/accounts/<G…>/payments?order=desc&limit=5` → `transaction_hash` igual al del chip, `asset_issuer = GBBD47IF…LFLA5` | `10_horizon_payments.png` |
| 11 | Segundo depósito (p. ej. 1 USDC) | Los pasos de trustline y SEP-10 pasan en un instante (trustline ya existe; sesión cacheada 24 h, sin firma nueva) y se abre la web del anchor directamente | — |
| 12 | Negativo: wallet **passkey** → Depositar | Card "Disponible pronto para passkey (SEP-45)", sin botón; Inicio no muestra la línea del USDC del anchor | `11_passkey_no_soportado.png` |
| 13 | Negativo: durante el polling, modo avión 20 s y volver | El polling no se cae (en logcat `pollDeposit(...): error transitorio`), el contador sigue y el estado avanza al volver la red | — |
| 14 | Negativo: en la web del anchor, ir a Inicio y matar la app desde recientes; reabrir → Depositar | Restaura el depósito en curso (id en `SavedStateHandle`) y retoma el polling | — |
| 15 | "Cancelar" a mitad de flujo | Vuelve al formulario con el monto; el `id` guardado se borra | — |

Resultado de la prueba: **pendiente** (fecha, APK/commit, hash y capturas se anotan aquí al ejecutarla).

## Tx hash del depósito

| Fecha (UTC) | Cuenta destino (G…) | Monto | tx hash | Stellar Expert |
|---|---|---|---|---|
| — | — | — | *pendiente de la prueba en dispositivo* | — |

## Notas honestas

- **El USDC depositado no sirve para pagar dentro de RAÍZ.** El Pool y los comercios trabajan con el
  USDC de Blend; el del anchor es otro activo (mismo código, otro emisor). Se muestra por separado a
  propósito para no mentir sobre fungibilidad. El swap (path payment) al USDC de Blend, o un anchor
  que emita el USDC del fondo, son la siguiente fase, no D3.
- **Anchor de prueba, KYC simulado.** La web interactiva del testanchor es la "SEP-24 Reference UI"
  del SDF: pide datos de prueba y no mueve dinero real. Lo que sí es real es el protocolo (SEP-1/10/24
  tal cual lo usaría un anchor de producción) y el pago on-chain en testnet.
- **Passkey fuera** hasta que exista SEP-45 en el anchor (o un anchor que lo soporte).
- **El camino feliz de SEP-10 no tiene test unitario**: validar un challenge exige que lo firme la
  `SIGNING_KEY` del anchor; con `MockEngine` solo se cubre el error de transporte del challenge
  (`500 → NETWORK_ERROR`). El resto (toml, `/info`, deposit, estados, polling) sí está cubierto (17 tests).
- **El token de la URL interactiva dura 10 min.** La app reabre la misma URL con "Abrir de nuevo la web
  del anchor"; si el usuario tardó más de 10 min, hay que cancelar y empezar un depósito nuevo (sin
  volver a firmar: el JWT SEP-10 dura 24 h). Comprobar en dispositivo cómo lo indica la web del anchor.
- **Custom Tabs necesita un navegador compatible** (Chrome, Edge, Brave…); si no hay, la app cae a un
  `Intent VIEW` normal. El manifest declara `<queries>` para que Android 11+ permita detectarlo.
- **El polling vive en el ViewModel** (`viewModelScope`): sigue mientras la app esté viva en segundo
  plano detrás de la Custom Tab; si el sistema mata el proceso, se retoma al volver gracias al `id`
  guardado. No hay servicio en primer plano ni notificaciones — no hace falta para un flujo de minutos.
- **Nada de D3 toca los contratos** (ni Rust ni `deployments.json`): es integración cliente ↔ anchor ↔
  Horizon. Por eso no hay TTL que extender ni redeploy.

## Cambios en la app (resumen técnico)

| Archivo | Qué |
|---|---|
| `data/anchor/AnchorModels.kt` (nuevo) | `AnchorInfo`, `AnchorSession` (`isUsable` con colchón de 60 s), `AnchorDepositStart`, `AnchorDepositState` (11 estados), `AnchorDepositStatus` (`isTerminal`), excepciones `AnchorPollTimeout` / `AnchorSessionExpired` |
| `data/anchor/AnchorClient.kt` (nuevo, `@Singleton`) | `loadInfo` (toml + `/info`, caché; rechaza endpoints sin `https`), `authenticate` (SEP-10, caché por cuenta), `cachedSession` / `clearSession`, `startDeposit`, `depositStatus`, `pollDeposit` (backoff 3 s → ×1,5 → 10 s, timeout 5 min). Mapeo de excepciones del SDK → `RaizResult`: 401/403 (con o sin `type=authentication_required`) y challenge inválido → `UNAUTHORIZED`; 400 → `PARSE_ERROR`; 404 → `NOT_FOUND`; 5xx / red / toml caído → `NETWORK_ERROR` |
| `data/stellar/HorizonStream.kt` | Generalizado a cualquier asset clásico: `assetBalanceFlow`, `getAssetBalance`, `hasTrustline`, `enableTrustline`, `latestIncomingPayment`; los métodos `*Usdc*` existentes delegan con el USDC de Blend |
| `data/model/PaymentRecord.kt` | `assetIssuer` (para distinguir los dos USDC) |
| `data/model/RaizConstants.kt` | `ANCHOR_HOME_DOMAIN`, `ANCHOR_USDC_CODE`, `ANCHOR_USDC_ISSUER` |
| `di/DataModule.kt` | `HttpClient` `@Named("anchor")` (CIO, timeouts 30/15/30 s, `expectSuccess = false`), distinto del del relayer |
| `ui/deposit/DepositViewModel.kt`, `DepositScreen.kt`, `CustomTabs.kt` (nuevos) | Pantalla "Depositar USDC" con 10 fases, Custom Tab con toolbar `RaizBlack`, éxito con `RaizSuccessAnimation` y chip a Stellar Expert |
| `ui/wallet/WalletScreen.kt`, `WalletViewModel.kt` | Botón "Depositar · anchor de prueba", línea de saldo del anchor (solo wallets G…), paso 3 del banner con dos caminos |
| `MainActivity.kt` | Ruta `deposit` |
| `AndroidManifest.xml`, `libs.versions.toml`, `build.gradle.kts` | `<queries>` para Custom Tabs; `androidx.browser 1.8.0` |
| `test/.../data/anchor/AnchorClientTest.kt` (nuevo) | 17 tests JVM (`MockEngine`, `runTest`; el `stellar.toml` del testanchor pegado tal cual) |

## Cambios tras la revisión adversarial (2026-09-27)

Dos verificadores independientes revisaron la rama antes de probarla en dispositivo. Lo que se corrigió:

| Hallazgo | Arreglo |
|---|---|
| La coroutine de reanudación (`resumePolling`) no se rastreaba: "Cancelar"/"Volver" durante la re-autenticación no la cancelaba y el polling resucitaba; dos taps en "Seguir esperando" disparaban dos SEP-10 | `resumeJob` rastreado; `reset()` lo cancela; guard común en `resumePolling`/`onResume`; `beginPolling` comprueba que el depósito siga en curso antes de arrancar |
| Re-autenticación por `401/403` sin tope (bucle sin fin si el anchor rechaza también el JWT nuevo) | Máximo una re-autenticación por cadena de polling; la segunda → error "El anchor rechazó la sesión dos veces seguidas" |
| El monto inicial "5" no se contrastaba con los límites reales de `/info`, y `startDeposit` no revalidaba | Se valida al cargar `/info` y otra vez justo antes de enviar |
| Un `401` (o `403` sin `type=authentication_required`) se trataba como error de red y se sondeaba 5 min | Ambos → `UNAUTHORIZED` → `AnchorSessionExpired` → re-autenticación (test nuevo) |
| Endpoints SEP-10/24 del toml sin `https` se aceptaban (el JWT viajaría en claro; CIO no aplica la NetworkSecurityPolicy) | `loadInfo` los rechaza con `PARSE_ERROR` (test nuevo) |
| La coma decimal se descartaba: en teclados es-CO/es-ES "1,0" mutaba a "10" en silencio | `,` → `.` antes de validar; varios puntos → "Monto inválido"; "5." viaja como "5" |
| La URL interactiva / `more_info_url` se abría sin comprobar el esquema | Solo `https`, en el ViewModel y en `CustomTabs.open` |
| "Abrir de nuevo la web del anchor" era un no-op silencioso tras restaurar el proceso hasta la primera respuesta | Botón deshabilitado mientras no haya URL conocida (la URL interactiva NO se persiste: lleva el token del anchor) |
| El fixture del `stellar.toml` era una versión recortada a mano, no el real "tal cual" que pide el contrato | Sustituido por el toml íntegro del 27-sep (mismas aserciones) |

