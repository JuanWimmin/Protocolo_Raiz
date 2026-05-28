#![no_std]

//! RAÍZ · Contrato Governance
//! ---------------------------
//! Soulbound tokens de residencia + propuestas + votación. Es el órgano
//! democrático del barrio: solo residentes verificados pueden crear propuestas
//! y votar. El Treasury consulta el resultado y ejecuta trustless si pasa.
//!
//! Reglas clave (ver `docs/raiz_v2_spec_contratos.md` §Contrato 2):
//!   - Soulbound: NO existe función `transfer`. Un residente nunca puede cederlo.
//!   - 1 residente = 1 voto. No se permite re-mint al mismo Address.
//!   - Quórum: (votes_for + votes_against) ≥ 30% del ResidentCount del barrio.
//!   - Mayoría simple: votes_for > votes_against.
//!   - Duración de propuesta: configurable, entre 3 y 14 días.

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
    BarrioAdminNotSet = 4,
    AlreadyResident = 5,
    NotAResident = 6,
    ProposalNotFound = 7,
    InvalidDuration = 8,
    InvalidAmount = 9,
    AlreadyVoted = 10,
    ProposalClosed = 11,
    ProposalNotActive = 12,
}

// ─────────────────────────────────────────────────────────────────────────────
// Tipos
// ─────────────────────────────────────────────────────────────────────────────

/// Soulbound — sin campo `transferable` y sin función transfer. Es por diseño.
#[contracttype]
#[derive(Clone)]
pub struct ResidentToken {
    pub resident: Address,
    pub barrio_id: BytesN<32>,
    pub issued_at: u64, // ledger timestamp en segundos unix
}

#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProposalStatus {
    Active,
    Passed,
    Rejected,
    Executed,
}

#[contracttype]
#[derive(Clone)]
pub struct Proposal {
    pub id: u64,
    pub barrio_id: BytesN<32>,
    pub proposer: Address,
    pub description: String,
    pub amount: i128,
    pub recipient: Address,
    pub votes_for: u32,
    pub votes_against: u32,
    pub created_at: u64,
    pub closes_at: u64,
    pub status: ProposalStatus,
}

#[contracttype]
#[derive(Clone)]
pub enum DataKey {
    // Globales (extensiones a la spec, necesarias para gobernar quién registra
    // admins de barrio y quién puede marcar propuestas como Executed)
    ProtocolAdmin,
    TreasuryContract,
    // Por barrio / propuesta (tal cual spec)
    Admin(BytesN<32>),               // barrio_id -> Address admin del barrio
    Resident(Address),               // residente -> ResidentToken
    Proposal(u64),                   // proposal_id -> Proposal
    ProposalCount,                   // contador global de propuestas
    ResidentCount(BytesN<32>),       // barrio_id -> número de residentes (quórum)
    BarrioProposals(BytesN<32>),     // barrio_id -> Vec<u64> proposal ids (para listar)
    Vote(u64, Address),              // (proposal_id, resident) -> bool
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

const MIN_DURATION_DAYS: u32 = 3;
const MAX_DURATION_DAYS: u32 = 14;
const SECONDS_PER_DAY: u64 = 86_400;
const QUORUM_PCT: u32 = 30;

#[contract]
pub struct GovernanceContract;

#[contractimpl]
impl GovernanceContract {
    /// Inicializa el contrato. Solo se llama una vez.
    pub fn initialize(
        env: Env,
        protocol_admin: Address,
        treasury_contract: Address,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::ProtocolAdmin) {
            return Err(Error::AlreadyInitialized);
        }
        protocol_admin.require_auth();
        env.storage()
            .instance()
            .set(&DataKey::ProtocolAdmin, &protocol_admin);
        env.storage()
            .instance()
            .set(&DataKey::TreasuryContract, &treasury_contract);
        env.storage()
            .persistent()
            .set(&DataKey::ProposalCount, &0u64);
        Ok(())
    }

    /// Registra el admin de un barrio. Solo el protocol_admin puede hacerlo.
    pub fn set_barrio_admin(
        env: Env,
        barrio_id: BytesN<32>,
        barrio_admin: Address,
    ) -> Result<(), Error> {
        let protocol_admin = Self::get_protocol_admin(&env)?;
        protocol_admin.require_auth();
        env.storage()
            .persistent()
            .set(&DataKey::Admin(barrio_id.clone()), &barrio_admin);
        // Inicializa el contador de residentes del barrio si no existe.
        let count_key = DataKey::ResidentCount(barrio_id);
        if !env.storage().persistent().has(&count_key) {
            env.storage().persistent().set(&count_key, &0u32);
        }
        Ok(())
    }

