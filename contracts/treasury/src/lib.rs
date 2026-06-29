#![no_std]

//! RAÍZ · Contrato Treasury
//! -------------------------
//! Ejecución trustless de propuestas aprobadas. Cualquiera puede llamar
//! `execute_proposal`: si Governance dice que pasó, Treasury orquesta:
//!
//!   1. Tally en Governance → debe ser Passed.
//!   2. Pide los detalles (amount, recipient, barrio_id) a Governance.
//!   3. Llama a Pool.withdraw_to para transferir USDC del pool al recipient.
//!   4. Registra una Execution auditable.
//!   5. Marca la propuesta como Executed en Governance.
//!   6. Emite evento `execution`.
//!
//! Ver `docs/raiz_v2_spec_contratos.md` §Contrato 3.

use soroban_sdk::{
    contract, contracterror, contractclient, contractimpl, contracttype, symbol_short, Address,
    Bytes, BytesN, Env, Vec,
};

// ─────────────────────────────────────────────────────────────────────────────
// Clientes cross-contract (interfaces declaradas a mano)
// ─────────────────────────────────────────────────────────────────────────────
// Mantener los tipos en sync con `contracts/governance/src/lib.rs`.
// Si Governance cambia la firma de tally/get_proposal/mark_executed o el
// struct Proposal, hay que actualizar aquí. El spec-auditor lo flageará.

mod governance_client {
    use soroban_sdk::{contractclient, contracttype, Address, BytesN, Env, String};

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

    // `#[contractclient]` consume el trait para generar el Client; el trait
    // queda como contrato lógico pero no se usa más → suprimimos dead_code.
    #[allow(dead_code)]
    #[contractclient(name = "GovernanceClient")]
    pub trait Governance {
        fn tally(env: Env, proposal_id: u64) -> ProposalStatus;
        fn get_proposal(env: Env, proposal_id: u64) -> Proposal;
        fn mark_executed(env: Env, treasury_caller: Address, proposal_id: u64);
    }
}

#[allow(dead_code)]
#[contractclient(name = "PoolClient")]
pub trait Pool {
    fn withdraw_to(
        env: Env,
        caller: Address,
        barrio_id: BytesN<32>,
        recipient: Address,
        amount: i128,
    );
    /// Lee las shares del vault que tiene este barrio (sin requerir auth).
    fn get_vault_shares(env: Env, barrio_id: BytesN<32>) -> i128;
    /// Rescata todas las shares del vault de vuelta al pool_balance.
    /// Solo puede llamar el admin o el treasury del barrio — Treasury pasa su propia Address.
    fn redeem_from_vault(env: Env, caller: Address, barrio_id: BytesN<32>, shares: i128);
}

// ─────────────────────────────────────────────────────────────────────────────
// Errores
// ─────────────────────────────────────────────────────────────────────────────

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
#[repr(u32)]
pub enum Error {
    NotInitialized = 1,
    AlreadyInitialized = 2,
    ProposalNotPassed = 3,
}

// ─────────────────────────────────────────────────────────────────────────────
// Tipos
// ─────────────────────────────────────────────────────────────────────────────

#[contracttype]
#[derive(Clone)]
pub struct Execution {
    pub proposal_id: u64,
    pub barrio_id: BytesN<32>,
    pub amount: i128,
    pub recipient: Address,
    pub executed_at: u64,
    pub tx_hash: BytesN<32>,
}

#[contracttype]
#[derive(Clone)]
pub enum DataKey {
    PoolContract,
    GovernanceContract,
    Execution(u64),
    ExecutionCount(BytesN<32>),     // contador por barrio (spec)
    BarrioExecutions(BytesN<32>),   // índice execution_ids por barrio (para listar)
    TotalExecutions,                // contador global de execution_ids
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato
// ─────────────────────────────────────────────────────────────────────────────

#[contract]
pub struct TreasuryContract;

#[contractimpl]
impl TreasuryContract {
    /// Inicializa el contrato apuntando a Pool y Governance.
    pub fn initialize(
        env: Env,
        pool_contract: Address,
        governance_contract: Address,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::PoolContract) {
            return Err(Error::AlreadyInitialized);
        }
        env.storage()
            .instance()
            .set(&DataKey::PoolContract, &pool_contract);
        env.storage()
            .instance()
            .set(&DataKey::GovernanceContract, &governance_contract);
        env.storage()
            .persistent()
            .set(&DataKey::TotalExecutions, &0u64);
        Ok(())
    }

    /// Ejecuta una propuesta aprobada. Trustless: cualquiera puede llamar.
    /// Si Governance dice que pasó, Treasury orquesta el resto.
    pub fn execute_proposal(env: Env, proposal_id: u64) -> Result<(), Error> {
        let gov_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::GovernanceContract)
            .ok_or(Error::NotInitialized)?;
        let pool_addr: Address = env
            .storage()
            .instance()
            .get(&DataKey::PoolContract)
            .ok_or(Error::NotInitialized)?;

