---
description: Despliega los 5 contratos a Stellar Testnet (scripts/deploy_testnet.sh)
allowed-tools: Bash, Read, Write
argument-hint: (sin argumentos; el script compila siempre antes de desplegar)
---

Despliega los 5 contratos a Stellar Testnet con `scripts/deploy_testnet.sh` (Stellar CLI 23.x).

Un deploy crea contratos NUEVOS con IDs nuevos: confírmalo con el usuario antes de correrlo. Después hay que volver a sembrar (`/seed-testnet`), actualizar el objeto `DEPLOYMENTS` de `landing/*.html`, la allowlist del relayer (`raiz-relayer`) y el archivo `android/app/src/main/assets/execution_hashes.json` (su clave de primer nivel es el ID del Treasury).

Pre-requisitos (verifica antes de correr):

1. `stellar --version` responde.
2. Identidad del admin: `stellar keys address raiz-admin`. OJO: desde el 2026-10-04 la clave maestra de esa cuenta tiene peso 0 (se rotó). La cuenta sigue siendo el admin, pero firma la identidad `raiz-admin-signer`; el script ya lo hace por defecto (variable `SIGNER`). Ver el gotcha "Rotación de la clave del admin" en `CLAUDE.md`.

Pasos:

1. Ejecuta el script (compila con `stellar contract build`, despliega, inicializa en orden Rewards → yield_adapter → Pool → Governance → Treasury, escribe `deployments.json` y lo copia a los assets de la app):
   ```bash
   ./scripts/deploy_testnet.sh
   ```

2. Verifica que `deployments.json` tenga este formato:
   ```json
   {
     "network": "testnet",
     "admin": "G...",
     "admin_identity": "raiz-admin",
     "usdc_sac": "CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU",
     "pool": "C...",
     "governance": "C...",
     "treasury": "C...",
     "rewards": "C...",
     "yield_adapter": "C...",
     "protocol_fee_bps": 50,
     "deployed_at": "2026-...",
     "usdc_issuer": "GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56",
     "blend_pool": "CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF"
   }
   ```

3. Reporta los IDs al usuario y la URL de Stellar Expert de cada uno.

Los deploys a testnet son flaky en ráfaga (propagación RPC + rate-limit): el script reintenta cada operación. Si un deploy "se cuelga" o un init da "Contract not found", es propagación — reintenta.
