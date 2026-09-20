# Paquete de evidencia — SOW Instaward RAÍZ

> Estado al **2026-09-19** (D1 verificado el 12-sep; D2 actualizado el 19-sep). Este README es el
> índice del paquete y el mapa exacto **campo del portal → enlace a pegar**. Detalle por entregable:
> [`d1/README.md`](d1/README.md), [`d2/ejecuciones_2026-09-12.md`](d2/ejecuciones_2026-09-12.md)
> (continúa [`d2/siembra_2026-09-06.md`](d2/siembra_2026-09-06.md)).
>
> Verificado hoy: `/v1/health` del relayer responde 200 en vivo (uptime ≈ 6 días, protocolo 28),
> raizapp.xyz sirve los contratos vigentes, y `main` local == GitHub en ambos repos
> (`Protocolo_Raiz@6faa296`, `raiz-relayer@7aa28dd`).

---

## D1 — Admin Relayer · enlaces para los 5 campos del portal

| # | Campo del portal | Enlace a pegar | Estado |
|---|---|---|---|
| 1 | Relayer GitHub Repository | https://github.com/JuanWimmin/raiz-relayer | ✅ Listo |
| 2 | Release APK Download Link | https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.2.0/raiz-0.2.0-relayer.apk | ✅ **Publicada 2026-09-12** — notas, SHA-256 y verificación en la [página del Release](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.2.0) |
| 3 | APK Decompilation Verification Doc | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/verificacion_apk.md | ✅ Listo (tras `git push` de esta carpeta) |
| 4 | Relayer Live Endpoint Screenshot | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/capturas/d1_relayer_health_2026-09-12.png | ✅ Captura añadida hoy (respuesta real del 12-sep). Respaldo: [deploy_fly.md](https://github.com/JuanWimmin/raiz-relayer/blob/main/docs/evidencia/deploy_fly.md) |
| 5 | Admin Flow Test Screenshots | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/regresion_dispositivo.md | ✅ Listo — regresión narrada con capturas y hashes; carpeta completa: [`d1/capturas/`](https://github.com/JuanWimmin/Protocolo_Raiz/tree/main/docs/evidencia_sow/d1/capturas) |

**Release v0.2.0 publicada el 2026-09-12** — "RAÍZ 0.2.0 — la app consume el relayer (D1 del SOW
Instaward)", asset `raiz-0.2.0-relayer.apk` (97,7 MB, SHA-256
`74ec75292bee1ad443eefdf365e8e869f2c0ec90a779958075536719a40dff0a`, el mismo verificado en
`d1/verificacion_apk.md`). En el campo 2 va el enlace de descarga directa; la página del tag es el
respaldo con notas y hash. Firmado con clave debug de Android (H9): para WP4 puede re-firmarse con
keystore propio sin afectar esta evidencia.

---

## D2 — Real Transaction Linking · enlaces para los 3 campos del portal

Objetivo SOW: cada Execution del dashboard enlaza a su transacción real de Stellar (el cliente
captura el hash del resultado del RPC y correlaciona los eventos `execution`), en lugar del hash
determinístico que guarda el contrato. Código en `main`, probado en dispositivo físico el 2026-09-19.

