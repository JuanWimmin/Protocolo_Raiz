# Pago diferido por enjambre — spec v0 (HackMeridian 2026)

> Estado: **spec congelada el 25-oct-2026 para el build de HackMeridian** (camino Scale, track Stellar Unlocked).
> No despliega ningún contrato nuevo: usa `Pool.pay_merchant` tal como está en testnet.
> Referencias: Jie et al., IEEE TC 73(2) 2024 (protocolo offline con TEE); Si et al., Sensors 26(10):3039, 2026
> (credenciales encadenadas y liquidación por lotes); guía oficial "Signing Soroban contract invocations".

---

## 0. Qué resuelve y qué no

**Resuelve:** que un visitante pueda pagarle a un comercio del territorio cuando uno de los dos (o ambos) no
tiene señal, y que el pago se liquide en la red en cuanto cualquier dispositivo del enjambre la recupere.

**No resuelve y no promete:** finalidad offline. Mientras el sobre no llega a la red, el comercio tiene una
promesa firmada, no dinero. El doble gasto es posible dentro de la ventana y se acota con (a) expiración por
ledger, (b) tope por comercio y (c) el bolsillo de territorio prefondeado (v1). Esto se dice en la UI, en el
README y en el pitch con estas mismas palabras.

## 1. Roles del enjambre

| Rol | Dispositivo | Hace | No puede |
|---|---|---|---|
| Pagador | Teléfono del visitante (wallet semilla en v0; llave Keystore atestada en v1; passkey C… en v1) | Construye y firma la entrada de autorización de `pay_merchant` sin red; la exporta como sobre QR | — |
| Cobrador | Teléfono del comercio | Verifica offline la firma; guarda el sobre; muestra "en tránsito"; actúa como pasarela si tiene señal | Alterar monto, destinatario ni tip (están firmados) |
| Portador | Cualquier teléfono con la app (residente, otro comercio) | Transporta sobres (QR en v0; BLE/Wi-Fi Direct en v1) | Leer el sobre en v1 (va cifrado a la pasarela); gastarlo; redirigirlo |
| Pasarela | Cualquier cuenta G con señal: el cobrador, un residente, una página web, o el relayer (v1) | Reconstruye la tx con su cuenta como fuente, simula en modo enforcing, paga el fee, envía, devuelve el hash | Cambiar nada de la invocación: la firma del pagador la fija |

Propiedad clave: **la pasarela no necesita ser de confianza.** Lo único que aporta es XLM para el fee y
conectividad. Por eso un iPhone sin la app, con una página web, sirve de pasarela.

## 2. La pieza del protocolo

Una `SorobanAuthorizationEntry` con credenciales de dirección firma el hash SHA-256 del preimage
`ENVELOPE_TYPE_SOROBAN_AUTHORIZATION = (network_id, nonce, signature_expiration_ledger, invocation)`.
La firma cubre **el árbol completo de invocación** (función raíz + sub-invocaciones), no el footprint ni la
transacción. Por eso se puede firmar sin red y enviar después desde otra cuenta.

Credenciales **legacy** (`SOROBAN_CREDENTIALS_ADDRESS`, preimage tipo 9). Medido el 25-oct-2026 en testnet
(Protocol 29): el host emite las credenciales V2 de CAP-71 (preimage `…WITH_ADDRESS`, tipo 10) solo si el
cliente las pide (`useUpgradedAuth`), y sigue aceptando las legacy, que son las únicas que conoce
`kmp-stellar-sdk` 1.6.0. El fixture y el pagador usan legacy; cuando el SDK Kotlin incorpore V2 se migra
(cambia el preimage, no el flujo).

### 2.1 Árbol de autorización de `pay_merchant` (verificado en `contracts/pool/src/lib.rs`)

Para `pay_merchant(tourist, merchant, amount, tip_bps)` con `fee_bps` leído de instancia (hoy 50):

