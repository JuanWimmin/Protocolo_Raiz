/# Plan de ejecución + instrucciones para Claude Code — HackMeridian 2026 (Lisboa, 25–26 oct)

> Cómo usar: igual que `PLAN_CLAUDE_CODE_SOW.md`. Cada bloque trae objetivo, criterio de aceptación,
> micro-detalles y un **prompt listo** para pegar en Claude Code desde la terminal. Se trabaja en orden.
> Spec técnica de lo que se construye: `docs/pago_diferido_v0.md` (congelada). Reloj: hora de Lisboa.
> Deadline de submission: **lunes 26-oct 12:00**. No hay entregas tardías.

---

## 0. Reglas de la hack (añadidas a las M1–M8 del plan del SOW, que siguen vigentes)

**H1 — Una rama, un tag.** Todo el trabajo va en `feat/pago-diferido`. Antes del primer commit de código
nuevo: `git tag hackmeridian-start` sobre el último commit previo al evento. El README EN ya enlaza el
diff `hackmeridian-start...main`: es la prueba de "lo construido durante el evento".

**H2 — Cero contratos nuevos, cero redeploy.** `pay_merchant` se usa tal cual. Si un prompt propone tocar
`contracts/`, es que se desvió: cortar.

**H3 — Primero el camino que no depende del SDK.** La pasarela web (JS) es el plan B de todo lo que haga la
app en el lado de envío. Si el spike del SDK falla a las 14:00, la app solo firma y la web envía. La demo
no cambia.

**H4 — La demo es: emulador (pagador, datos apagados) + iPhone (pasarela web en Safari).** No hay teléfono
Android físico. Nada de passkey en la demo (no funciona en emulador): wallet semilla.

**H5 — Honestidad en cada texto.** "Pago diferido", nunca "pago offline" a secas. Siempre: no hay finalidad
offline; el comercio tiene una promesa firmada hasta que llega el hash.

**H6 — Timebox duro.** Cada bloque tiene hora de corte. Si se pasa, se recorta alcance, no se corre la hora.

**H7 — Gates por commit:** `./gradlew :app:testDebugUnitTest` verde si tocó app; `./gradlew assembleDebug`
compila; `git diff` sin `S[A-Z0-9]{55}`; videos pasados por `scripts/mask_video_autofill.py` antes de
versionar.

---

## 1. Calendario (Lisboa)

| Hora | Bloque | Salida visible |
|---|---|---|
| 12:00–13:00 | **B0** Entorno: emulador, Claude Code, rama/tag, build limpio | APK debug corriendo en el AVD con datos apagables |
| 13:00–14:00 | **B1** Spike SDK: auth entry manual + envío por otra cuenta | Test JVM verde o decisión de plan B |
| 14:00–17:30 | **B2** Pagador en la app (firma sin red + QR) | QR con sobre válido desde el emulador |
| 15:00–18:30 | **B3** Pasarela web (`landing/pasarela.html`) — en paralelo con B2, sesión aparte | Hash en Stellar Expert desde Safari |
| 18:30–20:30 | **B4** Cobrador en la app (si B2+B3 cerraron) | Flujo completo en app; si no, se salta |
| 20:30–22:00 | **B5** Prueba end-to-end, capturas, video 60–90 s | Evidencia en `docs/evidencia_hackmeridian/` |
| 22:00–23:30 | **B6** README, arquitectura, submission answers; EP.01 si no se grabó | Textos listos |
| 23:30 → | Dormir. Opcional **P0 relayer** solo si todo lo anterior cerró | — |
| Lun 09:00–11:30 | **B7** Deck final (Diana), enlaces en incógnito, submission | Enviado antes de 11:45 |
| Lun 13:00 | **P0 relayer** si no se hizo | 0.3.1 desplegado con allowlist |

---

## B0 — Entorno (12:00–13:00)

### B0.1 Emulador (PowerShell, fuera de Claude Code)

Diagnóstico del 25-oct: hay Android Studio y `emulator.exe`, **no había imagen de sistema ni AVD**.
**Lo que pasó al correr `sdkmanager` (13:47):** el `.bat` ya no es sdkmanager, descarga el nuevo
"Android CLI", que nombra paquetes con `/` (p. ej. `platforms/android-34`); el id con `;` se partió en
cuatro paquetes inexistentes. Además `platform-tools` no se extrajo porque `adb.exe` estaba en uso.
WHPX sí está instalado y usable: la aceleración está resuelta.

**Camino recomendado (GUI, 1 pantalla):** Android Studio → Device Manager → "+" → Pixel 7 → API 35
(Google APIs, x86_64) → Finish. Baja la imagen y crea el AVD. **Hecho el 25-oct ~15:00.**

Camino CLI equivalente (si hiciera falta recrearlo):
```powershell
adb kill-server
$cli = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\android.bat"
& $cli sdk list --all | Select-String "system-images/android-35"      # id exacto con barras
& $cli sdk install "system-images/android-35/google_apis/x86_64" platform-tools
& $cli emulator create --help                                           # sustituye a avdmanager
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd <nombre> -camera-back virtualscene
```

- Apagar y encender la red del emulador (modo avión real para la demo):
  `adb shell svc wifi disable; adb shell svc data disable` / `… enable`.
