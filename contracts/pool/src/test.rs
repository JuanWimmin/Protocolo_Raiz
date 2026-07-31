#![cfg(test)]

//! Tests del contrato Pool.
//! Cubre: inicialización, registro de barrio/comercio, pago con tip,
//! actualización de estadísticas, retiro autorizado por el treasury, y el
//! flujo completo de yield (F1: Pool -> yield_adapter real (`BlendAdapter`)
//! -> `MockBlendPool` local (Blend v2 no tiene testutils compatibles con
//! soroban-sdk 26 — se mockea el pool de Blend, NO el adapter, que es
//! nuestro código real).
//!
//! Cross-contract a Rewards: el `setup()` registra el contrato Rewards real
//! (crate `rewards`, declarado como dev-dependency) en el env de test y le
//! pasa esa Address al `initialize` del Pool. Así `pay_merchant` puede llamar
//! `accrue_points` sin panic.
//!
//! Cross-contract al yield_adapter: se registra el crate `yield_adapter` REAL
//! (`BlendAdapter`, dev-dependency) — NO un mock del adapter. El adapter a su
//! vez habla con `MockBlendPool` (definido aquí abajo, mismo patrón que
//! `yield_adapter/src/test.rs`). Los fondos depositados terminan custodiados
//! por `MockBlendPool` (blend_addr), no por el adapter (que solo los retiene
//! transitoriamente entre `usdc.transfer` y `submit`).

extern crate std;

use super::*;
use rewards::{RewardsContract, RewardsContractClient};
use soroban_sdk::{
    contract, contractimpl, contracttype,
    testutils::{Address as _, BytesN as _, Events},
    token, Address, BytesN, Env, Map, String, Symbol, Vec,
};
use yield_adapter::{BlendAdapter, BlendAdapterClient};

// ─────────────────────────────────────────────────────────────────────────────
// MockBlendPool — simula el pool de Blend v2 que consume el BlendAdapter real.
// Copia local (duplicado intencional, mismo patrón que el resto del repo con
// mocks compartidos entre crates): `yield_adapter::test` es `#[cfg(test)]` y
// no es accesible desde este crate externo.
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_SCALAR_12: i128 = 1_000_000_000_000;
const MOCK_IDX: u32 = 3; // mimetiza TestnetV2 (USDC = índice de reserva 3)

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

    /// Simula yield: sube el b_rate (1e12 = 1:1, 1.1e12 = +10%).
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
                // Supply
                usdc.transfer(&spender, &me, &req.amount);
                let b_tokens = req.amount * MOCK_SCALAR_12 / b_rate; // floor
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                env.storage().persistent().set(&key, &(current + b_tokens));
                let bs: i128 = env.storage().instance().get(&MBKey::BSupply).unwrap_or(0);
                env.storage().instance().set(&MBKey::BSupply, &(bs + b_tokens));
            } else if req.request_type == 1 {
                // Withdraw: capea al balance disponible (como Blend real).
                let key = MBKey::BTokens(from.clone());
                let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
                let b_needed = (req.amount * MOCK_SCALAR_12 + b_rate - 1) / b_rate; // ceil
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

// ─────────────────────────────────────────────────────────────────────────────
// Helpers de test
// ─────────────────────────────────────────────────────────────────────────────

fn create_usdc<'a>(env: &Env, admin: &Address) -> (Address, token::StellarAssetClient<'a>) {
    let sac = env.register_stellar_asset_contract_v2(admin.clone());
    let addr = sac.address();
    let admin_client = token::StellarAssetClient::new(env, &addr);
    (addr, admin_client)
}

