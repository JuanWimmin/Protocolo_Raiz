#!/usr/bin/env node
// Pasarela de terminal del pago diferido: liquida un sobre (`raiz:sobre:1:` + base64url del XDR de la
// SorobanAuthorizationEntry firmada) desde CUALQUIER cuenta con señal. Es la referencia de la pasarela web
// (landing/pasarela.html) y el plan B cuando no hay otra pasarela a mano. Spec: docs/pago_diferido_v0.md §1, §2,
// §6 y A4 de docs/Pago diferido por enjambre.md.
//
// Uso:
//   node scripts/pasarela_cli.mjs "<sobre>"                → liquida (cuenta desechable fondeada por friendbot)
//   node scripts/pasarela_cli.mjs ruta/al/sobre.txt         → lee el sobre de un archivo
//   node scripts/pasarela_cli.mjs - < sobre.txt             → lee el sobre de stdin
//   node scripts/pasarela_cli.mjs "<sobre>" --dry-run       → solo verifica y simula (no fondea ni envía nada)
//   PASARELA_SECRET=S… node scripts/pasarela_cli.mjs "<sobre>"   → usa tu propia cuenta como pasarela
//
// Qué hace (A4): verifica el sobre sin red (prefijo, XDR, raíz pay_merchant del Pool, firmante = pagador, árbol
// reconstruido con fee_bps idéntico byte a byte, firma ed25519 de testnet) → lee el ledger (si E ya pasó, no
// gasta) → tx con la pasarela como fuente y auth = [sobre] → simulación en modo enforce → ensamblar (conserva la
// auth) → firmar → enviar → esperar → hash + enlace + prueba del nonce consumido.
//
// Reglas: la pasarela no puede alterar nada (la firma fija la invocación); "cobrado" solo con hash cuya auth sea
// este sobre; `Error(Auth, ExistingValue)` significa "nonce ya usado", nunca "cobrado". No usa claves del proyecto.
//
// Requiere @stellar/stellar-sdk >= 17 (STELLAR_SDK_PATH, instalación normal o ../raiz-relayer/node_modules).

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import { payMerchantInvocation } from './gen-auth-fixture.mjs';

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
const { xdr, Address, Keypair, Networks, StrKey, hash, scValToNative, TransactionBuilder, Operation, rpc } = SDK;

const deployments = require(path.resolve(__dirname, '../deployments.json'));
const IDS = { pool: deployments.pool, usdcSac: deployments.usdc_sac, admin: deployments.admin };
const FEE_BPS = Number(deployments.protocol_fee_bps);
const NETWORK = Networks.TESTNET;
const RPC_URL = 'https://soroban-testnet.stellar.org';
const FRIENDBOT = 'https://friendbot.stellar.org';
const PREFIX = 'raiz:sobre:1:';
const MAX_CHARS = 4096;
const BASE_FEE = '500000';
const POLL_MS = 3000;
const POLL_MAX = 30;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (x) => x.toXdr('base64');
const short = (h) => `${h.slice(0, 8)}…${h.slice(-6)}`;

// ── 1. Entrada ──────────────────────────────────────────────────────────────────────────────────
function readSobreArg(arg) {
  if (!arg) throw new Error('Falta el sobre. Uso: node scripts/pasarela_cli.mjs "<sobre>" [--dry-run]');
  if (arg === '-') return fs.readFileSync(0, 'utf8');
  if (!arg.startsWith(PREFIX) && fs.existsSync(arg)) return fs.readFileSync(arg, 'utf8');
  return arg;
}

export function decodeSobre(text) {
  const t = String(text).trim();
  if (t.length > MAX_CHARS) throw new Error('sobre demasiado largo');
  if (!t.startsWith(PREFIX)) throw new Error('no es un sobre RAÍZ (falta el prefijo raiz:sobre:1:)');
  const b64url = t.slice(PREFIX.length).replace(/\s+/g, '').replace(/=+$/, '');
  const std = b64url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (b64url.length % 4)) % 4);
  return xdr.SorobanAuthorizationEntry.fromXdr(std, 'base64');
}

