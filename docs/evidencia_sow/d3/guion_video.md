# D3 — Guion del video de 60 s (depósito SEP-24 con el anchor de prueba)

Objetivo (aceptación WP3): un depósito completo **desde la app → web del anchor (sin cortes) → saldo
actualizado + tx hash**. Un solo plano de pantalla del teléfono, narración en off en español.
Estado al 2026-10-03: **grabado** en el Motorola G04 — [`video/d3_deposito_sep24_60s.mp4`](video/d3_deposito_sep24_60s.mp4)
(61 s, el depósito) y [`video/d3_deposito_y_conversion_completo.mp4`](video/d3_deposito_y_conversion_completo.mp4)
(92 s, con la conversión). Tiempos reales al final ("Toma real del 2026-10-03"). Lo que sigue es el guion
previo; donde difiere de lo medido, manda la toma real.
El video del SOW es **solo el depósito**; la conversión al USDC del fondo es una toma extra opcional
(al final de este guion) que no cuenta dentro de los 60 s.

## Antes de grabar (checklist)

> Checklist de preparación tal como se usó el 3-oct (las casillas son de trabajo y se dejan sin
> marcar para la próxima toma).

- [ ] APK **debug** del commit final de `main` instalado
      (`cd android && ./gradlew :app:assembleDebug -q` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
      Anotar commit y hora en `README.md` de esta carpeta.
- [ ] Wallet **semilla** (`G…`) — passkey no sirve (muestra "Disponible pronto para passkey (SEP-45)").
      Con XLM (≥ 2 XLM: reserva de la trustline + fees) y **0 USDC del anchor**, para que el saldo pase de
      0 a 5 en cámara. Ideal: wallet nueva fondeada con friendbot justo antes — así también se ve el
      paso "Habilitando USDC del anchor en tu cuenta…" (la trustline).
- [ ] Anchor vivo: abrir en el PC `https://testanchor.stellar.org/.well-known/stellar.toml` y
      `https://testanchor.stellar.org/sep24/info` (200; `deposit.USDC.enabled = true`, mín 1, máx 10).
- [ ] Ensayo previo con OTRA wallet para medir cuánto tarda el anchor en pasar a `completed` y ver qué
      datos pide su web (el flujo es idéntico; el segundo depósito ya no pide firma SEP-10 en 24 h).
- [ ] Red estable (wifi), brillo alto, modo "No molestar", idioma del sistema en español.
- [ ] Chrome instalado y actualizado (Custom Tabs).
- [ ] Grabación: `adb shell screenrecord --time-limit 150 /sdcard/d3.mp4` (al terminar,
      `adb pull /sdcard/d3.mp4`) o `scrcpy --record d3.mp4`. Grabar hasta 2,5 min y recortar a 60 s.
- [ ] PC con `adb logcat -s RAIZ` corriendo (respaldo si algo falla; ver "Dónde capturar el tx hash").
- [ ] (Solo si se graba la toma extra "Convertir") cotización viva comprobada en el PC (comando `curl`
      de `README.md` § "El pool de liquidez"). Vale el mismo APK de `main`.

## Línea de tiempo (60 s)

| Tiempo | Pantalla | Acción | Qué debe verse | Narración sugerida |
|---|---|---|---|---|
| 0:00–0:06 | **Inicio** | Nada (mostrar) | Saldo USDC (Blend) del BalanceCard; botón verde "Depositar · anchor de prueba"; si la wallet es nueva, banner "Paso 3 · Consigue USDC" | "Esta es mi wallet RAÍZ en testnet. Hasta hoy el USDC de prueba lo regalaba el relayer; ahora lo deposito con un anchor real usando los estándares de Stellar." |
| 0:06–0:12 | **Depositar USDC** | Tap "Depositar · anchor de prueba" | Subtítulo "Anchor de prueba del SDF · testanchor.stellar.org · SEP-10 + SEP-24", card negra "USDC · anchor de prueba **0 USDC**", monto **5**, los tres pasos en texto pequeño | "Voy a depositar 5 USDC. La app hará tres cosas: la trustline, la autenticación SEP-10 firmada con mi wallet y el depósito interactivo SEP-24." |
| 0:12–0:20 | Depositar (preparando) | Tap "Depositar con el anchor" | Spinner: "Habilitando USDC del anchor en tu cuenta…" → "Autenticando con el anchor (SEP-10)…" → "Abriendo el depósito (SEP-24)…" | "Firmo el challenge del anchor con mi clave; el token queda solo en memoria del teléfono." |
| 0:20–0:40 | **Custom Tab** (web del anchor) | Confirmar monto, rellenar datos de prueba, enviar | Toolbar negra RAÍZ, dominio `anchor-ref-ui-testanchor.stellar.org`; formulario del anchor. **Sin cortes: es la gracia del "interactivo"** | "Esta es la web del anchor — aquí iría el KYC y el pago en efectivo o por transferencia. En el anchor de prueba se simula." |
| 0:40–0:50 | Depositar (esperando) | Atrás / cerrar la pestaña | Chip púrpura con el estado (`pending_anchor` → `pending_stellar` → `completed`) y "Esperando al anchor… Ns" contando | "La app sondea el estado del depósito hasta que el anchor envía el USDC a mi cuenta." *(si el anchor tarda más de ~10 s, acelerar este tramo en edición dejando visible el contador)* |
| 0:50–0:57 | **¡Depósito recibido!** | Tap en el chip verde | Animación de éxito, "4.5 USDC · anchor de prueba" (5 menos la comisión de 0,5 del anchor), chip "Ver en Stellar Expert · abc123…xyz789" → Stellar Expert con la tx (pago de USDC del anchor a mi `G…`) | "Depósito recibido. Este es el hash real de la transacción del anchor, verificable por cualquiera en Stellar Expert." |
| 0:57–1:00 | **Inicio** | Atrás → "Volver a la wallet" | Línea "● USDC · anchor de prueba: 4.5 USDC" bajo el botón; el saldo USDC (Blend) **no cambió** | "Y aquí está el saldo, rotulado aparte: es el USDC del anchor, no el del fondo del barrio." |

## Toma extra opcional (~10 s): "Convertir a USDC del fondo"

No forma parte del video del SOW: D3 es el depósito y esos 60 s no se tocan. Es un cierre opcional (o
un clip aparte) para enseñar que lo depositado acaba siendo saldo con el que sí se paga. Se graba
**después** de la toma 0:57–1:00, para que el video del SOW conserve el plano del saldo rotulado aparte.
Vale el APK de `main` desde el 3-oct.

| Tiempo | Pantalla | Acción | Qué debe verse | Narración sugerida |
|---|---|---|---|---|
| +0:00–0:03 | Inicio → **Depositar USDC** | Tap "Depositar · anchor de prueba" | Card negra con **4.5 USDC** y, debajo, la card de borde púrpura "Convertir a USDC del fondo" con "Recibirás ≈ N USDC · pool de liquidez de testnet, fee 0,3 %, tolerancia 1 %" | "En testnet el anchor de prueba emite un USDC distinto al del fondo del barrio. Lo convierto en un tap." |
| +0:03–0:07 | Depositar | Tap en el botón púrpura "Convertir…" | "Enviando la conversión…" (antes, "Habilitando el USDC del fondo en tu cuenta…" si la wallet no tenía esa trustline); la card negra baja a **0 USDC** | "Es un path payment que firmo yo, de mi cuenta a mi cuenta, contra un pool de liquidez: nadie custodia nada." |
| +0:07–0:10 | **Inicio** | Atrás | El saldo USDC del BalanceCard **subió ≈ N**; la línea "● USDC · anchor de prueba" ya no aparece | "Ahora sí es el USDC del fondo: con esto pago en los comercios del barrio. En mainnet este paso no existe, porque el USDC de Circle es uno solo." |

Notas para esta toma:

- **N es la cotización del momento**, no un número fijo: para 5 USDC fue 4,44 el 27-sep y 5,05 el
  3-oct. Comprobarla en el PC justo antes; si está muy lejos de 5, mejor no grabar la toma ese día.
- **Variante con el hash en pantalla (la que se grabó):** convertir desde "¡Depósito recibido!" (la card
  aparece bajo el chip del hash). Quedan fijos "✓ Convertido: recibiste N USDC del fondo" (N = monto real
  leído de Horizon) y el chip verde "Ver en Stellar Expert · hash". Desde el formulario la card también
  conserva el resultado. El hash sale además en logcat (`pathPaymentStrictSend: USDC→USDC OK para G…, hash=…`)
  y en Horizon (`/accounts/<G…>/operations?order=desc&limit=5`).
- Si la wallet no tiene trustline al USDC de Blend, necesita 0,5 XLM libres para crearla.
## Dónde capturar el tx hash (para el campo del portal)

1. **Chip de la app** en la pantalla de éxito (acortado `abc123…xyz789`). Al pulsarlo, la barra de
   direcciones de Stellar Expert muestra el hash completo: `https://stellar.expert/explorer/testnet/tx/<hash>`.
   Capturar esa pantalla (`capturas/08_stellar_expert_tx.png`).
2. **Horizon** desde el PC (público, sin JWT):
   `https://horizon-testnet.stellar.org/accounts/<G…>/payments?order=desc&limit=5` → el registro con
   `asset_code: "USDC"`, `asset_issuer: "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"`,
   `to: <G…>` y su `transaction_hash`. Debe coincidir con el del chip. Es la misma fuente que usa la
   app como respaldo si el anchor no informara `stellar_transaction_id`.
3. **Respaldo por logcat** (`adb logcat -s RAIZ`): no imprime el hash, pero sí
   `AnchorClient.authenticate: sesión SEP-10 obtenida (exp=…)` y los errores transitorios del polling —
   útil para diagnosticar si algo se atasca.

Anotar el hash en `README.md` de esta carpeta (sección "Tx hash del depósito") y en
`docs/evidencia_sow/README.md` (campo 2 de la tabla D3).

## Plan B durante la grabación

| Falla | Qué hacer |
|---|---|
| El anchor tarda > 5 min | La app muestra "El anchor sigue procesando" con "Seguir esperando". Pulsarlo y esperar; si el tramo útil supera el minuto, volver a grabar con otro depósito (el SEP-10 ya está cacheado). |
| Más de 10 min dentro de la web del anchor | El token de esa URL caduca. "Cancelar" en la app y volver a "Depositar con el anchor": arranca en un instante (sin firma nueva). |
| `pending_trust` | La app crea o reintenta la trustline sola. Si persiste, revisar que la cuenta tenga XLM suficiente (reserva). |
| "No se pudo hablar con el anchor de prueba" | Probar el toml en el PC; si el anchor está caído, posponer la grabación. |
| La Custom Tab no abre | Instalar/actualizar Chrome; sin Custom Tabs la app cae a un navegador normal (también sirve para el video). |
| Aparece "Tu cuenta aún no existe en Stellar" | Pulsar "Fondear con friendbot" y repetir desde 0:06. |
| (Toma extra) La card dice "No hay liquidez en testnet…" o "La liquidez cambió: vuelve a cotizar." | "Reintentar" vuelve a cotizar. Si persiste, omitir la toma extra: el video del SOW no depende de ella y el USDC del anchor sigue en la cuenta. |

## Publicación

> Hecho el 4-oct: el video se publicó en <https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4>
> (y la toma completa en `…/evidencia/d3_deposito_y_conversion_completo.mp4`). YouTube queda como
> opción si el portal lo exige.

- YouTube **no listado**, título "RAÍZ · Depósito SEP-24 con el anchor de prueba del SDF (testnet)",
  descripción con commit, fecha, `G…` destino y el enlace de Stellar Expert de la tx.
- Pegar el enlace del video y el hash en `docs/evidencia_sow/README.md` § D3 y en `README.md` de esta carpeta.

## Toma real del 2026-10-03 (tiempos medidos)

Wallet semilla nueva `GABZUFA6…GTISJ3` (friendbot + trustline al USDC del fondo hechos antes; 0 USDC),
APK debug de la rama `feat/wp3-sep24`, teléfono operado por adb (toques por `uiautomator` + `input`,
grabación con `adb shell screenrecord`). Los datos del formulario del anchor son ficticios.

| Tiempo | Qué se ve |
|---|---|
| 0:00 | Inicio: banner "Paso 3 · Consigue USDC", saldo 0 USDC |
| 0:08 | Tap "Depositar (anchor de prueba)" → pantalla "Depositar USDC" (toml + `/info` cargados a los 0:15) |
| 0:22 | Tap "Depositar con el anchor" → "Habilitando USDC del anchor…" → "Autenticando con el anchor (SEP-10)…" → "Abriendo el depósito (SEP-24)…" |
| 0:31 | Custom Tab del anchor ("SEP-24 Reference UI"): formulario Deposit |
| 0:42 | Formulario lleno y Submit |
| 0:49 | "Transaction information": `completed`, send 5 USD, receive 4.5 USDC, `STELLAR TRANSACTION ID ae4d3e43…` |
| 0:52 | Cierro la pestaña → vuelta a la app |
| 0:59 | "¡Depósito recibido! · 4.5 USDC · anchor de prueba" + chip "Ver en Stellar Expert · ae4d3e…df7562" (fin del corte de 60 s) |
| 1:06 | Tap "Convertir 4.5 USDC" → "Enviando la conversión…" |
| 1:16 | "✓ Convertido: recibiste 4.523 USDC del fondo" + chip `23e926…3d3658` |
| 1:23 | "Volver a la wallet" → Inicio con **4.523 USDC** y sin banner de alta (1:31) |

El corte de 60 s es el tramo 0:02–1:04 de la toma, sin cortes internos (velocidad ×1,04).
Hashes: depósito `ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562`, conversión `23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658`.
