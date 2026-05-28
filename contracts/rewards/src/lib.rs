#![no_std]

//! RAÍZ · Contrato Rewards
//! ------------------------
//! Puntos no transferibles + catálogo de premios (artesanías) por barrio.
//! El turista acumula puntos pagando con tip; los canjea por premios físicos
//! que el artesano entrega manualmente (y marca como entregados en el contrato).
//!
//! Reglas (ver `docs/raiz_v2_spec_contratos.md` §Contrato 4):
//!   - 1 punto por cada 0.01 USDC de tip (100_000 stroops = 1 punto).
//!   - Solo el contrato Pool registrado puede llamar `accrue_points`.
//!   - Los puntos NO son transferibles entre turistas.
//!   - `redeem` quema puntos y decrementa stock atómicamente.
//!   - `claim_redemption` solo lo puede llamar el artesano dueño del reward.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, symbol_short, Address, BytesN, Env,
    String, Vec,
};

// ─────────────────────────────────────────────────────────────────────────────
// Errores
// ─────────────────────────────────────────────────────────────────────────────

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
#[repr(u32)]
pub enum Error {
    NotInitialized = 1,
    AlreadyInitialized = 2,
    Unauthorized = 3,
    RewardNotFound = 4,
    InsufficientPoints = 5,
    OutOfStock = 6,
    RedemptionNotFound = 7,
    AlreadyClaimed = 8,
    InvalidParams = 9,
}

// ─────────────────────────────────────────────────────────────────────────────
// Tipos
// ─────────────────────────────────────────────────────────────────────────────

#[contracttype]
#[derive(Clone)]
pub struct Reward {
    pub id: u64,
    pub barrio_id: BytesN<32>,
    pub name: String,
    pub artisan: Address,
    pub points_cost: u64,
    pub stock: u32,
    pub image_ref: String, // URL para MVP, ipfs:// en v2
}

#[contracttype]
#[derive(Clone)]
pub struct Redemption {
    pub id: u64,
    pub tourist: Address,
    pub reward_id: u64,
    pub redeemed_at: u64,
    pub claimed: bool,
}

#[contracttype]
#[derive(Clone)]
pub enum DataKey {
    Admin,
    PoolContract,
    Points(Address),
    Reward(u64),
    RewardCount,
    Redemption(u64),
    RedemptionCount,
    // Extensión a la spec: índice de reward_ids por barrio (para list_rewards eficiente).
    BarrioRewards(BytesN<32>),
}

// 1 punto por cada 0.01 USDC = 100_000 stroops.
const POINTS_PER_STROOP_DIVISOR: i128 = 100_000;

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

#[contract]
pub struct RewardsContract;

#[contractimpl]
impl RewardsContract {
    /// Inicializa el contrato. Solo se llama una vez.
    pub fn initialize(
        env: Env,
        admin: Address,
        pool_contract: Address,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage()
            .instance()
            .set(&DataKey::PoolContract, &pool_contract);
        env.storage()
            .persistent()
            .set(&DataKey::RewardCount, &0u64);
        env.storage()
            .persistent()
            .set(&DataKey::RedemptionCount, &0u64);
        Ok(())
    }

    /// Registra una nueva artesanía. Solo el admin del protocolo.
    pub fn register_reward(
        env: Env,
        barrio_id: BytesN<32>,
        name: String,
        artisan: Address,
        points_cost: u64,
        stock: u32,
        image_ref: String,
    ) -> Result<u64, Error> {
        let admin = Self::get_admin(&env)?;
        admin.require_auth();
        if points_cost == 0 || stock == 0 {
            return Err(Error::InvalidParams);
        }

        let id: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::RewardCount)
            .unwrap_or(0);
        let reward = Reward {
            id,
            barrio_id: barrio_id.clone(),
            name,
            artisan,
            points_cost,
            stock,
            image_ref,
        };
        env.storage()
            .persistent()
            .set(&DataKey::Reward(id), &reward);
        env.storage()
            .persistent()
            .set(&DataKey::RewardCount, &(id + 1));

        // Índice por barrio
        let key = DataKey::BarrioRewards(barrio_id);
        let mut index: Vec<u64> = env
            .storage()
            .persistent()
            .get(&key)
            .unwrap_or(Vec::new(&env));
        index.push_back(id);
        env.storage().persistent().set(&key, &index);

