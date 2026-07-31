#![cfg(test)]

//! Tests integrados de Treasury.
//! Levanta los 4 contratos juntos (Rewards, Governance, Pool, Treasury) + USDC
//! SAC + el yield_adapter REAL (BlendAdapter) + MockBlendPool, los wirea como
//! en producción y ejercita el flujo completo:
//!
//!   pago con tip → pool tiene balance → propuesta → voto → tally → execute → cobro.

extern crate std;

use super::*;
use governance::{GovernanceContract, GovernanceContractClient};
use pool::{MerchantData, PoolContract, PoolContractClient};
use rewards::{RewardsContract, RewardsContractClient};
use soroban_sdk::{
    contract, contractimpl, contracttype,
    testutils::{Address as _, BytesN as _, Ledger},
    token, Address, BytesN, Env, Map, String, Symbol, Vec,
};
use yield_adapter::{BlendAdapter, BlendAdapterClient};

// ─────────────────────────────────────────────────────────────────────────────
// MockBlendPool (duplicado de pool/src/test.rs; `yield_adapter::test` y
// `pool::test` son `#[cfg(test)]` y no son accesibles desde este crate externo).
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_SCALAR_12: i128 = 1_000_000_000_000;
const MOCK_IDX: u32 = 3;

#[contracttype]
#[derive(Clone)]
pub struct Request {
    pub request_type: u32,
    pub address: Address,
    pub amount: i128,
}

#[contracttype]
#[derive(Clone)]
pub struct Positions {
    pub liabilities: Map<u32, i128>,
    pub collateral: Map<u32, i128>,
    pub supply: Map<u32, i128>,
}

#[contracttype]
#[derive(Clone)]
pub struct ReserveConfig {
    pub index: u32,
    pub decimals: u32,
    pub c_factor: u32,
    pub l_factor: u32,
    pub util: u32,
    pub max_util: u32,
    pub r_base: u32,
    pub r_one: u32,
    pub r_two: u32,
    pub r_three: u32,
    pub reactivity: u32,
    pub supply_cap: i128,
    pub enabled: bool,
}

#[contracttype]
#[derive(Clone)]
pub struct ReserveData {
    pub d_rate: i128,
    pub b_rate: i128,
    pub ir_mod: i128,
    pub b_supply: i128,
    pub d_supply: i128,
    pub backstop_credit: i128,
    pub last_time: u64,
}

#[contracttype]
#[derive(Clone)]
pub struct Reserve {
    pub asset: Address,
    pub config: ReserveConfig,
    pub data: ReserveData,
    pub scalar: i128,
}

#[contracttype]
#[derive(Clone)]
pub struct PoolConfig {
    pub oracle: Address,
    pub min_collateral: i128,
    pub bstop_rate: u32,
    pub status: u32,
    pub max_positions: u32,
}

#[contracttype]
#[derive(Clone)]
pub enum MBKey {
    Usdc,
    Blnd,
    BRate,
    IrMod,
    BSupply,
    DSupply,
    BstopRate,
    BTokens(Address),
    BlndAmount,
}

#[contract]
pub struct MockBlendPool;

#[contractimpl]
impl MockBlendPool {
    pub fn initialize(env: Env, usdc: Address, blnd: Address) {
        env.storage().instance().set(&MBKey::Usdc, &usdc);
        env.storage().instance().set(&MBKey::Blnd, &blnd);
        env.storage().instance().set(&MBKey::BRate, &MOCK_SCALAR_12);
        env.storage().instance().set(&MBKey::IrMod, &10_000_000i128);
        env.storage().instance().set(&MBKey::BSupply, &0i128);
        env.storage().instance().set(&MBKey::DSupply, &0i128);
        env.storage().instance().set(&MBKey::BstopRate, &2_000_000u32);
        env.storage().instance().set(&MBKey::BlndAmount, &0i128);
    }

    pub fn set_b_rate(env: Env, rate: i128) {
        env.storage().instance().set(&MBKey::BRate, &rate);
    }

