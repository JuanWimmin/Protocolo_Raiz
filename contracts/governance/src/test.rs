#![cfg(test)]

//! Tests del contrato Governance.
//! Cubre: initialize, set_barrio_admin, mint_resident (incluyendo soulbound:
//! no re-mint), create_proposal, vote (1 voto por residente), tally con y sin
//! quórum, mark_executed solo por Treasury, list_active_proposals.

extern crate std;

use super::*;
use soroban_sdk::{
    testutils::{Address as _, BytesN as _, Ledger},
    Address, BytesN, Env, String,
};

const DAY: u64 = 86_400;

struct Ctx<'a> {
    client: GovernanceContractClient<'a>,
    protocol_admin: Address,
    treasury: Address,
}

fn setup<'a>(env: &Env) -> Ctx<'a> {
    let protocol_admin = Address::generate(env);
    let treasury = Address::generate(env);

    let contract_id = env.register(GovernanceContract, ());
    let client = GovernanceContractClient::new(env, &contract_id);

    env.mock_all_auths();
    client.initialize(&protocol_admin, &treasury);

    Ctx {
        client,
        protocol_admin,
        treasury,
    }
}

fn barrio_id(env: &Env) -> BytesN<32> {
    BytesN::random(env)
}

fn setup_barrio_with_residents<'a>(
    env: &Env,
    ctx: &Ctx<'a>,
    n: u32,
) -> (BytesN<32>, Address, std::vec::Vec<Address>) {
    let bid = barrio_id(env);
    let barrio_admin = Address::generate(env);
    ctx.client.set_barrio_admin(&bid, &barrio_admin);

    let mut residents = std::vec::Vec::new();
    for _ in 0..n {
        let r = Address::generate(env);
        ctx.client.mint_resident(&barrio_admin, &r, &bid);
        residents.push(r);
    }
    (bid, barrio_admin, residents)
}

// ─────────────────────────────────────────────────────────────────────────────
// Initialize y barrio admin
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_initialize_idempotent() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    // Re-initialize debe fallar.
    let result = ctx
        .client
        .try_initialize(&ctx.protocol_admin, &ctx.treasury);
    assert!(result.is_err());
}

#[test]
fn test_set_barrio_admin_only_protocol_admin() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let bid = barrio_id(&env);
    let barrio_admin = Address::generate(&env);
    ctx.client.set_barrio_admin(&bid, &barrio_admin);
    // Contador de residentes inicia en 0
    assert_eq!(ctx.client.get_resident_count(&bid), 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// mint_resident — soulbound
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_mint_resident_ok() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);

    let token = ctx.client.get_resident(&residents[0]);
    assert_eq!(token.resident, residents[0]);
    assert_eq!(token.barrio_id, bid);
    assert_eq!(ctx.client.get_resident_count(&bid), 1);
}

#[test]
fn test_mint_resident_no_remint() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, barrio_admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);

    // Segundo mint al MISMO address debe fallar (soulbound: 1 residente = 1 voto).
    let result = ctx
        .client
        .try_mint_resident(&barrio_admin, &residents[0], &bid);
    assert!(result.is_err());
}

#[test]
fn test_mint_resident_rejects_wrong_admin() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let bid = barrio_id(&env);
    let barrio_admin = Address::generate(&env);
    ctx.client.set_barrio_admin(&bid, &barrio_admin);

    // Otro address que NO es el admin del barrio intenta mintear.
    let attacker = Address::generate(&env);
    let resident = Address::generate(&env);
    let result = ctx.client.try_mint_resident(&attacker, &resident, &bid);
    assert!(result.is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// create_proposal
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_create_proposal_by_resident() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);

    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "Pintura para la plaza"),
        &10_000_000i128,
        &recipient,
        &7u32,
    );
    assert_eq!(id, 0);

    let p = ctx.client.get_proposal(&id);
    assert_eq!(p.proposer, residents[0]);
    assert_eq!(p.amount, 10_000_000);
    assert_eq!(p.status, ProposalStatus::Active);
}

#[test]
fn test_create_proposal_non_resident_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, _residents) = setup_barrio_with_residents(&env, &ctx, 1);

    let outsider = Address::generate(&env);
    let recipient = Address::generate(&env);
    let result = ctx.client.try_create_proposal(
        &outsider,
        &bid,
        &String::from_str(&env, "Intento"),
        &10_000_000i128,
        &recipient,
        &7u32,
    );
    assert!(result.is_err());
}

#[test]
fn test_create_proposal_invalid_duration() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);
    let recipient = Address::generate(&env);

    // 2 días < mínimo (3)
    let r1 = ctx.client.try_create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &2u32,
    );
    assert!(r1.is_err());

    // 15 días > máximo (14)
    let r2 = ctx.client.try_create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &15u32,
    );
    assert!(r2.is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// vote
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_vote_increments_counters() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 3);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &7u32,
    );

    ctx.client.vote(&residents[0], &id, &true);
    ctx.client.vote(&residents[1], &id, &true);
    ctx.client.vote(&residents[2], &id, &false);

    let p = ctx.client.get_proposal(&id);
    assert_eq!(p.votes_for, 2);
    assert_eq!(p.votes_against, 1);
}

