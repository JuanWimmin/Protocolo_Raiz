---
description: Pobla el testnet con datos demo (3 barrios, comercios, residentes, propuestas)
allowed-tools: Bash, Read
---

Ejecuta el script de seed para poblar el testnet con datos de demostración.

Requisitos previos:

1. `deployments.json` existe con los 4 IDs de contratos válidos.
2. `scripts/seed.ts` existe.
3. La identidad `raiz-admin` tiene XLM de testnet para cubrir fees.

Lo que debe crear el seed:

- **3 barrios**: Centro Histórico, Barrio Norte, Costa Vieja (con `register_barrio`).
- **~6 comercios por barrio** con lat/lng reales (Cartagena/Bogotá), categorías variadas.
- **5 residentes por barrio** con soulbound token (`mint_resident`).
- **2 propuestas activas por barrio** con descripciones realistas.
- **20 pagos simulados con tip** (para que el pool tenga balance visible en el dashboard).
- **3 rewards por barrio** (artesanías, costo entre 50-500 puntos).

Comando:

```bash
cd scripts && npx tsx seed.ts
```

Tras correr, verifica con `stellar`:

```bash
# Lista comercios del primer barrio
stellar contract invoke --id $(jq -r .pool deployments.json) --network testnet -- list_merchants --barrio_id <BARRIO_ID>
```

Reporta al final: número de barrios, comercios, residentes, propuestas, pagos y rewards creados.
