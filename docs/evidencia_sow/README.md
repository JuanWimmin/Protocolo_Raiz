# Paquete de evidencia — SOW Instaward RAÍZ

> Estado al **2026-09-12**. Este README es el índice del paquete y el mapa exacto
> **campo del portal → enlace a pegar**. Detalle por entregable: [`d1/README.md`](d1/README.md),
> [`d2/siembra_2026-09-06.md`](d2/siembra_2026-09-06.md).
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

## D2 — Tx hash real · estado y enlaces ya disponibles

Objetivo SOW: dashboard en vivo + **≥3** ejecuciones enlazadas a su transacción real.

| Ejecución | Barrio | Tx real | Stellar Expert |
|---|---|---|---|
| #1 "Bancas para el parque" (2026-09-06) | Norte | `91c1c35c…596d` | https://stellar.expert/explorer/testnet/tx/91c1c35c0a796970bbb5bf59da1c79d23339969eb0325c3d00206219cf04596d |
| #2 "Limpieza de la playa" (2026-09-06) | Costa | `9ca06287…4a18` | https://stellar.expert/explorer/testnet/tx/9ca0628758b556ea05eb89780cc44ec06d22a822853ce206fb41e630753b4a18 |
| #3 "Luces para el parque infantil" (Centro) | Centro | — | **Ejecutable desde el 9-sep 16:49 UTC** — ejecutarla DESDE LA APP para capturar el hash del camino feliz (WP2) |
| #4 "Kit de reciclaje comunitario" (Norte, reserva) | Norte | — | Ejecutable desde el 9-sep |

Pendiente para cerrar D2: código WP2 (correlación `getEvents`→txHash en app y landing, según
`docs/PLAN_CLAUDE_CODE_SOW.md`), ejecutar #3 desde la app, y screenshots del dashboard con los
enlaces vivos → `d2/`.

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
5. D2: ejecutar #3 desde la app + WP2 + capturas → llenar sus campos con los enlaces de la tabla D2.
6. Cosmético: el `version` que reporta `/v1/health` sigue en `0.1.0` (bump de `package.json` del
   relayer en el próximo deploy).
