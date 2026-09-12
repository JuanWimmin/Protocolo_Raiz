# D2 — Ejecuciones con transacción real enlazada (2026-09-12)

Objetivo SOW D2: cada Execution del dashboard enlaza a su transacción real en Stellar Expert
(el `tx_hash` que guarda el contrato es un sha256 determinístico — un ID de auditoría — y no un
hash de transacción). Continúa la siembra de `siembra_2026-09-06.md`.

Red: **testnet** · Treasury `CACZWU3BXMCHI23CFN2GTPWCGSQKABMYF7EOMA2J63RMGAEZVXDFPATB` ·
Governance `CBBYI45J3VWQ53QATRWTARCFWNIG7EEZTFCS5OXJWS7KRCPOHQXHAL32`.

## Ejecuciones con evento `execution` en la ventana del RPC (4)

| # | Barrio | Propuesta | Monto | Ejecutada (UTC) | Ledger | tx `execute_proposal` | Stellar Expert |
|---|---|---|---|---|---|---|---|
| 1 | Norte | Bancas para el parque | 0.15 USDC | 2026-09-06 16:48:57 | 4537950 | `91c1c35c0a796970bbb5bf59da1c79d23339969eb0325c3d00206219cf04596d` | https://stellar.expert/explorer/testnet/tx/91c1c35c0a796970bbb5bf59da1c79d23339969eb0325c3d00206219cf04596d |
| 2 | Costa | Limpieza de la playa | 0.30 USDC | 2026-09-06 16:49:07 | 4537952 | `9ca0628758b556ea05eb89780cc44ec06d22a822853ce206fb41e630753b4a18` | https://stellar.expert/explorer/testnet/tx/9ca0628758b556ea05eb89780cc44ec06d22a822853ce206fb41e630753b4a18 |
| 3 | Centro | Luces para el parque infantil | 0.05 USDC | 2026-09-12 21:02:32 | 4644673 | `aa303a0f7ed6459994650d28c426a0fda02389085fe80853208b3dc09290f5f1` | https://stellar.expert/explorer/testnet/tx/aa303a0f7ed6459994650d28c426a0fda02389085fe80853208b3dc09290f5f1 |
| 4 | Norte | Kit de reciclaje comunitario | 0.05 USDC | 2026-09-12 21:03:02 | 4644679 | `db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3` | https://stellar.expert/explorer/testnet/tx/db0bcd5f7d2eab2cab0aa6dc60d0277161353c3392e497af3d8f2e67ee94eac3 |

#3 y #4 se ejecutaron hoy por CLI (`stellar contract invoke … -- execute_proposal`, firmante
`raiz-admin`; la llamada es trustless) porque no había dispositivo conectado para dispararlas desde
la app. Al ejecutar #3 el Treasury rescató primero toda la posición de Blend del Centro
(1 894 174 bTokens → 2 000 572 stroops USDC, hallazgo H9 conocido) y luego pagó 0.05 USDC al comercio.

Los 4 eventos se leyeron con `getEvents` (contrato Treasury, `topic[0] = execution`) y el campo
`txHash` de cada evento es el de la tabla. Verificación reproducible sin la app
(`@stellar/stellar-sdk` 17, misma lógica que la landing): ver "Cómo verificar" abajo.

## Sembradas hoy — ejecutables desde el 2026-09-15 ≈ 21:03 UTC (duración 3 días)

Para capturar el **camino feliz** (hash mostrado por la propia app al pulsar "Ejecutar trustless")
en dispositivo, y para que haya ejecuciones con evento en ventana cuando se arme el paquete WP4:

| # | Barrio | Propuesta | Monto | Votos | tx `create_proposal` |
|---|---|---|---|---|---|
| 5 | Centro | Bancas y sombra para la plaza | 0.05 USDC | 3/5 (60 %) | `b2a0791fe1dbbfbd2306099dd6828b180847fa9921a571a9a4752ded4195c544` |
| 6 | Norte | Huerta comunitaria del barrio | 0.05 USDC | 2/3 (66 %) | `f8d8bf4f769a82d7a017fe895692b11f7772c9c7874afdf1929c4cd01bfbf8d1` |

Votos #5: `2c39fd6f…f8d6`, `83f392d5…0a95a`, `0e9a9a51…8697d`. Votos #6: `3af5107e…bdcc4`, `01e8bf79…dab4c`.

## Hallazgos del RPC que condicionan el diseño (medidos hoy, Protocol 28)

