package com.raiz.app.data.stellar

import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.ExecutionEvent
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
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.Asset
import com.soneso.stellar.sdk.InvokeHostFunctionOperation
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.TimeBounds
import com.soneso.stellar.sdk.Transaction
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.contract.ContractClient
import com.soneso.stellar.sdk.rpc.SorobanServer
import com.soneso.stellar.sdk.rpc.exception.PrepareTransactionException
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

    /**
     * Servidor RPC propio para las operaciones que NO pasan por un `ContractClient` con spec
     * (el `transfer` del SAC y las lecturas de ledger que lo acompañan). Así el envío del
     * depósito a una wallet passkey no depende de que se pueda cargar el spec del Pool.
     */
    private val rpcServer: SorobanServer by lazy { SorobanServer(rpcUrl) }

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

    // ── Pool: get_vault_shares (Camino A — fondo del barrio en la fuente de yield) ─

    /**
     * Shares (stroops, 7 dec) que el fondo de un barrio tiene depositadas en
     * la fuente de yield configurada en el Pool (F1: Blend v2 vía el
     * contrato `yield_adapter` — antes el vault DeFindex). Lectura pura de
     * storage del Pool: internamente Pool delega en `adapter.shares_of(barrio_id)`,
     * pero para la app es transparente — mismo nombre y firma de siempre
     * (compat conservada a propósito, ver spec "NOTA DE COMPATIBILIDAD").
     * 0 si el barrio no ha depositado.
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

    // ── Pool: get_vault_value (valor USDC actual del fondo en la fuente de yield) ─

    /**
     * Valor USDC actual (en stroops) de la posición del barrio en la fuente
     * de yield configurada (F1: Blend v2 vía `yield_adapter`, antes DeFindex):
     * shares del barrio × precio en el momento de la consulta. Lectura pura —
     * usa el admin como source y signer = null. Devuelve 0 si el barrio no
     * tiene shares o el adapter no está configurado.
     *
     * Internamente Pool delega en `adapter.value_of(barrio_id)` (= shares ×
     * b_rate / 1e12 en Blend). Como cualquier lectura Soroban sobre entradas
     * con TTL expirado (sin actividad >~1 mes), el RPC puede en teoría
     * rechazar con "Signer required for write call" por footprint de
     * restore (gotcha general documentado en CLAUDE.md, no específico de
     * ningún proveedor de yield); en ese caso el resultado será
     * RaizResult.Error(NETWORK_ERROR, ...).
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

    // ── Pool: deposit_idle_to_vault / redeem_from_vault ───────────────────
    //
    // Los flujos admin (faucet, registro de comercio, soulbound, vault) viven
    // en el relayer (data/relayer/RelayerClient) desde 0.2.0: el APK ya no
    // lleva autoridad admin. Aquí vivían las escrituras `deposit_idle_to_vault`
    // y `redeem_from_vault` firmadas por el admin (hoy `POST /v1/vault/deposit`
    // y `POST /v1/vault/redeem` del relayer). Las lecturas `getVaultShares` /
    // `getVaultValue` siguen aquí.

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

    // ── Pool: register_merchant ───────────────────────────────────────────
    //
    // Los flujos admin (faucet, registro de comercio, soulbound, vault) viven
    // en el relayer (data/relayer/RelayerClient) desde 0.2.0: el APK ya no
    // lleva autoridad admin. Aquí vivía la escritura `register_merchant`
    // firmada por el admin del protocolo (hoy `POST /v1/register-merchant`).

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

    // ── Governance: mint_resident ─────────────────────────────────────────
    //
    // Los flujos admin (faucet, registro de comercio, soulbound, vault) viven
    // en el relayer (data/relayer/RelayerClient) desde 0.2.0: el APK ya no
    // lleva autoridad admin. Aquí vivía la escritura `mint_resident` firmada
    // por el admin del barrio (hoy `POST /v1/mint-resident`).

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
     *
     * Devuelve el **hash real de la transacción** (D2 del SOW): es el momento
     * barato de capturarlo — el `sendTransaction` lo devuelve y no hace falta
     * esperar a `getEvents`. El ViewModel lo muestra al instante y lo persiste
     * en [com.raiz.app.data.local.ExecutionHashStore].
     */
    suspend fun executeProposal(
        signer: KeyPair,
        proposalId: Long,
    ): RaizResult<String> {
        return try {
            // buildInvoke + sign + submit en vez de invoke(): conservamos el
            // AssembledTransaction y fijamos el hash ANTES de enviar. Una propuesta
            // solo se ejecuta una vez: si el sondeo posterior al envío falla (timeout
            // de 30 s del SDK, corte de red en un getTransaction) la tx puede
            // confirmarse igual, y perder su hash sería perder la evidencia.
            val client = treasuryClient()
            val assembled = client.buildInvoke<Unit>(
                functionName = "execute_proposal",
                arguments = mapOf("proposal_id" to proposalId.toULong()),
                source = signer.getAccountId(),
                signer = signer,
                parseResultXdrFn = { /* void */ },
            )
            assembled.sign(signer)
            val signedHash = runCatching { assembled.signed?.hashHex() }.getOrNull()
                ?.takeIf { it.isNotBlank() }

            try {
                assembled.submit()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val sent = assembled.sendTransactionResponse
                val hash = sent?.hash?.takeIf { it.isNotBlank() } ?: signedHash
                // Rechazada en el envío (ERROR / TRY_AGAIN_LATER): no está en vuelo.
                val rejected = sent != null &&
                    sent.status != SendTransactionStatus.PENDING &&
                    sent.status != SendTransactionStatus.DUPLICATE
                if (hash == null || rejected) throw e
                Log.w(TAG, "executeProposal #$proposalId: submit lanzó (${e.message}); sondeo tx $hash")
                when (awaitTransaction(client, hash)) {
                    GetTransactionStatus.SUCCESS -> {
                        Log.i(TAG, "executeProposal #$proposalId → tx $hash (confirmada tras sondeo propio)")
                        return RaizResult.Success(hash)
                    }
                    GetTransactionStatus.FAILED -> throw e
                    else -> return RaizResult.Error(
                        RaizErrorCode.NETWORK_ERROR,
                        "La transacción se envió pero aún no se confirma (tx ${hash.take(8)}…${hash.takeLast(6)}). " +
                            "No la repitas: refresca en unos segundos.",
                    )
                }
            }

            val hash = assembled.getTransactionResponse?.txHash?.takeIf { it.isNotBlank() }
                ?: assembled.sendTransactionResponse?.hash?.takeIf { it.isNotBlank() }
                ?: signedHash
                ?: error("execute_proposal enviada pero no se pudo determinar el txHash")
            Log.i(TAG, "executeProposal #$proposalId → tx $hash")
            RaizResult.Success(hash)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val code = when {
                "ProposalNotPassed" in msg ||
                    "Error(Contract, #3)" in msg -> RaizErrorCode.QUORUM_NOT_REACHED
                else -> RaizErrorCode.NETWORK_ERROR
            }
            RaizResult.Error(code, "executeProposal: ${e.message}")
        }
    }

    /**
     * Sondea `getTransaction(hash)` hasta SUCCESS/FAILED o hasta agotar el tiempo
     * (devuelve NOT_FOUND). Tolera fallos de red en sondeos individuales.
     */
    private suspend fun awaitTransaction(
        client: ContractClient,
        hash: String,
        timeoutMs: Long = 60_000L,
    ): GetTransactionStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                client.server.getTransaction(hash).status
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "awaitTransaction $hash: sondeo falló: ${e.message}")
                null
            }
            if (status == GetTransactionStatus.SUCCESS || status == GetTransactionStatus.FAILED) return status
            delay(3_000L)
        }
        return GetTransactionStatus.NOT_FOUND
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

    // ── USDC SAC: balance de smart accounts ──────────────────────────────
    //
    // Los flujos admin (faucet, registro de comercio, soulbound, vault) viven
    // en el relayer (data/relayer/RelayerClient) desde 0.2.0: el APK ya no
    // lleva autoridad admin. Aquí vivía el faucet para smart accounts
    // (`transfer` del SAC de USDC firmado por el admin, con SCVal crudos);
    // hoy es `POST /v1/faucet` del relayer, que cubre G… y C….

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

    // ── USDC SAC: transfer firmado por una cuenta clásica (depósito → wallet passkey) ──
    //
    // Último tramo del depósito SEP-24 de una wallet passkey: la "cuenta de depósito" (G…,
    // ver DepositAccountManager) envía el USDC del fondo al smart account C… del usuario. Un
    // pago clásico no admite un C… como destino, así que se invoca `transfer` del SAC. El SAC
    // es un contrato nativo del host SIN spec descargable (`ContractClient.forContract` falla
    // con "Contract spec not found"), por eso la operación se arma a mano — como hacía el
    // antiguo faucet admin `fundContractUsdc` (hoy en el relayer). El `require_auth` de `from`
    // se satisface con la firma del sobre: `from` es la cuenta origen de la transacción.

    /**
     * `transfer(from = signer, to, amount)` del SAC del USDC del fondo, firmado por [signer].
     * Devuelve el **hash real** de la transacción cuando queda confirmada.
     *
     * Un transfer NO es idempotente, así que sigue la misma disciplina que
     * [HorizonStream.pathPaymentStrictSend] y [executeProposal]:
     *  1. se construye, simula y firma UNA vez (si la simulación falla no se envía nada →
     *     [RaizErrorCode.SIMULATION_FAILED], reintentar es seguro);
     *  2. el hash queda fijado y se entrega al [journal] ANTES de enviar;
     *  3. se envía ese MISMO sobre (reenviarlo ante `TRY_AGAIN_LATER` es seguro; reconstruirlo no);
     *  4. se confirma por hash. Solo hay tres veredictos definitivos — entró, entró y falló, o
     *     venció sin entrar (pasó su `maxTime`) —; en los tres se llama a `journal.settled`.
     *     La vigencia y el vencimiento se miden con la HORA DE LA RED (cierre del último ledger
     *     que informa el RPC), nunca con el reloj del teléfono.
     *     Si no se pudo consultar, la función devuelve error SIN cerrar el diario: la tx sigue
     *     en vuelo y el llamador no debe construir otra hasta resolverla.
     *
     * Si la entrada `Balance(to)` del SAC está archivada, la simulación la incluye con
     * auto-restore (Protocol 23+); al ir firmada, la transacción la restaura y solo cuesta más fee.
     *
     * No comprueba que `to` exista: quien llama debe validar el destino ([contractExists]) —
     * el SAC acepta transferir a un `C…` sin desplegar.
     *
     * Logs: direcciones públicas, monto y hash completo. Nunca XDR ni material de firma.
     */
    suspend fun sacTransfer(
        signer: KeyPair,
        to: String,
        amountStroops: Long,
        journal: TxJournal? = null,
    ): RaizResult<String> {
        if (amountStroops <= 0L) {
            return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "Monto inválido para el envío.")
        }
        val from = signer.getAccountId()
        val server = rpcServer

        // 1. Construir, simular y firmar UNA vez; fijar el hash y escribir el diario.
        val built = try {
            val source = server.getAccount(from)
            val op = InvokeHostFunctionOperation(
                hostFunction = HostFunctionXdr.InvokeContract(
                    InvokeContractArgsXdr(
                        contractAddress = Address(deployments.usdcSac).toSCAddress(),
                        functionName = SCSymbolXdr("transfer"),
                        args = listOf(
                            Address(from).toSCVal(),
                            Address(to).toSCVal(),
                            Scv.toInt128(BigInteger.fromLong(amountStroops)),
                        ),
                    ),
                ),
                auth = emptyList(),
            )
            // `maxTime` desde la hora de la red: con el reloj del teléfono atrasado la tx nacería
            // ya vencida (txTOO_LATE) y, adelantado, se la daría por vencida antes de tiempo.
            val maxTime = (server.getLatestLedger().closeTime ?: (System.currentTimeMillis() / 1000L)) +
                SAC_TRANSFER_TIMEOUT_SEC
            val unsigned = TransactionBuilder(source, network)
                .addOperation(op)
                .setBaseFee(SAC_TRANSFER_BASE_FEE)
                .addTimeBounds(TimeBounds(minTime = 0L, maxTime = maxTime))
                .build()
            val tx = server.prepareTransaction(unsigned)
            tx.sign(signer)
            val hash = tx.hashHex()
            val validUntil = tx.getTimeBounds()?.maxTime?.takeIf { it > 0L } ?: maxTime
            journal?.signed(hash, validUntil, tx.sequenceNumber)
            PreparedSacTransfer(tx, hash, validUntil)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val detail = hostErrorFragment(e.message)
            Log.w(
                TAG,
                "sacTransfer: no se pudo preparar el envío $from → $to " +
                    "(${e.javaClass.simpleName}${detail?.let { ": $it" }.orEmpty()})",
            )
            val code = if (e is PrepareTransactionException) {
                RaizErrorCode.SIMULATION_FAILED
            } else {
                RaizErrorCode.NETWORK_ERROR
            }
            return RaizResult.Error(code, MSG_SAC_PREPARE_FAILED)
        }
        val prepared = built.tx
        val txHash = built.hash
        val validUntilSec = built.validUntilSec

        // 2. Enviar el MISMO sobre. Una excepción aquí es ambigua (pudo llegar): se sigue al sondeo.
        var rejected = false
        try {
            var attempt = 0
            while (true) {
                val sent = server.sendTransaction(prepared)
                if (sent.status == SendTransactionStatus.ERROR) {
                    rejected = true
                    break
                }
                if (sent.status != SendTransactionStatus.TRY_AGAIN_LATER) break // PENDING / DUPLICATE
                attempt++
                if (attempt >= SAC_SEND_ATTEMPTS) break
                delay(SAC_SEND_RETRY_MS)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "sacTransfer: sin respuesta al enviar (${e.javaClass.simpleName}); se consulta por hash $txHash")
        }
        if (rejected) {
            // La red no aceptó el sobre: no está en vuelo ni puede entrar.
            journal?.settled(txHash)
            Log.w(TAG, "sacTransfer: la red rechazó el envío $from → $to, hash=$txHash")
            return RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_SAC_REJECTED)
        }

        // 3. Confirmar por hash.
        return when (awaitSacTransfer(server, txHash, validUntilSec)) {
            GetTransactionStatus.SUCCESS -> {
                journal?.settled(txHash)
                Log.i(TAG, "sacTransfer: USDC del fondo OK $from → $to, $amountStroops stroops, hash=$txHash")
                RaizResult.Success(txHash)
            }
            GetTransactionStatus.FAILED -> {
                journal?.settled(txHash)
                Log.w(TAG, "sacTransfer: la tx entró y falló, hash=$txHash")
                RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_SAC_REJECTED)
            }
            GetTransactionStatus.NOT_FOUND -> {
                journal?.settled(txHash)
                Log.w(TAG, "sacTransfer: la tx venció sin entrar, hash=$txHash")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_SAC_EXPIRED)
            }
            null -> {
                // Sin veredicto: NO se cierra el diario, la tx puede entrar todavía.
                Log.w(TAG, "sacTransfer: enviada sin confirmar, hash=$txHash")
                RaizResult.Error(
                    RaizErrorCode.NETWORK_ERROR,
                    "El envío salió pero aún no se confirma (tx ${txHash.take(6)}…${txHash.takeLast(6)}). " +
                        "No lo repitas: lo estamos comprobando.",
                )
            }
        }
    }

    /**
     * Sondea `getTransaction(hash)` hasta un veredicto definitivo:
     *  - `SUCCESS` / `FAILED`: la tx está en un ledger;
     *  - `NOT_FOUND`: el RPC no la conoce y YA cerró un ledger con hora posterior a su `maxTime`
     *    → no entró ni puede entrar. Se decide con `latestLedgerCloseTime` de la propia
     *    respuesta (hora de la red): ni el reloj del teléfono ni un RPC atrasado pueden darla
     *    por vencida antes de tiempo;
     *  - `null`: se agotó la espera sin veredicto (sin red, RPC parado) → sigue en vuelo.
     */
    private suspend fun awaitSacTransfer(
        server: SorobanServer,
        hash: String,
        validUntilSec: Long,
    ): GetTransactionStatus? {
        repeat(SAC_AWAIT_POLLS) { poll ->
            val response = try {
                server.getTransaction(hash)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "sacTransfer: sondeo de $hash falló (${e.javaClass.simpleName})")
                null
            }
            val status = response?.status
            if (status == GetTransactionStatus.SUCCESS || status == GetTransactionStatus.FAILED) return status
            val networkNowSec = response?.latestLedgerCloseTime
            if (status == GetTransactionStatus.NOT_FOUND && networkNowSec != null && networkNowSec > validUntilSec) {
                return GetTransactionStatus.NOT_FOUND
            }
            if (poll < SAC_AWAIT_POLLS - 1) delay(SAC_POLL_MS)
        }
        return null
    }

    /**
     * Hora de la RED en segundos epoch: cierre del último ledger que conoce el RPC. Es el "ahora"
     * con el que se miden la vigencia y el vencimiento de las transacciones propias del depósito
     * (el reloj del teléfono puede estar mal). `null` si el RPC no responde o no la informa.
     */
    suspend fun networkTimeSec(): Long? = try {
        rpcServer.getLatestLedger().closeTime
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Log.w(TAG, "networkTimeSec: no se pudo leer el último ledger (${e.javaClass.simpleName})")
        null
    }

    /**
     * ¿Existe en la red el contrato `contractId` (p. ej. el smart account de una wallet
     * passkey)? Preflight obligatorio antes de [sacTransfer]: un `C…` es una dirección válida
     * aunque nadie lo haya desplegado, y el USDC enviado ahí sería irrecuperable. Mismo
     * criterio que el faucet del relayer (`raiz-relayer/src/stellar/reads.ts`, `contractExists`).
     *
     * Lee la entrada de instancia del contrato (lectura de ledger, no simulación). Si no
     * aparece, acepta como prueba de existencia que ese `C…` ya tenga saldo del USDC del fondo
     * en el SAC. Ante un fallo de red devuelve `Error`: quien llama NO debe enviar.
     */
    suspend fun contractExists(contractId: String): RaizResult<Boolean> {
        return try {
            val instance = rpcServer.getContractData(
                contractId,
                Scv.toLedgerKeyContractInstance(),
                SorobanServer.Durability.PERSISTENT,
            )
            if (instance != null) {
                RaizResult.Success(true)
            } else {
                val issuer = deployments.usdcIssuer
                val holdsUsdc = issuer != null && rpcServer.getSACBalance(
                    contractId,
                    Asset.createNonNativeAsset("USDC", issuer),
                    network,
                ).balanceEntry != null
                if (!holdsUsdc) Log.w(TAG, "contractExists: $contractId no aparece en la red")
                RaizResult.Success(holdsUsdc)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "contractExists($contractId): no se pudo consultar (${e.javaClass.simpleName})")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "No se pudo comprobar tu wallet en la red.")
        }
    }

    /**
     * Estado de la transacción `hash` según el RPC, para resolver un [sacTransfer] que quedó
     * en vuelo. Un solo intento; `UNKNOWN` = no se pudo consultar.
     */
    suspend fun transactionStatus(hash: String): DepositPlan.TxStatus = try {
        when (rpcServer.getTransaction(hash).status) {
            GetTransactionStatus.SUCCESS -> DepositPlan.TxStatus.SUCCESS
            GetTransactionStatus.FAILED -> DepositPlan.TxStatus.FAILED
            GetTransactionStatus.NOT_FOUND -> DepositPlan.TxStatus.NOT_FOUND
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Log.w(TAG, "transactionStatus($hash): no se pudo consultar (${e.javaClass.simpleName})")
        DepositPlan.TxStatus.UNKNOWN
    }

    /** Primer `Error(Tipo, Detalle)` del texto de una simulación fallida — nunca el texto completo ni XDR. */
    private fun hostErrorFragment(message: String?): String? =
        message?.let { HOST_ERROR_REGEX.find(it)?.value }

    /** Sobre de un [sacTransfer] ya firmado: se envía tal cual, nunca se reconstruye. */
    private class PreparedSacTransfer(val tx: Transaction, val hash: String, val validUntilSec: Long)

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
     * Historial de pagos on-chain para un address, leído desde los eventos Soroban
     * del contrato Pool. Complementa a [HorizonStream.paymentHistory] (que solo
     * captura pagos clásicos y no ve las invocaciones Soroban).
     *
     * Devuelve tanto SALIENTES (tourist==addr) como ENTRANTES (merchant==addr).
     * Un mismo address puede aparecer en ambos roles si tiene wallet dual
     * (turista que también es comerciante).
     *
     * PAGINACIÓN: getEvents devuelve eventos en orden ASCENDENTE desde startLedger.
     * Los pagos recientes están en las últimas páginas. Este método sigue el cursor
     * de cada respuesta hasta que llega null (última página) o alcanza MAX_PAGES.
     * Solo entonces filtra por addr — nunca a mitad de la lectura.
     *
     * PASSKEY: mientras el signing via smart-account no esté conectado, pasar el
     * G... del demoKeyPair (el que realmente firma payMerchant), NO el C... del
     * smart account. Ver WalletManager.currentKeyPair() y el TODO de passkey pleno.
     *
     * @param addr  G... o C... de la wallet. Devuelve SALIENTES (tourist==addr) y
     *              ENTRANTES (merchant==addr).
     * @return Lista de [PaymentRecord] en orden ledger desc (más reciente primero).
     */
    suspend fun tourPaymentEvents(
        addr: String,
    ): RaizResult<List<PaymentRecord>> {
        Log.i(TAG, "tourPaymentEvents: addr=$addr pool=${deployments.pool}")
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
                    runCatching { parsePaymentEvent(event, addr) }
                        .onFailure { Log.w(TAG, "tourPaymentEvents: parse falló event=${event.id}: ${it.message}") }
                        .getOrNull()
                }
            Log.i(TAG, "tourPaymentEvents: RESULTADO → ${records.size} PaymentRecords de ${allEvents.size} crudos para $addr")
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
     *   - ni tourist ni merchant del evento coinciden con addr
     *   - cualquier decodificación XDR falla
     *
     * Lógica de dirección:
     *   - tourist == addr → SALIENTE: from=tourist, to=merchant, amountStroops = amount + tip
     *   - merchant == addr → ENTRANTE: from=tourist, to=merchant, amountStroops = amount
     */
    private fun parsePaymentEvent(
        event: GetEventsResponse.EventInfo,
        addr: String,
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
        Log.i(TAG, "parsePaymentEvent: event=${event.id} tourist=$tourist merchant=$merchant buscando=$addr")

        // Determinar si el evento es SALIENTE (addr pagó) o ENTRANTE (addr cobró).
        // Si addr no coincide con ninguno de los dos, descartar silenciosamente.
        val isOutgoing = when (addr) {
            tourist  -> true
            merchant -> false
            else     -> {
                Log.i(TAG, "parsePaymentEvent: SKIP addr=$addr no es tourist ni merchant en event=${event.id}")
                return null
            }
        }

        // ── [2] amount + [3] tip → i128 stroops ────────────────────────────
        // El contrato define `amount` = monto base al comercio y `tip` = aporte al
        // barrio (amount * tip_bps / 10_000).
        // - SALIENTE: el turista pagó amount + tip (total deducido de su cuenta).
        // - ENTRANTE: el comercio recibió `amount` (el tip fue al pool, no al comercio).
        val amount = runCatching { ScvalParse.asLong(dataVec[2]) }.getOrElse { e ->
            Log.w(TAG, "parsePaymentEvent: asLong(amount) lanzó ${e.message} en event=${event.id}")
            return null
        }
        val tip = runCatching { ScvalParse.asLong(dataVec[3]) }.getOrElse { 0L }

        return PaymentRecord(
            txHash = event.transactionHash,
            from = tourist,
            to = merchant,
            amountStroops = if (isOutgoing) amount + tip else amount,
            assetCode = "USDC",
            createdAt = event.ledgerClosedAt,
            isOutgoing = isOutgoing,
        )
    }

    // ── Treasury: eventos `execution` vía getEvents (D2 — tx hash real) ──
    //
    // El contrato Treasury no puede conocer el hash de la transacción que lo
    // ejecuta: `Execution.tx_hash` on-chain es un sha256 determinístico (ID de
    // auditoría). El hash real solo existe fuera del contrato, en el evento que
    // el RPC indexa: `getEvents` devuelve `txHash` por evento. Aquí leemos TODOS
    // los eventos `execution` del Treasury dentro de la ventana de retención del
    // RPC y el dashboard los correlaciona con `get_execution_log` por
    // `proposal_id` (único a nivel de Governance; una propuesta se ejecuta una vez).
    //
    // DOS GOTCHAS del RPC (Protocol 28, medidos el 2026-09-12 contra testnet):
    //   1. La retención NO es 24h: `getHealth` reporta ledgerRetentionWindow =
    //      120 960 ledgers (~7 días). Usamos `oldestLedger` de getHealth como
    //      inicio (con margen, porque la ventana avanza mientras paginamos) en
    //      vez de una ventana fija.
    //   2. `getEvents` escanea como máximo ~10 000 ledgers por llamada y devuelve
    //      `cursor` AUNQUE la página venga vacía; al llegar al último ledger el
    //      cursor se repite en vez de volver `null`. Por eso NO se puede cortar
    //      en la primera página vacía (tourPaymentEvents sí lo hace porque su
    //      ventana es corta) y hay que parar cuando el cursor deja de avanzar.
    //      El cursor codifica el ledger en sus 32 bits altos (formato TOID).

    /**
     * Eventos `execution` del Treasury para un barrio, más recientes primero.
     * Lista vacía si no hay ninguno en la ventana de retención del RPC (las
     * ejecuciones más viejas siguen en `get_execution_log`, pero sin hash real:
     * la UI las muestra como "históricas", nunca inventa un link).
     */
    /**
     * @param sinceEpochSec si se conoce el `executed_at` más antiguo que interesa
     *   enlazar, el barrido arranca cerca de ese momento en vez de en el inicio de
     *   la retención: una ejecución de hace minutos cuesta 1 llamada en vez de ~13.
     *   La conversión tiempo→ledger asume 4 s/ledger (testnet va a ~5 s) más un
     *   margen fijo, así que siempre arranca ANTES del evento buscado.
     */
    suspend fun executionEvents(
        barrioId: String,
        sinceEpochSec: Long? = null,
    ): RaizResult<List<ExecutionEvent>> {
        val barrioHex = barrioId.removePrefix("0x").lowercase()
        if (barrioHex.hexToBytes() == null) {
            return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")
        }
        Log.i(TAG, "executionEvents: barrio=$barrioHex treasury=${deployments.treasury} since=$sinceEpochSec")
        return try {
            val server = treasuryClient().server
            val health = server.getHealth()
            val latest = health.latestLedger ?: server.getLatestLedger().sequence
            val retentionStart = (health.oldestLedger ?: (latest - EVENTS_LOOKBACK_LEDGERS).coerceAtLeast(1L)) +
                EXEC_RETENTION_MARGIN
            val sinceStart = sinceEpochSec?.let { since ->
                val ageSec = (System.currentTimeMillis() / 1000L - since).coerceAtLeast(0L)
                latest - ageSec / EXEC_MIN_LEDGER_SECONDS - EXEC_SINCE_MARGIN_LEDGERS
            }
            val oldest = maxOf(retentionStart, sinceStart ?: retentionStart) - EXEC_RETENTION_MARGIN
            Log.i(TAG, "executionEvents: latest=$latest start=${oldest + EXEC_RETENTION_MARGIN} retention=${health.ledgerRetentionWindow}")

            val eventFilter = GetEventsRequest.EventFilter(
                type = GetEventsRequest.EventFilterType.CONTRACT,
                contractIds = listOf(deployments.treasury),
                topics = emptyList(), // el Treasury solo emite `execution`; filtramos en cliente igualmente
            )

            // Ventana principal = toda la retención; fallback corto si el RPC la rechaza.
            val windowCandidates = listOf(
                (oldest + EXEC_RETENTION_MARGIN).coerceAtMost(latest),
                (latest - EVENTS_LOOKBACK_FALLBACK).coerceAtLeast(1L),
            ).distinct()

            var firstPage: GetEventsResponse? = null
            for (candidateStart in windowCandidates) {
                try {
                    firstPage = server.getEvents(
                        GetEventsRequest(
                            startLedger = candidateStart,
                            endLedger = null,
                            filters = listOf(eventFilter),
                            pagination = GetEventsRequest.Pagination(cursor = null, limit = 100L),
                        ),
                    )
                    Log.i(TAG, "executionEvents: 1ª pág start=$candidateStart → ${firstPage.events.size} ev cursor=${firstPage.cursor?.take(20)}")
                    break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val msg = e.message.orEmpty().lowercase()
                    Log.w(TAG, "executionEvents: getEvents(startLedger=$candidateStart) FALLÓ: $msg")
                    val isRetentionError = "startledger" in msg ||
                        "event retention" in msg ||
                        "minimum ledger" in msg ||
                        "ledger range" in msg
                    if (!isRetentionError) throw e
                }
            }
            if (firstPage == null) {
                Log.w(TAG, "executionEvents: sin respuesta en ninguna ventana → vacío")
                return RaizResult.Success(emptyList())
            }

            val allEvents = mutableListOf<GetEventsResponse.EventInfo>()
            allEvents.addAll(firstPage.events)
            var pageCursor: String? = firstPage.cursor?.takeIf { it.isNotEmpty() }
            var previousCursor: String? = null
            var pageCount = 1

            while (pageCursor != null && pageCursor != previousCursor && pageCount < EXEC_EVENTS_MAX_PAGES) {
                val cursorForPage = pageCursor
                // Reintento por página: un fallo transitorio en la página 9 no debe
                // tirar lo ya leído en las 8 anteriores.
                val nextPage = withNetworkRetry {
                    server.getEvents(
                        GetEventsRequest(
                            startLedger = null,   // OBLIGATORIO null cuando cursor está presente
                            endLedger = null,
                            filters = listOf(eventFilter),
                            pagination = GetEventsRequest.Pagination(cursor = cursorForPage, limit = 100L),
                        ),
                    )
                }
                pageCount++
                allEvents.addAll(nextPage.events)
                previousCursor = pageCursor
                pageCursor = nextPage.cursor?.takeIf { it.isNotEmpty() }
                Log.i(TAG, "executionEvents: pág $pageCount → ${nextPage.events.size} ev (total=${allEvents.size}) nextCursor=${pageCursor?.take(20)}")
                // Cursor TOID: ledger en los 32 bits altos. Si ya cubre el último
                // ledger conocido al empezar, no queda nada por escanear.
                val cursorLedger = pageCursor?.substringBefore('-')?.toLongOrNull()?.shr(32)
                if (cursorLedger != null && cursorLedger >= latest) break
            }
            if (pageCount >= EXEC_EVENTS_MAX_PAGES && pageCursor != null && pageCursor != previousCursor) {
                Log.w(TAG, "executionEvents: cap EXEC_EVENTS_MAX_PAGES=$EXEC_EVENTS_MAX_PAGES alcanzado — posibles eventos sin leer")
            }

            val parsed = allEvents
                .sortedByDescending { it.ledger }
                .mapNotNull { event ->
                    runCatching { parseExecutionEvent(event) }
                        .onFailure { Log.w(TAG, "executionEvents: parse falló event=${event.id}: ${it.message}") }
                        .getOrNull()
                }
                .filter { it.barrioId.equals(barrioHex, ignoreCase = true) }
            Log.i(TAG, "executionEvents: RESULTADO → ${parsed.size} eventos de ${allEvents.size} crudos en $pageCount págs para $barrioHex")
            RaizResult.Success(parsed)
        } catch (ce: CancellationException) {
            throw ce // cambio de barrio / pantalla cerrada: no es un error de red
        } catch (e: Exception) {
            Log.w(TAG, "executionEvents: error no recuperable: ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "executionEvents: ${e.message}")
        }
    }

    /**
     * Parsea un evento del Treasury como [ExecutionEvent]. Devuelve null si el
     * topic[0] no es Symbol("execution") o si el payload no tiene la forma
     * (proposal_id u64, amount i128, recipient Address).
     */
    private fun parseExecutionEvent(event: GetEventsResponse.EventInfo): ExecutionEvent? {
        val topics = event.parseTopic()
        if (topics.size < 2) return null
        val symbol = runCatching { Scv.fromSymbol(topics[0]) }.getOrNull() ?: return null
        if (symbol != "execution") return null
        val barrio = ScvalParse.asHex(topics[1])
        val dataVec = Scv.fromVec(event.parseValue())
        if (dataVec.size < 3) return null
        return ExecutionEvent(
            proposalId = ScvalParse.asULongAsLong(dataVec[0]),
            barrioId = barrio,
            amountStroops = ScvalParse.asLong(dataVec[1]),
            recipient = ScvalParse.asAddressString(dataVec[2]),
            txHash = event.transactionHash,
            ledger = event.ledger,
            ledgerClosedAt = event.ledgerClosedAt,
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

        // ── sacTransfer (depósito → wallet passkey) ──────────────────────────

        /** Tope de fee de inclusión del transfer (0,05 XLM); la fee de recursos la añade la simulación. */
        const val SAC_TRANSFER_BASE_FEE = 500_000L

        /** Vigencia (`maxTime`) del transfer desde la hora de la red: después ya no puede entrar en un ledger. */
        const val SAC_TRANSFER_TIMEOUT_SEC = 45L

        /** Reenvíos del MISMO sobre ante `TRY_AGAIN_LATER` (seguro: la red lo aplica a lo sumo una vez). */
        const val SAC_SEND_ATTEMPTS = 3
        const val SAC_SEND_RETRY_MS = 2_000L

        /** Sondeos de confirmación: 28 × 2,5 s = 70 s, más que la vigencia de la tx (45 s). */
        const val SAC_AWAIT_POLLS = 28
        const val SAC_POLL_MS = 2_500L

        /** `Error(Contract, #10)` y similares dentro del texto de una simulación fallida. */
        val HOST_ERROR_REGEX = Regex("Error\\(\\w+, [^)]{1,60}\\)")

        const val MSG_SAC_PREPARE_FAILED = "No se pudo preparar el envío a tu wallet. Revisa la red y reintenta."
        const val MSG_SAC_REJECTED = "La red rechazó el envío a tu wallet."
        const val MSG_SAC_EXPIRED = "El envío no llegó a entrar en la red. Reintenta."

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

        /**
         * Margen sobre `oldestLedger` de getHealth al pedir eventos `execution`:
         * la ventana de retención avanza ~1 ledger/5s y el RPC rechaza un
         * startLedger que ya quedó fuera (medido: 4 ledgers en 10 s).
         */
        const val EXEC_RETENTION_MARGIN = 200L

        /**
         * Páginas máximas para barrer toda la retención de eventos `execution`:
         * el RPC escanea ~10 000 ledgers por llamada → 120 960 ledgers ≈ 13
         * páginas. 30 deja holgura si la retención crece.
         */
        const val EXEC_EVENTS_MAX_PAGES = 30

        /**
         * Conversión conservadora tiempo→ledger para arrancar el barrido cerca de un
         * `executed_at`: testnet cierra ~1 ledger/5 s; asumir 4 s hace que el inicio
         * calculado caiga ANTES del evento real.
         */
        const val EXEC_MIN_LEDGER_SECONDS = 4L

        /** Margen extra (~1,4 h) restado al inicio calculado desde `executed_at`. */
        const val EXEC_SINCE_MARGIN_LEDGERS = 1_000L
    }
}
