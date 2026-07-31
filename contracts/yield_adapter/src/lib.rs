#![no_std]
// `env.events().publish()` está deprecado desde soroban-sdk 23 en favor de
// `#[contractevent]`, pero migrar cambiaría el formato on-chain de los eventos
// y rompería el dashboard de transparencia y el parser Android. Se mantiene
// `publish()` intencionalmente, igual que en los otros 4 crates de RAÍZ.
#![allow(deprecated)]

//! RAÍZ · Contrato `yield_adapter` — `BlendAdapter` (F1)
//! --------------------------------------------------------
//! El Pool NO conoce a Blend: conoce esta interfaz estándar (`deposit`,
//! `withdraw`, `shares_of`, `total_shares`, `value_of`, `apy_hint`). Cambiar
//! de fuente de yield es desplegar otro adapter + `Pool.set_yield_adapter`,
//! no un re-deploy de Pool. Ver `docs/raiz_v2_spec_contratos.md` §Contrato 5.
//!
//! Implementación 1: `BlendAdapter`, prestamista puro sobre Blend v2 testnet.
//!
//! ## Invariante de contabilidad
//! `shares` ≡ bTokens de Blend, sin capa extra de contabilidad. La posición
//! en Blend está a nombre de ESTE contrato (`env.current_contract_address()`),
//! compartida entre todos los barrios. `DataKey::Shares(barrio_id)` es la
//! porción de esa posición conjunta que le corresponde a cada barrio;
//! `DataKey::TotalShares` es la suma de todas las porciones. Se mantiene el
//! invariante:
//!   `total_shares() == Σ Shares(barrio_id) == positions.supply[usdc_index] en Blend`
//! `deposit` y `withdraw` NUNCA calculan las shares con una fórmula estática:
//! leen el delta REAL de `positions.supply` antes/después de `submit`, así
//! que el invariante se mantiene incluso si Blend redondea internamente.
//!
//! ## Auth con Blend (verificado contra blend-contracts-v2, jul-2026)
//! - `deposit`: Blend hace `usdc.transfer(adapter → blend_pool, amount)` como
//!   sub-invocación DENTRO de `submit` — el adapter debe pre-autorizarla con
//!   `env.authorize_as_current_contract` (Patrón A del brief de integración).
//! - `withdraw`/`claim_blnd`: SIN auth extra. Los tokens salen del pool de
//!   Blend hacia `to`/`caller`; el `spender.require_auth()` interno de Blend
//!   lo satisface el invoker auth (este adapter es el invocador directo).
//! - GOTCHA verificado empíricamente (soroban-sdk 26.1.1 + `mock_all_auths()`):
//!   `env.authorize_as_current_contract(...)` debe llamarse INMEDIATAMENTE
//!   antes de la invocación que dispara la sub-invocación autorizada
//!   (`submit`). Si se intercala OTRA llamada cross-contract (p.ej. los
//!   `get_reserve`/`get_positions` de solo-lectura) entre el `authorize_as_
//!   current_contract` y `submit`, el auth manager en modo "recording" deja
//!   de reconocer la entrada registrada y el `submit` falla con
//!   `Error(Auth, InvalidAction)` — aunque la entrada sea estructuralmente
//!   idéntica a la que sí funciona. Por eso en `deposit` las lecturas de
//!   Blend (`get_reserve`, `get_positions`) se hacen ANTES de autorizar, y
//!   `submit` es la llamada que sigue inmediatamente a
//!   `authorize_as_current_contract`.
//!
//! ## Redondeo (deposit floor / withdraw ceil, prueba en `test.rs`)
//! Con `b_rate >= SCALAR_12` (siempre cierto: Blend arranca en 1e12 y solo
//! crece con el interés acumulado), pedir `amount = floor(shares*b_rate/1e12)`
//! y que Blend queme `ceil(amount*1e12/b_rate)` bTokens produce SIEMPRE
//! `burned == shares` exactamente — es una identidad aritmética, no una
//! casualidad de los tests (demostración en el comentario de `withdraw`).
//! Aun así, el código NUNCA asume esa igualdad: descuenta del barrio el
//! delta REAL quemado (`before - after` en `positions.supply`), así que si
//! algún día Blend capea el retiro (viejo bug de sobre-pedir) el dust
//! (`shares - burned`, siempre `>= 0`) queda acreditado al barrio, nunca se
//! pierde ni se resta de otro barrio.

