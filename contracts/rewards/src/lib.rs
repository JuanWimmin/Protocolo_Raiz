#![no_std]

//! RAÍZ · Contrato Rewards
//! ------------------------
//! Puntos no transferibles y catálogo de premios (artesanías).
//! Implementación pendiente — ver `docs/raiz_v2_spec_contratos.md` §Contrato 4.
//!
//! NOTA: el contrato Pool ya importa este crate vía contractimport en el lib.rs
//! actual para la llamada cross-contract `accrue_points`. Cuando implementemos
//! Rewards de verdad, esa importación se resuelve y el cross-contract call funciona.

use soroban_sdk::{contract, contractimpl, Address, Env};

#[contract]
pub struct RewardsContract;

#[contractimpl]
impl RewardsContract {
    /// Placeholder no-op. Lo llama Pool tras un pago con tip.
    /// Cuando implementemos el contrato de verdad: acumula puntos = tip / FACTOR.
    pub fn accrue_points(_env: Env, _tourist: Address, _tip_amount: i128) {}
}
