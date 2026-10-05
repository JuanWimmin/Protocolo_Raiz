# D1 — Cómo verificar que el APK no lleva claves privadas (1 página)

**Qué demuestra:** el APK de RAÍZ no contiene ninguna clave privada de Stellar ni ninguna otra
credencial. La autoridad del admin (alta de comercios, soulbound de residente, faucet y vault) vive
en el servicio [`raiz-relayer`](https://github.com/JuanWimmin/raiz-relayer); la app solo le habla
por HTTPS, sin API key.

**Si no eres técnico:** una clave privada de Stellar es un texto de 56 caracteres que empieza por
`S`. Una dirección pública empieza por `G` (cuenta) o `C` (contrato) y no es secreta: cualquiera la
ve en el explorador. Un APK es un archivo zip; se abre y se busca texto con forma de clave privada.
El resultado esperado es **0**.

| Dato | Valor |
|---|---|
| APK verificado | [`raiz-0.4.0.apk`](https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.4.0/raiz-0.4.0.apk), del [Release v0.4.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.4.0) — versión 0.4.0 (`versionCode 4`), con D1 + D2 + D3 |
| Tamaño · SHA-256 | 98 001 833 bytes · `296f30d862a78187230a93d26fdc291a25faeb1cea8bd134b2d669dd802569d0` |
| Firma | Clave de depuración de Android (APK Signature Scheme v2, `CN=Android Debug`). No es una clave de Stellar |
| Fecha | 2026-10-04 |

## Opción A — sin instalar nada (1 clic)

Abre **[las ejecuciones del workflow `verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)**
y entra en la más reciente. GitHub descarga el APK del Release, lo abre y busca claves. La ejecución
debe estar en verde y su recuadro *Annotations* debe decir **"claves privadas válidas: 0"**, junto
al SHA-256 del APK. Si el verificador encontrara una sola clave, la ejecución saldría en rojo. Con
sesión de GitHub iniciada se ven además la tabla completa y los logs. Captura de una ejecución del
4-oct (sobre el APK 0.3.0), tal como la ve alguien sin sesión:
[`d1_verify_apk_workflow_2026-10-04.png`](capturas/d1_verify_apk_workflow_2026-10-04.png).

## Opción B — en tu equipo (un comando desde una copia del repo; solo necesita Python 3)

```bash
python scripts/verify_apk_no_secrets.py https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.4.0/raiz-0.4.0.apk
```

Salida del 2026-10-04:

```
SHA-256:   296f30d862a78187230a93d26fdc291a25faeb1cea8bd134b2d669dd802569d0
Tamaño:    98001833 bytes · 740 archivos examinados
Cadenas con FORMA de clave (S + 55 de [A-Z0-9]): 32
       8  lib/arm64-v8a/libmapbox-common.so   (y 8 en cada una de las otras 3 arquitecturas)
Claves privadas de Stellar VÁLIDAS (versión + checksum correctos): 0
RESULTADO: OK — cero claves privadas
```

## Opción C — a mano, con `unzip` y `grep` (lo mismo que hace el script)

```bash
unzip -q raiz-0.4.0.apk -d apk
grep -raEo "S[A-Z0-9]{55}" apk/classes*.dex apk/assets apk/res | sort -u | wc -l   # → 0
grep -raEo "S[A-Z0-9]{55}" apk | cut -d: -f1 | sort | uniq -c                       # → 4 líneas de 8 (32), todas libmapbox-common.so
```

En el código de la app (`classes*.dex`), en `assets/` y en `res/` hay **0** cadenas con forma de
clave. Las 32 del segundo comando son 8 tramos de texto en mayúsculas del binario del SDK de Mapbox
(`SACTIONA…`, `SVIRTUAL…`…), repetidos en sus 4 arquitecturas. Ninguna es una clave: una clave real
lleva un byte de versión y un checksum, y el script comprueba ambos (por eso dice 0 válidas).

## El verificador sí encuentra claves cuando las hay

- `python scripts/verify_apk_no_secrets.py --self-test` planta una clave aleatoria en un APK de
  prueba (en el bytecode, en UTF-16, pegada a otras mayúsculas y dentro de un zip anidado) y exige
  encontrarla en los 4 sitios.
- Sobre el APK del hackathon (`RAIZ-v0.1.0.apk`, SHA-256 `6dd907cc…427b4ea5`) el mismo comando
  encontró **3 claves privadas válidas**: la del admin y las de dos wallets demo. Ese era el
  bloqueador que el SOW declara. Ese APK se retiró del Release y la clave del admin que llevaba
  dentro se **revocó on-chain** el 2026-10-04: ver ["Rotación de la clave del admin"](README.md#rotación-de-la-clave-del-admin-2026-10-04).

## Qué sí contiene el APK, y por qué no es un secreto

- **La dirección pública del admin** (`GBLS7PL5…YC2P`), solo en `assets/deployments.json`. La app
  la usa como cuenta de origen al *leer* de los contratos por simulación; con ella no se firma nada.
- **Direcciones de contratos (`C…`) y el emisor del USDC**: públicas por definición.
- **La URL del relayer** (`https://raiz-relayer.fly.dev`), que es pública. **Ya no hay API key**:
  hasta la versión 0.3.0 el APK llevaba una API key de aplicación para identificarse ante el
  relayer; la 0.4.0 la eliminó (el relayer es público y se protege con cupos del lado del servidor).
  En el APK 0.4.0 no aparecen ni su valor, ni el nombre de la cabecera (`x-raiz-app-key`), ni el
  campo `RELAYER_APP_KEY`.
- **El token público de Mapbox** (`pk.*`), pensado para ir en aplicaciones cliente.
- Las wallets demo (turista y residente) existen solo en el build *debug* de desarrollo. El build
  *release* las deja vacías en `android/app/build.gradle.kts`, compile quien compile.
- **Ninguna clave para depositar.** Cuando una wallet passkey deposita con el anchor (D3), la app
  genera en el teléfono una cuenta de tránsito y guarda su frase cifrada en el almacenamiento
  privado de la app. Esa clave nace en el dispositivo de cada usuario: no viene en el APK.

## Resultado

| Comprobación | Esperado | Obtenido |
|---|---|---|
| Claves `S…` en código, assets y recursos | 0 | **0** |
| Claves privadas válidas en todo el APK | 0 | **0** (32 cadenas con esa forma en `libmapbox-common.so`, ninguna pasa el checksum) |
| Claves demo del equipo (turista, residente) dentro del APK | ausentes | **ausentes** (se buscó cada una, de 56 caracteres) |
| Nombres de la configuración vieja (`DEMO_ADMIN`, `raiz.admin.secret`) | 0 | **0** |
| API key del relayer: su valor, la cabecera `x-raiz-app-key` y el campo `RELAYER_APP_KEY` | ausentes | **ausentes** |
| Claves privadas en el historial de git (todas las ramas, repo de la app y del relayer) | 0 | **0** |

Bitácora completa de la primera verificación (APK 0.2.0, 6 de septiembre), con cada comando y su
salida: [`verificacion_apk_0.2.0_bitacora.md`](verificacion_apk_0.2.0_bitacora.md).
