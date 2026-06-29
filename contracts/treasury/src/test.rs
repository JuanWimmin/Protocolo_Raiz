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
use rewards::{RewardsContract, RewardsContractClient};
use soroban_sdk::{
    contract, contractimpl, contracttype,
    testutils::{Address as _, BytesN as _, Ledger},
    token, Address, BytesN, Env, String, Symbol, Val, Vec,
};

// ─────────────────────────────────────────────────────────────────────────────
// MockVault local (duplicado de pool/test.rs; pool::test no es accesible desde
// crates externos porque está bajo #[cfg(test)]).
// ─────────────────────────────────────────────────────────────────────────────

#[contracttype]
#[derive(Clone)]
pub enum MVKey {
    Usdc,
    Shares(Address),
    Price,
}

#[contract]
pub struct MockVault;

#[contractimpl]
impl MockVault {
    pub fn initialize(env: Env, usdc: Address) {
        env.storage().instance().set(&MVKey::Usdc, &usdc);
        env.storage().instance().set(&MVKey::Price, &10_000i128);
    }
    pub fn set_price(env: Env, price: i128) {
        env.storage().instance().set(&MVKey::Price, &price);
    }
    pub fn deposit(
        env: Env,
        amounts_desired: Vec<i128>,
        _amounts_min: Vec<i128>,
        from: Address,
        _invest: bool,
    ) -> (Vec<i128>, i128, Val) {
        from.require_auth();
        let amount = amounts_desired.get(0).unwrap_or(0);
        let usdc_addr: Address = env.storage().instance().get(&MVKey::Usdc).unwrap();
        let usdc = token::Client::new(&env, &usdc_addr);
        let vault = env.current_contract_address();
        usdc.transfer(&from, &vault, &amount);
        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let shares = amount * 10_000 / price;
        let key = MVKey::Shares(from.clone());
        let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
        env.storage().persistent().set(&key, &(current + shares));
        let mut out = Vec::new(&env);
        out.push_back(amount);
        // Tercer elemento: Val::VOID (comodín compatible con el vault real que
        // devuelve ScVal::Vec([ScVal::Void]) cuando invest=true).
        (out, shares, Val::VOID.into())
    }
    pub fn withdraw(
        env: Env,
        withdraw_shares: i128,
        _min_amounts_out: Vec<i128>,
        from: Address,
    ) -> Vec<i128> {
        from.require_auth();
        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let amount = withdraw_shares * price / 10_000;
        let usdc_addr: Address = env.storage().instance().get(&MVKey::Usdc).unwrap();
        let usdc = token::Client::new(&env, &usdc_addr);
        let vault = env.current_contract_address();
        usdc.transfer(&vault, &from, &amount);
        let key = MVKey::Shares(from.clone());
        let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
        env.storage().persistent().set(&key, &(current - withdraw_shares));
        let mut out = Vec::new(&env);
        out.push_back(amount);
        out
    }
    pub fn balance(env: Env, id: Address) -> i128 {
        env.storage().persistent().get(&MVKey::Shares(id)).unwrap_or(0)
    }
    pub fn get_asset_amounts_per_shares(env: Env, vault_shares: i128) -> Vec<i128> {
        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let mut out = Vec::new(&env);
        out.push_back(vault_shares * price / 10_000);
        out
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
    vault_addr: Address,
}

fn build_stack<'a>(env: &'a Env) -> Stack<'a> {
    env.mock_all_auths();

    let protocol_admin = Address::generate(env);

    // USDC SAC
    let sac = env.register_stellar_asset_contract_v2(protocol_admin.clone());
    let usdc_addr = sac.address();
    let usdc = token::Client::new(env, &usdc_addr);
    let usdc_admin = token::StellarAssetClient::new(env, &usdc_addr);

    // 5 contratos (4 de RAÍZ + MockVault)
    let rewards_addr = env.register(RewardsContract, ());
    let governance_addr = env.register(GovernanceContract, ());
    let vault_addr = env.register(MockVault, ());
    let pool_addr = env.register(PoolContract, ());
    let treasury_addr = env.register(TreasuryContract, ());

    let pool = PoolContractClient::new(env, &pool_addr);
    let governance = GovernanceContractClient::new(env, &governance_addr);
    let treasury = TreasuryContractClient::new(env, &treasury_addr);
    let rewards = RewardsContractClient::new(env, &rewards_addr);
    let vault = MockVaultClient::new(env, &vault_addr);

    // Wire: initialize cada uno.
    vault.initialize(&usdc_addr);
    rewards.initialize(&protocol_admin, &pool_addr);
    // initialize ahora toma defindex_vault como 5° arg
    pool.initialize(&protocol_admin, &usdc_addr, &rewards_addr, &50u32, &vault_addr);
    governance.initialize(&protocol_admin, &treasury_addr);
    treasury.initialize(&pool_addr, &governance_addr);

    let _ = vault; // silencia warning unused

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
        vault_addr,
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

/// Flujo completo con yield de vault:
///   pago → tip al pool → deposit al vault → propuesta → voto → execute
/// Treasury debe rescatar shares del vault antes de ejecutar el pago.
/// Con yield simulado (10%), el pool recupera más USDC del que depositó.
#[test]
fn test_execute_proposal_with_vault_shares_and_yield() {
    let env = Env::default();
    let s = build_stack(&env);
    let (bid, residents) = setup_barrio(&env, &s, 10);

    // Pool del barrio tiene 20_000_000 stroops (2 USDC) tras el pago con tip.
    let pool_initial = s.pool.get_pool_balance(&bid);
    assert_eq!(pool_initial, 20_000_000);

    // Admin deposita TODO el idle al vault (pool_balance queda en 0).
    s.pool.deposit_idle_to_vault(&s.protocol_admin, &bid, &pool_initial);
    assert_eq!(s.pool.get_pool_balance(&bid), 0);
    let shares = s.pool.get_vault_shares(&bid);
    assert_eq!(shares, 20_000_000); // 1:1

    // Simula yield del 10%: MockVault.set_price(11_000) + mint extra al vault.
    let vault = MockVaultClient::new(&env, &s.vault_addr);
    vault.set_price(&11_000i128);
    // El vault necesita USDC extra para cubrir el yield (10% de 20M = 2M).
    s.usdc_admin.mint(&s.vault_addr, &2_000_000i128);

    // Crea propuesta: quiere 1 USDC (10_000_000 stroops) del pool.
    // Como todo está en vault, Treasury deberá rescatar antes de pagar.
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

    // Vault shares ya son 0 (todo rescatado)
    assert_eq!(s.pool.get_vault_shares(&bid), 0);

    // Proposal marcada Executed
    assert_eq!(
        s.governance.get_proposal(&id).status,
        governance::ProposalStatus::Executed
    );
    // Treasury tiene 1 execution registrada
    assert_eq!(s.treasury.get_execution_count(&bid), 1);

    let _ = s.governance_addr; // silencia warning
}