1. **Retención ≈ 7 días, no 24 h**: `getHealth` → `ledgerRetentionWindow = 120 960` ledgers. Los
   eventos del 6-sep siguen visibles el 12-sep. El cliente arranca en `oldestLedger` (+200 de
   margen porque la ventana avanza mientras se pagina), no en una ventana fija.
2. **`getEvents` escanea ~10 000 ledgers por llamada** y devuelve `cursor` aunque la página venga
   vacía; al llegar al último ledger el cursor **se repite** en vez de volver `null`. Cubrir la
   retención completa son ~13 páginas. Cortar en la primera página vacía (como hace
   `tourPaymentEvents`) habría devuelto 0 ejecuciones: el nuevo `executionEvents` para cuando el
   cursor deja de avanzar o cuando su ledger (32 bits altos del TOID) alcanza el último conocido.
3. Ejecuciones más viejas que la ventana → `get_execution_log` las sigue listando pero sin hash
   real: la UI las marca "histórica · sin evento en la ventana del RPC" y **no inventa enlaces**.
   Las que dispara la propia app quedan cacheadas por `proposal_id` en el dispositivo
   (`ExecutionHashStore`) y conservan el enlace pasada la ventana.

## Qué cambió en el código (rama `worktree-wp2-tx-hash-real`)

- App: `SorobanClient.executionEvents(barrioId)` (getEvents del Treasury, paginación completa) y
  `executeProposal` devuelve el hash real (`buildInvoke` + `signAndSubmit` → `getTransactionResponse.txHash`).
  `Execution.realTxHash` / `verified`; `ExecutionHashStore` (caché local del camino feliz).
  Dashboard: chip "Ver en Stellar Expert" por ejecución verificada, contador "N con transacción
  verificable", estado "histórica" sin link; la card de la propuesta muestra el hash al instante
  tras ejecutar. El sha256 del contrato ya no se usa en la UI (solo como espejo del struct).
- Landing (`landing/index.html`, sección "05 / La prueba"): bloque "Ejecuciones del fondo · Treasury"
  con snapshot estático de las 4 filas y reemplazo en vivo por `getEvents` (misma paginación).
  Pendiente de copiar al repo Pages (`JuanWimmin/JuanWimmin.github.io`) como siempre.
- Spec (`docs/raiz_v2_spec_contratos.md`, Contrato 3) y `docs/RaizModels.kt`: nota sobre
  `tx_hash` = ID de auditoría; `realTxHash` y `ExecutionEvent` documentados como solo-cliente.

## Cómo verificar (sin la app)

```bash
# Lista los eventos `execution` del Treasury en toda la ventana del RPC con su txHash
node - <<'EOF'
const SDK = require('@stellar/stellar-sdk');            // ≥ 17
const s = new SDK.rpc.Server('https://soroban-testnet.stellar.org');
const f = { type: 'contract', contractIds: ['CACZWU3BXMCHI23CFN2GTPWCGSQKABMYF7EOMA2J63RMGAEZVXDFPATB'] };
(async () => {
  const h = await s.getHealth(); let acc = [], prev = null;
  let r = await s.getEvents({ startLedger: Number(h.oldestLedger) + 200, filters: [f], limit: 100 });
  for (let n = 1; n < 30; n++) {
    acc.push(...r.events); const c = r.cursor;
    if (!c || c === prev || Number(BigInt(c.split('-')[0]) / 4294967296n) >= Number(h.latestLedger)) break;
    prev = c; r = await s.getEvents({ filters: [f], cursor: c, limit: 100 });
  }
  for (const e of acc) console.log(e.ledgerClosedAt, SDK.scValToNative(e.value)[0], e.txHash);
})();
EOF
```

Salida del 2026-09-12: 4 filas (#1, #2, #3, #4) con los hashes de la tabla.

## Pendiente para cerrar D2 (necesita dispositivo)

- [ ] Instalar el APK de la rama en el Motorola G04 y capturar el Dashboard (Centro y Norte) con
      los chips "Ver en Stellar Expert" y el contador de verificadas → `capturas/`.
- [ ] Desde el **15-sep ≈ 21:03 UTC**: ejecutar #5 (Centro) desde la app → captura de la card con
      "✓ Ejecutada on-chain · tx …" y el chip (camino feliz); #6 (Norte) como reserva.
- [ ] Captura de la landing (sección "05 / La prueba" con el tag "en vivo · N con tx verificable ●")
      tras copiarla al repo Pages.
- [ ] Probar los 4 links de Stellar Expert en incógnito (WP4).
