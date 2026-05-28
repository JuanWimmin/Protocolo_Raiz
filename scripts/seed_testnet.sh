#!/usr/bin/env bash
# RAÍZ · Seed de testnet
# -----------------------
# Pobla los 4 contratos desplegados con datos demo: 3 barrios, comercios con
# lat/lng reales, residentes con soulbound, pagos con tip, propuestas activas,
# y rewards (artesanías).
#
# Requisitos: deployments.json existe (correr deploy_testnet.sh primero).

set -uo pipefail
# OJO: NO `set -e` global — muchas operaciones son idempotentes y queremos
# que el script siga aunque alguna falle (p.ej. trustline ya existe).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOYMENTS="$ROOT_DIR/deployments.json"
NETWORK="testnet"
ADMIN="raiz-admin"
TOURIST="raiz-tourist"

if [[ ! -f "$DEPLOYMENTS" ]]; then
    echo "✗ deployments.json no existe. Corre scripts/deploy_testnet.sh primero." >&2
    exit 1
fi

# jq es opcional en Windows. Usamos Node leyendo por stdin para evitar
# problemas con paths estilo /c/... de Git Bash.
get_field() {
    cat "$DEPLOYMENTS" | node -e "let d=''; process.stdin.on('data',c=>d+=c); process.stdin.on('end',()=>console.log(JSON.parse(d).$1))"
}
POOL=$(get_field pool)
GOVERNANCE=$(get_field governance)
REWARDS=$(get_field rewards)
TREASURY=$(get_field treasury)
USDC=$(get_field usdc_sac)
ADMIN_ADDR=$(get_field admin)
TOURIST_ADDR=$(stellar keys address "$TOURIST" 2>/dev/null || echo "")

if [[ -z "$POOL" || -z "$GOVERNANCE" || -z "$REWARDS" ]]; then
    echo "✗ No se pudieron leer los IDs de $DEPLOYMENTS" >&2
    exit 1
fi
log "Contratos: pool=$POOL governance=$GOVERNANCE rewards=$REWARDS treasury=$TREASURY"

GREEN="\033[0;32m"
YELLOW="\033[0;33m"
NC="\033[0m"
log()  { echo -e "${GREEN}→${NC} $*"; }
warn() { echo -e "${YELLOW}!${NC} $*"; }

invoke() {
    local contract_id="$1"
    local source="$2"
    shift 2
    local output
    output=$(stellar contract invoke \
        --id "$contract_id" \
        --source-account "$source" \
        --network "$NETWORK" \
        -- "$@" 2>&1)
    local rc=$?
    if [[ $rc -ne 0 ]]; then
        warn "invoke falló (rc=$rc): $*"
        echo "$output" | tail -5 >&2
        return $rc
    fi
    echo "$output" | tail -1
}

# ── 0. Setup turista demo ──────────────────────────────────────────────────

if [[ -z "$TOURIST_ADDR" ]]; then
    log "Creando identidad turista demo..."
    stellar keys generate "$TOURIST" --fund --network "$NETWORK"
    TOURIST_ADDR=$(stellar keys address "$TOURIST")
fi
log "Turista demo: $TOURIST_ADDR"

log "Asegurando trustline turista → USDC..."
stellar tx new change-trust \
    --source-account "$TOURIST" \
    --line "USDC:$ADMIN_ADDR" \
    --network "$NETWORK" >/dev/null 2>&1 || warn "(trustline ya existía)"

log "Minteando 5000 USDC al turista demo..."
invoke "$USDC" "$ADMIN" mint --to "$TOURIST_ADDR" --amount 50000000000 >/dev/null \
    || warn "(mint falló o ya tenía saldo)"

# ── 1. Helpers: crear merchant y resident ──────────────────────────────────

create_account_with_trustline() {
    # crea key con --fund + establece trustline USDC. Retorna el address.
    # Idempotente: si la key ya existe, intenta el trustline igual (puede haber
    # quedado a medias por una corrida fallida).
    local alias="$1"
    if ! stellar keys address "$alias" >/dev/null 2>&1; then
        stellar keys generate "$alias" --fund --network "$NETWORK" >/dev/null 2>&1
    fi
    # Trustline siempre (idempotente: si ya existe, falla silenciosamente).
    stellar tx new change-trust \
        --source-account "$alias" \
        --line "USDC:$ADMIN_ADDR" \
        --network "$NETWORK" >/dev/null 2>&1 || true
    stellar keys address "$alias"
}