```
tip  = amount * tip_bps / 10_000
fee  = amount * fee_bps / 10_000
to_m = amount - fee

root:  Pool.pay_merchant(tourist, merchant, amount, tip_bps)       [require_auth(tourist)]
  ├─ USDC.transfer(tourist, merchant, to_m)
  ├─ USDC.transfer(tourist, Pool,     tip)     solo si tip > 0
  └─ USDC.transfer(tourist, admin,    fee)     solo si fee > 0
```

`Rewards.accrue_points` lo invoca el Pool con `current_contract_address()`: no entra en el árbol del
turista. Divisiones enteras exactamente como el contrato (i128, trunca hacia cero). Direcciones de
`Pool`, `USDC` (SAC de Blend) y `admin` salen de `deployments.json`; `fee_bps` se lee y cachea cuando hay
red (si cambia, la firma deja de coincidir y la pasarela recibe `Auth, InvalidAction`: fallo seguro).

**Deuda registrada (25-oct):** el Pool descuenta el fee del protocolo del monto del comercio
(`to_m = amount − fee`), lo que choca con el principio de que el comercio recibe íntegra su venta. No se toca
en la hack (cero contratos nuevos); se resuelve en los contratos v2 (ajuste 5 de
`docs/Pago diferido por enjambre.md`). La pantalla del sobre lo muestra tal cual.

### 2.2 Nonce y expiración

- `nonce`: 64 bits aleatorios del CSPRNG del teléfono. La red guarda el nonce consumido como entrada
  temporal hasta `signature_expiration_ledger`; una segunda presentación del mismo sobre falla. El
  cobrador además guarda localmente `(payer, nonce)` vistos.
- `signature_expiration_ledger` E: estimado sin red (§3.1). Si E ya pasó al enviar → la red rechaza →
  estado EXPIRADO, sin pérdida para nadie.

## 3. Matemática del sobre

### 3.1 Estimación del ledger sin red

El pagador guarda, la última vez que tuvo red, el par (L0, t0): último ledger conocido y el instante
**monotónico** del dispositivo (`SystemClock.elapsedRealtime()`, nunca la hora de pared, que el usuario
puede mover). Al firmar en el instante t:

```
L̂(t) = L0 + floor((t - t0) / τ)          τ = tiempo medio de cierre (≈ 5 s; medir en el spike)
E     = L̂(t) + W                          W = ventana en ledgers que se concede al enjambre
```

Error del estimador: si el cierre real promedio en el intervalo fue τ' ≠ τ, el ledger real es
`L(t) = L0 + (t - t0)/τ'`. La diferencia `L(t) - L̂(t) ≈ (t - t0) · (1/τ' - 1/τ)`. Con τ = 5 s y un
cierre real de 6 s durante 12 h offline (43 200 s): L̂ sobreestima en ≈ 1 440 ledgers (la expiración
real queda ~2 h más lejos de lo previsto: inofensivo). Con cierre real de 4,5 s: L̂ subestima en ≈ 960
ledgers y la ventana efectiva se acorta ~1,3 h. Regla: **W se dimensiona para la ventana deseada más el
error de subestimación**, `W = W_deseada · (τ / τ_min)` con τ_min el cierre más rápido observado.
Ventana v0: 12 h → W_deseada = 8 640 ledgers → con τ_min = 4,5 s, W ≈ 9 600. Tope de la red: medir en el
spike (`max_entry_ttl` de las entradas temporales acota E − L).

Parámetro honesto en la UI: "válido hasta ~hh:mm (estimado)".

**Medido en el spike B1 (25-oct-2026, testnet Protocol 29):**

- Cierre de ledger: **5,000 s exactos** en 199 ledgers consecutivos (mín = máx = 5 s). Con τ = τ_min = 5 s, W para
  12 h sería 8 640; se mantiene el margen de la fórmula por si el cierre baja (τ_min = 4,5 s → W ≈ 9 600).
- Tope de la red: el nonce se guarda como entrada temporal viva hasta E y la red exige
  `E − L ≤ max_entry_ttl − 1`. Con `max_entry_ttl = 3 110 400` (≈ 180 días a 5 s): Δ = 3 110 399 **aceptado**,
  Δ = 3 110 400 **rechazado** con `Error(Auth, InvalidInput)` (simulación en modo enforce, juzgado por
  `E − latestLedger` de la propia simulación). La ventana de 12 h queda tres órdenes de magnitud por debajo.