#[test]
fn test_no_double_vote() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &7u32,
    );

    ctx.client.vote(&residents[0], &id, &true);
    let result = ctx.client.try_vote(&residents[0], &id, &false);
    assert!(result.is_err());
}

#[test]
fn test_vote_rejects_non_resident() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &7u32,
    );

    let outsider = Address::generate(&env);
    let result = ctx.client.try_vote(&outsider, &id, &true);
    assert!(result.is_err());
}

#[test]
fn test_vote_after_close_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 1);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );

    // Adelanta el ledger 4 días (más allá del cierre de 3 días)
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let result = ctx.client.try_vote(&residents[0], &id, &true);
    assert!(result.is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// tally
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_tally_passes_with_quorum_and_majority() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    // 10 residentes — quórum 30% = necesita ≥ 3 votos.
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );

    // 3 votos a favor, 1 en contra → quórum 4/10 = 40% ≥ 30%, mayoría a favor.
    ctx.client.vote(&residents[0], &id, &true);
    ctx.client.vote(&residents[1], &id, &true);
    ctx.client.vote(&residents[2], &id, &true);
    ctx.client.vote(&residents[3], &id, &false);

    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let status = ctx.client.tally(&id);
    assert_eq!(status, ProposalStatus::Passed);
}

#[test]
fn test_tally_rejects_without_quorum() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    // 10 residentes — quórum 30% = necesita ≥ 3 votos.
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );

    // Solo 2 votos → 2/10 = 20% < 30%. Sin quórum.
    ctx.client.vote(&residents[0], &id, &true);
    ctx.client.vote(&residents[1], &id, &true);

    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let status = ctx.client.tally(&id);
    assert_eq!(status, ProposalStatus::Rejected);
}

#[test]
fn test_tally_rejects_with_quorum_but_majority_against() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );

    // Quórum cumplido (4/10 = 40%) pero mayoría en contra.
    ctx.client.vote(&residents[0], &id, &false);
    ctx.client.vote(&residents[1], &id, &false);
    ctx.client.vote(&residents[2], &id, &false);
    ctx.client.vote(&residents[3], &id, &true);

    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let status = ctx.client.tally(&id);
    assert_eq!(status, ProposalStatus::Rejected);
}

#[test]
fn test_tally_before_close_returns_active() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 5);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &7u32,
    );
    ctx.client.vote(&residents[0], &id, &true);

    // Antes del cierre → Active, sin modificar la propuesta.
    let status = ctx.client.tally(&id);
    assert_eq!(status, ProposalStatus::Active);

    let p = ctx.client.get_proposal(&id);
    assert_eq!(p.status, ProposalStatus::Active);
}

#[test]
fn test_tally_is_idempotent() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );
    for r in &residents[0..4] {
        ctx.client.vote(r, &id, &true);
    }

    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let s1 = ctx.client.tally(&id);
    let s2 = ctx.client.tally(&id);
    assert_eq!(s1, s2);
    assert_eq!(s1, ProposalStatus::Passed);
}

// ─────────────────────────────────────────────────────────────────────────────
// mark_executed
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_mark_executed_only_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );
    for r in &residents[0..4] {
        ctx.client.vote(r, &id, &true);
    }
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    ctx.client.tally(&id);

    // Treasury sí puede marcar
    ctx.client.mark_executed(&ctx.treasury, &id);
    let p = ctx.client.get_proposal(&id);
    assert_eq!(p.status, ProposalStatus::Executed);
}

#[test]
fn test_mark_executed_rejects_non_treasury() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );
    for r in &residents[0..4] {
        ctx.client.vote(r, &id, &true);
    }
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    ctx.client.tally(&id);

    let attacker = Address::generate(&env);
    let result = ctx.client.try_mark_executed(&attacker, &id);
    assert!(result.is_err());
}

#[test]
fn test_mark_executed_rejects_if_not_passed() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);
    let id = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "x"),
        &1i128,
        &recipient,
        &3u32,
    );
    // Sin votos suficientes → Rejected tras tally
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    let s = ctx.client.tally(&id);
    assert_eq!(s, ProposalStatus::Rejected);

    let result = ctx.client.try_mark_executed(&ctx.treasury, &id);
    assert!(result.is_err());
}

// ─────────────────────────────────────────────────────────────────────────────
// list_active_proposals
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_list_active_proposals_excludes_closed() {
    let env = Env::default();
    env.mock_all_auths();
    let ctx = setup(&env);
    let (bid, _admin, residents) = setup_barrio_with_residents(&env, &ctx, 10);
    let recipient = Address::generate(&env);

    let id_a = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "A"),
        &1i128,
        &recipient,
        &3u32,
    );
    let id_b = ctx.client.create_proposal(
        &residents[0],
        &bid,
        &String::from_str(&env, "B"),
        &1i128,
        &recipient,
        &7u32,
    );

    // Cierra A votando, adelantando el ledger y haciendo tally
    for r in &residents[0..4] {
        ctx.client.vote(r, &id_a, &true);
    }
    env.ledger().with_mut(|l| l.timestamp += 4 * DAY);
    ctx.client.tally(&id_a);

    // B aún es Active (cierra a 7 días, solo pasaron 4)
    let active = ctx.client.list_active_proposals(&bid);
    assert_eq!(active.len(), 1);
    assert_eq!(active.get(0).unwrap().id, id_b);
}
