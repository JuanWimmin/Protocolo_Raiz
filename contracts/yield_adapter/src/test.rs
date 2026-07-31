#![cfg(test)]

//! Tests del `BlendAdapter`.
//!
//! `MockBlendPool` simula el pool de Blend v2: aplica `submit` (Supply=0 /
//! Withdraw=1) sobre un balance de bTokens por Address (siempre la Address
//! del adapter — es el único caller posible), expone `get_reserve` con un
//! `b_rate` seteable (`set_b_rate`, simula yield), `get_config` (bstop_rate)
//! y `claim` (BLND). El "pool de RAÍZ" se simula con un `Address::generate`
//! plano — misma convención que `rewards/src/test.rs` ("el pool en tests es
//! una Address::generate plana, no el contrato real"): lo único que le
//! importa al adapter es que `caller == PoolContract` almacenado.

extern crate std;

use super::*;
use soroban_sdk::{
    contract, contractimpl, contracttype,
    testutils::{Address as _, BytesN as _, Events},
    token, Address, BytesN, Env, Map, Vec,
};

const IDX: u32 = 3; // mimetiza TestnetV2 (USDC = índice de reserva 3)

// ─────────────────────────────────────────────────────────────────────────────
// Structs espejo de Blend (copia local: MockBlendPool es su propio #[contract]
// en este módulo, no puede reusar los tipos privados de `blend_pool_client`).
// ─────────────────────────────────────────────────────────────────────────────

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
        env.storage().instance().set(&MBKey::BRate, &SCALAR_12);
        env.storage().instance().set(&MBKey::IrMod, &10_000_000i128); // 1.0, 7 dec
        env.storage().instance().set(&MBKey::BSupply, &0i128);
        env.storage().instance().set(&MBKey::DSupply, &0i128);
        env.storage().instance().set(&MBKey::BstopRate, &2_000_000u32); // 20%, 7 dec
        env.storage().instance().set(&MBKey::BlndAmount, &0i128);
    }

    /// Simula yield: sube el b_rate (1e12 = 1:1, 1.1e12 = +10%).
    pub fn set_b_rate(env: Env, rate: i128) {
        env.storage().instance().set(&MBKey::BRate, &rate);
    }

    /// Configura utilización (para `apy_hint`).
    pub fn set_utilization(env: Env, b_supply: i128, d_supply: i128, ir_mod: i128) {
        env.storage().instance().set(&MBKey::BSupply, &b_supply);
        env.storage().instance().set(&MBKey::DSupply, &d_supply);
        env.storage().instance().set(&MBKey::IrMod, &ir_mod);
    }

    pub fn set_blnd_amount(env: Env, amount: i128) {
        env.storage().instance().set(&MBKey::BlndAmount, &amount);
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
        let b_rate: i128 = env.storage().instance().get(&MBKey::BRate).unwrap_or(SCALAR_12);
        let me = env.current_contract_address();

        for req in requests.iter() {
            if req.request_type == 0 {
                // Supply: el spender envía USDC al pool; la posición se acredita a `from`.
                usdc.transfer(&spender, &me, &req.amount);
                let b_tokens = req.amount * SCALAR_12 / b_rate; // floor
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                env.storage().persistent().set(&key, &(current + b_tokens));
                let bs: i128 = env.storage().instance().get(&MBKey::BSupply).unwrap_or(0);
                env.storage().instance().set(&MBKey::BSupply, &(bs + b_tokens));
            } else if req.request_type == 1 {
                // Withdraw: quema bTokens de `from`, transfiere subyacente a `to`.
                // Blend real capea el retiro al balance disponible — replicado aquí.
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                let b_needed = (req.amount * SCALAR_12 + b_rate - 1) / b_rate; // ceil
                let (b_burn, transfer_amount) = if b_needed >= current {
                    (current, current * b_rate / SCALAR_12)
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
        supply.set(IDX, b_tokens);
        Positions {
            liabilities: Map::new(&env),
            collateral: Map::new(&env),
            supply,
        }
    }

    pub fn get_reserve(env: Env, asset: Address) -> Reserve {
        let b_rate: i128 = env.storage().instance().get(&MBKey::BRate).unwrap_or(SCALAR_12);
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
                index: IDX,
                decimals: 7,
                c_factor: 0,
                l_factor: 0,
                util: 7_500_000,     // target 75%, 7 dec
                max_util: 9_500_000,
                r_base: 100_000,     // 1%
                r_one: 500_000,      // 5%
                r_two: 5_000_000,    // 50%
                r_three: 15_000_000, // 150%
                reactivity: 20,
                supply_cap: i128::MAX,
                enabled: true,
            },
            data: ReserveData {
                d_rate: SCALAR_12,
                b_rate,
                ir_mod,
                b_supply,
                d_supply,
                backstop_credit: 0,
                last_time: env.ledger().timestamp(),
            },
            scalar: SCALAR_7,
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

// ─────────────────────────────────────────────────────────────────────────────
// Helpers de test
// ─────────────────────────────────────────────────────────────────────────────

fn create_token<'a>(env: &Env, admin: &Address) -> (Address, token::StellarAssetClient<'a>) {
    let sac = env.register_stellar_asset_contract_v2(admin.clone());
    let addr = sac.address();
    let client = token::StellarAssetClient::new(env, &addr);
    (addr, client)
}

struct Stack<'a> {
    client: BlendAdapterClient<'a>,
    admin: Address,
    /// Simula el Pool de RAÍZ: solo importa que sea el `caller` autorizado.
    pool: Address,
    usdc_addr: Address,
    usdc: token::Client<'a>,
    usdc_admin: token::StellarAssetClient<'a>,
    blend_addr: Address,
    blend: MockBlendPoolClient<'a>,
    blnd: token::Client<'a>,
    blnd_admin: token::StellarAssetClient<'a>,
}

fn setup<'a>(env: &'a Env) -> Stack<'a> {
    let admin = Address::generate(env);
    let pool = Address::generate(env);
    let (usdc_addr, usdc_admin) = create_token(env, &admin);
    let (blnd_addr, blnd_admin) = create_token(env, &admin);
    let usdc = token::Client::new(env, &usdc_addr);
    let blnd = token::Client::new(env, &blnd_addr);

    let blend_addr = env.register(MockBlendPool, ());
    let blend = MockBlendPoolClient::new(env, &blend_addr);

    let adapter_addr = env.register(BlendAdapter, ());
    let client = BlendAdapterClient::new(env, &adapter_addr);

    env.mock_all_auths();

    blend.initialize(&usdc_addr, &blnd_addr);
    client.initialize(&admin, &pool, &blend_addr, &usdc_addr);

    Stack {
        client,
        admin,
        pool,
        usdc_addr,
        usdc,
        usdc_admin,
        blend_addr,
        blend,
        blnd,
        blnd_admin,
    }
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: initialize
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_rejects_double_init() {
    let env = Env::default();
    let s = setup(&env);
    let result = s
        .client
        .try_initialize(&s.admin, &s.pool, &s.blend_addr, &s.usdc_addr);
    assert!(result.is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: deposit
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_deposit_happy_path() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);

    // Simula que Pool ya transfirió el USDC al adapter antes de llamar deposit.
    s.usdc_admin.mint(&s.client.address, &100_000_000i128);

    let shares = s.client.deposit(&s.pool, &bid, &50_000_000i128);
    assert_eq!(shares, 50_000_000); // 1:1 al b_rate inicial (1e12)
    assert_eq!(s.client.shares_of(&bid), 50_000_000);
    assert_eq!(s.client.total_shares(), 50_000_000);
    assert_eq!(s.usdc.balance(&s.blend_addr), 50_000_000);
    assert_eq!(s.usdc.balance(&s.client.address), 50_000_000); // resto sin invertir
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_deposit_rejects_non_pool_caller() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &10_000_000i128);

    let attacker = Address::generate(&env);
    s.client.deposit(&attacker, &bid, &10_000_000i128);
}