/// Setup completo:
///   - Registra MockBlendPool, el yield_adapter REAL (BlendAdapter), Rewards, Pool.
///   - Inicializa MockBlendPool, BlendAdapter (pool_contract = el Pool real),
///     Rewards (apuntando al Pool), y Pool (con adapter_addr como 5° arg).
fn setup<'a>(
    env: &'a Env,
) -> (
    PoolContractClient<'a>,
    Address, // admin
    Address, // usdc address
    token::StellarAssetClient<'a>,
    Address, // yield_adapter (BlendAdapter) address
    Address, // MockBlendPool address (donde termina custodiado el USDC invertido)
) {
    let admin = Address::generate(env);
    let (usdc_addr, usdc_admin) = create_usdc(env, &admin);
    let (blnd_addr, _blnd_admin) = create_usdc(env, &admin);

    let blend_addr = env.register(MockBlendPool, ());
    let blend_client = MockBlendPoolClient::new(env, &blend_addr);

    let adapter_addr = env.register(BlendAdapter, ());
    let adapter_client = BlendAdapterClient::new(env, &adapter_addr);

    let rewards_addr = env.register(RewardsContract, ());
    let contract_id = env.register(PoolContract, ());
    let client = PoolContractClient::new(env, &contract_id);
    let rewards = RewardsContractClient::new(env, &rewards_addr);

    env.mock_all_auths();

    blend_client.initialize(&usdc_addr, &blnd_addr);
    adapter_client.initialize(&admin, &contract_id, &blend_addr, &usdc_addr);
    rewards.initialize(&admin, &contract_id);
    client.initialize(&admin, &usdc_addr, &rewards_addr, &50u32, &adapter_addr);

    (client, admin, usdc_addr, usdc_admin, adapter_addr, blend_addr)
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests base (pago, registro, retiro) — sin cambios funcionales
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_and_register_barrio() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "Centro Historico"), &treasury);

    let barrio = client.get_barrio(&bid);
    assert_eq!(barrio.pool_balance, 0);
    assert_eq!(barrio.tx_count, 0);
    assert_eq!(barrio.unique_tourists, 0);
    assert_eq!(barrio.treasury_contract, treasury);
}

#[test]
fn test_register_merchant_and_list() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "Centro Historico"), &treasury);

    let merchant_addr = Address::generate(&env);
    let merchant = MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "Cafe Don Aurelio"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 4_598_000,
        lng_e6: -74_075_000,
        category: Symbol::new(&env, "cafe"),
    };
    client.register_merchant(&merchant);

    let list = client.list_merchants(&bid);
    assert_eq!(list.len(), 1);
    assert_eq!(list.get(0).unwrap().address, merchant_addr);
    assert_eq!(list.get(0).unwrap().lat_e6, 4_598_000);
}

#[test]
fn test_pay_merchant_splits_correctly() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, usdc_addr, usdc_admin, _adapter, _blend) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "Centro Historico"), &treasury);

    let merchant_addr = Address::generate(&env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "Cafe"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(&env, "cafe"),
    });

    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);

    // 42_000_000 stroops (4.2 USDC) con tip 2% (200 bps)
    // tip = 840_000 | fee = 210_000 | al comercio = 41_790_000
    let amount = 42_000_000i128;
    client.pay_merchant(&tourist, &merchant_addr, &amount, &200u32);

    assert_eq!(usdc.balance(&merchant_addr), 41_790_000);
    let barrio = client.get_barrio(&bid);
    assert_eq!(barrio.pool_balance, 840_000);
    assert_eq!(barrio.tx_count, 1);
    assert_eq!(barrio.unique_tourists, 1);
    assert_eq!(usdc.balance(&client.address), 840_000);
}

#[test]
fn test_unique_tourists_counts_once() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);

    let merchant_addr = Address::generate(&env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "M"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(&env, "cafe"),
    });

    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);

    client.pay_merchant(&tourist, &merchant_addr, &10_000_000i128, &200u32);
    client.pay_merchant(&tourist, &merchant_addr, &10_000_000i128, &200u32);

    let barrio = client.get_barrio(&bid);
    assert_eq!(barrio.tx_count, 2);
    assert_eq!(barrio.unique_tourists, 1);
}

