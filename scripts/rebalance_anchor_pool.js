#!/usr/bin/env node
// Estado (y reequilibrio opcional) del pool de liquidez de testnet entre el USDC del anchor de
// prueba y el USDC del fondo (Blend) — el que usa la app para "Convertir a USDC del fondo" (D3).
//
// Por qué existe: ese pool es de un tercero y tiene poca profundidad (≈ 2 000 USDC por lado). Cada
// depósito SEP-24 hecho desde la app vende USDC del anchor contra él y empeora el precio ≈ 0,1 % por
// cada USDC depositado. La app solo convierte SOLA si la cotización entrega ≥ 97 % de lo enviado
// (`SwapMath.AUTO_CONVERT_MIN_RATIO_BPS`); por debajo pide un tap con aviso. Este script devuelve el
// precio a su sitio vendiendo USDC del fondo contra el pool (la operación inversa a la de la app).
//
// Uso:
//   node scripts/rebalance_anchor_pool.js                 → solo lee: reservas y cotización de 5 USDC
//   node scripts/rebalance_anchor_pool.js --apply         → reequilibra hasta que 5 USDC coticen al 100,5 %
//   node scripts/rebalance_anchor_pool.js --apply --target-bps 10200
//   node scripts/rebalance_anchor_pool.js --apply --max-accounts 5   → hasta 5 rondas (1..6; por defecto 3)
//
// `--apply` NO usa ninguna clave del proyecto: en cada ronda crea una cuenta desechable de testnet
// (friendbot), le pide 1 000 USDC del fondo al faucet de Blend (que da 1 000 UNA sola vez por cuenta),
// vende hasta 900 USDC al pool con UN path payment y descarta la clave. Si una ronda no basta (tras
// muchos depósitos el pool puede necesitar miles de USDC), encadena rondas hasta alcanzar el objetivo o
// agotar --max-accounts; si el faucet rechaza a una cuenta o una tx falla, reintenta la ronda con otra
// cuenta (hasta 2 veces). Para reutilizar una cuenta propia: REBALANCER_SECRET=S… (variable de entorno;
// no la escribas en ningún archivo del repo) → una sola ronda con esa cuenta.
//
// Requiere @stellar/stellar-sdk ≥ 17 (npm i -g, o el node_modules del repo hermano raiz-relayer).
const path = require('path');
const candidates = [
  process.env.STELLAR_SDK_PATH,
  '@stellar/stellar-sdk',
  path.resolve(__dirname, '../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
  path.resolve(__dirname, '../../../../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
].filter(Boolean);
let SDK;
for (const c of candidates) { try { SDK = require(c); break; } catch (_) { /* siguiente */ } }
if (!SDK) { console.error('No encuentro @stellar/stellar-sdk. Define STELLAR_SDK_PATH.'); process.exit(1); }

const HORIZON = 'https://horizon-testnet.stellar.org';
const FRIENDBOT = 'https://friendbot.stellar.org';
const BLEND_FAUCET = 'https://ewqw4hx7oa.execute-api.us-east-1.amazonaws.com/getAssets';
const ANCHOR_ISSUER = 'GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5'; // testanchor.stellar.org
const FUND_ISSUER = require(path.resolve(__dirname, '../deployments.json')).usdc_issuer
  || 'GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56'; // USDC de Blend
const ANCHOR_USDC = new SDK.Asset('USDC', ANCHOR_ISSUER);
const FUND_USDC = new SDK.Asset('USDC', FUND_ISSUER);

const STROOP = 10_000_000n;
const FEE_DEN = 10_000n;
const SAMPLE = 5n * STROOP; // depósito típico con el que se mide el precio
const toStroops = s => { const [i, f = ''] = String(s).split('.'); return BigInt(i) * STROOP + BigInt((f + '0000000').slice(0, 7)); };
const fmt = n => { const neg = n < 0n; const a = neg ? -n : n; return (neg ? '-' : '') + (a / STROOP) + '.' + String(a % STROOP).padStart(7, '0'); };
const pct = (num, den) => (Number(num * 1_000_000n / den) / 10_000).toFixed(2) + ' %';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const FETCH_TIMEOUT_MS = 60_000; // friendbot y el faucet de Blend a veces se cuelgan minutos: mejor fallar y reintentar
const fetchT = (url, init = {}) => fetch(url, { ...init, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });

async function getJson(url) {
  const r = await fetchT(url);
  if (!r.ok) throw new Error(`${url} → HTTP ${r.status}`);
  return r.json();
}

/** Pool de producto constante con fee sobre la entrada: lo que sale al meter `amountIn`. */
function swapOut(reserveIn, reserveOut, amountIn, feeBp) {
  const inAfterFee = amountIn * (FEE_DEN - feeBp);
  return (reserveOut * inAfterFee) / (reserveIn * FEE_DEN + inAfterFee);
}

async function readPool() {
  const q = `${HORIZON}/liquidity_pools?reserves=${encodeURIComponent(`USDC:${ANCHOR_ISSUER},USDC:${FUND_ISSUER}`)}`;
  const pools = (await getJson(q))._embedded.records;
  if (!pools.length) throw new Error('No existe un pool de liquidez entre los dos USDC en testnet.');
  // Si hubiera varios, el más profundo es el que usa el path payment.
  pools.sort((a, b) => Number(b.total_shares) - Number(a.total_shares));
  const p = pools[0];
  const reserve = issuer => toStroops(p.reserves.find(r => r.asset.endsWith(issuer)).amount);
  return { id: p.id, feeBp: BigInt(p.fee_bp), anchor: reserve(ANCHOR_ISSUER), fund: reserve(FUND_ISSUER) };
}

const MAX_SELL = 900n * STROOP; // tope por ronda: el faucet de Blend da 1 000 USDC por cuenta (margen para redondeos)
const MAX_RETRIES = 2; // rondas fallidas (faucet o tx) que se reintentan con otra cuenta desechable

/**
 * USDC del fondo que hay que vender al pool para que `SAMPLE` USDC del anchor coticen ≥ targetBps.
 * Devuelve `{ sell, partial }`: `sell` es 0 si ya cotiza por encima del objetivo; si harían falta más
 * de `MAX_SELL`, devuelve ese tope con `partial = true` (una ronda no basta: habrá que encadenar otra).
 */
function fundToSell(pool, targetBps) {
  const ratioOk = x => {
    const anchorOut = x > 0n ? swapOut(pool.fund, pool.anchor, x, pool.feeBp) : 0n;
    const out = swapOut(pool.anchor - anchorOut, pool.fund + x, SAMPLE, pool.feeBp);
    return out * FEE_DEN >= SAMPLE * targetBps;
  };
  if (ratioOk(0n)) return { sell: 0n, partial: false };
  if (!ratioOk(MAX_SELL)) return { sell: MAX_SELL, partial: true };
  let lo = 0n, hi = MAX_SELL;
  while (hi - lo > 1000n) { const mid = (lo + hi) / 2n; if (ratioOk(mid)) hi = mid; else lo = mid; }
  return { sell: hi, partial: false };
}

async function quoteSample() {
  const url = `${HORIZON}/paths/strict-send?source_asset_type=credit_alphanum4&source_asset_code=USDC`
    + `&source_asset_issuer=${ANCHOR_ISSUER}&source_amount=${fmt(SAMPLE)}&destination_assets=USDC:${FUND_ISSUER}`;
  const rec = (await getJson(url))._embedded.records[0];
  return rec ? toStroops(rec.destination_amount) : 0n;
}

async function report(label) {
  const pool = await readPool();
  const quoted = await quoteSample();
  console.log(`${label} · pool ${pool.id.slice(0, 8)}…${pool.id.slice(-6)} (fee ${pool.feeBp} bp)`);
  console.log(`  reservas: ${fmt(pool.anchor)} USDC del anchor · ${fmt(pool.fund)} USDC del fondo`);
  console.log(`  5 USDC del anchor → ${fmt(quoted)} USDC del fondo (${pct(quoted, SAMPLE)}) · la app convierte sola desde el 97 %`);
  return { ...pool, quoted };
}

async function submit(server, tx, label) {
  const hash = tx.hash().toString('hex');
  try {
    const res = await server.submitTransaction(tx);
    console.log(`  ${label}: ${res.hash}`);
    return res.hash;
  } catch (e) {
    const codes = e.response && e.response.data && e.response.data.extras && e.response.data.extras.result_codes;
    if (codes) throw new Error(`${label} falló: ${JSON.stringify(codes)}`);
    // Sin códigos de resultado (504 / timeout): Horizon dejó de esperar, pero la tx puede entrar igual → por hash.
    for (let i = 0; i < 12; i++) {
      await sleep(5000);
      const found = await server.transactions().transaction(hash).call().catch(() => null);
      if (!found) continue;
      if (!found.successful) throw new Error(`${label} falló on-chain: ${hash}`);
      console.log(`  ${label}: ${hash} (confirmada por hash tras «${e.message}»)`);
      return hash;
    }
    throw new Error(`${label} falló: ${e.message}`);
  }
}

/**
 * Una ronda con la cuenta `kp`: la crea con friendbot si no existe → le pide USDC del fondo al faucet de
 * Blend si le falta → trustline al USDC del anchor si falta → path payment de `sell` contra el pool.
 * Devuelve la cuenta, lo vendido y los hashes (el del faucet es null si la cuenta ya tenía saldo).
 */
async function sellRound(server, kp, pool, sell, kind) {
  const expected = swapOut(pool.fund, pool.anchor, sell, pool.feeBp);
  console.log(`  vendiendo ${fmt(sell)} USDC del fondo al pool (≈ ${fmt(expected)} USDC del anchor de vuelta)…`);
  const G = kp.publicKey();
  console.log(`  cuenta ${kind}: ${G}`);

  let account = await server.loadAccount(G).catch(() => null);
  if (!account) {
    const fb = await fetchT(`${FRIENDBOT}/?addr=${G}`);
    if (!fb.ok) throw new Error(`friendbot → HTTP ${fb.status}`);
    for (let i = 0; i < 10 && !account; i++) { await sleep(1500); account = await server.loadAccount(G).catch(() => null); }
    if (!account) throw new Error('friendbot no creó la cuenta a tiempo');
  }
  const balanceOf = issuer => {
    const line = account.balances.find(b => b.asset_code === 'USDC' && b.asset_issuer === issuer);
    return line ? toStroops(line.balance) : null;
  };

  let faucetHash = null;
  if ((balanceOf(FUND_ISSUER) ?? 0n) < sell) {
    // Faucet de Blend: devuelve una tx ya firmada por el emisor (trustlines + 1 000 USDC); falta nuestra firma.
    const fr = await fetchT(`${BLEND_FAUCET}?userId=${G}`);
    const raw = (await fr.text()).trim().replace(/^"|"$/g, '');
    if (!fr.ok) throw new Error(`faucet de Blend → HTTP ${fr.status}: ${raw.slice(0, 120)}`);
    const faucetTx = SDK.TransactionBuilder.fromXDR(raw, SDK.Networks.TESTNET);
    faucetTx.sign(kp);
    faucetHash = await submit(server, faucetTx, 'faucet de Blend');
    account = await server.loadAccount(G);
    if ((balanceOf(FUND_ISSUER) ?? 0n) < sell) throw new Error('La cuenta no tiene suficiente USDC del fondo (el faucet de Blend da 1 000 una sola vez por cuenta).');
  }

  const builder = new SDK.TransactionBuilder(account, { fee: '1000', networkPassphrase: SDK.Networks.TESTNET });
  if (balanceOf(ANCHOR_ISSUER) === null) builder.addOperation(SDK.Operation.changeTrust({ asset: ANCHOR_USDC }));
  builder.addOperation(SDK.Operation.pathPaymentStrictSend({
    sendAsset: FUND_USDC,
    sendAmount: fmt(sell),
    destination: G,
    destAsset: ANCHOR_USDC,
    destMin: fmt(expected * 99n / 100n), // 1 % de tolerancia
    path: [],
  }));
  const tx = builder.setTimeout(60).build();
  tx.sign(kp);
  const swapHash = await submit(server, tx, 'reequilibrio (path payment)');
  return { account: G, sell, expected, faucetHash, swapHash };
}

async function apply(targetBps, maxAccounts) {
  const server = new SDK.Horizon.Server(HORIZON);
  const own = process.env.REBALANCER_SECRET ? SDK.Keypair.fromSecret(process.env.REBALANCER_SECRET) : null;
  const maxRounds = own ? 1 : maxAccounts; // con cuenta propia, una sola ronda (comportamiento de siempre)

  const before = await report('Antes');
  let pool = before;
  let need = fundToSell(pool, targetBps);
  if (need.sell === 0n) { console.log(`Nada que hacer: ya cotiza por encima del ${pct(targetBps, FEE_DEN)}.`); return; }

  const rounds = [], failures = [];
  while (need.sell > 0n && rounds.length < maxRounds) {
    const n = rounds.length + 1;
    console.log(`Ronda ${n}/${maxRounds}${need.partial ? ' (tope de una cuenta: hará falta otra ronda)' : ''}`);
    const kp = own || SDK.Keypair.random();
    let result;
    try {
      result = await sellRound(server, kp, pool, need.sell, own ? 'indicada' : 'desechable');
    } catch (e) {
      if (own || failures.length >= MAX_RETRIES) throw e;
      failures.push({ account: kp.publicKey(), error: e.message });
      console.log(`  la ronda ${n} falló: ${e.message} → reintento ${failures.length}/${MAX_RETRIES} con otra cuenta`);
      await sleep(6000); pool = await readPool(); need = fundToSell(pool, targetBps); // por si la tx entró igual
      continue;
    }
    rounds.push({ n, ...result });
    await sleep(6000); // Horizon tarda un ledger en reflejar las reservas nuevas
    pool = await report(`Ronda ${n}`);
    need = fundToSell(pool, targetBps);
  }

  const after = await report('Después');
  const left = fundToSell(after, targetBps);
  console.log(`Resumen · ${rounds.length} ronda(s)` + (own ? ' con la cuenta indicada' : ` · ${rounds.length} cuenta(s) desechable(s)`)
    + (failures.length ? ` · ${failures.length} intento(s) fallido(s)` : ''));
  for (const r of rounds) {
    console.log(`  ronda ${r.n} · ${r.account} · vendió ${fmt(r.sell)} USDC del fondo (≈ ${fmt(r.expected)} del anchor)`);
    if (r.faucetHash) console.log(`    faucet ${r.faucetHash}`);
    console.log(`    swap   ${r.swapHash}`);
  }
  for (const f of failures) console.log(`  fallido · ${f.account} · ${f.error}`);
  console.log(`  reservas (anchor / fondo): ${fmt(before.anchor)} / ${fmt(before.fund)} → ${fmt(after.anchor)} / ${fmt(after.fund)}`);
  console.log(`  5 USDC del anchor: ${pct(before.quoted, SAMPLE)} → ${pct(after.quoted, SAMPLE)} · objetivo ≥ ${pct(targetBps, FEE_DEN)}`);
  if (left.sell > 0n) {
    console.log(`  Objetivo NO alcanzado: faltan ≈ ${fmt(left.sell)}${left.partial ? '+' : ''} USDC del fondo por vender → vuelve a ejecutar --apply (o sube --max-accounts).`);
    process.exitCode = 2;
  } else {
    console.log('  Objetivo alcanzado.');
  }
}

(async () => {
  const args = process.argv.slice(2);
  const flag = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
  const targetBps = BigInt(flag('--target-bps', 10_050));
  if (targetBps < 9_700n || targetBps > 10_500n) throw new Error('--target-bps debe estar entre 9700 y 10500');
  const maxAccounts = Number(flag('--max-accounts', 3));
  if (!Number.isInteger(maxAccounts) || maxAccounts < 1 || maxAccounts > 6) throw new Error('--max-accounts debe ser un entero entre 1 y 6');
  if (args.includes('--apply')) await apply(targetBps, maxAccounts);
  else await report('Ahora');
})().catch(e => { console.error('ERROR', e.message); process.exit(1); });
