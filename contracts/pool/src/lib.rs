#![no_std]
// `env.events().publish()` está deprecado desde soroban-sdk 23 en favor de
// `#[contractevent]`, pero migrar cambiaría el formato on-chain de los eventos
// (topics/data derivados del struct) y rompería el dashboard de transparencia
// y el parser Android. Se mantiene `publish()` intencionalmente.
#![allow(deprecated)]

//! RAÍZ · Contrato Pool
//! ---------------------
//! El corazón de los pagos. Recibe el pago del turista, separa el monto base
//! para el comercio y el "Tip Barrio" para el fondo comunitario, y llama
//! cross-contract al contrato Rewards para acumular puntos.
//!
//! También gestiona el yield sobre fondos ociosos (F1): admin o treasury del
//! barrio pueden depositar los fondos ociosos (`deposit_idle_to_vault`) y
//! rescatarlos (`redeem_from_vault`) a través de un contrato `yield_adapter`
//! intercambiable (`set_yield_adapter`) — Pool NO conoce a Blend, solo la
//! interfaz estándar del adapter. El yield queda en `pool_balance` tras el
//! rescate. Los nombres `deposit_idle_to_vault`/`redeem_from_vault`/
//! `get_vault_shares`/`get_vault_value` y los eventos `vault_dep`/`vault_red`
//! se CONSERVAN por compatibilidad con Treasury, la app y el dashboard —
//! "vault" ahora significa "la fuente de yield tras el adapter".
//!
//! Convenciones:
//!   - Montos en USDC se manejan como i128 en stroops (7 decimales). 1 USDC = 10_000_000.
//!   - tip_bps = basis points (200 = 2%).
//!   - barrio_id = BytesN<32>.
//!   - El token USDC se maneja vía su Stellar Asset Contract (SAC) usando la interfaz token.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, symbol_short, token, Address, BytesN,
    Env, String, Symbol, Vec,
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
    // F1: antes "VaultNotConfigured" (DeFindex). Mismo código, nuevo nombre:
    // significa que DataKey::YieldAdapter no está seteado.
    AdapterNotConfigured = 9,
    // F1: nuevos errores del colchón líquido / migración de adapter.
    InsufficientLiquidity = 10,
    AdapterHasPositions = 11,
    InvalidBps = 12,
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
    // F1: Address del contrato yield_adapter (instance), compartido para
    // todos los barrios. Antes era DefindexVault. La contabilidad de shares
    // por barrio YA NO vive aquí — vive en el adapter (única fuente de verdad).
    YieldAdapter,
    // F1: colchón líquido no invertido, en bps (instance, default 2000 = 20%).
    // Fracción del fondo del barrio que NUNCA se deposita en el adapter, para
    // que Treasury tenga liquidez inmediata sin depender de un rescate.
    CushionBps,
    Barrio(BytesN<32>),
    Merchant(Address),
    // Índice de comercios por barrio, para list_merchants (alimenta el mapa)
    BarrioMerchants(BytesN<32>),
    // Set de turistas que ya aportaron a un barrio (para unique_tourists)
    TouristSeen(BytesN<32>, Address),
    // Índice global de barrios registrados (persistent Vec<BytesN<32>>).
    // Alimentado por register_barrio; permite que la app descubra barrios
    // dinámicamente sin hardcodear la lista. Los barrios registrados ANTES
    // de este cambio no aparecerán aquí hasta un re-seed que los vuelva a
    // registrar. La app debe mantener fallback (deployments.json) mientras tanto.
    AllBarrios,
}

// ─────────────────────────────────────────────────────────────────────────────
// Interfaz del contrato Rewards (para cross-contract call)
// ─────────────────────────────────────────────────────────────────────────────
//
// Cliente declarado a mano con `#[contractclient]` en lugar de `contractimport!`:
// evita el build de dos pasos (compilar el wasm de Rewards antes que Pool).
// Mantener en sync con la firma real de `rewards::accrue_points`
// (contracts/rewards/src/lib.rs). Si Rewards cambia esa firma, actualizar aquí.
mod rewards_contract {
    use soroban_sdk::{contractclient, Address, Env};

