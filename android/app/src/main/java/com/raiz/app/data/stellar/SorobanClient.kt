package com.raiz.app.data.stellar

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.ProposalStatus
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.ResidentToken
import com.raiz.app.data.model.Reward
import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.Asset
import com.soneso.stellar.sdk.InvokeHostFunctionOperation
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.contract.ContractClient
import com.soneso.stellar.sdk.rpc.requests.GetEventsRequest
import com.soneso.stellar.sdk.rpc.responses.GetEventsResponse
import com.soneso.stellar.sdk.rpc.responses.GetTransactionStatus
import com.soneso.stellar.sdk.rpc.responses.SendTransactionStatus
import com.soneso.stellar.sdk.scval.Scv
import com.soneso.stellar.sdk.xdr.HostFunctionXdr
import com.soneso.stellar.sdk.xdr.InvokeContractArgsXdr
import com.soneso.stellar.sdk.xdr.SCSymbolXdr
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Fachada para invocar los 4 contratos Soroban de RAÍZ.
 *
 * Lecturas (read-only) usan `signer = null` y `source = deployments.admin`
 * para que el SDK simule la transacción sin firmar — basta con que la
 * source account exista en testnet (la del admin sí).
 *
 * Los structs (`Barrio`, `Merchant`) se reciben como SCVal Map y se parsean
 * manualmente con `ScvalParse` para mantener type-safety.
 */
@Singleton
class SorobanClient @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {

    private val deployments: Deployments by lazy { deploymentsLoader.load() }

    private val network: Network by lazy {
        when (deployments.network) {
            "testnet" -> Network.TESTNET
            "public" -> Network.PUBLIC
            else -> Network.TESTNET
        }
    }

    private val rpcUrl: String by lazy {
        when (deployments.network) {
            "testnet" -> RaizConstants.TESTNET_SOROBAN_RPC_URL
            else -> RaizConstants.TESTNET_SOROBAN_RPC_URL
        }
    }

    /**
     * Cliente del Pool. Se cachea entre llamadas porque construirlo dispara
     * `getLatestLedger` + `loadContractSpec` (dos round-trips a RPC) y
     * eso es lento.
     */
    private suspend fun poolClient(): ContractClient =
        cachedPoolClient ?: ContractClient.forContract(
            contractId = deployments.pool,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedPoolClient = it }

    private suspend fun governanceClient(): ContractClient =
        cachedGovClient ?: ContractClient.forContract(
            contractId = deployments.governance,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedGovClient = it }

    private suspend fun rewardsClient(): ContractClient =
        cachedRewardsClient ?: ContractClient.forContract(
            contractId = deployments.rewards,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedRewardsClient = it }

    private suspend fun treasuryClient(): ContractClient =
        cachedTreasuryClient ?: ContractClient.forContract(
            contractId = deployments.treasury,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedTreasuryClient = it }

    private var cachedPoolClient: ContractClient? = null
    private var cachedGovClient: ContractClient? = null
    private var cachedRewardsClient: ContractClient? = null
    private var cachedTreasuryClient: ContractClient? = null

    // ── Pool: get_pool_balance ────────────────────────────────────────────

    suspend fun getPoolBalance(barrioId: String): RaizResult<Long> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(
                code = RaizErrorCode.PARSE_ERROR,
                message = "barrio_id no es hex de 32 bytes: $barrioId",
            )

