# RAÍZ · Instaward SOW — Informe del primer mes y guía de evidencias

| | |
|---|---|
| **Proyecto** | RAÍZ (Protocolo Raíz) — red de pagos turísticos sobre Stellar con fondo comunitario gobernado por los residentes |
| **Equipo** | Juan Pablo Conrado Molina (contratos / backend / Android) · Diana Sofía Durán Samacá (producto / diseño) |
| **Capítulo** | Colombia Chapter — Ambassador Lead: Laura Estupiñán |
| **SOW** | Enviado el 15-jul-2026 · 3 entregables · USD 5.000 en XLM |
| **Ventana de ejecución** | 27-ago → 4-oct-2026 · Red: **Stellar testnet** |
| **Este informe** | 2026-10-05 · Índice completo del paquete: [`docs/evidencia_sow/`](https://github.com/JuanWimmin/Protocolo_Raiz/tree/main/docs/evidencia_sow) ([English](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/README.en.md)) |

---

## 1. Resumen ejecutivo

**Los tres entregables del SOW están completos, en vivo y verificables con un clic.** El bloqueador
declarado de mainnet (la clave del admin dentro del APK) quedó eliminado y además **revocado
on-chain**; cada gasto del fondo comunitario enlaza a su transacción real en Stellar Expert; y un
turista puede completar un depósito SEP-10 + SEP-24 contra el anchor de prueba del SDF desde la
app — **con cualquier tipo de wallet, incluidas las passkey**, algo que el SOW no exigía.

| Entregable | Comprometido (SOW §4.1) | Estado | Completado |
|---|---|---|---|
| **D1 — Admin Relayer** | Backend open-source que firma las operaciones de admin; APK sin secretos verificable por descompilación | ✅ Completo, con extras: APK **sin ninguna credencial** (ni API key) y clave antigua **revocada on-chain** | 6-sep (final 4-oct) |
| **D2 — Tx reales en el dashboard** | Cada ejecución del fondo enlazada a su transacción real (≥3 enlaces) | ✅ Completo: **8 ejecuciones** enlazadas, en la app y en la landing pública | 19-sep (8/8: 4-oct) |
| **D3 — On-ramp SEP-10 + SEP-24** | Depósito interactivo contra el anchor de prueba del SDF, video + tx hash | ✅ Completo, con extras: **todas las wallets** (semilla y passkey) y **conversión automática** al USDC del fondo | 3-oct (passkey: 4-oct) |

Todo el paquete es público: no exige instalar nada, crear cuentas ni conocimientos técnicos.
La verificación completa toma ~10 minutos (sección 4).

## 2. Lo realizado en el primer mes (cronología)

El plan semanal del SOW (§5.1) se ejecutó así — cada hito tiene commits públicos y evidencia fechada:

| Semana | Plan del SOW | Lo que pasó (con fechas) |
|---|---|---|
| **27-ago → 5-sep** | W1: construir y desplegar el relayer | Revisión técnica integral del repo + plan de sprint publicados (27-ago). Relayer `raiz-relayer` construido desde cero: TypeScript + Fastify + stellar-sdk oficial, cola serializada de firmas, idempotencia, rate-limits, CI y suite de integración contra testnet (27-ago). Fix de la landing pública que aún mostraba contratos pre-redeploy |
| **6-sep → 12-sep** | W2: migrar la app, APK sin secretos | Relayer **desplegado en Fly.io** y smoke real on-chain (6-sep). App 0.2.0 migrada: faucet, alta de comercio, soulbound de residente y tesorería pasan por el relayer; `DEMO_ADMIN_SECRET` eliminado del APK (PR #1, 6-sep). **Regresión aprobada en un Motorola G04 físico** (6-sep). Release v0.2.0 publicada con verificación por descompilación (12-sep). Siembra anticipada de las propuestas de evidencia D2 (6-sep) |
| **13-sep → 19-sep** | W3: tx reales + arranque SEP-10 | Código D2: la app captura el hash real al ejecutar y correlaciona eventos del Treasury vía RPC `getEvents`; dashboard con chip "Ver en Stellar Expert"; archivo versionado de hashes para ejecuciones que salen de la ventana de retención del RPC. Ejecuciones **#5 y #6 disparadas desde la app** en el dispositivo físico; landing pública con el bloque "Ejecuciones del fondo" en vivo (19-sep). Revisión adversarial independiente: 10 defectos encontrados y corregidos antes de publicar |
| **20-sep → 27-sep** | W4: completar SEP-24 | Código D3 completo y mergeado (27-sep): SEP-1 + SEP-10 + SEP-24 contra `testanchor.stellar.org` con el SDK que la app ya usaba; pantalla de depósito con 10 fases, trustline automática, Custom Tab, polling con backoff, JWT solo en memoria. Segunda revisión adversarial aplicada |
| **28-sep → 4-oct** | Evidencia y cierre | **Depósitos reales grabados**: 8 depósitos end-to-end el 3–4-oct, video de 60 s con wallet passkey, capturas y hashes. Extras del cierre: depósito para **todas las wallets** + **conversión automática** al USDC del fondo; **rotación on-chain de la clave del admin** (la expuesta en el APK del hackathon quedó con peso 0); relayer 0.3.0 **público sin API key** (cupos por IP); app 0.4.0 con **cero credenciales**; workflow público `verify-apk` que re-verifica el APK en CI; ejecuciones #7 y #8 desde la app; paquete de evidencia bilingüe ES/EN |

**Cifras del mes:** 2 repos nuevos/renovados públicos · app 0.1.0 → 0.4.0 (4 releases) · relayer
0 → 0.3.0 con **200 tests** · 85 tests de contratos en verde · 8 ejecuciones del fondo + 8 depósitos
SEP-24 reales on-chain · 3 revisiones adversariales independientes · >60 capturas y 4 videos de evidencia.

## 3. Evidencias por entregable (qué pegar / qué adjuntar)

### D1 — Admin Relayer Service

| Evidencia (SOW §6.1) | Enlace |
|---|---|
| Repositorio open-source del relayer | https://github.com/JuanWimmin/raiz-relayer |
| Servicio en vivo (endpoint público) | https://raiz-relayer.fly.dev/v1/health |
| APK release descargable (0.4.0, sin credenciales) | https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.4.0/raiz-0.4.0.apk — SHA-256 `296f30d8…2569d0` ([notas del release](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.4.0)) |
| Doc de verificación por descompilación (1 página) | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/verificacion_apk.md |
| Verificación **reproducible en CI pública** | https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml (annotation: "claves privadas válidas: 0") |
| Captura del endpoint vivo | [d1_relayer_health_2026-10-04.png](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/capturas/d1_relayer_health_2026-10-04.png) |
| Capturas de los flujos admin en dispositivo físico | [regresion_dispositivo.md](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/regresion_dispositivo.md) |
| Rotación de la clave (prueba on-chain) | [Cuenta admin en Stellar Expert](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P) → *Signers*: la clave nueva `GB42NCO6…` con peso 1; la que estuvo en el APK, con **peso 0** |

### D2 — Real Transaction Linking

| Evidencia | Enlace |
|---|---|
| Dashboard público en vivo | https://raizapp.xyz/#demo (bloque "Ejecuciones del fondo · Treasury", 8 filas enlazadas) |
| 3 transacciones directas en Stellar Expert | [#5 Centro](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) · [#6 Norte](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) · [#4 Norte](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3) |
| Captura del dashboard de la app con los enlaces | [10_norte_dashboard…png](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png) |
| Detalle completo (las 8 ejecuciones, verificación en incógnito) | [d2/ejecuciones_2026-09-12.md](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/ejecuciones_2026-09-12.md) |

### D3 — SEP-10 + SEP-24 On-Ramp

| Evidencia | Enlace |
|---|---|
| **Video de 60 segundos** (wallet passkey, sin cortes) | https://raizapp.xyz/evidencia/d3_deposito_passkey_60s.mp4 |
| Tx hash del pago del anchor | https://stellar.expert/explorer/testnet/tx/e4603f18ed5906a42672d19b8f78c942758fc8e61367a0ba5b062204d3fb9357 |
| Las otras 2 tx del mismo depósito (conversión + envío a la wallet) | [conversión](https://stellar.expert/explorer/testnet/tx/aacce26a5bb0f2bf52cec6d8893f76ac7a0c48a789a32c74bcf37a28e103561f) · [envío](https://stellar.expert/explorer/testnet/tx/90df876cdb62d505470b24709598cd80f7fc50af247604866d286fd093ceb1d7) |
| Captura "¡Depósito recibido!" con los 3 hashes | [12_passkey_deposito…png](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d3/capturas/12_passkey_deposito_recibido_tres_transacciones.png) |
| Detalle completo (8 depósitos, hallazgos del anchor, guion del video) | [d3/README.md](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d3/README.md) |

## 4. Cómo verificarlo en ~10 minutos (sin conocimientos técnicos)

1. **D1 (4 min).** Abrir [`/v1/health`](https://raiz-relayer.fly.dev/v1/health) → `"ok": true`,
   `"network": "testnet"`. Abrir la [última ejecución de `verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)
   → verde, con la anotación "claves privadas válidas: 0". Abrir la
   [cuenta del admin](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P)
   → en *Signers*, la clave que viajaba en el APK del hackathon tiene peso 0 (revocada).
2. **D2 (3 min).** Abrir [raizapp.xyz/#demo](https://raizapp.xyz/#demo) → bloque "Ejecuciones del
   fondo" → clic en cualquiera de las 8 filas → Stellar Expert muestra **Successful** y la llamada
   `execute_proposal(n)`.
3. **D3 (3 min).** Ver el [video de 60 s](https://raizapp.xyz/evidencia/d3_deposito_passkey_60s.mp4)
   → abrir el [hash del depósito](https://stellar.expert/explorer/testnet/tx/e4603f18ed5906a42672d19b8f78c942758fc8e61367a0ba5b062204d3fb9357)
   → **Successful**, transfer de 4,5 USDC del anchor a la wallet del teléfono.

La guía extendida, con capturas por paso, vive en el índice del paquete:
[`docs/evidencia_sow/README.md`](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/README.md).

## 5. Más allá del alcance comprometido

El SOW pedía quitar la clave admin del APK; el cierre del mes dejó un estándar más alto, sin
tocar el presupuesto:

1. **Cero credenciales en el APK** — ni clave privada ni API key (desde 0.4.0 el relayer es
   público con cupos por IP; la autenticación por wallet SEP-10/SEP-45 ya está planificada como
   WP7, fuera del SOW).
2. **La clave comprometida no solo se reemplazó: se revocó on-chain** (peso 0), con las
   transacciones de rotación documentadas y verificables.
3. **Verificación reproducible, no confiable**: cualquier persona puede re-ejecutar la
   comprobación "0 claves en el APK" en la CI pública de GitHub, sin instalar nada.
4. **D2 con 8 ejecuciones en vez de 3**, cuatro de ellas disparadas desde la app en un teléfono
   real, y un mecanismo para que los enlaces sobrevivan a la ventana de retención de 7 días del
   RPC de testnet (archivo versionado de hashes).
5. **D3 para todas las wallets**: las passkey (smart accounts C…) depositan vía una cuenta de
   depósito local y la app convierte automáticamente el USDC del anchor al USDC del fondo con una
   guarda de precio (≥97 %), dejando el saldo utilizable en la wallet — el SOW solo pedía el flujo
   básico.
6. **Calidad de proceso**: 3 revisiones adversariales independientes antes de publicar evidencia;
   paquete bilingüe ES/EN; página pública de evidencia en la landing.

## 6. Notas honestas y límites (lo que un revisor debe saber)

- **Todo corre en testnet**; no hay dinero real. Si testnet se reiniciara, los enlaces de Stellar
  Expert dejarían de resolver — por eso cada entregable conserva capturas y hashes versionados.
- **El APK va firmado con la clave de depuración de Android** (las wallets passkey están atadas a
  ella); la firma de tienda llega con mainnet, fuera del SOW. Esa firma no es una clave de Stellar
  ni da acceso a fondos.
- **El USDC del anchor de prueba es un asset distinto** del USDC del fondo; la conversión
  automática usa un pool de liquidez de testnet que RAÍZ no controla y solo ejecuta si el precio
  pasa la guarda.
- **Los videos se grabaron operando el teléfono por cable (`adb`)**, no con el dedo; app, anchor y
  transacciones son reales. Se tapó la franja de sugerencias del teclado por privacidad.
- **Los contratos del Annex A del SOW** son los del hackathon (15-jul); el 31-jul el protocolo se
  re-desplegó para rendir directo en Blend v2. La tabla vieja→nueva está en el
  [índice del paquete](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/README.md#nota-de-redeploy-los-contratos-del-annex-a-ya-no-son-los-vigentes).
- **Mantenimiento**: el estado on-chain de testnet caduca si nadie lo renueva; está renovado hasta
  comienzos de diciembre de 2026.

## 7. Siguiente paso (SOW §7)

Con los tres bloqueadores técnicos eliminados, el siguiente paso declarado en el SOW sigue vigente:
**aplicar al SCF Build Award** — piloto real en un barrio turístico de Cartagena, SEP-12/atestación
de residencia, auditoría de contratos y anchor local — con F2 ("Cadena de Barrio", ahorro
comunitario tipo tanda con identidad soulbound) como primera capa nueva sobre el protocolo ya
desplegado. El equipo queda igualmente elegible para un follow-on Instaward si el capítulo lo
considera útil como puente.

## 8. Índice maestro de enlaces

| Qué | URL |
|---|---|
| Monorepo (contratos + app + evidencia) | https://github.com/JuanWimmin/Protocolo_Raiz |
| Relayer open-source | https://github.com/JuanWimmin/raiz-relayer |
| Paquete de evidencia (índice ES / EN) | https://github.com/JuanWimmin/Protocolo_Raiz/tree/main/docs/evidencia_sow |
| Landing con datos on-chain en vivo | https://raizapp.xyz |
| Página de evidencia en la landing (videos) | https://raizapp.xyz/evidencia/ |
| APK 0.4.0 (descarga directa) | https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.4.0/raiz-0.4.0.apk |
| Relayer en vivo | https://raiz-relayer.fly.dev/v1/health |
| Verificación del APK en CI pública | https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml |
| Video demo general del producto | https://www.youtube.com/watch?v=y-9pglgVnnA |

---

## Executive summary (English)

All three deliverables of the RAÍZ Instaward SOW are **complete, live on Stellar testnet, and
verifiable in one click** (~10 minutes, no technical expertise required — full English index
[here](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/README.en.md)).

**D1 — Admin Relayer.** The admin private key no longer ships in the APK: admin operations are
signed server-side by an open-source relayer ([repo](https://github.com/JuanWimmin/raiz-relayer),
MIT, 200 tests, [live service](https://raiz-relayer.fly.dev/v1/health)). Beyond scope: the release
APK (v0.4.0) now contains **zero credentials of any kind**, the verification is reproducible in a
[public CI workflow](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)
("valid private keys: 0"), and the previously exposed key was **revoked on-chain** (weight 0 on the
[admin account](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P)).

**D2 — Real transaction linking.** Every community-fund execution links to its real Stellar
transaction — **8 executions** (SOW asked for 3), visible in the app and on the
[public dashboard](https://raizapp.xyz/#demo), e.g.
[#5](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) ·
[#6](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) ·
[#4](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3).

**D3 — SEP-10 + SEP-24 on-ramp.** A complete interactive deposit against the SDF test anchor from
inside the app — [60-second video](https://raizapp.xyz/evidencia/d3_deposito_passkey_60s.mp4) +
[transaction hash](https://stellar.expert/explorer/testnet/tx/e4603f18ed5906a42672d19b8f78c942758fc8e61367a0ba5b062204d3fb9357).
Beyond scope: it works for **all wallet types including passkey smart wallets**, with automatic
conversion into the fund's USDC (price-guarded).

Next step per SOW §7: apply to the **SCF Build Award** (Cartagena pilot, residency attestation,
contract audit, local anchor), with the community-savings layer ("Cadena de Barrio") as the first
new ring on top of the deployed protocol.
