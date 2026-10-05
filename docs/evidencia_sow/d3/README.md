# Evidencia SOW — D3: SEP-10 + SEP-24 (on-ramp con el anchor de prueba del SDF)

> Estado al **2026-10-04**: **D3 probado de punta a punta en el Motorola G04 y grabado.** El D3 base
> (SEP-10 + SEP-24) está en `main` desde el 27-sep. El 3-oct se instaló el APK en el teléfono y se
> completaron **dos depósitos SEP-24 reales** contra `testanchor.stellar.org`, cada uno seguido de la
> conversión al USDC del fondo (stretch). Hay video de 60 s, capturas y los
> hashes de las cuatro transacciones (sección "Resultado de la prueba"). 61 tests JVM en verde.
> Código, video y capturas están en `main` desde el 3-oct, y el flujo viaja en el APK final
> [`raiz-0.3.0.apk`](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.3.0) (4-oct).
> **Pendiente:** solo pegar los campos en el portal. El video ya está publicado en
> <https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4>.

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
| Autenticación SEP-10 con la wallet del usuario | `AnchorClient.authenticate` (`WebAuth` del SDK Soneso: challenge → validaciones del SDK → firma con el `KeyPair` de la semilla → JWT). JWT solo en memoria. | `android/app/src/main/java/com/raiz/app/data/anchor/AnchorClient.kt` | Código listo (en `main`) |
| Depósito SEP-24 interactivo | `AnchorClient.startDeposit` (`POST /sep24/transactions/deposit/interactive`) + Custom Tab con la web del anchor + `pollDeposit` (`GET /sep24/transaction`) hasta `completed` | `AnchorClient.kt`, `ui/deposit/DepositViewModel.kt`, `ui/deposit/DepositScreen.kt`, `ui/deposit/CustomTabs.kt` | Código listo |
| Trustline al USDC del anchor creada por la app | `HorizonStream.enableTrustline` (ChangeTrust firmada por el usuario) antes de abrir la web; reintento si el anchor reporta `pending_trust` | `data/stellar/HorizonStream.kt` | Código listo |
| Saldo del USDC del anchor visible | Card negra "USDC · anchor de prueba" en Depositar; línea "● USDC · anchor de prueba: N USDC" en Inicio (no se suma al saldo USDC de Blend) | `ui/deposit/DepositScreen.kt`, `ui/wallet/WalletScreen.kt` | Código listo |
| Tests | 17 tests JVM con `MockEngine` (el `stellar.toml` real del anchor pegado tal cual, `/info`, multipart del deposit, mapeo de estados y errores incl. 401 y toml sin TLS, polling con backoff y timeout) | `android/app/src/test/java/com/raiz/app/data/anchor/AnchorClientTest.kt` | Verdes (`./gradlew :app:testDebugUnitTest --tests "com.raiz.app.data.anchor.*"`) |
| **Extra (stretch del plan WP3, no lo exige el SOW):** conversión al USDC del fondo | Card "Convertir a USDC del fondo": cotización en vivo (`HorizonStream.quoteStrictSend` → `GET /paths/strict-send`) y `PathPaymentStrictSend` firmada por el usuario con destino su propia cuenta y `dest_min` = cotización − 1 % (`HorizonStream.pathPaymentStrictSend`); crea la trustline al USDC de Blend si falta | `data/stellar/SwapMath.kt`, `data/stellar/HorizonStream.kt`, `ui/deposit/DepositViewModel.kt`, `ui/deposit/DepositScreen.kt` | En `main` desde el 3-oct; **probado en el Motorola G04** (2 conversiones reales) |
| Tests de la conversión | 19 tests JVM de aritmética entera sobre stroops (stroops ↔ decimal de 7 cifras de Horizon, truncado, `dest_min` con 100 bps, ida y vuelta). La cotización y el envío hablan con Horizon y **no** tienen test unitario | `android/app/src/test/java/com/raiz/app/data/stellar/SwapMathTest.kt` | Verdes (`./gradlew :app:testDebugUnitTest --tests "com.raiz.app.data.stellar.*"`); suite completa: 61 (17 anchor + 24 relayer + 19 conversión + 1 formato de montos) |
| Prueba en dispositivo físico | Sección "Resultado de la prueba (2026-10-03)" + 9 capturas en [`capturas/`](capturas/) | Motorola G04 / Android 14 | ✅ **Hecha el 3-oct** |
| Video 60 s | [`video/d3_deposito_sep24_60s.mp4`](video/d3_deposito_sep24_60s.mp4) (61 s, el depósito sin cortes) y [`video/d3_deposito_y_conversion_completo.mp4`](video/d3_deposito_y_conversion_completo.mp4) (92 s, incluye la conversión y el saldo final) | Esta carpeta; para verlos con un clic: [60 s](https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4) · [completo](https://raizapp.xyz/evidencia/d3_deposito_y_conversion_completo.mp4). YouTube no listado opcional | ✅ **Grabado el 3-oct** |
| Tx hash del depósito | `stellar_transaction_id` que devuelve el anchor + verificación en Horizon | [`ae4d3e43…0edf7562`](https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562) (sección "Tx hash del depósito") | ✅ |
| Tx hash de la conversión (extra) | `hash` con el que Horizon acepta la `PathPaymentStrictSend` del usuario (chip verde de la card / logcat / Horizon `operations`) | [`23e926f4…e83d3658`](https://stellar.expert/explorer/testnet/tx/23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658) (sección "Tx hash de la conversión") | ✅ |

## Campos del portal (D3)

| # | Campo del portal (previsto) | Qué se pega | Estado |
|---|---|---|---|
| 1 | Video del depósito SEP-24 (60 s) | https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4 (se reproduce en el navegador con un clic; es una copia del archivo de esta carpeta, [`video/d3_deposito_sep24_60s.mp4`](video/d3_deposito_sep24_60s.mp4), que GitHub solo deja descargar). Si el portal pide YouTube: subir ese mismo archivo como no listado | ✅ Grabado el 3-oct |
| 2 | Tx hash del pago del anchor | https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562 | ✅ Pago de 4,5 USDC del anchor a la wallet del teléfono, verificado en Horizon. El enlace se abrió el 4-oct en un navegador en incógnito: "Successful", `transfer(GABC…YCZP, GABZ…ISJ3, 45000000)` — captura [`10_stellar_expert_tx_deposito_incognito.png`](capturas/10_stellar_expert_tx_deposito_incognito.png) |
| 3 | Captura de la app con el depósito completado | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d3/capturas/06_deposito_recibido_hash_y_cotizacion.png | ✅ Pantalla "¡Depósito recibido!" con el chip "Ver en Stellar Expert · ae4d3e…df7562" |

## Alcance decidido (no se re-discute)

- **Asset:** el **USDC del testanchor** (`USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5`)
  llega a la wallet `G…` del usuario. **No es el USDC de Blend** que usa el fondo del barrio (issuer
  `GATALTGT…5V56`): son dos activos distintos con el mismo código. Por eso la UI lo rotula siempre
  **"USDC · anchor de prueba"** y nunca lo suma al saldo principal. Con ese USDC no se puede pagar a un
  comercio de RAÍZ **hasta convertirlo**: el swap a USDC-Blend, que el 27-sep quedó como stretch, se
  implementó después (en `main` desde el 3-oct) y se probó en el teléfono ese día (sección
  "Convertir a USDC del fondo").
- **Solo wallets semilla (`G…`).** SEP-10 clásico firma con una cuenta Ed25519; un smart account
  passkey (`C…`) necesita SEP-45. Corrección del 4-oct: el anchor de prueba **sí** lo ofrece ya (su
  `stellar.toml` publica `WEB_AUTH_FOR_CONTRACTS_ENDPOINT` y el endpoint responde); lo que falta es
  implementarlo en la app, cuyo texto en pantalla todavía dice lo contrario. Para passkey, la pantalla muestra
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
                                                   (+ card "Convertir a USDC del fondo", paso 8)
      refunded / expired / error / no_market
        / too_small / too_large                  → error con el mensaje del anchor + "Reintentar"
      timeout 5 min                              → "El anchor sigue procesando" → "Seguir esperando" / "Volver"
 7. "Volver a la wallet": Inicio muestra "● USDC · anchor de prueba: 4.5 USDC" (el anchor de prueba
    descuenta 0,5 de comisión a un depósito de 5); el saldo USDC (Blend) no cambia mientras ese USDC
    no se convierta.
 8. (Opcional) "Convertir a USDC del fondo": cotiza en Horizon y envía un path
    payment firmado por el usuario → el saldo del anchor vuelve a 0, sube el USDC (Blend) de Inicio y
    el banner de alta ("Paso 3 · Consigue USDC") desaparece. Flujo completo en su sección.
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
   `transaction_hash` igual al del chip. **Medido el 3-oct:** el anchor de prueba no paga con un
   `payment` clásico sino con un `invoke_host_function` (transfer del contrato del activo, SAC); el
   movimiento aparece dentro de `asset_balance_changes`: `type = "transfer"`, `asset_code = "USDC"`,
   `asset_issuer = "GBBD47IF…LFLA5"`, `from = GABCKCYP…YCZP` (cuenta del anchor), `to = <G…>`,
   `amount = "4.5000000"`. La app ya leía ambos formatos (`HorizonStream.recordsFromOp`). El pago **no
   toca ningún contrato de RAÍZ**.

## Convertir a USDC del fondo (stretch del plan WP3, cumplido)

> Código en `main` desde el 2026-10-03 (desarrollado en la rama `feat/wp3-sep24`). **Probado en el Motorola
> G04 el 3-oct con dos conversiones reales** y revisado por tres revisores adversariales ese mismo día
> (10 defectos corregidos antes de grabar; ver "Cambios tras la revisión de la conversión"). No es un
> entregable del SOW (D3 es el depósito): es el stretch que el plan dejaba para "si sobra tiempo"
> (`docs/PLAN_CLAUDE_CODE_SOW.md`, WP3).

**Problema.** El depósito SEP-24 entrega `USDC:GBBD47IF…LFLA5` (anchor de prueba) y el Pool y los
comercios cobran en `USDC:GATALTGT…5V56` (Blend). En testnet son dos activos con el mismo código, así
que lo depositado no servía para pagar. **En mainnet este paso no existe:** allí el USDC de Circle es
uno solo — el que entrega un anchor de producción es el mismo que usa el fondo.

**Decisión.** Conversión **no custodial, en un tap**: una operación clásica `PathPaymentStrictSend`
firmada por la wallet del usuario y con **destino su propia cuenta** (el dinero no pasa por ninguna
cuenta de RAÍZ ni por el relayer; solo cambia de activo), contra un pool de liquidez clásico de testnet
entre los dos USDC. Se convierte **siempre el saldo completo** de USDC del anchor (no hay campo de
monto). La cotización sale de `GET /paths/strict-send` de Horizon y el mínimo aceptable (`dest_min`)
es la cotización − 1 % (`SwapMath.SLIPPAGE_BPS = 100`): si el pool se mueve más que eso entre cotizar
y enviar, la red rechaza la operación y el usuario conserva su USDC del anchor. El plan sugería
*strict receive*; se usó *strict send* porque lo que se conoce con exactitud es lo que se envía (todo
el saldo), no lo que se recibe.

### El pool de liquidez (verificado en Horizon testnet)

`https://horizon-testnet.stellar.org/liquidity_pools/23283282cba3c5363761ac9a7ce1ca027f6205f07107c730a76cfc021b9439e9`
— tipo `constant_product`, `fee_bp = 30` (0,3 %), 2 000 shares de un único proveedor de liquidez
(`GDU2NVZ3…BKFE`, que no es la cuenta admin de RAÍZ).

| Fecha de la consulta | Reserva USDC-Blend | Reserva USDC-anchor | `strict-send` de 5 USDC del anchor | `dest_min` (− 1 %) |
|---|---|---|---|---|
| 2026-09-27 | ≈ 1 920,72 | ≈ 2 150,41 | **4,4422608** USDC del fondo (`path = []`: un solo salto, el pool) | 4,3978381 |
| 2026-10-03 | 2 061,3022633 | 2 031,2084901 | **5,0464711** USDC del fondo (`path = []`) | 4,9960063 |
| 2026-10-04 02:50 UTC (conversión real nº 1) | — | — | 4,5 del anchor → **4,5429362** recibidos | 4,4975068 |
| 2026-10-04 03:11 UTC (conversión real nº 2, la del video) | 2 052,2364013 tras la operación | 2 040,2084901 tras la operación | 4,5 del anchor → **4,5229258** recibidos | 4,4776965 |

La cotización es la del instante y **no es 1:1**: depende de las reservas. Entre las dos consultas pasó
de ≈ 0,89 a ≈ 1,01 USDC del fondo por cada USDC del anchor, y el 2 y el 3 de octubre otras cuentas de
testnet (`GDNQ75…M7DR`, `GCNN2B…6XPN`) pasaron por este pool path payments de miles de USDC
(`…/liquidity_pools/<id>/operations?order=desc`). Para repetir la cotización desde un PC:

```bash
curl -s "https://horizon-testnet.stellar.org/paths/strict-send?source_asset_type=credit_alphanum4&source_asset_code=USDC&source_asset_issuer=GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5&source_amount=5&destination_assets=USDC:GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56"
```

### Flujo real del código

```
¿Dónde aparece la card "Convertir a USDC del fondo"? (blanca, borde púrpura; solo wallets semilla G…)
 ├─ en "¡Depósito recibido!", bajo el chip del hash, en cuanto el saldo del anchor refrescado es > 0
 └─ en el formulario de Depositar (fase READY) si la cuenta ya tiene saldo del anchor > 0:
    entre la card negra del saldo y el campo del monto
        │
        ▼
 1. Cotizar — automático, sin botón (al entrar con saldo y al completarse un depósito)
      GET /paths/strict-send
        source      = USDC del anchor (issuer leído del stellar.toml) · source_amount = saldo COMPLETO
        destination = USDC del fondo (deployments.usdcIssuer)
      → de los caminos devueltos se toma el de mayor destination_amount
      → dest_min = cotización × 9 900 / 10 000 (entero en stroops, redondeo hacia abajo)
      "Cotizando…" → "Recibirás ≈ <N> USDC · pool de liquidez de testnet, fee 0,3 %, tolerancia 1 %"
                     + botón púrpura "Convertir <saldo> USDC"
      (una conversión en vuelo no se re-cotiza; al empezar un depósito nuevo se descarta la cotización)
      sin caminos   → "No hay liquidez en testnet para convertir este USDC ahora." + "Reintentar"
 2. Tap "Convertir"
      a. ¿la cuenta tiene trustline al USDC del fondo?
           no → "Habilitando el USDC del fondo en tu cuenta…" (ChangeTrust firmada por el usuario)
      b. "Enviando la conversión…" → PathPaymentStrictSend firmada por el usuario
           send_asset = USDC del anchor · send_amount = saldo completo
           destination = SU MISMA cuenta G… · dest_asset = USDC del fondo
           dest_min = cotización − 1 % · path = el mismo camino que se cotizó (no se vuelve a pedir)
           fee base 100 stroops, timeout 60 s, enviada directo a Horizon (ni relayer ni contratos)
           La tx se construye y firma UNA vez y el sobre se envía UNA vez (un path payment no es
           idempotente). Si la conexión se corta sin respuesta, la app consulta la tx por su hash
           antes de declarar fallo: puede haber entrado igualmente.
 3. Resultado
      ok    → "✓ Convertido: recibiste <N> USDC del fondo" + chip verde "Ver en Stellar Expert · hash".
              <N> es el monto REAL (campo `amount` de la operación en Horizon), no la cotización; si
              esa lectura fallara se muestra la cotización con "≈". El saldo del anchor queda en 0 y la
              card sigue visible con el resultado (también en el formulario, no solo en la pantalla de éxito).
      error → mensaje en rojo + "Reintentar", que RELEE el saldo y vuelve a cotizar (nunca reenvía a ciegas):
                op_under_dest_min / op_too_few_offers → "La liquidez cambió: vuelve a cotizar."
                op_underfunded                        → "No tienes suficiente USDC del anchor."
                op_no_trust                           → "Falta la trustline al USDC del fondo."
                sin respuesta y la tx no aparece      → "No se pudo confirmar la conversión. Revisa tu saldo…"
                cualquier otro                        → "Horizon rechazó la conversión: {result_codes}"
 4. Inicio: el BalanceCard (USDC de Blend) ya incluye lo convertido → sirve para "Escanear y pagar".
    La línea "● USDC · anchor de prueba" deja de pintarse (solo aparece con saldo > 0) y el banner de
    alta avanza a "listo" (Inicio re-evalúa el paso pendiente al volver).
```

La card cierra siempre con la nota: "En mainnet este paso no existe: el USDC de Circle es uno solo. En
testnet el anchor de prueba emite otro USDC." Los montos viajan como `Long` en stroops de punta a punta
(`SwapMath`); a Horizon van como decimal de 7 cifras y nunca pasan por `Double`.

### De dónde sale el hash de la conversión

1. **Fuente:** el `hash` con el que Horizon responde al `submitTransaction` de la transacción que firmó
   el propio usuario. La app lo muestra acortado en el chip verde de la card ("Ver en Stellar Expert ·
   abc123…xyz789"), que abre `https://stellar.expert/explorer/testnet/tx/<hash>`.
2. **Logcat** (`adb logcat -s RAIZ`): `pathPaymentStrictSend: USDC→USDC OK para G…, hash=<hash completo>`
   — a diferencia del depósito, aquí el log sí imprime el hash.
3. **Verificación independiente:** `https://horizon-testnet.stellar.org/accounts/<G…>/operations?order=desc&limit=5`
   → la operación `type = "path_payment_strict_send"` con `from = to = <G…>`,
   `source_asset_issuer = "GBBD47IF…LFLA5"`, `asset_issuer = "GATALTGT…5V56"`, `source_amount` = lo
   convertido y `amount` = lo realmente recibido. La misma operación aparece en
   `…/liquidity_pools/23283282…/operations`.

### Límites conocidos (tras los arreglos del 3-oct)

- **El precio lo pone un pool de testnet que RAÍZ no controla.** La app enseña la cotización antes del
  botón, pero no avisa ni bloquea si está lejos de 1:1; la tolerancia del 1 % solo cubre lo que se mueva
  el pool *entre* cotizar y enviar. Ejemplo real: el 3-oct, durante cerca de un minuto (16:43–16:44 UTC),
  el pool quedó con ≈ 358 USDC-Blend frente a ≈ 11 655 USDC-anchor; en ese momento 5 USDC del anchor
  habrían cotizado ≈ 0,15. Si el pool se vacía, la card dice "No hay liquidez…" y el depósito sigue
  siendo válido como evidencia de D3.
- **La cotización no caduca ni se refresca sola.** Si pasa un rato antes del tap, se envía con el
  `dest_min` de la cotización vieja; si el pool se movió más del 1 % en contra, falla con "La liquidez
  cambió: vuelve a cotizar." y "Reintentar" cotiza de nuevo. Los fondos nunca quedan desprotegidos.
- **Hace falta XLM**: la fee de la operación y, si la cuenta no tenía trustline al USDC de Blend,
  0,5 XLM de reserva para crearla.
- **Solo wallets semilla (`G…`)**: una wallet passkey no pasa de "Disponible pronto para passkey
  (SEP-45)" y nunca ve la card (captura `09`).
- **El camino de red no tiene test unitario** (`quoteStrictSend`, `pathPaymentStrictSend` y el
  ViewModel hablan con Horizon); la aritmética sí (`SwapMath`, 19 tests) y el camino de red quedó
  ejercitado dos veces en dispositivo.

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

## Hallazgos de la prueba en dispositivo (2026-10-03)

| Qué | Resultado | Consecuencia |
|---|---|---|
| SEP-1 + `/info` desde el teléfono | La pantalla Depositar carga el `stellar.toml` y los límites en ≈ 7 s (TLS correcto con Conscrypt) | Sin cambios |
| SEP-10 desde la app | Logcat: `Trustline USDC activado para GDLGYDO4…` (21:49:07) y `AnchorClient.authenticate: sesión SEP-10 obtenida (exp=1791168547)` (21:49:08, hora local) | Es la evidencia de "SEP-10 auth working" de la semana 3 del SOW |
| Tiempo hasta la web del anchor | ≈ 10–12 s desde el tap (trustline nueva + challenge + `deposit/interactive`) | Entra holgado en el video de 60 s |
| Web interactiva del anchor | Formulario "Deposit": AMOUNT, FIRST NAME, LAST NAME, EMAIL, Submit. **No precarga el monto** que envía la app (hay que teclearlo otra vez) | Se documenta; en la toma se escriben datos ficticios (`Vecino Demo`, `test@example.com`) |
| Comisión del anchor | Depósito de 5 → **recibe 4,5 USDC** (`FEE AMOUNT 0.5 USD`), aunque `/info` dice `fee.enabled = false` | La pantalla de éxito muestra el `amount_out` real ("4.5 USDC · anchor de prueba") |
| Velocidad del anchor | `completed` ≈ 5 s después del Submit: al volver a la app ya está "¡Depósito recibido!" | Los estados intermedios del polling no llegan a verse con este anchor |
| Forma del pago | `invoke_host_function` (transfer del SAC) desde `GABCKCYP…YCZP`, no `payment` clásico | Ver "De dónde sale el tx hash" |
| Conversión | 4,5 → 4,5429362 y 4,5 → 4,5229258 USDC del fondo; `dest_min` respetado; ≈ 10 s por conversión | El saldo de Inicio sube y el banner de alta desaparece |
| Wallet passkey | "Disponible pronto para passkey (SEP-45)", sin botón (captura `09`) | Como estaba previsto |

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
| 7 | Al llegar `completed` | Pantalla "¡Depósito recibido!" · "4.5 USDC · anchor de prueba" (5 menos la comisión del anchor) · chip verde "Ver en Stellar Expert · abc123…xyz789" | `06_deposito_recibido_hash_y_cotizacion.png` |
| 8 | Tap el chip | Stellar Expert abre la tx: pago de 5 USDC (issuer `GBBD47IF…`) a la G… del dispositivo. **Anotar el hash completo en esta página y en `docs/evidencia_sow/README.md`** | `08_stellar_expert_tx.png` |
| 9 | "Volver a la wallet" | En Inicio, línea "● USDC · anchor de prueba: 5 USDC" bajo el botón; el saldo USDC (Blend) del BalanceCard **no cambió** | `09_inicio_saldo_anchor.png` |
| 10 | Contraste en Horizon (PC) | `https://horizon-testnet.stellar.org/accounts/<G…>/payments?order=desc&limit=5` → `transaction_hash` igual al del chip, `asset_issuer = GBBD47IF…LFLA5` | `10_horizon_payments.png` |
| 11 | **Convertir (extra).** En Inicio, tap "Depositar · anchor de prueba" otra vez | Card negra con **5 USDC** y, debajo, card blanca de borde púrpura "Convertir a USDC del fondo": "Cotizando…" y luego "Recibirás ≈ N USDC · pool de liquidez de testnet, fee 0,3 %, tolerancia 1 %" + botón púrpura "Convertir…" con el saldo completo. **Anotar N**: es la cotización del momento (para 5 USDC fue 4,44 el 27-sep y 5,05 el 3-oct) | `12_convertir_cotizacion.png` |
| 12 | Tap "Convertir…" | Spinner "Habilitando el USDC del fondo en tu cuenta…" (solo si la wallet no tenía trustline al USDC de Blend) → "Enviando la conversión…". Al terminar, la card negra queda en **0 USDC** y la card de conversión sigue visible con "✓ Convertido: recibiste N USDC del fondo" y el chip del hash. En logcat: `pathPaymentStrictSend: USDC→USDC OK para G…, hash=…` — **anotar el hash** en "Tx hash de la conversión" | `13_convertir_enviando.png` |
| 13 | Atrás → Inicio. Contraste en Horizon (PC) | El saldo USDC (Blend) del BalanceCard **subió ≈ N** y la línea "● USDC · anchor de prueba" ya no aparece. En `…/accounts/<G…>/operations?order=desc&limit=5`: `path_payment_strict_send` con `from = to = <G…>` y el mismo hash. Opcional: "Escanear y pagar" a un comercio con ese saldo | `14_inicio_saldo_fondo_tras_convertir.png` |
| 14 | Segundo depósito (p. ej. 1 USDC) y conversión desde la pantalla de éxito | Los pasos de trustline y SEP-10 pasan en un instante (trustline ya existe; sesión cacheada 24 h, sin firma nueva) y se abre la web del anchor directamente. En "¡Depósito recibido!", bajo el chip del hash (desplazar), aparece la card "Convertir a USDC del fondo"; al convertir desde ahí quedan fijos "✓ Convertidos…" y el chip verde "Ver en Stellar Expert · hash" | `15_convertido_hash.png` |
| 15 | Negativo: wallet **passkey** → Depositar | Card "Disponible pronto para passkey (SEP-45)", sin botón ni card de conversión; Inicio no muestra la línea del USDC del anchor | `11_passkey_no_soportado.png` |
| 16 | Negativo: durante el polling, modo avión 20 s y volver | El polling no se cae (en logcat `pollDeposit(...): error transitorio`), el contador sigue y el estado avanza al volver la red | — |
| 17 | Negativo: en la web del anchor, ir a Inicio y matar la app desde recientes; reabrir → Depositar | Restaura el depósito en curso (id en `SavedStateHandle`) y retoma el polling | — |
| 18 | "Cancelar" a mitad de flujo | Vuelve al formulario con el monto; el `id` guardado se borra | — |

Los pasos 1–10 y 15–18 son los del depósito (D3 del SOW). Los pasos 11–13 y la segunda mitad del 14
son el extra de la conversión. Todo vale con el APK de `main` desde el 3-oct.

## Resultado de la prueba (2026-10-03, Motorola G04 / Android 14, operado por adb)

APK debug compilado desde la rama `feat/wp3-sep24` (con los arreglos de la revisión de la conversión).
Se hicieron dos pasadas: un **ensayo** con la wallet semilla del modo demo (`GDLGYDO4…QIZM7`) y la **toma
grabada** con una wallet semilla nueva creada en la app (`GABZUFA6…GTISJ3`), fondeada con friendbot y con
la trustline al USDC del fondo, es decir, en el "Paso 3 · Consigue USDC" del alta. La sesión passkey que
tenía el teléfono se respaldó antes y se restauró al terminar.

| Paso del checklist | Resultado | Evidencia |
|---|---|---|
| 1 · Inicio con el banner "Paso 3 · Consigue USDC" y 0 USDC | ✅ | [`capturas/01_inicio_paso3_consigue_usdc.png`](capturas/01_inicio_paso3_consigue_usdc.png) |
| 2 · Depositar USDC listo (0 USDC del anchor, monto 5, mín 1 · máx 10) | ✅ | [`capturas/02_depositar_listo.png`](capturas/02_depositar_listo.png) |
| 4 · Trustline → "Autenticando con el anchor (SEP-10)…" → SEP-24 | ✅ | [`capturas/03_autenticando_sep10.png`](capturas/03_autenticando_sep10.png) + líneas de logcat de la tabla de hallazgos |
| 5 · Custom Tab con la web del anchor, formulario enviado | ✅ | [`capturas/04_web_del_anchor_formulario.png`](capturas/04_web_del_anchor_formulario.png), [`capturas/05_anchor_completed_con_stellar_tx.png`](capturas/05_anchor_completed_con_stellar_tx.png) |
| 6 · Estados intermedios del polling | No se vieron: el anchor pasa a `completed` en ≈ 5 s | — |
| 7 · "¡Depósito recibido!" con el hash | ✅ 4.5 USDC · chip `ae4d3e…df7562` | [`capturas/06_deposito_recibido_hash_y_cotizacion.png`](capturas/06_deposito_recibido_hash_y_cotizacion.png) |
| 8 · El hash abre en Stellar Expert | ✅ (enlace verificado en Horizon) | https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562 |
| 10 · Contraste en Horizon | ✅ `transfer` de 4,5 USDC (`GBBD47IF…`) a la wallet, ledger 5011978 | `https://horizon-testnet.stellar.org/transactions/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562/operations` |
| 11–12 · Convertir desde la pantalla de éxito | ✅ "✓ Convertido: recibiste 4.523 USDC del fondo" + chip `23e926…3d3658` | [`capturas/07_convertido_al_usdc_del_fondo.png`](capturas/07_convertido_al_usdc_del_fondo.png) |
| 13 · Inicio tras convertir | ✅ Saldo 4.523 USDC, sin línea del anchor, banner de alta desaparecido | [`capturas/08_inicio_saldo_del_fondo.png`](capturas/08_inicio_saldo_del_fondo.png) |
| 15 · Wallet passkey | ✅ "Disponible pronto para passkey (SEP-45)" | [`capturas/09_passkey_disponible_pronto_sep45.png`](capturas/09_passkey_disponible_pronto_sep45.png) |
| 3, 14, 16–18 · Validador del monto, segundo depósito en la misma wallet y casos negativos de red/proceso | **No ejecutados** en esta sesión | Cubiertos por tests JVM solo en parte (validación y polling); quedan para una pasada manual |

Videos (grabados con `adb shell screenrecord`, sin cortes en el paso interactivo):
[`video/d3_deposito_sep24_60s.mp4`](video/d3_deposito_sep24_60s.mp4) (61 s: de Inicio a "¡Depósito
recibido!" con el hash) y [`video/d3_deposito_y_conversion_completo.mp4`](video/d3_deposito_y_conversion_completo.mp4)
(92 s: además la conversión y el saldo final en Inicio).

## Tx hash del depósito

| Fecha (UTC) | Cuenta destino (G…) | Monto | tx hash | Stellar Expert |
|---|---|---|---|---|
| 2026-10-04 03:11:17 (3-oct 22:11 hora de Bogotá) · **la del video** | `GABZUFA64FJOIO5MFOZGQ4CBIOPHN47U7CXTSM4NXEPCIKUJ2BGTISJ3` | 5 depositados → 4,5 USDC recibidos | `ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562` | https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562 |
| 2026-10-04 02:49:57 (ensayo) | `GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7` | 5 → 4,5 USDC | `e798a56fb405afeda71697482d918af61ccf8bfb2148ea71e6d3a49d6900093b` | https://stellar.expert/explorer/testnet/tx/e798a56fb405afeda71697482d918af61ccf8bfb2148ea71e6d3a49d6900093b |

## Tx hash de la conversión (extra, no lo pide el SOW)

| Fecha (UTC) | Cuenta (G…) | Enviado (USDC del anchor) | Recibido (USDC del fondo) | tx hash | Stellar Expert |
|---|---|---|---|---|---|
| 2026-10-04 03:11:42 · **la del video** | `GABZUFA6…GTISJ3` | 4,5 | 4,5229258 (`dest_min` 4,4776965) | `23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658` | https://stellar.expert/explorer/testnet/tx/23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658 |
| 2026-10-04 02:50:52 (ensayo) | `GDLGYDO4…QIZM7` | 4,5 | 4,5429362 (`dest_min` 4,4975068) | `f09eae0771ad44c6fb1a6af6ff204a015ee2f3cc1d8cd75164a6e746d824259e` | https://stellar.expert/explorer/testnet/tx/f09eae0771ad44c6fb1a6af6ff204a015ee2f3cc1d8cd75164a6e746d824259e |

## Notas honestas

- **El USDC depositado no sirve para pagar dentro de RAÍZ tal cual; sí después de convertirlo.** El
  Pool y los comercios trabajan con el USDC de Blend; el del anchor es otro activo (mismo código, otro
  emisor) y se sigue mostrando por separado a propósito, para no mentir sobre fungibilidad. Lo que el
  27-sep era "la siguiente fase" ya existe (en `main` desde el 3-oct): "Convertir a USDC del fondo"
  cambia ese saldo por USDC de Blend con un path payment firmado por el usuario, y con eso sí se paga
  en comercios (probado en el teléfono el 3-oct). Las salvedades son reales: no es 1:1 (precio de un
  pool de testnet ajeno a RAÍZ) y en mainnet no haría falta porque
  el USDC de Circle es uno solo. Detalle y límites en "Convertir a USDC del fondo".
- **El ensayo usó la wallet del modo demo y la toma una wallet nueva.** Las dos son wallets semilla
  reales de testnet firmando en el teléfono; ninguna es la wallet passkey del dueño del dispositivo.
- **El video se grabó operando el teléfono por adb** (toques y texto inyectados por script), no con el
  dedo. La app, el anchor y las transacciones son reales; los datos del formulario del anchor son ficticios.
- **Anchor de prueba, KYC simulado.** La web interactiva del testanchor es la "SEP-24 Reference UI"
  del SDF: pide datos de prueba y no mueve dinero real. Lo que sí es real es el protocolo (SEP-1/10/24
  tal cual lo usaría un anchor de producción) y el pago on-chain en testnet.
- **Passkey fuera** hasta que la app implemente SEP-45 (el anchor de prueba ya lo ofrece, comprobado el 4-oct).
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
  Horizon. Por eso no hay TTL que extender ni redeploy. La conversión tampoco: es una operación clásica
  de Stellar enviada a Horizon, sin Soroban y sin relayer.

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

Añadido por la conversión (en `main` desde el 3-oct):

| Archivo | Qué |
|---|---|
| `data/stellar/SwapMath.kt` (nuevo) | Aritmética pura sin Android ni SDK: `stroopsToAmount` / `amountToStroops` (decimal de 7 cifras de Horizon ↔ `Long`, truncando), `applySlippageBps`, `Quote`, `SLIPPAGE_BPS = 100` |
| `data/stellar/HorizonStream.kt` | `StrictSendQuote`, `quoteStrictSend` (`strictSendPaths()` del SDK; elige el camino con mayor `destinationAmount`; sin caminos → `NOT_FOUND`), `pathPaymentStrictSend` (firma del usuario, destino su propia cuenta, mapeo de `op_underfunded` / `op_under_dest_min` / `op_too_few_offers` / `op_no_trust` a mensajes en español) y el mapeo de la `Asset` de respuesta de Horizon a la `Asset` de operación del SDK |
| `ui/deposit/DepositViewModel.kt` | `SwapState` (`Idle`, `Quoting`, `Quoted`, `Submitting`, `Done`, `Failed`), `quoteSwap` (automática al entrar con saldo y al completarse un depósito), `convertAnchorUsdc` (trustline al USDC de Blend si falta → path payment), `retryQuote`; inyecta `DeploymentsLoader` para el issuer del USDC del fondo |
| `ui/deposit/DepositScreen.kt` | `ConvertCard` en la pantalla de éxito y en el formulario cuando hay saldo del anchor; la pantalla de éxito pasa a ser desplazable |
| `test/.../data/stellar/SwapMathTest.kt` (nuevo) | 19 tests JVM de `SwapMath` (incluye la cotización real del 27-sep: 44 422 608 stroops → `dest_min` 43 978 381) |
| `data/stellar/HorizonStream.kt` (arreglos 3-oct) | `getAssetBalanceOrNull` (distingue "no se pudo leer" de "saldo 0"), `strictSendReceivedStroops` (monto real recibido), envío único + consulta por hash si se pierde la respuesta, `result_codes` de Horizon en el error |
| `ui/wallet/WalletViewModel.kt` (arreglo 3-oct) | `refresh()` re-evalúa el paso pendiente del alta: tras convertir, el banner "Paso 3" desaparece sin reiniciar la app |
| `data/model/RaizConstants.kt` + `test/.../data/model/FormatUsdcTest.kt` (nuevo) | `formatUsdc()` con `Locale.US`: en dispositivos con coma decimal "5,000" quedaba como "5, USDC" |

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

## Cambios tras la revisión de la conversión (2026-10-03)

Tres revisores adversariales (dinero, flujo, conformidad) leyeron el código de la conversión; cada
hallazgo se contrastó con el código y con la prueba en el teléfono antes de corregirlo.

| Hallazgo | Arreglo |
|---|---|
| Los cuatro textos con monto decían "USDC USDC" (visto también en el ensayo en dispositivo) | `formatUsdc()` ya trae la unidad; se quitó el sufijo repetido |
| El envío iba dentro de un reintento por errores de red: un path payment no es idempotente y una conversión exitosa podía reportarse como fallida (o duplicarse) | La tx se construye y firma una vez, se envía una vez y, sin respuesta, se consulta por hash |
| "Reintentar" cotizaba con el saldo viejo → bucle de `op_underfunded` si la tx sí había entrado | "Reintentar" relee el saldo antes de cotizar |
| En el formulario (READY) la card desaparecía al terminar: no se veía el resultado ni el hash | La card se mantiene mientras la conversión no esté en reposo |
| "Cancelar"/"Volver" dejaban la card sin cotización ni botón | `reset()` relee el saldo y vuelve a cotizar |
| La cotización podía pisar una conversión en vuelo, y depósito y conversión compartían la etiqueta de paso | Guardas cruzadas (`Submitting` bloquea cotizar y depositar) y etiqueta propia `swapStepLabel` |
| "✓ Convertidos N" mostraba lo cotizado, no lo recibido | Se lee de Horizon el `amount` real de la operación ("≈" si no se pudo leer) |
| Un fallo de red al leer el saldo escribía 0 y ocultaba la card | La lectura distingue error de 0; conserva el saldo previo y ofrece "Reintentar" |
| Errores no mapeados salían como "Bad request (code: 400)" | El mensaje y el log incluyen el fragmento `result_codes` de Horizon (nunca el XDR) |
| El banner de alta de Inicio no avanzaba tras convertir | `WalletViewModel.refresh()` re-evalúa el paso pendiente |
| `formatUsdc()` dependía del locale ("5, USDC" con coma decimal) — preexistente | `Locale.US` + test de regresión |
