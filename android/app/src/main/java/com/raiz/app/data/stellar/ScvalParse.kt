package com.raiz.app.data.stellar

import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.scval.Scv
import com.soneso.stellar.sdk.xdr.SCValXdr

/**
 * Helpers de parseo SCVal → tipos Kotlin.
 *
 * Los contratos Soroban serializan structs como `Map<Symbol, SCVal>` y enums
 * como `Symbol`. Estos helpers ahorran boilerplate al consumir resultados de
 * `ContractClient.invoke`.
 */
internal object ScvalParse {

    /** Convierte un SCVal Map (struct) en Map<String, SCValXdr> por field name. */
    fun asStruct(scval: SCValXdr): Map<String, SCValXdr> {
        val entries = Scv.fromMap(scval)
        return entries.entries.associate { (k, v) -> Scv.fromSymbol(k) to v }
    }

    /** Convierte un SCVal Vec en List<SCValXdr>. */
    fun asVec(scval: SCValXdr): List<SCValXdr> = Scv.fromVec(scval)

    /** Extrae una Address de un SCVal (acepta G... y C...). */
    fun asAddressString(scval: SCValXdr): String =
        Address.fromSCVal(scval).toString()

    /** Extrae el hex de un SCVal Bytes (típicamente BytesN<32> como barrio_id). */
    fun asHex(scval: SCValXdr): String =
        Scv.fromBytes(scval).joinToString("") { "%02x".format(it) }

    /** SCVal i128 → Long (stroops). */
    fun asLong(scval: SCValXdr): Long =
        Scv.fromInt128(scval).longValue(exactRequired = false)

    /** SCVal u64 → Long (timestamps, counters). */
    fun asULongAsLong(scval: SCValXdr): Long =
        Scv.fromUint64(scval).toLong()

    /** SCVal u32 → Int. */
    fun asUIntAsInt(scval: SCValXdr): Int =
        Scv.fromUint32(scval).toInt()

    fun asString(scval: SCValXdr): String = Scv.fromString(scval)
    fun asSymbol(scval: SCValXdr): String = Scv.fromSymbol(scval)
    fun asBoolean(scval: SCValXdr): Boolean = Scv.fromBoolean(scval)
    fun asInt32(scval: SCValXdr): Int = Scv.fromInt32(scval)

    /**
     * Extrae el discriminante (Symbol) de un enum unit-only de Soroban.
     *
     * Los `#[contracttype] enum Foo { A, B }` (sin variantes con datos) se
     * serializan como `SCV_VEC` cuyo primer elemento es un `SCV_SYMBOL` con
     * el nombre del variante: por ejemplo `[SCV_SYMBOL("Active")]`.
     *
     * Si el valor llega como Symbol directo (versiones antiguas del host)
     * también lo manejamos como fallback.
     */
    fun asEnumSymbol(scval: SCValXdr): String =
        runCatching { Scv.fromSymbol(Scv.fromVec(scval).first()) }
            .getOrElse { Scv.fromSymbol(scval) }
}
