# D1 — Verificación del APK: cero secretos (release 0.2.0)

**Qué demuestra:** el APK de RAÍZ ya no contiene ninguna clave privada de Stellar. La autoridad
admin (faucet, registro de comercio, soulbound de residente y vault) vive en el servicio
[`raiz-relayer`](https://github.com/JuanWimmin/raiz-relayer); la app solo hace HTTP contra él.

**Para el revisor no técnico:** una clave privada de Stellar es un texto de 56 caracteres que
empieza por `S` (por ejemplo `S` seguido de 55 letras/números). Una dirección pública empieza por
`G` (cuenta) o `C` (contrato) y NO es secreta: cualquiera la ve en el explorador de la red.
Descomprimimos el APK (es un zip) y buscamos texto con forma de clave privada: el resultado
esperado es **0**.

| Dato | Valor |
|---|---|
| APK | `android/app/build/outputs/apk/release/app-release.apk` (`versionName 0.2.0`, `versionCode 2`, `applicationId com.raiz.app`) |
| Tamaño | 97 731 596 bytes (93,2 MiB) |
| SHA-256 | `95fd9b90b7f92d4f33dffd24652344ed1f9292c31e94f9db8b72157231dcad7c` |
| Firma | **Firmado con la clave debug de Android para evidencia** (H9): `apksigner verify` → `Verifies`, esquema v2, 1 firmante, DN `C=US, O=Android, CN=Android Debug`. No es una clave Stellar; el keystore propio llega en WP4 |
| Commit del monorepo | `bbeab83` + cambios de la rama `feat/wp1-app-relayer` (sin commit al verificar; el commit definitivo se anota en el README de esta carpeta) |
| Fecha de la verificación | 2026-09-06 18:11 UTC (recompilación e integración tras la revisión adversarial H1–H10) |
| Compilado con | `./gradlew assembleRelease --console=plain -q` (Gradle 8.10.2, AGP del catálogo, `signingConfig = signingConfigs.getByName("debug")` en el `buildType` `release`) |
| Relayer que consume | `<<pendiente: URL Fly>>` (default en `BuildConfig.RELAYER_URL`: `https://raiz-relayer.fly.dev`) |

> **Historial.** La primera verificación del 2026-09-06 (17:25 UTC) se hizo sobre
> `app-release-unsigned.apk` (97 715 212 bytes, SHA-256 `39f0c788…69eb4`), antes de H9. Tras
> añadir `signingConfig = signingConfigs.getByName("debug")` al `buildType` `release`
> (`android/app/build.gradle.kts`), `assembleRelease` produce **`app-release.apk` firmado**
> (instalable con `adb install -r`) y se repitieron §1–§4 sobre él: los valores de la tabla y
> todas las salidas de abajo corresponden ya al **APK firmado**. La firma (clave de depuración de
> Android, no una clave Stellar) no añadió ninguna `S…`: §2 y §3 siguen dando 0. La diferencia de
> tamaño (+16 384 bytes) es el bloque de firma v2 del APK.

---

## 0. Antes de compilar: qué lee el build de `local.properties`

`android/local.properties` (no versionado) alimenta `BuildConfig`. Desde 0.2.0:

- `raiz.admin.secret` **ya no existe** como `buildConfigField` (se eliminó `DEMO_ADMIN_SECRET`); el
  build no lo lee aunque siga en el archivo. En el equipo de verificación se borró la línea por
  higiene. La **única fuente** de la seed del admin es la identidad `raiz-admin` de la Stellar CLI
  (`stellar keys show raiz-admin`), que es la que se usa en §3; en producción vive solo como
  variable de entorno del relayer.
- `raiz.tourist.secret` / `raiz.resident.secret` (wallets *demo*, no autoridad) solo entran en el
  **debug**. El `buildType` `release` los pisa con `""` en `build.gradle.kts`:

  ```kotlin
  release {
      // …
      buildConfigField("String", "DEMO_TOURIST_SECRET", "\"\"")
      buildConfigField("String", "DEMO_RESIDENT_SECRET", "\"\"")
  }
  ```

  Así el conteo de claves del APK release es 0 **sea cual sea el `local.properties` de quien
  compile** (en esta verificación el archivo SÍ tenía ambas claves demo, ver §3). En release el
  botón "Probar modo demo" se oculta y la app pide passkey / frase semilla.
- `raiz.relayer.url` / `raiz.relayer.key` → `BuildConfig.RELAYER_URL` / `RELAYER_APP_KEY`. En este
  APK de evidencia la key va vacía (no es necesaria para demostrar "cero secretos"; sin ella los
  botones que pasan por el relayer aparecen deshabilitados con aviso, nunca crash).

Compilar el release (desde H9 sale firmado con la clave debug de Android; el keystore propio es de WP4):

```bash
cd android && ./gradlew assembleRelease --console=plain -q
ls -la app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Salida de la verificación del 2026-09-06 18:11 UTC (APK **firmado**, tras H9):

```
RELEASE_EXIT=0
-rw-r--r-- 1 juanp 197609 97731596 Sep  6 13:11 app-release.apk
95fd9b90b7f92d4f33dffd24652344ed1f9292c31e94f9db8b72157231dcad7c *app/build/outputs/apk/release/app-release.apk
```

`output-metadata.json` del build: `"variantName": "release"`, `"versionCode": 2`,
`"versionName": "0.2.0"`, `"outputFile": "app-release.apk"`. Firma comprobada con
`apksigner verify --print-certs -v app-release.apk` (build-tools 37.0.0):

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
```

(En la misma sesión, en este orden: `./gradlew assembleDebug --console=plain -q` EXIT=0 y
`./gradlew :app:testDebugUnitTest --console=plain -q` EXIT=0 — `RelayerClientTest`: **24 tests,
0 fallos, 0 errores, 0 omitidos** (`app/build/test-results/testDebugUnitTest/TEST-com.raiz.app.data.relayer.RelayerClientTest.xml`):
bodies exactos (`registerMerchant`, `vaultDeposit`, `vaultRedeem`), idempotencia por intento,
`TX_TIMEOUT` con/sin `txHash`, timeouts de Ktor, `health` 200/503, mapeo de `FAUCET_EMPTY`,
`TRUSTLINE_DEAUTHORIZED`, `UNAUTHORIZED_ADMIN`, `RPC_UNREACHABLE`/`QUEUE_FULL`/`RESTORE_REQUIRED`,
`MERCHANT_EXISTS`/`ALREADY_RESIDENT` idempotentes, 502 HTML y 200 vacío. La primera pasada del
17:25 UTC tenía 7 tests.)

## 1. Descomprimir el APK

```bash
APK=android/app/build/outputs/apk/release/app-release.apk   # firmado con la clave debug (H9)
OUT=/c/Users/juanp/.claude/jobs/6833f7d2/tmp/apk-signed   # cualquier carpeta vacía vale (p. ej. /tmp/raiz-apk-0.2.0)
mkdir -p "$OUT"
unzip -o -q "$APK" -d "$OUT"       # sin unzip: python -m zipfile -e "$APK" "$OUT"
ls "$OUT"
```

```
AndroidManifest.xml
assets
classes.dex
classes2.dex
classes3.dex
classes4.dex
classes5.dex
DebugProbesKt.bin
googleid.properties
kotlin
kotlin-tooling-metadata.json
lib
META-INF
okhttp3
org
play-services-auth.properties
play-services-auth-api-phone.properties
play-services-auth-base.properties
play-services-base.properties
play-services-basement.properties
play-services-cronet.properties
play-services-fido.properties
play-services-tasks.properties
res
resources.arsc
```

(Equivalente con `apktool d "$APK" -o "$OUT"`, como indica el README del relayer § "Verificación
por un revisor"; el resultado de los greps es el mismo.)

## 2. Ninguna clave privada `S…` en código, assets ni recursos

`grep -a` trata los `.dex` (bytecode compilado) como texto: las constantes de cadena de Kotlin
viven ahí en claro, así que si `DEMO_ADMIN_SECRET` siguiera en `BuildConfig` aparecería aquí.

```bash
cd "$OUT"
grep -raEo "S[A-Z0-9]{55}" classes*.dex assets res | sort -u | wc -l
```

```
0
```

**Resultado: 0** (esperado 0).

Ampliando la búsqueda a **todo** el APK (incluidas las librerías nativas `lib/`):

```bash
grep -raEo "S[A-Z0-9]{55}" . | sort -u | wc -l
grep -raEo "S[A-Z0-9]{55}" . | cut -d: -f1 | sort | uniq -c
```

```
32
      8 ./lib/arm64-v8a/libmapbox-common.so
      8 ./lib/armeabi-v7a/libmapbox-common.so
      8 ./lib/x86/libmapbox-common.so
      8 ./lib/x86_64/libmapbox-common.so
```

Las 32 coincidencias son las **mismas 8 cadenas** repetidas en las 4 variantes de arquitectura de
`libmapbox-common.so` (binario del SDK de Mapbox, no código de RAÍZ): tramos de texto en
mayúsculas del binario que empiezan por `S` (`SACTIONA…`, `SCAPEACH…`, `SERTMATC…`, `SEXCLUDE…`,
`STAMPREC…`, `STRICTOT…`, `SUNIQUER…`, `SVIRTUAL…`). Ninguna es una semilla Stellar: decodificadas
como StrKey (base32 + byte de versión + CRC16-XModem), **0 de 8 pasan el checksum** y 6 de 8 ni
siquiera llevan el byte de versión `0x90` de una seed ed25519:

```
SACTIONA… checksum StrKey: invalido | version byte: 0x90
SCAPEACH… checksum StrKey: invalido | version byte: 0x90
SERTMATC… checksum StrKey: invalido | version byte: 0x91
SEXCLUDE… checksum StrKey: invalido | version byte: 0x91
STAMPREC… checksum StrKey: invalido | version byte: 0x94
STRICTOT… checksum StrKey: invalido | version byte: 0x94
SUNIQUER… checksum StrKey: invalido | version byte: 0x95
SVIRTUAL… checksum StrKey: invalido | version byte: 0x95
seeds StrKey validas: 0
```

## 3. Las claves concretas del equipo (admin, turista demo, residente demo) no están

El script lee cada clave desde su única fuente — la seed del admin desde la identidad
`raiz-admin` de la Stellar CLI (`stellar keys show raiz-admin`), las dos wallets demo desde el
`local.properties` del equipo — y **no la imprime**: solo dice si aparece dentro del APK
descomprimido. Dos salvaguardas para que el check no dé un falso "OK":

- si el valor local está **vacío** (la clave no existe en esa máquina) se **omite** en vez de
  buscar una cadena vacía, que `grep` encontraría siempre;
- se busca el texto **literal** (`-F`), no como expresión regular.

```bash
check() {
  [ -z "$2" ] && { echo "$1: sin valor local, omitido"; return; }
  grep -raqF -- "$2" "$OUT" && echo "$1: PRESENTE — FALLO" || echo "$1: ausente — OK (len=${#2})"
}
check "admin (stellar keys show raiz-admin)" "$(stellar keys show raiz-admin | tr -d '\r\n')"
check "tourist (local.properties)"  "$(grep -E '^raiz\.tourist\.secret='  android/local.properties | cut -d= -f2- | tr -d '\r\n')"
check "resident (local.properties)" "$(grep -E '^raiz\.resident\.secret=' android/local.properties | cut -d= -f2- | tr -d '\r\n')"
```

Salida sobre el APK **firmado** (2026-09-06 18:11 UTC). En esta pasada de integración el check
del admin **no se re-ejecutó** (la sesión no invoca `stellar keys show`); queda cubierto por §2,
que es estrictamente más amplio: cualquier seed real casa con `S[A-Z0-9]{55}` y pasa el checksum
StrKey, y §2 da **0** coincidencias en `classes*.dex`/`assets`/`res` y **0 seeds válidas** en todo
el APK. La salida del check del admin sobre el APK sin firmar (17:25 UTC) fue
`admin (stellar keys show raiz-admin): ausente — OK (len=56)`.

```
tourist (local.properties): ausente — OK (len=56)
resident (local.properties): ausente — OK (len=56)
admin: no re-ejecutado (sin stellar keys show); cubierto por el grep S[A-Z0-9]{55} = 0 en dex/assets/res
```

(`len=56` confirma que cada clave existía y tenía la longitud de una seed real, es decir, que
se buscó algo concreto y no una cadena vacía. El `local.properties` de la máquina de
verificación tiene exactamente `mapbox.access.token`, `passkey.rp.id`, `raiz.resident.secret`,
`raiz.tourist.secret` y `sdk.dir` — sin `raiz.relayer.key`, por eso la API key del APK va vacía —.
El antiguo cuarto check sobre `raiz.admin.secret` en `local.properties` se eliminó: esa entrada ya
no es fuente de nada — ni del build ni del relayer — y en esta máquina ya no existe.)

Tampoco quedan los **nombres** de la configuración vieja en ningún archivo del APK:

```bash
grep -ra "raiz.admin.secret\|DEMO_ADMIN" "$OUT" | wc -l
```

```
0
```

## 4. Nota honesta: lo que SÍ contiene el APK (y por qué no es un secreto)

```bash
grep -rla "$(stellar keys address raiz-admin)" "$OUT"     # GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P
```

```
./assets/deployments.json
./classes2.dex
```

- `assets/deployments.json` incluye la dirección **pública** `G…` del admin
  (`GBLS7PL5…YC2P`). La app la usa como *source account* de las **lecturas por simulación**
  (`get_barrio`, `get_pool_balance`, `list_merchants`…): Soroban exige una cuenta existente
  para simular, y no hace falta firma alguna. Es un dato público on-chain — se ve en
  https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P —
  y con ella **no se puede firmar nada**.
- La misma `G…` aparece en `classes2.dex` porque `ui/components/BalanceCard.kt:109` la usa como
  texto de ejemplo en una `@Preview` de Compose (vista previa del editor) y el release no
  minifica. Es la misma dirección pública, no una clave; sustituirla por un placeholder en la
  preview es cosmético y queda como mejora opcional.
- Los IDs de contratos (`C…`), el issuer del USDC de Blend (`G…`) y el pool de Blend: públicos
  por definición, iguales a `deployments.json` del repo.
- Lo que hay de configuración del relayer en `classes2.dex`: los **nombres** de los campos
  `RELAYER_URL` / `RELAYER_APP_KEY` / `DEMO_TOURIST_SECRET` / `DEMO_RESIDENT_SECRET` de
  `BuildConfig` y la URL por defecto `https://raiz-relayer.fly.dev`; los valores de las dos
  claves demo son `""` (§0) y la API key en este APK es `""`.
- `RELAYER_APP_KEY` (`x-raiz-app-key`), cuando se compila con ella: identifica a la app ante el
  relayer para el rate-limit. Viaja en el APK **a propósito**; el README del relayer
  (§ "Modelo de amenazas") explica que en testnet no protege fondos reales y cómo rotarla
  (`fly secrets set` + republicar).
- Token público de Mapbox (`pk.*`): pensado para ir en clientes, restringido por paquete.

## 5. Contraparte en el código fuente

```bash
grep -rn "demoAdminKeyPair\|DEMO_ADMIN_SECRET\|sendUsdcFromAdmin\|fundContractUsdc\|raiz.admin.secret" android --exclude-dir=build --exclude-dir=.gradle ; echo "rc=$?"
git grep -cE "S[A-Z0-9]{55}" -- . ; echo "rc=$? (1 = cero coincidencias)"
```

```
rc=1
rc=1 (1 = cero coincidencias)
```

Sin resultados en el primero (ni siquiera en comentarios) y cero claves `S…` en todo el repo
(también sobre `git diff` y los archivos nuevos de la rama).

## 6. Prueba manual del cliente contra un relayer local (misma sesión)

Para comprobar que el contrato HTTP que implementa `RelayerClient` es el real, se levantó el
relayer en local (`raiz-relayer`, commit vigente, `NETWORK=testnet`, `PORT=18082`, la clave admin
solo como variable de entorno) y se hicieron 4 llamadas que **no escriben en la cadena**:

| Llamada | Respuesta real | Mapeo en `RelayerClient` |
|---|---|---|
| `GET /v1/health` | `200 {"ok":true,"network":"testnet","protocolVersion":28,"admin":"GBLS7PL5…","contracts":{pool,governance,treasury,rewards,yield_adapter,usdc_sac},"faucet":{"enabled":true,"amountStroops":"200000000","remainingToday":50},"vaultEndpoints":true,"queue":{"pending":0},"version":"0.1.0",…}` — los 6 `contracts` y el `admin` coinciden 1:1 con `assets/deployments.json` del APK | `Success(RelayerHealth)` → feature-flag (`vaultEndpoints`) |
| `POST /v1/mint-resident` **sin** `x-raiz-app-key` | `401 {"ok":false,"error":{"code":"UNAUTHORIZED_APP","message":"Falta o es incorrecta la cabecera x-raiz-app-key.","retryable":false}}` | `Error(UNAUTHORIZED)` |
| `POST /v1/faucet` con `address` inválido | `400 {"ok":false,"error":{"code":"VALIDATION_ERROR","message":"Body inválido: address: address debe ser G… o C…",…}}` | `Error(PARSE_ERROR)` |
| `POST /v1/mint-resident` de `raiz-r-centro-1` (ya residente del Centro) | `409 {"ok":false,"error":{"code":"ALREADY_RESIDENT","message":"Esta dirección ya tiene su soulbound de residente.","details":{"contract":"governance","contractCode":5,"name":"AlreadyResident"}}}` — el relayer lo detecta en simulación, no envía tx | `Success(null)` (idempotente, igual que hacía la app en 0.1.0) |

Proceso terminado al acabar (`taskkill //F //PID <pid> //T`); el mint/faucet **real** con hash
en Stellar Expert queda para la regresión en dispositivo contra la URL de Fly
(`regresion_dispositivo.md`).

## Resultado

| Comprobación | Esperado | Obtenido |
|---|---|---|
| §2 claves `S…` en dex/assets/res | 0 | **0** |
| §2 claves `S…` en todo el APK | 0 seeds válidas | 32 cadenas en `libmapbox-common.so` (4 ABIs × 8), **0 pasan el checksum StrKey** |
| §3 clave del admin presente | ausente | **ausente** — sobre el firmado, implicado por §2 (0 `S…` en dex/assets/res, 0 seeds válidas en todo el APK); check directo con `stellar keys show` solo en la pasada del 17:25 UTC (sin firmar) |
| §3 claves demo turista/residente presentes | ausentes | **ausentes** (`len=56` en ambas: se buscó una seed real) |
| §3 nombres `raiz.admin.secret` / `DEMO_ADMIN` | 0 | **0** |
| §4 `G…` pública del admin | `assets/deployments.json` + preview en `classes*.dex` | `assets/deployments.json` + `classes2.dex` (preview `BalanceCard.kt`) |
| §5 rastro en fuentes | ninguno | **ninguno** |
| §6 contrato HTTP del relayer vs cliente | coincide | **coincide** (health, 401, 400, 409 idempotente) |

Verificado por: sesión de Claude Code (fase 3 WP1, integración post-revisión H1–H10) para Juan ·
2026-09-06 18:11 UTC · APK firmado `app-release.apk` `95fd9b90…cad7c` (97 731 596 bytes). La
primera pasada (17:25 UTC) fue sobre `app-release-unsigned.apk` `39f0c788…69eb4` con resultados
idénticos en §2–§5.