// ── 2. Verificación sin red (A3 sin la regla "es para mí") ────────────────────────────────────────
export function verifySobre(entry, { ids = IDS, feeBps = FEE_BPS, networkPassphrase = NETWORK } = {}) {
  if (entry.credentials.type !== 'sorobanCredentialsAddress') {
    throw new Error(`credenciales ${entry.credentials.type}: v0 solo acepta cuentas G con credenciales legacy`);
  }
  const creds = entry.credentials.address;
  const payer = Address.fromScAddress(creds.address).toString();
  if (!StrKey.isValidEd25519PublicKey(payer)) throw new Error('el firmante no es una cuenta G');

  const fn = entry.rootInvocation.function.contractFn;
  if (!fn) throw new Error('la raíz no es una llamada a contrato');
  const contract = Address.fromScAddress(fn.contractAddress).toString();
  if (contract !== ids.pool) throw new Error(`no es un pago a este Pool (raíz en ${short(contract)})`);
  if (String(fn.functionName) !== 'pay_merchant') throw new Error(`la raíz es ${fn.functionName}, no pay_merchant`);
  if (fn.args.length !== 4) throw new Error('pay_merchant con un número de argumentos inesperado');
  const [aPayer, aMerchant, aAmount, aTip] = fn.args.map((v) => scValToNative(v));
  const args = { payer: String(aPayer), merchant: String(aMerchant), amount: BigInt(aAmount), tipBps: Number(aTip) };
  if (args.payer !== payer) throw new Error('el pagador del árbol no es quien firma');

  const expected = payMerchantInvocation({ payer, merchant: args.merchant, amount: args.amount, tipBps: args.tipBps, feeBps, ids });
  if (b64(expected) !== b64(entry.rootInvocation)) throw new Error('el reparto no coincide (¿cambió fee_bps o el admin del Pool?)');

  const preimage = xdr.HashIdPreimage.envelopeTypeSorobanAuthorization(new xdr.HashIdPreimageSorobanAuthorization({
    networkId: hash(networkPassphrase),
    nonce: creds.nonce,
    signatureExpirationLedger: creds.signatureExpirationLedger,
    invocation: entry.rootInvocation,
  }));
  const payload = hash(preimage.toXdr());
  const sigs = scValToNative(creds.signature);
  if (!Array.isArray(sigs) || sigs.length !== 1) throw new Error('la credencial debe llevar exactamente una firma');
  const sig = sigs[0];
  const pub = Buffer.from(sig.public_key);
  if (!pub.equals(Buffer.from(StrKey.decodeEd25519PublicKey(payer)))) throw new Error('la firma no es de la clave del pagador');
  if (!Keypair.fromPublicKey(payer).verify(payload, Buffer.from(sig.signature))) throw new Error('firma inválida');

  const tip = (args.amount * BigInt(args.tipBps)) / 10000n;
  const fee = (args.amount * BigInt(feeBps)) / 10000n;
  return {
    payer,
    args,
    split: { tip, fee, toMerchant: args.amount - fee, debit: args.amount + tip },
    nonce: BigInt(creds.nonce.toString()),
    exp: Number(creds.signatureExpirationLedger),
    contractFn: fn,
  };
}

