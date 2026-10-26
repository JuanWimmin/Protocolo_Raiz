 y e# Guion del deck — HackMeridian 2026 (10 slides, plan B7)

> **Para qué sirve.** Es el guion de las 10 slides que fija `docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md` (B7: deck final
> el lunes 27-oct, 09:00–11:30, enviado antes de las 11:45). Por slide: título, lo que se dice en voz alta
> (3–5 frases, ≈ 20–30 s), qué va en pantalla y la fuente de cada cifra. Duración total ≈ 4–5 min.
> **Reglas que no se negocian:** se dice "pago diferido", nunca "pago offline" a secas (regla H5 del plan); no hay
> finalidad offline y el comercio tiene una promesa firmada hasta que llega el hash; el 2 % del Tip Barrio no se
> toca; el modelo de sostenibilidad está EN EVALUACIÓN y no lleva cifras; toda cifra lleva fuente; ningún secreto;
> nada sobre personas, socios, usuarios o ingresos que el repo no respalde (el SOW 2, `docs/SOW2_RAIZ_borrador.docx`,
> es un BORRADOR no versionado, no un compromiso vigente); nada se presenta como probado si el plan lo deja pendiente
> (Safari en iPhone, camino COBRO, auto-liquidación del sobre propio: evidencia §4).
> **Paleta** (CLAUDE.md, "Paleta UI"): negro `#1a1a1a`, amarillo `#FBBF24`, púrpura `#534AB7`, verde `#0F6E56`,
> fondo `#FAFAF7`. Track: **Stellar Unlocked** · builder track: **Scale**. Redactado el 2026-10-26; enlaces
> comprobados ese día (HTTP 200: repo, compare, release 0.4.0, raizapp.xyz, pasarela, health del relayer). Revisado el
> 2026-10-26 tras una revisión adversarial: 5 hallazgos aplicados (lista "Correcciones de la revisión" al final).

Fuentes abreviadas que se repiten abajo: **spec** = `docs/pago_diferido_v0.md` · **ARQ** = `docs/ARQUITECTURA_TECNICA.md`
§13 · **plan** = `docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md` · **evidencia** = `docs/evidencia_hackmeridian/README.md` ·
**enjambre.md** = `docs/Pago diferido por enjambre.md` · **propuesta** = `docs/NuevaPropuesta/propuesta_raiz_ahorro_enjambre.md` ·
**compare** = <https://github.com/JuanWimmin/Protocolo_Raiz/compare/hackmeridian-start...feat/pago-diferido>.

| # | Slide | Tiempo |
|---|---|---|
| 1 | El problema: la vereda sin señal | 25 s |
| 2 | Lo que RAÍZ ya hace (antes de la hack) | 30 s |
| 3 | El reto: sin señal no hay pago | 25 s |
| 4 | El pago diferido por enjambre: cuatro roles | 30 s |
| 5 | La pieza del protocolo | 30 s |
| 6 | Demo | 30 s |
| 7 | Límites declarados y cómo se acotan | 30 s |
| 8 | Modelo de sostenibilidad (en evaluación) | 25 s |
| 9 | Roadmap: lo siguiente ya está especificado (v1) | 25 s |
| 10 | Equipo y cierre | 20 s |

---

## Slide 1 — El problema: la vereda sin señal

**Título en pantalla:** *Una vereda sin señal. Una venta perdida.*

**Guion (≈ 25 s):**
Una vereda sin señal. Una artesana de Cota pierde una venta porque el datáfono no prendió. En el turismo rural de
América Latina —zonas sin señal, visitantes sin SIM local— eso pasa a menudo: el visitante quiere pagar, el comercio
quiere cobrar, y la red no está.
RAÍZ existe para que el valor del turismo se quede en el territorio que lo genera. Este fin de semana vinimos a
resolver algo previo: que ese valor pueda cobrarse aunque no haya señal.

**En pantalla:** una foto o ilustración de una vereda (sin personas identificables); la frase narrativa del equipo,
**sin comillas y sin atribuírsela a la artesana** (no es una cita): *Una artesana de Cota perdió una venta: el datáfono
no prendió*; logo RAÍZ y el lema "Tu paga, el barrio crece". Ninguna cifra de frecuencia ("todos los días", "X %"):
no existe en el repo.

