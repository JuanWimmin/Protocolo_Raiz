#![no_std]

//! RAÍZ · Contrato Pool
//! ---------------------
//! El corazón de los pagos. Recibe el pago del turista, separa el monto base
//! para el comercio y el "Tip Barrio" para el fondo comunitario, y llama
//! cross-contract al contrato Rewards para acumular puntos.
//!
//! Convenciones:
//!   - Montos en USDC se manejan como i128 en stroops (7 decimales). 1 USDC = 10_000_000.
//!   - tip_bps = basis points (200 = 2%).
//!   - barrio_id = BytesN<32>.
//!   - El token USDC se maneja vía su Stellar Asset Contract (SAC) usando la interfaz token.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, symbol_short,
    token, Address, BytesN, Env, String, Symbol, Vec,
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
    MerchantNotFound = 4,
    MerchantNotVerified = 5,
    BarrioNotFound = 6,
    InvalidAmount = 7,
    InvalidTipBps = 8,
}

// ─────────────────────────────────────────────────────────────────────────────
// Tipos de almacenamiento (espejo de RaizModels.kt)
// ─────────────────────────────────────────────────────────────────────────────

#[contracttype]
#[derive(Clone)]
pub struct BarrioData {
    pub id: BytesN<32>,
    pub name: String,
    pub pool_balance: i128,
    pub total_collected: i128,
    pub tx_count: u64,
    pub unique_tourists: u32,
    pub treasury_contract: Address,
}

#[contracttype]
#[derive(Clone)]
pub struct MerchantData {
    pub address: Address,
    pub name: String,
    pub barrio_id: BytesN<32>,
    pub verified: bool,
    pub lat_e6: i32,
    pub lng_e6: i32,
    pub category: Symbol,
}

// Claves de almacenamiento
#[contracttype]
#[derive(Clone)]
pub enum DataKey {
    Admin,
    UsdcToken,
    RewardsContract,
    ProtocolFeeBps,
    Barrio(BytesN<32>),
    Merchant(Address),
    // Índice de comercios por barrio, para list_merchants (alimenta el mapa)
    BarrioMerchants(BytesN<32>),
    // Set de turistas que ya aportaron a un barrio (para unique_tourists)
    TouristSeen(BytesN<32>, Address),
}

// ─────────────────────────────────────────────────────────────────────────────
// Interfaz del contrato Rewards (para cross-contract call)
// ─────────────────────────────────────────────────────────────────────────────

mod rewards_contract {
    // `contractimport!` resuelve rutas relativas al Cargo.toml del crate
    // (CARGO_MANIFEST_DIR), no al archivo. Desde `contracts/pool/` subimos un
    // nivel a `contracts/` donde vive el `target/` compartido del workspace.
    // Compila `rewards` antes de `pool`:
    //   cargo build --release --target wasm32-unknown-unknown -p rewards
    soroban_sdk::contractimport!(
        file = "../target/wasm32-unknown-unknown/release/rewards.wasm"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

const BPS_DENOMINATOR: i128 = 10_000;

#[contract]
pub struct PoolContract;

#[contractimpl]
impl PoolContract {
    /// Inicializa el contrato. Solo se llama una vez.
    pub fn initialize(
        env: Env,
        admin: Address,
        usdc_token: Address,
        rewards_contract: Address,
        protocol_fee_bps: u32,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage().instance().set(&DataKey::UsdcToken, &usdc_token);
        env.storage()
            .instance()
            .set(&DataKey::RewardsContract, &rewards_contract);
        env.storage()
            .instance()
            .set(&DataKey::ProtocolFeeBps, &protocol_fee_bps);
        Ok(())
    }

    /// Registra un barrio. Solo admin.
    pub fn register_barrio(
        env: Env,
        id: BytesN<32>,
        name: String,
        treasury_contract: Address,
    ) -> Result<(), Error> {
        let admin = Self::get_admin(&env)?;
        admin.require_auth();

        let barrio = BarrioData {
            id: id.clone(),
            name,
            pool_balance: 0,
            total_collected: 0,
            tx_count: 0,
            unique_tourists: 0,
            treasury_contract,
        };
        env.storage().persistent().set(&DataKey::Barrio(id.clone()), &barrio);
        // Inicializa el índice de comercios vacío
        let empty: Vec<Address> = Vec::new(&env);
        env.storage()
            .persistent()
            .set(&DataKey::BarrioMerchants(id), &empty);
        Ok(())
    }

    /// Registra y verifica un comercio. Solo admin.
    pub fn register_merchant(env: Env, data: MerchantData) -> Result<(), Error> {
        let admin = Self::get_admin(&env)?;
        admin.require_auth();

        // El barrio debe existir
        if !env
            .storage()
            .persistent()
            .has(&DataKey::Barrio(data.barrio_id.clone()))
        {
            return Err(Error::BarrioNotFound);
        }

        env.storage()
            .persistent()
            .set(&DataKey::Merchant(data.address.clone()), &data);

        // Añade al índice del barrio
        let key = DataKey::BarrioMerchants(data.barrio_id.clone());
        let mut list: Vec<Address> = env
            .storage()
            .persistent()
            .get(&key)
            .unwrap_or(Vec::new(&env));
        if !list.contains(&data.address) {
            list.push_back(data.address.clone());
            env.storage().persistent().set(&key, &list);
        }
        Ok(())
    }

    /// Pago principal: el turista paga al comercio con Tip Barrio opcional.
    ///
    /// - `amount`: monto base en stroops USDC (lo que recibe el comercio menos fee).
    /// - `tip_bps`: tip en basis points (200 = 2%). El tip va al pool del barrio.
    pub fn pay_merchant(
        env: Env,
        tourist: Address,
        merchant: Address,
        amount: i128,
        tip_bps: u32,
    ) -> Result<(), Error> {
        tourist.require_auth();

        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        if tip_bps > 10_000 {
            return Err(Error::InvalidTipBps);
        }

        // Carga el comercio
        let merchant_data: MerchantData = env
            .storage()
            .persistent()
            .get(&DataKey::Merchant(merchant.clone()))
            .ok_or(Error::MerchantNotFound)?;
        if !merchant_data.verified {
            return Err(Error::MerchantNotVerified);
        }

        let barrio_id = merchant_data.barrio_id.clone();
        let mut barrio: BarrioData = env
            .storage()
            .persistent()
            .get(&DataKey::Barrio(barrio_id.clone()))
            .ok_or(Error::BarrioNotFound)?;

        // Calcula tip y fee
        let tip: i128 = amount * (tip_bps as i128) / BPS_DENOMINATOR;
        let fee_bps: u32 = env
            .storage()
            .instance()
            .get(&DataKey::ProtocolFeeBps)
            .unwrap_or(0);
        let fee: i128 = amount * (fee_bps as i128) / BPS_DENOMINATOR;
        let to_merchant: i128 = amount - fee;

        // Cliente del token USDC
        let usdc_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::UsdcToken)
            .ok_or(Error::NotInitialized)?;
        let usdc = token::Client::new(&env, &usdc_addr);

        // Transferencias:
        //  1. turista -> comercio (monto base menos fee)
        usdc.transfer(&tourist, &merchant, &to_merchant);
        //  2. turista -> este contrato (el tip va al pool, custodiado por el contrato)
        if tip > 0 {
            let pool_addr = env.current_contract_address();
            usdc.transfer(&tourist, &pool_addr, &tip);
            barrio.pool_balance += tip;
            barrio.total_collected += tip;
        }
        //  3. fee del protocolo -> admin (si aplica)
        if fee > 0 {
            let admin = Self::get_admin(&env)?;
            usdc.transfer(&tourist, &admin, &fee);
        }

        // Actualiza estadísticas del barrio
        barrio.tx_count += 1;
        let seen_key = DataKey::TouristSeen(barrio_id.clone(), tourist.clone());
        if !env.storage().persistent().has(&seen_key) {
            barrio.unique_tourists += 1;
            env.storage().persistent().set(&seen_key, &true);
        }
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id.clone()), &barrio);