#[test]
#[should_panic(expected = "Error(Contract, #4)")] // InvalidAmount
fn test_deposit_rejects_invalid_amount() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.client.deposit(&s.pool, &bid, &0i128);
}

#[test]
fn test_total_shares_equals_sum_of_barrios() {
    let env = Env::default();
    let s = setup(&env);
    let bid_a = barrio_id(&env);
    let bid_b = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &150_000_000i128);

    s.client.deposit(&s.pool, &bid_a, &100_000_000i128);
    s.client.deposit(&s.pool, &bid_b, &50_000_000i128);

    assert_eq!(
        s.client.total_shares(),
        s.client.shares_of(&bid_a) + s.client.shares_of(&bid_b)
    );
    assert_eq!(s.client.total_shares(), 150_000_000);
}

#[test]
fn test_deposit_emits_supply_event() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &10_000_000i128);

    s.client.deposit(&s.pool, &bid, &10_000_000i128);
    let events = env.events().all();
    assert!(!events.events().is_empty());
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: withdraw
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_withdraw_happy_path() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &100_000_000i128);
    s.client.deposit(&s.pool, &bid, &100_000_000i128);

    let to = Address::generate(&env);
    let got = s.client.withdraw(&s.pool, &bid, &40_000_000i128, &to);
    assert_eq!(got, 40_000_000);
    assert_eq!(s.usdc.balance(&to), 40_000_000);
    assert_eq!(s.client.shares_of(&bid), 60_000_000);
    assert_eq!(s.client.total_shares(), 60_000_000);
}