create_account_no_trust() {
    local alias="$1"
    if ! stellar keys address "$alias" >/dev/null 2>&1; then
        stellar keys generate "$alias" --fund --network "$NETWORK" >/dev/null 2>&1
    fi
    stellar keys address "$alias"
}

register_merchant_call() {
    local pool_id="$1"
    local merchant_addr="$2"
    local name="$3"
    local barrio_id="$4"
    local lat_e6="$5"
    local lng_e6="$6"
    local category="$7"
    # MerchantData es struct — se pasa como JSON inline en --data.
    invoke "$pool_id" "$ADMIN" register_merchant \
        --data "{\"address\":\"$merchant_addr\",\"name\":\"$name\",\"barrio_id\":\"$barrio_id\",\"verified\":true,\"lat_e6\":$lat_e6,\"lng_e6\":$lng_e6,\"category\":\"$category\"}"
}

# ── 2. Tres barrios ────────────────────────────────────────────────────────
# Los barrio_id son hex de 32 bytes. Usamos patrones reconocibles.

BARRIO_CENTRO="ce47120000000000000000000000000000000000000000000000000000000001"
BARRIO_NORTE="bba17e0000000000000000000000000000000000000000000000000000000002"
BARRIO_COSTA="c057a900000000000000000000000000000000000000000000000000000000003" # 65 chars by mistake? no
# Reduce a 64 hex chars exactos:
BARRIO_COSTA="c057a9000000000000000000000000000000000000000000000000000000000a"

log "Creando 3 barrios..."
invoke "$POOL" "$ADMIN" register_barrio --id "$BARRIO_CENTRO" --name '"Centro Historico"' --treasury_contract "$TREASURY" >/dev/null
log "  ✓ Centro Historico ($BARRIO_CENTRO)"
invoke "$POOL" "$ADMIN" register_barrio --id "$BARRIO_NORTE"  --name '"Barrio Norte"'     --treasury_contract "$TREASURY" >/dev/null
log "  ✓ Barrio Norte ($BARRIO_NORTE)"
invoke "$POOL" "$ADMIN" register_barrio --id "$BARRIO_COSTA"  --name '"Costa Vieja"'      --treasury_contract "$TREASURY" >/dev/null
log "  ✓ Costa Vieja ($BARRIO_COSTA)"

# Governance: asigna admin del barrio (mismo raiz-admin para demo).
log "Asignando admin de barrios en Governance..."
invoke "$GOVERNANCE" "$ADMIN" set_barrio_admin --barrio_id "$BARRIO_CENTRO" --barrio_admin "$ADMIN_ADDR" >/dev/null
invoke "$GOVERNANCE" "$ADMIN" set_barrio_admin --barrio_id "$BARRIO_NORTE"  --barrio_admin "$ADMIN_ADDR" >/dev/null
invoke "$GOVERNANCE" "$ADMIN" set_barrio_admin --barrio_id "$BARRIO_COSTA"  --barrio_admin "$ADMIN_ADDR" >/dev/null

# ── 3. Comercios por barrio (lat/lng reales × 1e6) ─────────────────────────
# Centro Histórico (Cartagena: ~10.422, -75.548)
# Barrio Norte (Bogotá: ~4.668, -74.057)
# Costa Vieja (Cartagena: ~10.405, -75.535)

log "Registrando comercios..."

# Centro Histórico — nombres SIN comillas extra (la función las añade en el JSON)
M1=$(create_account_with_trustline "raiz-m-centro-1")
M2=$(create_account_with_trustline "raiz-m-centro-2")
M3=$(create_account_with_trustline "raiz-m-centro-3")
register_merchant_call "$POOL" "$M1" "Cafe Don Aurelio"        "$BARRIO_CENTRO" 10422100 -75547800 cafe        >/dev/null
register_merchant_call "$POOL" "$M2" "Mochilas del Centro"     "$BARRIO_CENTRO" 10421500 -75549100 artesania   >/dev/null
register_merchant_call "$POOL" "$M3" "Restaurante La Boveda"   "$BARRIO_CENTRO" 10423000 -75547200 restaurante >/dev/null
log "  ✓ Centro: 3 comercios"

