# Setup de Mapbox para RAÍZ (Android · Kotlin · Jetpack Compose)

Guía paso a paso. Mapbox es el primer tropiezo común en Android porque necesita
DOS tokens distintos y un repositorio Maven privado. Sigue el orden exacto.

---

## Paso 0 — Crear cuenta y entender los dos tokens

Mapbox usa dos tokens diferentes. Confundirlos es el error #1:

| Token | Para qué | Dónde va | Empieza con |
|---|---|---|---|
| **Public token** | Lo usa la app en runtime para cargar mapas | `strings.xml` o recurso | `pk.*` |
| **Secret/Download token** | Descarga el SDK desde el Maven privado de Mapbox | `~/.gradle/gradle.properties` (NUNCA en el repo) | `sk.*` |

1. Crea cuenta en https://account.mapbox.com
2. El **public token** (`pk....`) ya viene por defecto en tu cuenta → "Tokens".
3. Crea un **secret token** (`sk....`): botón "Create a token", marca SOLO el scope `Downloads:Read`. Cópialo (solo se muestra una vez).

---

## Paso 1 — Configurar el secret token (descarga del SDK)

Edita (o crea) el archivo `~/.gradle/gradle.properties` en tu MÁQUINA, fuera del proyecto:

```properties
MAPBOX_DOWNLOADS_TOKEN=sk.eyJ1Ijoi...tu_secret_token
```

Esto mantiene el secret fuera del control de versiones. Cada miembro del equipo
pone el suyo en su propia máquina.

---

## Paso 2 — Repositorio Maven (settings.gradle.kts)

En `settings.gradle.kts`, dentro de `dependencyResolutionManagement { repositories { ... } }`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").get()
            }
        }
    }
}
```

> Nota: `username` es literalmente la palabra `"mapbox"`, no tu usuario.

---

## Paso 3 — Dependencia (app/build.gradle.kts)

```kotlin
dependencies {
    // Mapbox Maps SDK para Android con soporte Compose
    implementation("com.mapbox.maps:android:11.9.0")
    implementation("com.mapbox.extension:maps-compose:11.9.0")
    // ... resto de tus dependencias
}
```

> Verifica la última versión estable en https://docs.mapbox.com/android/maps/guides/ —
> la 11.x es la línea actual en 2026. Compose extension va emparejada a la misma versión.

---

## Paso 4 — Public token en la app

Crea `app/src/main/res/values/mapbox_access_token.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="mapbox_access_token" translatable="false">pk.eyJ1Ijoi...tu_public_token</string>
</resources>
```

> Para producción, no hardcodees el `pk.` en el XML versionado. Usa
> `local.properties` + BuildConfig, o un recurso ignorado por git. Para el MVP
> del hackathon, el XML está bien si el repo es privado.

Inicializa el token donde arranca la app (Application class o antes de mostrar el mapa):

```kotlin
import com.mapbox.common.MapboxOptions

class RaizApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
    }
}
```

Registra la Application en `AndroidManifest.xml`:
```xml
<application
    android:name=".RaizApplication"
    ... >
```

---

## Paso 5 — Permisos (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Pide `ACCESS_FINE_LOCATION` en runtime (Android 6+) antes de centrar el mapa en el usuario.
Usa `androidx.activity.compose.rememberLauncherForActivityResult` con
`ActivityResultContracts.RequestPermission`.

---

## Paso 6 — Mapa en Compose con los pines de RAÍZ

Esqueleto de `BarrioMapScreen.kt`. Dibuja un pin por cada `Merchant` de
`Pool.listMerchants()`, coloreado por categoría:

```kotlin
import androidx.compose.runtime.*
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotation
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState

@Composable
fun BarrioMapScreen(
    merchants: List<Merchant>,
    onMerchantTap: (Merchant) -> Unit
) {
    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            // Centro Histórico de Bogotá como ejemplo
            center(Point.fromLngLat(-74.075, 4.598))
            zoom(15.0)
        }
    }

    MapboxMap(
        modifier = Modifier.fillMaxSize(),
        mapViewportState = mapViewportState
    ) {
        merchants.forEach { merchant ->
            PointAnnotation(
                point = Point.fromLngLat(merchant.lng, merchant.lat)
            ) {
                // iconImage según merchant.category (cafe, artesania, etc.)
                // textField = merchant.name
                interactionsState.onClicked {
                    onMerchantTap(merchant)
                    true
                }
            }
        }
    }
}
```

> Recuerda: en `RaizModels.kt`, `Merchant.lat` y `Merchant.lng` ya convierten
> el `latE6/lngE6` del contrato a Double. Mapbox usa `(lng, lat)` en ese orden
> (longitud primero) — error común invertirlos.

Al tocar un pin, abre un bottom sheet con `MerchantMapPin` (nombre, categoría,
aporte acumulado al barrio, botón "Pagar aquí").

---

## Errores comunes y solución rápida

| Error | Causa | Solución |
|---|---|---|
| `401 Unauthorized` al hacer Gradle sync | Secret token mal o sin scope Downloads:Read | Revisa `~/.gradle/gradle.properties`, regenera el `sk.` con el scope correcto |
| Mapa carga gris/en blanco | Public token no inicializado | Verifica `MapboxOptions.accessToken` antes de mostrar el mapa |
| `Could not find com.mapbox.maps:android` | Repo Maven no configurado | Revisa el bloque maven en settings.gradle.kts |
| Pines en el océano (lat/lng invertidos) | Orden lng/lat | Mapbox usa `Point.fromLngLat(lng, lat)` — longitud primero |
| Build falla en CI | Secret token no está en CI | Añade `MAPBOX_DOWNLOADS_TOKEN` como secret/env var del CI |

---

## Alternativa si Mapbox da mucha guerra

Si en mitad del hackathon Mapbox se vuelve un cuello de botella, el plan B es
**Google Maps Compose** (`com.google.maps.android:maps-compose`). Solo necesita
UNA API key, sin repo Maven privado. Estéticamente es menos personalizable pero
se monta en 15 minutos. El modelo de datos (`Merchant` con lat/lng) no cambia,
solo la capa de UI del mapa — así que migrar es barato.
