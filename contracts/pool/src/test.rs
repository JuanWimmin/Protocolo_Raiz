#![cfg(test)]

//! Tests del contrato Pool.
//! Cubre: inicialización, registro de barrio/comercio, pago con tip,
//! actualización de estadísticas, retiro autorizado por el treasury,
//! y el flujo completo de vault DeFindex (deposit/redeem/yield).
//!
//! Cross-contract a Rewards: el `setup()` registra el contrato Rewards real
//! (crate `rewards`, declarado como dev-dependency) en el env de test y le
//! pasa esa Address al `initialize` del Pool. Así `pay_merchant` puede llamar
//! `accrue_points` sin panic.
//!
//! Cross-contract al vault: se usa `MockVault` definido aquí abajo. El mock
//! hace el `usdc.transfer(from, vault, amount)` real en deposit para ejercitar
//! la ruta de auth. Con `mock_all_auths()` ese auth se bypasea (limitación
//! de test conocida — el `authorize_as_current_contract` es correcto para prod).

extern crate std;

use super::*;
use rewards::{RewardsContract, RewardsContractClient};
use soroban_sdk::{
    contract, contractimpl, contracttype,
    testutils::{Address as _, BytesN as _, Events},
    token, Address, BytesN, Env, String, Symbol, Val, Vec,
};

// ─────────────────────────────────────────────────────────────────────────────
// Mock del vault DeFindex
// ─────────────────────────────────────────────────────────────────────────────
//
// Simula el comportamiento del vault de DeFindex para tests:
//  - deposit: hace usdc.transfer(from, vault, amount) y mintea shares 1:1
//  - withdraw: quema shares y devuelve USDC a `from` al precio configurado
//  - set_price: permite simular yield (price=11_000 => 1 share = 1.1 USDC)
//  - balance / get_asset_amounts_per_shares: lecturas
//
// Las interfaces deben coincidir con lo que Pool espera (DefindexVaultClient).

#[contracttype]
#[derive(Clone)]
pub enum MVKey {
    Usdc,
    Shares(Address),
    // Precio en basis points de 10_000: 10_000 = 1:1, 11_000 = 1.1:1
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

    /// Cambia el precio de shares (para simular yield en tests).
    /// price = 11_000 significa que cada share vale 1.1 USDC (10% de yield).
    pub fn set_price(env: Env, price: i128) {
        env.storage().instance().set(&MVKey::Price, &price);
    }

    /// Implementa la interfaz deposit del vault DeFindex.
    /// Hace usdc.transfer(from, vault, amount) y mintea shares 1:1 al precio actual.
    /// Retorna (amounts_depositados, shares_minteadas, Val) para coincidir con
    /// DefindexVaultClient (3er elemento = Val comodín, como el vault real en testnet
    /// que devuelve [null] → ScVal::Vec([ScVal::Void]) con invest=true).
    pub fn deposit(
        env: Env,
        amounts_desired: Vec<i128>,
        amounts_min: Vec<i128>,
        from: Address,
        invest: bool,
    ) -> (Vec<i128>, i128, Val) {
        // Suprime warnings de args no usados en el mock
        let _ = amounts_min;
        let _ = invest;

        from.require_auth();
        let amount = amounts_desired.get(0).unwrap_or(0);
        let usdc_addr: Address = env.storage().instance().get(&MVKey::Usdc).unwrap();
        let usdc = token::Client::new(&env, &usdc_addr);
        let vault = env.current_contract_address();

        // Esta es la sub-llamada que Pool pre-autoriza con authorize_as_current_contract.
        // Con mock_all_auths() en tests, se bypasea. En producción el pool_addr.require_auth()
        // aquí sería satisfecho por el InvokerContractAuthEntry del Pool.
        usdc.transfer(&from, &vault, &amount);

        // Mintea shares 1:1 con el amount (independiente del precio)
        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let shares = amount * 10_000 / price; // Inversión: shares = amount / price
        let key = MVKey::Shares(from.clone());
        let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
        env.storage().persistent().set(&key, &(current + shares));

        let mut amounts_out = Vec::new(&env);
        amounts_out.push_back(amount);
        // Devuelve Val::VOID como tercer elemento (ignorado por Pool).
        // En el vault DeFindex real devuelve ScVal::Vec([ScVal::Void]) con invest=true,
        // que Val también puede contener sin problema (identidad).
        (amounts_out, shares, Val::VOID.into())
    }

    /// Implementa la interfaz withdraw del vault DeFindex.
    /// Quema shares y transfiere `shares * price / 10_000` USDC de vuelta a `from`.
    pub fn withdraw(
        env: Env,
        withdraw_shares: i128,
        min_amounts_out: Vec<i128>,
        from: Address,
    ) -> Vec<i128> {
        let _ = min_amounts_out;
        from.require_auth();

        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let amount = withdraw_shares * price / 10_000;

        let usdc_addr: Address = env.storage().instance().get(&MVKey::Usdc).unwrap();
        let usdc = token::Client::new(&env, &usdc_addr);
        let vault = env.current_contract_address();

        // El vault transfiere sus propios fondos al recipient.
        // Pool NO necesita pre-autorizar esto (el vault es el sender).
        usdc.transfer(&vault, &from, &amount);

        let key = MVKey::Shares(from.clone());
        let current: i128 = env.storage().persistent().get(&key).unwrap_or(0);
        env.storage().persistent().set(&key, &(current - withdraw_shares));

        let mut out = Vec::new(&env);
        out.push_back(amount);
        out
    }

