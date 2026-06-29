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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Secrets demo — desde local.properties, NO del repo.
        // En release real estarían vacíos y la app pediría seed phrase / passkey.
        buildConfigField("String", "DEMO_TOURIST_SECRET", "\"${localProp("raiz.tourist.secret")}\"")
        buildConfigField("String", "DEMO_RESIDENT_SECRET", "\"${localProp("raiz.resident.secret")}\"")
        buildConfigField("String", "DEMO_ADMIN_SECRET", "\"${localProp("raiz.admin.secret")}\"")
        // Mapbox public token (pk.*) — runtime de la app para descargar tiles.
        buildConfigField("String", "MAPBOX_TOKEN", "\"${localProp("mapbox.access.token")}\"")
        // DeFindex API key (OPCIONAL) — solo para mostrar APY en vivo vía REST.
        // Sin ella la pantalla Yield funciona igual con datos on-chain
        // (precio por share, TVL, rendimiento). Va en local.properties, no en el repo.
        buildConfigField("String", "DEFINDEX_API_KEY", "\"${localProp("defindex.api.key")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // ZXing para QR: core (generación) + embedded (Activity de escaneo).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    // EncryptedSharedPreferences para guardar la seed phrase de la wallet
    // cifrada con clave del Android Keystore.
    implementation(libs.androidx.security.crypto)
    // Mapbox Maps + extensión Compose.
    implementation(libs.mapbox.maps.android)
    implementation(libs.mapbox.maps.compose)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
}