    #[allow(dead_code)]
    #[contractclient(name = "Client")]
    pub trait Rewards {
        /// Acumula puntos para `tourist` proporcional a `tip_amount`. Rewards
        /// verifica que `caller_pool` es el Pool registrado (require_auth +
        /// comparación contra el address guardado en su storage).
        fn accrue_points(env: Env, caller_pool: Address, tourist: Address, tip_amount: i128);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Interfaz del yield_adapter (cliente declarado a mano, F1)
// ─────────────────────────────────────────────────────────────────────────────
//
// Se usa `#[contractclient]` en lugar de `contractimport!`: evita el build de
// dos pasos. Mantener en sync con `contracts/yield_adapter/src/lib.rs`
// (interfaz estándar del Contrato 5 en la spec — cualquier implementación de
// yield_adapter debe respetar EXACTAMENTE estas firmas).
//
// NOTA: las funciones públicas del adapter devuelven `Result<T, Error>`, pero
// el trait del cliente declara solo el tipo de éxito (`T`) — el host de
// Soroban traduce `Result::Err` en un trap/panic en la llamada cross-contract,
// no en un `Result` XDR. Mismo patrón que `PoolClient`/`GovernanceClient` en
// treasury/src/lib.rs.
mod yield_adapter_client {
    use soroban_sdk::{contractclient, Address, BytesN, Env};

    #[allow(dead_code)]
    #[contractclient(name = "Client")]
    pub trait YieldAdapter {
        /// Deposita `amount` (ya transferido al adapter por Pool) y devuelve
        /// las shares acreditadas al barrio.
        fn deposit(env: Env, caller: Address, barrio_id: BytesN<32>, amount: i128) -> i128;
        /// Retira `shares` del barrio y envía el USDC resultante a `to`.
        /// Devuelve el USDC (stroops) efectivamente enviado.
        fn withdraw(
            env: Env,
            caller: Address,
            barrio_id: BytesN<32>,
            shares: i128,
            to: Address,
        ) -> i128;
        /// Shares del barrio (lectura pura, sin auth).
        fn shares_of(env: Env, barrio_id: BytesN<32>) -> i128;
        /// Suma de shares de todos los barrios (invariante de migración).
        fn total_shares(env: Env) -> i128;
        /// Valor actual en USDC stroops de las shares del barrio.
        fn value_of(env: Env, barrio_id: BytesN<32>) -> i128;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

const BPS_DENOMINATOR: i128 = 10_000;
/// Colchón líquido por defecto: 20% del fondo del barrio nunca se invierte.
const DEFAULT_CUSHION_BPS: u32 = 2_000;

#[contract]
pub struct PoolContract;

#[contractimpl]
impl PoolContract {
    /// Inicializa el contrato. Solo se llama una vez.
    ///
    /// BREAKING CHANGE (F1, re-deploy): el 5° parámetro pasa a ser el contrato
    /// `yield_adapter` (antes era `defindex_vault`). Al re-desplegar en
    /// testnet hay que actualizar el script y deployments.json.
    pub fn initialize(
        env: Env,
        admin: Address,
        usdc_token: Address,
        rewards_contract: Address,
        protocol_fee_bps: u32,
        yield_adapter: Address,
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
        env.storage()
            .instance()
            .set(&DataKey::YieldAdapter, &yield_adapter);
        env.storage()
            .instance()
            .set(&DataKey::CushionBps, &DEFAULT_CUSHION_BPS);
        Ok(())
    }

    /// Cambia el yield_adapter en caliente sin re-desplegar Pool. Solo admin
    /// (v-actual; cuando la gobernanza pueda invocarlo, será una propuesta
    /// votable — "¿el fondo va conservador o 70/30?").
    ///
    /// GUARDA: si ya había un adapter configurado, exige que no tenga
    /// posiciones activas (`total_shares() == 0`). Migrar con posiciones
    /// exige rescatar todo antes (vía `redeem_from_vault` por barrio). Si no
    /// había adapter previo (primera vez), la operación es libre.
    pub fn set_yield_adapter(env: Env, admin: Address, adapter: Address) -> Result<(), Error> {
        let stored_admin = Self::get_admin(&env)?;
        admin.require_auth();
        if admin != stored_admin {
            return Err(Error::Unauthorized);
        }
        if let Some(current) = env
            .storage()
            .instance()
            .get::<DataKey, Address>(&DataKey::YieldAdapter)
        {
            let current_client = yield_adapter_client::Client::new(&env, &current);
            if current_client.total_shares() > 0 {
                return Err(Error::AdapterHasPositions);
            }
        }
        env.storage().instance().set(&DataKey::YieldAdapter, &adapter);
        Ok(())
    }