- E = L (ledger actual) sigue siendo válido; E < L falla con `Error(Auth, InvalidInput)`; reenviar un sobre ya
  liquidado falla con `Error(Auth, ExistingValue)` (nonce consumido). Son los tres mensajes que la pasarela
  traduce a `expirada` y `nonce usado`.
- **Fee que paga la pasarela (testnet, hoy): 28,65 XLM** (`fee_charged` de `ebee152c…`; la simulación pedía
  32,95 XLM de recursos y hoy pide 41,7). No es el sobre ni una restauración: la simulación lista 1 224 bytes
  escritos, 2,6 M de instrucciones y ninguna entrada archivada; es el precio de escritura de testnet por el
  tamaño de su estado (la misma `pay_merchant` costaba 0,0009 XLM en mayo). Consecuencias: la pasarela de la
  demo se fondea con friendbot (10 000 XLM ≈ 250 liquidaciones) y el pitch no promete "con unos pocos XLM"
  hasta medirlo en mainnet. El modelo de quién paga ese fee (pasarela voluntaria, comercio, relayer) es parte
  del modelo de sostenibilidad en evaluación.
- **Ventana revisada (25-oct, noche): W = 11 520 ledgers, no 9 600.** La fórmula de arriba cubría la deriva
  durante la ventana pero no la acumulada entre la última sincronización y la firma: con hasta Δ_max = 24 h
  sin señal, `W = T_des/τ_min + Δ_max·(1/τ_min − 1/τ) = 9 600 + 1 920 = 11 520` (A2, paso 8 de
  `docs/Pago diferido por enjambre.md`). Peor caso (red a 4,5 s tras 24 h de deriva): 12 h exactas; con el
  cierre medido hoy, 16 h. La UI muestra la ventana garantizada: "Válido ~12 h (hasta ~hh:mm, estimado)".
  Implementado en `LedgerMath.window` con sus tests.

### 3.2 Exposición y doble gasto

Sea B el saldo del pagador, A_i los montos de los sobres pendientes que emitió, y P el bolsillo (v1).

- **v0 (sin bolsillo):** la red ejecuta los sobres en el orden en que llegan; el k-ésimo falla si
  `Σ_{j≤k} (A_j + tip_j) > B`. Un pagador malicioso puede emitir Σ A_i > B y decidir qué comercios cobran.
  Exposición de cada comercio = su A_i. Mitigación v0: tope por comercio `A_i ≤ C_m` (configurable,
  p. ej. 20 USDC) y la identidad del pagador queda firmada (no hay anonimato para repudiar).
- **v1 (bolsillo):** el pagador bloquea P en el contrato `pocket` del territorio estando en línea; los
  sobres se giran contra P. Pérdida agregada máxima de los comercios = `max(0, Σ A_i − P)`. El cobrador
  acepta si `A_i ≤ min(C_m, P_visto − Σ A_vistos)`, donde `P_visto` sale de la caché del registro de
  bolsillos del territorio que cada cobrador sincroniza cuando tiene señal (el territorio tiene decenas
  o cientos de bolsillos: cabe en el teléfono). El ataque restante exige que el pagador gaste el bolsillo
  en varios comercios **antes** de que cualquiera sincronice: queda acotado por P y por la frecuencia de
  sincronización del enjambre.
- Lo que Si et al. hacen con credenciales de vuelto encadenadas (cada pago consume la credencial anterior y
  emite una nueva, hash-linked) aquí lo hacen el nonce de Soroban (unicidad) y el saldo del bolsillo
  (conservación). No hay que reimplementar la cadena de hashes.

### 3.3 Entrega por el enjambre

Si un sobre se confía a k portadores independientes, cada uno con probabilidad q de alcanzar una
pasarela antes de E, `P(liquidado) = 1 − (1 − q)^k`. Con q = 0,6: k = 1 → 60 %; k = 2 → 84 %; k = 3 →
93,6 %. Los portadores solo pueden retrasar o perder (disponibilidad), nunca alterar (integridad) ni
redirigir (el destinatario va firmado). Replicar un sobre es gratis y seguro: la red consume el nonce
una sola vez.

