#!/usr/bin/env bash
# RAÍZ · Deploy a Stellar Testnet
# --------------------------------
# Compila los 5 contratos a wasm (Pool, Governance, Treasury, Rewards,
# yield_adapter), referencia el USDC SAC de Blend, despliega los 5
# contratos, inicializa todos en el orden correcto, y guarda los IDs en
# deployments.json.
#
# Requisitos:
#   - Rust toolchain con target wasm32-unknown-unknown.
#   - Stellar CLI 23.x instalado en PATH.
#   - Conexión a internet (testnet + friendbot).

set -euo pipefail

NETWORK="${NETWORK:-testnet}"
IDENTITY="${IDENTITY:-raiz-admin}"
PROTOCOL_FEE_BPS="${PROTOCOL_FEE_BPS:-50}"
# F1: el Pool custodia el USDC de Blend (el que acepta el pool TestnetV2) en
# vez de un USDC propio. SAC, emisor clásico y pool de Blend — fijos en testnet.
USDC_SAC="${USDC_SAC:-CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU}"
USDC_ISSUER="${USDC_ISSUER:-GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56}"
# Blend v2 TestnetV2: pool prestamista con la reserva USDC (índice 3). El
# yield_adapter (BlendAdapter) habla directo con este pool — ya no hay vault
# intermediario (DeFindex, pre-F1).
BLEND_POOL="${BLEND_POOL:-CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF}"

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
# Todos los cross-contract calls del repo usan #[contractclient] declarado a
# mano (no `contractimport!`), así que no hace falta un build en dos pasos:
# `stellar contract build` compila los 5 crates al target wasm32v1-none, el
# único que acepta el host de Soroban.
stellar contract build

for crate in rewards governance treasury pool yield_adapter; do
    wasm="$WASM_DIR/$crate.wasm"
    if [[ ! -f "$wasm" ]]; then
        err "No se encontró $wasm"
        exit 1
    fi
    size=$(stat -c%s "$wasm" 2>/dev/null || stat -f%z "$wasm")
    log "  $crate.wasm — $size bytes"
done

# ── 3. USDC (Blend testnet) ────────────────────────────────────────────────
# F1: el Pool custodia el USDC de Blend (el que acepta el pool TestnetV2),
# NO un USDC propio. Ese SAC ya está desplegado en testnet; solo lo referenciamos.
USDC_ID="$USDC_SAC"
log "USDC (Blend) SAC: $USDC_ID  (emisor clásico USDC:$USDC_ISSUER)"
log "Blend pool (TestnetV2): $BLEND_POOL"

# ── 4. Deploy los 5 contratos ──────────────────────────────────────────────

deploy_contract() {
    local name="$1"
    local wasm="$WASM_DIR/$name.wasm"
    local out="" tries=0
    # Testnet RPC es flaky en deploys rápidos secuenciales → reintenta.
    while [[ $tries -lt 5 ]]; do
        out=$(stellar contract deploy \
            --wasm "$wasm" \
            --source-account "$IDENTITY" \
            --network "$NETWORK" 2>/dev/null | tail -1 | tr -d '[:space:]' || true)
        if [[ "$out" == C* && ${#out} -eq 56 ]]; then
            echo "$out"; return 0
        fi
        tries=$((tries + 1))
        warn "deploy $name reintento $tries/5..." >&2
        sleep 5 2>/dev/null || true
    done
    err "deploy de $name falló tras 5 intentos"
    return 1
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

log "Deploy yield_adapter (BlendAdapter)..."
YIELD_ADAPTER_ID=$(deploy_contract yield_adapter)
log "  yield_adapter: $YIELD_ADAPTER_ID"

# ── 5. Initialize en el orden correcto ─────────────────────────────────────
# Todos los contratos ya están desplegados (con Address propia) antes de que
# ninguno se inicialice, así que no hay problema de huevo-gallina entre
# Pool <-> yield_adapter: cada `initialize` solo necesita la Address del otro
# contrato (ya conocida), no que ya esté inicializado.
# Orden: Rewards (necesita pool_addr) → yield_adapter (necesita pool_addr) →
#        Pool (necesita yield_adapter_addr) → Governance → Treasury.

invoke() {
    local contract_id="$1"
    shift
    local tries=0
    while [[ $tries -lt 5 ]]; do
        if stellar contract invoke \
            --id "$contract_id" \
            --source-account "$IDENTITY" \
            --network "$NETWORK" \
            -- "$@"; then
            return 0
        fi
        tries=$((tries + 1))
        warn "invoke reintento $tries/5..." >&2
        sleep 5 2>/dev/null || true
    done
    err "invoke falló tras 5 intentos: $*"
    return 1
}

log "Initialize Rewards (admin=$IDENTITY, pool=$POOL_ID)..."
invoke "$REWARDS_ID" initialize \
    --admin "$ADMIN_ADDR" \
    --pool_contract "$POOL_ID"

log "Initialize yield_adapter (admin, pool=$POOL_ID, blend_pool=$BLEND_POOL, usdc)..."
invoke "$YIELD_ADAPTER_ID" initialize \
    --admin "$ADMIN_ADDR" \
    --pool_contract "$POOL_ID" \
    --blend_pool "$BLEND_POOL" \
    --usdc_token "$USDC_ID"

log "Initialize Pool (admin, usdc, rewards, fee=$PROTOCOL_FEE_BPS, yield_adapter=$YIELD_ADAPTER_ID)..."
invoke "$POOL_ID" initialize \
    --admin "$ADMIN_ADDR" \
    --usdc_token "$USDC_ID" \
    --rewards_contract "$REWARDS_ID" \
    --protocol_fee_bps "$PROTOCOL_FEE_BPS" \
    --yield_adapter "$YIELD_ADAPTER_ID"

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
  "yield_adapter": "$YIELD_ADAPTER_ID",
  "protocol_fee_bps": $PROTOCOL_FEE_BPS,
  "deployed_at": "$DEPLOYED_AT",
  "usdc_issuer": "$USDC_ISSUER",
  "blend_pool": "$BLEND_POOL"
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
echo "  Adapter:    https://stellar.expert/explorer/$NETWORK/contract/$YIELD_ADAPTER_ID"
echo "  USDC SAC:   https://stellar.expert/explorer/$NETWORK/contract/$USDC_ID"
echo "  Blend pool: https://stellar.expert/explorer/$NETWORK/contract/$BLEND_POOL"
echo "  Admin:      https://stellar.expert/explorer/$NETWORK/account/$ADMIN_ADDR"