    pub fn submit(
        env: Env,
        from: Address,
        spender: Address,
        to: Address,
        requests: Vec<Request>,
    ) -> Positions {
        spender.require_auth();
        if from != spender {
            from.require_auth();
        }
        let usdc_addr: Address = env.storage().instance().get(&MBKey::Usdc).unwrap();
        let usdc = token::Client::new(&env, &usdc_addr);
        let b_rate: i128 = env
            .storage()
            .instance()
            .get(&MBKey::BRate)
            .unwrap_or(MOCK_SCALAR_12);
        let me = env.current_contract_address();

        for req in requests.iter() {
            if req.request_type == 0 {
                usdc.transfer(&spender, &me, &req.amount);
                let b_tokens = req.amount * MOCK_SCALAR_12 / b_rate;
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                env.storage().persistent().set(&key, &(current + b_tokens));
                let bs: i128 = env.storage().instance().get(&MBKey::BSupply).unwrap_or(0);
                env.storage().instance().set(&MBKey::BSupply, &(bs + b_tokens));
            } else if req.request_type == 1 {
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                let b_needed = (req.amount * MOCK_SCALAR_12 + b_rate - 1) / b_rate;
                let (b_burn, transfer_amount) = if b_needed >= current {
                    (current, current * b_rate / MOCK_SCALAR_12)
                } else {
                    (b_needed, req.amount)
                };
                usdc.transfer(&me, &to, &transfer_amount);
                env.storage().persistent().set(&key, &(current - b_burn));
                let bs: i128 = env.storage().instance().get(&MBKey::BSupply).unwrap_or(0);
                env.storage().instance().set(&MBKey::BSupply, &(bs - b_burn));
            }
        }

        Self::get_positions(env.clone(), from)
    }

    pub fn get_positions(env: Env, address: Address) -> Positions {
        let b_tokens: i128 = env
            .storage()
            .persistent()
            .get(&MBKey::BTokens(address))
            .unwrap_or(0);
        let mut supply = Map::new(&env);
        supply.set(MOCK_IDX, b_tokens);
        Positions {
            liabilities: Map::new(&env),
            collateral: Map::new(&env),
            supply,
        }
    }

    pub fn get_reserve(env: Env, asset: Address) -> Reserve {
        let b_rate: i128 = env
            .storage()
            .instance()
            .get(&MBKey::BRate)
            .unwrap_or(MOCK_SCALAR_12);
        let ir_mod: i128 = env
            .storage()
            .instance()
            .get(&MBKey::IrMod)
            .unwrap_or(10_000_000);
        let b_supply: i128 = env.storage().instance().get(&MBKey::BSupply).unwrap_or(0);
        let d_supply: i128 = env.storage().instance().get(&MBKey::DSupply).unwrap_or(0);
        Reserve {
            asset,
            config: ReserveConfig {
                index: MOCK_IDX,
                decimals: 7,
                c_factor: 0,
                l_factor: 0,
                util: 7_500_000,
                max_util: 9_500_000,
                r_base: 100_000,
                r_one: 500_000,
                r_two: 5_000_000,
                r_three: 15_000_000,
                reactivity: 20,
                supply_cap: i128::MAX,
                enabled: true,
            },
            data: ReserveData {
                d_rate: MOCK_SCALAR_12,
                b_rate,
                ir_mod,
                b_supply,
                d_supply,
                backstop_credit: 0,
                last_time: env.ledger().timestamp(),
            },
            scalar: 10_000_000,
        }
    }

    pub fn get_config(env: Env) -> PoolConfig {
        let bstop_rate: u32 = env.storage().instance().get(&MBKey::BstopRate).unwrap_or(0);
        PoolConfig {
            oracle: env.current_contract_address(),
            min_collateral: 0,
            bstop_rate,
            status: 6,
            max_positions: 4,
        }
    }

    pub fn claim(env: Env, from: Address, reserve_token_ids: Vec<u32>, to: Address) -> i128 {
        from.require_auth();
        let _ = reserve_token_ids;
        let amount: i128 = env.storage().instance().get(&MBKey::BlndAmount).unwrap_or(0);
        if amount > 0 {
            let blnd_addr: Address = env.storage().instance().get(&MBKey::Blnd).unwrap();
            let blnd = token::Client::new(&env, &blnd_addr);
            blnd.transfer(&env.current_contract_address(), &to, &amount);
        }
        amount
    }
}

const DAY: u64 = 86_400;