        return runCatching {
            val client = poolClient()
            client.invoke<Long>(
                functionName = "get_pool_balance",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getPoolBalance: ${e.message}")
            },
        )
    }

    // ── Pool: get_vault_shares (Camino A — fondo del barrio en DeFindex) ───

    /**
     * Shares (stroops, 7 dec) que el fondo de un barrio tiene depositadas en el
     * vault de DeFindex. Lectura pura de storage del Pool (read-only). A precio
     * por share ~1.0 equivale a su valor en USDC. 0 si el barrio no ha depositado.
     */
    suspend fun getVaultShares(barrioId: String): RaizResult<Long> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            poolClient().invoke<Long>(
                functionName = "get_vault_shares",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getVaultShares: ${e.message}")
            },
        )
    }

    // ── Pool: get_vault_value (valor USDC actual del fondo en DeFindex) ──

    /**
     * Valor USDC actual (en stroops) de la posición del barrio en el vault
     * DeFindex: shares del barrio × precio por share en el momento de la consulta.
     * Lectura pura — usa el admin como source y signer = null.
     * Devuelve 0 si el barrio no tiene shares o el vault no está configurado.
     *
     * Internamente el contrato llama a `vault.get_asset_amounts_per_shares`.
     * Si las entradas del vault tienen TTL expirado (sin actividad >~1 mes),
     * el RPC puede rechazar con "Signer required for write call"; en ese caso
     * el resultado será RaizResult.Error(NETWORK_ERROR, ...).
     */
    suspend fun getVaultValue(barrioId: String): RaizResult<Long> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            poolClient().invoke<Long>(
                functionName = "get_vault_value",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getVaultValue: ${e.message}")
            },
        )
    }

    // ── Pool: list_barrios (RBAC dinámico) ───────────────────────────────

    /**
     * IDs (hex) de todos los barrios registrados on-chain. Lectura pura.
     * Solo existe en contratos desplegados con el índice `AllBarrios`; en un
     * deploy previo la llamada falla → el caller debe tener fallback.
     */
    suspend fun listBarrios(): RaizResult<List<String>> {
        return runCatching {
            poolClient().invoke<List<String>>(
                functionName = "list_barrios",
                arguments = emptyMap(),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { ScvalParse.asHex(it) }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "listBarrios: ${e.message}")
            },
        )
    }

    // ── Pool: get_barrio ──────────────────────────────────────────────────

    suspend fun getBarrio(barrioId: String): RaizResult<Barrio> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        return runCatching {
            poolClient().invoke<Barrio>(
                functionName = "get_barrio",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    val fields = ScvalParse.asStruct(scval)
                    Barrio(
                        id = ScvalParse.asHex(fields.req("id")),
                        name = ScvalParse.asString(fields.req("name")),
                        poolBalanceStroops = ScvalParse.asLong(fields.req("pool_balance")),
                        totalCollectedStroops = ScvalParse.asLong(fields.req("total_collected")),
                        txCount = ScvalParse.asULongAsLong(fields.req("tx_count")),
                        uniqueTourists = ScvalParse.asUIntAsInt(fields.req("unique_tourists")),
                        treasuryContract = ScvalParse.asAddressString(fields.req("treasury_contract")),
                    )
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                if (e.message?.contains("BarrioNotFound") == true ||
                    e.message?.contains("Error(Contract, #6)") == true
                ) {
                    RaizResult.Error(RaizErrorCode.NOT_FOUND, "barrio no registrado")
                } else {
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getBarrio: ${e.message}")
                }
            },
        )
    }

    // ── Pool: list_merchants ──────────────────────────────────────────────

    suspend fun listMerchants(barrioId: String): RaizResult<List<Merchant>> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        return runCatching {
            poolClient().invoke<List<Merchant>>(
                functionName = "list_merchants",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { item ->
                        val f = ScvalParse.asStruct(item)
                        Merchant(
                            address = ScvalParse.asAddressString(f.req("address")),
                            name = ScvalParse.asString(f.req("name")),
                            barrioId = ScvalParse.asHex(f.req("barrio_id")),
                            verified = ScvalParse.asBoolean(f.req("verified")),
                            latE6 = ScvalParse.asInt32(f.req("lat_e6")),
                            lngE6 = ScvalParse.asInt32(f.req("lng_e6")),
                            category = MerchantCategory.fromSymbol(
                                ScvalParse.asSymbol(f.req("category"))
                            ),
                        )
                    }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "listMerchants: ${e.message}")
            },
        )
    }

    // ── Pool: register_merchant (admin only) ─────────────────────────────

    /**
     * Registra un comercio en el contrato Pool. Solo el admin del protocolo
     * puede llamar esto on-chain. La app lo expone con el demoAdminKeyPair
     * en modo demo para que cualquier usuario pueda volverse comerciante
     * sin coordinar offline.
     *
     * El struct MerchantData se construye desde los campos planos y se pasa
     * como un Map al SDK, que lo serializa automáticamente.
     */
    suspend fun registerMerchant(
        admin: KeyPair,
        merchantAddress: String,
        name: String,
        barrioId: String,
        latE6: Int,
        lngE6: Int,
        categorySymbol: String,
    ): RaizResult<Unit> {
        val barrioBytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        // Para structs en el SDK Soneso, se pasa un Map<String, Any> donde
        // cada key es el nombre del campo y el valor su contenido.
        val merchantData = linkedMapOf<String, Any>(
            "address" to merchantAddress,
            "name" to name,
            "barrio_id" to barrioBytes,
            "verified" to true,
            "lat_e6" to latE6,
            "lng_e6" to lngE6,
            "category" to com.soneso.stellar.sdk.scval.Scv.toSymbol(categorySymbol),
        )

        return runCatching {
            poolClient().invoke<Unit>(
                functionName = "register_merchant",
                arguments = mapOf("data" to merchantData),
                source = admin.getAccountId(),
                signer = admin,
                parseResultXdrFn = { /* void */ },
            )
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "BarrioNotFound" in msg ||
                        "Error(Contract, #6)" in msg -> RaizErrorCode.NOT_FOUND
                    "Unauthorized" in msg ||
                        "Error(Contract, #3)" in msg -> RaizErrorCode.UNAUTHORIZED
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "registerMerchant: ${e.message}")
            },
        )
    }

    // ── Pool: get_merchant ────────────────────────────────────────────────

    /** Devuelve los datos del merchant dado su address G...  */
    suspend fun getMerchant(merchantAddress: String): RaizResult<Merchant> {
        return runCatching {
            poolClient().invoke<Merchant>(
                functionName = "get_merchant",
                arguments = mapOf("merchant" to merchantAddress),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    val f = ScvalParse.asStruct(scval)
                    Merchant(
                        address = ScvalParse.asAddressString(f.req("address")),
                        name = ScvalParse.asString(f.req("name")),
                        barrioId = ScvalParse.asHex(f.req("barrio_id")),
                        verified = ScvalParse.asBoolean(f.req("verified")),
                        latE6 = ScvalParse.asInt32(f.req("lat_e6")),
                        lngE6 = ScvalParse.asInt32(f.req("lng_e6")),
                        category = MerchantCategory.fromSymbol(
                            ScvalParse.asSymbol(f.req("category"))
                        ),
                    )
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                if ("MerchantNotFound" in msg || "Error(Contract, #4)" in msg) {
                    RaizResult.Error(RaizErrorCode.NOT_FOUND, "merchant no registrado")
                } else {
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getMerchant: ${e.message}")
                }
            },
        )
    }

    // ── Pool: pay_merchant (ESCRITURA firmada) ───────────────────────────

    /**
     * Paga del turista al comercio con Tip Barrio opcional.
     *
     * Dispara la cadena completa del contrato Pool:
     *   - transfiere `amount - fee` del turista al comercio
     *   - transfiere `tip` del turista al pool del barrio
     *   - llama Rewards.accrue_points cross-contract (puntos para el turista)
     *   - emite evento `payment(tourist, merchant, amount, tip, barrio_id)`
     *
     * Devuelve Unit en éxito porque el contrato no retorna datos. La
     * confirmación visual de éxito viene de:
     *   - el balance USDC del turista bajando (HorizonStream)
     *   - el pool_balance del barrio subiendo (próximo getBarrio)
     */
    suspend fun payMerchant(
        tourist: KeyPair,
        merchantAddress: String,
        amountStroops: Long,
        tipBps: Int,
    ): RaizResult<Unit> {
        return runCatching {
            // Reintento ante errores DNS/conexión transitorios (e.g. "Unable to resolve host
            // 'soroban-testnet.stellar.org': No address associated with hostname").
            // Los errores de contrato (InsufficientBalance, MerchantNotFound) no se reintentan.
            withNetworkRetry {
                poolClient().invoke<Unit>(
                    functionName = "pay_merchant",
                    arguments = mapOf(
                        "tourist" to tourist.getAccountId(),
                        "merchant" to merchantAddress,
                        "amount" to amountStroops,
                        "tip_bps" to tipBps.toUInt(),
                    ),
                    source = tourist.getAccountId(),
                    signer = tourist,
                    parseResultXdrFn = { /* void */ },
                )
            }
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "InsufficientBalance" in msg ||
                        "Error(Contract, #7)" in msg -> RaizErrorCode.INSUFFICIENT_BALANCE
                    "trustline" in msg.lowercase() ||
                        "Error(Contract, #13)" in msg -> RaizErrorCode.INSUFFICIENT_BALANCE
                    "MerchantNotFound" in msg ||
                        "Error(Contract, #4)" in msg -> RaizErrorCode.NOT_FOUND
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "payMerchant: ${e.message}")
            },
        )
    }

    // ── Governance: get_resident / list_active_proposals / get_resident_count ──

    /** Si el address tiene ResidentToken, lo retorna. NOT_FOUND si no. */
    suspend fun getResident(address: String): RaizResult<ResidentToken> {
        return runCatching {
            governanceClient().invoke<ResidentToken>(
                functionName = "get_resident",
                arguments = mapOf("resident" to address),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    val f = ScvalParse.asStruct(scval)
                    ResidentToken(
                        resident = ScvalParse.asAddressString(f.req("resident")),
                        barrioId = ScvalParse.asHex(f.req("barrio_id")),
                        issuedAt = ScvalParse.asULongAsLong(f.req("issued_at")),
                    )
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                // Error #6 = NotAResident en Governance.
                if ("NotAResident" in msg || "Error(Contract, #6)" in msg) {
                    RaizResult.Error(RaizErrorCode.NOT_FOUND, "no es residente")
                } else {
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getResident: ${e.message}")
                }
            },
        )
    }

    /** Propuestas Active de un barrio. */
    suspend fun listActiveProposals(barrioId: String): RaizResult<List<Proposal>> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            governanceClient().invoke<List<Proposal>>(
                functionName = "list_active_proposals",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { item ->
                        val f = ScvalParse.asStruct(item)
                        Proposal(
                            id = ScvalParse.asULongAsLong(f.req("id")),
                            barrioId = ScvalParse.asHex(f.req("barrio_id")),
                            proposer = ScvalParse.asAddressString(f.req("proposer")),
                            description = ScvalParse.asString(f.req("description")),
                            amountStroops = ScvalParse.asLong(f.req("amount")),
                            recipient = ScvalParse.asAddressString(f.req("recipient")),
                            votesFor = ScvalParse.asUIntAsInt(f.req("votes_for")),
                            votesAgainst = ScvalParse.asUIntAsInt(f.req("votes_against")),
                            createdAt = ScvalParse.asULongAsLong(f.req("created_at")),
                            closesAt = ScvalParse.asULongAsLong(f.req("closes_at")),
                            status = ProposalStatus.fromSymbol(
                                ScvalParse.asEnumSymbol(f.req("status")),
                            ),
                        )
                    }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "listActiveProposals: ${e.message}")
            },
        )
    }

    suspend fun getResidentCount(barrioId: String): RaizResult<Int> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            governanceClient().invoke<Int>(
                functionName = "get_resident_count",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asUIntAsInt(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { RaizResult.Error(RaizErrorCode.NETWORK_ERROR, it.message ?: "?") },
        )
    }

    // ── Governance: vote (ESCRITURA firmada) ─────────────────────────────

    suspend fun vote(
        resident: KeyPair,
        proposalId: Long,
        support: Boolean,
    ): RaizResult<Unit> {
        return runCatching {
            governanceClient().invoke<Unit>(
                functionName = "vote",
                arguments = mapOf(
                    "resident" to resident.getAccountId(),
                    "proposal_id" to proposalId.toULong(),
                    "support" to support,
                ),
                source = resident.getAccountId(),
                signer = resident,
                parseResultXdrFn = { /* void */ },
            )
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "AlreadyVoted" in msg || "Error(Contract, #10)" in msg -> RaizErrorCode.ALREADY_VOTED
                    "NotAResident" in msg || "Error(Contract, #6)" in msg -> RaizErrorCode.NOT_A_RESIDENT
                    "ProposalClosed" in msg || "Error(Contract, #11)" in msg -> RaizErrorCode.PROPOSAL_CLOSED
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "vote: ${e.message}")
            },
        )
    }

    // ── Governance: create_proposal (ESCRITURA firmada) ──────────────────

    /**
     * Crea una propuesta de gasto del fondo comunitario.
     *
     * El proposer debe ser residente verificado del barrio indicado; el
     * contrato valida esto on-chain y rechaza con NotAResident si no lo es.
     * El contrato también valida amount > 0 y duration_days en [3, 14].
     *
     * Retorna el id (u64) de la propuesta recién creada, que la UI puede
     * usar para navegar directamente a la pantalla de detalle.
     *
     * Errores mapeados:
     *   - NotAResident (#6)    → NOT_A_RESIDENT  — "Debes ser residente de este barrio"
     *   - InvalidAmount (#9)   → PARSE_ERROR      — "El monto debe ser mayor que cero"
     *   - InvalidDuration (#8) → PARSE_ERROR      — "La duración debe ser 3-14 días"
     */
    suspend fun createProposal(
        proposer: KeyPair,
        barrioId: String,
        description: String,
        amountStroops: Long,
        recipient: String,
        durationDays: Int,
    ): RaizResult<Long> {
        val barrioBytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        return runCatching {
            governanceClient().invoke<Long>(
                functionName = "create_proposal",
                arguments = mapOf(
                    "proposer" to proposer.getAccountId(),
                    "barrio_id" to barrioBytes,
                    "description" to description,
                    "amount" to amountStroops,
                    "recipient" to recipient,
                    "duration_days" to durationDays.toUInt(),
                ),
                source = proposer.getAccountId(),
                signer = proposer,
                parseResultXdrFn = { ScvalParse.asULongAsLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val (code, text) = when {
                    "NotAResident" in msg ||
                        "Error(Contract, #6)" in msg ->
                        RaizErrorCode.NOT_A_RESIDENT to "Debes ser residente de este barrio"
                    "InvalidAmount" in msg ||
                        "Error(Contract, #9)" in msg ->
                        RaizErrorCode.PARSE_ERROR to "El monto debe ser mayor que cero"
                    "InvalidDuration" in msg ||
                        "Error(Contract, #8)" in msg ->
                        RaizErrorCode.PARSE_ERROR to "La duración debe ser 3-14 días"
                    else -> RaizErrorCode.NETWORK_ERROR to "createProposal: ${e.message}"
                }
                RaizResult.Error(code, text)
            },
        )
    }

    // ── Rewards: get_points / list_rewards / redeem ──────────────────────

    suspend fun getPoints(tourist: String): RaizResult<Long> {
        return runCatching {
            rewardsClient().invoke<Long>(
                functionName = "get_points",
                arguments = mapOf("tourist" to tourist),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asULongAsLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { RaizResult.Error(RaizErrorCode.NETWORK_ERROR, it.message ?: "?") },
        )
    }

    /** Lista los rewards (artesanías) disponibles de un barrio. */
    suspend fun listRewards(barrioId: String): RaizResult<List<Reward>> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            rewardsClient().invoke<List<Reward>>(
                functionName = "list_rewards",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { item ->
                        val f = ScvalParse.asStruct(item)
                        Reward(
                            id = ScvalParse.asULongAsLong(f.req("id")),
                            barrioId = ScvalParse.asHex(f.req("barrio_id")),
                            name = ScvalParse.asString(f.req("name")),
                            artisan = ScvalParse.asAddressString(f.req("artisan")),
                            pointsCost = ScvalParse.asULongAsLong(f.req("points_cost")),
                            stock = ScvalParse.asUIntAsInt(f.req("stock")),
                            imageRef = ScvalParse.asString(f.req("image_ref")),
                        )
                    }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "listRewards: ${e.message}")
            },
        )
    }

    /**
     * Canjea un reward firmando con la KeyPair del turista.
     * On-chain: el contrato quema los puntos, decrementa stock, crea Redemption
     * y emite evento `redeem`. Errores típicos: InsufficientPoints (#5),
     * OutOfStock (#6), RewardNotFound (#4).
     *
     * Retorna el `redemption_id` de la nueva Redemption creada.
     */
    suspend fun redeem(
        tourist: KeyPair,
        rewardId: Long,
    ): RaizResult<Long> {
        return runCatching {
            rewardsClient().invoke<Long>(
                functionName = "redeem",
                arguments = mapOf(
                    "tourist" to tourist.getAccountId(),
                    "reward_id" to rewardId.toULong(),
                ),
                source = tourist.getAccountId(),
                signer = tourist,
                parseResultXdrFn = { ScvalParse.asULongAsLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "InsufficientPoints" in msg ||
                        "Error(Contract, #5)" in msg -> RaizErrorCode.INSUFFICIENT_POINTS
                    "OutOfStock" in msg ||
                        "Error(Contract, #6)" in msg -> RaizErrorCode.OUT_OF_STOCK
                    "RewardNotFound" in msg ||
                        "Error(Contract, #4)" in msg -> RaizErrorCode.NOT_FOUND
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "redeem: ${e.message}")
            },
        )
    }

    // ── Treasury: execute_proposal (ESCRITURA trustless) ─────────────────

    /**
     * Ejecuta una propuesta aprobada. Trustless: cualquiera puede llamar,
     * lo que importa es que el contrato verifique vía tally que el estado
     * sea Passed. Si pasa, Treasury orquesta el resto:
     *   - pool.withdraw_to(treasury, barrio, recipient, amount)
     *   - registra Execution
     *   - governance.mark_executed
     *
     * El firmante paga el gas pero no necesita rol especial. Aquí usamos
     * el demoKeyPair del turista como cualquier "auditor" del barrio.
     */
    suspend fun executeProposal(
        signer: KeyPair,
        proposalId: Long,
    ): RaizResult<Unit> {
        return runCatching {
            treasuryClient().invoke<Unit>(
                functionName = "execute_proposal",
                arguments = mapOf("proposal_id" to proposalId.toULong()),
                source = signer.getAccountId(),
                signer = signer,
                parseResultXdrFn = { /* void */ },
            )
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "ProposalNotPassed" in msg ||
                        "Error(Contract, #3)" in msg -> RaizErrorCode.QUORUM_NOT_REACHED
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "executeProposal: ${e.message}")
            },
        )
    }

    // ── Governance: tally (cierra la votación si pasó closes_at) ─────────

    /**
     * Llama tally on-chain. Si la propuesta ya cerró por timestamp pero su
     * status sigue Active, el contrato calcula quórum/mayoría y actualiza el
     * status a Passed/Rejected. Si aún no cerró, devuelve Active sin tocar
     * el storage.
     *
     * Cualquiera puede llamar; aquí firma con el demoKeyPair (el turista
     * paga unos stroops de gas).
     */
    suspend fun tally(
        signer: KeyPair,
        proposalId: Long,
    ): RaizResult<ProposalStatus> {
        return runCatching {
            governanceClient().invoke<ProposalStatus>(
                functionName = "tally",
                arguments = mapOf("proposal_id" to proposalId.toULong()),
                source = signer.getAccountId(),
                signer = signer,
                parseResultXdrFn = { scval ->
                    ProposalStatus.fromSymbol(ScvalParse.asEnumSymbol(scval))
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "tally: ${e.message}")
            },
        )
    }

    // ── Treasury: get_execution_log ──────────────────────────────────────

    /** Devuelve todas las ejecuciones de propuestas pasadas de un barrio. */
    suspend fun getExecutionLog(barrioId: String): RaizResult<List<Execution>> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        return runCatching {
            treasuryClient().invoke<List<Execution>>(
                functionName = "get_execution_log",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { item ->
                        val f = ScvalParse.asStruct(item)
                        Execution(
                            proposalId = ScvalParse.asULongAsLong(f.req("proposal_id")),
                            barrioId = ScvalParse.asHex(f.req("barrio_id")),
                            amountStroops = ScvalParse.asLong(f.req("amount")),
                            recipient = ScvalParse.asAddressString(f.req("recipient")),
                            executedAt = ScvalParse.asULongAsLong(f.req("executed_at")),
                            txHash = ScvalParse.asHex(f.req("tx_hash")),
                        )
                    }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getExecutionLog: ${e.message}")
            },
        )
    }

    // ── USDC SAC: balance y fondeo de smart accounts ─────────────────────

    /**
     * Envía USDC del admin (G...) al smart account del turista (C...) invocando
     * el SAC de Blend directamente con SCVal crudos — sin spec descargable.
     *
     * El SAC (Stellar Asset Contract) es un contrato nativo del host; NO tiene
     * WASM descargable, así que `ContractClient.forContract()` fallaría con
     * "Contract spec not found". En su lugar construimos la operación a mano:
     *
     *   InvokeHostFunctionOperation(SAC.transfer(from, to, amount_i128))
     *
     * `prepareTransaction` simula + ensambla (soroban data + fee). Para cuentas
     * clásicas (G...) la auth en `require_auth()` se satisface con la firma
     * estándar de la transacción — no hace falta firmar auth entries por separado.
     *
     * @param adminSigner      KeyPair del admin que transfiere (G...).
     * @param contractAddress  Dirección C... del smart account destino.
     * @param amountStroops    Cantidad en stroops (7 decimales). 1 USDC = 10_000_000.
     */
    suspend fun fundContractUsdc(
        adminSigner: KeyPair,
        contractAddress: String,
        amountStroops: Long,
    ): RaizResult<Unit> {
        return try {
            val server = poolClient().server
            val adminAccount = server.getAccount(adminSigner.getAccountId())

            val invokeArgs = InvokeContractArgsXdr(
                contractAddress = Address(deployments.usdcSac).toSCAddress(),
                functionName = SCSymbolXdr("transfer"),
                args = listOf(
                    Address(adminSigner.getAccountId()).toSCVal(),
                    Address(contractAddress).toSCVal(),
                    Scv.toInt128(BigInteger.fromLong(amountStroops)),
                ),
            )
            val op = InvokeHostFunctionOperation(
                hostFunction = HostFunctionXdr.InvokeContract(invokeArgs),
                auth = emptyList(),
            )
            val unsignedTx = TransactionBuilder(adminAccount, network)
                .addOperation(op)
                .setBaseFee(500_000L)
                .setTimeout(30L)
                .build()

            val prepared = server.prepareTransaction(unsignedTx)
            prepared.sign(adminSigner)

            val sendResp = server.sendTransaction(prepared)
            if (sendResp.status == SendTransactionStatus.ERROR) {
                return RaizResult.Error(
                    code = RaizErrorCode.NETWORK_ERROR,
                    message = "fundContractUsdc: send error ${sendResp.errorResultXdr}",
                )
            }

            val txHash = sendResp.hash
                ?: return RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "fundContractUsdc: hash nulo en sendTransaction")

            for (attempt in 0 until 10) {
                delay(3_000L)
                val txResp = server.getTransaction(txHash)
                when (txResp.status) {
                    GetTransactionStatus.SUCCESS ->
                        return RaizResult.Success(Unit)
                    GetTransactionStatus.FAILED ->
                        return RaizResult.Error(
                            code = RaizErrorCode.SIMULATION_FAILED,
                            message = "fundContractUsdc: tx FAILED ${txResp.resultXdr}",
                        )
                    GetTransactionStatus.NOT_FOUND -> { /* todavía propagando, seguir esperando */ }
                }
            }
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "fundContractUsdc: timeout esperando $txHash")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val code = when {
                "InsufficientBalance" in msg || "Error(Contract, #7)" in msg ->
                    RaizErrorCode.INSUFFICIENT_BALANCE
                "Unauthorized" in msg || "Error(Contract, #3)" in msg ->
                    RaizErrorCode.UNAUTHORIZED
                else -> RaizErrorCode.NETWORK_ERROR
            }
            RaizResult.Error(code, "fundContractUsdc: ${e.message}")
        }
    }

    /**
     * Lee el balance USDC (stroops) de un smart account on-chain (C...) usando
     * la API dedicada de SAC del SDK — sin spec descargable.
     *
     * `SorobanServer.getSACBalance(holderAddress, asset, network)` accede
     * directamente a la entrada de ledger del SAC para ese titular, devolviendo
     * el importe como Long en stroops. No se necesita el WASM del SAC.
     *
     * Los C... no aparecen en la API de balances de Horizon (no son cuentas
     * clásicas), por eso no se puede usar `HorizonStream` para ellos.
     *
     * @param contractAddress  Dirección C... del smart account a consultar.
     * @return saldo en stroops como Long. 0 si el C... no tiene balance registrado.
     */
    suspend fun usdcBalanceOfContract(contractAddress: String): RaizResult<Long> {
        val issuer = deployments.usdcIssuer
            ?: return RaizResult.Error(
                code = RaizErrorCode.NETWORK_ERROR,
                message = "usdcBalanceOfContract: usdc_issuer no está en deployments.json",
            )
        return try {
            val server = poolClient().server
            val usdcAsset = Asset.createNonNativeAsset("USDC", issuer)
            val response = server.getSACBalance(contractAddress, usdcAsset, network)
            RaizResult.Success(response.balanceEntry?.getAmountAsLong() ?: 0L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // Si el ledger entry no existe (cuenta nueva sin depósito), balance = 0
            if ("entryNotFound" in msg || "not found" in msg.lowercase() ||
                "ledger key does not exist" in msg.lowercase()
            ) {
                RaizResult.Success(0L)
            } else {
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "usdcBalanceOfContract: $msg")
            }
        }
    }

    // ── Pool: eventos de pago vía Soroban RPC getEvents ─────────────────
    //
    // Los pagos `pay_merchant` se realizan DENTRO del contrato Pool (no son
    // operaciones clásicas de Stellar), por lo que Horizon `/payments` NO los
    // registra. En su lugar el contrato emite un evento en cada pago con:
    //   topic[0] = Symbol("payment")
    //   topic[1] = BytesN<32>(barrio_id)
    //   value    = Vec([tourist_addr, merchant_addr, amount_i128, tip_i128])
    //
    // NOTA DE PASSKEY: para wallets passkey, WalletManager.currentKeyPair()
    // cae al demoKeyPair() (G...) porque la firma via smart-account no está
    // conectada todavía. Por tanto, el evento tiene el G... del demo como
    // tourist, pero currentAccountId() devuelve el C... del contrato.
    // El ViewModel DEBE pasar el G... del KeyPair realmente usado en payMerchant
    // (no el C... del smart account) para que este filtro funcione. Ver:
    // WalletManager.demoKeyPair().getAccountId() o currentKeyPair().getAccountId().
    //
    // LÍMITE DE RETENCIÓN: testnet RPC retiene ~17 000 ledgers (~24h). Si el RPC
    // rechaza startLedger, se reintenta con ventana más pequeña (FALLBACK). Los
    // errores se loguean explícitamente — nunca silenciosos.

    /**
     * Historial de pagos on-chain del turista, leído desde los eventos Soroban
     * del contrato Pool. Complementa a [HorizonStream.paymentHistory] (que solo
     * captura pagos clásicos y no ve las invocaciones Soroban).
     *
     * PAGINACIÓN: getEvents devuelve eventos en orden ASCENDENTE desde startLedger.
     * Los pagos recientes están en las últimas páginas. Este método sigue el cursor
     * de cada respuesta hasta que llega null (última página) o alcanza MAX_PAGES.
     * Solo entonces filtra por tourist — nunca a mitad de la lectura.
     *
     * PASSKEY: mientras el signing via smart-account no esté conectado, pasar el
     * G... del demoKeyPair (el que realmente firma payMerchant), NO el C... del
     * smart account. Ver WalletManager.currentKeyPair() y el TODO de passkey pleno.
     *
     * @param touristAddress  G... o C... exacto que aparece como tourist en los eventos.
     * @return Lista de [PaymentRecord] en orden ledger desc (más reciente primero).
     */
    suspend fun tourPaymentEvents(
        touristAddress: String,
    ): RaizResult<List<PaymentRecord>> {
        Log.i(TAG, "tourPaymentEvents: touristAddress=$touristAddress pool=${deployments.pool}")
        return runCatching {
            val server = poolClient().server
            val latestLedger = server.getLatestLedger().sequence
            Log.i(TAG, "tourPaymentEvents: latestLedger=$latestLedger LOOKBACK=$EVENTS_LOOKBACK_LEDGERS FALLBACK=$EVENTS_LOOKBACK_FALLBACK")

            // Preparamos el EventFilter (reutilizado en todas las páginas)
            val eventFilter = GetEventsRequest.EventFilter(
                type = GetEventsRequest.EventFilterType.CONTRACT,
                contractIds = listOf(deployments.pool),
                topics = emptyList(), // sin filtro de topic: match todos los eventos del Pool
            )

            // Intentamos la ventana principal; si el RPC rechaza startLedger
            // por retención, reintentamos con la ventana de fallback.
            val windowCandidates = listOf(
                (latestLedger - EVENTS_LOOKBACK_LEDGERS).coerceAtLeast(1L),
                (latestLedger - EVENTS_LOOKBACK_FALLBACK).coerceAtLeast(1L),
            ).distinct()

            var firstPage: GetEventsResponse? = null
            for (candidateStart in windowCandidates) {
                Log.i(TAG, "tourPaymentEvents: getEvents 1ª pág startLedger=$candidateStart")
                try {
                    firstPage = server.getEvents(
                        GetEventsRequest(
                            startLedger = candidateStart,
                            endLedger = null,
                            filters = listOf(eventFilter),
                            pagination = GetEventsRequest.Pagination(cursor = null, limit = 100L),
                        ),
                    )
                    Log.i(TAG, "tourPaymentEvents: 1ª pág OK → ${firstPage.events.size} ev cursor=${firstPage.cursor?.take(20)} oldest=${firstPage.oldestLedger}")
                    break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    Log.w(TAG, "tourPaymentEvents: getEvents(startLedger=$candidateStart) FALLÓ: $msg")
                    val isRetentionError = "startledger" in msg.lowercase() ||
                        "event retention" in msg.lowercase() ||
                        "minimum ledger" in msg.lowercase()
                    if (!isRetentionError) throw e   // error no relacionado → propagar al onFailure
                    // Error de retención → prueba la siguiente ventana
                }
            }

            if (firstPage == null) {
                Log.w(TAG, "tourPaymentEvents: sin respuesta en ninguna ventana → vacío")
                return@runCatching emptyList<PaymentRecord>()
            }

            // ── Paginación completa ───────────────────────────────────────────
            // Protocolo cursor (Soroban RPC spec):
            //   Pág 1: startLedger=N, cursor=null  → responde cursor=X (o null si última)
            //   Pág 2: startLedger=null, cursor=X  → responde cursor=Y (o null si última)
            //   REGLA: cuando cursor != null, startLedger DEBE ser null.
            //
            // Acumulamos todos los eventos; filtramos tourist solo al final.
            val allEvents = mutableListOf<GetEventsResponse.EventInfo>()
            allEvents.addAll(firstPage.events)

            var pageCursor: String? = firstPage.cursor?.takeIf { it.isNotEmpty() }
            var pageCount = 1

            while (pageCursor != null && pageCount < MAX_PAGES) {
                Log.i(TAG, "tourPaymentEvents: pág ${pageCount + 1} cursor=${pageCursor.take(20)} (total acum=${allEvents.size})")
                val nextPage = server.getEvents(
                    GetEventsRequest(
                        startLedger = null,   // OBLIGATORIO null cuando cursor está presente
                        endLedger = null,
                        filters = listOf(eventFilter),
                        pagination = GetEventsRequest.Pagination(cursor = pageCursor, limit = 100L),
                    ),
                )
                pageCount++
                allEvents.addAll(nextPage.events)
                Log.i(TAG, "tourPaymentEvents: pág $pageCount → ${nextPage.events.size} ev (total=${allEvents.size}) nextCursor=${nextPage.cursor?.take(20)}")

                pageCursor = nextPage.cursor?.takeIf { it.isNotEmpty() }
                if (nextPage.events.isEmpty()) break  // página vacía = fin
            }

            if (pageCount >= MAX_PAGES && pageCursor != null) {
                Log.w(TAG, "tourPaymentEvents: cap MAX_PAGES=$MAX_PAGES alcanzado — posibles eventos adicionales sin leer")
            }
            Log.i(TAG, "tourPaymentEvents: paginación completa: $pageCount págs, ${allEvents.size} eventos crudos")

            // ── Filtrado y parseo ─────────────────────────────────────────────
            val records = allEvents
                .sortedByDescending { it.ledger }
                .mapNotNull { event ->
                    runCatching { parsePaymentEvent(event, touristAddress) }
                        .onFailure { Log.w(TAG, "tourPaymentEvents: parse falló event=${event.id}: ${it.message}") }
                        .getOrNull()
                }
            Log.i(TAG, "tourPaymentEvents: RESULTADO → ${records.size} PaymentRecords de ${allEvents.size} crudos para $touristAddress")
            records
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                Log.w(TAG, "tourPaymentEvents: error no recuperable: ${e.message}")
                RaizResult.Error(
                    code = RaizErrorCode.NETWORK_ERROR,
                    message = "tourPaymentEvents: ${e.message}",
                )
            },
        )
    }

    /**
     * Parsea un [GetEventsResponse.EventInfo] del Pool como [PaymentRecord].
     * Loguea cada paso para facilitar diagnóstico en device.
     *
     * Devuelve null si:
     *   - topic[0] no es Symbol("payment")  (puede ser "vault_dep", "vault_red")
     *   - value no es Vec[≥4]
     *   - tourist del evento != touristAddress
     *   - cualquier decodificación XDR falla
     */
    private fun parsePaymentEvent(
        event: GetEventsResponse.EventInfo,
        touristAddress: String,
    ): PaymentRecord? {
        // ── topic[0] debe ser Symbol("payment") ───────────────────────────
        val topics = runCatching { event.parseTopic() }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: parseTopic() lanzó ${e.message} en event=${event.id}")
            return null
        }
        if (topics.isEmpty()) {
            Log.w(TAG, "parsePaymentEvent: topics vacíos en event=${event.id}")
            return null
        }
        val topicSymbol = runCatching { Scv.fromSymbol(topics[0]) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: fromSymbol(topics[0]) lanzó ${e.message} en event=${event.id}")
            return null
        }
        Log.i(TAG, "parsePaymentEvent: event=${event.id} ledger=${event.ledger} topic[0]=$topicSymbol")
        if (topicSymbol != "payment") return null  // "vault_dep" / "vault_red" → skip silencioso

        // ── value = Vec([tourist_addr, merchant_addr, amount_i128, tip_i128]) ──
        // Rust tuple (A, B, C, D) → SCV_VEC con 4 elementos en Soroban.
        val dataVec = runCatching { Scv.fromVec(event.parseValue()) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: parseValue/fromVec lanzó ${e.message} en event=${event.id}")
            return null
        }
        if (dataVec.size < 4) {
            Log.w(TAG, "parsePaymentEvent: dataVec.size=${dataVec.size} < 4 en event=${event.id}")
            return null
        }

        // ── [0] tourist → Address ─────────────────────────────────────────
        val tourist = runCatching { ScvalParse.asAddressString(dataVec[0]) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: asAddressString(tourist) lanzó ${e.message} en event=${event.id}")
            return null
        }
        // ── [1] merchant → Address ────────────────────────────────────────
        val merchant = runCatching { ScvalParse.asAddressString(dataVec[1]) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: asAddressString(merchant) lanzó ${e.message} en event=${event.id}")
            return null
        }
        Log.i(TAG, "parsePaymentEvent: event=${event.id} tourist=$tourist merchant=$merchant buscando=$touristAddress")

        // Filtro por turista — loguea cuando hay mismatch (útil para passkey C... vs G...)
        if (tourist != touristAddress) {
            Log.i(TAG, "parsePaymentEvent: SKIP tourist=$tourist != buscando=$touristAddress")
            return null
        }

        // ── [2] amount + [3] tip → i128 stroops ────────────────────────────
        // El contrato define `amount` = monto base al comercio y `tip` = aporte al
        // barrio (amount * tip_bps / 10_000). El TOTAL que se le descuenta al turista
        // es amount + tip → eso es lo que mostramos en el historial (lo que pagó de verdad).
        val amount = runCatching { ScvalParse.asLong(dataVec[2]) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: asLong(amount) lanzó ${e.message} en event=${event.id}")
            return null
        }
        val tip = runCatching { ScvalParse.asLong(dataVec[3]) }.getOrElse { 0L }

        return PaymentRecord(
            txHash = event.transactionHash,
            from = tourist,
            to = merchant,
            amountStroops = amount + tip,   // total deducido = monto al comercio + tip al barrio
            assetCode = "USDC",
            createdAt = event.ledgerClosedAt,
            isOutgoing = true, // el turista siempre es el pagador en pay_merchant
        )
    }

    // ── Diagnóstico ──────────────────────────────────────────────────────

    fun debugDeployments(): Deployments = deployments

    // ── Helpers privados ──────────────────────────────────────────────────

    /**
     * Reintento con backoff exponencial para errores de red transitorios.
     *
     * SOLO reintenta si el mensaje de error es característico de un fallo DNS
     * o de conexión (e.g. "Unable to resolve host 'soroban-testnet.stellar.org':
     * No address associated with hostname"). Los errores de contrato Soroban
     * (InsufficientBalance, MerchantNotFound…) se propagan inmediatamente sin
     * reintentar.
     *
     * Backoff: 1s → 2s (maxAttempts=3 → 2 esperas antes del fallo definitivo).
     */
    private suspend fun <T> withNetworkRetry(
        maxAttempts: Int = 3,
        block: suspend () -> T,
    ): T {
        var lastEx: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty().lowercase()
                val cause = e.cause
                val isTransient = "unable to resolve" in msg ||
                    "no address associated" in msg ||
                    "failed to connect" in msg ||
                    "connection reset" in msg ||
                    "eof" in msg ||
                    "connect timed out" in msg ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    cause is java.net.UnknownHostException ||
                    cause is java.net.ConnectException
                // Errores de contrato: nunca reintentar (llevan "Error(Contract, #N)")
                val isContractError = "error(contract" in msg || "error(wasm" in msg
                if (!isTransient || isContractError || attempt == maxAttempts - 1) throw e
                lastEx = e
                delay(1_000L shl attempt) // intento 0→1s, intento 1→2s
            }
        }
        throw lastEx ?: error("withNetworkRetry: sin excepción tras $maxAttempts intentos")
    }

    /** Convierte hex (64 chars) a ByteArray (32 bytes). null si inválido. */
    private fun String.hexToBytes(): ByteArray? {
        val clean = removePrefix("0x")
        if (clean.length != 64 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Acceso a campo de struct con error claro si falta. */
    private fun <V> Map<String, V>.req(key: String): V =
        this[key] ?: error("Campo '$key' no presente en el struct SCVal")

    private companion object {
        const val TAG = "RAIZ"

        /**
         * Ventana principal de lookback para getEvents: ~11h en testnet (ledger ~5s).
         * 8 000 × 5s = 40 000s ≈ 11h. Ventana suficiente para la sesión de demo
         * sin forzar que el RPC busque ledgers expirados (testnet retiene ~24h).
         * Reducida de 17 280 (24h) porque la paginación ya cubre páginas tardías.
         */
        const val EVENTS_LOOKBACK_LEDGERS = 8_000L

        /**
         * Ventana de fallback: ~3h (600 ledgers × 5s = 3 000s). Se usa solo si el
         * RPC rechaza la ventana principal por estar fuera de la retención disponible
         * en testnet. Cubre todos los pagos de la sesión de demostración reciente.
         */
        const val EVENTS_LOOKBACK_FALLBACK = 600L

        /**
         * Límite de seguridad de páginas de paginación en getEvents. Con limit=100
         * por página, esto cubre hasta 2 000 eventos del Pool. Un pool activo de
         * hackathon raramente supera las 5 páginas — el cap solo protege contra
         * contratos con miles de eventos que harían el fetch inmanejable.
         */
        const val MAX_PAGES = 20
    }
}