# Barrio Norte
M4=$(create_account_with_trustline "raiz-m-norte-1")
M5=$(create_account_with_trustline "raiz-m-norte-2")
M6=$(create_account_with_trustline "raiz-m-norte-3")
register_merchant_call "$POOL" "$M4" "Cafe Bogota"             "$BARRIO_NORTE" 4668200 -74057300 cafe        >/dev/null
register_merchant_call "$POOL" "$M5" "Tienda La 93"            "$BARRIO_NORTE" 4677500 -74047800 tienda      >/dev/null
register_merchant_call "$POOL" "$M6" "Hospedaje Norte"         "$BARRIO_NORTE" 4669300 -74055100 hospedaje   >/dev/null
log "  ✓ Norte: 3 comercios"

# Costa Vieja
M7=$(create_account_with_trustline "raiz-m-costa-1")
M8=$(create_account_with_trustline "raiz-m-costa-2")
M9=$(create_account_with_trustline "raiz-m-costa-3")
register_merchant_call "$POOL" "$M7" "Pescaderia La Costa"    "$BARRIO_COSTA" 10405200 -75534800 restaurante >/dev/null
register_merchant_call "$POOL" "$M8" "Artesania Wayuu"        "$BARRIO_COSTA" 10406100 -75535200 artesania   >/dev/null
register_merchant_call "$POOL" "$M9" "Cafe Costa Vieja"       "$BARRIO_COSTA" 10404500 -75534100 cafe        >/dev/null
log "  ✓ Costa: 3 comercios"

# ── 4. Residentes con soulbound ────────────────────────────────────────────

log "Minteando soulbound a 9 residentes (3 por barrio)..."
for i in 1 2 3; do
    R=$(create_account_no_trust "raiz-r-centro-$i")
    invoke "$GOVERNANCE" "$ADMIN" mint_resident --barrio_admin "$ADMIN_ADDR" --resident "$R" --barrio_id "$BARRIO_CENTRO" >/dev/null
done
for i in 1 2 3; do
    R=$(create_account_no_trust "raiz-r-norte-$i")
    invoke "$GOVERNANCE" "$ADMIN" mint_resident --barrio_admin "$ADMIN_ADDR" --resident "$R" --barrio_id "$BARRIO_NORTE" >/dev/null
done
for i in 1 2 3; do
    R=$(create_account_no_trust "raiz-r-costa-$i")
    invoke "$GOVERNANCE" "$ADMIN" mint_resident --barrio_admin "$ADMIN_ADDR" --resident "$R" --barrio_id "$BARRIO_COSTA" >/dev/null
done
log "  ✓ 9 residentes minteados"

# ── 5. Pagos con tip (alimenta el pool de cada barrio) ─────────────────────

log "Simulando 6 pagos con tip (2%) — turista demo paga a comercios..."
# Pagos a Centro
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M1" --amount 50000000  --tip_bps 200 >/dev/null # 5 USDC
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M2" --amount 100000000 --tip_bps 200 >/dev/null # 10 USDC
log "  ✓ 2 pagos en Centro Histórico"
# Pagos a Norte
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M4" --amount 80000000  --tip_bps 200 >/dev/null # 8 USDC
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M5" --amount 30000000  --tip_bps 200 >/dev/null # 3 USDC
log "  ✓ 2 pagos en Barrio Norte"
# Pagos a Costa
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M7" --amount 120000000 --tip_bps 200 >/dev/null # 12 USDC
invoke "$POOL" "$TOURIST" pay_merchant --tourist "$TOURIST_ADDR" --merchant "$M8" --amount 70000000  --tip_bps 200 >/dev/null # 7 USDC
log "  ✓ 2 pagos en Costa Vieja"

# ── 6. Propuestas activas (1 por barrio) ───────────────────────────────────

log "Creando 3 propuestas activas..."
R_CENTRO_1=$(stellar keys address raiz-r-centro-1)
R_NORTE_1=$(stellar keys address raiz-r-norte-1)
R_COSTA_1=$(stellar keys address raiz-r-costa-1)

