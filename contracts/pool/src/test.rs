#![cfg(test)]

//! Tests del contrato Pool.
//! Cubre: inicialización, registro de barrio/comercio, pago con tip,
//! actualización de estadísticas, y retiro autorizado por el treasury.
//!
//! Cross-contract a Rewards: el `setup()` registra el contrato Rewards real
//! (crate `rewards`, declarado como dev-dependency) en el env de test y le
//! pasa esa Address al `initialize` del Pool. Así `pay_merchant` puede llamar
//! `accrue_points` sin panic.

extern crate std;

use super::*;
use rewards::{RewardsContract, RewardsContractClient};
use soroban_sdk::{
    testutils::{Address as _, BytesN as _, Events},
    token, Address, BytesN, Env, String, Symbol,
};

// Helper: despliega un token USDC de prueba (Stellar Asset Contract) y devuelve
// su cliente admin para mintear saldos.
fn create_usdc<'a>(env: &Env, admin: &Address) -> (Address, token::StellarAssetClient<'a>) {
    let sac = env.register_stellar_asset_contract_v2(admin.clone());
    let addr = sac.address();
    let admin_client = token::StellarAssetClient::new(env, &addr);
    (addr, admin_client)
}

// Helper de setup: registra el contrato Pool y el contrato Rewards real
// (no un placeholder) para que el cross-contract call funcione.
fn setup<'a>(
    env: &Env,
) -> (
    PoolContractClient<'a>,
    Address, // admin
    Address, // usdc address
    token::StellarAssetClient<'a>,
) {
    let admin = Address::generate(env);
    let (usdc_addr, usdc_admin) = create_usdc(env, &admin);

    // Registra Rewards y Pool. Inicializa Rewards apuntando al Pool para que
    // verifique correctamente al caller en accrue_points.
    let rewards_addr = env.register(RewardsContract, ());
    let contract_id = env.register(PoolContract, ());
    let client = PoolContractClient::new(env, &contract_id);
    let rewards = RewardsContractClient::new(env, &rewards_addr);

    env.mock_all_auths();
    rewards.initialize(&admin, &contract_id);
    client.initialize(&admin, &usdc_addr, &rewards_addr, &50u32); // fee 0.5%
    (client, admin, usdc_addr, usdc_admin)
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

#[test]
fn test_initialize_and_register_barrio() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin) = setup(&env);

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
    let (client, _admin, _usdc, _usdc_admin) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "Centro Historico"), &treasury);

    let merchant_addr = Address::generate(&env);
    let merchant = MerchantData {
        address: merchant_addr.clone(),
        name: String::from_str(&env, "Cafe Don Aurelio"),
        barrio_id: bid.clone(),
        verified: true,
        lat_e6: 4_598_000,   // ~4.598 lat
        lng_e6: -74_075_000, // ~-74.075 lng (Bogotá)
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
    let (client, _admin, usdc_addr, usdc_admin) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    // Setup barrio + comercio
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

    // Funde al turista con 100 USDC (1_000_000_000 stroops)
    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);

    // Paga 42_000_000 stroops (4.2 USDC) con tip 2% (200 bps)
    // tip = 4.2 * 0.02 = 0.084 USDC = 840_000 stroops
    // fee = 4.2 * 0.005 = 0.021 USDC = 210_000 stroops
    // al comercio = 4.2 - fee = 4.179 USDC = 41_790_000 stroops
    let amount = 42_000_000i128;
    client.pay_merchant(&tourist, &merchant_addr, &amount, &200u32);

    // Verifica balances
    assert_eq!(usdc.balance(&merchant_addr), 41_790_000); // comercio
    let barrio = client.get_barrio(&bid);
    assert_eq!(barrio.pool_balance, 840_000); // tip al pool
    assert_eq!(barrio.tx_count, 1);
    assert_eq!(barrio.unique_tourists, 1);

    // El pool (contrato) custodia el tip
    assert_eq!(usdc.balance(&client.address), 840_000);
}

#[test]
fn test_unique_tourists_counts_once() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin) = setup(&env);

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

    // Mismo turista paga dos veces
    client.pay_merchant(&tourist, &merchant_addr, &10_000_000i128, &200u32);
    client.pay_merchant(&tourist, &merchant_addr, &10_000_000i128, &200u32);

    let barrio = client.get_barrio(&bid);
    assert_eq!(barrio.tx_count, 2);
    assert_eq!(barrio.unique_tourists, 1); // solo cuenta una vez
}

#[test]
fn test_withdraw_only_by_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, usdc_addr, usdc_admin) = setup(&env);
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

    // Genera saldo en el pool vía un pago con tip
    let tourist = Address::generate(&env);
    usdc_admin.mint(&tourist, &1_000_000_000i128);
    // 100_000_000 stroops = 10 USDC. tip = 10 * 2% = 0.2 USDC = 2_000_000 stroops en el pool.
    client.pay_merchant(&tourist, &merchant_addr, &100_000_000i128, &200u32);

    let recipient = Address::generate(&env);
    // El treasury retira 0.1 USDC (1_000_000 stroops) al beneficiario.
    client.withdraw_to(&treasury, &bid, &recipient, &1_000_000i128);

    assert_eq!(usdc.balance(&recipient), 1_000_000);
    assert_eq!(client.get_pool_balance(&bid), 1_000_000); // 2M - 1M
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_withdraw_rejects_non_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, usdc_admin) = setup(&env);

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

    // Un atacante (no treasury) intenta retirar -> debe fallar
    let attacker = Address::generate(&env);
    let recipient = Address::generate(&env);
    client.withdraw_to(&attacker, &bid, &recipient, &1_000_000i128);
}

#[test]
fn test_payment_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, usdc_admin) = setup(&env);

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

    // Debe haber al menos un evento de pago
    let events = env.events().all();
    assert!(!events.is_empty());
}
