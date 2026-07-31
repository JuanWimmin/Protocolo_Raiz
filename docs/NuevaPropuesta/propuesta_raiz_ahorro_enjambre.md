# RAÍZ → Protocolo de ahorro comunitario sobre Stellar
## Propuesta técnica: independencia de DeFindex + la capa "Enjambre"

> Preparado el 2026-07-31 a partir de la revisión completa del repo `Protocolo_Raiz` y de tres investigaciones verificadas sobre la red Stellar (estado julio 2026), mecanismos de ahorro del ecosistema y viabilidad de enjambres de dispositivos. Fuentes al final y en `investigacion_stellar_julio2026.md`.

---

## 0. Resumen ejecutivo

RAÍZ hoy es una **red de pagos con un fondo comunitario que rinde vía DeFindex**. Esta propuesta la convierte en un **protocolo de ahorro comunitario** en tres movimientos:

1. **Independencia de yield:** reemplazar el vault DeFindex por integración **directa con Blend** detrás de un *adaptador de yield propio* — cambio quirúrgico en el contrato Pool, patrón ya probado en producción (DeFindex mismo es solo un contrato que rutea a Blend).
2. **La capa de ahorro que no existe en Stellar:** tandas/cadenas de ahorro soulbound, metas con time-lock, ahorro con compromiso (commit-to-save) y sorteo sin pérdida. El gap analysis de julio 2026 confirma que **ninguno de estos productos existe maduro en Stellar** — RAÍZ puede ser el primero.
3. **El Enjambre:** los dispositivos del barrio como infraestructura del protocolo — custodia colectiva del fondo (passkeys + políticas, y a futuro firmas umbral FROST), atestación vecinal de residencia, pagos por mesh store-and-forward, verificación ligera de la cadena desde el móvil, y recompensas tipo DePIN por sostener la red del barrio. **Nada de esto existe en Stellar hoy.**

La misión no cambia: *el valor que genera el barrio se queda en el barrio y lo decide el barrio*. Cambia la profundidad: de "el 2% va a un fondo que rinde en un vault de terceros" a "el barrio ES la infraestructura financiera: custodia, verifica, ahorra y decide".

---

## 1. Verificación de la red Stellar (julio 2026)

Verificado el 31-jul-2026:

- **Red operativa**: mainnet y testnet con 100% de uptime en 90 días, sin incidentes (status.stellar.org). Horizon y Soroban RPC responden.
- **Protocol 27 "Zipper"** activo en mainnet desde el 8-jul-2026. Clave para esta propuesta: trae **delegación de autenticación nativa para smart accounts** (CAP-71) — exactamente el building block que necesita la custodia de enjambre — y **Protocol 28 traerá autenticación basada en contratos completa**. RAÍZ ya usa smart accounts (OZSmartAccountKit); está llegando a la ola en el momento justo.
- Upgrades previos relevantes: **ejecución paralela de Soroban** y estado en RAM (P23, lecturas más baratas — bueno para dashboards), **fundamentos ZK** (P25, BN254/Poseidon — abre privacidad futura de ahorros), **TTL v2** (P26, control fino de la renta de storage — importante para un protocolo de ahorro de largo plazo, recuerda el gotcha de TTL que ya sufriste con `get_asset_amounts_per_shares`).
- Ledger ~5s (objetivo 2.5s votable vía CAP-70, aún no confirmado en mainnet), fees de fracción de centavo, TVL de cadena ~$227M, **Blend $141M TVL / APY medio ~3.6%**, DeFindex $11.5M.
- **SCF 7.0**: hasta $150K por track de Build (tope $300K/proyecto) — los gaps que esta propuesta ataca son exactamente el perfil "primero en Stellar" que el SCF financia.

Conclusión: la red está sana, el timing regulatorio-técnico (smart accounts nativas P27→P28) juega a favor, y el terreno de "ahorro comunitario" está **vacío**.

---

## 2. Diagnóstico: la dependencia actual de DeFindex

Del código (`contracts/pool/src/lib.rs`, `DefindexClient.kt`, `YieldViewModel.kt`):

- Camino A: `Pool.deposit_idle_to_vault` / `redeem_from_vault` llaman al vault DeFindex con `authorize_as_current_contract`; `Treasury.execute_proposal` rescata shares antes de pagar.
- Camino B: la app lee TVL/posición y deposita/rescata como tesorería; APY vía REST de api.defindex.io (API key).
- Consecuencias de la dependencia: (a) el fondo custodia el USDC de Blend porque **el vault lo exige** — la elección de token la dictó DeFindex; (b) fee de gestión del vault intermedio; (c) riesgo de contraparte de un protocolo con $11.5M TVL cuya estrategia subyacente... es Blend de todos modos; (d) API key externa para mostrar APY.

