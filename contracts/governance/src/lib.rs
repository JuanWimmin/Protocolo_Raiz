#![no_std]

//! RAÍZ · Contrato Governance
//! ---------------------------
//! Soulbound tokens de residencia + votación de propuestas.
//! Implementación pendiente — ver `docs/raiz_v2_spec_contratos.md` §Contrato 2.

use soroban_sdk::{contract, contractimpl, Env};

#[contract]
pub struct GovernanceContract;

#[contractimpl]
impl GovernanceContract {
    pub fn ping(_env: Env) -> u32 {
        0
    }
}
