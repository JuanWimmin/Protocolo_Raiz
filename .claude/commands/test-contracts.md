---
description: Corre cargo test del workspace de contratos
allowed-tools: Bash, Read
---

Ejecuta todos los tests del workspace de contratos Soroban:

```bash
cd contracts && cargo test --workspace 2>&1 | tail -80
```

Si hay errores de compilación o tests fallidos:
1. Identifica el archivo y línea exactos del error.
2. Si el error es claro y de bajo riesgo (typo, import faltante, tipo incorrecto), arréglalo directamente.
3. Si el error sugiere drift contra la spec (`docs/raiz_v2_spec_contratos.md`), avisa antes de tocar.
4. No marques la tarea como completa hasta que `cargo test --workspace` pase verde.

Reporta al final:
- Número de tests pasados / fallados por crate.
- Cualquier warning relevante.