#[test]
fn test_withdraw_only_by_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, usdc_addr, usdc_admin, _adapter, _blend) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);

    let merchant_addr = Address::generate(&env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "M"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(&env, "cafe"),
    });

    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);
    client.pay_merchant(&tourist, &merchant_addr, &100_000_000i128, &200u32);

    let recipient = Address::generate(&env);
    client.withdraw_to(&treasury, &bid, &recipient, &1_000_000i128);

    assert_eq!(usdc.balance(&recipient), 1_000_000);
    assert_eq!(client.get_pool_balance(&bid), 1_000_000);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_withdraw_rejects_non_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);

    let merchant_addr = Address::generate(&env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "M"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(&env, "cafe"),
    });
    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);
    client.pay_merchant(&tourist, &merchant_addr, &100_000_000i128, &200u32);

    let attacker = Address::generate(&env);
    let recipient = Address::generate(&env);
    client.withdraw_to(&attacker, &bid, &recipient, &1_000_000i128);
}

#[test]
fn test_payment_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);
    let merchant_addr = Address::generate(&env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "M"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(&env, "cafe"),
    });
    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);
    client.pay_merchant(&tourist, &merchant_addr, &10_000_000i128, &200u32);

    let events = env.events().all();
    assert!(!events.events().is_empty());
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests del yield sobre fondos ociosos (F1: Pool -> yield_adapter -> Blend)
// ─────────────────────────────────────────────────────────────────────────────

/// Helper: prepara barrio con saldo en el pool vía un pago con tip.
fn setup_barrio_with_balance<'a>(
    env: &'a Env,
    client: &PoolContractClient<'a>,
    usdc_admin: &token::StellarAssetClient<'a>,
) -> (BytesN<32>, Address, i128) {
    let treasury = Address::generate(env);
    let bid = barrio_id(env);
    client.register_barrio(&bid, &String::from_str(env, "Centro"), &treasury);

    let merchant_addr = Address::generate(env);
    client.register_merchant(&MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(env, "Tienda"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 0,
        lng_e6: 0,
        category: Symbol::new(env, "tienda"),
    });

    let tourist = Address::generate(env);
    // Funde con 1000 USDC. Pago 100 USDC con tip 10% → 10 USDC (100_000_000) al pool.
    usdc_admin.mint(&tourist, &10_000_000_000i128);
    client.pay_merchant(&tourist, &merchant_addr, &1_000_000_000i128, &1_000u32); // 10% tip

    let balance = client.get_pool_balance(&bid);
    // tip = 1_000_000_000 * 1_000 / 10_000 = 100_000_000
    assert_eq!(balance, 100_000_000);
    (bid, treasury, balance)
}

#[test]
fn test_deposit_to_vault_reduces_pool_balance_and_tracks_shares() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, adapter_addr, blend_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let (bid, _treasury, pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    assert_eq!(pool_initial, 100_000_000);

    // Deposita la mitad (dentro del colchón por defecto de 20%: remaining=50% >= 20%).
    let to_deposit = 50_000_000i128;
    client.deposit_idle_to_vault(&admin, &bid, &to_deposit);

    // Pool balance baja por `to_deposit`
    assert_eq!(client.get_pool_balance(&bid), 50_000_000);

    // El adapter tiene shares (1:1 al b_rate inicial de Blend)
    let shares = client.get_vault_shares(&bid);
    assert_eq!(shares, 50_000_000);

    // El USDC termina custodiado por el pool de Blend (mock), NO por el adapter
    // (el adapter solo lo retiene transitoriamente entre transfer y submit).
    assert_eq!(usdc.balance(&blend_addr), 50_000_000);
    assert_eq!(usdc.balance(&adapter_addr), 0);

    // Pool (contrato) todavía custodia el otro 50%
    assert_eq!(usdc.balance(&client.address), 50_000_000);

    // get_vault_value refleja 1:1
    assert_eq!(client.get_vault_value(&bid), 50_000_000);
}

