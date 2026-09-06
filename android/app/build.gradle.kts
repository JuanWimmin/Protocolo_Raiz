import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Carga `local.properties` (NO commiteado) para inyectar secrets/config local
// como BuildConfig fields. Pattern estándar de Android para evitar exponer
// valores sensibles en el repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key, default)

android {
    namespace = "com.raiz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.raiz.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Secrets demo — desde local.properties, NO del repo.
        // En release real estarían vacíos y la app pediría seed phrase / passkey.
        buildConfigField("String", "DEMO_TOURIST_SECRET", "\"${localProp("raiz.tourist.secret")}\"")
        buildConfigField("String", "DEMO_RESIDENT_SECRET", "\"${localProp("raiz.resident.secret")}\"")
        // Mapbox public token (pk.*) — runtime de la app para descargar tiles.
        buildConfigField("String", "MAPBOX_TOKEN", "\"${localProp("mapbox.access.token")}\"")

        // Passkey / smart account (WebAuthn secp256r1).
        // rpId DEBE coincidir con el dominio del servidor de assetlinks.json en
        // producción. En desarrollo puede dejarse vacío — el botón "Crear wallet
        // con passkey" se oculta cuando está vacío (ver WelcomeScreen.kt).
        // Ejemplo de local.properties:
        //   passkey.rp.id=raiz.app
        //   passkey.rp.name=RAIZ
        buildConfigField("String", "PASSKEY_RP_ID",   "\"${localProp("passkey.rp.id")}\"")
        buildConfigField("String", "PASSKEY_RP_NAME", "\"${localProp("passkey.rp.name", "RAIZ")}\"")

        // Relayer admin (D1 SOW): la app ya no lleva la clave privada del admin del
        // protocolo (antes un buildConfigField más aquí); llama por HTTP a
        // raiz-relayer (ver data/relayer/RelayerClient.kt). La API key
        // viaja en el APK (no es secreto real, ver README del relayer § modelo de
        // amenazas) — se rota con `fly secrets set` + republish si hace falta.
        buildConfigField("String", "RELAYER_URL", "\"${localProp("raiz.relayer.url", "https://raiz-relayer.fly.dev")}\"")
        buildConfigField("String", "RELAYER_APP_KEY", "\"${localProp("raiz.relayer.key")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // D1 SOW: el APK release NO lleva ninguna clave privada `S…`, tampoco
            // las wallets demo (turista/residente), aunque estén en local.properties.
            // Un buildConfigField en el buildType pisa al de defaultConfig; así la
            // verificación por descompilación (docs/evidencia_sow/d1/verificacion_apk.md)
            // no depende del local.properties de quien compile. En release el
            // botón "Probar modo demo" se oculta (WelcomeScreen) y la app pide
            // passkey / frase semilla.
            buildConfigField("String", "DEMO_TOURIST_SECRET", "\"\"")
            buildConfigField("String", "DEMO_RESIDENT_SECRET", "\"\"")
            // Firma de evidencia D1 (instalable en dispositivo); WP4 la sustituye por
            // keystore propio leído de local.properties/env. La clave de firma debug
            // NO es un secreto de Stellar: el APK sigue sin ninguna `S…` (ver
            // docs/evidencia_sow/d1/verificacion_apk.md).
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Primer test JVM del módulo (RelayerClientTest): sin esto, cualquier
    // android.util.Log.* que el código de producción llame durante el test
    // (p. ej. RelayerClient logueando errores del relayer) lanza
    // "Method ... not mocked" — el stub de android.jar para unit tests no
    // implementa Log por defecto. Fix estándar de AGP (enlazado desde el
    // propio mensaje de error), no un mock framework nuevo.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Stellar SDK arrastra BouncyCastle, kotlinx, jspecify... varios JARs
            // duplican entradas inocuas bajo META-INF/. Las descartamos.
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose vía BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Navigation Compose + Hilt integration
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Stellar SDK (Soneso KMP) — Horizon + Soroban RPC + SEP-*
    implementation(libs.stellar.sdk)
    // BigInteger usado por Scv.fromInt128 — trayéndolo a la API pública.
    implementation(libs.ionspin.bignum)
    // Conscrypt — provider TLS de Google. Se instala en RaizApplication.onCreate
    // para que Ktor/JSSE use el trust store de Android al conectar a testnet.
    implementation(libs.conscrypt.android)
    // Ktor core — necesario para referenciar HttpClient en HorizonStream.
    implementation(libs.ktor.client.core)
    // Ktor — cliente HTTP hacia el relayer admin (D1 SOW). CIO = mismo engine que
    // usa el Stellar SDK (mantiene consistencia con Conscrypt instalado en
    // RaizApplication); content-negotiation + serialization-json para el envelope
    // JSON del relayer (ver data/relayer/RelayerModels.kt).
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // ZXing para QR: core (generación) + embedded (Activity de escaneo).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    // EncryptedSharedPreferences para guardar la seed phrase de la wallet
    // cifrada con clave del Android Keystore.
    implementation(libs.androidx.security.crypto)
    // Biometric — bloqueo de la app (huella/rostro + PIN del dispositivo).
    implementation(libs.androidx.biometric)
    // Credential Manager para passkey WebAuthn. Requerido por AndroidWebAuthnProvider
    // del Stellar SDK. credentials-play-services-auth amplía soporte FIDO2 a Android 8-9
    // a través de Google Play Services.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    // Mapbox Maps + extensión Compose.
    implementation(libs.mapbox.maps.android)
    implementation(libs.mapbox.maps.compose)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Debug
    debugImplementation(libs.compose.ui.tooling)

    // Test JVM puro (src/test) — RelayerClientTest y sucesores.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
