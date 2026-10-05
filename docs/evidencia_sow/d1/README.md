# Evidencia SOW — D1: Admin Relayer

**Entregable D1 (SOW Instaward):** servicio open source que firma en el servidor las operaciones de
admin de RAÍZ; la app Android lo consume; APK release **sin secretos**, verificable por
descompilación; regresión en dispositivo físico.

**En una frase:** antes, el APK llevaba la clave privada del admin del protocolo para poder
registrar comercios, emitir el soulbound de residente y repartir USDC de prueba. Desde la versión
0.2.0 el APK no lleva ninguna clave privada: quien firma como admin es el servicio `raiz-relayer`,
con una clave que vive únicamente en una variable de entorno del servidor. El 4-oct, además, la
clave que había viajado en el APK viejo se **revocó on-chain** y el relayer pasó a firmar con una
clave nueva, que nunca estuvo en un APK. Y desde la **0.4.0** (también del 4-oct) el APK tampoco
lleva la API key con la que se identificaba ante el relayer: solo contiene direcciones públicas.

## Tabla entregable → evidencia

| Entregable SOW D1 | Evidencia | Dónde | Estado |
|---|---|---|---|
| Repositorio público del relayer | TypeScript + Fastify + `@stellar/stellar-sdk`, licencia MIT, README en español (endpoints, modelo de amenazas, runbook), 200 pruebas automáticas (`vitest`), integración real contra testnet y CI | https://github.com/JuanWimmin/raiz-relayer | ✅ Público desde el 27-ago |
| Servicio corriendo en testnet | `GET /v1/health`: `ok`, `network: testnet`, los 5 contratos de `deployments.json`, cuenta admin y clave que firma | https://raiz-relayer.fly.dev/v1/health · captura [`d1_relayer_health_2026-10-04.png`](capturas/d1_relayer_health_2026-10-04.png) | ✅ Desplegado el 6-sep (Fly, región `iad`); versión 0.3.0 desde el 4-oct (pública, sin API key, con cupos por IP) |
| App migrada al relayer | `android/app/src/main/java/com/raiz/app/data/relayer/RelayerClient.kt` + 27 tests de mapeo de errores; ningún ViewModel firma como admin | Este repo, `main` (PR #1, 6-sep) | ✅ |
| APK release sin secretos | [`raiz-0.4.0.apk`](https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.4.0/raiz-0.4.0.apk): versión final del sprint, con D1 + D2 + D3 y sin ninguna credencial. SHA-256 `296f30d8…802569d0` | [Release v0.4.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.4.0). La anterior, [v0.3.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.3.0), aún llevaba la API key del relayer. La primera versión sin clave admin fue la [v0.2.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.2.0) (12-sep) | ✅ 4-oct |
| Verificable por descompilación | Documento de 1 página + script reproducible + ejecución automática en GitHub | [`verificacion_apk.md`](verificacion_apk.md) · [`scripts/verify_apk_no_secrets.py`](../../../scripts/verify_apk_no_secrets.py) · [workflow `verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml) | ✅ 0 claves privadas |
| Regresión en dispositivo físico | 4 flujos vía relayer en un Motorola G04 (faucet con passkey y con semilla, alta de comercio, residente + voto, yield), con capturas y hashes | [`regresion_dispositivo.md`](regresion_dispositivo.md) · [`capturas/`](capturas) | ✅ 6-sep (APK 0.2.0). El APK 0.3.0 pasó una prueba de humo el 4-oct (capturas `d1_v030_*`) y el **APK 0.4.0 completó un depósito real** ese mismo día: capturas [`d1_v040_01`](capturas/d1_v040_01_inicio_release.png), [`02`](capturas/d1_v040_02_deposito_completo_release.png), [`03`](capturas/d1_v040_03_inicio_tras_deposito_release.png) |
| La clave que viajó en el APK ya no sirve | Rotación on-chain: la clave maestra de la cuenta admin tiene peso 0 | [Sección de abajo](#rotación-de-la-clave-del-admin-2026-10-04) | ✅ 4-oct |

## Cómo lo verifica un revisor en 10 minutos

1. **Servicio vivo (1 min).** Abre <https://raiz-relayer.fly.dev/v1/health>: `"ok":true`,
   `"network":"testnet"`, y los contratos coinciden con [`deployments.json`](../../../deployments.json).
2. **Código abierto (2 min).** En <https://github.com/JuanWimmin/raiz-relayer>, el README explica los
   endpoints y el modelo de amenazas. La clave del admin solo entra por la variable de entorno
   `RELAYER_ADMIN_SECRET`; el repositorio no contiene ninguna clave.
3. **APK sin secretos (3 min).** Abre la ejecución más reciente del
   [workflow `verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)
   (en verde, con la anotación "claves privadas válidas: 0") o repítelo tú con
   [`verificacion_apk.md`](verificacion_apk.md).
4. **Funciona en un teléfono (3 min).** Abre [`regresion_dispositivo.md`](regresion_dispositivo.md)
   y pulsa dos o tres enlaces de su tabla final: en cada transacción la cuenta de origen es el admin
   `GBLS7PL5…YC2P` (firmó el relayer) y el destinatario es la wallet del usuario.
5. **La clave vieja está revocada (1 min).** Abre la
   [cuenta admin en Stellar Expert](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P):
   en *Signers* la clave original aparece con peso 0.

## Rotación de la clave del admin (2026-10-04)

**Qué se encontró.** Al armar este paquete se examinó el APK del hackathon (`RAIZ-v0.1.0.apk`,
publicado el 30-jun y, hasta el 4-oct, todavía descargable). El verificador encontró dentro tres
claves privadas: las
de dos wallets demo y **la clave maestra de la cuenta admin vigente**, `GBLS7PL5…YC2P`. El redeploy
del 31-jul había reutilizado esa misma cuenta, así que la clave nunca se cambió: el APK nuevo ya no
la llevaba, pero quien hubiera descargado el viejo podía firmar como admin.

**Qué se hizo.** Se rotó la clave sin cambiar la dirección de la cuenta. Los contratos guardan esa
dirección como admin, de modo que no hubo que redesplegar nada ni tocar la app:

| # | Paso | Evidencia |
|---|---|---|
| 1 | Se generó una clave nueva (`GB42NCO6…BRYL7`). Solo existe en el servidor del relayer y en el equipo de mantenimiento; nunca ha estado en un APK ni en un repositorio | — |
| 2 | Se añadió como firmante de la cuenta admin, con peso 1 | tx [`b7524190…3ca1`](https://stellar.expert/explorer/testnet/tx/b7524190400b7a172b336480b08c976d2ea83323e1a611af2f5c38534ec93ca1) |
| 3 | El relayer (versión 0.2.0) pasó a firmar con la clave nueva. `/v1/health` lo muestra: `signer: GB42NCO6…`, `signerAuthorized: true` | commit [`9620123`](https://github.com/JuanWimmin/raiz-relayer/commit/96201239576071b27f1221a9e719e9f8e76032cf) · faucet de prueba firmado con la clave nueva: [`b3092c56…7189`](https://stellar.expert/explorer/testnet/tx/b3092c56bd9db1b0417b4b3d0a51b1d311d3b4887b32f5e193b92f8fc8197189) (pago clásico) y [`04d0a525…7ad4`](https://stellar.expert/explorer/testnet/tx/04d0a5253118f9d2abe886a76392d2c1d7ab85d5ec829a9ee8b4c8d7b75f7ad4) (contrato) |
| 4 | Con una transacción firmada por la clave **nueva**, la clave maestra quedó con peso 0 y los umbrales en 1/1/1 | tx [`1c56d1ca…35d7`](https://stellar.expert/explorer/testnet/tx/1c56d1ca0c10caaedfeeeccdbca49ce4587500fe76727e868b4c06b4a6b235d7) |
| 5 | Comprobación: una transacción firmada con la clave vieja es rechazada por la red | `tx_bad_auth` (salida abajo) |
| 6 | El APK 0.1.0 se retiró de su Release y la landing enlaza al APK vigente (0.4.0) | [Release v0.1.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.1.0) (sin archivo) · <https://raizapp.xyz> |
| 7 | La suite de integración del relayer (11 pruebas reales contra testnet) pasa con la clave nueva: faucet, residente, comercio y los rechazos esperados | [`it-testnet-2026-10-04.json`](https://github.com/JuanWimmin/raiz-relayer/blob/main/docs/evidencia/it-testnet-2026-10-04.json) · `mint_resident` [`f5842447…bd8d`](https://stellar.expert/explorer/testnet/tx/f584244713df7d6ec28d59f2cf11be143514db58f3b580334fbecd1039d4bd8d) · `register_merchant` [`684ba48f…7fec`](https://stellar.expert/explorer/testnet/tx/684ba48f31aea7e59b0907969a032652ad4e213776cfe8d6d3892346c6837fec) |

El paso 4 lo firma la clave nueva a propósito: si esa clave no pudiera firmar por la cuenta, la
transacción habría fallado sin cambiar nada. Así es imposible dejar la cuenta sin dueño.

**Cómo comprobarlo.** En la
[cuenta admin en Stellar Expert](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P),
sección *Signers*: `GB42NCO6…BRYL7` con peso 1 y `GBLS7PL5…YC2P` (la clave original) con peso 0. Con
peso 0 esa clave no puede firmar nada, ni siquiera para devolverse el peso. La misma información, en
crudo: <https://horizon-testnet.stellar.org/accounts/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P>.

Intento del 4-oct, después del paso 4, de enviar una transacción firmada con la clave vieja:

```
$ stellar tx new bump-sequence --source-account raiz-admin --network testnet --bump-to 1
ℹ️  Signing transaction: 08e5364ae68ef3b2869e470e36c71931e779e13ca737a1a118a05718f96cefc9
❌ error: transaction submission failed: TxBadAuth
```

Y el relayer siguió funcionando con la clave nueva después del cambio: faucet
[`8c868f29…0bca`](https://stellar.expert/explorer/testnet/tx/8c868f2917dba386738f9f4901f46c12e203e87d704b92230d0eee07f2d00bca).

**Lo que no cambió.** La dirección del admin (`GBLS7PL5…YC2P`), los 5 contratos, `deployments.json`
y la app: ninguno depende de qué clave firma, solo de la cuenta.

## Notas honestas

- **La dirección pública del admin sí está en el APK** (`assets/deployments.json`). Es pública y
  necesaria como cuenta de origen de las lecturas por simulación; con ella no se firma nada.
- **El relayer es público: no hay API key.** De 0.2.0 a 0.3.0 la app enviaba una API key de
  aplicación que viajaba en el APK; no firmaba nada, pero cualquiera podía extraerla, así que no
  protegía de verdad. Desde el 4-oct (app 0.4.0 + relayer 0.3.0) no existe: cualquiera puede llamar
  al relayer y lo que acota el abuso son cupos del lado del servidor (por IP y minuto, por IP y día,
  por dirección destino y un tope global diario), además de que cada operación solo se firma si las
  reglas de los contratos lo permiten. El relayer lo documenta como modelo de amenazas de
  **testnet**. Lo que viene después — que cada petición vaya autenticada por la wallet del usuario
  (SEP-10 y SEP-45) — está planificado en detalle como WP7 de
  [`PLAN_CLAUDE_CODE_SOW.md`](../../PLAN_CLAUDE_CODE_SOW.md); la custodia comunal real es la fase F3.
- **Wallets demo.** Las claves de las wallets demo (turista y residente) existen solo en el build
  *debug* de desarrollo; el build *release* las deja vacías. Son las otras dos claves que llevaba el
  APK 0.1.0: son cuentas de prueba sin autoridad sobre el protocolo y siguen activas porque la demo
  las usa.
- **Firma del APK.** Va firmado con la clave de depuración de Android, no con una de tienda. Es
  intencional: el `assetlinks.json` de las wallets passkey está atado a esa huella
  (`AB:0F:7A:CB:…:A7:13`), y cambiarla rompería las wallets existentes. La firma de publicación llega
  con mainnet, fuera del alcance del SOW.
- **Cadenas con forma de clave en `libmapbox-common.so`.** Un `grep` simple encuentra 32 en el
  binario del SDK de Mapbox; ninguna es una clave (no pasan el checksum). Detalle en
  [`verificacion_apk.md`](verificacion_apk.md).
- **Contratos vigentes.** Los IDs son los del redeploy del 31-jul (`deployments.json`), no los del
  Annex A del SOW. La tabla vieja → nueva está en el [README general](../README.md).
- **Un solo firmante.** Tras la rotación la cuenta admin sigue dependiendo de una sola clave, ahora
  en el servidor. Pasarla a multisig 2-de-3 está preparado (`scripts/setup_admin_multisig.sh`) y la
  custodia comunal es F3.

## Cambios en la app (resumen técnico)

| Antes (0.1.0, firmaba el APK) | Desde 0.2.0 (firma el relayer) |
|---|---|
| `BuildConfig.DEMO_ADMIN_SECRET` + `WalletManager.demoAdminKeyPair()` | Eliminados |
| `HorizonStream.sendUsdcFromAdmin` (faucet `G…`) y `SorobanClient.fundContractUsdc` (faucet `C…`) | `RelayerClient.faucet(address)` → `POST /v1/faucet` |
| `SorobanClient.registerMerchant(admin, …)` | `RelayerClient.registerMerchant(…)` → `POST /v1/register-merchant` |
| `SorobanClient.mintResident(admin, …)` | `RelayerClient.mintResident(…)` → `POST /v1/mint-resident` (idempotente) |
| `SorobanClient.depositIdleToVault / redeemFromVault(admin, …)` | `RelayerClient.vaultDeposit / vaultRedeem` → `POST /v1/vault/{deposit,redeem}` |

Lo que el usuario sigue firmando con su propia wallet: pagar a un comercio, votar, crear
propuestas, canjear premios, ejecutar propuestas aprobadas (trustless), friendbot y trustlines.

**Desde 0.4.0 (4-oct):** se eliminaron `BuildConfig.RELAYER_APP_KEY` y la cabecera
`x-raiz-app-key`; `RelayerClient` solo necesita la URL del relayer, y un `401` del servidor se
muestra como "El relayer no autorizó la petición". Además, el faucet dejó de ser un camino normal
de la app: el USDC se consigue depositando con el anchor (D3) y el faucet queda como contingencia.

Bitácora completa de la primera verificación del APK (0.2.0, 6-sep):
[`verificacion_apk_0.2.0_bitacora.md`](verificacion_apk_0.2.0_bitacora.md).