    /// Shares que tiene `id` en este vault.
    pub fn balance(env: Env, id: Address) -> i128 {
        env.storage().persistent().get(&MVKey::Shares(id)).unwrap_or(0)
    }

    /// Valor USDC de `vault_shares` shares al precio actual.
    pub fn get_asset_amounts_per_shares(env: Env, vault_shares: i128) -> Vec<i128> {
        let price: i128 = env.storage().instance().get(&MVKey::Price).unwrap_or(10_000);
        let value = vault_shares * price / 10_000;
        let mut out = Vec::new(&env);
        out.push_back(value);
        out
    }
}

// Necesitamos el Client generado por el contractimpl del MockVault para tests
use MockVaultClient as MockVaultContractClient;

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
///   - Registra MockVault, Rewards, Pool.
///   - Inicializa Rewards apuntando al Pool.
///   - Inicializa Pool con vault address incluido (nuevo parámetro).
fn setup<'a>(
    env: &'a Env,
) -> (
    PoolContractClient<'a>,
    Address, // admin
    Address, // usdc address
    token::StellarAssetClient<'a>,
    Address, // vault address
) {
    let admin = Address::generate(env);
    let (usdc_addr, usdc_admin) = create_usdc(env, &admin);

    // Registra MockVault
    let vault_addr = env.register(MockVault, ());
    let vault_client = MockVaultContractClient::new(env, &vault_addr);

    // Registra Rewards y Pool
    let rewards_addr = env.register(RewardsContract, ());
    let contract_id = env.register(PoolContract, ());
    let client = PoolContractClient::new(env, &contract_id);
    let rewards = RewardsContractClient::new(env, &rewards_addr);

    env.mock_all_auths();

    // Inicializa MockVault con la dirección USDC
    vault_client.initialize(&usdc_addr);

    // Inicializa Rewards apuntando al Pool para que verifique el caller en accrue_points
    rewards.initialize(&admin, &contract_id);

    // Inicializa Pool con el nuevo parámetro defindex_vault
    client.initialize(&admin, &usdc_addr, &rewards_addr, &50u32, &vault_addr);

    (client, admin, usdc_addr, usdc_admin, vault_addr)
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests existentes (sin cambios funcionales, solo actualizado setup)
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_and_register_barrio() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

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
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

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
    let (client, _admin, usdc_addr, usdc_admin, _vault) = setup(&env);
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
    let (client, _admin, _usdc_addr, usdc_admin, _vault) = setup(&env);

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
    let (client, _admin, usdc_addr, usdc_admin, _vault) = setup(&env);
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
    let (client, _admin, _usdc, usdc_admin, _vault) = setup(&env);

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
    let (client, _admin, _usdc, usdc_admin, _vault) = setup(&env);

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
    assert!(!events.is_empty());
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests del vault DeFindex
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
    let (client, admin, usdc_addr, usdc_admin, vault_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let (bid, _treasury, pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    assert_eq!(pool_initial, 100_000_000);

    // Deposita la mitad al vault
    let to_deposit = 50_000_000i128;
    client.deposit_idle_to_vault(&admin, &bid, &to_deposit);

    // Pool balance baja por `to_deposit`
    assert_eq!(client.get_pool_balance(&bid), 50_000_000);

    // Vault tiene shares (1:1 al precio inicial)
    let shares = client.get_vault_shares(&bid);
    assert_eq!(shares, 50_000_000);

    // El vault tiene el USDC
    assert_eq!(usdc.balance(&vault_addr), 50_000_000);

    // Pool (contrato) todavía custodia el otro 50%
    assert_eq!(usdc.balance(&client.address), 50_000_000);

    // get_vault_value refleja 1:1
    assert_eq!(client.get_vault_value(&bid), 50_000_000);
}

#[test]
fn test_redeem_from_vault_restores_pool_balance() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, _vault_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    let to_deposit = 80_000_000i128;
    client.deposit_idle_to_vault(&admin, &bid, &to_deposit);
    assert_eq!(client.get_pool_balance(&bid), 20_000_000);

    // Rescata todas las shares
    let shares = client.get_vault_shares(&bid);
    client.redeem_from_vault(&admin, &bid, &shares);

    // Pool recupera el USDC (1:1 ya que price=10_000)
    assert_eq!(client.get_pool_balance(&bid), 100_000_000);
    assert_eq!(client.get_vault_shares(&bid), 0);

    // Pool contrato custodia todo el USDC de nuevo
    assert_eq!(usdc.balance(&client.address), 100_000_000);
}

#[test]
fn test_get_vault_value_reflects_yield() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, usdc_addr, usdc_admin, vault_addr) = setup(&env);
    let usdc = token::Client::new(&env, &usdc_addr);
    let vault_client = MockVaultContractClient::new(&env, &vault_addr);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Deposita 50 USDC al vault
    client.deposit_idle_to_vault(&admin, &bid, &50_000_000i128);
    let shares = client.get_vault_shares(&bid);
    assert_eq!(shares, 50_000_000); // 1:1 al precio default

    // Simula yield del 10%: precio sube a 11_000 (1.1 USDC por share)
    // y mint USDC extra al vault para cubrir el yield
    vault_client.set_price(&11_000i128);
    usdc_admin.mint(&vault_addr, &5_000_000i128); // 5 USDC de yield simulado

    // get_vault_value debe reflejar el nuevo precio
    let vault_value = client.get_vault_value(&bid);
    // 50_000_000 shares * 11_000 / 10_000 = 55_000_000
    assert_eq!(vault_value, 55_000_000);

    // Rescata y verifica que pool_balance sube por el yield
    client.redeem_from_vault(&admin, &bid, &shares);
    // pool_balance = 50_000_000 (idle) + 55_000_000 (rescate con yield)
    assert_eq!(client.get_pool_balance(&bid), 105_000_000);

    // Vault ya no tiene USDC
    assert_eq!(usdc.balance(&vault_addr), 0);
}