**Fuentes:** la historia de la vereda y de la artesana de Cota es la apertura del guion de demo del proyecto
(enjambre.md §07 "La toma de la demo", 0–10 s; plan, B5 punto 2): es una **línea narrativa del equipo**, no una cita
de la artesana ni de una entrevista, por eso va sin comillas. "Pasa a menudo" es la hipótesis de problema del propio
proyecto (propuesta §5.3: "un dolor real del turismo LatAm: zonas sin señal, turistas sin SIM local"), sin cifra de
frecuencia en el repo: no decir "todos los días" ni poner números. El problema general del turismo extractivo:
`README.md` §1. **Pendiente:** en el repo solo está esa línea; si se quiere una cita textual de la artesana debe
salir de las entrevistas a comercios y residentes (carpeta de Drive enlazada en `README.md`) y con su
consentimiento. No inventar palabras que no estén grabadas.

---

## Slide 2 — Lo que RAÍZ ya hace (antes de la hack)

**Título en pantalla:** *RAÍZ ya corre en Stellar testnet — y todo esto existía antes del evento.*

**Guion (≈ 30 s):**
RAÍZ es una red de pagos turísticos sobre Stellar: cada pago manda un 2 % al fondo del barrio, los residentes
votan con tokens soulbound y la Tesorería ejecuta sin que nadie tenga la llave. Hoy son cinco contratos Soroban en
testnet, una app Android 0.4.0 que no lleva ninguna credencial dentro, un relayer de código abierto, depósitos
SEP-10 y SEP-24 para wallets semilla y passkey, y ocho ejecuciones del fondo con hash real. Todo esto estaba
hecho antes del 25 de octubre y está marcado con el tag `hackmeridian-start`.

**En pantalla:** cinco números grandes, cada uno con su enlace (como texto corto o QR): **5 contratos** ·
**85 tests** de contratos · **app 0.4.0** (0 credenciales) · **8 ejecuciones** con hash real · **SEP-10/24**
(video de 60 s). Abajo, en pequeño: "tag `hackmeridian-start` = `1361b38` (10-oct-2026)".

**Fuentes de cada cifra:**
- 5 contratos Soroban desplegados el 2026-07-31: `deployments.json`; tabla con enlaces a Stellar Expert en
  `README.md` §7 (Pool `CD775D33…KBE2`).
- 85 tests de contratos en CI: `README.md` §9; `CLAUDE.md` "Estado actual".
- App 0.4.0 sin credenciales: Release <https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.4.0>;
  CI `verify-apk`; `README.md` §6.
- 8 ejecuciones del fondo con hash real: `docs/evidencia_sow/README.en.md` (fila D2) y
  `docs/evidencia_sow/d2/ejecuciones_2026-09-12.md`.
- SEP-10 + SEP-24 para todas las wallets, video de 60 s y tx `e4603f18…`: `docs/evidencia_sow/README.en.md` (fila D3).
- Relayer en vivo: <https://raiz-relayer.fly.dev/v1/health> (`"ok":true`, `protocolVersion` 29 el 26-oct).
- Datos demo del testnet (3 barrios, 9 comercios, 9 residentes): `README.md` §8 (descripción de `seed_testnet.sh`).
- Tag `hackmeridian-start` = `1361b38`, 2026-10-10: evidencia (cabecera); `README.md` sección HackMeridian.

---

## Slide 3 — El reto: sin señal no hay pago

**Título en pantalla:** *Un pago en Stellar necesita la red en el momento de pagar — por cuatro cosas.*

**Guion (≈ 25 s):**
El pago en línea de hoy necesita señal justo al pagar por cuatro motivos: el número de secuencia del pagador, la
simulación de Soroban que calcula footprint y recursos, el nonce de la autorización que devuelve esa simulación, y
XLM para el fee. Prefirmar una transacción completa no sirve: fija la secuencia del visitante y sigue faltando la
simulación. Y los canales de pago, las carteras con enclave o las credenciales encadenadas exigen un contrato por
par, hardware que no hay en Android o un emisor central.

**En pantalla:** tabla de cuatro filas "Necesita · Por qué · En el pago diferido" (secuencia → la pone la
pasarela; simulación → la hace la pasarela; nonce → lo elige el teléfono; XLM → lo paga la pasarela). Debajo, la
tabla comparativa en una línea por enfoque: canales (Lightning, xLumi) · cartera con TEE (Jie et al., 2024) ·
credenciales encadenadas (Si et al., 2026) · **RAÍZ v0: ninguna preparación, nonce + expiración + tope**.