invoke "$GOVERNANCE" "raiz-r-centro-1" create_proposal \
    --proposer "$R_CENTRO_1" --barrio_id "$BARRIO_CENTRO" \
    --description '"Pintura mural en la Plaza"' --amount 2000000 --recipient "$M2" --duration_days 7 >/dev/null
invoke "$GOVERNANCE" "raiz-r-norte-1" create_proposal \
    --proposer "$R_NORTE_1" --barrio_id "$BARRIO_NORTE" \
    --description '"Bancas para el parque"' --amount 1500000 --recipient "$M5" --duration_days 5 >/dev/null
invoke "$GOVERNANCE" "raiz-r-costa-1" create_proposal \
    --proposer "$R_COSTA_1" --barrio_id "$BARRIO_COSTA" \
    --description '"Limpieza de la playa"' --amount 3000000 --recipient "$M8" --duration_days 10 >/dev/null
log "  ✓ 3 propuestas (ids 0, 1, 2)"

# ── 7. Algunos votos (quórum 30% con 3 residentes = 1 voto basta) ──────────

log "Registrando votos en propuestas..."
invoke "$GOVERNANCE" "raiz-r-centro-1" vote --resident "$R_CENTRO_1" --proposal_id 0 --support true  >/dev/null
invoke "$GOVERNANCE" "raiz-r-centro-1" vote --resident "$(stellar keys address raiz-r-centro-2)" --proposal_id 0 --support true >/dev/null 2>&1 || true
invoke "$GOVERNANCE" "raiz-r-norte-1" vote  --resident "$R_NORTE_1"  --proposal_id 1 --support true  >/dev/null
invoke "$GOVERNANCE" "raiz-r-costa-1" vote  --resident "$R_COSTA_1"  --proposal_id 2 --support true  >/dev/null
log "  ✓ Votos registrados"

# ── 8. Rewards (artesanías) ────────────────────────────────────────────────

log "Registrando rewards..."
# El artesano de cada reward es el merchant artesanía correspondiente.
invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_CENTRO" --name '"Mochila wayuu artesanal"' \
    --artisan "$M2" --points_cost 50 --stock 5 --image_ref '"https://images.test/mochila.png"' >/dev/null
invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_CENTRO" --name '"Hamaca tejida"' \
    --artisan "$M2" --points_cost 200 --stock 3 --image_ref '"https://images.test/hamaca.png"' >/dev/null
log "  ✓ Centro: 2 rewards"

invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_NORTE" --name '"Café orgánico 1lb"' \
    --artisan "$M4" --points_cost 80 --stock 10 --image_ref '"https://images.test/cafe.png"' >/dev/null
invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_NORTE" --name '"Ruana de lana"' \
    --artisan "$M5" --points_cost 300 --stock 2 --image_ref '"https://images.test/ruana.png"' >/dev/null
log "  ✓ Norte: 2 rewards"

invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_COSTA" --name '"Sombrero vueltiao"' \
    --artisan "$M8" --points_cost 150 --stock 4 --image_ref '"https://images.test/sombrero.png"' >/dev/null
invoke "$REWARDS" "$ADMIN" register_reward \
    --barrio_id "$BARRIO_COSTA" --name '"Bolso playero"' \
    --artisan "$M8" --points_cost 100 --stock 6 --image_ref '"https://images.test/bolso.png"' >/dev/null
log "  ✓ Costa: 2 rewards"

# ── Resumen ────────────────────────────────────────────────────────────────

echo
log "Seed completo. Resumen:"
echo "  3 barrios: Centro Histórico, Barrio Norte, Costa Vieja"
echo "  9 comercios (3 por barrio)"
echo "  9 residentes con soulbound (3 por barrio)"
echo "  6 pagos con tip (turista demo → comercios)"
echo "  3 propuestas activas con votos"
echo "  6 rewards (2 por barrio)"
echo
echo "Verificación on-chain:"
echo "  Pool del Centro:   stellar contract invoke --id $POOL --source-account $ADMIN --network testnet -- get_pool_balance --barrio_id $BARRIO_CENTRO"
echo "  Propuesta 0:       stellar contract invoke --id $GOVERNANCE --source-account $ADMIN --network testnet -- get_proposal --proposal_id 0"
echo "  Rewards del Costa: stellar contract invoke --id $REWARDS --source-account $ADMIN --network testnet -- list_rewards --barrio_id $BARRIO_COSTA"
