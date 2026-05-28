#![no_std]

//! RAÍZ · Contrato Treasury
//! -------------------------
//! Ejecución trustless de propuestas aprobadas en Governance.
//! Implementación pendiente — ver `docs/raiz_v2_spec_contratos.md` §Contrato 3.

use soroban_sdk::{contract, contractimpl, Env};

#[contract]
pub struct TreasuryContract;

#[contractimpl]
impl TreasuryContract {
    pub fn ping(_env: Env) -> u32 {
        0
    }
}
