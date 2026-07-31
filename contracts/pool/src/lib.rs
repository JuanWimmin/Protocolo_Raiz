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
//! También gestiona el vault DeFindex: admin o treasury del barrio pueden
//! depositar los fondos ociosos (`deposit_idle_to_vault`) y rescatarlos
//! (`redeem_from_vault`). El yield queda en `pool_balance` tras el rescate.
//!
//! Convenciones:
//!   - Montos en USDC se manejan como i128 en stroops (7 decimales). 1 USDC = 10_000_000.
//!   - tip_bps = basis points (200 = 2%).
//!   - barrio_id = BytesN<32>.
//!   - El token USDC se maneja vía su Stellar Asset Contract (SAC) usando la interfaz token.

use soroban_sdk::{
    auth::{ContractContext, InvokerContractAuthEntry, SubContractInvocation},
    contract, contracterror, contractimpl, contracttype, symbol_short, vec,
    token, Address, BytesN, Env, IntoVal, String, Symbol, Vec,
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
    VaultNotConfigured = 9,
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
    // Vault DeFindex (instance): dirección del vault compartida para todos los barrios
    DefindexVault,
    Barrio(BytesN<32>),
    Merchant(Address),
    // Índice de comercios por barrio, para list_merchants (alimenta el mapa)
    BarrioMerchants(BytesN<32>),
    // Set de turistas que ya aportaron a un barrio (para unique_tourists)
    TouristSeen(BytesN<32>, Address),
    // Shares del vault por barrio (persistent)
    VaultShares(BytesN<32>),
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
// Interfaz del vault DeFindex (cliente declarado a mano)
// ─────────────────────────────────────────────────────────────────────────────
//
// Se usa `#[contractclient]` en lugar de `contractimport!` porque el wasm del
// vault DeFindex no está en este repo.
//
// DECISIÓN sobre el tipo de retorno de `deposit`:
//   La firma on-chain real es:
//     deposit(...) -> (Vec<i128>, i128, Option<Vec<Option<AssetInvestmentAllocation>>>)
//   Solo necesitamos el elemento `.1` (i128 de shares). El tercer elemento varía
//   según la versión y configuración del vault:
//     - Con invest=false: puede ser Void (None) → ScVal::Void
//     - Con invest=true:  el vault DeFindex real devuelve [null] → ScVal::Vec([ScVal::Void])
//   Usar `()` (→ Void) o `Option<...>` falla para el caso invest=true del vault real.
//   SOLUCIÓN: usar `soroban_sdk::Val` (tipo comodín). `Val` implementa
//   `TryFromVal<Env, Val>` de forma identitaria (soroban-env-common val.rs:271),
//   por lo que acepta CUALQUIER valor XDR sin hacer type-checking. Solo lo ignoramos.
//   Verificado que Val es aceptable como componente de tupla en #[contractclient].
mod vault_client {
    use soroban_sdk::{contractclient, Address, Env, Val, Vec};

    #[allow(dead_code)]
    #[contractclient(name = "DefindexVaultClient")]
    pub trait DefindexVault {
        /// Deposita `amounts_desired[0]` USDC desde `from` al vault.
        /// Retorna (amounts_depositados, shares_minteadas, _alloc_ignorada).
        /// Solo usamos el elemento `.1`. El tercer elemento es `Val` (comodín
        /// que acepta cualquier XDR: Void cuando invest=false, Vec([Void]) cuando
        /// invest=true con el vault DeFindex real).
        fn deposit(
            env: Env,
            amounts_desired: Vec<i128>,
            amounts_min: Vec<i128>,
            from: Address,
            invest: bool,
        ) -> (Vec<i128>, i128, Val);

        /// Quema `withdraw_shares` shares y transfiere USDC de vuelta a `from`.
        /// Retorna vec de montos por asset (tomamos [0] para USDC).
        fn withdraw(
            env: Env,
            withdraw_shares: i128,
            min_amounts_out: Vec<i128>,
            from: Address,
        ) -> Vec<i128>;

        /// Shares que tiene `id` en el vault.
        fn balance(env: Env, id: Address) -> i128;

        /// Valor USDC underlying de `vault_shares` shares (vec por asset).
        fn get_asset_amounts_per_shares(env: Env, vault_shares: i128) -> Vec<i128>;
    }
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
    ///
    /// BREAKING CHANGE vs versión anterior: añade `defindex_vault`.
    /// Al re-desplegar en testnet hay que actualizar el script y deployments.json.
    pub fn initialize(
        env: Env,
        admin: Address,
        usdc_token: Address,
        rewards_contract: Address,
        protocol_fee_bps: u32,
        defindex_vault: Address,
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
            .set(&DataKey::DefindexVault, &defindex_vault);
        Ok(())
    }

    /// Actualiza la dirección del vault DeFindex. Solo admin.
    /// Útil si hay que migrar a una nueva versión del vault sin re-desplegar Pool.
    pub fn set_defindex_vault(env: Env, admin: Address, vault: Address) -> Result<(), Error> {
        let stored_admin = Self::get_admin(&env)?;
        admin.require_auth();
        if admin != stored_admin {
            return Err(Error::Unauthorized);
        }
        env.storage().instance().set(&DataKey::DefindexVault, &vault);
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

    // ── Vault DeFindex ────────────────────────────────────────────────────

    /// Deposita fondos ociosos del pool en el vault DeFindex para generar yield.
    ///
    /// Solo puede llamar el admin del protocolo O el treasury del barrio.
    ///
    /// Auth cross-contract (lo más delicado):
    ///   El vault hace `from.require_auth()` (satisfecho porque Pool es el invocador directo)
    ///   Y además llama internamente a `usdc.transfer(pool, vault, amount)`. Esa sub-llamada
    ///   la hace el VAULT, no el Pool → el Pool DEBE pre-autorizarla con
    ///   `env.authorize_as_current_contract` antes de llamar `vault.deposit`.
    ///
    /// NOTA TEST: con `mock_all_auths()` el auth se bypasea y los tests pasan sin
    ///   `authorize_as_current_contract`. El código es correcto para producción.
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

        let vault_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::DefindexVault)
            .ok_or(Error::VaultNotConfigured)?;
        let usdc_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::UsdcToken)
            .ok_or(Error::NotInitialized)?;

        let pool_addr = env.current_contract_address();

        // Pre-autoriza la sub-invocación que el vault hará internamente:
        //   usdc.transfer(pool, vault, amount)
        // La llama el VAULT (no Pool), así que Pool debe autorizarla explícitamente.
        env.authorize_as_current_contract(vec![
            &env,
            InvokerContractAuthEntry::Contract(SubContractInvocation {
                context: ContractContext {
                    contract: usdc_addr.clone(),
                    fn_name: Symbol::new(&env, "transfer"),
                    args: vec![
                        &env,
                        pool_addr.clone().into_val(&env),
                        vault_addr.clone().into_val(&env),
                        amount.into_val(&env),
                    ],
                },
                sub_invocations: vec![&env],
            }),
        ]);

        let vault = vault_client::DefindexVaultClient::new(&env, &vault_addr);
        let amounts_desired = vec![&env, amount];
        let amounts_min = vec![&env, 0i128];
        let (_amounts_deposited, shares, _alloc) =
            vault.deposit(&amounts_desired, &amounts_min, &pool_addr, &true);

        barrio.pool_balance -= amount;
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id.clone()), &barrio);

        let shares_key = DataKey::VaultShares(barrio_id.clone());
        let prev_shares: i128 = env.storage().persistent().get(&shares_key).unwrap_or(0);
        env.storage()
            .persistent()
            .set(&shares_key, &(prev_shares + shares));

        env.events().publish(
            (symbol_short!("vault_dep"), barrio_id),
            (amount, shares),
        );
        Ok(())
    }

    /// Rescata shares del vault DeFindex de vuelta al pool (realizando el yield).
    ///
    /// Solo puede llamar el admin del protocolo O el treasury del barrio.
    ///
    /// Auth: el vault hace `from.require_auth()` (Pool es el invocador → satisfecho
    ///   automáticamente). El vault luego llama `usdc.transfer(vault, pool, amount)`:
    ///   esa transferencia ES del vault, no del pool → pool NO necesita
    ///   `authorize_as_current_contract` para esta dirección.
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

        let vault_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::DefindexVault)
            .ok_or(Error::VaultNotConfigured)?;

        let pool_addr = env.current_contract_address();
        let vault = vault_client::DefindexVaultClient::new(&env, &vault_addr);
        let min_amounts_out = vec![&env, 0i128];
        let amounts: Vec<i128> = vault.withdraw(&shares, &min_amounts_out, &pool_addr);

        // Suma todos los montos devueltos (para vault multi-asset futuros).
        // En nuestro caso vault single-asset (USDC), sería amounts[0].
        let got: i128 = amounts.iter().fold(0i128, |acc, x| acc + x);

        barrio.pool_balance += got;
        env.storage()
            .persistent()
            .set(&DataKey::Barrio(barrio_id.clone()), &barrio);

        let shares_key = DataKey::VaultShares(barrio_id.clone());
        let prev_shares: i128 = env.storage().persistent().get(&shares_key).unwrap_or(0);
        env.storage()
            .persistent()
            .set(&shares_key, &(prev_shares - shares));

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

    /// Shares del vault que tiene este barrio en el vault DeFindex.
    pub fn get_vault_shares(env: Env, barrio_id: BytesN<32>) -> i128 {
        env.storage()
            .persistent()
            .get(&DataKey::VaultShares(barrio_id))
            .unwrap_or(0)
    }

    /// Valor USDC actual de las shares del vault para un barrio.
    /// Llama `vault.get_asset_amounts_per_shares` si hay shares > 0.
    /// Devuelve 0 si no hay shares o el vault no está configurado.
    pub fn get_vault_value(env: Env, barrio_id: BytesN<32>) -> i128 {
        let shares: i128 = env
            .storage()
            .persistent()
            .get(&DataKey::VaultShares(barrio_id.clone()))
            .unwrap_or(0);
        if shares == 0 {
            return 0;
        }
        let vault_addr: Address = match env.storage().instance().get(&DataKey::DefindexVault) {
            Some(addr) => addr,
            None => return 0,
        };
        let vault = vault_client::DefindexVaultClient::new(&env, &vault_addr);
        let amounts = vault.get_asset_amounts_per_shares(&shares);
        amounts.get(0).unwrap_or(0)
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
