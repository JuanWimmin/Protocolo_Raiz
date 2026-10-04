# RAÍZ — Paquete de evidencia del SOW Instaward

> **Estado al 2026-10-04: los tres entregables del SOW están completos.** Todo lo de esta página es
> público y se comprueba con un clic, sin instalar nada y sin conocimientos técnicos (≈ 10 minutos).
> Red: Stellar **testnet**. [English version](README.en.md).

| | Entregable (SOW §4.1) | Evidencia comprometida (SOW §6.1) | Evidencia entregada | Completado |
|---|---|---|---|---|
| **D1** | Admin Relayer Service | Repo + APK release + doc de verificación + capturas | [Repo del relayer](https://github.com/JuanWimmin/raiz-relayer) · [servicio en vivo](https://raiz-relayer.fly.dev/v1/health) · [APK 0.3.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.3.0) · [cómo verificar (1 página)](d1/verificacion_apk.md) · capturas: [alta de comercio](d1/capturas/d1_comercio_02.png), [residente](d1/capturas/d1_residente_04.png), [faucet](d1/capturas/d1_faucet_seed_03.png) · [informe de la prueba](d1/regresion_dispositivo.md) | ✅ 6-sep (APK publicado el 12-sep; APK final y rotación de la clave: 4-oct) |
| **D2** | Real Transaction Linking | Dashboard en vivo + 3 enlaces de Stellar Expert | [Dashboard en vivo](https://raizapp.xyz/#demo) · tx [#5](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) · [#6](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) · [#4](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3) · [captura de la app](d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png) | ✅ 19-sep (8 ejecuciones enlazadas: 4-oct) |
| **D3** | SEP-10 + SEP-24 On-Ramp | Video demo + tx hash | [Video de 60 s](https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4) · [tx hash del depósito](https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562) · [captura de la app](d3/capturas/06_deposito_recibido_hash_y_cotizacion.png) | ✅ 3-oct |

## Cómo verificarlo en 10 minutos

### D1 — La clave del admin ya no viaja en la app (≈ 4 min)

Antes, el APK llevaba dentro la clave privada del admin del protocolo. Ahora la app no lleva ninguna
clave privada: las operaciones de admin las firma un servicio (el *relayer*) con una clave nueva que
nunca estuvo en un APK, y la app solo se lo pide por internet. La clave vieja quedó revocada (paso 5).

1. **El servicio está vivo.** Abre <https://raiz-relayer.fly.dev/v1/health>: responde `"ok":true` y
   `"network":"testnet"`.
2. **Es código abierto.** <https://github.com/JuanWimmin/raiz-relayer> (licencia MIT, con pruebas automáticas).
3. **El APK no lleva claves privadas.** Abre [`verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)
   y haz clic en la primera fila de la lista (la ejecución más reciente). GitHub descarga el APK
   publicado y busca claves privadas. La ejecución debe estar en verde y, en el recuadro
   *Annotations*, el aviso titulado "Verificación del APK" debe decir **"claves privadas válidas: 0"**.
   Para repetirlo a mano: [cómo verificar, 1 página](d1/verificacion_apk.md).
4. **Funciona en un teléfono real.** [Prueba en un Motorola G04](d1/regresion_dispositivo.md) del
   6-sep, con la versión 0.2.0 (la primera sin la clave): los flujos de admin (alta de comercio,
   residente, faucet) pasan por el relayer, con capturas y enlaces. La versión final 0.3.0 pasó una
   prueba de humo en el mismo teléfono el 4-oct.
5. **La clave vieja quedó revocada.** Abre la
   [cuenta del admin en Stellar Expert](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P):
   en *Signers* hay dos claves: `GB42NCO6…` con peso 1 (la nueva, la que usa el relayer) y
   `GBLS7PL5…` con peso 0 (la original, la que estuvo en el APK viejo; coincide con la dirección de
   la cuenta). Peso 0 significa que ya no puede firmar nada. Detalle en
   [`d1/README.md`](d1/README.md#rotación-de-la-clave-del-admin-2026-10-04).

### D2 — Cada gasto del fondo enlaza a su transacción real (≈ 3 min)

1. Abre <https://raizapp.xyz/#demo> y busca el bloque **"Ejecuciones del fondo · Treasury"** (debajo
   del video y de las tarjetas de los contratos): 8 ejecuciones, cada una con su enlace.
2. Haz clic en cualquiera, o en estas tres:
   [#5 Centro](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) ·
   [#6 Norte](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) ·
   [#4 Norte](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3).
   Stellar Expert muestra **Status: Successful** y la llamada `execute_proposal(n)`.
3. La misma lista dentro de la app (pantalla *Transparencia*):
   [captura del barrio Norte](d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png), con sus 3
   ejecuciones (#1, #4 y #6), cada una con el botón "Ver en Stellar Expert". La #5 es del barrio
   Centro: [captura](d2/capturas/14_centro_ejecuciones_3_5_7.png).

### D3 — Un depósito SEP-24 completo desde la app (≈ 3 min)

1. Mira el **[video de 60 segundos](https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4)**: la app
   se autentica con el anchor de prueba del SDF (SEP-10), abre su web para el depósito (SEP-24) y
   termina en "¡Depósito recibido!" con el hash.
2. Abre ese **[hash en Stellar Expert](https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562)**
   (`ae4d3e…df7562`, el mismo del botón verde con que termina el video): **Successful**, un `transfer`
   de 4,5 USDC de la cuenta del anchor a la wallet del teléfono. Se depositan 5; el anchor de prueba
   descuenta 0,5 de comisión.

## Campos del portal: qué pegar en cada uno

| Entregable | Campo | Enlace |
|---|---|---|
| D1 | Relayer GitHub Repository | https://github.com/JuanWimmin/raiz-relayer |
| D1 | Release APK Download Link | https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.3.0/raiz-0.3.0.apk |
| D1 | APK Decompilation Verification Doc | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/verificacion_apk.md |
| D1 | Relayer Live Endpoint Screenshot | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/capturas/d1_relayer_health_2026-10-04.png |
| D1 | Admin Flow Test Screenshots | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/regresion_dispositivo.md |
| D2 | Dashboard Live URL | https://raizapp.xyz/#demo |
| D2 | Stellar Expert TX Links (x3) | https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc<br>https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1<br>https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3 |
| D2 | Dashboard Screenshot with TX Links | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png |
| D3 | Video del depósito SEP-24 (60 s) | https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4 |
| D3 | Tx hash del pago del anchor | https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562 |
| D3 | Captura con el depósito completado | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d3/capturas/06_deposito_recibido_hash_y_cotizacion.png |

## Nota de redeploy: los contratos del Annex A ya no son los vigentes

El Annex A del SOW (15-jul) enlaza los contratos del hackathon. El 31-jul el protocolo se
re-desplegó para rendir directo en Blend v2, sin DeFindex. Los contratos vigentes son los que
muestran `deployments.json`, raizapp.xyz y `/v1/health` del relayer; los del Annex A siguen en la
red, pero sin actividad nueva.

| Contrato | Annex A (hackathon) | Vigente desde el 2026-07-31 |
|---|---|---|
| Pool | `CAKYU5HW…XEVN2FK` | [`CD775D33…4LCKBE2`](https://stellar.expert/explorer/testnet/contract/CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2) |
| Governance | `CAENXDX7…B77PVE` | [`CBBYI45J…QXHAL32`](https://stellar.expert/explorer/testnet/contract/CBBYI45J3VWQ53QATRWTARCFWNIG7EEZTFCS5OXJWS7KRCPOHQXHAL32) |
| Treasury | `CDGGFSV7…BWQGPXA` | [`CACZWU3B…ZVXDFPATB`](https://stellar.expert/explorer/testnet/contract/CACZWU3BXMCHI23CFN2GTPWCGSQKABMYF7EOMA2J63RMGAEZVXDFPATB) |
| Rewards | `CD5OET7F…CEW2I6PPT` | [`CDTTEZX2…U5SHFU5DZJ`](https://stellar.expert/explorer/testnet/contract/CDTTEZX2QO3L2A4EC34VGVAWYAI4CQD42SGYMFQNNTEQWYU5SHFU5DZJ) |
| Vault DeFindex | `CBMVK2JK…DACXDFZDWHN` | Eliminado. Lo sustituye `yield_adapter` [`CA5J6YVH…GI4ASBJPJUC`](https://stellar.expert/explorer/testnet/contract/CA5J6YVHZQQKB64ODHCUI65AIK24BQGLL42UZTBV7NPT5GI4ASBJPJUC), que presta en el pool USDC de Blend v2 |

## Notas honestas

- **La clave del admin estuvo expuesta y se rotó.** El APK del hackathon (0.1.0) llevaba dentro la
  clave privada del admin; era el bloqueador que declara el SOW. Ese APK se retiró y, el 4-oct, la
  clave se revocó on-chain: la cuenta admin conserva su dirección, pero ahora firma una clave nueva
  que nunca estuvo en un APK. Transacciones y pasos: [`d1/README.md`](d1/README.md#rotación-de-la-clave-del-admin-2026-10-04).
- **Todo corre en testnet.** No hay dinero real. Si la red de pruebas se reiniciara, los enlaces a
  Stellar Expert dejarían de resolver; por eso cada entregable guarda también capturas.
- **El APK sí lleva una API key de aplicación del relayer.** No es la clave del admin ni firma nada:
  identifica a la app para limitar el número de peticiones. Detalle en
  [`d1/verificacion_apk.md`](d1/verificacion_apk.md#qué-sí-contiene-el-apk-y-por-qué-no-es-un-secreto).
- **El APK va firmado con la clave de depuración de Android**, no con una de tienda. Esa firma solo
  identifica al instalador: no es una clave de Stellar ni da acceso a cuentas o fondos. Es
  intencional: las wallets passkey están atadas a ella. La firma de publicación llega con mainnet,
  fuera del alcance del SOW.
- **D2:** el servidor público de testnet (RPC) solo guarda 7 días de eventos. Las ejecuciones más
  antiguas enlazan gracias a un archivo versionado de hashes, y la app las rotula "Verificada
  (archivo)". En la landing, la etiqueta del bloque pasa de "en vivo" a "snapshot 4·oct·2026" siete
  días después de la última ejecución (desde el 11-oct); los 8 enlaces no cambian.
- **D3:** el USDC del anchor de prueba es un activo distinto del USDC del fondo; la app lo rotula
  aparte y ofrece convertirlo. Las wallets passkey aún no pueden depositar (falta SEP-45 en el
  anchor). El video se grabó operando el teléfono desde el computador por cable (`adb`), no con el
  dedo; app, anchor y transacciones son reales.
- **Mantenimiento:** en testnet los datos de los contratos caducan si nadie los renueva. Están
  renovados hasta comienzos de diciembre de 2026.

## Detalle por entregable

- **D1:** [`d1/README.md`](d1/README.md) (relayer, APK, rotación de la clave) ·
  [`d1/verificacion_apk.md`](d1/verificacion_apk.md) · [`d1/regresion_dispositivo.md`](d1/regresion_dispositivo.md) ·
  [`d1/capturas/`](d1/capturas)
- **D2:** [`d2/ejecuciones_2026-09-12.md`](d2/ejecuciones_2026-09-12.md) (las 8 ejecuciones, cómo
  enlaza el dashboard, comprobación en incógnito) · [`d2/siembra_2026-09-06.md`](d2/siembra_2026-09-06.md) ·
  [`d2/capturas/`](d2/capturas)
- **D3:** [`d3/README.md`](d3/README.md) (flujo, hallazgos, hashes) · [`d3/guion_video.md`](d3/guion_video.md) ·
  [`d3/video/`](d3/video) · [`d3/capturas/`](d3/capturas)