#[test]
fn test_redeem_from_vault_restores_pool_balance() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Deposita 80% (justo en el borde del colchón de 20%: remaining=20% == mínimo, permitido).
    let to_deposit = 80_000_000i128;
    client.deposit_idle_to_vault(&admin, &bid, &to_deposit);
    assert_eq!(client.get_pool_balance(&bid), 20_000_000);

    // Rescata todas las shares
    let shares = client.get_vault_shares(&bid);
    client.redeem_from_vault(&admin, &bid, &shares);

    // Pool recupera el USDC (1:1 ya que b_rate no cambió)
    assert_eq!(client.get_pool_balance(&bid), 100_000_000);
    assert_eq!(client.get_vault_shares(&bid), 0);

    // Pool contrato custodia todo el USDC de nuevo
    assert_eq!(usdc.balance(&client.address), 100_000_000);
}

#[test]
fn test_get_vault_value_reflects_yield() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, _adapter_addr, blend_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);
    let blend_client = MockBlendPoolClient::new(&env, &blend_addr);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Deposita 50 USDC al adapter (dentro del colchón).
    client.deposit_idle_to_vault(&admin, &bid, &50_000_000i128);
    let shares = client.get_vault_shares(&bid);
    assert_eq!(shares, 50_000_000); // 1:1 al b_rate inicial

    // Simula yield del 10%: b_rate sube a 1.1e12 y el pool de Blend necesita
    // el USDC extra para poder pagarlo al retirar.
    blend_client.set_b_rate(&1_100_000_000_000i128);
    usdc_admin.mint(&blend_addr, &5_000_000i128); // 5 USDC de yield simulado

    // get_vault_value debe reflejar el nuevo b_rate
    let vault_value = client.get_vault_value(&bid);
    // 50_000_000 shares * 1.1e12 / 1e12 = 55_000_000
    assert_eq!(vault_value, 55_000_000);

    // Rescata y verifica que pool_balance sube por el yield
    client.redeem_from_vault(&admin, &bid, &shares);
    // pool_balance = 50_000_000 (idle) + 55_000_000 (rescate con yield) = 105_000_000
    assert_eq!(client.get_pool_balance(&bid), 105_000_000);

    // El pool de Blend ya no tiene USDC de este barrio
    assert_eq!(usdc.balance(&blend_addr), 0);
}

#[test]
fn test_deposit_by_treasury_also_allowed() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // El treasury puede depositar al adapter, no solo el admin
    client.deposit_idle_to_vault(&treasury, &bid, &30_000_000i128);
    assert_eq!(client.get_pool_balance(&bid), 70_000_000);
    assert_eq!(client.get_vault_shares(&bid), 30_000_000);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_deposit_rejects_unauthorized_caller() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    let attacker = Address::generate(&env);
    client.deposit_idle_to_vault(&attacker, &bid, &10_000_000i128);
}

#[test]
#[should_panic(expected = "Error(Contract, #7)")] // InvalidAmount
fn test_deposit_rejects_amount_exceeding_balance() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, pool_balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Intenta depositar más de lo que hay en el pool
    client.deposit_idle_to_vault(&admin, &bid, &(pool_balance + 1));
}

#[test]
fn test_vault_shares_zero_before_deposit() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);

    assert_eq!(client.get_vault_shares(&bid), 0);
    assert_eq!(client.get_vault_value(&bid), 0);
}

/// Nota: en soroban-sdk, `env.events().all()` devuelve solo los eventos de la
/// ÚLTIMA invocación top-level (se resetean entre llamadas). Por eso verificamos
/// events después de CADA llamada por separado.
#[test]
fn test_vault_deposit_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter, _blend) = setup(&env);

    let (bid, _treasury, _balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Llama deposit y comprueba eventos de esa invocación
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);
    let events = env.events().all();
    // Mínimo: vault_dep del pool + transfers del SAC + eventos del adapter
    assert!(!events.events().is_empty());
    assert!(events.events().len() >= 2);
}

