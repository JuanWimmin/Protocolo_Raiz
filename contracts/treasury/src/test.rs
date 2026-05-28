#![cfg(test)]

//! Tests integrados de Treasury.
//! Levanta los 4 contratos juntos (Rewards, Governance, Pool, Treasury) + USDC
//! SAC, los wirea como en producción y ejercita el flujo completo:
//!
//!   pago con tip → pool tiene balance → propuesta → voto → tally → execute → cobro.

extern crate std;

use super::*;
use governance::{GovernanceContract, GovernanceContractClient};
use pool::{MerchantData, PoolContract, PoolContractClient};
use rewards::RewardsContract;
use soroban_sdk::{
    testutils::{Address as _, BytesN as _, Ledger},
    token, Address, BytesN, Env, String, Symbol,
};

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
}

fn build_stack<'a>(env: &Env) -> Stack<'a> {
    env.mock_all_auths();

    let protocol_admin = Address::generate(env);

    // USDC SAC
    let sac = env.register_stellar_asset_contract_v2(protocol_admin.clone());
    let usdc_addr = sac.address();
    let usdc = token::Client::new(env, &usdc_addr);
    let usdc_admin = token::StellarAssetClient::new(env, &usdc_addr);

    // 4 contratos
    let rewards_addr = env.register(RewardsContract, ());
    let governance_addr = env.register(GovernanceContract, ());
    let pool_addr = env.register(PoolContract, ());
    let treasury_addr = env.register(TreasuryContract, ());

    let pool = PoolContractClient::new(env, &pool_addr);
    let governance = GovernanceContractClient::new(env, &governance_addr);
    let treasury = TreasuryContractClient::new(env, &treasury_addr);

    // Wire: initialize cada uno
    pool.initialize(&protocol_admin, &usdc_addr, &rewards_addr, &50u32);
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