        // Cross-contract: acumula puntos en Rewards (proporcional al tip)
        if tip > 0 {
            let rewards_addr: Address = env
                .storage()
                .instance()
                .get(&DataKey::RewardsContract)
                .ok_or(Error::NotInitialized)?;
            let rewards = rewards_contract::Client::new(&env, &rewards_addr);
            rewards.accrue_points(&tourist, &tip);
        }

        // Evento
        env.events().publish(
            (symbol_short!("payment"), barrio_id.clone()),
            (tourist, merchant, amount, tip),
        );

        Ok(())
    }

    /// Transfiere fondos del pool a un beneficiario. Solo el Treasury del barrio puede llamar.
    /// Lo usa Treasury.execute_proposal tras un voto aprobado.
    pub fn withdraw_to(
        env: Env,
        caller: Address,
        barrio_id: BytesN<32>,
        recipient: Address,
        amount: i128,
    ) -> Result<(), Error> {
        caller.require_auth();

        let mut barrio: BarrioData = env
            .storage()
            .persistent()
            .get(&DataKey::Barrio(barrio_id.clone()))
            .ok_or(Error::BarrioNotFound)?;

        // Solo el treasury registrado para este barrio puede retirar
        if caller != barrio.treasury_contract {
            return Err(Error::Unauthorized);
        }
        if amount <= 0 || amount > barrio.pool_balance {
            return Err(Error::InvalidAmount);
        }

        let usdc_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::UsdcToken)
            .ok_or(Error::NotInitialized)?;
        let usdc = token::Client::new(&env, &usdc_addr);
        let pool_addr = env.current_contract_address();
        usdc.transfer(&pool_addr, &recipient, &amount);

        barrio.pool_balance -= amount;
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id), &barrio);
        Ok(())
    }

    // ── Lecturas ──────────────────────────────────────────────────────────

    pub fn get_pool_balance(env: Env, barrio_id: BytesN<32>) -> i128 {
        env.storage()
            .persistent()
            .get::<DataKey, BarrioData>(&DataKey::Barrio(barrio_id))
            .map(|b| b.pool_balance)
            .unwrap_or(0)
    }

    pub fn get_barrio(env: Env, barrio_id: BytesN<32>) -> Result<BarrioData, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Barrio(barrio_id))
            .ok_or(Error::BarrioNotFound)
    }

    pub fn get_merchant(env: Env, merchant: Address) -> Result<MerchantData, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Merchant(merchant))
            .ok_or(Error::MerchantNotFound)
    }

    /// Lista los comercios de un barrio. Alimenta el mapa de Mapbox.
    pub fn list_merchants(env: Env, barrio_id: BytesN<32>) -> Vec<MerchantData> {
        let addresses: Vec<Address> = env
            .storage()
            .persistent()
            .get(&DataKey::BarrioMerchants(barrio_id))
            .unwrap_or(Vec::new(&env));
        let mut out: Vec<MerchantData> = Vec::new(&env);
        for addr in addresses.iter() {
            if let Some(m) = env
                .storage()
                .persistent()
                .get::<DataKey, MerchantData>(&DataKey::Merchant(addr))
            {
                out.push_back(m);
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

mod test;
