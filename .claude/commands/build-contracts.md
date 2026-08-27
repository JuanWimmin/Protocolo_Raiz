---
description: Compila los 5 contratos a wasm32 release
allowed-tools: Bash, Read
---

Compila los contratos Soroban a WASM optimizado para Stellar testnet/mainnet.

Por dependencia entre contratos (Pool importa Rewards vía `contractimport!`), el orden importa:

```bash
cd contracts
cargo build --release --target wasm32-unknown-unknown -p rewards
cargo build --release --target wasm32-unknown-unknown -p governance
cargo build --release --target wasm32-unknown-unknown -p treasury
cargo build --release --target wasm32-unknown-unknown -p pool
```

Tras compilar, verifica tamaños:

```bash
ls -la target/wasm32-unknown-unknown/release/*.wasm
```

Cada wasm debe pesar < 64 KiB idealmente. Si alguno se pasa de 256 KiB, hay un problema (genéricos sin monomorfizar, panic con format!, etc.) — investiga.

Si el build falla en `pool` por `contractimport!` apuntando a un wasm que no existe, asegúrate de haber compilado `rewards` PRIMERO. Si persiste, considera definir el cliente de Rewards con `#[contractclient]` en lugar de importar el wasm.