**Dato clave de la investigación:** DeFindex es una capa de conveniencia sobre Blend ("routes deposits into Blend behind the scenes" — blog oficial de Stellar sobre composabilidad). RAÍZ puede hablar con Blend directamente y quedarse la capa de estrategia — que es donde está el valor y la identidad de protocolo.

---

## 3. Vía 1 — Independencia de yield: Blend directo + adaptador propio

### 3.1 El cambio quirúrgico

Sustituir en `Pool` las llamadas al vault por llamadas al **pool USDC de Blend**:

```
// hoy:  Pool ──deposit──▶ Vault DeFindex ──▶ Blend
// meta: Pool ──submit(SUPPLY_COLLATERAL)──▶ Blend pool USDC
```

- Interfaz verificada: `submit_with_allowance()` / `submit()` con request `SUPPLY_COLLATERAL`, `get_positions()` para la posición, `get_reserve()` para el b-rate (precio del bToken, sube por segundo con el interés), `claim()` para emisiones BLND. Hay implementación de referencia open-source (Blend-Vault de James Bachini, estilo ERC-4626) y precedente de producción (Meru, Airtm, Lobstr).
- Los bTokens reemplazan a las `VaultShares(barrio_id)` actuales — misma contabilidad por barrio, otro subyacente.
- `Treasury.execute_proposal` cambia `redeem_from_vault` por el retiro Blend equivalente. La app reemplaza `DefindexClient` por un `BlendClient` (lecturas on-chain puras: b-rate × bTokens = valor; **sin API key externa**, el APY se calcula del propio b-rate).

### 3.2 Hacerlo protocolo, no integración: el `YieldAdapter`

Para no cambiar una dependencia por otra, el Pool no debe conocer a Blend: debe conocer una **interfaz propia**.

```rust
// contracts/yield_adapter (trait conceptual)
deposit(barrio_id, amount) -> shares
withdraw(barrio_id, shares) -> amount
value_of(barrio_id) -> i128       // valor actual en USDC
apy_hint() -> u32                  // bps, informativo
```

Implementación 1: `BlendAdapter`. Implementaciones futuras: RWA (Ondo USDY ~5.3%, CETES de Etherfuse — con sus restricciones jurisdiccionales), renta fija (Spield cuando llegue a mainnet), o **estrategia mixta votada por la gobernanza del barrio** ("¿el fondo va 100% conservador o 70/30?" es una propuesta más que los residentes votan — el ahorro también se gobierna). Cambiar de fuente de yield se vuelve un `set_adapter` gobernado, no un re-deploy.

### 3.3 Gestión de riesgo (lección YieldBlox, feb-2026)

