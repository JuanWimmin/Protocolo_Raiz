#!/usr/bin/env node
// Prueba end-to-end de landing/pasarela.html en Chrome headless por CDP (sin puppeteer ni extensión): abre la
// página con ?sobre=, espera la verificación sin red y el estado en la red, crea la cuenta pasarela (friendbot),
// liquida y espera el hash; imprime el DOM relevante y la consola, y guarda una captura (ancho de teléfono).
// Usa el WebSocket nativo de Node (>= 22). No toca claves del proyecto: la pasarela es desechable (friendbot).
//
// Uso (Git Bash):
//   (cd landing && python -m http.server 8765 --bind 127.0.0.1 &)      # o la URL pública raizapp.xyz
//   CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe" PROFILE_DIR=/tmp/raiz-cdp OUT_PNG=/tmp/pasarela.png \
//     node scripts/pasarela_web_e2e.mjs "http://127.0.0.1:8765/pasarela.html?sobre=$(cat sobre.txt)" [--no-liquidar]
// El sobre sale de scripts/deferred_envelope_demo.mjs (real) o del campo sobre_qr del fixture (solo verificación).
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const CHROME = process.env.CHROME;
const PROFILE = process.env.PROFILE_DIR;
const OUT_PNG = process.env.OUT_PNG;
const url = process.argv[2];
const liquidar = !process.argv.includes('--no-liquidar');
const port = 9333;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--hide-scrollbars',
  `--user-data-dir=${PROFILE}`, `--remote-debugging-port=${port}`, '--remote-allow-origins=*', 'about:blank',
], { stdio: 'ignore' });

async function targets() {
  for (let i = 0; i < 60; i++) {
    try { const r = await fetch(`http://127.0.0.1:${port}/json`); return await r.json(); } catch { await sleep(250); }
  }
  throw new Error('DevTools no respondió');
}
const page = (await targets()).find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = (e) => rej(new Error('ws error')); });
let id = 0; const pending = new Map(); const consola = [];
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); return; }
  if (d.method === 'Runtime.consoleAPICalled') consola.push(`[${d.params.type}] ${d.params.args.map((a) => a.value ?? a.description ?? '').join(' ')}`);
  if (d.method === 'Runtime.exceptionThrown') consola.push(`[exception] ${d.params.exceptionDetails.exception?.description ?? d.params.exceptionDetails.text}`);
  if (d.method === 'Log.entryAdded') consola.push(`[log:${d.params.entry.level}] ${d.params.entry.text} ${d.params.entry.url ?? ''}`);
};
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
async function js(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error('JS: ' + (r.result.exceptionDetails.exception?.description ?? r.result.exceptionDetails.text));
  return r.result?.result?.value;
}
const txt = (idEl) => js(`(document.getElementById(${JSON.stringify(idEl)}) || {}).textContent || ''`);
async function waitFor(name, fn, ok, timeoutMs, every = 1000) {
  const t0 = Date.now();
  for (;;) {
    const v = await fn();
    if (ok(v)) return v;
    if (Date.now() - t0 > timeoutMs) throw new Error(`timeout (${name}): último valor ${JSON.stringify(v)}`);
    await sleep(every);
  }
}

try {
  await send('Runtime.enable'); await send('Log.enable'); await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 412, height: 915, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url });
  const pill = await waitFor('verif-pill', () => txt('verif-pill'), (v) => v && v !== '…', 40000);
  console.log('verificación:', pill, '| error:', await txt('verif-error'));
  for (const k of ['v-payer', 'v-merchant-name', 'v-merchant', 'v-tom', 'v-tip', 'v-fee', 'v-debit', 'v-nonce']) console.log(`  ${k}: ${await txt(k)}`);
  const red = await waitFor('v-red', () => txt('v-red'), (v) => v && !/consultando|querying/.test(v), 40000);
  console.log('  v-exp:', await txt('v-exp'));
  console.log('  v-red:', red, '| ledger-pie:', await txt('ledger-pie'));
  console.log('  btn-liquidar.disabled:', await js(`document.getElementById('btn-liquidar').disabled`));

  if (liquidar && /válida|valid/.test(pill)) {
    const pg = await txt('p-g');
    if (pg === '—') {
      await js(`document.getElementById('btn-crear').click()`);
      const xlm = await waitFor('p-xlm', () => txt('p-xlm'), (v) => /^\d/.test(v) && !/^0( |$|·)/.test(v), 60000);
      console.log('pasarela creada:', await txt('p-g'), '| XLM:', xlm);
    } else {
      console.log('pasarela existente:', pg, '| XLM:', await waitFor('p-xlm', () => txt('p-xlm'), (v) => /^\d/.test(v), 30000));
    }
    const disabled = await js(`document.getElementById('btn-liquidar').disabled`);
    if (disabled) { console.log('btn-liquidar deshabilitado: no se liquida'); }
    else {
      await js(`document.getElementById('btn-liquidar').click()`);
      const fin = await waitFor('liquidación', async () => {
        const hash = await txt('liq-hash');
        const errHidden = await js(`document.getElementById('liq-error').hidden`);
        const err = errHidden ? '' : await txt('liq-error');
        return { hash, err };
      }, (v) => v.hash || v.err, 180000, 2000);
      console.log('resultado:', JSON.stringify(fin));
      console.log('  liq-pill:', await txt('liq-pill'));
      console.log('  liq-link:', await js(`document.getElementById('liq-link').href`));
      console.log('  qr hash img:', await js(`!!document.querySelector('#qrhash img')`));
      console.log('  v-red tras liquidar:', await txt('v-red'));
      console.log('  log:\n    ' + (await txt('log')).split('\n').join('\n    '));
    }
  }
  if (OUT_PNG) {
    await js(`window.scrollTo(0, 0)`);
    const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    fs.writeFileSync(OUT_PNG, Buffer.from(shot.result.data, 'base64'));
    console.log('captura:', OUT_PNG);
  }
} catch (e) {
  console.error('FALLO:', e.message);
  process.exitCode = 1;
} finally {
  const errores = consola.filter((l) => /exception|error/i.test(l));
  console.log(`consola: ${consola.length} mensajes, ${errores.length} errores`);
  for (const l of errores.slice(0, 20)) console.log('  ' + l.slice(0, 400));
  ws.close(); chrome.kill();
}