/// CRÍTICO: corrige el bug pre-F1 de rescatar shares contablemente ajenas.
/// La posición conjunta en Blend SÍ tiene fondos suficientes (los de A), pero
/// B no tiene shares propias — debe fallar, nunca dejar `Shares(B)` negativo.
#[test]
#[should_panic(expected = "Error(Contract, #5)")] // InsufficientShares
fn test_withdraw_rejects_cross_barrio_raid() {
    let env = Env::default();
    let s = setup(&env);
    let bid_a = barrio_id(&env);
    let bid_b = barrio_id(&env);

    s.usdc_admin.mint(&s.client.address, &100_000_000i128);
    s.client.deposit(&s.pool, &bid_a, &100_000_000i128);
    assert_eq!(s.client.shares_of(&bid_b), 0);

    let to = Address::generate(&env);
    s.client.withdraw(&s.pool, &bid_b, &10_000_000i128, &to);
}

#[test]
#[should_panic(expected = "Error(Contract, #5)")] // InsufficientShares
fn test_withdraw_rejects_more_than_own_shares() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &50_000_000i128);
    s.client.deposit(&s.pool, &bid, &50_000_000i128);

    let to = Address::generate(&env);
    s.client.withdraw(&s.pool, &bid, &60_000_000i128, &to);
}

#[test]
#[should_panic(expected = "Error(Contract, #5)")] // InsufficientShares (shares <= 0)
fn test_withdraw_rejects_zero_shares() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    let to = Address::generate(&env);
    s.client.withdraw(&s.pool, &bid, &0i128, &to);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_withdraw_rejects_non_pool_caller() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &10_000_000i128);
    s.client.deposit(&s.pool, &bid, &10_000_000i128);

    let attacker = Address::generate(&env);
    let to = Address::generate(&env);
    s.client.withdraw(&attacker, &bid, &5_000_000i128, &to);
}