    /// Mintea un soulbound token de residente. Solo el admin del barrio.
    /// No permite re-mint al mismo Address (1 residente = 1 voto).
    pub fn mint_resident(
        env: Env,
        barrio_admin: Address,
        resident: Address,
        barrio_id: BytesN<32>,
    ) -> Result<(), Error> {
        barrio_admin.require_auth();

        let stored_admin: Address = env
            .storage()
            .persistent()
            .get(&DataKey::Admin(barrio_id.clone()))
            .ok_or(Error::BarrioAdminNotSet)?;
        if barrio_admin != stored_admin {
            return Err(Error::Unauthorized);
        }

        // 1 residente = 1 voto: si ya tiene token, falla.
        if env
            .storage()
            .persistent()
            .has(&DataKey::Resident(resident.clone()))
        {
            return Err(Error::AlreadyResident);
        }

        let token = ResidentToken {
            resident: resident.clone(),
            barrio_id: barrio_id.clone(),
            issued_at: env.ledger().timestamp(),
        };
        env.storage()
            .persistent()
            .set(&DataKey::Resident(resident.clone()), &token);

        let count_key = DataKey::ResidentCount(barrio_id.clone());
        let count: u32 = env.storage().persistent().get(&count_key).unwrap_or(0);
        env.storage().persistent().set(&count_key, &(count + 1));

        env.events().publish(
            (symbol_short!("resident"), barrio_id),
            resident,
        );
        Ok(())
    }

    /// Crea una propuesta. Solo residentes verificados del barrio pueden hacerlo.
    pub fn create_proposal(
        env: Env,
        proposer: Address,
        barrio_id: BytesN<32>,
        description: String,
        amount: i128,
        recipient: Address,
        duration_days: u32,
    ) -> Result<u64, Error> {
        proposer.require_auth();

        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        if duration_days < MIN_DURATION_DAYS || duration_days > MAX_DURATION_DAYS {
            return Err(Error::InvalidDuration);
        }

        // El proposer debe ser residente DEL BARRIO en cuestión.
        let token: ResidentToken = env
            .storage()
            .persistent()
            .get(&DataKey::Resident(proposer.clone()))
            .ok_or(Error::NotAResident)?;
        if token.barrio_id != barrio_id {
            return Err(Error::NotAResident);
        }

        let now = env.ledger().timestamp();
        let id: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::ProposalCount)
            .unwrap_or(0);

        let proposal = Proposal {
            id,
            barrio_id: barrio_id.clone(),
            proposer: proposer.clone(),
            description,
            amount,
            recipient,
            votes_for: 0,
            votes_against: 0,
            created_at: now,
            closes_at: now + (duration_days as u64) * SECONDS_PER_DAY,
            status: ProposalStatus::Active,
        };
        env.storage()
            .persistent()
            .set(&DataKey::Proposal(id), &proposal);
        env.storage()
            .persistent()
            .set(&DataKey::ProposalCount, &(id + 1));

        // Índice por barrio
        let index_key = DataKey::BarrioProposals(barrio_id.clone());
        let mut index: Vec<u64> = env
            .storage()
            .persistent()
            .get(&index_key)
            .unwrap_or(Vec::new(&env));
        index.push_back(id);
        env.storage().persistent().set(&index_key, &index);