### 3.4 Capacidad del QR

Medido con el fixture de B1 (3 sub-invocaciones): la entrada firmada ocupa **912 bytes** de XDR (1 216
caracteres en base64) y el sobre JSON de la §5 completo, en base64url, **2 043 caracteres**. Un QR versión 40
con corrección M admite 2 331 bytes en modo binario (el base64url lleva minúsculas: no es el modo alfanumérico
del QR): cabe en un solo código con ≈ 290 bytes de margen; con corrección L caben 2 953. Si el sobre cifrado
de v1 se pasa, se fragmenta en 2 QR con índice `i/n`.

## 4. Seguridad: modelo de amenazas (siguiendo la taxonomía de Jie et al.)

| Ataque | Defensa | Estado |
|---|---|---|
| Falsificación del sobre (monto, destinatario, tip) | Todo va dentro del árbol firmado con ed25519; verificable offline por el cobrador | v0 |
| Replay del mismo sobre | Nonce consumido on-chain + caché local `(payer, nonce)` del cobrador | v0 |
| Replay en otra red | `network_id` dentro del preimage | v0 |
| Doble gasto del pagador | Expiración, tope por comercio, identidad firmada; bolsillo prefondeado | v0 parcial / v1 |
| Doble gasto desde otro dispositivo (passkeys sincronizadas) | Llave de Keystore en TEE/StrongBox con Key Attestation: no se sincroniza; la atestación prueba hardware + firma de la app | v1 |
| Pagador con teléfono rooteado | No se puede impedir en software (ni Jie et al. sin SGX); se acota con el bolsillo | v1 |
| Portador malicioso | Solo puede perder/retrasar: replicación k; en v1 sobre cifrado (X25519 a la clave de la pasarela) para que no lea quién pagó a quién | v0 / v1 |
| Pasarela maliciosa | No puede alterar la invocación; puede negarse a enviar: cualquier otra pasarela sirve; puede ver el sobre (v1: cifrado salvo para el cobrador) | v0 |
| Cobrador que entrega y luego el pago expira/falla | Riesgo comercial explícito; la UI lo rotula "promesa firmada, no confirmada"; tope C_m | v0 |
| Reloj del teléfono manipulado | Se usa reloj monotónico desde la última sincronización, no la hora de pared | v0 |
| Privacidad en territorios de conflicto | El sobre expone pagador, comercio y monto a quien lo transporte: v1 cifrado al destino; coordenadas ~100 m ya previstas | v1 |

## 5. Formato del sobre (v0)

> **Revisado el 25-oct (noche), decisión del equipo:** lo que viaja en el QR y en "Copiar sobre" es **solo la
> entrada firmada**: `raiz:sobre:1:` + base64url (sin relleno) del XDR de la `SorobanAuthorizationEntry`
> (1 229 caracteres, QR versión 29 con corrección M; 912 bytes). Comercio, monto, aporte y reparto se derivan
> de la invocación firmada (`PayArgs`/`SobreValidator` en la app), así que no existe la clase de error
> "JSON y XDR no coinciden" y hay una sola fuente de verdad. El JSON de abajo queda como descripción de los
> campos que el XDR contiene, no como formato de transporte (ajuste 1 de `docs/Pago diferido por enjambre.md`;
> fixture: campo `sobre_qr`). La pasarela web y el cobrador deben parsear el prefijo + XDR.

JSON compacto → base64url → QR.

```json
{
  "v": 1,
  "net": "Test SDF Network ; September 2015",
  "payer": "G…",
  "merchant": "G…",
  "amount": "250000000",
  "tip_bps": 200,
  "fee_bps": 50,
  "nonce": "7021436735540711424",
  "exp": 6398412,
  "l0": 6389772, "t0": 1761393600,
  "auth": "<SorobanAuthorizationEntry XDR base64>"
}
```