**Fuentes:** las cuatro dependencias y el diagrama de secuencia: enjambre.md §02 ("Por qué el pago en línea no
funciona sin señal"); la comparación con canales, TEE y credenciales encadenadas: misma sección, tabla
"Enfoque / Preparación / Doble gasto / Hardware"; referencias académicas: spec (cabecera) y enjambre.md "Fuentes".

---

## Slide 4 — El pago diferido por enjambre: cuatro roles

**Título en pantalla:** *El visitante firma sin señal. Cualquier teléfono con señal liquida.*

**Guion (≈ 30 s):**
El visitante firma sin señal la autorización del pago y la entrega como un sobre: un código QR. El comercio
verifica la firma sin red y muestra "en tránsito". Y cualquier teléfono con señal —el comercio, un vecino que lo
lleva, una página web, o el propio pagador cuando vuelve la cobertura— lo envía como fuente de la transacción y
paga el fee. La pasarela no necesita ser de confianza: la firma del pagador fija monto, comercio y aporte, y la
red garantiza que cada sobre se cobra una sola vez y que vence.

**En pantalla:** diagrama de los cuatro roles en tres columnas (SIN SEÑAL → SIN SEÑAL → CON SEÑAL): **Pagador**
(firma sin red) → QR → **Cobrador** (verifica sin red, guarda EN_TRANSITO) / **Portador** (transporta, no puede
alterar ni redirigir) → **Pasarela** (reconstruye la tx como fuente, simula en modo enforce, paga el fee, envía).
Pie: "auth = pagador · fuente = pasarela · el nonce se consume una sola vez". Logos de los tres caminos: la app,
`raizapp.xyz/pasarela.html`, `scripts/pasarela_cli.mjs`.

**Fuentes:** tabla de roles ("Hace / No puede"): spec §1; diagrama ASCII y tabla ampliada con COBRO/PORTADOR:
ARQ §13.2; resumen de una línea: `README.md` §3(f). Propiedad "la pasarela no necesita ser de confianza":
spec §1 y ARQ §13.2. El texto literal de la app: "Se liquida sola en cuanto tu teléfono, el del comercio o el de
cualquier usuario de RAÍZ que la lleve tenga señal" (spec §6; ARQ §13.5).

---

## Slide 5 — La pieza del protocolo

**Título en pantalla:** *Una `SorobanAuthorizationEntry` firmada sin red. Cero contratos nuevos.*

**Guion (≈ 30 s):**
La pieza es una entrada de autorización de Soroban. El pagador firma el hash de un preimage que lleva el id de
red, un nonce, el ledger de expiración y el árbol completo de invocación: `pay_merchant` más las tres
transferencias de USDC al comercio, al fondo y al protocolo. No firma la transacción ni el footprint; por eso
otra cuenta puede enviarla después. La red garantiza unicidad —el nonce se consume on-chain— y expiración por
ledger. No desplegamos ningún contrato: es `Pool.pay_merchant` tal como está en testnet desde julio.

**En pantalla:** el sobre dibujado por bloques: credenciales **232 B** (dirección, nonce, expiración, firma) +
invocación **680 B** = **912 B**; el árbol (`pay_merchant` → `transfer` × 3); la fórmula
`E = L̂ + W`, **W = 11 520 ledgers** (12 h garantizadas); "QR versión 29 · 1 229 caracteres · prefijo
`raiz:sobre:1:`"; abajo, los tres mensajes de la red: `ExistingValue` = nonce usado, `InvalidInput` = vencido,
`InvalidAction` = árbol alterado.

**Fuentes de cada cifra:** 912 B, 232 B, 680 B y 728 B del preimage: enjambre.md §04 y M2 (verificados contra el
fixture); 1 229 caracteres y QR v29 con corrección M: spec §5 y §3.4, enjambre.md M3; W = 11 520 y "12 h
garantizadas, ≈ 16 h con el cierre medido": spec §3.1 y ARQ §13.3; cierre de ledger 5,000 s en 199 ledgers y tope
`E − L ≤ 3 110 399`: spec §3.1; mensajes de error: spec §3.1 y §6; credenciales legacy (tipo 9) aceptadas en
Protocol 29: spec §2; árbol espejo de `contracts/pool/src/lib.rs` comprobado con `--verify-tree`: spec §2.1 y §7b;
"cero contratos nuevos": regla H2 del plan y evidencia (cabecera). Guía oficial: "Signing Soroban contract
invocations" (enjambre.md, Fuentes).

---

## Slide 6 — Demo

**Título en pantalla:** *Cuatro caminos de liquidación, cuatro transacciones, ninguna enviada por el pagador.*

**Guion (≈ 30 s):**
Esto es lo que probamos. El emulador con los datos apagados firma el sobre y muestra el QR con "válido hasta,
estimado". La pasarela pública en raizapp.xyz, abierta en cualquier navegador, pega o escanea el sobre, verifica la
firma sin red, liquida y devuelve el hash. En Stellar Expert la cuenta fuente es la pasarela y la autorización es del
pagador. Reenviar el mismo sobre falla por nonce. Y la app, actuando de portadora, liquidó un sobre sola, once
segundos después de recibirlo.

**La frase de la pasarela depende del video real (decidir el lunes, no antes):**
- **(A) Si el video se graba con el iPhone en Safari escaneando el QR del emulador** (plan H4 y B5), se puede decir
  "Esto es lo que grabamos" y "la pasarela pública en raizapp.xyz lo escanea desde un iPhone, sin la app".
- **(B) Si no se graba así**, queda la frase de arriba ("abierta en cualquier navegador, pega o escanea el sobre") y en
  pantalla la captura a ancho de teléfono que guarda `scripts/pasarela_web_e2e.mjs` (Chrome headless).
- En ningún caso afirmar como probado lo que el plan deja pendiente: **Safari en iPhone (B3), el camino COBRO con una
  wallet de comercio y la auto-liquidación de un sobre propio firmado sin señal (B4)** siguen sin probar (evidencia §4;
  ARQ §13.6; plan, resultados de B3 y B4). Lo probado y documentado es: emulador Android (wallet semilla) y la pasarela
  web en Chrome headless por CDP.

**En pantalla:** tres o cuatro capturas (QR del sobre en el emulador · pasarela con el hash — hoy, la captura headless
de `scripts/pasarela_web_e2e.mjs`; la del iPhone solo si se hace la prueba (A) · Stellar Expert con fuente ≠ pagador ·
"Recibidos: Liquidado"), un **QR al video (PENDIENTE)** y los cuatro hashes acortados con su enlace: `ebee152c…` (B1, Kotlin) · `cec2388a…` (pasarela de terminal) · `27c61461…` (pasarela web) ·
`79a4c858…` (portador desde la app). Pie: "testnet · fuente ≠ pagador · auth = pagador".

**Fuentes:** tabla de las cuatro transacciones con ledger, fecha, fuente, pagador, comercio, USDC movido y fee:
evidencia §2; enlaces:
<https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47> (ledger 5 384 473),
<https://stellar.expert/explorer/testnet/tx/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34> (5 388 242),
<https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47> (5 388 486),
<https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2> (5 391 483).
"Liquidado en 11 s" como portador: plan, resultado de B4. Capturas: nombres sugeridos en evidencia §5
(`capturas/02_sobre_qr.png`, `05_pasarela_liquidado_hash.png`, `06_stellar_expert_fuente_distinta.png`,
`09_recibidos_liquidado.png`) — **PENDIENTES: hoy no existen**. Video de 75 s: guion en plan B5 y enjambre.md §07;
destino previsto `docs/evidencia_hackmeridian/video/hackmeridian_pago_diferido_75s.mp4` y copia en
`raizapp.xyz/evidencia/` (evidencia §5) — **PENDIENTE**; cualquier toma con teclado pasa antes por
`scripts/mask_video_autofill.py` (regla H7). Nota de honestidad para quien presente: la cadena no prueba el
momento de firmar sin señal; eso lo prueban el código sin dependencia de red y el video (evidencia §2, último párrafo).
Lo probado hasta hoy es emulador Android + Chrome headless por CDP (evidencia §4, "Not tested on a physical device this
time"; ARQ §13.6, "Pendiente: la aceptación manual desde Safari en un iPhone"); la pasarela "en Safari" y el video son
del plan (H4, B5), no hechos todavía.

---

## Slide 7 — Límites declarados y cómo se acotan

**Título en pantalla:** *"En tránsito" es una promesa firmada, no dinero confirmado.*

**Guion (≈ 30 s):**
Lo decimos con estas palabras: no hay finalidad offline. Mientras el sobre no llega a la red, el comercio tiene
una promesa firmada, no dinero. El doble gasto es posible dentro de la ventana y se acota con tres cosas:
expiración por ledger —doce horas garantizadas—, un tope por cobro de veinte dólares por defecto, y la identidad
firmada del pagador. En la versión uno, el bolsillo prefondeado del territorio acota la pérdida agregada a lo que
exceda del bolsillo. Y "Cobrado" solo aparece con un hash cuya autorización sea exactamente ese sobre.

**En pantalla:** tabla corta "Ataque → Defensa → v0 / v1": falsificar el sobre → todo va firmado (v0) · replay →
nonce consumido on-chain (v0) · doble gasto → expiración + tope 20 USDC + identidad firmada (v0 parcial) /
bolsillo prefondeado (v1) · portador malicioso → solo puede retrasar; replicar es gratis (v0) · leer quién pagó →
sobre cifrado (v1). Fórmula del bolsillo: `pérdida agregada ≤ max(0, Σ sobres − P)`. Frase fija de la UI:
«"En tránsito" es una promesa firmada, no dinero confirmado».

**Fuentes de cada cifra:** "no hay finalidad offline" y las tres cotas: spec §0 y §3.2; tope por cobro 20 USDC
por defecto (`TOPE_DEFAULT_STROOPS = 200_000_000`): spec §6, ARQ §13.4 y §13.5; 12 h garantizadas / ≈ 16 h hoy:
spec §3.1; fórmula del bolsillo `max(0, Σ A_i − P)`: spec §3.2; tabla de amenazas: spec §4; regla "Cobrado solo
con hash cuya auth sea el sobre" y `ExistingValue` ≠ cobrado: spec §6, ARQ §13.5, enjambre.md §A4; entrega por
portadores `P = 1 − (1 − q)^k` (q = 0,6: k = 1 → 60 %, k = 2 → 84 %, k = 3 → 93,6 %): spec §3.3; frase literal de la
UI: ARQ §13.1 (`ui/deferred/DeferredListScreen.kt`); lista completa de límites (passkey sin firma offline,
retención del RPC ≈ 7 días, sin dispositivo físico esta vez, credenciales legacy, fee de testnet no es modelo de
costos, deuda del fee descontado al comercio): evidencia §4 y ARQ §13.8.

---

## Slide 8 — Modelo de sostenibilidad (en evaluación)

**Título en pantalla:** *El 2 % no se toca. El resto está en evaluación.*

**Guion (≈ 25 s):**
El modelo de sostenibilidad está en evaluación y lo decimos así. Una regla no se toca: el 2 % del Tip Barrio va
íntegro al fondo del barrio. Hoy los contratos llevan un fee de protocolo del 0,5 % al administrador, y tenemos
registrado como deuda que se descuenta del monto del comercio. Las opciones que evaluamos son un cargo a cargo
del visitante y el apoyo voluntario; el pago diferido añade la pregunta de quién paga el fee de la pasarela. Sin
cifras hasta medir en mainnet y hasta tener la validación de campo.

**En pantalla:** tres bloques: **"2 % → fondo del barrio (no se toca)"** · **"0,5 % fee de protocolo hoy → admin
(deuda registrada: se descuenta al comercio; contratos v2)"** · **"En evaluación: cargo a cargo del visitante ·
apoyo voluntario · quién paga el fee de la pasarela"**. Pie: "Sin proyecciones de ingresos".

**Fuentes:** regla "en evaluación; el 2 % no se toca; opciones a cargo del visitante y apoyo voluntario": plan B6;
2 % (200 bps) al fondo: `README.md` §1 y §4; `protocol_fee_bps = 50` al admin: `deployments.json` y `README.md`
§7; deuda "el Pool descuenta el fee del monto del comercio (`to_m = amount − fee`)": spec §2.1, ARQ §13.8; "quién
paga el fee de la pasarela (voluntaria, comercio, relayer) es parte del modelo en evaluación" y los fees medidos
(28,65 XLM el primer pago del turista en el barrio; ≈ 0,0023 XLM los siguientes; no prometer costos hasta
mainnet): spec §3.1, evidencia §2 (nota del fee); opción "el fee de protocolo financia la infraestructura del
enjambre": propuesta §5.5 (es una propuesta, no una decisión); una recomendación del modelo de ingresos basada en
validación de campo está **prevista en el borrador** del SOW 2 (`docs/SOW2_RAIZ_borrador.docx`, D4; borrador no
versionado, no un compromiso vigente). **No poner cifras de ingresos, usuarios ni socios: no existen en el repo.**

---

## Slide 9 — Roadmap: lo siguiente ya está especificado (v1)

**Título en pantalla:** *Lo siguiente ya está especificado: la versión uno.*

**Guion (≈ 25 s):**
Lo siguiente ya está especificado como versión uno y lo proponemos para el próximo sprint: el bolsillo prefondeado
del territorio; Key Attestation para que una passkey sincronizada o un segundo teléfono no puedan firmar sobres; portadores por
Bluetooth; el sobre cifrado, para que quien lo lleva no lea quién pagó a quién; el relayer como pasarela; y
SEP-45 para que las wallets passkey firmen y liquiden desde la app. Detrás está el roadmap del protocolo de ahorro
comunitario: cadena de barrio, custodia de enjambre, metas, enjambre frontera y voto secreto.

**En pantalla:** línea de tiempo en tres tramos: **v0 — hoy (hecho):** firma sin red, sobre QR, pasarela pública,
cobrador y portador con liquidación automática · **v1 — propuesto para el próximo sprint (spec §7):** bolsillo
(`pocket`) · Key Attestation · portadores BLE / Wi-Fi Direct · sobre cifrado (X25519) · relayer como pasarela (`POST /v1/deferred-payment`) ·
SEP-45 + passkey firmando offline · iOS vía KMP · historia > 7 días (`raiz-memory`) · **F2–F6:** Cadena de Barrio ·
custodia de enjambre + atestación vecinal · metas/retos/sorteo · enjambre frontera · voto secreto ZK. Marcar
"F1 Blend directo: hecho (31-jul-2026)".

**Fuentes:** lista "Fuera de v0 (v1 / SOW de noviembre)": spec §7; `raiz-memory` e historia más allá de 7 días:
spec §10; límites y deuda que motivan cada ítem: ARQ §13.8; gossip de portadores y decisión "enjambre abierto o
sellado": enjambre.md §A5; bolsillo y su cota: spec §3.2; Key Attestation: spec §4; SEP-45 (WP7):
`docs/PLAN_CLAUDE_CODE_SOW.md` §WP7 y `README.md` §9; roadmap F1–F6: propuesta §8 y `README.md` §9; F1 hecha el
2026-07-31: `README.md` §9; F2 en pausa hasta entregar la evidencia: `CLAUDE.md`.
**Ojo con el "SOW de noviembre":** la spec (§7, §10) llama así a la v1, pero el único documento real es un **BORRADOR
no versionado** (`docs/SOW2_RAIZ_borrador.docx`, fechado 13-oct-2026; su aprobación no consta en el repo). Sus
entregables D1–D4 (subidas del 19-oct al 9-nov) son territorio, i18n ES/EN, `savings_circle` v0 y validación de campo;
su "Out-of-Scope" dice literalmente "offline payments (the 'Enjambre' store-and-forward proposal — design note only this
sprint)", su anexo A.3 lo lista como "Design note this sprint; PoC next phase" y su D3 preveía construir en HackMeridian
el protocolo de ahorro, no el pago diferido. La hack adelantó el Enjambre a v0 y el equipo propondrá actualizar ese
alcance. En el deck **no** decir que el SOW está vigente ni que contiene la v1: decir "propuesto para el próximo sprint"
y, si se menciona el SOW 2, "borrador".

---

## Slide 10 — Equipo y cierre

**Título en pantalla:** *Todo se verifica en un clic.*

**Guion (≈ 20 s):**
Somos un equipo pequeño: contratos, backend y Android por un lado; producto y salida al mercado por el otro, con
tres entrevistas de descubrimiento hasta hoy y la validación de campo por delante. Todo lo que mostramos se verifica en
un clic: el repositorio, el diff del evento, los cuatro hashes y la pasarela pública que cualquiera puede abrir ahora
mismo. Gracias.

**En pantalla:** nombres y roles — **pendiente: confirmación explícita de cada persona antes de ponerlos en el deck o
en el portal** (hasta entonces, el marcador "pendiente: nombres y roles"). Lo único que hay en los documentos: el
borrador del SOW 2 §1 nombra a dos integrantes con los roles contratos/backend/Android y producto/go-to-market (Juan
Pablo Conrado Molina y Diana Sofía Durán Samacá); el README no nombra a nadie. Sin ubicación ni datos de contacto.
GitHub `JuanWimmin`. Dos QR:
el repo (<https://github.com/JuanWimmin/Protocolo_Raiz>) y la pasarela (<https://raizapp.xyz/pasarela.html>).
Pie: "Lisboa, 25–26 oct 2026 · Stellar Unlocked · Scale".

**Fuentes:** nombres y roles: `docs/SOW2_RAIZ_borrador.docx` §1 "Builder / Team Name" (borrador, no versionado); el
deck lo arma producto (plan B7; la ubicación que da el plan no se pone: es un dato personal que no aporta); equipo de
2–3 personas con roles protocolo / app / producto y comunidad: `docs/NuevaPropuesta/plan_trabajo_raiz.md` "Roles";
tres entrevistas hasta hoy: `docs/evidencia_sow/closeout_sprint1_respuestas_EN.md` ("General Comments and Outcomes");
titular de la licencia "Protocolo RAÍZ (JuanWimmin)": `LICENSE`. **Pendiente: cada persona confirma su nombre
completo y su rol tal como deben aparecer en el portal; sin esa confirmación el campo Team y la slide llevan el
marcador. No añadir nadie que no esté en los documentos; no decir "construimos con la comunidad" (hoy hay tres
entrevistas).**

---

## Lista de verificación de la submission (tomada del plan, con el estado real al 2026-10-26)

| # | Punto del plan ("Checklist de la submission") | Estado | Dónde / enlace |
|---|---|---|---|
| 1 | Repo público con el código → `Protocolo_Raiz` (+ `raiz-relayer`) | **Hecho.** Rama `feat/pago-diferido` pusheada (`origin/feat/pago-diferido` = `4d287d3`); tag `hackmeridian-start` en origin (`1361b38`) | <https://github.com/JuanWimmin/Protocolo_Raiz/tree/feat/pago-diferido> · <https://github.com/JuanWimmin/raiz-relayer> |
| 2 | README: resumen, problema, setup, instalación, variables de entorno, instrucciones de demo (sección EN) | **Hecho salvo el video.** Sección EN "HackMeridian 2026 — what we are building here" + "Deferred payment — what shipped" (`README.md` líneas 12–28); setup/instalación/variables en `README.md` §8 (ES); pasos de demo en EN en evidencia §3.d. La línea "Demo video: pending" sigue pendiente | `README.md` · `docs/evidencia_hackmeridian/README.md` §3 |
| 3 | Arquitectura: cómo usa Stellar, assets, IDs de contratos, anchors, wallets, integraciones | **Hecho.** ARQ §13 (roles, pieza del protocolo, componentes, máquina de estados, pasarelas, pruebas, límites) con los IDs de `deployments.json`; resto de la arquitectura en ARQ §1–§12 y `README.md` §2 | `docs/ARQUITECTURA_TECNICA.md#13-pago-diferido-por-enjambre-hackmeridian-2026` |
| 4 | Demo en video + URL pública explorable (`raizapp.xyz` + `raizapp.xyz/pasarela.html`) | **Parcial.** URLs públicas vivas (HTTP 200 el 26-oct; copia publicada idéntica byte a byte al repo, evidencia §1 B3). **Video PENDIENTE** (guion de 75 s en plan B5 y enjambre.md §07; nombre previsto `video/hackmeridian_pago_diferido_75s.mp4`, evidencia §5) | <https://raizapp.xyz> · <https://raizapp.xyz/pasarela.html> |
| 5 | Evidencia de tx on-chain (hash del pago diferido + las 8 ejecuciones previas) | **Hecho.** 4 hashes del pago diferido releídos en Horizon el 26-oct (evidencia §2) + 8 ejecuciones del fondo (`docs/evidencia_sow/README.en.md`, `d2/ejecuciones_2026-09-12.md`) | evidencia §2 · `docs/evidencia_sow/README.en.md` |
| 6 | Prior work vs. hackathon work: tag `hackmeridian-start` y diff enlazado | **Hecho.** Enlazado en `README.md` (sección EN) y en evidencia (cabecera); 27 commits en la rama al 26-oct (26 de feature hasta `a782f51` + docs `4d287d3`); `main` sigue en el tag: el diff contra `main` está vacío hasta mergear (`README.md`) | compare: <https://github.com/JuanWimmin/Protocolo_Raiz/compare/hackmeridian-start...feat/pago-diferido> |
| 7 | Scale: alcance claro, arquitectura de lo nuevo, modelo de sostenibilidad, próximos pasos, deck | **Parcial.** Alcance (spec §0, §7), arquitectura (ARQ §13), próximos pasos (spec §7, §10) y respuestas en EN (`submission_answers_EN.md`, este paquete): hechos. Sostenibilidad: documentada como EN EVALUACIÓN, sin cifras (plan B6; `submission_answers_EN.md`). **Deck: PENDIENTE** (este guion es el insumo; slides a cargo de Diana, plan B7) | `docs/evidencia_hackmeridian/submission_answers_EN.md` · este archivo |
| 8 | Track temático guardado en la submission: **Stellar Unlocked** | **Pendiente** (acción en el portal al enviar; builder track **Scale**) | plan, cabecera y "Checklist" |
| 9 | Todos los enlaces abiertos en incógnito antes de enviar | **Pendiente** (el 26-oct se comprobaron por HTTP: repo, compare, release 0.4.0, landing, pasarela, health del relayer, video SOW D3 y YouTube; falta la pasada en incógnito del lunes y añadir los enlaces nuevos: video de la hack y, si se mergea, el compare contra `main`) | plan B7 |

### Pendientes del equipo antes de las 11:45 del lunes

1. **Video de 75 s** (plan B5; evidencia §5): grabar emulador sin datos + pasarela; subtítulos EN; pasar por
   `scripts/mask_video_autofill.py` cualquier toma con teclado; subir a `docs/evidencia_hackmeridian/video/` y a
   `raizapp.xyz/evidencia/`; enlazarlo en `README.md` (línea "Demo video: pending"), en evidencia §5 y en la slide 6.
2. **Capturas** `docs/evidencia_hackmeridian/capturas/01…11` (nombres en evidencia §5) y su enlace en la slide 6.
3. **Deck** de 10 slides con este guion (Diana, plan B7); QR del video cuando exista.
4. **Confirmar equipo** (nombres, roles, integrantes) **con cada persona** para la slide 10 y el campo Team; hasta
   entonces, el marcador "pendiente: nombres y roles". Sin ubicación ni datos de contacto.
5. **Pruebas que el plan deja abiertas** (evidencia §4): Safari en iPhone escaneando el QR del emulador (B3),
   camino COBRO con una wallet de comercio y auto-liquidación de un sobre propio firmado sin señal (B4). Si no se
   hacen, dejarlas declaradas como hoy; no afirmar que están probadas.
6. **Decidir si se mergea `feat/pago-diferido` a `main`** antes de enviar: hoy `main` = tag, y el README avisa que el
   diff contra `main` está vacío hasta el merge; el compare enlazado (`hackmeridian-start...feat/pago-diferido`)
   funciona igual en ambos casos.
7. Marcar **Stellar Unlocked** (track temático) y **Scale** (builder track) en el portal; abrir todos los enlaces
   en incógnito.
8. **Slide 6, elegir la versión (A) o (B)** según cómo se grabe el video: solo si el iPhone en Safari escanea el QR del
   emulador se dice "lo escanea desde un iPhone"; si no, "abierta en cualquier navegador, pega o escanea" con la
   captura headless.
9. **Slide 9 y respuestas:** el SOW 2 se menciona, si acaso, como borrador; la v1 es "propuesta para el próximo
   sprint". Nunca "SOW vigente" ni "current sprint".

### Correcciones de la revisión adversarial del 2026-10-26 (aplicadas aquí y en `submission_answers_EN.md`)

1. **SOW 2 / "SOW de noviembre"** (gravedad alta): la v1 ya no se presenta como contenido de un SOW vigente; el borrador
   real (`docs/SOW2_RAIZ_borrador.docx`) no la contiene y clasifica el Enjambre como "design note only this sprint".
2. **Cita de la artesana de Cota** (media): pasa a frase narrativa del equipo, sin comillas ni atribución; "pasa todos
   los días" → "pasa a menudo", sin cifra.
3. **Slide 6 / demo** (media): condicionada al video real; Safari en iPhone, camino COBRO y auto-liquidación del sobre
   propio quedan declarados como no probados.
4. **Equipo** (media): marcador hasta confirmación explícita de cada persona; sin "desde Bogotá"; sin "construimos con la
   comunidad".
5. **"Pay even without signal"** (media): en la submission pasa a "start a payment even without signal — a deferred
   payment", "accept offline payments" → "accept deferred payments", y cada etiqueta de la UI en español lleva glosa en
   inglés la primera vez.
