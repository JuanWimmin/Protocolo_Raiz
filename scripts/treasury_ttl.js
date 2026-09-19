#!/usr/bin/env node
// TTL de las entradas persistentes del Treasury (solo lectura: getContractData).
//
// Por qué existe: el Treasury crea sus entradas con el TTL mínimo (120 960 ledgers ≈ 7 días) y no
// lo extiende (hallazgo H2). Con una entrada archivada, `get_execution_log` deja de ser lectura pura
// (auto-restore de Protocol 23+) y la app falla con "Signer required for write call".
//
// Uso:
//   node scripts/treasury_ttl.js          → estado de cada clave (VIVA / ARCHIVADA / no existe)
//   node scripts/treasury_ttl.js xdr      → XDR base64 de cada clave, para:
//     stellar contract restore --network testnet --source-account raiz-admin --id <TREASURY> \
//       --durability persistent --key-xdr "<XDR>"
//     stellar contract extend  --network testnet --source-account raiz-admin --id <TREASURY> \
//       --durability persistent --ledgers-to-extend 1500000 --key-xdr "<XDR>" [--key-xdr ...]
//
// Requiere @stellar/stellar-sdk ≥ 17 (npm i -g, o el node_modules del repo hermano raiz-relayer).
const path = require('path');
// Orden: STELLAR_SDK_PATH, instalación normal, repo hermano raiz-relayer (desde el checkout o desde un worktree).
const candidates = [
  process.env.STELLAR_SDK_PATH,
  '@stellar/stellar-sdk',
  path.resolve(__dirname, '../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
  path.resolve(__dirname, '../../../../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
].filter(Boolean);
let SDK;
for (const c of candidates) { try { SDK = require(c); break; } catch (_) { /* siguiente */ } }
if (!SDK) { console.error('No encuentro @stellar/stellar-sdk. Define STELLAR_SDK_PATH.'); process.exit(1); }
const { xdr, nativeToScVal } = SDK;
const deployments = require(path.resolve(__dirname, '../deployments.json'));
const TREASURY = deployments.treasury;
const server = new SDK.rpc.Server('https://soroban-testnet.stellar.org');
const MAX_EXECUTIONS = Number(process.env.MAX_EXECUTIONS || 12);

const BARRIOS = {
  Centro: 'ce47120000000000000000000000000000000000000000000000000000000001',
  Norte: 'bba17e0000000000000000000000000000000000000000000000000000000002',
  Costa: 'c057a9000000000000000000000000000000000000000000000000000000000a',
};
const sym = s => xdr.ScVal.scvSymbol(s);
const u64 = n => nativeToScVal(BigInt(n), { type: 'u64' });
const bytes = hex => xdr.ScVal.scvBytes(Buffer.from(hex, 'hex'));
// Variante de enum #[contracttype]: Vec[Symbol(nombre), ...valores]
const variant = (name, ...vals) => xdr.ScVal.scvVec([sym(name), ...vals]);

const keys = [['TotalExecutions', variant('TotalExecutions')]];
for (let i = 0; i < MAX_EXECUTIONS; i++) keys.push([`Execution(${i})`, variant('Execution', u64(i))]);
for (const [n, hex] of Object.entries(BARRIOS)) {
  keys.push([`ExecutionCount(${n})`, variant('ExecutionCount', bytes(hex))]);
  keys.push([`BarrioExecutions(${n})`, variant('BarrioExecutions', bytes(hex))]);
}

(async () => {
  if (process.argv[2] === 'xdr') {
    for (const [name, scv] of keys) console.log(name.padEnd(26), scv.toXDR('base64'));
    return;
  }
  const latest = (await server.getLatestLedger()).sequence;
  console.log('treasury', TREASURY, '· latestLedger', latest);
  for (const [name, scv] of keys) {
    try {
      const e = await server.getContractData(TREASURY, scv, SDK.rpc.Durability.Persistent);
      const left = e.liveUntilLedgerSeq - latest;
      console.log(name.padEnd(26), left >= 0
        ? `VIVA      ${String(left).padStart(8)} ledgers ≈ ${(left * 5 / 86400).toFixed(1)} d`
        : 'ARCHIVADA → restore + extend');
    } catch (err) {
      if (/not found/i.test(err.message)) { if (!name.startsWith('Execution(')) console.log(name.padEnd(26), 'no existe'); }
      else console.log(name.padEnd(26), 'ERROR', err.message);
    }
  }
})();
