#![cfg(test)]

//! Tests del contrato Rewards.
//! Cubre: initialize, register_reward, accrue_points (solo Pool puede),
//! redeem (puntos, stock, atomicidad), claim_redemption (solo artesano).

extern crate std;

use super::*;
use soroban_sdk::{
    testutils::{Address as _, BytesN as _},
    Address, BytesN, Env, String,
};

struct Ctx<'a> {
    client: RewardsContractClient<'a>,
    admin: Address,
    pool: Address,        // Address que simula al contrato Pool
}

fn setup<'a>(env: &Env) -> Ctx<'a> {
    let admin = Address::generate(env);
    // En tests, "pool" es un Address generado. En producción es el contract id real.
    // Con mock_all_auths, el require_auth de caller_pool en accrue_points
    // acepta cualquier address — lo crítico que validamos es la igualdad
    // con stored_pool.
    let pool = Address::generate(env);

    let contract_id = env.register(RewardsContract, ());
    let client = RewardsContractClient::new(env, &contract_id);

    env.mock_all_auths();
    client.initialize(&admin, &pool);

    Ctx { client, admin, pool }
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

fn make_reward<'a>(
    env: &Env,
    ctx: &Ctx<'a>,
    points_cost: u64,
    stock: u32,
) -> (u64, Address, BytesN<32>) {
    let bid = barrio_id(env);
    let artisan = Address::generate(env);
    let id = ctx.client.register_reward(
        &bid,
        &String::from_str(env, "Mochila wayuu"),
        &artisan,
        &points_cost,
        &stock,
        &String::from_str(env, "https://images.test/mochila.png"),
    );
    (id, artisan, bid)
}

// ─────────────────────────────────────────────────────────────────────────────
// Initialize y register_reward
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_idempotent() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let result = ctx.client.try_initialize(&ctx.admin, &ctx.pool);
    assert!(result.is_err());
}

#[test]
fn test_register_reward_invalid_params() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let bid = barrio_id(&env);
    let artisan = Address::generate(&env);

    // points_cost = 0
    let r1 = ctx.client.try_register_reward(
        &bid,
        &String::from_str(&env, "x"),
        &artisan,
        &0u64,
        &10u32,
        &String::from_str(&env, "url"),
    );
    assert!(r1.is_err());

    // stock = 0
    let r2 = ctx.client.try_register_reward(
        &bid,
        &String::from_str(&env, "x"),
        &artisan,
        &100u64,
        &0u32,
        &String::from_str(&env, "url"),
    );
    assert!(r2.is_err());
}

#[test]
fn test_list_rewards_per_barrio() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (_id_a, _, bid_a) = make_reward(&env, &ctx, 100, 5);
    let (_id_b, artisan_b, _) = make_reward(&env, &ctx, 200, 3);
    // Reward B en mismo barrio que A — hacemos manual con la misma bid_a
    ctx.client.register_reward(
        &bid_a,
        &String::from_str(&env, "Otro"),
        &artisan_b,
        &150u64,
        &2u32,
        &String::from_str(&env, "url"),
    );

    let list_a = ctx.client.list_rewards(&bid_a);
    // A + el segundo registrado con bid_a = 2 rewards
    assert_eq!(list_a.len(), 2);
}

// ─────────────────────────────────────────────────────────────────────────────
// accrue_points
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_accrue_points_from_pool() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let tourist = Address::generate(&env);
    // tip = 2_000_000 stroops (0.2 USDC) → 20 puntos
    ctx.client.accrue_points(&ctx.pool, &tourist, &2_000_000i128);
    assert_eq!(ctx.client.get_points(&tourist), 20);

    // Acumulación: otro tip de 5_000_000 stroops (0.5 USDC) → 50 puntos más = 70
    ctx.client.accrue_points(&ctx.pool, &tourist, &5_000_000i128);
    assert_eq!(ctx.client.get_points(&tourist), 70);
}

#[test]
fn test_accrue_points_rejects_non_pool() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let attacker = Address::generate(&env);
    let tourist = Address::generate(&env);
    let result = ctx.client.try_accrue_points(&attacker, &tourist, &2_000_000i128);
    assert!(result.is_err());
    assert_eq!(ctx.client.get_points(&tourist), 0);
}