        env.events()
            .publish((symbol_short!("proposal"), barrio_id), (id, proposer));
        Ok(id)
    }

    /// Vota una propuesta. El residente debe pertenecer al barrio de la propuesta.
    pub fn vote(
        env: Env,
        resident: Address,
        proposal_id: u64,
        support: bool,
    ) -> Result<(), Error> {
        resident.require_auth();

        let mut proposal: Proposal = env
            .storage()
            .persistent()
            .get(&DataKey::Proposal(proposal_id))
            .ok_or(Error::ProposalNotFound)?;

        if proposal.status != ProposalStatus::Active {
            return Err(Error::ProposalNotActive);
        }
        if env.ledger().timestamp() >= proposal.closes_at {
            return Err(Error::ProposalClosed);
        }

        // Verifica residencia en el barrio de la propuesta.
        let token: ResidentToken = env
            .storage()
            .persistent()
            .get(&DataKey::Resident(resident.clone()))
            .ok_or(Error::NotAResident)?;
        if token.barrio_id != proposal.barrio_id {
            return Err(Error::NotAResident);
        }

        // No doble voto.
        let vote_key = DataKey::Vote(proposal_id, resident.clone());
        if env.storage().persistent().has(&vote_key) {
            return Err(Error::AlreadyVoted);
        }
        env.storage().persistent().set(&vote_key, &support);

        if support {
            proposal.votes_for += 1;
        } else {
            proposal.votes_against += 1;
        }
        env.storage()
            .persistent()
            .set(&DataKey::Proposal(proposal_id), &proposal);

        env.events().publish(
            (symbol_short!("vote"), proposal.barrio_id.clone()),
            (proposal_id, resident, support),
        );
        Ok(())
    }

    /// Calcula el resultado de una propuesta. Idempotente: si ya está
    /// Passed/Rejected/Executed, devuelve ese estado sin modificar nada.
    /// Si la propuesta aún está abierta (now < closes_at), devuelve Active.
    pub fn tally(env: Env, proposal_id: u64) -> Result<ProposalStatus, Error> {
        let mut proposal: Proposal = env
            .storage()
            .persistent()
            .get(&DataKey::Proposal(proposal_id))
            .ok_or(Error::ProposalNotFound)?;

        // Si ya fue calculada antes, devuélvela tal cual.
        if proposal.status != ProposalStatus::Active {
            return Ok(proposal.status);
        }
        // Aún no cierra: sigue activa.
        if env.ledger().timestamp() < proposal.closes_at {
            return Ok(ProposalStatus::Active);
        }

        // Cerró: calcula quórum + mayoría simple.
        let resident_count: u32 = env
            .storage()
            .persistent()
            .get(&DataKey::ResidentCount(proposal.barrio_id.clone()))
            .unwrap_or(0);
        let total_votes = proposal.votes_for + proposal.votes_against;
        // (total * 100) >= QUORUM_PCT * resident_count — evita división entera.
        let reached_quorum =
            resident_count > 0 && (total_votes as u64) * 100 >= (QUORUM_PCT as u64) * (resident_count as u64);

        let new_status = if reached_quorum && proposal.votes_for > proposal.votes_against {
            ProposalStatus::Passed
        } else {
            ProposalStatus::Rejected
        };

        proposal.status = new_status.clone();
        env.storage()
            .persistent()
            .set(&DataKey::Proposal(proposal_id), &proposal);

        env.events().publish(
            (symbol_short!("tally"), proposal.barrio_id.clone()),
            (proposal_id, new_status.clone()),
        );
        Ok(new_status)
    }

    /// Marca una propuesta como Executed. Solo el contrato Treasury puede llamar.
    /// Debe ser llamado después de `tally` que devolvió Passed.
    pub fn mark_executed(env: Env, treasury_caller: Address, proposal_id: u64) -> Result<(), Error> {
        treasury_caller.require_auth();
        let stored_treasury: Address = env
            .storage()
            .instance()
            .get(&DataKey::TreasuryContract)
            .ok_or(Error::NotInitialized)?;
        if treasury_caller != stored_treasury {
            return Err(Error::Unauthorized);
        }

        let mut proposal: Proposal = env
            .storage()
            .persistent()
            .get(&DataKey::Proposal(proposal_id))
            .ok_or(Error::ProposalNotFound)?;

        if proposal.status != ProposalStatus::Passed {
            return Err(Error::ProposalNotActive);
        }
        proposal.status = ProposalStatus::Executed;
        env.storage()
            .persistent()
            .set(&DataKey::Proposal(proposal_id), &proposal);
        Ok(())
    }

    // ── Lecturas ──────────────────────────────────────────────────────────

    pub fn get_proposal(env: Env, proposal_id: u64) -> Result<Proposal, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Proposal(proposal_id))
            .ok_or(Error::ProposalNotFound)
    }

    pub fn get_resident(env: Env, resident: Address) -> Result<ResidentToken, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Resident(resident))
            .ok_or(Error::NotAResident)
    }

    pub fn get_resident_count(env: Env, barrio_id: BytesN<32>) -> u32 {
        env.storage()
            .persistent()
            .get(&DataKey::ResidentCount(barrio_id))
            .unwrap_or(0)
    }

    pub fn list_active_proposals(env: Env, barrio_id: BytesN<32>) -> Vec<Proposal> {
        let ids: Vec<u64> = env
            .storage()
            .persistent()
            .get(&DataKey::BarrioProposals(barrio_id))
            .unwrap_or(Vec::new(&env));
        let mut out: Vec<Proposal> = Vec::new(&env);
        for id in ids.iter() {
            if let Some(p) = env
                .storage()
                .persistent()
                .get::<DataKey, Proposal>(&DataKey::Proposal(id))
            {
                if p.status == ProposalStatus::Active {
                    out.push_back(p);
                }
            }
        }
        out
    }

    // ── Internos ──────────────────────────────────────────────────────────

    fn get_protocol_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::ProtocolAdmin)
            .ok_or(Error::NotInitialized)
    }
}

#[cfg(test)]
mod test;