    /// Colchón líquido gobernable (default 2000 bps = 20%): fracción del
    /// fondo del barrio que NUNCA se invierte, para que Treasury tenga
    /// liquidez inmediata. Solo admin (futuro: gobernanza).
    pub fn set_cushion_bps(env: Env, admin: Address, bps: u32) -> Result<(), Error> {
        let stored_admin = Self::get_admin(&env)?;
        admin.require_auth();
        if admin != stored_admin {
            return Err(Error::Unauthorized);
        }
        if bps > 10_000 {
            return Err(Error::InvalidBps);
        }
        env.storage().instance().set(&DataKey::CushionBps, &bps);
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

        // Actualiza el índice global de barrios para que la app los descubra
        // dinámicamente. Evita duplicados: si el mismo id ya figura en la lista
        // (re-registro del barrio), no se inserta de nuevo.
        let mut all: Vec<BytesN<32>> = env
            .storage()
            .persistent()
            .get(&DataKey::AllBarrios)
            .unwrap_or(Vec::new(&env));
        if !all.contains(&id) {
            all.push_back(id.clone());
            env.storage().persistent().set(&DataKey::AllBarrios, &all);
        }

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

        // Cross-contract: acumula puntos en Rewards (proporcional al tip).
        // Rewards verifica que el caller es este Pool — por eso pasamos
        // current_contract_address() como primer arg.
        if tip > 0 {
            let rewards_addr: Address = env
                .storage()
                .instance()
                .get(&DataKey::RewardsContract)
                .ok_or(Error::NotInitialized)?;
            let rewards = rewards_contract::Client::new(&env, &rewards_addr);
            rewards.accrue_points(&env.current_contract_address(), &tourist, &tip);
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

    // ── Yield sobre fondos ociosos (F1: vía yield_adapter) ──────────────────

    /// Deposita fondos ociosos del pool en la fuente de yield vía el adapter.
    ///
    /// Solo puede llamar el admin del protocolo O el treasury del barrio.
    ///
    /// Flujo: `usdc.transfer(pool → adapter, amount)` — llamada DIRECTA al SAC
    /// (invoker auth, sin `authorize_as_current_contract`: a diferencia de
    /// DeFindex, aquí Pool mismo mueve los fondos, no el adapter) — seguido de
    /// `adapter.deposit(pool_addr, barrio_id, amount)`. El
    /// `authorize_as_current_contract` para la sub-invocación a Blend vive
    /// ahora DENTRO del adapter, no en Pool.
    ///
    /// Valida el colchón líquido ANTES de mover fondos: tras depositar, el
    /// saldo idle restante del barrio debe seguir representando al menos
    /// `cushion_bps` del valor total (idle + invertido).
    pub fn deposit_idle_to_vault(
        env: Env,
        caller: Address,
        barrio_id: BytesN<32>,
        amount: i128,
    ) -> Result<(), Error> {
        caller.require_auth();

        let admin = Self::get_admin(&env)?;
        let mut barrio: BarrioData = env
            .storage()
            .persistent()
            .get(&DataKey::Barrio(barrio_id.clone()))
            .ok_or(Error::BarrioNotFound)?;

        if caller != admin && caller != barrio.treasury_contract {
            return Err(Error::Unauthorized);
        }
        if amount <= 0 || amount > barrio.pool_balance {
            return Err(Error::InvalidAmount);
        }

        let adapter_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::YieldAdapter)
            .ok_or(Error::AdapterNotConfigured)?;
        let usdc_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::UsdcToken)
            .ok_or(Error::NotInitialized)?;

        let adapter_client = yield_adapter_client::Client::new(&env, &adapter_addr);

        // Colchón: (pool_balance - amount) * 10_000 >= (pool_balance + value_of) * cushion_bps
        let cushion_bps: u32 = env
            .storage()
            .instance()
            .get(&DataKey::CushionBps)
            .unwrap_or(DEFAULT_CUSHION_BPS);
        let current_value = adapter_client.value_of(&barrio_id);
        let remaining_liquid = barrio.pool_balance - amount;
        let total_value = barrio.pool_balance + current_value;
        if remaining_liquid * BPS_DENOMINATOR < total_value * (cushion_bps as i128) {
            return Err(Error::InsufficientLiquidity);
        }

        let usdc = token::Client::new(&env, &usdc_addr);
        let pool_addr = env.current_contract_address();
        // Invocación directa al SAC: invoker auth (Pool es el invocador
        // directo), SIN authorize_as_current_contract — eso vive en el adapter.
        usdc.transfer(&pool_addr, &adapter_addr, &amount);

        let shares = adapter_client.deposit(&pool_addr, &barrio_id, &amount);

        barrio.pool_balance -= amount;
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id.clone()), &barrio);