struct Stack<'a> {
    treasury: TreasuryContractClient<'a>,
    pool: PoolContractClient<'a>,
    governance: GovernanceContractClient<'a>,
    usdc: token::Client<'a>,
    usdc_admin: token::StellarAssetClient<'a>,
    protocol_admin: Address,
    treasury_addr: Address,
    pool_addr: Address,
    governance_addr: Address,
    adapter_addr: Address,
    blend_addr: Address,
}

fn build_stack<'a>(env: &'a Env) -> Stack<'a> {
    env.mock_all_auths();

    let protocol_admin = Address::generate(env);

    // USDC SAC
    let sac = env.register_stellar_asset_contract_v2(protocol_admin.clone());
    let usdc_addr = sac.address();
    let usdc = token::Client::new(env, &usdc_addr);
    let usdc_admin = token::StellarAssetClient::new(env, &usdc_addr);
    // BLND SAC (solo lo necesita MockBlendPool.initialize; no se usa en estos tests).
    let blnd_sac = env.register_stellar_asset_contract_v2(protocol_admin.clone());
    let blnd_addr_token = blnd_sac.address();

    // 6 contratos (4 de RAÍZ + yield_adapter real + MockBlendPool)
    let rewards_addr = env.register(RewardsContract, ());
    let governance_addr = env.register(GovernanceContract, ());
    let blend_addr = env.register(MockBlendPool, ());
    let adapter_addr = env.register(BlendAdapter, ());
    let pool_addr = env.register(PoolContract, ());
    let treasury_addr = env.register(TreasuryContract, ());

    let pool = PoolContractClient::new(env, &pool_addr);
    let governance = GovernanceContractClient::new(env, &governance_addr);
    let treasury = TreasuryContractClient::new(env, &treasury_addr);
    let rewards = RewardsContractClient::new(env, &rewards_addr);
    let blend = MockBlendPoolClient::new(env, &blend_addr);
    let adapter = BlendAdapterClient::new(env, &adapter_addr);

    // Wire: initialize cada uno.
    blend.initialize(&usdc_addr, &blnd_addr_token);
    adapter.initialize(&protocol_admin, &pool_addr, &blend_addr, &usdc_addr);
    rewards.initialize(&protocol_admin, &pool_addr);
    // initialize toma yield_adapter como 5° arg (F1; antes defindex_vault).
    pool.initialize(&protocol_admin, &usdc_addr, &rewards_addr, &50u32, &adapter_addr);
    governance.initialize(&protocol_admin, &treasury_addr);
    treasury.initialize(&pool_addr, &governance_addr);

    Stack {
        treasury,
        pool,
        governance,
        usdc,
        usdc_admin,
        protocol_admin,
        treasury_addr,
        pool_addr,
        governance_addr,
        adapter_addr,
        blend_addr,
    }
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

/// Setup completo de un barrio: registra barrio en Pool y Governance,
/// añade un comercio, mintea N residentes, y simula un pago con tip que llena
/// el pool del barrio. Devuelve barrio_id, residentes, recipient candidato.
fn setup_barrio<'a>(
    env: &Env,
    s: &Stack<'a>,
    n_residents: u32,
) -> (BytesN<32>, std::vec::Vec<Address>) {
    let bid = barrio_id(env);

    // Pool: registra barrio con Treasury como treasury_contract autorizado.
    s.pool.register_barrio(
        &bid,
        &String::from_str(env, "Centro Historico"),
        &s.treasury_addr,
    );

    // Pool: registra un comercio verificado.
    let merchant_addr = Address::generate(env);
    s.pool.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(env, "Cafe Don Aurelio"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 4_598_000,
        lng_e6: -74_075_000,
        category: Symbol::new(env, "cafe"),
    });

    // Governance: registra admin del barrio + mintea N residentes.
    let barrio_admin = Address::generate(env);
    s.governance.set_barrio_admin(&bid, &barrio_admin);
    let mut residents = std::vec::Vec::new();
    for _ in 0..n_residents {
        let r = Address::generate(env);
        s.governance.mint_resident(&barrio_admin, &r, &bid);
        residents.push(r);
    }

    // Pago con tip: fondea el pool del barrio con un buen monto.
    let tourist = Address::generate(env);
    s.usdc_admin.mint(&tourist, &10_000_000_000i128); // 1000 USDC
    // 1_000_000_000 stroops (100 USDC) con tip 2% → 20_000_000 stroops (2 USDC) al pool.
    s.pool
        .pay_merchant(&tourist, &merchant_addr, &1_000_000_000i128, &200u32);

    (bid, residents)
}