Un pool construido sobre Blend fue drenado por ~$10.2M por manipulación de oráculo. Reglas para RAÍZ: usar solo pools Blend con oráculos robustos y backstop grande (el riesgo es POR POOL); exponer en el dashboard el pool exacto, su backstop y su oráculo (transparencia = misión); considerar un **colchón no invertido** (ej. 20% del fondo siempre líquido en el Pool) — parámetro gobernable; a futuro, RAÍZ podría ser el primer protocolo de Stellar con **mutual de cobertura comunitaria** (gap #7 del análisis — nadie lo ha construido tras YieldBlox).

---

## 4. Vía 2 — La capa de ahorro que NO existe en Stellar

Aquí está la tecnología nueva. El gap analysis (julio 2026) confirma que en Stellar no existe ninguno de estos productos en forma madura. Todos se apoyan en lo que RAÍZ ya tiene y ninguno rompe la misión — la profundizan: hoy el barrio ahorra colectivamente por goteo del 2%; esto le da al barrio y a sus vecinos **instrumentos de ahorro activos**.

### 4.1 "Cadena de Barrio" — la tanda soulbound (gap #1, el más alineado)

La ROSCA (tanda, cadena, san, pasanaku, natillera) es LA institución de ahorro popular de LatAm — y on-chain en Stellar solo existe un intento embrionario sin tracción (SoroSusu). Contrato nuevo `savings_circle`:

- N residentes del barrio (verificados por su token soulbound de Governance — **reutiliza tu identidad existente**) se comprometen a aportar X USDC por ronda; cada ronda, uno recibe el bote, orden por sorteo o subasta.
- El token soulbound resuelve el problema histórico de las ROSCAs on-chain: el **default anónimo**. Aquí quien incumple es un vecino identificado, con reputación de ahorro registrada (historial de cadenas completadas = score crediticio comunitario, base para microcrédito v3).
- Mientras el bote espera su turno, **rinde vía el YieldAdapter** — el yield extra puede ir al pool del barrio (la tanda le "paga renta" a la comunidad) o repartirse.
- El 2% de los pagos turísticos y las tandas conviven en el mismo protocolo: turismo llena el fondo común, las cadenas construyen ahorro individual — dos raíces del mismo árbol.

### 4.2 Alcancía con meta y time-lock (gap #2)

Stellar tiene time-locks nativos (claimable balances con predicados temporales) pero **Soroban no puede tocarlos** y nadie los productizó. Contrato `goal_vault`: metas personales o colectivas ("la escuela: 500 USDC para diciembre") con retiro bloqueado hasta fecha/meta, depósitos de cualquiera (el turista puede aportar a una meta del barrio visible en la app — nueva forma de Tip dirigido), rendimiento vía adapter. Un timelock-vault Soroban con metas sería primero en su clase.

### 4.3 Commit-to-save con yield socializado (gap #3)

Patrón GoodGhosting/HaloFi, inexistente en Stellar: retos de ahorro por temporada ("aporta 5 USDC/semana por 12 semanas"); quien cumple recupera todo + su parte del yield; el yield de quien abandona se reparte entre los que cumplen **o cae al pool del barrio**. Gamifica el hábito y financia lo común con la deserción.

### 4.4 Sorteo sin pérdida (gap #4)

Prize-linked savings tipo PoolTogether: el yield agregado de un período se sortea entre los ahorradores del barrio; nadie pierde su capital. P25 (X-Ray) da las herramientas ZK para sorteos verificables a futuro; en v1 basta aleatoriedad derivada de datos de ledger + compromiso previo. (Revisar el encuadre legal por jurisdicción: "premio al ahorro", no lotería.)

> **Nota de secuencia:** 4.1 es el diferencial cultural y técnico — empezar ahí. 4.2–4.4 comparten el 80% de la infraestructura (depósitos por miembro, épocas, adapter, reparto) y salen casi gratis después.

---

## 5. Vía 3 — El Enjambre: los dispositivos del barrio como infraestructura

Tu intuición de "enjambres de dispositivos como nodos" es correcta en dirección y hay que afinarla en física. Lo honesto primero:

**Lo que un teléfono NO puede ser:** validador de Stellar (16GB RAM, NVMe 10K IOPS, 99.9% uptime, puerto peer — inviable en móvil), ni garantizar **finalidad offline** (el doble gasto sin internet es imposible de prevenir en software puro — conclusión formal del BIS Project Polaris; cualquiera que lo prometa está mintiendo).

**Lo que el enjambre SÍ puede ser — y nada de esto existe en Stellar:**

### 5.1 Custodia colectiva del fondo (mata tu bloqueante #1 de mainnet)

Hoy la clave admin va embebida en el APK — tu limitación conocida más grave. El enjambre la elimina en tres etapas:

- **Etapa A (hoy, cero código de protocolo):** multisig nativo Stellar — la autoridad admin pasa a una cuenta con hasta 20 firmantes-vecinos y umbrales por criticidad de operación.
- **Etapa B (la buena, 2026):** el fondo/admin del barrio como **smart account Soroban** cuyos firmantes son las **passkeys de M residentes** con **policy signers** (límites diarios, timelocks, umbral k-de-n, allowlists de operación). Building blocks auditados existen (OpenZeppelin Stellar Contracts, smart accounts RC v0.7.0) y **Protocol 27 acaba de traer la delegación de autenticación nativa** que abarata exactamente esto. RAÍZ ya integra OZSmartAccountKit para usuarios — extenderlo a la custodia comunal es el paso natural. Nadie ha ensamblado esto para fondos vecinales en Stellar.
- **Etapa C (frontera real):** **FROST t-de-n** — N teléfonos de residentes co-firman produciendo UNA firma ed25519 estándar que Stellar ya verifica sin ningún cambio de protocolo (RFC 9591; frost-ed25519 probado). "La llave del barrio no existe: existe el barrio." **No existe ningún tooling FROST-Stellar en el mundo** — construir `frost-stellar` (DKG entre móviles + re-sharing cuando un vecino entra/sale) es una contribución de infraestructura que el SCF financiaría y que pondría a RAÍZ en el mapa técnico de Stellar. Es el "enjambre como nodo" en su forma más pura: el enjambre es el firmante.

### 5.2 Atestación vecinal de residencia (mata tu bloqueante #2: el KYC mock)

Reemplazar el mint manual del admin por **web-of-trust de barrio**: un aspirante a residente es atestiguado por M vecinos ya verificados (firman con passkey, con stake de reputación y revocación; ventana de disputa). Contrato `attestation` + flujo en la app. Honestidad técnica: es un mecanismo **social** anti-sybil, no una prueba criptográfica de presencia (GPS es falsificable; BLE prueba dispositivos, no personas) — para una comunidad real que se conoce, es más honesto y más fiel a la misión que un KYC corporativo. No existe nada así en Stellar.

### 5.3 Mesh de pagos store-and-forward (el enjambre transporta)

Una transacción Stellar es un sobre XDR firmado que puede viajar por **cualquier canal**: BLE, WiFi Direct, QR, NFC. Patrón TxTenna (Bitcoin por mesh) + Bitchat (2025 demostró mesh BLE store-and-forward real entre smartphones):

- El turista sin datos firma el pago; el XDR salta de teléfono en teléfono del enjambre (comercios, vecinos) hasta que **cualquier** dispositivo con internet lo emite; la confirmación vuelve por el mismo mesh.
- Sin mentir sobre finalidad: el comercio ve "pago en tránsito por el enjambre" hasta la confirmación real. Con ledgers de ~5s, en cuanto un nodo del mesh toca internet la finalidad llega en segundos.
- Resuelve un dolor real del turismo LatAm (zonas sin señal, turistas sin SIM local) y de tu propio gotcha TLS/OEM: el pago de un dispositivo problemático puede salir relayado por otro.
- Nadie ha hecho esto en Stellar. Sería el primer caso.

### 5.4 Verificación ligera desde el móvil (el enjambre verifica)

No existe light client de Stellar — pero el patrón está probado fuera (Stellar Oracle de Pendulum/Spacewalk): recolectar los envelopes SCP "externalized" y **verificar las firmas ed25519 de los ~23 validadores tier-1 conocidos**. Es criptografía trivial para un móvil. Una lib `stellar-light-verify` (Kotlin/Rust) dentro de la app haría que el dashboard de transparencia del barrio **no confíe en ningún RPC**: "este saldo del fondo está confirmado por la red, verificado EN tu teléfono". Coherencia total con la tesis de transparencia; contribución de ecosistema financiable.

### 5.5 DePIN de barrio (el enjambre se recompensa)

No existe ningún DePIN sobre Stellar. El protocolo puede recompensar con micropagos (fees de fracción de centavo lo permiten) los servicios medibles del enjambre: relays mesh que transportaron pagos (acuses firmados), comercios-gateway que emitieron transacciones ajenas, dispositivos que corren el verificador ligero, y 1–3 **mini-PCs comunitarios** (casa comunal / junta) corriendo watcher/Basic Validator como "testigo del barrio" — alimenta el dashboard local e indexa los eventos del barrio (sin peso en el consenso global: eso no hay que prometerlo). Fuente de las recompensas: el protocol fee (0.5%) deja de ir al admin y pasa a financiar la infraestructura del enjambre — el fee se vuelve circular. Lección Helium a respetar: lo difícil no es pagar, es **verificar el trabajo físico** — empezar solo con trabajos verificables on-chain (transacciones emitidas, relays con acuse criptográfico).

---

## 6. Qué NO prometer (síntesis de honestidad técnica)

1. "Los teléfonos validan Stellar" — falso e innecesario. El enjambre custodia, verifica, transporta y atestigua; no produce consenso global.
2. "Pagos offline con finalidad" — imposible sin hardware seguro. El mesh es *transporte*, la finalidad siempre la da la red.
3. "La atestación vecinal es una prueba" — es web-of-trust con incentivos, y así hay que nombrarla.
4. "APY garantizado" — el yield de Blend es variable (~3.6% medio hoy) y con riesgo por pool; mostrarlo siempre como variable y con su riesgo visible.

---

## 7. Arquitectura objetivo

```
┌──────────────────────── App Android (+ enjambre) ────────────────────────┐
│  Pantallas actuales + Ahorro (cadenas/metas/retos) + Enjambre (estado)   │
│  PasskeyWalletManager ─ passkeys usuario Y firmante-de-custodia          │
│  MeshRelay (BLE/QR store-and-forward)   LightVerify (firmas SCP tier-1)  │
└──────────────┬───────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────── Soroban ──────────────────────────────────┐
│ Pool (pagos + fondo)            ──┐                                       │
│ savings_circle (tandas)          ├──▶ YieldAdapter ──▶ BlendAdapter ──▶ Blend USDC
│ goal_vault (metas/retos/sorteo)──┘         │                              │
│ Governance (soulbound, votos) ◀─ attestation (web-of-trust vecinal)      │
│ Treasury (ejecución trustless)   swarm_rewards (DePIN de barrio)         │
│ Custodia: smart account comunal (passkeys+policies → FROST)              │
└───────────────────────────────────────────────────────────────────────────┘
```

Lo que se conserva intacto: Governance soulbound, Treasury trustless, Rewards, el flujo de pago con Tip Barrio, la paleta y las 7 pantallas. Lo que muere: el vault DeFindex, la clave admin en el APK, el mint manual de residentes, la API key de DeFindex.

## 8. Roadmap propuesto

| Fase | Qué | Por qué primero |
|---|---|---|
| **1. Blend directo + YieldAdapter** (semanas) | `BlendAdapter` en Pool, `BlendClient` en app, fuera DeFindex | Cumple el objetivo declarado ya; riesgo bajo; patrón probado; sin API keys |
| **2. Cadena de Barrio** (1–2 meses) | Contrato `savings_circle` + UI + reputación soulbound | El gap más grande y más alineado culturalmente; convierte a RAÍZ en "protocolo de ahorro" de verdad |
| **3. Custodia enjambre Etapa B + atestación vecinal** | Smart account comunal con passkeys/policies (P27); `attestation` | Elimina LOS DOS bloqueantes de mainnet (clave admin, KYC mock) con tecnología que no existe en Stellar |
| **4. Metas + retos + sorteo** | `goal_vault` sobre la infra de la fase 2 | 80% reutilizado; producto completo de ahorro |
| **5. Enjambre frontera** | Mesh store-and-forward, `stellar-light-verify`, `swarm_rewards`, piloto FROST | Los "primeros en Stellar" de infraestructura; candidatos ideales a SCF Build (hasta $150K/track) — presentar fases 3 y 5 como proyectos SCF |

Cada fase es demostrable por sí sola (importante para pitch/SCF) y ninguna rompe lo desplegado: el Pool actual sigue operando mientras el adapter se prueba en testnet.

---

## 9. Fuentes principales

Red y protocolo: status.stellar.org · stellar.org/blog/foundation-news/stellar-zipper-protocol-27-upgrade-guide · stellar.org/blog/developers/announcing-protocol-23 · stellar.org/blog/developers/announcing-stellar-x-ray-protocol-25 · stellar.org/blog/foundation-news/stellar-yardstick-protocol-26-upgrade-guide · developers.stellar.org/docs/learn/fundamentals/stellar-consensus-protocol · developers.stellar.org/docs/validators/admin-guide/prerequisites · stellar.org/foundation/roadmap · stellar.org/foundation/strategy
Ahorro: docs.blend.capital/blend-whitepaper · docs.blend.capital/users/general-faq · jamesbachini.com/blend-vault (+ github.com/jamesbachini/Blend-Vault) · stellar.org/blog/developers/composability-on-stellar-from-concept-to-reality · halborn.com/blog/post/explained-the-yieldblox-hack-february-2026 · defillama.com/chain/stellar · github.com/SoroSusu-Protocol/sorosusu-contracts · developers.stellar.org/docs/tokens/stellar-asset-contract · developers.stellar.org/docs/build/guides/transactions/claimable-balances · stellar.org/press/ondo-finance-launches-usdy-on-stellar · spield.live · lumenloop.com/research/upshift-brings-multi-chain-vault-infrastructure-stellar
Enjambre: docs.openzeppelin.com/stellar-contracts/accounts/smart-account · rfc-editor.org/rfc/rfc9591.html · github.com/taurushq-io/frost-ed25519 · medium.com/pendulum-chain/introducing-the-stellar-oracle-ce4b85244cc8 · github.com/pendulum-chain/spacewalk · coincenter.org/decentralizing-bitcoins-last-mile-with-mobile-mesh-networks · bis.org/publ/othp64.htm (Project Polaris) · github.com/montelibero-org/stellar-multisig · arxiv.org/html/2406.02239v1 (survey DePIN)
Financiación: communityfund.stellar.org (SCF 7.0)