#[test]
fn test_deposit_by_treasury_also_allowed() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin, _vault_addr) = setup(&env);

    let (bid, treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // El treasury puede depositar al vault, no solo el admin
    client.deposit_idle_to_vault(&treasury, &bid, &30_000_000i128);
    assert_eq!(client.get_pool_balance(&bid), 70_000_000);
    assert_eq!(client.get_vault_shares(&bid), 30_000_000);
}

#[test]
#[should_panic(expected = "Error(Contract, #3)")] // Unauthorized
fn test_deposit_rejects_unauthorized_caller() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc_addr, usdc_admin, _vault_addr) = setup(&env);

    let (bid, _treasury, _pool_initial) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    let attacker = Address::generate(&env);
    client.deposit_idle_to_vault(&attacker, &bid, &10_000_000i128);
}

#[test]
#[should_panic(expected = "Error(Contract, #7)")] // InvalidAmount
fn test_deposit_rejects_amount_exceeding_balance() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _vault_addr) = setup(&env);

    let (bid, _treasury, pool_balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Intenta depositar más de lo que hay en el pool
    client.deposit_idle_to_vault(&admin, &bid, &(pool_balance + 1));
}

#[test]
fn test_vault_shares_zero_before_deposit() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

    let bid = barrio_id(&env);
    let treasury = Address::generate(&env);
    client.register_barrio(&bid, &String::from_str(&env, "B"), &treasury);

    assert_eq!(client.get_vault_shares(&bid), 0);
    assert_eq!(client.get_vault_value(&bid), 0);
}

/// Nota: en soroban-sdk v22, `env.events().all()` devuelve solo los eventos de la
/// ÚLTIMA invocación top-level (se resetean entre llamadas). Por eso verificamos
/// events después de CADA llamada por separado.
#[test]
fn test_vault_deposit_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _vault) = setup(&env);

    let (bid, _treasury, _balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);

    // Llama deposit y comprueba eventos de esa invocación
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);
    let events = env.events().all();
    // Mínimo: vault_dep del pool + transfer del SAC (llamado por MockVault.deposit)
    assert!(!events.is_empty());
    // Hay al menos 2 (SAC transfer + vault_dep)
    assert!(events.len() >= 2);
}

#[test]
fn test_vault_redeem_emits_event() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, admin, _usdc_addr, usdc_admin, _vault) = setup(&env);

    let (bid, _treasury, _balance) = setup_barrio_with_balance(&env, &client, &usdc_admin);
    client.deposit_idle_to_vault(&admin, &bid, &10_000_000i128);

    let shares = client.get_vault_shares(&bid);
    // Llama redeem y comprueba eventos de esa invocación
    client.redeem_from_vault(&admin, &bid, &shares);
    let events = env.events().all();
    // Mínimo: vault_red del pool + transfer del SAC (llamado por MockVault.withdraw)
    assert!(!events.is_empty());
    assert!(events.len() >= 2);
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests del índice global de barrios (DataKey::AllBarrios / list_barrios)
// ─────────────────────────────────────────────────────────────────────────────

/// Sin registrar ningún barrio, list_barrios debe devolver un Vec vacío.
#[test]
fn test_list_barrios_empty_before_register() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

    let barrios = client.list_barrios();
    assert_eq!(barrios.len(), 0);
}

/// Al registrar N barrios, list_barrios los devuelve todos en orden de inserción.
#[test]
fn test_list_barrios_returns_all_in_order() {
    let env = Env::default();
    env.mock_all_auths();
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

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
    let (client, _admin, _usdc, _usdc_admin, _vault) = setup(&env);

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
