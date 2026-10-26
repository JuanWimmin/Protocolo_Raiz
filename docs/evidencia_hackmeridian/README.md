# RAÍZ — HackMeridian 2026 evidence package: deferred payments over a device swarm

> **Status as of 2026-10-26 (before the Monday submission).** Everything built at the hackathon lives in the branch
> [`feat/pago-diferido`](https://github.com/JuanWimmin/Protocolo_Raiz/tree/feat/pago-diferido) of this repository, on
> top of the tag `hackmeridian-start` (commit `1361b38`, 2026-10-10: the last commit before the event). The event's
> work is the diff
> [`hackmeridian-start...feat/pago-diferido`](https://github.com/JuanWimmin/Protocolo_Raiz/compare/hackmeridian-start...feat/pago-diferido):
> 26 commits, 68 files, +14 288 / −200 lines (`git diff --shortstat hackmeridian-start..a782f51`).
> Network: Stellar **testnet**. **No new contract was deployed** (rule H2 of the plan): every transaction below calls
> `Pool.pay_merchant` exactly as deployed on 2026-07-31. Screenshots and video are **not in this folder yet**
> (section 5 lists what is pending). Resumen en español al final.

Sources of truth: the frozen spec [`docs/pago_diferido_v0.md`](../pago_diferido_v0.md) (what it solves and what it
does not, roles, the protocol piece, math, threat model, state machine, tests with hashes) and the plan with the
"Resultado" block of each stage, [`docs/PLAN_CLAUDE_CODE_HACKMERIDIAN.md`](../PLAN_CLAUDE_CODE_HACKMERIDIAN.md)
(B1–B4). This page follows the format of the previous evidence package,
[`docs/evidencia_sow/README.en.md`](../evidencia_sow/README.en.md).

## In one paragraph

A visitor **without signal** signs on the phone the `SorobanAuthorizationEntry` of
`Pool.pay_merchant(tourist, merchant, amount, tip_bps)` — legacy address credentials, preimage
`ENVELOPE_TYPE_SOROBAN_AUTHORIZATION`, covering the whole invocation tree: the root call plus the three `transfer`s of
the fund's USDC (merchant, Pool tip, protocol fee). The signed entry travels as an **envelope** ("sobre"):
`raiz:sobre:1:` + base64url of the XDR (1 229 characters, QR version 29). Any device with signal rebuilds the
transaction with **its own account as source and fee payer**, simulates in enforcing mode and submits; the network
guarantees uniqueness (nonce, consumed on-chain) and expiration (`signature_expiration_ledger`). Four settlement
paths were built during the event and each one has a transaction on testnet whose source account is **not** the
payer: a Kotlin integration test, a terminal gateway, a public web gateway and the app itself acting as carrier.

| Stage | What | Where in the repo | On-chain evidence | Status |
|---|---|---|---|---|
| **B1** | Sign offline with `kmp-stellar-sdk` 1.6.0, byte-for-byte against a `@stellar/stellar-sdk` 17 fixture; another account submits | `data/deferred/AuthTreeBuilder.kt`, `DeferredAuthSigner.kt`, `scripts/gen-auth-fixture.mjs`, `android/app/src/test/resources/deferred_auth_fixture.json`, `DeferredAuthSpikeTest` (16 tests), `DeferredAuthTestnetIT` | [`ebee152c…6d47`](https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47) | Done 2026-10-25 ([plan, B1 result](../PLAN_CLAUDE_CODE_HACKMERIDIAN.md#b1--spike-sdk-13001400-timebox-estricto)) |
| **B2** | Payer in the app: "Firmar sin señal", envelope screen with QR, "Mis sobres", closure decided by the network | `data/deferred/{SobreCodec,SobreValidator,LedgerEstimator,DeferredStore,DeferredPaymentManager}.kt`, `data/network/ConnectivityMonitor.kt`, `data/local/MerchantCache.kt`, `ui/pay/DeferredSignedScreen.kt`, `ui/deferred/DeferredListScreen.kt` | [`cec2388a…1c34`](https://stellar.expert/explorer/testnet/tx/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34) (envelope of the emulator's wallet, settled by the terminal gateway) | Done 2026-10-25 ([CLAUDE.md, "Estado actual"](../../CLAUDE.md)) |
| **B3** | Gateways: terminal (`scripts/pasarela_cli.mjs`) and public web (`landing/pasarela.html` → <https://raizapp.xyz/pasarela.html>) | plus `scripts/deferred_envelope_demo.mjs`, `scripts/pasarela_web_e2e.mjs` | [`27c61461…9f47`](https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47) (web gateway, headless Chrome) | Done 2026-10-25 ([plan, B3 result](../PLAN_CLAUDE_CODE_HACKMERIDIAN.md#b3--pasarela-web-pública-15001830-sesión-de-claude-code-aparte)) |
| **B4** | Receiver roles in the app (COBRO = the merchant, PORTADOR = a carrier) and automatic settlement on both phones | `data/deferred/{SobreSettler,SobreSubmit,ReceivedSobre,ReceivedSobrePolicy,ReceivedSobreStore,ReceivedSobreVerifier,SobreInbox,SobrePaymentsCache}.kt`, `data/stellar/{EventsPaging,SobreLookup}.kt`, `ui/cobros/*`, `ui/deferred/RecibirSobre*` | [`79a4c858…eee2`](https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2) (carrier from the app, emulator) | Done 2026-10-26 ([plan, B4 result](../PLAN_CLAUDE_CODE_HACKMERIDIAN.md#b4--cobrador-en-la-app-18302030-solo-si-b2-y-b3-cerraron)) |

## 1. What was built during HackMeridian

`git log --oneline hackmeridian-start..HEAD` at `a782f51` (26 commits, all dated 2026-10-25 and 2026-10-26, Lisbon
time), grouped by stage. Every file named here is in the diff linked above (`A` = added during the event).

### B1 — the protocol piece: fixture first, Kotlin after

| Commit | What |
|---|---|
| `5e671d4` | `scripts/gen-auth-fixture.mjs` (A): Node + `@stellar/stellar-sdk` 17.0.1 builds the `pay_merchant` invocation tree of spec §2.1 with a fixed test keypair, nonce and expiration, signs it with `authorizeEntry` **and** by hand, and writes `android/app/src/test/resources/deferred_auth_fixture.json` (A): preimage, hash, signature, signed entry (912 bytes of XDR), the `sobre_qr` string (1 229 chars) and variants (tip 0, fee 0…). `--check` proves it is deterministic; `--verify-tree` simulates `pay_merchant` on testnet unsigned and checks that the hand-built tree equals the one the host emits. |
| `32fd9bc` | `data/deferred/AuthTreeBuilder.kt` (A): root + up to three `transfer` sub-invocations with the contract's integer math (`i128`, truncation). `data/deferred/DeferredAuthSigner.kt` (A): preimage `ENVELOPE_TYPE_SOROBAN_AUTHORIZATION`, SHA-256, ed25519, no network. |
| `7b6e0dc` | `DeferredAuthSpikeTest` (A, 16 tests): invocation, unsigned entry, preimage, hash, signature, signed entry, the SDK's own `Auth.authorizeEntry`, round-trip, `verify` and the variants, all compared byte for byte with the fixture. `DeferredAuthTestnetIT` (A): a disposable friendbot account submits the entry signed by the emulator's wallet; off by default (`RAIZ_DEFERRED_IT=1` plus `RAIZ_DEFERRED_PAYER_MNEMONIC` or `RAIZ_DEFERRED_PAYER_SECRET`; no secret in the repo). |
| `297f43d` | Spec §3.1 updated with what was measured: ledger close 5.000 s over 199 consecutive ledgers; the network accepts `E − L ≤ max_entry_ttl − 1 = 3 110 399` ledgers; replay → `Error(Auth, ExistingValue)`; expired → `Error(Auth, InvalidInput)`; Protocol 29 still accepts legacy credentials and emits CAP-71 V2 only when asked. |

### B2 — the payer in the app

| Commit | What |
|---|---|
| `978d0ea` | `data/deferred/LedgerEstimator.kt` (A): ledger estimated without network from the last known `(L0, t0)` anchor with the **monotonic** clock (`LedgerMath.window`, spec §3.1). `data/deferred/DeferredStore.kt` (A): the payer's envelopes and their states, per wallet (SharedPreferences + kotlinx.serialization; the project has no Room/DataStore). |
| `8fa132a` | `data/network/ConnectivityMonitor.kt` (A, with a `forceOffline` debug switch), `data/local/MerchantCache.kt` (A: without signal, merchants come from the cache filled while online, no mocks), `data/deferred/DeferredPaymentManager.kt` (A: payer orchestrator). |
| `450bb13` | `ui/pay/PayScreen.kt` + `PayViewModel.kt`: the "Firmar sin señal" mode is decided by connectivity **before** confirming, never after a failed online payment. `ui/pay/DeferredSignedScreen.kt` (A): QR (`QrUtils.kt`), detail, "válido hasta ~hh:mm (estimado)", "Copiar sobre" and the honest text that it is a signed promise, not confirmed money; state labels in `ui/deferred/DeferredLabels.kt` (A). |
| `b32fb22` | "Mis sobres" history (`ui/deferred/DeferredListScreen.kt`, `DeferredListViewModel.kt`, A), confirmation by hash, row on Home. |
| `77cef2a` | The envelope is the signed XDR with the `raiz:sobre:1:` prefix (`data/deferred/SobreCodec.kt`, `SobreValidator.kt`, A), not the JSON of spec §5: one source of truth, no "JSON and XDR disagree" error class. Envelopes belong to a wallet, not to the app. Closure is decided by the network: nonce consumed (`SorobanClient.nonceConsumed`, a temporary entry under the payer's account) or a hash whose auth is the envelope. |
| `fc41591` | Tests of the codec and validator of the XDR envelope (`SobreCodecTest`, A), of the 11 520-ledger window and of the state transitions (`LedgerMathTest`, `DeferredPolicyTest`, both added in `978d0ea`). |
| `58e78ad` | Spec: envelope format (XDR only), **W = 11 520 ledgers** (12 h guaranteed in the worst case of clock drift after 24 h without signal; about 16 h with the measured 5 s close), the protocol-fee debt (see section 4), the envelope contract for B3. |
| `2082769` | Fix: the synchronizer recovers the hash only from a transaction whose auth is the envelope. |

### B3 — gateways: anyone with signal settles

| Commit | What |
|---|---|
| `d391756` | `scripts/pasarela_cli.mjs` (A): terminal gateway. Verifies without network (prefix, XDR, root `pay_merchant` of the Pool, signer = payer, tree rebuilt with `fee_bps` 50 identical byte for byte, ed25519 over the type-9 preimage) → reads the ledger (if E has passed, nothing is spent) → nonce → transaction with the gateway as source and `auth = [envelope]` → simulation in enforce mode → assemble (keeps the auth) → sign → send → hash plus proof that the nonce is consumed. Disposable friendbot account, or `PASARELA_SECRET`; `--dry-run` only verifies and simulates. |
| `5b33650` | `landing/pasarela.html` (A): static page, no build, `@stellar/stellar-sdk@17.0.1`, `html5-qrcode@2.3.8` and `qrcode-generator@1.4.4` from jsDelivr; scan with the camera or paste; same rules as the CLI; state on the network (current ledger, nonce via `getLedgerEntries`, merchant name via a simulated `get_merchant`); gateway account kept in `localStorage` (friendbot or a pasted secret, XLM balance from Horizon); settle → hash, Stellar Expert link and a QR of the hash; errors translated; ES/EN; `?sobre=` and `#sobre=` preload. `scripts/deferred_envelope_demo.mjs` (A): a **real** envelope from a testnet wallet (secret only through `DEFERRED_PAYER_SECRET`, never printed), random nonce, E = current ledger + window. |
| `a5de85d`, `5fdddaa` | `scripts/pasarela_web_e2e.mjs` (A): end-to-end test of the page in headless Chrome over CDP (no Puppeteer, no extension): opens `?sobre=`, waits for the offline verification and the network state, creates the gateway account, settles and waits for the hash; saves a phone-width screenshot. |
| `d077c7a` | Fix: QR reader nearly full screen and at high resolution. |

Published copy: <https://raizapp.xyz/pasarela.html> (GitHub Pages, repo `JuanWimmin/JuanWimmin.github.io`). Checked
on 2026-10-26: HTTP 200 and the served file is byte-identical (MD5 after LF normalization) to `landing/pasarela.html`
at `a782f51`.

### B4 — receiver roles and automatic settlement on both phones

| Commit | What |
|---|---|
| `48b8ccf` | Data layer. `SorobanClient.submitSobre` (extracted from the spike: enforce simulation before signing, fee cap, pre-send journal, nonce checked right before sending, resend only on `TRY_AGAIN_LATER`, confirmation by hash with the network's time). `data/deferred/SobreSettler.kt` (A): one orchestrator for the payer's own envelopes and the received ones — lock per envelope and per source account, resume by hash, attempts with backoff, started on connectivity transitions, respects the forced "sin señal" of debug. `ReceivedSobre.kt`, `ReceivedSobrePolicy.kt`, `ReceivedSobreStore.kt`, `ReceivedSobreVerifier.kt`, `SobrePaymentsCache.kt`, `SobreSubmit.kt` (A): role COBRO if the recipient is my account, PORTADOR otherwise; verification without network plus an idempotent nonce index (same envelope = "already registered"; same nonce with another auth = fraud); validity from the real ledger or an estimate with a 360-ledger margin plus drift by the anchor's age; cap only for COBRO (20 USDC by default, editable). `data/stellar/EventsPaging.kt`, `SobreLookup.kt` (A): before declaring an envelope expired, its hash is searched in the events since the anchor (the nonce entry is temporary and disappears when it expires). Tests (A): `ReceivedSobrePolicyTest`, `ReceivedSobreStoreTest`, `ReceivedSobreVerifierTest`, `SobrePaymentsCacheTest`, `DeferredStoreTest`, `SobreErrorsTest`, `EventsPagingTest`, `SobreLookupTest`, `SobreEventsTestnetIT` (off by default, `RAIZ_EVENTS_IT`). |
| `5a8a1e9` | UI. Cobros (`ui/cobros/CobrosScreen.kt`, `CobrosViewModel.kt`, `CobroComponents.kt` A): "Escanear sobre", "Pegar sobre", verdict, "Pagos en tránsito" with honest state, detail with "Liquidar ahora" or "Copiar sobre y abrir la pasarela" (passkey wallets), editable cap, offline banner; the screen renders without network. Any wallet: the Home scanner recognizes an envelope and opens "Recibir sobre" (`ui/deferred/RecibirSobreScreen.kt`, `RecibirSobreViewModel.kt`, `RecibidoComponents.kt`, `data/deferred/SobreInbox.kt` A), with manual paste; "Mis sobres" gains "Recibidos". `MainActivity.kt` starts the settler after unlock. |
| `a782f51` | The envelopes row on Home is always visible; docs of B4 in the plan, the spec and `CLAUDE.md`. |

Rules of the receiver's state machine (spec §6, decided after an adversarial review): "Cobrado" **only** with a hash
whose auth is the envelope; a consumed nonce without a hash stays "en tránsito, buscando el comprobante"; the nonce
seen in another payment, or expired without a hash, is RECHAZADO (fraud); nothing is rejected for transient causes
(funds, trustline, unverified merchant, source-account codes, network) — they postpone with a reason and the chain
decides; fee cap 100 XLM for own envelopes, 2 XLM automatic and 100 XLM manual for received ones.

### Maintenance fixes shipped in the same branch (not part of the feature)

Found while preparing the demo environment, documented as gotchas in `CLAUDE.md`: `c1a7f2d` merchant reads tolerant
to Protocol 29 auto-restore (`SorobanClient.simulateRead`), `ee2885a` merchant role resolved with `get_merchant`,
`5ca67eb` wording of the deposit price notice, `80d9419` anchor-pool rebalance in several rounds
(`scripts/rebalance_anchor_pool.js --max-accounts`). `eaea801` and `956bb35` are documentation (spec, plan, gotchas).

### Tests

`./gradlew :app:testDebugUnitTest`: **315 tests, 0 failures, 3 skipped** (the three testnet integration tests, off
by default). Last local run: 2026-10-26 02:09 (`android/app/build/test-results/testDebugUnitTest/`). Of those, 184
belong to the event's work: `DeferredAuthSpikeTest` 16, `DeferredAuthTestnetIT` 2 (skipped), `DeferredPolicyTest` 24,
`DeferredStoreTest` 9, `LedgerMathTest` 11, `ReceivedSobrePolicyTest` 31, `ReceivedSobreStoreTest` 14,
`ReceivedSobreVerifierTest` 17, `SobreCodecTest` 12, `SobreErrorsTest` 15, `SobrePaymentsCacheTest` 6,
`EventsPagingTest` 12, `SobreEventsTestnetIT` 1 (skipped), `SobreLookupTest` 4, `DeferredLabelsTest` 10. The 131
tests that existed before the event (`CLAUDE.md`, app 0.4.0) still pass.

## 2. Transactions: hash → Stellar Expert → what it proves

All four were re-read from Horizon on 2026-10-26 (`GET https://horizon-testnet.stellar.org/transactions/<hash>` and
`…/transactions/<hash>/operations`). In every one: `successful: true`, `operation_count: 1`, operation
`invoke_host_function` on the Pool `CD775D33…KBE2` with function `pay_merchant`, and three `asset_balance_changes`
of type `transfer` of the fund's USDC (issuer `GATALTGT…5V56`) **leaving the payer's account**: 99.5 % to the
merchant, 2 % to the Pool (Tip Barrio) and 0.5 % to the admin (protocol fee). In every one the `source_account`
(who paid the fee) is a **different account** from the payer whose USDC moved: that is the whole point — the gateway
contributes XLM and connectivity, nothing else, and cannot alter amount, recipient or tip.

| # | Path | Transaction | Source = fee payer (Horizon `source_account`) | Payer (the `from` of the three transfers) | Merchant | USDC moved | `fee_charged` | Ledger · time (UTC) |
|---|---|---|---|---|---|---|---|---|
| 1 | **B1, Kotlin** — `DeferredAuthTestnetIT`: the entry signed by the emulator's seed wallet, submitted by a disposable friendbot account | [`ebee152c…6d47`](https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47) · [Horizon](https://horizon-testnet.stellar.org/transactions/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47/operations) | [`GCCXC2E2…2WRH`](https://stellar.expert/explorer/testnet/account/GCCXC2E2SZ6454YCR43MYSG7ELKBMGGZP425QIZA3XBFKYCF6SYG2WRH) | [`GC4VHSZY…QQEH`](https://stellar.expert/explorer/testnet/account/GC4VHSZYLB7B3SGYYOV4JAWEG2TT6OJAMT6QTC7HLMP53O5DB3XCQQEH) (emulator seed wallet) | Cafe Don Aurelio [`GA7CDZDQ…4TSN`](https://stellar.expert/explorer/testnet/account/GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN) | 0.4975 + 0.0100 + 0.0025 (= 0.5 USDC, tip 2 %) | 286 548 714 stroops = **28.6548714 XLM** (see note) | 5 384 473 · 2026-10-25 16:32:32 |
| 2 | **Terminal gateway** — `scripts/pasarela_cli.mjs`: second payment of the same tourist in Centro, settled from a disposable friendbot account | [`cec2388a…1c34`](https://stellar.expert/explorer/testnet/tx/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34) · [Horizon](https://horizon-testnet.stellar.org/transactions/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34/operations) | [`GD2Q2CZU…JLVF`](https://stellar.expert/explorer/testnet/account/GD2Q2CZUBC5ODRIW2ZRCGWLMBHZYQUOL26TD3RI7BG6KWBXTFTA3JLVF) | `GC4VHSZY…QQEH` (same wallet as #1) | Restaurante La Boveda [`GAGVM6AT…DVIN`](https://stellar.expert/explorer/testnet/account/GAGVM6ATSKHLYJV3VBOEG6UKDFZZ6BVSEGF6CJO3FFSZF46JNZUBDVIN) | 4.975 + 0.100 + 0.025 (= 5 USDC, tip 2 %) | 23 414 stroops = **0.0023414 XLM** | 5 388 242 · 2026-10-25 21:46:37 |
| 3 | **Public web gateway** — `landing/pasarela.html` driven by `scripts/pasarela_web_e2e.mjs` (headless Chrome, CDP): envelope of the CLI identity `raiz-tourist` made with `scripts/deferred_envelope_demo.mjs`; the page created its own gateway account with friendbot | [`27c61461…9f47`](https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47) · [Horizon](https://horizon-testnet.stellar.org/transactions/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47/operations) | [`GADSC7Z5…43MO`](https://stellar.expert/explorer/testnet/account/GADSC7Z5UV7APTZ2RWYGDRJ2CAQT4DIUDLZLTK66CH6VFGFIRQ2T43MO) | [`GDLGYDO4…IZM7`](https://stellar.expert/explorer/testnet/account/GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7) (`raiz-tourist`) | Cafe Don Aurelio `GA7CDZDQ…4TSN` | 0.4975 + 0.0100 + 0.0025 (= 0.5 USDC, tip 2 %) | 44 356 stroops = **0.0044356 XLM** | 5 388 486 · 2026-10-25 22:06:57 |
| 4 | **The app as carrier (B4)** — the emulator's seed wallet received, as PORTADOR, an envelope of `raiz-tourist` ("Lo llevas para GA7CDZ…4TSN"), verified it without network and `SobreSettler` submitted it on its own with the wallet's own XLM; "Liquidado" with the hash in "Recibidos" | [`79a4c858…eee2`](https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2) · [Horizon](https://horizon-testnet.stellar.org/transactions/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2/operations) | `GC4VHSZY…QQEH` (the carrier: the same wallet that was the **payer** in #1 and #2) | `GDLGYDO4…IZM7` (`raiz-tourist`) | Cafe Don Aurelio `GA7CDZDQ…4TSN` | 0.4975 + 0.0100 + 0.0025 (= 0.5 USDC, tip 2 %) | 23 415 stroops = **0.0023415 XLM** | 5 391 483 · 2026-10-26 02:16:42 |
| 5 | **Merchant's app settles automatically** (B4; emulator, seed wallet of Mochilas del Centro): the tourist signed in airplane mode, the merchant pasted the envelope in "Recibir sobre", verified it offline ("Es un pago para ti") and the app sent it by itself | [`6770a2c8…b5fbb4`](https://stellar.expert/explorer/testnet/tx/6770a2c8d417f2c679a0a623eb75bc19764af9ea36d56cca501f3e6f73b5fbb4) | `GBVOJF4F…FUS6M3` (the merchant) | `GC4VHSZY…XCQQEH` | Mochilas del Centro | 5 USDC + 0.1 tip | 0.0023412 XLM | 5 393 408 · 2026-10-26 04:57:07 |
| 6 | **Payer's app settles automatically** (B4; same tourist signed a second envelope offline, kept it, and regained signal): after a 10 s courtesy wait the app sent it itself | [`028f997e…6484b3`](https://stellar.expert/explorer/testnet/tx/028f997efbcd83f2b1bb4539352f7fedf9b9deacd1799047c89c748cb06484b3) | `GC4VHSZY…XCQQEH` (the payer) | `GC4VHSZY…XCQQEH` | Mochilas del Centro | 5 USDC + 0.1 tip | 0.0023417 XLM | 5 393 459 · 2026-10-26 05:01:22 |
| 7 | **Public web gateway on a phone's browser** (B3; Chrome in the emulator, no app, envelope preloaded via `#sobre=`): friendbot account created in the page, simulate enforce, send | [`41619717…5ce559`](https://stellar.expert/explorer/testnet/tx/41619717f2b636ba861023fc70923e711c776c7151c73d43f95876816c5ce559) | `GAF7IMFY…PPMB2D` (disposable, friendbot) | `GDLGYDO4…IZM7` (raiz-tourist) | Cafe Don Aurelio | 0.75 USDC + 0.015 tip | 0.0023405 XLM | 5 393 540 · 2026-10-26 05:08:07 |

Where each hash is documented: #1 in spec §8 and the plan's B1 result; #2 in `CLAUDE.md` (fee gotcha) and spec §3.1;
#3 in spec §8 and the plan's B3 result; #4 in the plan's B4 result and `CLAUDE.md`. Merchant names were read from the
Pool on 2026-10-26 with `stellar contract invoke … --send=no -- get_merchant --merchant <G…>` (simulation only):
`"name":"Cafe Don Aurelio","verified":true` and `"name":"Restaurante La Boveda","verified":true`, both in barrio
Centro (`ce471200…0001`).

**Fee note.** #1 cost 28.65 XLM because it was the **first** `pay_merchant` of that tourist in that barrio: the Pool
creates two persistent entries (`TouristSeen` and `Points`) and testnet charges their initial rent at its write
price; the next payments of the same tourist (#2, #4) cost about 0.0023 XLM (spec §3.1; `CLAUDE.md`, fee gotcha).
It is testnet's rent model, not the envelope, and it is why the demo gateway is funded by friendbot (10 000 XLM) and
why nothing is promised about mainnet costs until measured there. The spec quotes 0.0052 XLM for #3: that was the
resource fee requested by the simulation; Horizon's `fee_charged` is 0.0044356 XLM.

What is **not** on-chain and therefore not claimed here: the moment of signing without signal. The chain only shows
that someone other than the payer submitted a valid, payer-signed authorization. The offline part is covered by the
code (the signer has no network dependency: `DeferredAuthSigner.kt`, `AuthTreeBuilder.kt`, verified byte for byte
against the fixture) and, once recorded, by the video of section 5.

## 3. Verify in 5 minutes

### 3.0 With a browser only (about 2 minutes)

1. Open the four Stellar Expert links of the table above. Each shows **Successful**, a single `invoke_host_function`
   calling `pay_merchant` on `CD775D33…KBE2`, and a source account that is not the account whose USDC moves.
2. Open the compare view
   [`hackmeridian-start...feat/pago-diferido`](https://github.com/JuanWimmin/Protocolo_Raiz/compare/hackmeridian-start...feat/pago-diferido):
   that diff is the event's work; everything before the tag existed before the event.
3. Open <https://raizapp.xyz/pasarela.html> and go to 3.a.

### 3.a The public gateway verifies an envelope without touching the network

Paste the test envelope of the fixture (`sobre_qr` field of
`android/app/src/test/resources/deferred_auth_fixture.json`) into the page, or open it preloaded:

<https://raizapp.xyz/pasarela.html#sobre=raiz:sobre:1:AAAAAQAAAAAAAAAAKp6n5D4QA8O3KqGD_xJdBMBo2QYFR1nKtTTJEp87xyNhcSeCgshQAABhocwAAAAQAAAAAQAAAAEAAAARAAAAAQAAAAIAAAAPAAAACnB1YmxpY19rZXkAAAAAAA0AAAAgKp6n5D4QA8O3KqGD_xJdBMBo2QYFR1nKtTTJEp87xyMAAAAPAAAACXNpZ25hdHVyZQAAAAAAAA0AAABAMTLfZI05U_JUsG8H-iDljsMgd4I0DCYZa1PUeAmNoRl5D5SAekpJMtngFZwxafCSf2qeflU2wF937Wh2XWX2BAAAAAAAAAAB__6Pe5PI7YZgygkJwM3xyRnHuGkg7NrKusalp0HfFiUAAAAMcGF5X21lcmNoYW50AAAABAAAABIAAAAAAAAAACqep-Q-EAPDtyqhg_8SXQTAaNkGBUdZyrU0yRKfO8cjAAAAEgAAAAAAAAAAPiHkcE1VSBmY7kU8OEN10WIOLydwAxzJy4PIM9ZlKk4AAAAKAAAAAAAAAAAAAAAAAX14QAAAAAMAAADIAAAAAwAAAAAAAAABICLVbgq6ZFFvbmJgTSliMr6GT_37hNWGE-dCPKzgKygAAAAIdHJhbnNmZXIAAAADAAAAEgAAAAAAAAAAKp6n5D4QA8O3KqGD_xJdBMBo2QYFR1nKtTTJEp87xyMAAAASAAAAAAAAAAA-IeRwTVVIGZjuRTw4Q3XRYg4vJ3ADHMnLg8gz1mUqTgAAAAoAAAAAAAAAAAAAAAABe4_4AAAAAAAAAAAAAAABICLVbgq6ZFFvbmJgTSliMr6GT_37hNWGE-dCPKzgKygAAAAIdHJhbnNmZXIAAAADAAAAEgAAAAAAAAAAKp6n5D4QA8O3KqGD_xJdBMBo2QYFR1nKtTTJEp87xyMAAAASAAAAAf_-j3uTyO2GYMoJCcDN8ckZx7hpIOzayrrGpadB3xYlAAAACgAAAAAAAAAAAAAAAAAHoSAAAAAAAAAAAAAAAAEgItVuCrpkUW9uYmBNKWIyvoZP_fuE1YYT50I8rOArKAAAAAh0cmFuc2ZlcgAAAAMAAAASAAAAAAAAAAAqnqfkPhADw7cqoYP_El0EwGjZBgVHWcq1NMkSnzvHIwAAABIAAAAAAAAAAFcvvX3HujPBD2Jd49YLvwtyEPAdjmkGkzT5X2a6Q4AsAAAACgAAAAAAAAAAAAAAAAAB6EgAAAAA>

The page must show a valid signature and derive **from the XDR alone** (there is no JSON to trust): payer
`GAVJ5J7E…HSLN`, merchant Cafe Don Aurelio (`GA7CDZDQ…4TSN`, read with a simulated `get_merchant`), amount 2.5 USDC,
Tip Barrio 0.05 (200 bps), protocol fee 0.0125 (50 bps), nonce `7021436735540711424`, expiration ledger 6 398 412.
Those are the fixture's values (`amount` 25 000 000 stroops, `tip` 500 000, `fee` 125 000, `to_merchant` 24 875 000).

**This envelope is for verification only.** Its payer is a deterministic test keypair whose account was never
created on testnet (`GET https://horizon-testnet.stellar.org/accounts/GAVJ5J7E…HSLN` returns 404, checked
2026-10-26), so settling it is rejected at simulation with `Error(Auth, InvalidAction)` and nothing is spent. To watch
a settlement, use a real envelope (3.e).

### 3.b The terminal gateway, dry run (Node 22+; `@stellar/stellar-sdk` 17 through `STELLAR_SDK_PATH`, a normal install, or `../raiz-relayer/node_modules`)

```bash
node scripts/gen-auth-fixture.mjs --check                  # the fixture on disk is reproduced byte for byte
node scripts/pasarela_cli.mjs "<envelope>" --dry-run       # verifies and simulates; never funds or sends
```

With the fixture envelope, the run of 2026-10-26 printed `Sobre verificado sin red ✓` with the same payer, merchant,
amounts, nonce and expiration as above, the current ledger, and then `Simulación rechazada … Error(Auth,
InvalidAction)` (the test payer has no account, see 3.a). With a real envelope (3.e) the simulation passes and the dry
run stops before funding or sending anything.

### 3.c Unit tests (JVM, about 3 minutes on a laptop)

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: 315 tests, 0 failures, 3 skipped (the testnet integration tests `DeferredAuthTestnetIT` and
`SobreEventsTestnetIT`; they run only with `RAIZ_DEFERRED_IT=1` plus `RAIZ_DEFERRED_PAYER_MNEMONIC` or
`RAIZ_DEFERRED_PAYER_SECRET`, and `RAIZ_EVENTS_IT=1`). `DeferredAuthSpikeTest` is the byte-for-byte proof that the
Kotlin signer reproduces what `@stellar/stellar-sdk` 17 produces.

### 3.d The app: sign without signal → QR → receive in another wallet → automatic settlement

Tested so far on an Android emulator (Pixel 7, API 35) with a seed wallet; passkey wallets do not submit from the app
(section 4). Steps as used in the plan (B0, B2, B4):

1. Build and install the debug APK: `cd android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. While online: fund the seed wallet with the **fund's** USDC (the Blend USDC, SAC `CAQCFVLO…RCJU`; the home banner's
   faucet or the Blend faucet, `CLAUDE.md`) and pay once online to any merchant of the barrio. That first payment
   creates the tourist's persistent entries, so the gateway's fee for the deferred payment is cents instead of
   ~29 XLM (fee note in section 2).
3. Cut the network: `adb shell svc wifi disable && adb shell svc data disable` (or the "sin señal (debug)" switch in
   Pagar, which forces the offline path). In Pagar the button becomes **"Firmar sin señal"**; merchants come from the
   local cache. The result screen shows the QR, the detail, the estimated validity and "Copiar sobre"; "Mis sobres"
   lists it as "Sin señal: se enviará solo al volver la red".
4. On a second wallet (another emulator or phone with the app): the Home scanner recognizes the QR and opens
   **"Recibir sobre"** (or paste the copied envelope). The verdict is computed without network: COBRO if the envelope
   is for this wallet (cap 20 USDC by default), PORTADOR otherwise ("Lo llevas para GA7CDZ…"). Pasting the same
   envelope again answers "Ya tenías este sobre".
5. Give that second wallet signal: `SobreSettler` submits on its own; "Recibidos" shows "Liquidado" with the hash
   (transaction #4 was produced this way; the carrier needs XLM of its own, friendbot on testnet). The payer's "Mis
   sobres" closes when it regains signal: consumed nonce or a hash whose auth is the envelope.
6. Merchants: the Cobros screen has "Escanear sobre" / "Pegar sobre", "Pagos en tránsito" and "Liquidar ahora".

### 3.e A real envelope from the terminal, settled by the web page or the CLI

```bash
# the secret enters only through the environment and is never printed
DEFERRED_PAYER_SECRET=$(stellar keys secret raiz-tourist) \
  node scripts/deferred_envelope_demo.mjs --amount 0.5 --tip-bps 200 --window 11520 --out sobre.txt
# defaults: merchant Cafe Don Aurelio (Centro), 1 USDC, 2 %, 11 520 ledgers

node scripts/pasarela_cli.mjs sobre.txt            # funds a disposable gateway with friendbot and settles
# or paste the content of sobre.txt into https://raizapp.xyz/pasarela.html
node scripts/pasarela_cli.mjs sobre.txt            # second time: Error(Auth, ExistingValue) → "nonce usado"
```

The payer wallet needs a trustline and balance in the fund's USDC. The headless end-to-end run of the web page is
`scripts/pasarela_web_e2e.mjs` (usage in its header). Transactions #3 and #4 were made with envelopes from this script.

## 4. Declared limits

Stated in the same words in the spec (§0, §3.2, §7), the UI and the pitch; and the debts recorded in the plan's B4
result.

- **There is no offline finality.** Until the envelope reaches the network, the merchant holds a **signed promise,
  not money**. The app says it on the envelope screen and in Cobros ("promesa firmada, no dinero confirmado"); the
  network decides CONFIRMADO / EXPIRADO / RECHAZADO (spec §0, §6).
- **Double spend is possible inside the window and is bounded, not prevented** (spec §3.2, v0): the chain executes
  envelopes in arrival order, the k-th fails if the payer's balance is exhausted, and a malicious payer can issue more
  than it holds and decide which merchants get paid. Bounds in v0: expiration by ledger (W = 11 520 ledgers; 12 h
  guaranteed in the worst drift case, about 16 h at the measured 5 s close), a per-payment cap on the receiving side
  (20 USDC by default) and the payer's signed identity (no anonymity to repudiate). The prefunded territory pocket
  (`pocket` contract) that caps aggregate loss is **v1, not built**.
- **The expiration is estimated without network** from the last known ledger and the monotonic clock (spec §3.1);
  the UI shows "válido hasta ~hh:mm (estimado)". A receiver without a ledger reference accepts a COBRO never, a
  PORTADOR with a warning (plan, B4 result).
- **Passkey wallets (`C…`) do not sign envelopes offline and do not submit from the app**: they copy the envelope and
  open the gateway through `#sobre=`. Offline signing with a passkey smart account is v1 (spec §1, §7; plan B4 debts).
- **Envelopes older than the RPC's event retention (about 7 days) have no terminal verdict**: the nonce entry is
  temporary and the hash search in events stops at the retention window, so the app can only say that the envelope
  expired and can no longer be checked (plan, B4 debt; `CLAUDE.md` gotcha on `getEvents`).
- **Carriers move envelopes by QR (and copy/paste) only.** Bluetooth / Wi-Fi Direct transport, the encrypted envelope
  (a carrier today can read payer, merchant and amount), hardware key attestation, the relayer as gateway and iOS are
  v1 (spec §4, §7).
- **Not tested on a physical device this time.** The payer, carrier and settler flows ran on an Android emulator
  (seed wallet) and the web gateway in headless Chrome driven by CDP (plan, H4 and B4 result). Still pending from the
  plan: the manual acceptance of Safari on an iPhone scanning the emulator's QR (B3), the COBRO path with a merchant
  wallet, and the automatic settlement of the payer's **own** envelope signed without signal from the app (B4). The
  previous RAÍZ releases were tested on a Motorola G04 (`docs/evidencia_sow/`).
- **Legacy credentials.** The entry uses `SOROBAN_CREDENTIALS_ADDRESS` (preimage type 9), the only kind
  `kmp-stellar-sdk` 1.6.0 knows; Protocol 29 accepts them and emits CAP-71 V2 credentials only when asked. The
  migration changes the preimage, not the flow (spec §2).
- **Fees on testnet are not a cost model.** The first `pay_merchant` of a tourist in a barrio cost 28.65 XLM (#1);
  the following ones about 0.0023 XLM (#2, #4). Who pays the gateway's fee (a volunteer gateway, the merchant, the
  relayer) is part of the sustainability model that is still under evaluation (spec §3.1).
- **Recorded protocol debt:** the Pool deducts the protocol fee from the merchant's amount (`to_m = amount − fee`),
  which conflicts with the principle that the merchant receives the full sale. Not touched during the event (no new
  contracts); scheduled for the v2 contracts. The envelope screen shows the split as it is (spec §2.1).
- **The fixture envelope cannot be settled** (test keypair without an account, 3.a). It exists to verify signatures
  and the tree, byte for byte, in Kotlin and in the gateways.
- **Everything is on testnet.** If the test network were reset, the Stellar Expert links would stop resolving; that is
  why this folder will also hold screenshots and the video (section 5).

## 5. Screenshots and video

**Pending: none of these files exist yet.** They will be added by the team under `docs/evidencia_hackmeridian/` and
this section updated with the real file names. Any take that shows a keyboard over a web page must first go through
`python scripts/mask_video_autofill.py ENTRADA SALIDA` (rule H7 of the plan: the keyboard's suggestion strip can show
personal data from autofill).

Screenshots (`capturas/`, taken 2026-10-26 from the emulator recordings, the emulator's Chrome and a headless Chrome):

| File | What it shows |
|---|---|
| `capturas/01_pagar_sin_senal.png` | Pagar with the network off: banner "Sin señal", the button reads "Firmar sin señal" |
| `capturas/02_sobre_qr.png` | The envelope screen: QR, detail, "Válido ~12 h (estimado)", the signed-promise text |
| `capturas/04_pasarela_verificado.png` | <https://raizapp.xyz/pasarela.html> in the emulator's Chrome: valid signature, merchant, amounts, nonce, expiration |
| `capturas/05_pasarela_liquidado_hash.png` | The same page after settling: SUCCESS, hash, Stellar Expert link |
| `capturas/05b_pasarela_liquidado_chrome_emulador.png` | Same, full phone screenshot with the hash QR and the log (simulate enforce → send → OK) |
| `capturas/06_stellar_expert_fuente_distinta.png` | Stellar Expert of `6770a2c8…`: source account = the merchant, `pay_merchant(GC4V…QQEH, …)` = the tourist's authorization |
| `capturas/07_pasarela_nonce_usado.png` | Re-opening the gateway with an already settled envelope: "Nonce ya consumido en la red… No se envía" |
| `capturas/08_recibir_sobre_cobro.png` | "Recibir sobre" in the merchant's wallet: verdict without network ("Es un pago para ti"), then "Enviando…" |
| `capturas/09_recibidos_liquidado.png` | "Recibidos" of a carrier wallet with an envelope "Liquidado" and its hash after the automatic settlement |
| `capturas/10_comercio_cobrado_hash.png` | The merchant's screen after the automatic settlement: "Cobrado", transaction hash, "Ver en Stellar Expert" |
| `capturas/11_mis_sobres_confirmado.png` | The payer's "Mis sobres": both envelopes "Confirmado" with their hashes (one settled by the merchant, one by the payer itself) |

Not captured: "Mis sobres" with an envelope still in transit (03) and Cobros' "Pagos en tránsito" list (10 was taken on the verdict screen instead).

**Video.** A 89 s silent draft (Problem → Demo → How → Next) was cut on 2026-10-26 from emulator recordings:
`C:/Blockota/RAIZ_entregas/VideosDemo/2026-10-26/demo_raiz_borrador_90s.mp4` (raw clips in `raw/`, cut list in `cut_list.md`,
build script in `work/build_demo.py`). Not versioned; the voice-over, the final edit and the public URL are pending.

## 6. Contracts and accounts used (testnet)

No contract was deployed or modified during the event. IDs from `deployments.json` (deployed 2026-07-31):

| Piece | Address |
|---|---|
| Pool (`pay_merchant`, root of the signed tree) | [`CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2`](https://stellar.expert/explorer/testnet/contract/CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2) |
| USDC of the fund (Stellar Asset Contract of Blend's testnet USDC; the three `transfer`s of the tree) | [`CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU`](https://stellar.expert/explorer/testnet/contract/CAQCFVLOBK5GIULPNZRGATJJMIZL5BSP7X5YJVMGCPTUEPFM4AVSRCJU) — issuer `GATALTGTWIOT6BUDBCZM3Q4OQ4BO2COLOAZ7IYSKPLC2PMSOPPGF5V56` |
| Protocol admin (receives the 0.5 % fee; `protocol_fee_bps` = 50) | [`GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P`](https://stellar.expert/explorer/testnet/account/GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P) |
| Rewards (`accrue_points`, called by the Pool with its own address: **not** part of the tourist's signed tree) | `CDTTEZX2QO3L2A4EC34VGVAWYAI4CQD42SGYMFQNNTEQWYU5SHFU5DZJ` |
| Merchants of the four transactions (barrio Centro) | Cafe Don Aurelio `GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN` · Restaurante La Boveda `GAGVM6ATSKHLYJV3VBOEG6UKDFZZ6BVSEGF6CJO3FFSZF46JNZUBDVIN` |
| Payers | emulator seed wallet `GC4VHSZYLB7B3SGYYOV4JAWEG2TT6OJAMT6QTC7HLMP53O5DB3XCQQEH` · CLI identity `raiz-tourist` `GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7` |
| Gateways (disposable, funded by friendbot; none is a project key) | `GCCXC2E2SZ6454YCR43MYSG7ELKBMGGZP425QIZA3XBFKYCF6SYG2WRH` (#1) · `GD2Q2CZUBC5ODRIW2ZRCGWLMBHZYQUOL26TD3RI7BG6KWBXTFTA3JLVF` (#2) · `GADSC7Z5UV7APTZ2RWYGDRJ2CAQT4DIUDLZLTK66CH6VFGFIRQ2T43MO` (#3) |

The admin account is signed by a rotated key that never shipped in an APK (`docs/evidencia_sow/d1/README.md`); the
deferred payment never needs the admin: the payer signs, anyone submits.

---

## Resumen en español

**Qué se construyó en HackMeridian (Lisboa, 25–26 oct 2026).** El "pago diferido por enjambre": el visitante firma
**sin señal**, en su teléfono, la `SorobanAuthorizationEntry` de `Pool.pay_merchant` (credenciales legacy, árbol
completo: raíz + tres `transfer` del USDC del fondo al comercio, al Pool y al admin) y la exporta como **sobre**
(`raiz:sobre:1:` + base64url del XDR firmado, 1 229 caracteres, QR v29). Cualquier dispositivo con señal reconstruye
la transacción con **su propia cuenta como fuente y pagadora del fee**, simula en modo enforce y la envía; la red
garantiza unicidad (nonce consumido on-chain) y expiración (`signature_expiration_ledger`, ventana W = 11 520
ledgers). No se desplegó ni modificó ningún contrato. Todo está en la rama `feat/pago-diferido` sobre el tag
`hackmeridian-start` (`1361b38`): 26 commits, 68 archivos, +14 288 / −200 líneas
([diff](https://github.com/JuanWimmin/Protocolo_Raiz/compare/hackmeridian-start...feat/pago-diferido)).

**Las cuatro piezas y su transacción en testnet** (las cuatro releídas en Horizon el 26-oct; en todas la cuenta
fuente es distinta del pagador cuyo USDC se mueve):

1. **B1 — firma offline en Kotlin** (`data/deferred/AuthTreeBuilder.kt`, `DeferredAuthSigner.kt`; 16 tests byte a
   byte contra el fixture de `scripts/gen-auth-fixture.mjs`) y envío desde otra cuenta (`DeferredAuthTestnetIT`):
   [`ebee152c…6d47`](https://stellar.expert/explorer/testnet/tx/ebee152cc8bc39af0c3ab1060e515cfdf3a5a6cc76d7ba35f3ab2f2aab506d47)
   — fuente `GCCXC2E2…` (friendbot), pagador `GC4VHSZY…` (wallet semilla del emulador), 0,5 USDC + 2 % a Cafe Don
   Aurelio, fee 28,6548714 XLM (primer pago del turista en el barrio: crea `TouristSeen` y `Points`), ledger 5 384 473.
2. **B2 — pagador en la app** ("Firmar sin señal", pantalla del sobre con QR, "Mis sobres", cierre decidido por la
   red) y **pasarela de terminal** `scripts/pasarela_cli.mjs`:
   [`cec2388a…1c34`](https://stellar.expert/explorer/testnet/tx/cec2388a231379b79ee51b0c2f0baf8b6bbb6dd6cb962cb71f95942014e41c34)
   — fuente `GD2Q2CZU…` (friendbot), pagador `GC4VHSZY…`, 5 USDC + 2 % a Restaurante La Boveda, fee 0,0023414 XLM,
   ledger 5 388 242.
3. **B3 — pasarela web pública** `landing/pasarela.html` → <https://raizapp.xyz/pasarela.html> (sin servidor propio;
   la copia publicada es idéntica byte a byte a la del repo), probada en Chrome headless con
   `scripts/pasarela_web_e2e.mjs` y un sobre real de `scripts/deferred_envelope_demo.mjs`:
   [`27c61461…9f47`](https://stellar.expert/explorer/testnet/tx/27c6146173ab13ba53599026221ca92c3fc9e21df529533aa0149626fe959f47)
   — fuente `GADSC7Z5…` (creada por la página con friendbot), pagador `GDLGYDO4…` (`raiz-tourist`), 0,5 USDC + 2 % a
   Cafe Don Aurelio, fee 0,0044356 XLM, ledger 5 388 486.
4. **B4 — cobrador y portador en la app con liquidación automática** (`SobreSettler`, `SorobanClient.submitSobre`,
   `ReceivedSobre*`, Cobros, "Recibir sobre", "Recibidos"):
   [`79a4c858…eee2`](https://stellar.expert/explorer/testnet/tx/79a4c858b987f60304d87909a32791348fa88bc22ea933e082b6ddcbd296eee2)
   — fuente `GC4VHSZY…` (la wallet del emulador actuando de PORTADOR), pagador `GDLGYDO4…`, 0,5 USDC + 2 % a Cafe Don
   Aurelio, fee 0,0023415 XLM, ledger 5 391 483; el mismo sobre pegado otra vez → "Ya tenías este sobre".

**Tests:** `./gradlew :app:testDebugUnitTest` → 315 tests, 0 fallos, 3 omitidos (las pruebas de integración contra
testnet, apagadas por defecto); 184 de ellos son del trabajo de la hack.

**Verificación en 5 minutos (sección 3):** abrir los cuatro enlaces de Stellar Expert y el diff; pegar en la
pasarela pública el sobre del fixture (enlace precargado en 3.a: verifica la firma y deriva comercio, montos, nonce y
expiración solo del XDR; no se puede liquidar porque su pagador es una llave de prueba sin cuenta en testnet);
`node scripts/pasarela_cli.mjs "<sobre>" --dry-run`; los tests JVM; el flujo en la app (red apagada → "Firmar sin
señal" → QR → "Recibir sobre" en otra wallet → liquidación automática al volver la señal); y cómo generar un sobre
real con `scripts/deferred_envelope_demo.mjs` (la clave solo por variable de entorno).

**Límites declarados (sección 4):** no hay finalidad offline (el comercio tiene una promesa firmada hasta que llega
el hash); el doble gasto es posible dentro de la ventana y se acota con expiración por ledger, tope por pago (20 USDC
por defecto) e identidad firmada — el bolsillo prefondeado es v1; la expiración se estima sin red; las wallets
passkey no firman offline ni envían desde la app (liquidan por la pasarela); un sobre más viejo que la retención del
RPC (~7 días) queda sin veredicto terminal; portadores por QR (Bluetooth, sobre cifrado, atestación de hardware,
relayer como pasarela e iOS son v1); no probado en dispositivo físico esta vez (emulador + Chrome headless; quedan
Safari en iPhone, el camino COBRO con una wallet de comercio y la auto-liquidación de un sobre propio firmado sin
señal); credenciales legacy; el fee de testnet no es un modelo de costos (28,65 XLM el primer pago del turista en el
barrio, céntimos los siguientes); deuda registrada: el Pool descuenta el fee del protocolo del monto del comercio
(contratos v2).

**Pendiente del equipo (sección 5):** capturas en `docs/evidencia_hackmeridian/capturas/` y el video de 75 s en
`video/` (nombres sugeridos arriba; pasar cualquier toma con teclado por `scripts/mask_video_autofill.py`), y
enlazarlos aquí y en la sección HackMeridian del README.