/// Crea una propuesta y vota la cantidad de a favor / en contra indicada.
fn create_and_vote<'a>(
    env: &Env,
    s: &Stack<'a>,
    bid: &BytesN<32>,
    residents: &[Address],
    recipient: &Address,
    amount: i128,
    votes_for: usize,
    votes_against: usize,
) -> u64 {
    let id = s.governance.create_proposal(
        &residents[0],
        bid,
        &String::from_str(env, "Pintura para la plaza"),
        &amount,
        recipient,
        &3u32,
    );
    let mut i = 0;
    for _ in 0..votes_for {
        s.governance.vote(&residents[i], &id, &true);
        i += 1;
    }
    for _ in 0..votes_against {
        s.governance.vote(&residents[i], &id, &false);
        i += 1;
    }
    id
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_idempotent() {
    let env = Env::default();
    let s = build_stack(&env);
    let result = s.treasury.try_initialize(&s.pool_addr, &s.governance_addr);
    assert!(result.is_err());
}

#[test]
fn test_execute_proposal_end_to_end() {
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);

    // Pool del barrio tiene 20_000_000 stroops (2 USDC) tras el pago con tip.
    assert_eq!(s.pool.get_pool_balance(&bid), 20_000_000);

    // Propuesta: 1 USDC (10_000_000 stroops) para un recipient.
    let recipient = Address::generate(&env);
    let id = create_and_vote(&env, &s, &bid, &residents, &recipient, 10_000_000, 4, 1);

    // Cierra: adelanta el ledger más allá de los 3 días + tally para sellar Passed.
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    assert_eq!(
        s.governance.tally(&id),
        governance::ProposalStatus::Passed
    );

    // Ejecuta — cualquiera puede llamar.
    s.treasury.execute_proposal(&id);

    // Recipient recibió 1 USDC
    assert_eq!(s.usdc.balance(&recipient), 10_000_000);
    // Pool bajó de 20M a 10M
    assert_eq!(s.pool.get_pool_balance(&bid), 10_000_000);
    // Proposal marcada Executed
    assert_eq!(
        s.governance.get_proposal(&id).status,
        governance::ProposalStatus::Executed
    );
    // Execution registrada
    assert_eq!(s.treasury.get_execution_count(&bid), 1);
    let log = s.treasury.get_execution_log(&bid);
    assert_eq!(log.len(), 1);
    let exec = log.get(0).unwrap();
    assert_eq!(exec.proposal_id, id);
    assert_eq!(exec.amount, 10_000_000);
    assert_eq!(exec.recipient, recipient);
}

#[test]
fn test_execute_rejects_unpassed_proposal() {
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);
    let recipient = Address::generate(&env);

    // Solo 2 votos a favor → 2/10 = 20% < 30% → Rejected.
    let id = create_and_vote(&env, &s, &bid, &residents, &recipient, 1_000_000, 2, 0);
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    assert_eq!(
        s.governance.tally(&id),
        governance::ProposalStatus::Rejected
    );

    // Treasury debe rechazar la ejecución.
    let result = s.treasury.try_execute_proposal(&id);
    assert!(result.is_err());
    // Pool intacto
    assert_eq!(s.pool.get_pool_balance(&bid), 20_000_000);
}

#[test]
fn test_double_execute_fails() {
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);
    let recipient = Address::generate(&env);
    let id = create_and_vote(&env, &s, &bid, &residents, &recipient, 5_000_000, 4, 0);
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);

    // Primera ejecución OK
    s.treasury.execute_proposal(&id);
    assert_eq!(s.usdc.balance(&recipient), 5_000_000);

    // Segunda ejecución: el status ya es Executed (no Passed) → falla.
    let result = s.treasury.try_execute_proposal(&id);
    assert!(result.is_err());
    // Recipient no recibió más
    assert_eq!(s.usdc.balance(&recipient), 5_000_000);
}

