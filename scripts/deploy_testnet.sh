#!/usr/bin/env bash
# RAÍZ · Deploy a Stellar Testnet
# --------------------------------
# Compila los 4 contratos a wasm, despliega un USDC SAC (USDC:raiz-admin),
# despliega los 4 contratos, inicializa todos en el orden correcto, y guarda
# los IDs en deployments.json.
#
# Requisitos:
#   - Rust toolchain con target wasm32-unknown-unknown.
#   - Stellar CLI 23.x instalado en PATH.
#   - Conexión a internet (testnet + friendbot).

set -euo pipefail

NETWORK="${NETWORK:-testnet}"
IDENTITY="${IDENTITY:-raiz-admin}"
PROTOCOL_FEE_BPS="${PROTOCOL_FEE_BPS:-50}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACTS_DIR="$ROOT_DIR/contracts"
# `stellar contract build` usa target `wasm32v1-none` (no `wasm32-unknown-unknown`),
# que genera wasm sin instrucciones reference-types — necesario para Soroban.
WASM_DIR="$CONTRACTS_DIR/target/wasm32v1-none/release"
DEPLOYMENTS_JSON="$ROOT_DIR/deployments.json"

GREEN="\033[0;32m"
YELLOW="\033[0;33m"
RED="\033[0;31m"
NC="\033[0m"

log()  { echo -e "${GREEN}→${NC} $*"; }
warn() { echo -e "${YELLOW}!${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*" >&2; }

# ── 1. Identidad admin ─────────────────────────────────────────────────────

log "Verificando identidad '$IDENTITY'..."
if ! stellar keys address "$IDENTITY" >/dev/null 2>&1; then
    log "Identidad no existe. Generando + fundeando en $NETWORK..."
    stellar keys generate "$IDENTITY" --fund --network "$NETWORK"
fi
ADMIN_ADDR=$(stellar keys address "$IDENTITY")
log "Admin: $ADMIN_ADDR"

# ── 2. Compilar wasm ───────────────────────────────────────────────────────

log "Compilando wasm (stellar contract build → wasm32v1-none)..."
cd "$CONTRACTS_DIR"
# Para el `contractimport!` del Pool (target wasm32-unknown-unknown) necesita
# el wasm de rewards también en ese target — compila aparte primero.
cargo build --release --target wasm32-unknown-unknown -p rewards
# `stellar contract build` recompila al target wasm32v1-none que es el que
# acepta el host de Soroban.
stellar contract build

for crate in rewards governance treasury pool; do
    wasm="$WASM_DIR/$crate.wasm"
    if [[ ! -f "$wasm" ]]; then
        err "No se encontró $wasm"
        exit 1
    fi
    size=$(stat -c%s "$wasm" 2>/dev/null || stat -f%z "$wasm")
    log "  $crate.wasm — $size bytes"
done

# ── 3. Deploy USDC SAC ─────────────────────────────────────────────────────

# El id del SAC es determinístico desde el asset, no hace falta consultarlo
# después del deploy. Calculamos el id, luego desplegamos (si ya existe,
# stellar contract asset deploy retorna error que ignoramos con || true).
log "Calculando USDC SAC id (asset USDC:$ADMIN_ADDR)..."
USDC_ID=$(stellar contract id asset \
    --asset "USDC:$ADMIN_ADDR" \
    --network "$NETWORK" 2>&1 | grep -E '^C[A-Z0-9]+$' | tail -1)
log "USDC SAC esperado: $USDC_ID"

log "Desplegando SAC si no existe..."
stellar contract asset deploy \
    --asset "USDC:$ADMIN_ADDR" \
    --source-account "$IDENTITY" \
    --network "$NETWORK" >/dev/null 2>&1 || warn "Asset deploy: ya existía (OK)"

# ── 4. Deploy los 4 contratos ──────────────────────────────────────────────

deploy_contract() {
    local name="$1"
    local wasm="$WASM_DIR/$name.wasm"
    stellar contract deploy \
        --wasm "$wasm" \
        --source-account "$IDENTITY" \
        --network "$NETWORK" 2>&1 | tail -1 | tr -d '[:space:]'
}

log "Deploy Pool..."
POOL_ID=$(deploy_contract pool)
log "  Pool: $POOL_ID"

log "Deploy Governance..."
GOVERNANCE_ID=$(deploy_contract governance)
log "  Governance: $GOVERNANCE_ID"

log "Deploy Treasury..."
TREASURY_ID=$(deploy_contract treasury)
log "  Treasury: $TREASURY_ID"

log "Deploy Rewards..."
REWARDS_ID=$(deploy_contract rewards)
log "  Rewards: $REWARDS_ID"

# ── 5. Initialize en el orden correcto ─────────────────────────────────────
# Orden: Rewards (necesita pool_addr) → Pool → Governance → Treasury.

invoke() {
    local contract_id="$1"
    shift
    stellar contract invoke \
        --id "$contract_id" \
        --source-account "$IDENTITY" \
        --network "$NETWORK" \
        -- "$@"
}

log "Initialize Rewards (admin=$IDENTITY, pool=$POOL_ID)..."
invoke "$REWARDS_ID" initialize \
    --admin "$ADMIN_ADDR" \
    --pool_contract "$POOL_ID"

log "Initialize Pool (admin, usdc, rewards, fee=$PROTOCOL_FEE_BPS)..."
invoke "$POOL_ID" initialize \
    --admin "$ADMIN_ADDR" \
    --usdc_token "$USDC_ID" \
    --rewards_contract "$REWARDS_ID" \
    --protocol_fee_bps "$PROTOCOL_FEE_BPS"

log "Initialize Governance (protocol_admin, treasury=$TREASURY_ID)..."
invoke "$GOVERNANCE_ID" initialize \
    --protocol_admin "$ADMIN_ADDR" \
    --treasury_contract "$TREASURY_ID"

log "Initialize Treasury (pool, governance)..."
invoke "$TREASURY_ID" initialize \
    --pool_contract "$POOL_ID" \
    --governance_contract "$GOVERNANCE_ID"

# ── 6. Guardar deployments.json ────────────────────────────────────────────

DEPLOYED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "$DEPLOYMENTS_JSON" <<EOF
{
  "network": "$NETWORK",
  "admin": "$ADMIN_ADDR",
  "admin_identity": "$IDENTITY",
  "usdc_sac": "$USDC_ID",
  "pool": "$POOL_ID",
  "governance": "$GOVERNANCE_ID",
  "treasury": "$TREASURY_ID",
  "rewards": "$REWARDS_ID",
  "protocol_fee_bps": $PROTOCOL_FEE_BPS,
  "deployed_at": "$DEPLOYED_AT"
}
EOF
log "deployments.json escrito:"
cat "$DEPLOYMENTS_JSON"
echo

log "Stellar Expert:"
echo "  Pool:       https://stellar.expert/explorer/$NETWORK/contract/$POOL_ID"
echo "  Governance: https://stellar.expert/explorer/$NETWORK/contract/$GOVERNANCE_ID"
echo "  Treasury:   https://stellar.expert/explorer/$NETWORK/contract/$TREASURY_ID"
echo "  Rewards:    https://stellar.expert/explorer/$NETWORK/contract/$REWARDS_ID"
echo "  USDC SAC:   https://stellar.expert/explorer/$NETWORK/contract/$USDC_ID"
echo "  Admin:      https://stellar.expert/explorer/$NETWORK/account/$ADMIN_ADDR"