- Instalar la app: `cd android; .\gradlew assembleDebug; adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- La wallet semilla del emulador se fondea con el faucet del banner (relayer) o con Blend; hacerlo con red
  **antes** de apagarla. El pagador necesita USDC del fondo (SAC `CAQCFVLO…`), no USDC del anchor.
- Mapbox: `android/local.properties` con `mapbox.access.token=pk.…` (ya debería estar).

### B0.2 Claude Code

- Abrir en la raíz del repo: `claude`. El `CLAUDE.md` orienta al agente; los subagentes
  `kmp-stellar-integration` y `compose-ui-builder` y las skills `stellar-dev` (`smart-contracts`,
  `dapp`, `data`, `standards`) ya están instalados (ver CLAUDE.md "Skills de Stellar").
- Primer mensaje de cada sesión: pedirle que lea `CLAUDE.md`, `docs/pago_diferido_v0.md` y este plan, y
  que trabaje en `feat/pago-diferido`. Plan mode (shift+tab) para B1 y B2; directo para B3, B5, B6.
- Rama y tag (una vez):
  ```bash
  git checkout -b feat/pago-diferido
  git add docs/pago_diferido_v0.md docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md README.md
  git commit -m "docs(hack): spec del pago diferido por enjambre y plan de HackMeridian"
  git tag hackmeridian-start HEAD~1    # el commit anterior a cualquier trabajo del evento
  git push -u origin feat/pago-diferido --tags
  ```
  (Los PDF/DOCX sueltos del SOW 2 y `closeout_…` se commitean aparte, `docs(sow): …`, o quedan para después.)

**Aceptación B0:** emulador con la app abierta, wallet semilla con ≥ 10 USDC del fondo, `adb shell svc data
disable` deja la app sin red; rama y tag pusheados.

---

## B1 — Spike SDK (13:00–14:00, timebox estricto)

**Objetivo:** demostrar en un test JVM que con `kmp-stellar-sdk` 1.6.0 se puede (a) construir a mano la
`SorobanAuthorizationEntry` de `pay_merchant` con el árbol de la spec §2.1, (b) firmarla con el keypair
del pagador sin simular, y (c) que otra cuenta la adjunte a una invocación y la envíe a testnet.

**Micro-detalles:**
- Preimage: `HashIdPreimage` de tipo `ENVELOPE_TYPE_SOROBAN_AUTHORIZATION` con `networkId` (SHA-256 de la
  passphrase), `nonce`, `signatureExpirationLedger`, `invocation`. La firma ed25519 va en
  `SorobanAddressCredentials.signature` como `ScVal` vector de mapas `{public_key, signature}` (formato
  que espera el host para cuentas G). Mirar cómo lo hace el SDK en `signAuthEntries` y replicarlo.
- Las sub-invocaciones son `transfer` del SAC con `(from, to, amount)` en ese orden; `amount` es `i128`.
- `fee_bps` = 50 (`deployments.json` → `protocol_fee_bps`).
- Probar PRIMERO con expiración = ledger actual + 100 (sin estimador), y medir el tope: subir la
  expiración hasta que la red rechace; anotar el máximo en la spec §3.1.
- Lado envío: `client.buildInvoke(signer = null)` ya se usa en `SorobanClient.kt:734`; averiguar si el
  `AssembledTransaction` admite reemplazar sus auth entries por las firmadas externamente antes de
  `signAndSubmit(keypair_pasarela)`. Si no hay API pública, construir la operación `InvokeHostFunction`
  a mano con `auth = listOf(entry)` y simular con `simulateTransaction` (modo enforcing porque la auth ya
  viene firmada).

**Decisión a las 14:00:** si (c) no sale, la app solo hace (a)+(b) y la pasarela web hace el envío (B3).
No se insiste más allá de las 14:00.

**Método (decidido 25-oct 15:00): fixture primero, Kotlin después.** Es el patrón que ya funcionó en
`raiz-confidential-stack` (`wallet/tools/gen-sign-fixture.mjs` + `StellarAccountTest`): JS genera la
referencia con `@stellar/stellar-sdk`, el test JVM compara byte a byte. Si no coincide, el fixture dice en
qué byte. Se importa el **método**, cero código del repo viejo.

**Prompt listo:**
```text
Lee CLAUDE.md, docs/pago_diferido_v0.md (secciones 2 y 3) y docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md B1. Estamos en la rama feat/pago-diferido. Usa el subagente kmp-stellar-integration. Antes de tocar Kotlin, crea scripts/gen-auth-fixture.mjs (Node, @stellar/stellar-sdk): construye la invocación de Pool.pay_merchant(tourist, merchant, amount, tip_bps) con el árbol de la spec §2.1 (fee_bps 50, ids de deployments.json), firma la SorobanAuthorizationEntry con authorizeEntry usando un keypair de prueba fijo, nonce fijo y expiración fija, y vuelca a android/app/src/test/resources/deferred_auth_fixture.json: preimage XDR, hash, firma, entrada firmada XDR y todos los parámetros. Luego, en 1 hora como máximo: un test JVM android/app/src/test/.../stellar/DeferredAuthSpikeTest.kt que (1) construya a mano la misma SorobanAuthorizationEntry con kmp-stellar-sdk 1.6.0, (2) firme el preimage ENVELOPE_TYPE_SOROBAN_AUTHORIZATION y compare byte a byte contra el fixture (preimage, hash, firma, XDR), y (3) serialice/parsee la entrada. Solo cuando coincida, un segundo test marcado como integración (desactivado por defecto con una propiedad del sistema) envía a testnet una invocación de pay_merchant construida por OTRA cuenta (fondeada por friendbot) adjuntando esa entrada, con expiración = ledger actual + 100, usando una wallet pagadora semilla con USDC del fondo que yo te doy por variable de entorno. No toques contracts/. Antes de escribir código, dame el plan con las clases del SDK que vas a usar y si el AssembledTransaction permite inyectar auth entries externas; si no lo permite, dime cómo construirías la operación a mano. Al final, reporta: ¿funcionó (c)? ¿cuál es la expiración máxima que acepta la red? Gate: ./gradlew :app:testDebugUnitTest verde.
```

**Resultado (25-oct, cerrado ≈ 17:00): (a), (b) y (c) funcionan con `kmp-stellar-sdk` 1.6.0.** Fixture
`scripts/gen-auth-fixture.mjs` → `android/app/src/test/resources/deferred_auth_fixture.json` (credenciales
legacy; el árbol coincide con la simulación del host). Kotlin: `data/deferred/AuthTreeBuilder.kt` +
`DeferredAuthSigner.kt`; `DeferredAuthSpikeTest` (16 tests byte a byte, verde) y `DeferredAuthTestnetIT`
(apagado por defecto: `RAIZ_DEFERRED_IT=1` + `RAIZ_DEFERRED_PAYER_MNEMONIC` o `_SECRET`, `--rerun`). Envío desde
otra cuenta: tx `ebee152c…6d47` (fuente = pasarela friendbot, auth = wallet del emulador); replay →
`Error(Auth, ExistingValue)`; expirada → `Error(Auth, InvalidInput)`. **Expiración máxima que acepta la red:
`E − L = max_entry_ttl − 1 = 3 110 399` ledgers** (≈ 180 días). Cierre de ledger medido: 5,000 s. No hizo falta
`AssembledTransaction`: la pasarela construye `InvokeHostFunctionOperation(auth = [entrada])`, simula
`ENFORCE` y `prepareTransaction` conserva la auth adjunta.

---

## B2 — Pagador: firma sin red + QR (14:00–17:30)

**Objetivo:** en `PayScreen`, un modo "Sin señal" que firma el sobre de la spec §5 y lo muestra como QR.

**Alcance:**
1. `data/deferred/DeferredEnvelope.kt`: modelo del sobre (campos §5), serialización JSON compacta →
   base64url, y validación JSON ↔ XDR.
2. `data/deferred/AuthTreeBuilder.kt`: árbol de `pay_merchant` (del spike), puro y testeado.
3. `data/deferred/LedgerEstimator.kt`: guarda `(L0, t0)` con `SystemClock.elapsedRealtime()` cada vez que
   hay red (p. ej. en `HorizonStream`/`SorobanClient` al leer el último ledger); `estimate(now)` y
   `expiration(windowLedgers)` según §3.1 (τ = 5 s, τ_min = 4,5 s, W por defecto 12 h).
4. `data/deferred/DeferredStore.kt`: DataStore con los sobres emitidos y su estado (FIRMADO, ENTREGADO,
   CONFIRMADO(hash), EXPIRADO, RECHAZADO). *Implementado (25-oct) con SharedPreferences +
   kotlinx.serialization (patrón `ExecutionHashStore`): el proyecto no tiene Room ni DataStore y no
   compensaba añadirlos; el mismo archivo `raiz_deferred` guarda el ancla del estimador.*
5. UI: en `PayScreen`, cuando no hay red (o con un switch "Firmar sin señal" en debug), el botón pasa a
   "Firmar sin señal"; pantalla de resultado con el QR (`QrUtils.generate`, tamaño 1024 px, corrección M),
   el detalle (comercio, monto, 2 %), "válido hasta ~hh:mm (estimado)" y el texto honesto: "Es una promesa
   firmada. El comercio la cobra cuando cualquier dispositivo del territorio tenga señal." Botón "Copiar
   sobre" (para pegarlo en la pasarela web si la cámara del iPhone no lee la pantalla del emulador).
6. Historial: lista de sobres con estado; al recuperar red, consulta por hash si la pasarela lo devolvió
   (v0: el usuario pega el hash o escanea el QR de confirmación de la pasarela; automatizar es v1).

**Micro-detalles:** nunca usar la hora de pared; el nonce sale de `SecureRandom`; el monto va en stroops
(i128) y la UI muestra USDC con `toUsdc()`; la paleta es la del CLAUDE.md; `strings` nuevos en ES con la
palabra "territorio" donde aplique, pero sin tocar las frases existentes (no es el momento del refactor).

**Aceptación:** sin señal (el emulador tiene DOS radios: `adb shell cmd connectivity airplane-mode enable`,
o `svc wifi disable` + `svc data disable`; `disable`/`enable` para volver), el emulador firma, muestra QR,
y la pasarela web (B3) liquida ese QR en testnet.

**Prompt listo:**
```text
Lee CLAUDE.md, docs/pago_diferido_v0.md completo y docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md B2. Rama feat/pago-diferido. Usa kmp-stellar-integration para data/deferred y compose-ui-builder para la UI. Implementa el lado PAGADOR del pago diferido reutilizando lo que salió del spike (DeferredAuthSpikeTest): DeferredEnvelope (sobre §5, JSON→base64url, validación JSON↔XDR), AuthTreeBuilder (árbol §2.1), LedgerEstimator (§3.1, reloj monotónico, W para 12 h con τ_min 4,5 s) y DeferredStore (DataStore, estados §6). En PayScreen/PayViewModel añade el modo "Firmar sin señal" (automático sin red; switch en debug), la pantalla de resultado con QR 1024 px corrección M, detalle, "válido hasta ~hh:mm (estimado)", el texto honesto de la spec y el botón "Copiar sobre". Tests JVM: los 9 de la spec §8 que apliquen al pagador (árbol con tip>0/fee>0, tip=0, fee=0; preimage estable; firma verificable; estimador con τ' 4,5/5/6 s; JSON↔XDR discrepante → rechazo). No toques contracts/ ni el flujo de pago en línea existente. Commits: feat(deferred): … separados por capa. Gate: :app:testDebugUnitTest verde + assembleDebug. Al terminar, instala en el emulador y dime los pasos exactos para probar con la red apagada.
```

---

## B3 — Pasarela web pública (15:00–18:30, sesión de Claude Code aparte)

**Objetivo:** `landing/pasarela.html`: cualquier teléfono con señal (el iPhone en Safari) liquida un sobre.

**Contrato del sobre (decidido 25-oct, noche; manda sobre el prompt de abajo):** el QR y el texto pegado son
`raiz:sobre:1:` + base64url del XDR de la `SorobanAuthorizationEntry` firmada (sin JSON). La pasarela quita el
prefijo, decodifica el XDR, deriva comercio/monto/aporte de `rootInvocation` (raíz `pay_merchant` del Pool de
`DEPLOYMENTS`, 4 argumentos) y reconstruye el árbol con `fee_bps` 50 para comprobar que es idéntico; la firma
se verifica sobre el preimage tipo 9. Sobre de prueba: campo `sobre_qr` de
`android/app/src/test/resources/deferred_auth_fixture.json` (1 229 caracteres). "Cobrado" solo con el hash
cuya auth sea ese sobre; `Error(Auth, ExistingValue)` = "nonce usado", nunca "cobrado".

**Alcance:**
1. Página estática con `@stellar/stellar-sdk` desde CDN (misma versión mayor que la landing) y un lector
   QR por cámara (`jsQR` o `html5-qrcode` desde CDN) + área para pegar el sobre.
2. Verificación local: parsea el sobre, decodifica el XDR, comprueba que JSON ↔ XDR coinciden, verifica la
   firma ed25519 del pagador contra el preimage (red testnet), muestra el detalle y si `exp` ya pasó
   (consulta el ledger actual al RPC).
3. Cuenta de pasarela: generada en el navegador y guardada en `localStorage` (testnet), fondeada con
   friendbot al primer uso; o pegar una secret de prueba. Mostrar su G… y saldo XLM.
4. Envío: construye la tx con la pasarela como fuente, operación `invokeHostFunction` de `pay_merchant`
   con `auth = [entry]`, `simulateTransaction`, `assembleTransaction`, firma, `sendTransaction`, polling
   de `getTransaction`; muestra hash + enlace a Stellar Expert y un QR con el hash (para que el pagador lo
   escanee y cierre su estado).
5. Errores legibles: nonce usado, expirada, fondos insuficientes, comercio no verificado, fee cambió.
6. Mismo `DEPLOYMENTS` que `index.html` (fuente `deployments.json`); estilo de la landing; ES/EN mínimo
   (textos en un objeto, selector arriba).

**Micro-detalles:** el relayer JS ya hace "fuente distinta + auth ajena": copiar el patrón, no inventarlo.
La página se prueba primero con el sobre del fixture de B1 (`deferred_auth_fixture.json`), antes de que la app
produzca uno; el script `scripts/deferred_envelope_demo.js` reutiliza `gen-auth-fixture.mjs`.
La cámara en Safari exige HTTPS: probar sobre `raizapp.xyz/pasarela.html` (copiar al repo Pages) o con
`npx serve` + túnel; en local HTTP sirve el modo "pegar sobre". Publicar en Pages en cuanto funcione: es la
"public live URL" de la submission.

**Aceptación:** desde Safari en el iPhone, escaneando el QR del emulador, aparece el hash y Stellar Expert
muestra la tx con fuente = pasarela y auth del pagador. Reenviar el mismo sobre falla por nonce.

**Resultado (25-oct, 22:10; hecho en la sesión principal, no hubo sesión aparte):** `landing/pasarela.html`
publicada en https://raizapp.xyz/pasarela.html. Sin build: `@stellar/stellar-sdk@17.0.1`, `html5-qrcode@2.3.8`
y `qrcode-generator@1.4.4` desde jsDelivr; estilo y `DEPLOYMENTS` de la landing; textos ES/EN en un objeto.
Flujo: escanear (cámara en la página) o pegar → **verificar sin red** con las mismas reglas que
`scripts/pasarela_cli.mjs` (prefijo `raiz:sobre:1:`, XDR, credenciales legacy de una cuenta G, raíz
`pay_merchant` del Pool, árbol reconstruido con `fee_bps` 50 idéntico byte a byte, firma ed25519 sobre el
preimage tipo 9) → estado en la red (ledger actual, `nonce` consumido vía `getLedgerEntries`, nombre del
comercio con `get_merchant` simulado) → cuenta pasarela en `localStorage` (friendbot o secret pegada; saldo
por Horizon) → **liquidar**: `invokeHostFunction` con los argumentos firmados y `auth = [sobre]`, simulación
`enforce`, `assembleTransaction`, firma, envío, polling → hash + Stellar Expert + QR del hash; errores
traducidos (nonce usado, expirado, firma/árbol, #4, #5, #10, #13). `?sobre=` o `#sobre=` precargan y
verifican (para pruebas y enlaces). Sobres de prueba: `sobre_qr` del fixture y
`scripts/deferred_envelope_demo.mjs` (sobre real desde `DEFERRED_PAYER_SECRET`, nonce aleatorio, E = L + W).
**Probada** en Chrome headless por CDP (sin extensión, `scratchpad/cdp_pasarela_test.mjs`): el fixture verifica
("firma válida", nonce libre, Cafe Don Aurelio verificado) y un sobre real de `raiz-tourist` (0,5 USDC + 2 %)
se liquidó con una pasarela creada por la página (`GADSC7Z5…T43MO`, friendbot): tx
[`27c61461…9f47`](https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47)
(ledger 5 388 486, fee de recursos 0,0052 XLM); después la página marca el nonce como consumido y no deja
reenviar. Queda la aceptación manual del usuario: Safari en el iPhone escaneando el QR del emulador
(la cámara exige HTTPS: ya está en raizapp.xyz).

**Prompt listo:**
```text
Lee CLAUDE.md, docs/pago_diferido_v0.md (§2, §5, §6) y docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md B3. Rama feat/pago-diferido. Crea landing/pasarela.html: página estática (estilo y DEPLOYMENTS de landing/index.html) que escanea por cámara o recibe pegado un sobre de pago diferido, verifica localmente JSON↔XDR y la firma ed25519 del pagador sobre el preimage ENVELOPE_TYPE_SOROBAN_AUTHORIZATION (testnet), consulta el ledger actual para marcar expiración, y lo liquida: cuenta de pasarela en localStorage fondeada por friendbot (o secret pegada), tx con la pasarela como fuente e invokeHostFunction de Pool.pay_merchant con auth=[entry], simulate → assemble → sign → send → getTransaction, hash + enlace a Stellar Expert + QR del hash, y errores legibles (nonce usado, expirada, fondos, comercio no verificado, fee cambió). Usa @stellar/stellar-sdk y un lector QR desde CDN, sin build. Textos ES/EN en un objeto. Para el patrón "fuente distinta + auth ajena" fíjate en cómo lo hace el relayer (https://github.com/JuanWimmin/raiz-relayer). Entrégame también un sobre de prueba generado con un script node (scripts/deferred_envelope_demo.js) para probar la página antes de que la app lo produzca. No toques contracts/.
```

---

## B4 — Cobrador en la app (18:30–20:30, solo si B2 y B3 cerraron)

**Objetivo:** `CobrosScreen` escanea el sobre, lo verifica offline, lo guarda EN_TRANSITO y lo envía con la
cuenta del comercio cuando hay red.

**Alcance:** escáner (ya hay ZXing en el proyecto), `DeferredVerifier` (firma, nonce no visto, exp > L̂,
destinatario = mi cuenta, monto ≤ tope configurable), lista "Pagos en tránsito" con estado, botón
"Liquidar ahora" que usa el camino del spike (o, si el spike falló, abre la pasarela web con el sobre en el
portapapeles). **Si a las 20:30 no está, se corta y la demo usa la web como cobrador.**

**Resultado (26-oct, madrugada; alcance ampliado por el usuario a cobrador + PORTADOR + liquidación automática):**
commits `48b8ccf` (capa de datos) y `5a8a1e9` (UI) en `feat/pago-diferido`; 315 tests JVM, 0 fallos.
- **Liquidación automática en los dos teléfonos.** `SorobanClient.submitSobre` (extraído del spike: simulación
  ENFORCE antes de firmar, tope de fee, diario pre-envío con `TxJournal`, nonce consultado justo antes de enviar,
  reenvío solo con `TRY_AGAIN_LATER`, confirmación por hash con la hora de la red) y `SobreSettler`, un único
  orquestador para los sobres propios del pagador y los recibidos: candado por sobre y por cuenta fuente,
  reanudación por hash, intentos y backoff, arranque por transición de conectividad (`start` desde
  `MainActivity` tras desbloquear) y respeto del "sin señal" forzado de depuración.
- **Sobres recibidos (`data/deferred/ReceivedSobre*`):** rol `COBRO` si el destinatario es mi cuenta, `PORTADOR` si
  no; verificación sin red con `SobreValidator` + índice idempotente de nonces (mismo sobre = "ya registrado";
  mismo nonce con otra auth = fraude), vigencia con ledger real o estimación con margen 360 + deriva por la edad del
  ancla (un cobro nunca se acepta sin referencia; un portador sí, con aviso), tope solo en cobro (20 USDC por
  defecto, editable). **"Cobrado" solo con hash cuya auth sea el sobre**; nonce consumido sin hash sigue en
  tránsito "buscando el comprobante" y, si aparece el nonce en otro pago o vence sin hash, RECHAZADO. Nada se
  rechaza por causas transitorias (#4/#5/#10/#13, códigos tx de la fuente, red): se aplaza con motivo y la red
  decide; `markConfirmed` pisa RECHAZADO/EXPIRADO. Antes de dar un sobre por expirado se busca su hash en los
  eventos desde el ancla (`EventsPaging`/`SobreLookup`/`sobrePaymentEvents`), porque la entrada del nonce es
  temporal y desaparece al vencer. Tope de fee: propios 100 XLM, recibidos 2 XLM automático (nadie drena el XLM de
  un comercio con sobres de un stroop) y 100 XLM con "Liquidar ahora".
- **UI.** Cobros: "Escanear sobre" y "Pegar sobre", veredicto, "Pagos en tránsito" con estado honesto, detalle con
  "Liquidar ahora" / "Copiar sobre y abrir la pasarela" (passkey), tope editable, banner sin señal; la pantalla se
  pinta sin red. Cualquier wallet: el escáner de Inicio reconoce un sobre y abre "Recibir sobre" (portador o cobro),
  con pegado manual; "Mis sobres" gana "Recibidos" y su detalle; la fila de Inicio cuenta propios y recibidos y
  siempre es visible. Textos del pagador: "Se liquida sola en cuanto tu teléfono, el del comercio o el de cualquier
  usuario de RAÍZ que la lleve tenga señal."
- **Método:** dos workflows de agentes (carril A datos: implementador ×2 secuenciales, revisor adversarial, arreglo;
  carril B UI: implementadores ×2 en paralelo, compilación, revisor adversarial, arreglo) sobre un plan revisado
  adversarialmente (`docs/…/ok-claude…` en `~/.claude/plans`); hallazgos aplicados: cobrador sin "Cobrado" por
  nonce, escrituras atómicas, tope de fee, orden pagador-primero, pasarela por fragmento `#sobre=`.
- **Prueba en el emulador:** APK debug instalado; con la wallet semilla `GC4VHSZY…` como PORTADOR se pegó en "Recibir sobre" un sobre real de `raiz-tourist` (0,5 USDC a Cafe Don Aurelio, generado con `scripts/deferred_envelope_demo.mjs`): verificado sin red ("Lo llevas para GA7CDZ…4TSN"), enviado solo por el settler y "Liquidado" en 11 s — tx [`79a4c858…96eee2`](https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2) (ledger 5 391 483, fuente = el portador, fee 0,0023 XLM); el mismo sobre pegado otra vez → "Ya tenías este sobre"; "Recibidos" lo lista como "Liquidado" con el hash. **Hecho el 26-oct (≈ 05:00, emulador, por adb):** camino COBRO con la wallet semilla de Mochilas del Centro (el turista firmó
  en modo avión; el comercio pegó el sobre, lo verificó sin red y su app lo envió sola: tx
  [`6770a2c8…b5fbb4`](https://stellar.expert/explorer/testnet/tx/6770a2c8d417f2c679a0a623eb75bc19764af9ea36d56cca501f3e6f73b5fbb4), fuente = el comercio, ledger 5 393 408), auto-liquidación de un sobre propio
  (el turista recuperó señal y su app lo envió tras esperar 10 s: tx [`028f997e…6484b3`](https://stellar.expert/explorer/testnet/tx/028f997efbcd83f2b1bb4539352f7fedf9b9deacd1799047c89c748cb06484b3), ledger 5 393 459) y la
  pasarela web desde el Chrome del emulador (sin app: tx [`41619717…5ce559`](https://stellar.expert/explorer/testnet/tx/41619717f2b636ba861023fc70923e711c776c7151c73d43f95876816c5ce559), ledger 5 393 540). Clips y borrador del
  video de 89 s en `C:/Blockota/RAIZ_entregas/VideosDemo/2026-10-26/`; capturas en `docs/evidencia_hackmeridian/capturas/`.
- Deuda declarada: un sobre más viejo que la retención del RPC (~7 días) queda sin veredicto terminal ("venció y ya
  no se puede comprobar"); SEP-45/passkey siguen liquidando por la pasarela; sin prueba en dispositivo físico.

**Prompt listo:**
```text
Lee docs/pago_diferido_v0.md (§1, §4, §6) y docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md B4. Rama feat/pago-diferido. Implementa el lado COBRADOR mínimo en ui/cobros: escanear el sobre con el escáner existente, DeferredVerifier (firma ed25519 contra el preimage, nonce no visto en caché local, exp > estimación, destinatario = cuenta del comercio, monto ≤ tope configurable 20 USDC), guardar EN_TRANSITO en DeferredStore, lista "Pagos en tránsito" con estado y motivo, y "Liquidar ahora" que envía con la cuenta del comercio por el camino que funcionó en el spike (si no funcionó, copia el sobre y abre https://raizapp.xyz/pasarela.html). Tests: nonce repetido → RECHAZADO; exp pasada → no envía; destinatario distinto → RECHAZADO. Timebox 2 h: si algo se complica, entrega solo escaneo + verificación + lista, sin envío. Gate: tests verdes + assembleDebug.
```

---

## B5 — Prueba end-to-end, capturas y video (20:30–22:00)

1. Rebalancear el pool del anchor NO hace falta (no hay depósito en la demo). Sí: fondear al pagador con
   USDC del fondo con red, y registrar/verificar que el comercio de la demo existe en `Pool`
   (`list_merchants`; si hace falta uno nuevo, el relayer lo registra: `register-merchant`).
2. Guion de 75 s: (0–10) problema: vereda sin señal, la artesana de Cota perdió la venta; (10–30) emulador
   con datos apagados firma el sobre → QR; (30–50) iPhone en Safari escanea, verifica, "en tránsito" →
   liquida → hash; (50–65) Stellar Expert: fuente = pasarela, auth = pagador; reenvío falla por nonce;
   (65–75) qué sigue: bolsillo, atestación de hardware, BLE, relayer como pasarela.
3. Grabar la pantalla del emulador (`adb shell screenrecord` o grabador de Windows) y la del iPhone
   (grabación nativa), montar lado a lado; subtítulos EN. Pasar cualquier toma con teclado por
   `python scripts/mask_video_autofill.py`.
4. Evidencia a `docs/evidencia_hackmeridian/`: `README.md` (hashes, enlaces, cómo reproducir en 5 min),
   capturas numeradas, `sobre_demo.json`.

**Prompt listo (documentación de evidencia):**
```text
Lee docs/evidencia_sow/README.en.md como modelo de formato y docs/pago_diferido_v0.md. Crea docs/evidencia_hackmeridian/README.md (EN, con resumen ES al final): qué se construyó durante HackMeridian (diff hackmeridian-start...feat/pago-diferido), tabla hash → enlace Stellar Expert → qué prueba, guía de verificación en 5 minutos para un juez (incluida la pasarela pública), límites declarados, y las capturas que yo te pase en docs/evidencia_hackmeridian/capturas/. Sin afirmaciones sin enlace.
```

---

## B6 — README, arquitectura y respuestas de la submission (22:00–23:30)

- La sección EN del README ya existe; actualizar con los enlaces reales (pasarela, evidencia, video).
- `docs/ARQUITECTURA_TECNICA.md`: subsección "Pago diferido por enjambre" con el diagrama de roles (texto
  o mermaid) y los IDs de contratos usados. Requisito explícito de la submission.
- Respuestas (EN): problema, usuarios, propuesta de valor, qué se construyó durante el evento, arquitectura
  de lo nuevo, modelo de sostenibilidad (honesto: en evaluación; el 2 % no se toca; opciones a cargo del
  visitante y apoyo voluntario), próximos pasos (= SOW de noviembre), equipo.

**Prompt listo:**
```text
Lee README.md (sección HackMeridian), docs/pago_diferido_v0.md, docs/ARQUITECTURA_TECNICA.md y docs/evidencia_hackmeridian/README.md. Rama feat/pago-diferido. (1) Actualiza la sección HackMeridian del README con los enlaces reales que te doy. (2) Añade a ARQUITECTURA_TECNICA.md la subsección "Pago diferido por enjambre": roles, flujo, diagrama mermaid, qué piezas del protocolo usa (auth entry, nonce, expiración, fuente distinta), IDs de contratos, límites. (3) Redacta docs/evidencia_hackmeridian/submission_answers_EN.md con respuestas para: project summary, problem and target users, value proposition, what was built during the hackathon vs prior work, technical architecture of the new work, Stellar features used, sustainability model (en evaluación, el 2 % no se toca), future goals and next steps, team. Párrafos de texto plano, sin viñetas, listos para pegar. Toda cifra con fuente; si no hay fuente, no va.
```

---

## B7 — Lunes 09:00–11:30: deck y envío

- Deck de 10 slides (Diana desde Bogotá con el guion de `docs/evidencia_hackmeridian/`): 1 problema con la
  cita de Cota · 2 lo que RAÍZ ya hace (números con enlace) · 3 el reto: sin señal no hay pago · 4 el pago
  diferido por enjambre (roles) · 5 la pieza del protocolo · 6 demo (capturas + QR al video) · 7 límites
  declarados y cómo se acotan (bolsillo) · 8 modelo de sostenibilidad (en evaluación) · 9 roadmap = SOW
  noviembre · 10 equipo.
- Verificar en incógnito: repo, README, pasarela pública, video, Stellar Expert, evidencia.
- Enviar antes de las 11:45. EP.02 a las 12:00.

---

## P0 — Relayer 0.3.1: cerrar la acuñación abierta de residentes (45 min; ideal domingo noche, si no lunes 13:00)

**Contexto:** la auditoría del 24-oct confirmó con PoC que `/v1/mint-resident` es anónimo (cupo 10/IP/día) y
que con quórum del 30 % se puede capturar la gobernanza de un barrio de 3 residentes. El fix existe en un
bundle que NO está en este portátil: se reimplementa.

**Repo:** `raiz-relayer` (ajustar la ruta local; si no está clonado: `git clone https://github.com/JuanWimmin/raiz-relayer`).

**Alcance:** variables `MINT_RESIDENT_ENABLED` (default `true`) y `MINT_RESIDENT_ALLOWLIST` (lista de G…/C…
separadas por coma); si está deshabilitado o la dirección no está en la lista → 403 **sin gastar cupo**;
`/v1/health` expone `mintResident: { enabled, allowlisted: n }`; `package.json` 0.3.1; 3 tests
(deshabilitado → 403; no listado → 403 sin consumir cupo; listado → camino normal).

**Prompt listo (en el repo del relayer):**
```text
Lee CLAUDE.md/README del relayer y la ruta de mint-resident con sus tests. Implementa en una rama fix/mint-resident-allowlist: variables de entorno MINT_RESIDENT_ENABLED (default true) y MINT_RESIDENT_ALLOWLIST (direcciones G…/C… separadas por coma, validadas con strkey). Si enabled=false o la dirección no está en la lista, responde 403 con un código de error estable ANTES de tocar los cupos (no debe consumir cuota ni por IP ni global). /v1/health expone mintResident: {enabled, allowlisted}. Sube la versión a 0.3.1. Añade 3 tests (deshabilitado; no listado y cupo intacto; listado y camino normal) sin romper los existentes. No toques la firma ni la cola de sequence number. Gate: typecheck, build y toda la suite verde; luego dime el comando exacto de fly deploy (--ha=false) y de fly secrets set para dejar solo las direcciones de los teléfonos de demo.
```

**Despliegue (tú):**
```bash
npm run test:it            # integración contra testnet antes de desplegar
fly deploy --ha=false -a raiz-relayer
fly secrets set MINT_RESIDENT_ALLOWLIST="G…emulador,G…otra" -a raiz-relayer
curl -s https://raiz-relayer.fly.dev/v1/health
```
Después, en GitHub (ambos repos): private vulnerability reporting, secret scanning + push protection,
Dependabot alerts, protección de `main`. Respaldar `raiz-admin-signer` fuera del portátil.

---

## Qué se reutiliza de `raiz-confidential-stack` (decidido 25-oct 15:00) y qué no

Revisado el repo completo (wallet CT "Sobre del Barrio", `raiz-memory`, `goal_meta`, scripts, friction report).

| Pieza | Decisión | Dónde |
|---|---|---|
| Método del fixture byte a byte (JS referencia → test JVM) | **Sí, camino crítico** | B1 y B3 |
| Vocabulario "sobre" (promesa firmada que viaja y se abre on-chain) | **Sí, gratis** | Textos de app, pasarela, deck |
| `raiz-memory` (índice durable de `getEvents`) | **No en la demo ni en el alcance declarado.** Refuerza la historia consultable (lista "pagos liquidados", ejecuciones que dejan de ser "históricas"), no la seguridad ni el flujo offline. Va en el deck como "siguiente paso: la memoria del territorio (ya construida)" y se integra el lunes tarde o en el SOW de noviembre. Solo si B2+B3 cierran antes de 18:30: túnel desde la laptop para la lista de la pasarela, nunca como dependencia | Deck, roadmap |
| Friction report + 10 issue drafts, medición de proving en gama media | Una línea en la slide de "ecosystem impact" | Deck |
| "Mi recibo" (D-sender), Confidential Tokens, prover WebView, `goal_meta`, `SorobanRpc.kt` | **No.** v2 del sobre cifrado, una línea de roadmap con enlace | Deck |

Regla: nada de esto cambia el alcance declarado en el check-in ni el track (Stellar Unlocked).

## Checklist de la submission (de la página oficial "Requirements for every project")

- [ ] Repo público con el código → `Protocolo_Raiz` (+ `raiz-relayer`)
- [ ] README: resumen, problema, setup, instalación, variables de entorno, instrucciones de demo (sección EN)
- [ ] Arquitectura: cómo usa Stellar, assets, IDs de contratos, anchors, wallets, integraciones
- [ ] Demo en video + **URL pública explorable** (`raizapp.xyz` + `raizapp.xyz/pasarela.html`)
- [ ] Evidencia de tx on-chain (hash del pago diferido + las 8 ejecuciones previas)
- [ ] Prior work vs. hackathon work: tag `hackmeridian-start` y diff enlazado
- [ ] Scale: alcance claro, arquitectura de lo nuevo, modelo de sostenibilidad, próximos pasos, deck
- [ ] Track temático guardado en la submission: **Stellar Unlocked**
- [ ] Todos los enlaces abiertos en incógnito antes de enviar

## Comandos que se repiten

```bash
# red del emulador
adb shell svc wifi disable && adb shell svc data disable      # sin señal
adb shell svc wifi enable  && adb shell svc data enable       # con señal
# instalar y ver logs
adb install -r android/app/build/outputs/apk/debug/app-debug.apk && adb logcat -s RAIZ
# ledger actual (para el estimador y para probar expiraciones)
curl -s https://soroban-testnet.stellar.org -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"getLatestLedger"}'
# simular sin enviar (gotcha del CLAUDE.md)
stellar contract invoke --id <pool> --network testnet --send=no -- list_merchants --barrio_id <hex>
# publicar la landing/pasarela: copiar landing/*.html al repo Pages JuanWimmin/JuanWimmin.github.io
# grabar emulador
adb shell screenrecord /sdcard/demo.mp4 && adb pull /sdcard/demo.mp4
```