#[test]
fn test_anyone_can_execute_passed_proposal() {
    // El protocolo es trustless: cualquier address puede disparar execute
    // sobre una propuesta Passed. Aquí solo verificamos que mock_all_auths
    // permite cualquier caller (no hay require_auth en execute_proposal).
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);
    let recipient = Address::generate(&env);
    let id = create_and_vote(&env, &s, &bid, &residents, &recipient, 1_000_000, 4, 0);
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);

    // No hace falta firmar como nadie — execute_proposal no requiere auth.
    s.treasury.execute_proposal(&id);
    assert_eq!(s.usdc.balance(&recipient), 1_000_000);
    let _ = (s.protocol_admin, s.governance_addr); // silencia warnings de unused
}

/// Flujo completo con yield del yield_adapter (F1):
///   pago → tip al pool → deposit al adapter → propuesta → voto → execute
/// Treasury debe rescatar shares del adapter antes de ejecutar el pago.
/// Con yield simulado (10%), el pool recupera más USDC del que depositó.
///
/// DECISIÓN: este test deposita el 100% del fondo idle del barrio al adapter
/// (para ejercitar el rescate TOTAL que hace Treasury). Eso viola el colchón
/// líquido por defecto de Pool (20%, F1) — se setea `cushion_bps = 0` en el
/// setup de este test para preservar el escenario original ("todo invertido,
/// Treasury debe rescatarlo todo antes de pagar").
#[test]
fn test_execute_proposal_with_vault_shares_and_yield() {
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);

    // Pool del barrio tiene 20_000_000 stroops (2 USDC) tras el pago con tip.
    let pool_initial = s.pool.get_pool_balance(&bid);
    assert_eq!(pool_initial, 20_000_000);

    // Sin colchón para este test (ver DECISIÓN en el doc del test).
    s.pool.set_cushion_bps(&s.protocol_admin, &0u32);

    // Admin deposita TODO el idle al adapter (pool_balance queda en 0).
    s.pool
        .deposit_idle_to_vault(&s.protocol_admin, &bid, &pool_initial);
    assert_eq!(s.pool.get_pool_balance(&bid), 0);
    let shares = s.pool.get_vault_shares(&bid);
    assert_eq!(shares, 20_000_000); // 1:1 al b_rate inicial

    // Simula yield del 10%: MockBlendPool.set_b_rate(1.1e12) + mint extra al
    // pool de Blend (mock) para cubrir el yield.
    let blend = MockBlendPoolClient::new(&env, &s.blend_addr);
    blend.set_b_rate(&1_100_000_000_000i128);
    // El pool de Blend necesita USDC extra para cubrir el yield (10% de 20M = 2M).
    s.usdc_admin.mint(&s.blend_addr, &2_000_000i128);

    // Crea propuesta: quiere 1 USDC (10_000_000 stroops) del pool.
    // Como todo está en el adapter, Treasury deberá rescatar antes de pagar.
    let recipient = Address::generate(&env);
    let id = create_and_vote(&env, &s, &bid, &residents, &recipient, 10_000_000, 4, 0);

    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    assert_eq!(s.governance.tally(&id), governance::ProposalStatus::Passed);

    // execute_proposal debe:
    //  1. Detectar shares > 0 → llamar pool.redeem_from_vault
    //     pool_balance += 22_000_000 (20M shares * 1.1 = 22M)
    //  2. pool.withdraw_to 10_000_000 al recipient
    s.treasury.execute_proposal(&id);

    // Recipient recibió exactamente 1 USDC (10_000_000 stroops)
    assert_eq!(s.usdc.balance(&recipient), 10_000_000);

    // pool_balance = 22_000_000 (rescatado con yield) - 10_000_000 (propuesta) = 12_000_000
    assert_eq!(s.pool.get_pool_balance(&bid), 12_000_000);

    // Shares del adapter ya son 0 (todo rescatado)
    assert_eq!(s.pool.get_vault_shares(&bid), 0);

    // Proposal marcada Executed
    assert_eq!(
        s.governance.get_proposal(&id).status,
        governance::ProposalStatus::Executed
    );
    // Treasury tiene 1 execution registrada
    assert_eq!(s.treasury.get_execution_count(&bid), 1);

    let _ = (s.governance_addr, s.adapter_addr); // silencia warnings
}
