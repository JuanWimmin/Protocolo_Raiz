#!/usr/bin/env node
// Referencia byte a byte de la SorobanAuthorizationEntry de Pool.pay_merchant (pago diferido por enjambre,
// spec docs/pago_diferido_v0.md §2 y §7b). El pagador Kotlin (kmp-stellar-sdk 1.6.0) debe reproducir
// EXACTAMENTE estos bytes: lo comprueba android/app/src/test/.../deferred/DeferredAuthSpikeTest.kt.
//
// Uso:
//   node scripts/gen-auth-fixture.mjs                 → escribe android/app/src/test/resources/deferred_auth_fixture.json
//   node scripts/gen-auth-fixture.mjs --check         → no escribe; falla si el fixture en disco difiere (determinismo)
//   node scripts/gen-auth-fixture.mjs --verify-tree   → además simula pay_merchant en testnet SIN firmar (modo record)
//                                                       y comprueba que el árbol construido a mano es el que emite el host
//                                                       (VERIFY_TOURIST=G… opcional: cuenta con USDC del fondo; por
//                                                       defecto la wallet semilla del emulador de la demo)
//
// Decisiones:
//   - Credenciales LEGACY (sorobanCredentialsAddress, preimage ENVELOPE_TYPE_SOROBAN_AUTHORIZATION = 9). El SDK JS 17
//     usa por defecto las V2 de CAP-71 (authV2), que el SDK Kotlin 1.6.0 no conoce. Por eso la entrada se construye
//     a mano y se firma con authorizeEntry (que respeta el tipo de credencial) y, en paralelo, a mano: deben coincidir.
//   - El pagador es un keypair de prueba derivado de una semilla fija. Se guarda la semilla en hex, NUNCA el S…
//     (gate H7 del plan: ningún `S[A-Z0-9]{55}` en el repo).
//   - Ninguna fecha ni aleatoriedad: ejecutar dos veces produce el mismo archivo.
//
// Requiere @stellar/stellar-sdk >= 17 (STELLAR_SDK_PATH, instalación normal o el node_modules del repo hermano
// raiz-relayer), igual que scripts/treasury_ttl.js.

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import path from 'node:path';
import fs from 'node:fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

