#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# RAÍZ — Quick win F1 (Etapa A de custodia): admin de testnet a multisig 2-de-3
#
# Convierte la cuenta admin (identity `raiz-admin`, la G... de deployments.json)
# en una cuenta multisig nativa Stellar con 3 firmantes (la clave master + los
# 2 miembros del equipo) y umbral 2 para operaciones medium/high.
#
# USO:
#   SIGNER_2=GXXX... SIGNER_3=GYYY... ./scripts/setup_admin_multisig.sh
#   SIGNER_2=GXXX... SIGNER_3=GYYY... MODE=high-only ./scripts/setup_admin_multisig.sh
#
# MODOS:
#   full      (default) → low=1, med=2, high=2. TODA operación que mueva fondos
#               o administre contratos con la cuenta admin exige 2 firmas de 3.
#               ⚠️  IMPACTO REAL: rompe el flujo single-sig existente hasta que
#               haya co-firma: el faucet de la app (fundContractUsdc firma solo
#               con raiz.admin.secret), scripts/seed_testnet.sh y cualquier
#               invoke --source raiz-admin fallarán con tx_bad_auth. Actívalo
#               cuando el equipo tenga el flujo de co-firma ensayado
#               (stellar tx sign --sign-with-key ... encadenado, o Lab).
#   high-only → low=1, med=1, high=2. Las operaciones diarias (pagos, invokes,
#               faucet, seed) siguen con 1 firma; solo cambiar firmantes /
#               umbrales / merge exige 2 de 3. Protege contra secuestro de la
#               clave (nadie puede rotar firmantes solo) sin romper la demo.
#               Recomendado como primer paso; subir a `full` después.
#
# ORDEN SEGURO (no cambiar): primero se AÑADEN los firmantes, se verifica que
# están on-chain, y SOLO ENTONCES se suben los umbrales. Si subes umbrales
# antes de añadir firmantes, la cuenta queda bloqueada (master weight 1 < 2).
#
# ROLLBACK: tras activar `full`, deshacer TAMBIÉN exige 2 firmas (set-options
# es high). No hay marcha atrás unilateral — esa es la gracia y el riesgo.
#
# Requiere: stellar CLI ≥ 23.x con la identity `raiz-admin` configurada.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NETWORK="${NETWORK:-testnet}"
MODE="${MODE:-full}"
ADMIN_ID="${ADMIN_ID:-raiz-admin}"
HORIZON="${HORIZON:-https://horizon-testnet.stellar.org}"

if [[ -z "${SIGNER_2:-}" || -z "${SIGNER_3:-}" ]]; then
  echo "ERROR: exporta SIGNER_2 y SIGNER_3 (claves públicas G... de los 2 miembros del equipo)." >&2
  echo "  SIGNER_2=G... SIGNER_3=G... $0" >&2
  exit 1
fi

for s in "$SIGNER_2" "$SIGNER_3"; do
  if [[ ! "$s" =~ ^G[A-Z2-7]{55}$ ]]; then
    echo "ERROR: '$s' no parece una clave pública Stellar (G... de 56 chars)." >&2
    exit 1
  fi
done

ADMIN_PUB=$(stellar keys address "$ADMIN_ID")
echo "── Admin:    $ADMIN_PUB (identity: $ADMIN_ID, red: $NETWORK)"
echo "── Firmante 2: $SIGNER_2"
echo "── Firmante 3: $SIGNER_3"
echo "── Modo:     $MODE"

if [[ "$SIGNER_2" == "$ADMIN_PUB" || "$SIGNER_3" == "$ADMIN_PUB" || "$SIGNER_2" == "$SIGNER_3" ]]; then
  echo "ERROR: los 3 firmantes deben ser claves distintas." >&2
  exit 1
fi

case "$MODE" in
  full)      LOW=1; MED=2; HIGH=2 ;;
  high-only) LOW=1; MED=1; HIGH=2 ;;
  *) echo "ERROR: MODE debe ser 'full' o 'high-only'." >&2; exit 1 ;;
esac

echo
echo "⚠️  Vas a modificar los firmantes/umbrales de la cuenta admin en $NETWORK."
if [[ "$MODE" == "full" ]]; then
  echo "⚠️  MODO FULL: el faucet de la app y seed_testnet.sh dejarán de funcionar"
  echo "    con una sola firma. Confirma que el equipo está listo para co-firmar."
fi
read -r -p "¿Continuar? (escribe SI) " CONFIRM
[[ "$CONFIRM" == "SI" ]] || { echo "Abortado."; exit 1; }

# Paso 1 y 2: añadir los firmantes (weight 1 cada uno). Umbrales AÚN en 1.
echo
echo "[1/3] Añadiendo firmante 2..."
stellar tx new set-options --source-account "$ADMIN_ID" --network "$NETWORK" \
  --signer "$SIGNER_2" --signer-weight 1

echo "[2/3] Añadiendo firmante 3..."
stellar tx new set-options --source-account "$ADMIN_ID" --network "$NETWORK" \
  --signer "$SIGNER_3" --signer-weight 1

# Verificación on-chain antes de tocar umbrales (evita bloquear la cuenta).
echo "    Verificando firmantes en Horizon..."
SIGNERS_JSON=$(curl -s "$HORIZON/accounts/$ADMIN_PUB")
for s in "$SIGNER_2" "$SIGNER_3"; do
  if ! echo "$SIGNERS_JSON" | grep -q "$s"; then
    echo "ERROR: el firmante $s no aparece en la cuenta. NO se tocan umbrales. Reintenta." >&2
    exit 1
  fi
done
echo "    ✓ Los 3 firmantes están on-chain."

# Paso 3: umbrales + peso del master, en una sola transacción.
echo "[3/3] Fijando umbrales (low=$LOW, med=$MED, high=$HIGH, master=1)..."
stellar tx new set-options --source-account "$ADMIN_ID" --network "$NETWORK" \
  --master-weight 1 --low-threshold "$LOW" --med-threshold "$MED" --high-threshold "$HIGH"

echo
echo "✅ Multisig activo. Estado de la cuenta:"
curl -s "$HORIZON/accounts/$ADMIN_PUB" | python -c "
import json,sys
a=json.load(sys.stdin)
print('  thresholds:', a['thresholds'])
for s in a['signers']:
    print('  signer:', s['key'], 'weight', s['weight'])
"
echo
echo "Co-firma de una operación (ejemplo, modo full):"
echo "  stellar contract invoke ... --source-account $ADMIN_ID --build-only > tx.xdr"
echo "  stellar tx sign --sign-with-key <SECRET_MIEMBRO_2> < tx.xdr > tx-firmada.xdr"
echo "  stellar tx send < tx-firmada.xdr"