Los campos fuera de `auth` son redundantes (todo está dentro del XDR) y existen para que el cobrador
muestre el detalle y verifique **antes** de decodificar el XDR; la pasarela reconstruye la invocación
desde el XDR, nunca desde el JSON. Si JSON y XDR difieren → RECHAZADO.

## 6. Máquina de estados

```
Pagador:   BORRADOR → FIRMADO(exp) → ENTREGADO(QR mostrado) → [CONFIRMADO(hash) | EXPIRADO | RECHAZADO]
Cobrador:  ESCANEADO → VERIFICADO(firma ok, nonce nuevo, exp > L̂) → EN_TRANSITO → [CONFIRMADO | EXPIRADO | RECHAZADO(motivo)]
Pasarela:  RECIBIDO → SIMULADO(enforcing) → ENVIADO(hash) → CONFIRMADO | FALLIDO(motivo)
```

Liquidación por hash: la pasarela devuelve el hash; cobrador y pagador lo consultan cuando tienen red
(mismo patrón de `DepositViewModel.settle`). Motivos de RECHAZADO: `nonce usado`, `expirada`, `fondos
insuficientes`, `firma inválida`, `comercio no verificado`, `fee_bps cambió`.

## 7. Alcance v0 (24 h) y criterios de aceptación

1. **Pagador (app Android, wallet semilla):** modo "sin señal" en Pagar → construye el árbol §2.1, firma,
   muestra QR + "válido hasta ~hh:mm (estimado)". Guarda el sobre en Room/DataStore con estado.
2. **Pasarela web pública (`landing/pasarela.html`, stellar-sdk JS):** escanea o pega el sobre, verifica
   firma y expiración, reconstruye la tx con una cuenta de pasarela (generada en el navegador y fondeada
   por friendbot, o pegada), simula enforcing, envía, muestra hash + enlace a Stellar Expert. **Es la
   demo del rol "cualquier teléfono con señal sirve de pasarela": corre en Safari de un iPhone.**
3. **Cobrador (app Android):** escanear sobre → VERIFICADO → EN_TRANSITO → enviar con su cuenta cuando hay
   red. Si el tiempo no alcanza, el cobrador de la demo es la pasarela web: el flujo sigue completo.
4. **Evidencia:** tx en Stellar Expert con fuente = pasarela y auth = pagador; video con el pagador en
   modo avión (emulador con datos apagados o teléfono) y la pasarela en el iPhone.
5. **README EN** "Built at HackMeridian" + este documento + `git tag hackmeridian-start` en el check-in.

Fuera de v0 (v1 / SOW de noviembre): contrato `pocket`, Key Attestation, portadores BLE, sobre cifrado,
pasarela en el relayer (`POST /v1/deferred-payment` con intención estructurada + auth entry; el relayer
sigue sin aceptar XDR de transacción del cliente), passkey C… firmando offline, iOS vía KMP.

## 7b. Método de verificación del árbol (decidido 25-oct)

La referencia de la §2.1 no se valida "a ojo": `scripts/gen-auth-fixture.mjs` (Node, `@stellar/stellar-sdk`,
`authorizeEntry`) genera, con keypair, nonce y expiración fijos, el preimage, su hash, la firma y la entrada
firmada en XDR → `android/app/src/test/resources/deferred_auth_fixture.json`. El código Kotlin del pagador
debe reproducir esos bytes exactamente (test JVM), y la pasarela web se prueba primero con ese mismo sobre.
Es el patrón fixture-byte-a-byte que ya funcionó en `raiz-confidential-stack` (`gen-sign-fixture.mjs` +
`StellarAccountTest`); se importa el método, no el código.

**Hecho el 25-oct:** `node scripts/gen-auth-fixture.mjs` genera el fixture (determinista; `--check` lo
verifica; `--verify-tree` simula `pay_merchant` en testnet sin firmar y comprueba que el árbol construido a
mano es el que emite el host: igual, 3 sub-invocaciones). El pagador Kotlin vive en
`android/app/src/main/java/com/raiz/app/data/deferred/` (`AuthTreeBuilder`, `DeferredAuthSigner`) y
`DeferredAuthSpikeTest` (16 tests) comprueba byte a byte invocación, entrada sin firmar, preimage, hash, firma,
entrada firmada, el contraste con `Auth.authorizeEntry` del SDK, el round-trip, `verify` y las variantes
(tip 0, fee 0, truncado, monto minúsculo, nonce negativo).