#[test]
fn test_vault_redeem_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter, _blend) = setup(&env);

    let (bid, _treasury, _balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);

    let shares = client.get_vault_shares(&bid);
    // Llama redeem y comprueba eventos de esa invocación
    client.redeem_from_vault(&admin, &bid, &shares);
    let events = env.events().all();
    // Mínimo: vault_red del pool + transfer del SAC + evento del adapter
    assert!(!events.events().is_empty());
    assert!(events.events().len() >= 2);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests del colchón líquido (CushionBps, F1)
// ─────────────────────────────────────────────────────────────────────────────

#[test]
#[should_panic(expected = "Error(Contract, #10)")] // InsufficientLiquidity
fn test_deposit_idle_to_vault_rejects_when_cushion_violated() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Total = 100_000_000. Depositar 81_000_000 deja 19_000_000 líquidos
    // (19% < 20% de colchón por defecto) — debe rechazarse.
    client.deposit_idle_to_vault(&admin, &bid, &81_000_000i128);
}

#[test]
fn test_deposit_idle_to_vault_exactly_at_cushion_boundary_ok() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Depositar 80_000_000 deja exactamente 20_000_000 (20%) — el borde
    // permitido (`>=`), no debe fallar.
    client.deposit_idle_to_vault(&admin, &bid, &80_000_000i128);
    assert_eq!(client.get_pool_balance(&bid), 20_000_000);
}

#[test]
fn test_set_cushion_bps_changes_threshold() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Con colchón 0, se puede depositar el 100% del fondo.
    client.set_cushion_bps(&admin, &0u32);
    client.deposit_idle_to_vault(&admin, &bid, &100_000_000i128);
    assert_eq!(client.get_pool_balance(&bid), 0);
    assert_eq!(client.get_vault_shares(&bid), 100_000_000);
}

#[test]
#[should_panic(expected = "Error(Contract, #12)")] // InvalidBps
fn test_set_cushion_bps_rejects_over_10000() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, _usdc_admin, _adapter_addr, _blend_addr) = setup(&env);
    client.set_cushion_bps(&admin, &10_001u32);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_set_cushion_bps_rejects_non_admin() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, _usdc_admin, _adapter_addr, _blend_addr) = setup(&env);
    let attacker = Address::generate(&env);
    client.set_cushion_bps(&attacker, &1_000u32);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests de set_yield_adapter (F1)
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_set_yield_adapter_allowed_when_no_positions() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, _usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    // Sin ningún depósito previo (total_shares del adapter actual == 0):
    // migrar a un nuevo adapter es libre.
    let new_blend_addr = env.register(MockBlendPool, ());
    let new_adapter_addr = env.register(BlendAdapter, ());
    let new_adapter_client = BlendAdapterClient::new(&env, &new_adapter_addr);
    new_adapter_client.initialize(&admin, &client.address, &new_blend_addr, &usdc_addr);

    client.set_yield_adapter(&admin, &new_adapter_addr);
}

#[test]
#[should_panic(expected = "Error(Contract, #11)")] // AdapterHasPositions
fn test_set_yield_adapter_blocked_with_active_positions() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);

    // El adapter actual tiene posiciones activas (total_shares > 0) — migrar
    // sin rescatar antes debe fallar.
    let new_blend_addr = env.register(MockBlendPool, ());
    let new_adapter_addr = env.register(BlendAdapter, ());
    let new_adapter_client = BlendAdapterClient::new(&env, &new_adapter_addr);
    new_adapter_client.initialize(&admin, &client.address, &new_blend_addr, &usdc_addr);

    client.set_yield_adapter(&admin, &new_adapter_addr);
}

