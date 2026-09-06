package com.raiz.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import com.raiz.app.data.relayer.RelayerJson
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Named
import javax.inject.Singleton

/**
 * Módulo Hilt para la capa data.
 *
 * DeploymentsLoader, SorobanClient y WalletManager se resuelven directamente
 * desde sus `@Inject constructor` + `@Singleton`, no necesitan @Provides.
 *
 * Aquí sí caben provisiones que no se pueden inyectar por constructor, como
 * el HttpClient del relayer admin (D1 del SOW, ver data/relayer/RelayerClient.kt).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * HttpClient dedicado a `raiz-relayer` (registro de comercios, soulbound de
     * residentes, faucet y vault firmados server-side — ver
     * `data/relayer/RelayerClient.kt` y `raiz-relayer/README.md`).
     *
     * Se distingue con `@Named("relayer")` porque no es el mismo cliente que usa
     * el Stellar SDK internamente para Horizon/RPC (ese lo trae el SDK, no lo
     * proveemos nosotros).
     *
     * Engine **CIO**: mismo que arrastra el Stellar SDK. Conscrypt ya se instala
     * como provider TLS preferido en `RaizApplication.onCreate()`, así que este
     * cliente lo hereda automáticamente sin configuración adicional aquí.
     *
     * Timeout de **95 s**: el relayer encola una única transacción admin a la vez
     * (una cuenta admin = un sequence number). Cada job tiene un deadline interno
     * de 70 s (`JOB_DEADLINE_MS`) más hasta 15 s de la request al RPC que
     * estuviera en vuelo al vencer (`RPC_REQUEST_TIMEOUT_MS`), con reintentos de
     * propagación — ver `raiz-relayer/README.md` § Variables de entorno. Un
     * timeout de 30 s aquí cortaría respuestas válidas.
     */
    @Provides
    @Singleton
    @Named("relayer")
    fun provideRelayerHttpClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        // Mismo Json que usa RelayerClient para decodificar (y los tests): una sola definición.
        install(ContentNegotiation) {
            json(RelayerJson)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 95_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 95_000L
        }
    }
}