| # | Campo del portal | Enlace a pegar | Estado |
|---|---|---|---|
| 1 | Dashboard Live URL | https://raizapp.xyz/#demo | ✅ Publicado 2026-09-19 (repo Pages `8637196`). Bloque "Ejecuciones del fondo · Treasury": 6 ejecuciones enlazadas; las que siguen en la ventana del RPC se confirman en vivo con `getEvents` |
| 2 | Stellar Expert TX Links (x3) | Los tres de abajo (#5, #6, #4) | ✅ 6 disponibles; #5 y #6 se ejecutaron **desde la app** |
| 3 | Dashboard Screenshot with TX Links | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png | ✅ App (Moto G04): 3 ejecuciones, las 3 con enlace. Web: [`11_landing_viva…png`](https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/capturas/11_landing_viva_raizapp_xyz_6_ejecuciones_con_tx_links.png). Carpeta: [`d2/capturas/`](https://github.com/JuanWimmin/Protocolo_Raiz/tree/main/docs/evidencia_sow/d2/capturas) |

**Los 3 enlaces del campo 2** (cada uno verificado en Horizon: tx exitosa, `execute_proposal(n)` sobre el Treasury):

1. #5 Centro, ejecutada desde la app (2026-09-19): https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc
2. #6 Norte, ejecutada desde la app (2026-09-19): https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1
3. #4 Norte (2026-09-12): https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3

Resto: #1 [`91c1c35c…`](https://stellar.expert/explorer/testnet/tx/91c1c35c0a796970bbb5bf59da1c79d23339969eb0325c3d00206219cf04596d) ·
#2 [`9ca06287…`](https://stellar.expert/explorer/testnet/tx/9ca0628758b556ea05eb89780cc44ec06d22a822853ce206fb41e630753b4a18) ·
#3 [`aa303a0f…`](https://stellar.expert/explorer/testnet/tx/aa303a0f7ed6459994650d28c426a0fda02389085fe80853208b3dc09290f5f1).

**Cómo enlaza el dashboard (para el revisor):** (1) cuando la app ejecuta una propuesta, fija el hash
al firmar y lo confirma con el resultado del RPC — capturas `02` y `06`; (2) al cargar, correlaciona
por `proposal_id` los eventos `execution` del Treasury (`getEvents`) con `get_execution_log`; (3) el
RPC de testnet solo retiene ~7 días de eventos, así que las ejecuciones más antiguas toman su hash
de un archivo versionado (`android/app/src/main/assets/execution_hashes.json`) y la UI las rotula
"Verificada (archivo)". La captura `08` muestra Stellar Expert abierto desde el chip de la app.

Pendiente menor (no bloquea los 3 campos): ejecutar #7 Centro y #8 Costa desde la app a partir del
**22-sep ≈ 23:10 UTC** (mínimo on-chain de 3 días por propuesta), añadir sus hashes al archivo y
extender el TTL de sus entradas (`scripts/treasury_ttl.js`). Opcional: Release del APK con D2.

## D3 — SEP-10 + SEP-24 · sin iniciar

WP3 del plan (alcance ya decidido: USDC del testanchor a wallets seed; passkey fuera con mensaje).
Evidencia esperada: video 60 s + tx hash del depósito.

---

## Nota de redeploy — contratos del Annex A del SOW vs vigentes

El Annex A del SOW (15-jul) enlaza los contratos del deploy del hackathon. El 31-jul (F1) el
protocolo se re-desplegó para independizarse de DeFindex e integrar Blend v2 directo. Los IDs
vigentes (= `deployments.json`, los mismos que muestra `/v1/health` del relayer y raizapp.xyz):

| Contrato | Annex A (pre-F1, sin actividad nueva) | Vigente (redeploy F1, 2026-07-31) |
|---|---|---|
| Pool | `CAKYU5HW…XEVN2FK` | [`CD775D33…4LCKBE2`](https://stellar.expert/explorer/testnet/contract/CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2) |
| Governance | `CAENXDX7…B77PVE` | [`CBBYI45J…QXHAL32`](https://stellar.expert/explorer/testnet/contract/CBBYI45J3VWQ53QATRWTARCFWNIG7EEZTFCS5OXJWS7KRCPOHQXHAL32) |
| Treasury | `CDGGFSV7…BWQGPXA` | [`CACZWU3B…ZVXDFPATB`](https://stellar.expert/explorer/testnet/contract/CACZWU3BXMCHI23CFN2GTPWCGSQKABMYF7EOMA2J63RMGAEZVXDFPATB) |
| Rewards | `CD5OET7F…CEW2I6PPT` | [`CDTTEZX2…U5SHFU5DZJ`](https://stellar.expert/explorer/testnet/contract/CDTTEZX2QO3L2A4EC34VGVAWYAI4CQD42SGYMFQNNTEQWYU5SHFU5DZJ) |
| Vault DeFindex | `CBMVK2JK…DACXDFZDWHN` | **Eliminado en F1** → `yield_adapter` [`CA5J6YVH…GI4ASBJPJUC`](https://stellar.expert/explorer/testnet/contract/CA5J6YVHZQQKB64ODHCUI65AIK24BQGLL42UZTBV7NPT5GI4ASBJPJUC) + pool USDC de Blend v2 |

---

## Checklist para dejar el portal en verde

1. ÚNICO push pendiente: este `README.md` y `d1/capturas/d1_relayer_health_2026-09-12.png` aún no
   están en `main` (`git add docs/evidencia_sow && git commit && git push`) — el enlace del campo 4
   existe para el revisor solo después de ese push. Todo lo demás de D1 ya está publicado
   (`ab6eba5` + Release v0.2.0).
2. ✅ Release v0.2.0 publicada (2026-09-12).
3. Pegar los 5 enlaces de la tabla D1 en el portal.
4. (Opcional) Reemplazar `d1_relayer_health_2026-09-12.png` por una captura de navegador con la
   barra de URL visible — la actual es la respuesta real del 12-sep renderizada tal cual, con
   URL y fecha rotuladas.
5. D2: pegar en el portal los 3 campos de la tabla D2 (URL viva, 3 links de Stellar Expert, captura).
6. Cosmético: el `version` que reporta `/v1/health` sigue en `0.1.0` (bump de `package.json` del
   relayer en el próximo deploy).