        let governance = governance_client::GovernanceClient::new(&env, &gov_addr);

        // 1. Tally — solo si pasó podemos ejecutar.
        let status = governance.tally(&proposal_id);
        if status != governance_client::ProposalStatus::Passed {
            return Err(Error::ProposalNotPassed);
        }

        // 2. Carga los detalles de la propuesta.
        let proposal = governance.get_proposal(&proposal_id);

        // 3. Si hay shares en el vault, rescatarlas primero para que el saldo
        //    idle del pool sea suficiente para el retiro. El yield queda en pool_balance.
        let treasury_addr = env.current_contract_address();
        let pool = PoolClient::new(&env, &pool_addr);
        let vault_shares = pool.get_vault_shares(&proposal.barrio_id);
        if vault_shares > 0 {
            // redeem_from_vault sube pool_balance += got, baja VaultShares = 0
            pool.redeem_from_vault(&treasury_addr, &proposal.barrio_id, &vault_shares);
        }

        // Ahora pool_balance >= vault_yield + idle anterior; ejecuta el retiro.
        pool.withdraw_to(
            &treasury_addr,
            &proposal.barrio_id,
            &proposal.recipient,
            &proposal.amount,
        );

        // 4. Registra la Execution.
        let exec_id: u64 = env
            .storage()
            .persistent()
            .get(&DataKey::TotalExecutions)
            .unwrap_or(0);
        let executed_at = env.ledger().timestamp();
        let tx_hash = Self::compute_tx_hash(&env, proposal_id, &proposal.barrio_id, executed_at);

        let execution = Execution {
            proposal_id,
            barrio_id: proposal.barrio_id.clone(),
            amount: proposal.amount,
            recipient: proposal.recipient.clone(),
            executed_at,
            tx_hash,
        };
        env.storage()
            .persistent()
            .set(&DataKey::Execution(exec_id), &execution);
        env.storage()
            .persistent()
            .set(&DataKey::TotalExecutions, &(exec_id + 1));

        // Contador e índice por barrio
        let count_key = DataKey::ExecutionCount(proposal.barrio_id.clone());
        let count: u64 = env.storage().persistent().get(&count_key).unwrap_or(0);
        env.storage().persistent().set(&count_key, &(count + 1));

        let index_key = DataKey::BarrioExecutions(proposal.barrio_id.clone());
        let mut index: Vec<u64> = env
            .storage()
            .persistent()
            .get(&index_key)
            .unwrap_or(Vec::new(&env));
        index.push_back(exec_id);
        env.storage().persistent().set(&index_key, &index);

        // 5. Marca la propuesta como Executed en Governance.
        governance.mark_executed(&treasury_addr, &proposal_id);

        // 6. Evento auditable.
        env.events().publish(
            (symbol_short!("execution"), proposal.barrio_id.clone()),
            (proposal_id, proposal.amount, proposal.recipient),
        );

        Ok(())
    }

    /// Devuelve todas las ejecuciones de un barrio (para el dashboard).
    pub fn get_execution_log(env: Env, barrio_id: BytesN<32>) -> Vec<Execution> {
        let ids: Vec<u64> = env
            .storage()
            .persistent()
            .get(&DataKey::BarrioExecutions(barrio_id))
            .unwrap_or(Vec::new(&env));
        let mut out: Vec<Execution> = Vec::new(&env);
        for id in ids.iter() {
            if let Some(e) = env
                .storage()
                .persistent()
                .get::<DataKey, Execution>(&DataKey::Execution(id))
            {
                out.push_back(e);
            }
        }
        out
    }

    pub fn get_execution(env: Env, execution_id: u64) -> Option<Execution> {
        env.storage()
            .persistent()
            .get(&DataKey::Execution(execution_id))
    }

    pub fn get_execution_count(env: Env, barrio_id: BytesN<32>) -> u64 {
        env.storage()
            .persistent()
            .get(&DataKey::ExecutionCount(barrio_id))
            .unwrap_or(0)
    }

    // ── Internos ──────────────────────────────────────────────────────────

    /// Hash determinístico auditable: sha256(proposal_id || barrio_id || executed_at).
    /// No es el tx hash real de Stellar (ese solo lo conoce el cliente off-chain),
    /// pero es único por ejecución y reproducible para comparar.
    fn compute_tx_hash(
        env: &Env,
        proposal_id: u64,
        barrio_id: &BytesN<32>,
        executed_at: u64,
    ) -> BytesN<32> {
        let mut preimage = Bytes::new(env);
        preimage.append(&Bytes::from_array(env, &proposal_id.to_be_bytes()));
        preimage.append(&Bytes::from_array(env, &barrio_id.to_array()));
        preimage.append(&Bytes::from_array(env, &executed_at.to_be_bytes()));
        env.crypto().sha256(&preimage).into()
    }
}

#[cfg(test)]
mod test;
