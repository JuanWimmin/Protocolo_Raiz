# D1 — Cómo verificar que el APK no lleva claves privadas (1 página)

**Qué demuestra:** el APK de RAÍZ no contiene ninguna clave privada de Stellar. La autoridad del
admin (alta de comercios, soulbound de residente, faucet y vault) vive en el servicio
[`raiz-relayer`](https://github.com/JuanWimmin/raiz-relayer); la app solo le habla por HTTP.

**Si no eres técnico:** una clave privada de Stellar es un texto de 56 caracteres que empieza por
`S`. Una dirección pública empieza por `G` (cuenta) o `C` (contrato) y no es secreta: cualquiera la
ve en el explorador. Un APK es un archivo zip; se abre y se busca texto con forma de clave privada.
El resultado esperado es **0**.

| Dato | Valor |
|---|---|
| APK verificado | [`raiz-0.3.0.apk`](https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.3.0/raiz-0.3.0.apk), del [Release v0.3.0](https://github.com/JuanWimmin/Protocolo_Raiz/releases/tag/v0.3.0) — versión 0.3.0 (`versionCode 3`), con D1 + D2 + D3 |
| Tamaño · SHA-256 | 97 936 229 bytes · `5037394234615e31935c13249569b12abd22769a0be2f1367488a013d771f56c` |
| Firma | Clave de depuración de Android (APK Signature Scheme v2, `CN=Android Debug`). No es una clave de Stellar |
| Fecha | 2026-10-04 |

## Opción A — sin instalar nada (1 clic)

Abre **[las ejecuciones del workflow `verify-apk`](https://github.com/JuanWimmin/Protocolo_Raiz/actions/workflows/verify-apk.yml)**
y entra en la más reciente. GitHub descarga el APK del Release, lo abre y busca claves; el resumen
de la ejecución debe mostrar **"Claves privadas válidas: 0"** y **"Resultado: ✅ cero claves
privadas"**.

## Opción B — en tu equipo (un comando, solo necesita Python 3)

```bash
python scripts/verify_apk_no_secrets.py https://github.com/JuanWimmin/Protocolo_Raiz/releases/download/v0.3.0/raiz-0.3.0.apk
```

Salida del 2026-10-04:

```
SHA-256:   5037394234615e31935c13249569b12abd22769a0be2f1367488a013d771f56c
Tamaño:    97936229 bytes · 740 archivos examinados
Cadenas con FORMA de clave (S + 55 de [A-Z0-9]): 32
       8  lib/arm64-v8a/libmapbox-common.so   (y 8 en cada una de las otras 3 arquitecturas)
Claves privadas de Stellar VÁLIDAS (versión + checksum correctos): 0
RESULTADO: OK — cero claves privadas
```

## Opción C — a mano, con `unzip` y `grep` (lo mismo que hace el script)

```bash
unzip -q raiz-0.3.0.apk -d apk
grep -raEo "S[A-Z0-9]{55}" apk/classes*.dex apk/assets apk/res | sort -u | wc -l   # → 0
grep -raEo "S[A-Z0-9]{55}" apk | cut -d: -f1 | sort | uniq -c                       # → 32, todas en libmapbox-common.so
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
- **La API key de aplicación del relayer**: identifica a la app para el límite de peticiones. Viaja
  en el APK a propósito; no da acceso a fondos (modelo de amenazas de testnet en el README del relayer).
- **El token público de Mapbox** (`pk.*`), pensado para ir en aplicaciones cliente.
- Las wallets demo (turista y residente) existen solo en el build *debug* de desarrollo. El build
  *release* las deja vacías en `android/app/build.gradle.kts`, compile quien compile.

## Resultado

| Comprobación | Esperado | Obtenido |
|---|---|---|
| Claves `S…` en código, assets y recursos | 0 | **0** |
| Claves privadas válidas en todo el APK | 0 | **0** (32 cadenas con esa forma en `libmapbox-common.so`, ninguna pasa el checksum) |
| Claves demo del equipo (turista, residente) dentro del APK | ausentes | **ausentes** (se buscó cada una, de 56 caracteres) |
| Nombres de la configuración vieja (`DEMO_ADMIN`, `raiz.admin.secret`) | 0 | **0** |
| Claves privadas en el historial de git (todas las ramas, repo de la app y del relayer) | 0 | **0** |

Bitácora completa de la primera verificación (APK 0.2.0, 6 de septiembre), con cada comando y su
salida: [`verificacion_apk_0.2.0_bitacora.md`](verificacion_apk_0.2.0_bitacora.md).
