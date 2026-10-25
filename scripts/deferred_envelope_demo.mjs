#!/usr/bin/env node
// Genera un sobre REAL de pago diferido (`raiz:sobre:1:` + base64url del XDR firmado) desde una wallet de
// testnet, con nonce aleatorio y expiración = ledger actual + ventana. Sirve para probar la pasarela (web o
// terminal) sin pasar por la app. La clave SOLO entra por variable de entorno; nunca se imprime.
//
// Uso:
//   DEFERRED_PAYER_SECRET=$(stellar keys secret raiz-tourist) node scripts/deferred_envelope_demo.mjs \
//       [--merchant G…] [--amount 1.5] [--tip-bps 200] [--window 11520] [--out sobre.txt]
//
// Por defecto: merchant Cafe Don Aurelio (Centro), 1 USDC, 2 %, ventana de 11 520 ledgers (≈ 16 h a 5 s).
// Imprime el sobre y, si se indica --out, lo guarda en un archivo.

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import { buildSignedEntry } from './gen-auth-fixture.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const candidates = [
  process.env.STELLAR_SDK_PATH,
  '@stellar/stellar-sdk',
  path.resolve(__dirname, '../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
  path.resolve(__dirname, '../../../../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
].filter(Boolean);
let SDK;
for (const c of candidates) { try { SDK = require(c); break; } catch (_) { /* siguiente */ } }
if (!SDK) { console.error('No encuentro @stellar/stellar-sdk (>= 17). Define STELLAR_SDK_PATH.'); process.exit(1); }
const { Keypair, rpc } = SDK;

const deployments = require(path.resolve(__dirname, '../deployments.json'));
const PREFIX = 'raiz:sobre:1:';

function arg(name, def) { const i = process.argv.indexOf(name); return i >= 0 ? process.argv[i + 1] : def; }

const secret = process.env.DEFERRED_PAYER_SECRET;
if (!secret) { console.error('Define DEFERRED_PAYER_SECRET (p. ej. $(stellar keys secret raiz-tourist)).'); process.exit(1); }
const keypair = Keypair.fromSecret(secret);
const merchant = arg('--merchant', 'GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN');
const amountUsdc = arg('--amount', '1');
const tipBps = Number(arg('--tip-bps', '200'));
const windowLedgers = Number(arg('--window', '11520'));
const out = arg('--out', null);

// 1 USDC = 10_000_000 stroops; el monto se parsea sin floats.
const [ent, dec = ''] = String(amountUsdc).replace(',', '.').split('.');
const amount = BigInt(ent) * 10_000_000n + BigInt((dec + '0000000').slice(0, 7));

const server = new rpc.Server('https://soroban-testnet.stellar.org');
const { sequence: ledger } = await server.getLatestLedger();
const exp = ledger + windowLedgers;
const nonceBytes = Keypair.random().rawSecretKey().subarray(0, 8);
let nonce = 0n;
for (const b of nonceBytes) nonce = (nonce << 8n) | BigInt(b);
nonce = BigInt.asIntN(64, nonce);

const { entry } = await buildSignedEntry({
  keypair, merchant, amount, tipBps, feeBps: Number(deployments.protocol_fee_bps), nonce, exp,
});
const sobre = PREFIX + Buffer.from(entry.toXdr()).toString('base64url');
console.error(`pagador ${keypair.publicKey()} · comercio ${merchant} · ${amountUsdc} USDC · tip ${tipBps} bps · nonce ${nonce} · exp ${exp} (ledger ${ledger}) · ${sobre.length} chars`);
if (out) { fs.writeFileSync(out, sobre + '\n'); console.error(`guardado en ${out}`); }
console.log(sobre);
