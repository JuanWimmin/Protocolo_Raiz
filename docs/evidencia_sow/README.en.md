# RAÍZ — Instawards SOW evidence package

> **Status as of 2026-10-04: all three SOW deliverables are complete.** Everything on this page is
> public and can be checked with one click, with no installs and no technical background (about 10
> minutes). Network: Stellar **testnet**. [Versión en español](README.md) (the detailed pages under
> `d1/`, `d2/` and `d3/` are in Spanish).

| | Deliverable (SOW §4.1) | Planned evidence (SOW §6.1) | Evidence delivered | Completed |
|---|---|---|---|---|
| **D1** | Admin Relayer Service | Repo + release APK + verification doc + screenshots | [Relayer repo](https://github.com/JuanWimmin/raiz-relayer) · [live service](https://raiz-relayer.fly.dev/v1/health) · [APK 0.3.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.3.0) · [how to verify (1 page)](d1/verificacion_apk.md) · screenshots: [merchant registration](d1/capturas/d1_comercio_02.png), [resident](d1/capturas/d1_residente_04.png), [faucet](d1/capturas/d1_faucet_seed_03.png) · [test report](d1/regresion_dispositivo.md) | ✅ Sep 6 (APK published Sep 12; final APK and key rotation: Oct 4) |
| **D2** | Real Transaction Linking | Live dashboard + 3 Stellar Expert links | [Live dashboard](https://raizapp.xyz/#demo) · tx [#5](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) · [#6](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) · [#4](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3) · [app screenshot](d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png) | ✅ Sep 19 (8 linked executions: Oct 4) |
| **D3** | SEP-10 + SEP-24 On-Ramp | Demo video + tx hash | [60-second video](https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4) · [deposit tx hash](https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562) · [app screenshot](d3/capturas/06_deposito_recibido_hash_y_cotizacion.png) | ✅ Oct 3 |

## How to verify it in 10 minutes

### D1 — The admin key no longer ships inside the app (about 4 min)

The APK used to carry the protocol admin's private key. The app now carries no private key: admin
operations are signed by a service (the *relayer*) with a new key that has never been in an APK, and
the app only asks it over HTTPS. The old key has been revoked (step 5).

1. **The service is live.** Open <https://raiz-relayer.fly.dev/v1/health>: it answers `"ok":true`
   and `"network":"testnet"`.
2. **It is open source.** <https://github.com/JuanWimmin/raiz-relayer> (MIT license, automated tests).
3. **The APK carries no private keys.** Open [`verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)
   and click the first row of the list (the latest run). GitHub downloads the published APK and
   searches it for private keys. The run must be green and, in its *Annotations* box, the notice
   titled "Verificación del APK" must say **"claves privadas válidas: 0"** (valid private keys: 0).
   To repeat it by hand:
   [how to verify, 1 page](d1/verificacion_apk.md).
4. **It works on a real phone.** [Test on a Motorola G04](d1/regresion_dispositivo.md) on Sep 6,
   with version 0.2.0 (the first one without the key): the admin flows (merchant registration,
   resident minting, faucet) go through the relayer, with screenshots and transaction links. The
   final version 0.3.0 passed a smoke test on the same phone on Oct 4.
5. **The old key has been revoked.** Open the
   [admin account on Stellar Expert](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P):
   under *Signers* there are two keys: `GB42NCO6…` with weight 1 (the new one, used by the relayer)
   and `GBLS7PL5…` with weight 0 (the original one, which was in the old APK; it matches the
   account's address). Weight 0 means it can no longer sign anything. Details in
   [`d1/README.md`](d1/README.md#rotación-de-la-clave-del-admin-2026-10-04).

### D2 — Every spend from the fund links to its real transaction (about 3 min)

1. Open <https://raizapp.xyz/#demo> and find the block **"Ejecuciones del fondo · Treasury"**
   (fund executions; below the video and the contract cards): 8 executions, each with its link.
2. Click any of them, or these three:
   [#5 Centro](https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc) ·
   [#6 Norte](https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1) ·
   [#4 Norte](https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3).
   Stellar Expert shows **Status: Successful** and the call `execute_proposal(n)`.
3. The same list inside the app (the *Transparencia* screen):
   [screenshot of the Norte neighborhood](d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png),
   with its 3 executions (#1, #4 and #6), each with the "Ver en Stellar Expert" button. #5 belongs
   to the Centro neighborhood: [screenshot](d2/capturas/14_centro_ejecuciones_3_5_7.png).

### D3 — A complete SEP-24 deposit from the app (about 3 min)

1. Watch the **[60-second video](https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4)**: the app
   authenticates with the SDF test anchor (SEP-10), opens the anchor's web page for the deposit
   (SEP-24) and ends on "¡Depósito recibido!" (deposit received) with the transaction hash.
2. Open that **[hash on Stellar Expert](https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562)**
   (`ae4d3e…df7562`, the same one shown on the green button at the end of the video): **Successful**,
   a `transfer` of 4.5 USDC from the anchor's account to the phone's wallet. The deposit is 5; the
   test anchor keeps a 0.5 fee.

## Portal fields: what to paste in each

| Deliverable | Field | Link |
|---|---|---|
| D1 | Relayer GitHub Repository | https://github.com/JuanWimmin/raiz-relayer |
| D1 | Release APK Download Link | https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.3.0/raiz-0.3.0.apk |
| D1 | APK Decompilation Verification Doc | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/verificacion_apk.md |
| D1 | Relayer Live Endpoint Screenshot | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/capturas/d1_relayer_health_2026-10-04.png |
| D1 | Admin Flow Test Screenshots | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d1/regresion_dispositivo.md |
| D2 | Dashboard Live URL | https://raizapp.xyz/#demo |
| D2 | Stellar Expert TX Links (x3) | https://stellar.expert/explorer/testnet/tx/c891ec26b29d686912175b162e7f9acd0b462c21969e6f555fbdf02514e24ddc<br>https://stellar.expert/explorer/testnet/tx/76452c3a5262d3c16c196888b5186990d1dec703c451acdb0c9a8019ba3cf6e1<br>https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3 |
| D2 | Dashboard Screenshot with TX Links | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d2/capturas/10_norte_dashboard_3_ejecuciones_con_tx_links.png |
| D3 | SEP-24 deposit video (60 s) | https://raizapp.xyz/evidencia/d3_deposito_sep24_60s.mp4 |
| D3 | Tx hash of the anchor's payment | https://stellar.expert/explorer/testnet/tx/ae4d3e434d894924dfef688a7202de436133b7085a413e060d01e07e0edf7562 |
| D3 | Screenshot with the completed deposit | https://github.com/JuanWimmin/Protocolo_Raiz/blob/main/docs/evidencia_sow/d3/capturas/06_deposito_recibido_hash_y_cotizacion.png |

## Redeploy note: the contracts in Annex A are no longer the current ones

Annex A of the SOW (July 15) links the hackathon contracts. On July 31 the protocol was redeployed
to earn yield directly on Blend v2, without DeFindex. The current contracts are the ones shown by
`deployments.json`, raizapp.xyz and the relayer's `/v1/health`; the Annex A ones still exist on the
network but see no new activity.

| Contract | Annex A (hackathon) | Current since 2026-07-31 |
|---|---|---|
| Pool | `CAKYU5HW…XEVN2FK` | [`CD775D33…4LCKBE2`](https://stellar.expert/explorer/testnet/contract/CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2) |
| Governance | `CAENXDX7…B77PVE` | [`CBBYI45J…QXHAL32`](https://stellar.expert/explorer/testnet/contract/CBBYI45J3VWQ53QATRWTARCFWNIG7EEZTFCS5OXJWS7KRCPOHQXHAL32) |
| Treasury | `CDGGFSV7…BWQGPXA` | [`CACZWU3B…ZVXDFPATB`](https://stellar.expert/explorer/testnet/contract/CACZWU3BXMCHI23CFN2GTPWCGSQKABMYF7EOMA2J63RMGAEZVXDFPATB) |
| Rewards | `CD5OET7F…CEW2I6PPT` | [`CDTTEZX2…U5SHFU5DZJ`](https://stellar.expert/explorer/testnet/contract/CDTTEZX2QO3L2A4EC34VGVAWYAI4CQD42SGYMFQNNTEQWYU5SHFU5DZJ) |
| DeFindex vault | `CBMVK2JK…DACXDFZDWHN` | Removed. Replaced by `yield_adapter` [`CA5J6YVH…GI4ASBJPJUC`](https://stellar.expert/explorer/testnet/contract/CA5J6YVHZQQKB64ODHCUI65AIK24BQGLL42UZTBV7NPT5GI4ASBJPJUC), which lends into the Blend v2 USDC pool |

## Honest notes

- **The admin key was exposed, and it has been rotated.** The hackathon APK (0.1.0) carried the
  admin's private key; that was the blocker the SOW declares. That APK has been withdrawn and, on
  Oct 4, the key was revoked on-chain: the admin account keeps its address, but it is now signed by
  a new key that has never been in an APK. Transactions and steps:
  [`d1/README.md`](d1/README.md#rotación-de-la-clave-del-admin-2026-10-04).
- **Everything runs on testnet.** No real money is involved. If the test network were reset, the
  Stellar Expert links would stop resolving; that is why each deliverable also keeps screenshots.
- **The APK does carry a relayer application API key.** It is not the admin key and it signs
  nothing: it identifies the app so that requests can be rate-limited. Details in
  [`d1/verificacion_apk.md`](d1/verificacion_apk.md#qué-sí-contiene-el-apk-y-por-qué-no-es-un-secreto).
- **The APK is signed with the Android debug key**, not a store key. That signature only identifies
  the installer: it is not a Stellar key and gives no access to accounts or funds. It is
  intentional: passkey wallets are bound to it. A publishing key comes with mainnet, which is out
  of the SOW's scope.
- **D2:** the public testnet server (RPC) keeps only 7 days of events. Older executions stay linked
  through a versioned file of hashes, and the app labels them "Verificada (archivo)". On the
  landing page, the block's label changes from "en vivo" (live) to "snapshot 4·oct·2026" seven
  days after the last execution (from Oct 11); the 8 links do not change.
- **D3:** the test anchor's USDC is a different asset from the fund's USDC; the app labels it
  separately and offers to convert it. Passkey wallets cannot deposit yet: they need SEP-45, which
  the test anchor already offers but the app does not implement yet. The video was recorded by driving the phone from a computer over a cable (`adb`), not by
  hand; the app, the anchor and the transactions are real.
- **Maintenance:** on testnet, contract data expires unless someone renews it. It is renewed until
  early December 2026.

## Detail per deliverable (in Spanish)

- **D1:** [`d1/README.md`](d1/README.md) (relayer, APK, key rotation) ·
  [`d1/verificacion_apk.md`](d1/verificacion_apk.md) · [`d1/regresion_dispositivo.md`](d1/regresion_dispositivo.md) ·
  [`d1/capturas/`](d1/capturas)
- **D2:** [`d2/ejecuciones_2026-09-12.md`](d2/ejecuciones_2026-09-12.md) (the 8 executions, how the
  dashboard links them, incognito check) · [`d2/siembra_2026-09-06.md`](d2/siembra_2026-09-06.md) ·
  [`d2/capturas/`](d2/capturas)
- **D3:** [`d3/README.md`](d3/README.md) (flow, findings, hashes) · [`d3/guion_video.md`](d3/guion_video.md) ·
  [`d3/video/`](d3/video) · [`d3/capturas/`](d3/capturas)