use soroban_sdk::{
    auth::{ContractContext, InvokerContractAuthEntry, SubContractInvocation},
    contract, contracterror, contractimpl, contracttype, symbol_short, vec, Address, BytesN, Env,
    IntoVal, Symbol,
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
    InvalidAmount = 4,
    InsufficientShares = 5,
}

// ─────────────────────────────────────────────────────────────────────────────
// Storage
// ─────────────────────────────────────────────────────────────────────────────

#[contracttype]
#[derive(Clone)]
pub enum DataKey {
    Admin,          // Address admin del protocolo (instance)
    PoolContract,   // Address del Pool de RAÍZ — único autorizado a deposit/withdraw (instance)
    BlendPool,      // Address del pool USDC de Blend v2 (instance)
    UsdcToken,      // Address del USDC SAC de Blend (instance)
    Shares(BytesN<32>), // barrio_id -> bTokens del barrio (persistent)
    TotalShares,    // suma de bTokens de todos los barrios (instance)
}

// ─────────────────────────────────────────────────────────────────────────────
// Cliente del pool Blend v2 (declarado a mano — NO blend-contract-sdk: su
// última versión (2.25.0) es para soroban-sdk ^25.0.1, incompatible con
// nuestro 26.1.1). Structs espejo EXACTOS de blend-contracts-v2 (verificados
// campo a campo contra pool/src/pool/actions.rs, pool.rs, reserve.rs,
// storage.rs — jul-2026). La deserialización de un ScMap por Soroban exige
// match completo de campos por nombre: si falta o sobra un campo, panic.
// ─────────────────────────────────────────────────────────────────────────────

mod blend_pool_client {
    use soroban_sdk::{contractclient, contracttype, Address, Env, Map, Vec};

    /// `request_type` es un `u32` plano en el wire (no un enum Rust). Ver
    /// `REQUEST_SUPPLY`/`REQUEST_WITHDRAW` en el módulo padre.
    #[contracttype]
    #[derive(Clone)]
    pub struct Request {
        pub request_type: u32,
        pub address: Address,
        pub amount: i128,
    }