## 8. Tests (mínimo v0)

JVM (Android):
0. **Fixture:** preimage, hash, firma y XDR de la entrada coinciden byte a byte con `deferred_auth_fixture.json`.
1. Árbol de `pay_merchant` con tip > 0 y fee > 0: 3 sub-invocaciones con montos exactos (i128, truncado).
2. Árbol con `tip_bps = 0`: 2 sub-invocaciones (sin transfer al Pool).
3. Árbol con `fee_bps = 0`: sin transfer al admin.
4. Preimage: hash estable para el mismo (red, nonce, exp, invocation); cambia si cambia cualquier campo.
5. Firma verificable con la pública del pagador; falla con otra red.
6. Estimador L̂: casos τ' = 4,5 / 5 / 6 s y Δt = 1 h / 12 h; W dimensionado según §3.1.
7. Sobre JSON ↔ XDR: discrepancia en monto o destinatario → RECHAZADO.
8. Caché de nonces: segundo escaneo del mismo sobre → RECHAZADO(nonce usado).
9. Expirado localmente (exp ≤ L̂) → no se envía.
Integración (testnet, manual el domingo): sobre firmado en modo avión → pasarela web envía → hash; mismo
sobre reenviado → falla por nonce; sobre con exp pasado → falla por expiración.

**Hecha el 25-oct desde Kotlin** (`DeferredAuthTestnetIT`, apagada por defecto; se activa con variables de
entorno, sin secretos en el repo): la wallet semilla del emulador `GC4VHSZY…CQQEH` firmó sin red un sobre de
0,5 USDC + 2 % a Cafe Don Aurelio; una pasarela desechable fondeada por friendbot (`GCCXC2E2…2WRH`) reconstruyó
la invocación desde el XDR, simuló en modo enforce, pagó el fee y envió:
[`ebee152c…6d47`](https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47)
(ledger 5 384 473; fuente = pasarela, auth = pagador). El mismo sobre reenviado → `Error(Auth, ExistingValue)`;
expirado → `Error(Auth, InvalidInput)`; tope de expiración medido en la §3.1.

## 9. Lo que el deck dice de esto (una slide)

"El visitante firma sin señal; cualquier teléfono con señal liquida. La red garantiza unicidad y
expiración; el bolsillo del territorio acota el riesgo; el comercio siempre sabe qué tiene: una promesa
firmada o dinero confirmado." Sin la palabra "offline payments" a secas: siempre "pago diferido".

## 10. Relación con `raiz-confidential-stack` (Sobre del Barrio) y con la transparencia

- **Vocabulario único:** "sobre" = promesa firmada que viaja y se abre on-chain. Hoy, el sobre de pago
  diferido (firmado, en tránsito por el enjambre); mañana, el sobre de aporte cifrado (Confidential Tokens).
- **Transparencia del pago diferido:** queda garantizada en el momento de la liquidación por la propia red
  (tx con fuente = pasarela y auth = pagador, visible para siempre en cualquier explorador). No depende de
  ningún servicio propio.
- **Historia consultable más allá de 7 días** (lista "pagos liquidados en el territorio", ejecuciones del
  fondo sin la etiqueta "histórica"): la da `raiz-memory` (índice durable de `getEvents`, ya construido).
  Es v1: no entra en la demo ni en el alcance declarado de HackMeridian; se integra en el SOW de noviembre.
  Ojo de privacidad: indexa el evento `payment` tal cual (incluye la dirección del turista); no empeora lo
  que el RPC ya publica, pero refuerza el ítem "evento sin dirección del turista" de v1.
- **Selective disclosure ("Mi recibo", D-sender) y Confidential Tokens:** v2 del sobre cifrado; realizan
  el principio "demostrar sin exhibir" de la propuesta. Solo roadmap.