/// Documenta el invariante de redondeo (prueba algebraica en el comentario
/// de `withdraw` en lib.rs): con `b_rate >= 1e12`, pedir
/// `amount = floor(shares*b_rate/1e12)` y quemar `ceil(amount*1e12/b_rate)`
/// da SIEMPRE `burned == shares` exactamente, incluso con un `b_rate`
/// "feo" (no múltiplo de las cantidades involucradas) y montos pequeños.
/// El barrio no pierde ni retiene shares de más — cero dust perdido.
#[test]
fn test_withdraw_burn_matches_shares_exactly_no_dust_lost() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);

    s.blend.set_b_rate(&1_333_333_333_333i128); // 1.333...
    s.usdc_admin.mint(&s.client.address, &1_000_000_000i128);

    // floor(7 * 1e12 / 1_333_333_333_333) = floor(5.25000...) = 5
    let shares = s.client.deposit(&s.pool, &bid, &7i128);
    assert_eq!(shares, 5);
    assert_eq!(s.client.shares_of(&bid), 5);

    let to = Address::generate(&env);
    let got = s.client.withdraw(&s.pool, &bid, &5i128, &to);

    // Quema EXACTAMENTE las 5 shares — sin resto negativo, sin dust perdido.
    assert_eq!(s.client.shares_of(&bid), 0);
    assert_eq!(s.client.total_shares(), 0);
    // floor(5 * 1_333_333_333_333 / 1e12) = floor(6.6666...) = 6
    assert_eq!(got, 6);
    assert_eq!(s.usdc.balance(&to), 6);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: yield (b_rate sube) + value_of
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_value_of_and_shares_of_zero_initially() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    assert_eq!(s.client.shares_of(&bid), 0);
    assert_eq!(s.client.value_of(&bid), 0);
    assert_eq!(s.client.total_shares(), 0);
}

#[test]
fn test_yield_via_b_rate_increase() {
    let env = Env::default();
    let s = setup(&env);
    let bid = barrio_id(&env);
    s.usdc_admin.mint(&s.client.address, &100_000_000i128);
    s.client.deposit(&s.pool, &bid, &100_000_000i128);
    assert_eq!(s.client.value_of(&bid), 100_000_000);

    // Simula 10% de yield: b_rate sube de 1e12 a 1.1e12.
    s.blend.set_b_rate(&1_100_000_000_000i128);
    // El pool de Blend necesita el USDC extra para poder pagar el yield al retirar.
    s.usdc_admin.mint(&s.blend_addr, &10_000_000i128);

    assert_eq!(s.client.value_of(&bid), 110_000_000);

    let to = Address::generate(&env);
    let got = s.client.withdraw(&s.pool, &bid, &100_000_000i128, &to);
    assert_eq!(got, 110_000_000);
    assert_eq!(s.usdc.balance(&to), 110_000_000);
    assert_eq!(s.client.shares_of(&bid), 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: apy_hint
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_apy_hint_zero_when_no_demand() {
    let env = Env::default();
    let s = setup(&env);
    // b_supply=0 por defecto en el mock -> total_b=0 -> apy_hint = 0 (evita div/0).
    assert_eq!(s.client.apy_hint(), 0);
}

#[test]
fn test_apy_hint_positive_with_utilization() {
    let env = Env::default();
    let s = setup(&env);
    // 75% de utilización (justo en el target) con ir_mod = 1.0.
    s.blend
        .set_utilization(&1_000_000_000i128, &750_000_000i128, &10_000_000i128);
    let apy = s.client.apy_hint();
    // base_ir=6% -> loan_apr=6% -> supply_apr_pre_bstop=4.5% -> con bstop 20% -> 3.6% = 360 bps
    assert_eq!(apy, 360);
    assert!(apy > 0 && apy < 10_000);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: claim_blnd
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_claim_blnd_happy_path() {
    let env = Env::default();
    let s = setup(&env);
    s.blnd_admin.mint(&s.blend_addr, &5_000_000i128);
    s.blend.set_blnd_amount(&5_000_000i128);

    let to = Address::generate(&env);
    let claimed = s.client.claim_blnd(&s.admin, &to);
    assert_eq!(claimed, 5_000_000);
    assert_eq!(s.blnd.balance(&to), 5_000_000);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_claim_blnd_rejects_non_admin() {
    let env = Env::default();
    let s = setup(&env);
    let attacker = Address::generate(&env);
    let to = Address::generate(&env);
    s.client.claim_blnd(&attacker, &to);
}