#[test]
fn test_accrue_points_zero_tip_is_noop() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let tourist = Address::generate(&env);
    // tip = 0 no debe errar, simplemente no añade puntos
    ctx.client.accrue_points(&ctx.pool, &tourist, &0i128);
    assert_eq!(ctx.client.get_points(&tourist), 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// redeem
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_redeem_happy_path() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, _artisan, _bid) = make_reward(&env, &ctx, 100, 3);
    let tourist = Address::generate(&env);
    // Acumula 150 puntos (necesita 100)
    ctx.client.accrue_points(&ctx.pool, &tourist, &15_000_000i128);
    assert_eq!(ctx.client.get_points(&tourist), 150);

    let red_id = ctx.client.redeem(&tourist, &reward_id);
    assert_eq!(red_id, 0);

    // Puntos quemados, stock decrementado
    assert_eq!(ctx.client.get_points(&tourist), 50);
    assert_eq!(ctx.client.get_reward(&reward_id).stock, 2);

    let redemption = ctx.client.get_redemption(&red_id);
    assert_eq!(redemption.tourist, tourist);
    assert_eq!(redemption.reward_id, reward_id);
    assert!(!redemption.claimed);
}

#[test]
fn test_redeem_insufficient_points() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, _, _) = make_reward(&env, &ctx, 100, 3);
    let tourist = Address::generate(&env);
    // Solo 50 puntos, necesita 100
    ctx.client.accrue_points(&ctx.pool, &tourist, &5_000_000i128);

    let result = ctx.client.try_redeem(&tourist, &reward_id);
    assert!(result.is_err());
    // Estado no cambia
    assert_eq!(ctx.client.get_points(&tourist), 50);
    assert_eq!(ctx.client.get_reward(&reward_id).stock, 3);
}

#[test]
fn test_redeem_out_of_stock() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, _, _) = make_reward(&env, &ctx, 100, 1);
    let alice = Address::generate(&env);
    let bob = Address::generate(&env);
    ctx.client.accrue_points(&ctx.pool, &alice, &10_000_000i128);
    ctx.client.accrue_points(&ctx.pool, &bob, &10_000_000i128);

    // Alice canjea el único stock
    ctx.client.redeem(&alice, &reward_id);
    assert_eq!(ctx.client.get_reward(&reward_id).stock, 0);

    // Bob intenta canjear → out of stock
    let result = ctx.client.try_redeem(&bob, &reward_id);
    assert!(result.is_err());
    // Bob no perdió puntos
    assert_eq!(ctx.client.get_points(&bob), 100);
}

// ─────────────────────────────────────────────────────────────────────────────
// claim_redemption
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_claim_redemption_by_artisan() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, artisan, _) = make_reward(&env, &ctx, 100, 3);
    let tourist = Address::generate(&env);
    ctx.client.accrue_points(&ctx.pool, &tourist, &10_000_000i128);
    let red_id = ctx.client.redeem(&tourist, &reward_id);

    ctx.client.claim_redemption(&artisan, &red_id);
    assert!(ctx.client.get_redemption(&red_id).claimed);
}

#[test]
fn test_claim_redemption_rejects_non_artisan() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, _artisan, _) = make_reward(&env, &ctx, 100, 3);
    let tourist = Address::generate(&env);
    ctx.client.accrue_points(&ctx.pool, &tourist, &10_000_000i128);
    let red_id = ctx.client.redeem(&tourist, &reward_id);

    let attacker = Address::generate(&env);
    let result = ctx.client.try_claim_redemption(&attacker, &red_id);
    assert!(result.is_err());
    assert!(!ctx.client.get_redemption(&red_id).claimed);
}

#[test]
fn test_claim_redemption_no_double_claim() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);

    let (reward_id, artisan, _) = make_reward(&env, &ctx, 100, 3);
    let tourist = Address::generate(&env);
    ctx.client.accrue_points(&ctx.pool, &tourist, &10_000_000i128);
    let red_id = ctx.client.redeem(&tourist, &reward_id);

    ctx.client.claim_redemption(&artisan, &red_id);
    let result = ctx.client.try_claim_redemption(&artisan, &red_id);
    assert!(result.is_err());
}