const candidates = [
  process.env.STELLAR_SDK_PATH,
  '@stellar/stellar-sdk',
  path.resolve(__dirname, '../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
  path.resolve(__dirname, '../../../../../raiz-relayer/node_modules/@stellar/stellar-sdk'),
].filter(Boolean);
let SDK;
let sdkPath;
for (const c of candidates) {
  try { SDK = require(c); sdkPath = c; break; } catch (_) { /* siguiente */ }
}
if (!SDK) { console.error('No encuentro @stellar/stellar-sdk (>= 17). Define STELLAR_SDK_PATH.'); process.exit(1); }
let sdkVersion = 'desconocida';
try { sdkVersion = require(path.join(sdkPath, 'package.json')).version; } catch (_) {
  try { sdkVersion = require('@stellar/stellar-sdk/package.json').version; } catch (_2) { /* queda desconocida */ }
}

const { xdr, Address, Keypair, Networks, hash, nativeToScVal, authorizeEntry } = SDK;

// ── Parámetros fijos del fixture ────────────────────────────────────────────────────────────────
const deployments = require(path.resolve(__dirname, '../deployments.json'));
const IDS = { pool: deployments.pool, usdcSac: deployments.usdc_sac, admin: deployments.admin };
const NETWORK = Networks.TESTNET; // 'Test SDF Network ; September 2015'
const PAYER_SEED = createHash('sha256').update('raiz-deferred-fixture-v1').digest(); // 32 bytes, determinista
const payer = Keypair.fromRawEd25519Seed(PAYER_SEED);
const MERCHANT = 'GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN'; // Cafe Don Aurelio (Centro), verificado
const AMOUNT = 25_000_000n;      // 2,5 USDC en stroops
const TIP_BPS = 200;             // 2 %
const FEE_BPS = Number(deployments.protocol_fee_bps); // 50 = 0,5 % (igual que la instancia del Pool en testnet)
const NONCE = 7021436735540711424n;   // ejemplo de la spec §5 (cabe en int64)
const EXP = 6398412;                  // ejemplo de la spec §5
const NEGATIVE_NONCE = -4611686018427387905n; // la mitad de los nonces aleatorios de 64 bits son negativos
const OUT = path.resolve(__dirname, '../android/app/src/test/resources/deferred_auth_fixture.json');
const DEMO_TOURIST = 'GC4VHSZYLB7B3SGYYOV4JAWEG2TT6OJAMT6QTC7HLMP53O5DB3XCQQEH'; // wallet semilla del emulador (demo)

// ── Árbol de autorización (spec §2.1 = contracts/pool/src/lib.rs pay_merchant) ──────────────────
export function split(amount, tipBps, feeBps) {
  // BigInt divide truncando hacia cero, como i128 en Rust.
  const tip = (amount * BigInt(tipBps)) / 10_000n;
  const fee = (amount * BigInt(feeBps)) / 10_000n;
  return { tip, fee, toMerchant: amount - fee };
}

function contractFn(contractId, functionName, args, subInvocations = []) {
  return new xdr.SorobanAuthorizedInvocation({
    function: xdr.SorobanAuthorizedFunction.sorobanAuthorizedFunctionTypeContractFn(
      new xdr.InvokeContractArgs({ contractAddress: new Address(contractId).toScAddress(), functionName, args }),
    ),
    subInvocations,
  });
}

function transferInvocation(usdcSac, from, to, amount) {
  return contractFn(usdcSac, 'transfer', [
    new Address(from).toScVal(),
    new Address(to).toScVal(),
    nativeToScVal(amount, { type: 'i128' }),
  ]);
}

export function payMerchantInvocation({ payer: payerPub, merchant, amount, tipBps, feeBps, ids }) {
  const { tip, fee, toMerchant } = split(amount, tipBps, feeBps);
  const subs = [transferInvocation(ids.usdcSac, payerPub, merchant, toMerchant)];
  if (tip > 0n) subs.push(transferInvocation(ids.usdcSac, payerPub, ids.pool, tip));   // lib.rs: if tip > 0
  if (fee > 0n) subs.push(transferInvocation(ids.usdcSac, payerPub, ids.admin, fee));  // lib.rs: if fee > 0
  return contractFn(ids.pool, 'pay_merchant', [
    new Address(payerPub).toScVal(),
    new Address(merchant).toScVal(),
    nativeToScVal(amount, { type: 'i128' }),
    nativeToScVal(tipBps, { type: 'u32' }),
  ], subs);
}

// ── Entrada, preimage y firma (idéntico a authorizeEntry del SDK para credenciales legacy) ──────
function addressCredentials(payerPub, nonce, exp, signature) {
  return xdr.SorobanCredentials.sorobanCredentialsAddress(new xdr.SorobanAddressCredentials({
    address: new Address(payerPub).toScAddress(),
    nonce: xdr.Int64(nonce),
    signatureExpirationLedger: exp,
    signature,
  }));
}

export function unsignedEntry(payerPub, nonce, exp, invocation) {
  return new xdr.SorobanAuthorizationEntry({
    credentials: addressCredentials(payerPub, nonce, exp, xdr.ScVal.scvVoid()),
    rootInvocation: invocation,
  });
}

export function preimageOf(networkPassphrase, nonce, exp, invocation) {
  return xdr.HashIdPreimage.envelopeTypeSorobanAuthorization(new xdr.HashIdPreimageSorobanAuthorization({
    networkId: hash(networkPassphrase),
    nonce: xdr.Int64(nonce),
    signatureExpirationLedger: exp,
    invocation,
  }));
}

/** Firma a mano (sin authorizeEntry): devuelve todas las piezas intermedias. */
export function signManually({ keypair, networkPassphrase, nonce, exp, invocation }) {
  const preimage = preimageOf(networkPassphrase, nonce, exp, invocation);
  const preimageBytes = preimage.toXdr();
  const payloadHash = hash(preimageBytes);
  const signature = keypair.sign(payloadHash);
  const signatureScVal = xdr.ScVal.scvVec([
    nativeToScVal(
      { public_key: keypair.rawPublicKey(), signature },
      { type: { public_key: ['symbol', null], signature: ['symbol', null] } },
    ),
  ]);
  const entry = new xdr.SorobanAuthorizationEntry({
    credentials: addressCredentials(keypair.publicKey(), nonce, exp, signatureScVal),
    rootInvocation: invocation,
  });
  return { preimage, preimageBytes, payloadHash, signature, signatureScVal, entry };
}

/** Para scripts posteriores (B3): entrada firmada lista para el sobre, con nonce/expiración reales. */
export async function buildSignedEntry({ keypair, merchant, amount, tipBps, feeBps, nonce, exp, ids = IDS, networkPassphrase = NETWORK }) {
  const invocation = payMerchantInvocation({ payer: keypair.publicKey(), merchant, amount, tipBps, feeBps, ids });
  const manual = signManually({ keypair, networkPassphrase, nonce, exp, invocation });
  const viaSdk = await authorizeEntry(unsignedEntry(keypair.publicKey(), nonce, exp, invocation), keypair, exp, networkPassphrase);
  if (viaSdk.toXdr('base64') !== manual.entry.toXdr('base64')) {
    throw new Error('La firma a mano no coincide con authorizeEntry del SDK');
  }
  return { invocation, ...manual };
}

const b64 = (x) => x.toXdr('base64');
const hex = (u8) => Buffer.from(u8).toString('hex');

async function buildFixture() {
  const payerPub = payer.publicKey();
  const { tip, fee, toMerchant } = split(AMOUNT, TIP_BPS, FEE_BPS);
  const base = { payer: payerPub, merchant: MERCHANT, amount: AMOUNT, tipBps: TIP_BPS, feeBps: FEE_BPS, ids: IDS };
  const invocation = payMerchantInvocation(base);
  const unsigned = unsignedEntry(payerPub, NONCE, EXP, invocation);
  const manual = signManually({ keypair: payer, networkPassphrase: NETWORK, nonce: NONCE, exp: EXP, invocation });
  const viaSdk = await authorizeEntry(unsigned, payer, EXP, NETWORK);
  if (b64(viaSdk) !== b64(manual.entry)) {
    throw new Error('La entrada firmada a mano no coincide con authorizeEntry del SDK: revisar el layout de la firma');
  }
  // Round-trip: lo que se escribe debe volver a decodificar a los mismos bytes.
  const roundTrip = xdr.SorobanAuthorizationEntry.fromXdr(b64(manual.entry), 'base64');
  if (b64(roundTrip) !== b64(manual.entry)) throw new Error('Round-trip XDR de la entrada firmada no es estable');
  if (!Keypair.fromPublicKey(payerPub).verify(manual.payloadHash, manual.signature)) throw new Error('La firma no verifica');

  const negative = signManually({ keypair: payer, networkPassphrase: NETWORK, nonce: NEGATIVE_NONCE, exp: EXP, invocation });

  const variant = (over) => {
    const p = { ...base, ...over };
    const s = split(p.amount, p.tipBps, p.feeBps);
    const inv = payMerchantInvocation(p);
    return {
      amount: String(p.amount), tip_bps: p.tipBps, fee_bps: p.feeBps,
      tip: String(s.tip), fee: String(s.fee), to_merchant: String(s.toMerchant),
      sub_invocations: inv.subInvocations.length,
      invocation_xdr: b64(inv),
    };
  };

  const envelope = {
    v: 1,
    net: NETWORK,
    payer: payerPub,
    merchant: MERCHANT,
    amount: String(AMOUNT),
    tip_bps: TIP_BPS,
    fee_bps: FEE_BPS,
    nonce: String(NONCE),
    exp: EXP,
    l0: 6389772,
    t0: 1761393600,
    auth: b64(manual.entry),
  };
  const envelopeB64url = Buffer.from(JSON.stringify(envelope), 'utf8').toString('base64url');

  return {
    generated_by: 'scripts/gen-auth-fixture.mjs',
    sdk: `@stellar/stellar-sdk ${sdkVersion}`,
    credentials: 'sorobanCredentialsAddress (legacy, sin CAP-71)',
    preimage_envelope_type: 9,
    network_passphrase: NETWORK,
    network_id_hex: hex(hash(NETWORK)),
    payer_public: payerPub,
    payer_seed_hex: hex(PAYER_SEED),
    payer_seed_derivation: 'sha256("raiz-deferred-fixture-v1")',
    merchant: MERCHANT,
    pool: IDS.pool,
    usdc_sac: IDS.usdcSac,
    admin: IDS.admin,
    amount: String(AMOUNT),
    tip_bps: TIP_BPS,
    fee_bps: FEE_BPS,
    tip: String(tip),
    fee: String(fee),
    to_merchant: String(toMerchant),
    nonce: String(NONCE),
    signature_expiration_ledger: EXP,
    invocation_xdr: b64(invocation),
    invocation_json: invocation.toJson(),
    entry_unsigned_xdr: b64(unsigned),
    preimage_xdr: Buffer.from(manual.preimageBytes).toString('base64'),
    payload_hash_hex: hex(manual.payloadHash),
    signature_hex: hex(manual.signature),
    signature_scval_xdr: b64(manual.signatureScVal),
    entry_signed_xdr: b64(manual.entry),
    entry_signed_bytes: manual.entry.toXdr().length,
    variants: {
      tip0: variant({ tipBps: 0 }),
      fee0: variant({ feeBps: 0 }),
      trunc: variant({ amount: 33_333_333n }),
      tiny: variant({ amount: 40n }),
      negative_nonce: {
        nonce: String(NEGATIVE_NONCE),
        preimage_xdr: Buffer.from(negative.preimageBytes).toString('base64'),
        payload_hash_hex: hex(negative.payloadHash),
        signature_hex: hex(negative.signature),
        entry_signed_xdr: b64(negative.entry),
      },
    },
    envelope,
    envelope_b64url: envelopeB64url,
    envelope_b64url_chars: envelopeB64url.length,
  };
}

// ── Verificación del árbol contra el host (simulación sin firma, modo record) ──────────────────
async function verifyTree() {
  const tourist = process.env.VERIFY_TOURIST || DEMO_TOURIST;
  const amount = 5_000_000n; // 0,5 USDC
  // La fuente de la tx es OTRA cuenta (el admin existe siempre): así el host devuelve credenciales de
  // dirección para el turista (como en el pago diferido) y no credenciales de cuenta fuente.
  const sourceId = process.env.VERIFY_SOURCE || IDS.admin;
  const server = new SDK.rpc.Server('https://soroban-testnet.stellar.org');
  const source = await server.getAccount(sourceId);
  const contract = new SDK.Contract(IDS.pool);
  const tx = new SDK.TransactionBuilder(source, { fee: '500000', networkPassphrase: NETWORK })
    .addOperation(contract.call(
      'pay_merchant',
      new Address(tourist).toScVal(),
      new Address(MERCHANT).toScVal(),
      nativeToScVal(amount, { type: 'i128' }),
      nativeToScVal(TIP_BPS, { type: 'u32' }),
    ))
    .setTimeout(60)
    .build();
  const expected = b64(payMerchantInvocation({ payer: tourist, merchant: MERCHANT, amount, tipBps: TIP_BPS, feeBps: FEE_BPS, ids: IDS }));
  let ok = true;
  for (const upgraded of [false, true]) {
    let sim;
    try {
      sim = await server.simulateTransaction(tx, undefined, undefined, upgraded);
    } catch (e) {
      console.log(`  useUpgradedAuth=${upgraded}: la simulación lanzó ${e.message}`);
      ok = false;
      continue;
    }
    if (SDK.rpc.Api.isSimulationError(sim)) {
      console.log(`  useUpgradedAuth=${upgraded}: error de simulación: ${sim.error}`);
      ok = false;
      continue;
    }
    const auth = sim.result?.auth ?? [];
    const entry = auth[0];
    const same = entry && b64(entry.rootInvocation) === expected;
    const creds = entry?.credentials?.type;
    const addr = creds && creds !== 'sorobanCredentialsSourceAccount'
      ? ` nonce=${entry.credentials.address?.nonce ?? entry.credentials.addressV2?.nonce} exp=${entry.credentials.address?.signatureExpirationLedger ?? entry.credentials.addressV2?.signatureExpirationLedger}`
      : '';
    console.log(`  useUpgradedAuth=${upgraded}: ${auth.length} entrada(s); credenciales=${creds}${addr}; ` +
      `subInvocations=${entry?.rootInvocation?.subInvocations?.length}; árbol ${same ? 'IGUAL al construido a mano' : 'DISTINTO'}`);
    if (entry && !same) {
      console.log('    host:  ' + b64(entry.rootInvocation));
      console.log('    mano:  ' + expected);
    }
    if (!same) ok = false;
  }
  return ok;
}

async function main() {
  const args = new Set(process.argv.slice(2));
  const fixture = await buildFixture();
  const json = JSON.stringify(fixture, null, 2) + '\n';
  if (args.has('--check')) {
    const onDisk = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : null;
    if (onDisk !== json) { console.error(`DIFIERE: ${OUT} no coincide con lo generado. Ejecuta sin --check.`); process.exit(2); }
    console.log(`OK: ${path.relative(process.cwd(), OUT)} está al día.`);
  } else {
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, json, 'utf8');
    console.log(`Escrito ${path.relative(process.cwd(), OUT)}`);
  }
  console.log(`  SDK ${fixture.sdk} · pagador ${fixture.payer_public}`);
  console.log(`  montos: amount=${fixture.amount} tip=${fixture.tip} fee=${fixture.fee} to_merchant=${fixture.to_merchant}`);
  console.log(`  payload hash ${fixture.payload_hash_hex}`);
  console.log(`  entrada firmada: ${fixture.entry_signed_bytes} bytes XDR · ${fixture.entry_signed_xdr.length} chars base64 · ` +
    `sobre §5: ${fixture.envelope_b64url_chars} chars base64url`);
  if (args.has('--verify-tree')) {
    console.log('Verificando el árbol contra la simulación de testnet (sin firma):');
    const ok = await verifyTree();
    if (!ok) { console.error('ÁRBOL NO VERIFICADO'); process.exit(3); }
    console.log('OK: el árbol §2.1 coincide con el que emite el host.');
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