    /// Las claves de los 3 mapas son el ÍNDICE `u32` de la reserva
    /// (`ReserveConfig.index`), NO la Address del asset.
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
        pub d_rate: i128,        // conversión dToken->subyacente, 12 decimales
        pub b_rate: i128,        // conversión bToken->subyacente, 12 decimales
        pub ir_mod: i128,        // modificador de la curva de interés, 7 decimales
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
        pub scalar: i128, // 10^decimals del asset (USDC: 1e7)
    }

    /// Verificado contra `pool/src/storage.rs` (blend-contracts-v2, curl
    /// 2026-07-31): `oracle, min_collateral, bstop_rate, status, max_positions`.
    #[contracttype]
    #[derive(Clone)]
    pub struct PoolConfig {
        pub oracle: Address,
        pub min_collateral: i128,
        pub bstop_rate: u32, // take del backstop sobre el interés, 7 decimales
        pub status: u32,
        pub max_positions: u32,
    }

    #[allow(dead_code)]
    #[contractclient(name = "Client")]
    pub trait BlendPool {
        fn submit(env: Env, from: Address, spender: Address, to: Address, requests: Vec<Request>) -> Positions;
        fn get_positions(env: Env, address: Address) -> Positions;
        fn get_reserve(env: Env, asset: Address) -> Reserve;
        fn get_config(env: Env) -> PoolConfig;
        fn claim(env: Env, from: Address, reserve_token_ids: Vec<u32>, to: Address) -> i128;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Constantes
// ─────────────────────────────────────────────────────────────────────────────

/// Prestamista puro: NUNCA `SupplyCollateral`/`WithdrawCollateral` (2/3) — no
/// entra al health factor ni a `max_positions`. Patrón del fee-vault oficial.
const REQUEST_SUPPLY: u32 = 0;
const REQUEST_WITHDRAW: u32 = 1;
/// Escala del `b_rate`/`d_rate` en Blend v2 (en v1 era 1e9 — NO mezclar).
const SCALAR_12: i128 = 1_000_000_000_000;
/// Escala de los factores de la curva de interés y `bstop_rate` (7 decimales).
const SCALAR_7: i128 = 10_000_000;

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

#[contract]
pub struct BlendAdapter;

#[contractimpl]
impl BlendAdapter {
    /// Inicializa el adapter. Solo se llama una vez.
    pub fn initialize(
        env: Env,
        admin: Address,
        pool_contract: Address,
        blend_pool: Address,
        usdc_token: Address,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage()
            .instance()
            .set(&DataKey::PoolContract, &pool_contract);
        env.storage().instance().set(&DataKey::BlendPool, &blend_pool);
        env.storage().instance().set(&DataKey::UsdcToken, &usdc_token);
        env.storage().instance().set(&DataKey::TotalShares, &0i128);
        Ok(())
    }

    /// Deposita `amount` (stroops USDC) en Blend, acreditado al barrio.
    ///
    /// El USDC ya debe estar en el balance de este contrato — Pool hace
    /// `usdc.transfer(pool → adapter, amount)` justo antes (invocación
    /// directa, invoker auth, SIN auth anidada en Pool). Aquí SÍ hace falta
    /// `authorize_as_current_contract` porque el `usdc.transfer(adapter →
    /// blend_pool, amount)` lo dispara Blend como sub-invocación DENTRO de
    /// `submit` — un frame más profundo que el invoker auth de este adapter
    /// no cubre automáticamente.
    pub fn deposit(env: Env, caller: Address, barrio_id: BytesN<32>, amount: i128) -> Result<i128, Error> {
        caller.require_auth();
        let pool_contract = Self::get_pool_contract(&env)?;
        if caller != pool_contract {
            return Err(Error::Unauthorized);
        }
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }

        let blend_pool_addr = Self::get_blend_pool(&env)?;
        let usdc_addr = Self::get_usdc_token(&env)?;
        let me = env.current_contract_address();

        let blend = blend_pool_client::Client::new(&env, &blend_pool_addr);
        let idx = blend.get_reserve(&usdc_addr).config.index;

        // Delta REAL de bTokens minteados (nunca una fórmula estática): la
        // posición supply[idx] es compartida por todos los barrios.
        let before: i128 = blend.get_positions(&me).supply.get(idx).unwrap_or(0);

        // `authorize_as_current_contract` debe llamarse INMEDIATAMENTE antes de
        // la invocación que dispara la sub-invocación autorizada (`submit`, que
        // internamente hace `usdc.transfer`): intercalar otras llamadas
        // cross-contract (como los `get_reserve`/`get_positions` de arriba)
        // ENTRE la autorización y `submit` hace que el auth manager de Soroban
        // no reconozca la entrada registrada (verificado empíricamente).
        env.authorize_as_current_contract(vec![
            &env,
            InvokerContractAuthEntry::Contract(SubContractInvocation {
                context: ContractContext {
                    contract: usdc_addr.clone(),
                    fn_name: Symbol::new(&env, "transfer"),
                    args: vec![
                        &env,
                        me.clone().into_val(&env),
                        blend_pool_addr.clone().into_val(&env),
                        amount.into_val(&env),
                    ],
                },
                sub_invocations: vec![&env],
            }),
        ]);

        let new_positions = blend.submit(
            &me,
            &me,
            &me,
            &vec![
                &env,
                blend_pool_client::Request {
                    request_type: REQUEST_SUPPLY,
                    address: usdc_addr,
                    amount,
                },
            ],
        );
        let after: i128 = new_positions.supply.get(idx).unwrap_or(0);
        let minted = after - before;

        let shares_key = DataKey::Shares(barrio_id.clone());
        let prev: i128 = env.storage().persistent().get(&shares_key).unwrap_or(0);
        env.storage().persistent().set(&shares_key, &(prev + minted));

        let total: i128 = env.storage().instance().get(&DataKey::TotalShares).unwrap_or(0);
        env.storage()
            .instance()
            .set(&DataKey::TotalShares, &(total + minted));

        env.events()
            .publish((symbol_short!("supply"), barrio_id), (amount, minted));

        Ok(minted)
    }

    /// Retira `shares` del barrio y envía el USDC resultante a `to`.
    ///
    /// SIN auth extra hacia Blend: el flujo de tokens es `blend_pool → to`
    /// (transfer saliente del pool de Blend), cubierto por el
    /// `spender.require_auth()` interno de Blend vía invoker auth (este
    /// adapter es `from = spender` y el invocador directo de `submit`).
    ///
    /// `amount` pedido = `floor(shares * b_rate / 1e12)`. Con `b_rate >=
    /// 1e12` (siempre cierto), Blend quema `ceil(amount * 1e12 / b_rate)`
    /// bTokens, que es SIEMPRE exactamente `shares` (identidad aritmética:
    /// sea `x = shares·b_rate`, `amount = floor(x/S)` con resto `r < S <=
    /// b_rate`; entonces `amount·S = x - r` y `(x-r)/b_rate = shares -
    /// r/b_rate` cae en `(shares-1, shares]`, cuyo techo es siempre
    /// `shares`). El código igual usa el delta REAL de `positions.supply`
    /// (`burned = before - after`), nunca esa fórmula, así que si Blend
    /// llegase a capear el retiro (posición insuficiente), `burned < shares`
    /// y el dust (`shares - burned`) queda acreditado al barrio.
    pub fn withdraw(
        env: Env,
        caller: Address,
        barrio_id: BytesN<32>,
        shares: i128,
        to: Address,
    ) -> Result<i128, Error> {
        caller.require_auth();
        let pool_contract = Self::get_pool_contract(&env)?;
        if caller != pool_contract {
            return Err(Error::Unauthorized);
        }

        let shares_key = DataKey::Shares(barrio_id.clone());
        let barrio_shares: i128 = env.storage().persistent().get(&shares_key).unwrap_or(0);
        if shares <= 0 || shares > barrio_shares {
            return Err(Error::InsufficientShares);
        }

        let blend_pool_addr = Self::get_blend_pool(&env)?;
        let usdc_addr = Self::get_usdc_token(&env)?;
        let me = env.current_contract_address();

        let blend = blend_pool_client::Client::new(&env, &blend_pool_addr);
        let reserve = blend.get_reserve(&usdc_addr);
        let idx = reserve.config.index;
        let b_rate = reserve.data.b_rate;

        let amount = shares * b_rate / SCALAR_12;
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }

        let before: i128 = blend.get_positions(&me).supply.get(idx).unwrap_or(0);
        blend.submit(
            &me,
            &me,
            &to,
            &vec![
                &env,
                blend_pool_client::Request {
                    request_type: REQUEST_WITHDRAW,
                    address: usdc_addr,
                    amount,
                },
            ],
        );
        let after: i128 = blend.get_positions(&me).supply.get(idx).unwrap_or(0);
        let burned = before - after; // <= shares siempre (ver comentario arriba)

        env.storage()
            .persistent()
            .set(&shares_key, &(barrio_shares - burned));

        let total: i128 = env.storage().instance().get(&DataKey::TotalShares).unwrap_or(0);
        env.storage()
            .instance()
            .set(&DataKey::TotalShares, &(total - burned));

        env.events()
            .publish((symbol_short!("withdrw"), barrio_id), (shares, amount));

        Ok(amount)
    }

    /// Solo admin: reclama emisiones BLND del lado supply hacia `to`. En
    /// TestnetV2 el lado supply de USDC puede no tener emisiones — la llamada
    /// a Blend es segura igual (devuelve 0 si `get_reserve_emissions` es None,
    /// no lo exponemos aquí porque no lo necesitamos: `claim` en sí ya es
    /// no-op seguro si no hay nada que reclamar).
    pub fn claim_blnd(env: Env, admin: Address, to: Address) -> Result<i128, Error> {
        admin.require_auth();
        let stored_admin = Self::get_admin(&env)?;
        if admin != stored_admin {
            return Err(Error::Unauthorized);
        }

        let blend_pool_addr = Self::get_blend_pool(&env)?;
        let usdc_addr = Self::get_usdc_token(&env)?;
        let blend = blend_pool_client::Client::new(&env, &blend_pool_addr);
        let idx = blend.get_reserve(&usdc_addr).config.index;
        // reserve_token_id: dTokens = index*2, bTokens (supply/collateral) = index*2+1.
        let token_id = idx * 2 + 1;
        let me = env.current_contract_address();
        let claimed = blend.claim(&me, &vec![&env, token_id], &to);
        Ok(claimed)
    }

    // ── Lecturas puras (sin auth) ────────────────────────────────────────

    pub fn shares_of(env: Env, barrio_id: BytesN<32>) -> i128 {
        env.storage()
            .persistent()
            .get(&DataKey::Shares(barrio_id))
            .unwrap_or(0)
    }

    pub fn total_shares(env: Env) -> i128 {
        env.storage()
            .instance()
            .get(&DataKey::TotalShares)
            .unwrap_or(0)
    }

    /// Valor actual en USDC stroops: `shares × b_rate / 1e12`, con `b_rate`
    /// acumulado al ledger actual (`get_reserve` de Blend ya hace el accrual).
    pub fn value_of(env: Env, barrio_id: BytesN<32>) -> i128 {
        let shares: i128 = env
            .storage()
            .persistent()
            .get(&DataKey::Shares(barrio_id))
            .unwrap_or(0);
        if shares == 0 {
            return 0;
        }
        let blend_pool_addr: Address = match env.storage().instance().get(&DataKey::BlendPool) {
            Some(a) => a,
            None => return 0,
        };
        let usdc_addr: Address = match env.storage().instance().get(&DataKey::UsdcToken) {
            Some(a) => a,
            None => return 0,
        };
        let blend = blend_pool_client::Client::new(&env, &blend_pool_addr);
        let reserve = blend.get_reserve(&usdc_addr);
        shares * reserve.data.b_rate / SCALAR_12
    }

    /// APY estimado de supply (informativo), en basis points.
    ///
    /// Estimado ÚNICAMENTE desde `get_reserve` (curva de 3 pendientes de
    /// `calc_accrual`, verificada contra `pool/src/pool/interest.rs`) más el
    /// take del backstop vía `get_config().bstop_rate` (verificado contra
    /// `pool/src/storage.rs` con curl el 2026-07-31 — campos exactos:
    /// `oracle, min_collateral, bstop_rate, status, max_positions`).
    pub fn apy_hint(env: Env) -> u32 {
        let blend_pool_addr: Address = match env.storage().instance().get(&DataKey::BlendPool) {
            Some(a) => a,
            None => return 0,
        };
        let usdc_addr: Address = match env.storage().instance().get(&DataKey::UsdcToken) {
            Some(a) => a,
            None => return 0,
        };
        let blend = blend_pool_client::Client::new(&env, &blend_pool_addr);
        let reserve = blend.get_reserve(&usdc_addr);
        let cfg = reserve.config;
        let data = reserve.data;

        // Activos/pasivos en subyacente (stroops), no en bTokens/dTokens crudos.
        let total_b = data.b_supply * data.b_rate / SCALAR_12;
        let total_d = data.d_supply * data.d_rate / SCALAR_12;
        if total_b <= 0 {
            return 0;
        }
        let util = total_d * SCALAR_7 / total_b; // 7 decimales

        let target = (cfg.util as i128).max(1);
        let base_ir: i128 = if util <= target {
            cfg.r_base as i128 + (cfg.r_one as i128) * util / target
        } else if util <= 9_500_000 {
            let denom = (9_500_000 - target).max(1);
            cfg.r_base as i128 + cfg.r_one as i128 + (cfg.r_two as i128) * (util - target) / denom
        } else {
            let extra = (cfg.r_three as i128) * (util - 9_500_000) / 500_000;
            cfg.r_base as i128 + cfg.r_one as i128 + cfg.r_two as i128 + extra
        };

        let loan_apr = base_ir * data.ir_mod / SCALAR_7; // 7 dec
        let supply_apr_pre_bstop = loan_apr * util / SCALAR_7; // 7 dec

        let bstop_rate = blend.get_config().bstop_rate as i128; // 7 dec
        let supply_apr = supply_apr_pre_bstop * (SCALAR_7 - bstop_rate) / SCALAR_7;

        let bps = supply_apr * 10_000 / SCALAR_7;
        if bps <= 0 {
            0
        } else if bps > u32::MAX as i128 {
            u32::MAX
        } else {
            bps as u32
        }
    }

    // ── Internos ──────────────────────────────────────────────────────────

    fn get_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)
    }

    fn get_pool_contract(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::PoolContract)
            .ok_or(Error::NotInitialized)
    }

    fn get_blend_pool(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::BlendPool)
            .ok_or(Error::NotInitialized)
    }

    fn get_usdc_token(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::UsdcToken)
            .ok_or(Error::NotInitialized)
    }
}

mod test;