        env.events().publish(
            (symbol_short!("vault_dep"), barrio_id),
            (amount, shares),
        );
        Ok(())
    }

    /// Rescata shares de la fuente de yield vía el adapter de vuelta a
    /// `pool_balance` (realizando el yield).
    ///
    /// Solo puede llamar el admin del protocolo O el treasury del barrio. El
    /// adapter valida `shares <= shares_of(barrio_id)` — corrige el bug
    /// pre-F1 de rescatar shares contablemente ajenas a otro barrio.
    pub fn redeem_from_vault(
        env: Env,
        caller: Address,
        barrio_id: BytesN<32>,
        shares: i128,
    ) -> Result<(), Error> {
        caller.require_auth();

        let admin = Self::get_admin(&env)?;
        let mut barrio: BarrioData = env
            .storage()
            .persistent()
            .get(&DataKey::Barrio(barrio_id.clone()))
            .ok_or(Error::BarrioNotFound)?;

        if caller != admin && caller != barrio.treasury_contract {
            return Err(Error::Unauthorized);
        }
        if shares <= 0 {
            return Err(Error::InvalidAmount);
        }

        let adapter_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::YieldAdapter)
            .ok_or(Error::AdapterNotConfigured)?;

        let pool_addr = env.current_contract_address();
        let adapter_client = yield_adapter_client::Client::new(&env, &adapter_addr);
        let got = adapter_client.withdraw(&pool_addr, &barrio_id, &shares, &pool_addr);

        barrio.pool_balance += got;
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id.clone()), &barrio);

        env.events().publish(
            (symbol_short!("vault_red"), barrio_id),
            (shares, got),
        );
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

    /// Shares que tiene este barrio en la fuente de yield actual (delega en
    /// el adapter — Pool ya no guarda shares propias). 0 si no hay adapter.
    pub fn get_vault_shares(env: Env, barrio_id: BytesN<32>) -> i128 {
        let adapter_addr: Address = match env.storage().instance().get(&DataKey::YieldAdapter) {
            Some(addr) => addr,
            None => return 0,
        };
        yield_adapter_client::Client::new(&env, &adapter_addr).shares_of(&barrio_id)
    }

    /// Valor USDC actual de las shares del barrio (delega en el adapter).
    /// 0 si no hay adapter configurado.
    pub fn get_vault_value(env: Env, barrio_id: BytesN<32>) -> i128 {
        let adapter_addr: Address = match env.storage().instance().get(&DataKey::YieldAdapter) {
            Some(addr) => addr,
            None => return 0,
        };
        yield_adapter_client::Client::new(&env, &adapter_addr).value_of(&barrio_id)
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

    /// Devuelve todos los `barrio_id` registrados, en orden de inserción.
    /// Solo lectura, sin autenticación. Permite que la app descubra barrios
    /// dinámicamente sin hardcodear la lista en el cliente.
    ///
    /// NOTA: los barrios registrados ANTES de que se añadiera `DataKey::AllBarrios`
    /// (este cambio) no aparecerán hasta un re-despliegue + re-seed. La app debe
    /// mantener un fallback (deployments.json / lista hardcodeada) mientras tanto.
    pub fn list_barrios(env: Env) -> Vec<BytesN<32>> {
        env.storage()
            .persistent()
            .get(&DataKey::AllBarrios)
            .unwrap_or(Vec::new(&env))
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
