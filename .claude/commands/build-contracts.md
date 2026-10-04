---
description: Compila los 5 contratos a wasm (stellar contract build, wasm32v1-none)
allowed-tools: Bash, Read
---

Compila los 5 contratos Soroban (pool, governance, treasury, rewards, yield_adapter) al wasm que acepta el host de Soroban.

Un solo paso: todos los cross-contract calls usan `#[contractclient]` declarado a mano, así que no hay orden de compilación ni build en dos pasos.

```bash
cd contracts
stellar contract build
```

OJO: `cargo build --target wasm32-unknown-unknown` NO sirve para desplegar: emite instrucciones `reference-types` que el host de Soroban rechaza. Usa siempre `stellar contract build` (target `wasm32v1-none`; el toolchain lo fija `contracts/rust-toolchain.toml`).

Tras compilar, verifica tamaños:

```bash
ls -la target/wasm32v1-none/release/*.wasm
```

Cada wasm debe pesar < 64 KiB idealmente. Si alguno se pasa de 256 KiB, hay un problema (genéricos sin monomorfizar, panic con format!, etc.) — investiga.
