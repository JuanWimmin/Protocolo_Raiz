package com.raiz.app.data.stellar

import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.ProposalStatus
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.ResidentToken
import com.raiz.app.data.model.Reward
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.contract.ContractClient
import javax.inject.Inject
import javax.inject.Singleton

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

    // ── Diagnóstico ──────────────────────────────────────────────────────

    fun debugDeployments(): Deployments = deployments

    // ── Helpers privados ──────────────────────────────────────────────────

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
}
