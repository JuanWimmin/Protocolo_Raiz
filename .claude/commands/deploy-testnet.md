---
description: Despliega los 4 contratos a Stellar Testnet (requiere scripts/deploy_testnet.sh)
allowed-tools: Bash, Read, Write
argument-hint: [--rebuild] (opcional, fuerza compilar antes de desplegar)
---

Despliega los contratos compilados a Stellar Testnet usando `stellar` CLI (v23.x).

Pre-requisitos (verifica antes de correr):

1. `stellar --version` debe responder.
2. Identidad configurada: `stellar keys ls` debe mostrar al menos una identidad (la del admin del protocolo).
3. Si no existe, crear con: `stellar keys generate --global raiz-admin --network testnet --fund`.

Pasos:

1. Si pasa `--rebuild` o si no existen los `.wasm`, compila primero:
   ```bash
   cd contracts
   cargo build --release --target wasm32-unknown-unknown -p rewards
   cargo build --release --target wasm32-unknown-unknown -p governance
   cargo build --release --target wasm32-unknown-unknown -p treasury
   cargo build --release --target wasm32-unknown-unknown -p pool
   ```

2. Ejecuta el script de deploy:
   ```bash
   ./scripts/deploy_testnet.sh
   ```

3. El script debe guardar los IDs de contratos en `deployments.json`. Verifica que el archivo tenga este formato:
   ```json
   {
     "network": "testnet",
     "rewards": "C...",
     "governance": "C...",
     "treasury": "C...",
     "pool": "C...",
     "usdc_sac": "CDLZFC3SYJYDZT7K67VZ75HPJVIEUVNIXF47ZG2FB2RMQQVU2HHGCYSC",
     "deployed_at": "2026-..."
   }
   ```

4. Reporta los IDs al usuario y la URL del Stellar Expert para cada uno.

Si `scripts/deploy_testnet.sh` no existe aún, créalo siguiendo el patrón estándar (`stellar contract deploy --wasm ... --network testnet --source raiz-admin`) e inicializa cada contrato con sus parámetros.