        Ok(id)
    }

    /// Acumula puntos a un turista. Solo el contrato Pool registrado puede llamar.
    /// `caller_pool` debe ser la Address del Pool y autorizar la llamada
    /// (Soroban autoriza automáticamente cuando un contrato se referencia a sí
    /// mismo en un cross-contract call vía `env.current_contract_address()`).
    pub fn accrue_points(
        env: Env,
        caller_pool: Address,
        tourist: Address,
        tip_amount: i128,
    ) -> Result<(), Error> {
        caller_pool.require_auth();
        let stored_pool: Address = env
            .storage()
            .instance()
            .get(&DataKey::PoolContract)
            .ok_or(Error::NotInitialized)?;
        if caller_pool != stored_pool {
            return Err(Error::Unauthorized);
        }
        if tip_amount <= 0 {
            return Ok(()); // tip 0: no-op silencioso
        }

        let points_to_add = (tip_amount / POINTS_PER_STROOP_DIVISOR) as u64;
        let current: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::Points(tourist.clone()))
            .unwrap_or(0);
        env.storage()
            .persistent()
            .set(&DataKey::Points(tourist), &(current + points_to_add));
        Ok(())
    }

    /// Canje atómico: quema puntos + decrementa stock + crea Redemption.
    pub fn redeem(env: Env, tourist: Address, reward_id: u64) -> Result<u64, Error> {
        tourist.require_auth();

        let mut reward: Reward = env
            .storage()
            .persistent()
            .get(&DataKey::Reward(reward_id))
            .ok_or(Error::RewardNotFound)?;
        if reward.stock == 0 {
            return Err(Error::OutOfStock);
        }

        let points: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::Points(tourist.clone()))
            .unwrap_or(0);
        if points < reward.points_cost {
            return Err(Error::InsufficientPoints);
        }

        // Atómico: quema puntos primero, luego stock, luego Redemption.
        env.storage().persistent().set(
            &DataKey::Points(tourist.clone()),
            &(points - reward.points_cost),
        );
        reward.stock -= 1;
        env.storage()
            .persistent()
            .set(&DataKey::Reward(reward_id), &reward);

        let red_id: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::RedemptionCount)
            .unwrap_or(0);
        let redemption = Redemption {
            id: red_id,
            tourist: tourist.clone(),
            reward_id,
            redeemed_at: env.ledger().timestamp(),
            claimed: false,
        };
        env.storage()
            .persistent()
            .set(&DataKey::Redemption(red_id), &redemption);
        env.storage()
            .persistent()
            .set(&DataKey::RedemptionCount, &(red_id + 1));

        env.events().publish(
            (symbol_short!("redeem"), reward.barrio_id),
            (tourist, reward_id, red_id),
        );
        Ok(red_id)
    }

    /// El artesano marca el premio como entregado. Solo el artesano del reward.
    pub fn claim_redemption(
        env: Env,
        artisan: Address,
        redemption_id: u64,
    ) -> Result<(), Error> {
        artisan.require_auth();
        let mut redemption: Redemption = env
            .storage()
            .persistent()
            .get(&DataKey::Redemption(redemption_id))
            .ok_or(Error::RedemptionNotFound)?;
        if redemption.claimed {
            return Err(Error::AlreadyClaimed);
        }
        let reward: Reward = env
            .storage()
            .persistent()
            .get(&DataKey::Reward(redemption.reward_id))
            .ok_or(Error::RewardNotFound)?;
        if reward.artisan != artisan {
            return Err(Error::Unauthorized);
        }
        redemption.claimed = true;
        env.storage()
            .persistent()
            .set(&DataKey::Redemption(redemption_id), &redemption);

        env.events().publish(
            (symbol_short!("claim"), reward.barrio_id),
            (artisan, redemption_id),
        );
        Ok(())
    }

    // ── Lecturas ──────────────────────────────────────────────────────────

    pub fn get_points(env: Env, tourist: Address) -> u64 {
        env.storage()
            .persistent()
            .get(&DataKey::Points(tourist))
            .unwrap_or(0)
    }

    pub fn get_reward(env: Env, reward_id: u64) -> Result<Reward, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Reward(reward_id))
            .ok_or(Error::RewardNotFound)
    }

    pub fn get_redemption(env: Env, redemption_id: u64) -> Result<Redemption, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Redemption(redemption_id))
            .ok_or(Error::RedemptionNotFound)
    }

    pub fn list_rewards(env: Env, barrio_id: BytesN<32>) -> Vec<Reward> {
        let ids: Vec<u64> = env
            .storage()
            .persistent()
            .get(&DataKey::BarrioRewards(barrio_id))
            .unwrap_or(Vec::new(&env));
        let mut out: Vec<Reward> = Vec::new(&env);
        for id in ids.iter() {
            if let Some(r) = env
                .storage()
                .persistent()
                .get::<DataKey, Reward>(&DataKey::Reward(id))
            {
                out.push_back(r);
            }
        }
        out
    }

    // ── Internos ──────────────────────────────────────────────────────────

    fn get_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)
    }
}

#[cfg(test)]
mod test;
