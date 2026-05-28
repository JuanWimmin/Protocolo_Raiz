package com.raiz.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt para la capa data.
 *
 * Por ahora vacío: DeploymentsLoader, SorobanClient y WalletManager se
 * resuelven directamente desde sus `@Inject constructor` + `@Singleton`,
 * no necesitan @Provides.
 *
 * Aquí caben futuras provisiones que no se pueden inyectar por constructor:
 *   - SorobanServer pre-configurado con la URL de testnet/mainnet.
 *   - OkHttpClient compartido para Horizon SSE.
 *   - KeyPair source dummy para llamadas read-only.
 *   - Json de kotlinx-serialization con opciones globales.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    // Vacío por diseño — ver comentario arriba.
}
