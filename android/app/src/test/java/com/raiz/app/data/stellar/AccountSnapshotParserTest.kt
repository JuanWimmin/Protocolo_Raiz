package com.raiz.app.data.stellar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests JVM puros de [DepositPlan.parseAccountSnapshot]: la "foto" de una cuenta clásica a
 * partir del JSON de `GET /accounts/{id}` de Horizon. Los montos deben salir exactos en
 * stroops y un saldo que no se pudo leer nunca debe convertirse en un 0 inventado.
 */
class AccountSnapshotParserTest {

    @Test
    fun `cuenta real con los dos USDC y saldo nativo`() {
        // Respuesta real de Horizon testnet (4-oct) de la wallet de la prueba de D3, recortada
        // a los campos que se leen: 24,5229258 USDC del fondo, 0 del anchor (con trustline).
        val snapshot = DepositPlan.parseAccountSnapshot(parse(REAL_ACCOUNT_JSON), ANCHOR_ISSUER, FUND_ISSUER)

        assertEquals(21_525_371_065_204_741L, snapshot.sequence)
        assertEquals(99_999_117_785L, snapshot.xlmStroops)
        assertEquals(245_229_258L, snapshot.fundUsdcStroops)
        // Trustline con saldo 0 → 0, NO null (null significa "sin trustline").
        assertEquals(0L, snapshot.anchorUsdcStroops)
    }

    @Test
    fun `sin trustline al USDC del anchor ese saldo es null`() {
        val json = account(balances = listOf(usdc("24.5229258", FUND_ISSUER), native("9999.9117785")))
        val snapshot = DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)

        assertNull(snapshot.anchorUsdcStroops)
        assertEquals(245_229_258L, snapshot.fundUsdcStroops)
    }

    @Test
    fun `cuenta recien fondeada sin trustlines`() {
        val json = account(balances = listOf(native("10000.0000000")))
        val snapshot = DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)

        assertEquals(100_000_000_000L, snapshot.xlmStroops)
        assertNull(snapshot.anchorUsdcStroops)
        assertNull(snapshot.fundUsdcStroops)
    }

    @Test
    fun `mismo codigo con otro emisor no se confunde`() {
        // Un tercer "USDC" de un emisor ajeno no es ni el del anchor ni el del fondo.
        val json = account(
            balances = listOf(
                usdc("999.0000000", OTHER_ISSUER),
                usdc("4.5000000", ANCHOR_ISSUER),
                native("9999.0000000"),
            ),
        )
        val snapshot = DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)

        assertEquals(45_000_000L, snapshot.anchorUsdcStroops)
        assertNull(snapshot.fundUsdcStroops)
    }

    @Test
    fun `los montos de 7 decimales salen exactos en stroops`() {
        val json = account(
            balances = listOf(
                usdc("0.0000001", ANCHOR_ISSUER),
                usdc("4.5229258", FUND_ISSUER),
                native("1.5000000"),
            ),
        )
        val snapshot = DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)

        assertEquals(1L, snapshot.anchorUsdcStroops)
        assertEquals(45_229_258L, snapshot.fundUsdcStroops)
        assertEquals(15_000_000L, snapshot.xlmStroops)
    }

    @Test
    fun `ignora otros activos y participaciones de pools de liquidez`() {
        val json = account(
            balances = listOf(
                """{"balance":"12.0000000","asset_type":"credit_alphanum4","asset_code":"SRT","asset_issuer":"$ANCHOR_ISSUER"}""",
                """{"balance":"500.0000000","liquidity_pool_id":"23283282cba3c5363761ac9a7ce1ca027f6205f07107c730a76cfc021b9439e9","asset_type":"liquidity_pool_shares"}""",
                usdc("4.5000000", ANCHOR_ISSUER),
                native("9999.0000000"),
            ),
        )
        val snapshot = DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)

        assertEquals(45_000_000L, snapshot.anchorUsdcStroops)
        assertNull(snapshot.fundUsdcStroops)
        assertEquals(99_990_000_000L, snapshot.xlmStroops)
    }

    @Test
    fun `sin sequence lanza en vez de inventar`() {
        val json = parse("""{"balances":[${native("1.0000000")}]}""")
        assertThrows(IllegalArgumentException::class.java) {
            DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)
        }
    }

    @Test
    fun `sin balances lanza`() {
        val json = parse("""{"sequence":"5"}""")
        assertThrows(IllegalArgumentException::class.java) {
            DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)
        }
    }

    @Test
    fun `sin saldo nativo lanza`() {
        val json = account(balances = listOf(usdc("4.5000000", ANCHOR_ISSUER)))
        assertThrows(IllegalArgumentException::class.java) {
            DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)
        }
    }

    @Test
    fun `un monto ilegible lanza en vez de contarse como 0`() {
        val json = account(balances = listOf(usdc("cuatro", ANCHOR_ISSUER), native("9999.0000000")))
        assertThrows(IllegalArgumentException::class.java) {
            DepositPlan.parseAccountSnapshot(json, ANCHOR_ISSUER, FUND_ISSUER)
        }
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun account(balances: List<String>): JsonObject =
        parse("""{"sequence":"100","balances":[${balances.joinToString(",")}]}""")

    private fun usdc(balance: String, issuer: String): String =
        """{"balance":"$balance","limit":"922337203685.4775807","asset_type":"credit_alphanum4","asset_code":"USDC","asset_issuer":"$issuer"}"""

    private fun native(balance: String): String =
        """{"balance":"$balance","buying_liabilities":"0.0000000","selling_liabilities":"0.0000000","asset_type":"native"}"""

    private companion object {
        const val FUND_ISSUER = "GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56"
        const val ANCHOR_ISSUER = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        const val OTHER_ISSUER = "GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P"

        val REAL_ACCOUNT_JSON = """
            {
              "id": "GABZUFA64FJOIO5MFOZGQ4CBIOPHN47U7CXTSM4NXEPCIKUJ2BGTISJ3",
              "account_id": "GABZUFA64FJOIO5MFOZGQ4CBIOPHN47U7CXTSM4NXEPCIKUJ2BGTISJ3",
              "sequence": "21525371065204741",
              "sequence_ledger": 5023532,
              "subentry_count": 2,
              "last_modified_ledger": 5023532,
              "balances": [
                {
                  "balance": "24.5229258",
                  "limit": "922337203685.4775807",
                  "buying_liabilities": "0.0000000",
                  "selling_liabilities": "0.0000000",
                  "last_modified_ledger": 5024999,
                  "is_authorized": true,
                  "is_authorized_to_maintain_liabilities": true,
                  "asset_type": "credit_alphanum4",
                  "asset_code": "USDC",
                  "asset_issuer": "GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56"
                },
                {
                  "balance": "0.0000000",
                  "limit": "922337203685.4775807",
                  "buying_liabilities": "0.0000000",
                  "selling_liabilities": "0.0000000",
                  "last_modified_ledger": 5011983,
                  "is_authorized": true,
                  "is_authorized_to_maintain_liabilities": true,
                  "asset_type": "credit_alphanum4",
                  "asset_code": "USDC",
                  "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
                },
                {
                  "balance": "9999.9117785",
                  "buying_liabilities": "0.0000000",
                  "selling_liabilities": "0.0000000",
                  "asset_type": "native"
                }
              ],
              "paging_token": "GABZUFA64FJOIO5MFOZGQ4CBIOPHN47U7CXTSM4NXEPCIKUJ2BGTISJ3"
            }
        """.trimIndent()
    }
}