#[test]
fn test_set_yield_adapter_allowed_after_full_redeem() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);
    let shares = client.get_vault_shares(&bid);
    client.redeem_from_vault(&admin, &bid, &shares);
    assert_eq!(client.get_vault_shares(&bid), 0);

    // Tras rescatar todo (total_shares == 0), migrar debe funcionar.
    let new_blend_addr = env.register(MockBlendPool, ());
    let new_adapter_addr = env.register(BlendAdapter, ());
    let new_adapter_client = BlendAdapterClient::new(&env, &new_adapter_addr);
    new_adapter_client.initialize(&admin, &client.address, &new_blend_addr, &usdc_addr);

    client.set_yield_adapter(&admin, &new_adapter_addr);
}

// ─────────────────────────────────────────────────────────────────────────────
// Test crítico: InsufficientShares cross-barrio a través de Pool (F1 corrige
// el bug pre-F1 donde VaultShares podía quedar negativo).
// ─────────────────────────────────────────────────────────────────────────────

#[test]
#[should_panic(expected = "Error(Contract, #5)")] // InsufficientShares (del adapter)
fn test_redeem_from_vault_rejects_cross_barrio_raid_through_pool() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _adapter_addr, _blend_addr) = setup(&env);

    // Barrio A deposita; Barrio B (sin fondos invertidos) intenta rescatar
    // shares que en realidad pertenecen a A. El adapter debe rechazarlo
    // aunque la posición conjunta en Blend sí tenga fondos suficientes.
    let (bid_a, _treasury_a, _bal_a) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    client.deposit_idle_to_vault(&admin, &bid_a, &50_000_000i128);

    let treasury_b = Address::generate(&env);
    let bid_b = barrio_id(&env);
    client.register_barrio(&bid_b, &String::from_str(&env, "Otro Barrio"), &treasury_b);
    assert_eq!(client.get_vault_shares(&bid_b), 0);

    client.redeem_from_vault(&admin, &bid_b, &10_000_000i128);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests del índice global de barrios (DataKey::AllBarrios / list_barrios)
// ─────────────────────────────────────────────────────────────────────────────

/// Sin registrar ningún barrio, list_barrios debe devolver un Vec vacío.
#[test]
fn test_list_barrios_empty_before_register() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let barrios = client.list_barrios();
    assert_eq!(barrios.len(), 0);
}

/// Al registrar N barrios, list_barrios los devuelve todos en orden de inserción.
#[test]
fn test_list_barrios_returns_all_in_order() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let bid1 = barrio_id(&env);
    let bid2 = barrio_id(&env);
    let bid3 = barrio_id(&env);
    let treasury = Address::generate(&env);

    client.register_barrio(&bid1, &String::from_str(&env, "Centro"), &treasury);
    client.register_barrio(&bid2, &String::from_str(&env, "Candelaria"), &treasury);
    client.register_barrio(&bid3, &String::from_str(&env, "Usaquen"), &treasury);

    let barrios = client.list_barrios();
    assert_eq!(barrios.len(), 3);
    assert_eq!(barrios.get(0).unwrap(), bid1);
    assert_eq!(barrios.get(1).unwrap(), bid2);
    assert_eq!(barrios.get(2).unwrap(), bid3);
}

/// Re-registrar el mismo barrio_id (sobreescritura de datos) no debe duplicarlo en el índice.
#[test]
fn test_list_barrios_no_duplicates_on_re_register() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _adapter, _blend) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);

    // Primer registro
    client.register_barrio(&bid, &String::from_str(&env, "Centro"), &treasury);
    // Re-registro del mismo id (sobreescribe nombre)
    client.register_barrio(&bid, &String::from_str(&env, "Centro v2"), &treasury);

    // El índice no debe tener duplicados
    let barrios = client.list_barrios();
    assert_eq!(barrios.len(), 1);
    assert_eq!(barrios.get(0).unwrap(), bid);
}