// ── 3. Lectura de las respuestas de la red ──────────────────────────────────────────────────────
function traducir(texto) {
  const s = String(texto ?? '');
  if (/Auth, ExistingValue/.test(s)) return 'NONCE YA USADO: la red ya ejecutó un sobre con este nonce (este u otro con el mismo nonce). NO se da por cobrado sin un hash cuya auth sea este sobre.';
  if (/Auth, InvalidInput/.test(s)) return 'EXPIRADO (o expiración fuera del tope de la red): E es menor que el ledger actual o E − L > 3 110 399.';
  if (/Auth, InvalidAction|Auth, /.test(s)) return 'LA FIRMA NO CORRESPONDE AL ÁRBOL: sobre alterado, o el Pool cambió fee_bps/admin. El pagador debe firmar de nuevo.';
  if (/Contract, #4\)/.test(s)) return 'COMERCIO NO REGISTRADO en el Pool (Error #4).';
  if (/Contract, #5\)/.test(s)) return 'COMERCIO NO VERIFICADO (Error #5).';
  if (/Contract, #10\)/.test(s)) return 'FONDOS INSUFICIENTES del pagador en USDC del fondo (SAC #10).';
  if (/Contract, #13\)/.test(s)) return 'EL PAGADOR NO TIENE TRUSTLINE al USDC del fondo (SAC #13).';
  return `error de la red: ${s.slice(0, 300)}`;
}

function nonceKey(payer, nonce) {
  return xdr.LedgerKey.contractData(new xdr.LedgerKeyContractData({
    contract: new Address(payer).toScAddress(),
    key: xdr.ScVal.scvLedgerKeyNonce(new xdr.ScNonceKey({ nonce: xdr.Int64(nonce) })),
    durability: xdr.ContractDataDurability.temporary,
  }));
}

async function nonceConsumed(server, payer, nonce) {
  const r = await server.getLedgerEntries(nonceKey(payer, nonce));
  return (r.entries ?? []).length > 0;
}

// ── 4. Pasarela ──────────────────────────────────────────────────────────────────────────────────
async function gatewayKeypair(server) {
  if (process.env.PASARELA_SECRET) {
    const kp = Keypair.fromSecret(process.env.PASARELA_SECRET);
    await server.getAccount(kp.publicKey());
    return { kp, desechable: false };
  }
  const kp = Keypair.random();
  const res = await fetch(`${FRIENDBOT}/?addr=${kp.publicKey()}`);
  if (!res.ok) throw new Error(`friendbot → HTTP ${res.status}`);
  for (let i = 0; i < 10; i++) {
    try { await server.getAccount(kp.publicKey()); return { kp, desechable: true }; } catch { await sleep(1500); }
  }
  throw new Error('friendbot no creó la cuenta de la pasarela a tiempo');
}

async function main() {
  const argv = process.argv.slice(2);
  const dryRun = argv.includes('--dry-run');
  const sobreText = readSobreArg(argv.find((a) => !a.startsWith('--')));
  const server = new rpc.Server(RPC_URL);

  // 1–2. Decodificar y verificar sin red.
  const entry = decodeSobre(sobreText);
  const v = verifySobre(entry);
  const fmt = (n) => (Number(n) / 1e7).toFixed(7).replace(/0+$/, '').replace(/\.$/, '');
  console.log('Sobre verificado sin red ✓');
  console.log(`  pagador   ${v.payer}`);
  console.log(`  comercio  ${v.args.merchant}`);
  console.log(`  monto     ${fmt(v.args.amount)} USDC · aporte ${fmt(v.split.tip)} (${v.args.tipBps} bps) · fee ${fmt(v.split.fee)} (${FEE_BPS} bps) · débito ${fmt(v.split.debit)}`);
  console.log(`  nonce     ${v.nonce} · expira en el ledger ${v.exp}`);

  // 3. Ledger actual: no gastar por un sobre vencido.
  const { sequence: ledger } = await server.getLatestLedger();
  const restantes = v.exp - ledger;
  if (restantes < 0) { console.error(`EXPIRADO: ledger ${ledger} > E ${v.exp}. No se envía.`); process.exit(2); }
  console.log(`  ledger    ${ledger} (quedan ${restantes} ledgers ≈ ${(restantes * 5 / 3600).toFixed(1)} h)`);
  if (await nonceConsumed(server, v.payer, v.nonce)) {
    console.error('NONCE YA CONSUMIDO en la red: este sobre ya se liquidó (o uno con el mismo nonce). No se envía.');
    process.exit(3);
  }

  // 4. Tx con la pasarela como fuente y la auth del pagador intacta.
  const source = dryRun && !process.env.PASARELA_SECRET
    ? { kp: null, account: await server.getAccount(IDS.admin), desechable: false }
    : await (async () => { const g = await gatewayKeypair(server); return { ...g, account: await server.getAccount(g.kp.publicKey()) }; })();
  const op = Operation.invokeHostFunction({
    func: xdr.HostFunction.hostFunctionTypeInvokeContract(v.contractFn), // los argumentos firmados, tal cual
    auth: [entry],                                                       // la auth del pagador, intacta
  });
  const tx = new TransactionBuilder(source.account, { fee: BASE_FEE, networkPassphrase: NETWORK })
    .addOperation(op).setTimeout(120).build();

  const sim = await server.simulateTransaction(tx, undefined, 'enforce'); // con auth presente: enforce
  if (rpc.Api.isSimulationError(sim)) { console.error(`Simulación rechazada → ${traducir(sim.error)}\n  detalle: ${String(sim.error).slice(0, 400)}`); process.exit(4); }
  console.log(`Simulación enforce ✓  (fee de recursos ${(Number(sim.minResourceFee) / 1e7).toFixed(3)} XLM)`);
  if (dryRun) { console.log('--dry-run: no se envía nada.'); return; }

  console.log(`  pasarela  ${source.kp.publicKey()} (${source.desechable ? 'desechable, friendbot' : 'PASARELA_SECRET'})`);
  const prepared = rpc.assembleTransaction(tx, sim).build(); // conserva la auth adjunta
  prepared.sign(source.kp);
  const sent = await server.sendTransaction(prepared);
  const txHash = sent.hash;
  if (sent.status === 'ERROR') { console.error(`Envío rechazado → ${traducir(sent.errorResult ?? sent.errorResultXdr)}`); process.exit(5); }
  console.log(`Enviada (${sent.status}) hash ${txHash}; esperando…`);

  let result = null;
  for (let i = 0; i < POLL_MAX; i++) {
    await sleep(POLL_MS);
    const r = await server.getTransaction(txHash);
    if (r.status !== 'NOT_FOUND') { result = r; break; }
  }
  if (!result) { console.error(`Sin respuesta final en ${POLL_MAX * POLL_MS / 1000} s; consulta el hash: ${txHash}`); process.exit(6); }
  if (result.status !== 'SUCCESS') { console.error(`La tx entró y FALLÓ (${result.status}). hash ${txHash}`); process.exit(7); }

  const consumed = await nonceConsumed(server, v.payer, v.nonce);
  console.log('LIQUIDADO ✓');
  console.log(`  hash      ${txHash}`);
  console.log(`  ledger    ${result.ledger}`);
  console.log(`  enlace    https://stellar.expert/explorer/testnet/tx/${txHash}`);
  console.log(`  nonce consumido en la red: ${consumed ? 'sí (prueba del cobro; la app lo confirma sola al tener señal)' : 'aún no visible'}`);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((e) => { console.error(`ERROR: ${e.message}`); process.exit(1); });
}
