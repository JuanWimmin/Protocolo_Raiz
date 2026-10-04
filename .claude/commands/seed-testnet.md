---
description: Pobla el testnet con datos demo (3 barrios, comercios, residentes, propuestas)
allowed-tools: Bash, Read
---

Ejecuta el script de seed para poblar el testnet con datos de demostración.

Requisitos previos:

1. `deployments.json` existe con los 5 IDs de contratos válidos (correr `/deploy-testnet` antes).
2. La cuenta admin tiene XLM de testnet para cubrir fees. Desde el 2026-10-04 firma la identidad `raiz-admin-signer` (la clave maestra de `raiz-admin` tiene peso 0); el script ya lo hace.
3. Node en el PATH (el script lee `deployments.json` con Node).

Comando:

```bash
./scripts/seed_testnet.sh
```

Lo que crea el seed:

- **3 barrios**: Centro Histórico, Barrio Norte, Costa Vieja (`register_barrio` + `set_barrio_admin`).
- **9 comercios** (3 por barrio) con lat/lng reales.
- **9 residentes** con soulbound (3 por barrio, `mint_resident`).
- **6 pagos con tip** del turista demo a los comercios.
- **Depósito de prueba** de 0,2 USDC del fondo del Centro en Blend v2 vía `yield_adapter`.
- **3 propuestas activas con votos** (1 por barrio).
- **6 rewards** (2 por barrio).

Las cuentas se fondean con el faucet de USDC de Blend (el admin no puede acuñarlo; el faucet da USDC una sola vez por cuenta).

Tras correr, verifica SIN enviar transacciones (una "lectura" con entradas archivadas se envía firmada si no pones `--send=no`):

```bash
stellar contract invoke --id <POOL> --source-account <G del admin> --network testnet --send=no -- list_merchants --barrio_id <BARRIO_ID>
```

Reporta al final: número de barrios, comercios, residentes, propuestas, pagos y rewards creados.

Recordatorio de TTL: las entradas nuevas nacen con ~7 días de vida. Tras sembrar hay que extenderlas (`stellar contract extend --ledgers-to-extend 1500000`) o la app dejará de leerlas una semana después (gotchas de TTL en `CLAUDE.md`).
