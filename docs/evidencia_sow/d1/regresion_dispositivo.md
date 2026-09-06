# D1 — Regresión en dispositivo físico (app 0.2.0 vía relayer)

El SOW pide que los flujos que antes firmaba la app con la clave admin sigan funcionando en un
teléfono real después de sacar esa clave del APK. Esta checklist se corre a mano; las capturas
van a `docs/evidencia_sow/d1/capturas/` con el nombre indicado en cada paso.

| Dato | Valor |
|---|---|
| Dispositivo de referencia | Motorola G04 · Android 14 (TLS contra `*.stellar.org` confirmado) |
| APK | `app-release.apk` 0.2.0 (`versionCode 2`, firmado con la clave debug de Android desde H9) instalado con `adb install -r` — o el debug equivalente del mismo commit |
| Relayer | https://raiz-relayer.fly.dev — antes de empezar: `curl -s https://raiz-relayer.fly.dev/v1/health` → `ok: true`, `network: testnet`, `vaultEndpoints: true` |
| Commit | rama `feat/wp1-app-relayer` — anotar el `git rev-parse --short HEAD` con el que se compiló el APK instalado (el APK de evidencia `74ec7529…` sale del último commit de la rama) |
| Fecha / quién prueba | (rellenar al probar: fecha) / (rellenar: nombre) |

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

## Tabla final de transacciones (rellenar)

| Flujo | Cuenta del usuario | txHash | Stellar Expert | OK |
|---|---|---|---|---|
| Faucet passkey (`C…`) | | | | ☐ |
| Faucet semilla (`G…`) | | | | ☐ |
| Faucet sin trustline (`curl`, esperado 422 `NO_TRUSTLINE`) | | — | — | ☐ |
| Volverse comercio | | | | ☐ |
| Verificar residente | | | | ☐ |
| Yield depositar | — | | | ☐ |
| Yield rescatar | — | | | ☐ |

Incidencias encontradas: `(rellenar: ninguna / descripción + captura)`
