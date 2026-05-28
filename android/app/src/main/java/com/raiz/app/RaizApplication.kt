package com.raiz.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Application class de RAÍZ.
 *
 * Responsable de tres setups críticos al arrancar la app:
 *
 *   1. Conscrypt como provider TLS #1. Reemplaza el JSSE histórico de
 *      Android por la implementación moderna de Google (igual que la usan
 *      Chrome y Play Services).
 *
 *   2. SSLContext default que confía en:
 *      - System CAs (lo normal)
 *      - El intermediate de Sectigo R36 (res/raw/sectigo_intermediate.pem),
 *        porque el cert de soroban-testnet.stellar.org está firmado por esa
 *        intermediate y algunos OEMs (Vivo, Oppo) no la incluyen en sistema.
 *        Esto deja la cadena válida sin tocar el trust store del dispositivo.
 *
 *   3. (Próximamente) Mapbox access token.
 *
 * Si Conscrypt o el SSLContext fallan, la app sigue corriendo — pero las
 * llamadas a soroban-testnet.stellar.org fallarán con
 * "Trust anchor for certification path not found".
 */
@HiltAndroidApp
class RaizApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installSecurity()
        // TODO: MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
    }

    private fun installSecurity() {
        runCatching {
            // 1. Conscrypt como provider #1
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)

            // 2. KeyStore con system CAs + nuestro intermediate Sectigo
            val mergedKeyStore = buildMergedKeyStore()

            // 3. TrustManager y SSLContext
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(mergedKeyStore)
            val sslContext = SSLContext.getInstance("TLS", provider)
            sslContext.init(null, tmf.trustManagers, null)
            SSLContext.setDefault(sslContext)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)

            Log.i(TAG, "TLS configurado: Conscrypt + Sectigo intermediate")
        }.onFailure {
            Log.e(TAG, "installSecurity falló: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Construye un KeyStore que tiene todos los CAs del sistema Android +
     * el intermediate de Sectigo empacado en res/raw/.
     */
    private fun buildMergedKeyStore(): KeyStore {
        val merged = KeyStore.getInstance(KeyStore.getDefaultType())
        merged.load(null, null)

        // Copia de los system CAs (alias-by-alias).
        val sys = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val aliases = sys.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            sys.getCertificate(alias)?.let { merged.setCertificateEntry(alias, it) }
        }

        // Sectigo intermediate manual
        val certFactory = CertificateFactory.getInstance("X.509")
        resources.openRawResource(R.raw.sectigo_intermediate).use { input ->
            val cert = certFactory.generateCertificate(input)
            merged.setCertificateEntry("sectigo_intermediate_r36", cert)
        }
        return merged
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
