# Plan de trabajo RAÍZ — agosto 2026 → enero 2027

> Objetivo del semestre: **aplicar al Stellar Community Fund con F1+F2 demostrables** y llegar a enero con el portafolio de ahorro completo en testnet y la decisión de mainnet tomada con datos.
> Equipo: 2–3 personas. Horizonte: 6 meses, con gates trimestrales de go/no-go.
> Referencia técnica: paper `paper-tecnico-raiz.tex` y propuesta `propuesta-raiz-ahorro-enjambre.md` (en este Project).

---

## 0. Los tres principios del plan

1. **El SCF es por referidos (SCF 7.0)** — tu contacto del SDF es la puerta de entrada, no una reunión de cortesía. Todo el mes 1–3 está diseñado para que, cuando pidas el referido, tengas código corriendo que lo justifique.
2. **Nada de F5 antes de que F2 tenga usuarios.** La frontera (FROST, mesh, light-verify) avanza solo en ranuras de investigación acotadas. La disciplina es el moat.
3. **Cada mes termina en algo demostrable en un teléfono.** Si un hito no se puede enseñar en vivo, no es un hito.

## Roles (2–3 personas)

| Rol | Responsabilidad | Dedicación |
|---|---|---|
| **R1 · Protocolo** | Contratos Rust/Soroban: YieldAdapter, savings_circle, attestation, tests, deploys | núcleo |
| **R2 · App** | Android/Kotlin: BlendClient, UI Ahorro, flujos passkey, UX de co-firma | núcleo |
| **R3 · Producto y comunidad** | Aplicación SCF, pitch, encuadre legal del sorteo, relación con el barrio piloto, entrevistas | parcial |

Con 2 personas: R3 se reparte entre ambas, y el mes 5 se aligera (piloto más pequeño).

---

## MES 1 (agosto) — F1: independencia de DeFindex

**Semana 1 — higiene y arranque**
- Commitear los cambios pendientes de `main` y decidir el destino de las 3 ramas feat/ (merge o cierre).
- Actualizar `ARQUITECTURA_TECNICA.md` (está en revisión 2026-05-28: aún dice que passkey es stub).
- **Quick win Etapa A:** migrar la autoridad admin de testnet a una cuenta multisig 2-de-3 (los miembros del equipo). Elimina "la clave en el APK" del discurso YA, sin código nuevo.
- Definir el trait `YieldAdapter` (spec en el paper §4.2) y actualizar `raiz_v2_spec_contratos.md`.

**Semanas 2–4 — BlendAdapter**
- R1: **migrar el workspace de soroban-sdk 22.x → 26.1.0** aprovechando que el código de contratos está abierto (requisito futuro de la capa ZK: BN254 + Poseidon vía CryptoHazmat llegan en ≥25.x; ojo a los breaking changes de testing de eventos en 25.0 y renombres de tipos en 26.0). Luego: crate `yield_adapter` + `BlendAdapter` (submit SUPPLY_COLLATERAL, retiro, b-rate, claim de BLND) contra el pool USDC de Blend testnet; migrar `Pool.deposit_idle_to_vault/redeem_from_vault` a la interfaz; colchón líquido (20%, parámetro); tests (objetivo: mantener 100% verde, ~70 tests).
- R2: `BlendClient` (lecturas puras: posición = b-rate × bTokens, APY derivado del b-rate); retirar `DefindexClient` y la API key; actualizar pantalla Yield.
- R3: mapear la convocatoria SCF activa (fechas, tracks, formato) y pedir la reunión formal con tu contacto para fin de mes 2, con demo F1 en mano.

**🎯 Hito M1:** demo en dispositivo real: *"el fondo del barrio rinde directo en Blend, sin intermediarios, con APY calculado on-chain"*. deployments.json nuevo + seed actualizado.

---

## MES 2 (septiembre) — F2: la Cadena de Barrio

- R1: contrato `savings_circle`: crear círculo (cuota, período, N), admisión solo-residentes (lee Governance), sorteo del orden (commit-reveal con datos de ledger), aportes, cobro por ronda, registro de defaults, reputación (persistent), yield del bote vía YieldAdapter, reparto al cierre (modo prorrata y modo "solidario" → fondo del barrio). Suite de tests grande: este contrato mueve plata de vecinos, trátalo como el Pool.
- R2: sección **Ahorro** en la app: crear/unirse/aportar/cobrar, timeline de rondas (usa el estilo de la Fig. 2 del paper), perfil de reputación en el RAÍZ Passport.
- R3: guion de demo de la tanda + borrador de la aplicación SCF (el paper es el anexo técnico; el pitch V2 ya existe).

**🎯 Hito M2:** una Cadena de Barrio completa (5+ cuentas demo, 3+ rondas, un default simulado, reparto de yield) corriendo end-to-end en testnet, grabada en video.

---

## MES 3 (octubre) — Aplicación SCF + endurecimiento  ⛩️ GATE Q1

- R3 + todos: **enviar la aplicación SCF** vía el referido: proyecto = F3 (custodia de enjambre + atestación vecinal) con F1+F2 como tracción demostrada; presupuesto por hitos (formato 10-20-30-40%); pedir también acceso al **Audit Bank** para auditar `savings_circle`. En el roadmap de la aplicación, mencionar F6 (voto secreto ZK) como siguiente frontera — la SDF lista "zkVoting" como caso de uso deseado y su estrategia 2026 prioriza privacidad; demuestra que RAÍZ surfea cada upgrade de protocolo (P27 → custodia, P25/26 → privacidad democrática).
- R1: hardening — spec-auditor sobre los contratos nuevos, fuzzing básico de savings_circle, revisión de TTLs (lección del gotcha DeFindex), documentar errores/eventos.
- R2: pulido de UX de Ahorro con 3–5 usuarios de prueba reales (amigos/familia con el APK).
- Diseño (no código) de F3: spec del smart account comunal (firmantes passkey, políticas k-de-n, límites, timelock) y del contrato `attestation`.

**⛩️ Gate Q1 (fin de octubre):** F1+F2 estables y aplicación SCF enviada.
- *Si el SCF avanza* → mes 4 según plan.
- *Si no avanza* → plan B: Instawards/Kickstart, Drips Wave de Stellar, y F3 en versión reducida (solo atestación, sin smart account comunal) mientras se reintenta la siguiente ronda. El proyecto no se detiene: F4 no depende de financiación.

---

## MES 4 (noviembre) — F3: custodia de enjambre + atestación

- R1: contrato `attestation` (aval de M vecinos con stake de reputación, ventana de disputa, revocación) integrado con `Governance.mint_resident`; smart account comunal (OpenZeppelin smart accounts + delegación P27) como `treasury_contract`/autoridad de barrio.
- R2: flujos de app: "avalar a un vecino" (firma passkey), panel de custodia (quiénes son los firmantes del fondo, co-firma de operaciones administrativas).
- R3: reglas sociales de la atestación con el barrio piloto candidato (¿cuántos avales? ¿quién arranca el génesis de residentes?).

**🎯 Hito M4:** un residente nuevo entra por atestación vecinal (sin admin) y una operación del fondo se ejecuta co-firmada por k-de-n passkeys, en testnet.

---

## MES 5 (diciembre) — F4 + preparación del piloto

- R1: `goal_vault` (metas con time-lock, aportes de terceros) + retos commit-to-save. Sorteo sin pérdida **solo si** R3 cerró el encuadre legal; si no, queda diseñado y se pospone (está en el plan, no en el critical path).
- R2: UI de metas/retos; el "Tip dirigido" del turista a una meta visible.
- R3: diseño del piloto real (barrio de Cartagena): 1 comercio ancla + 10–20 residentes semilla, rampa de entrada (mientras no haya anchor: coordinación manual documentada), métricas de éxito definidas ANTES de empezar (ej. ≥1 cadena completada, ≥50 pagos con tip, ≥1 propuesta ejecutada).

**🎯 Hito M5:** portafolio de ahorro completo en testnet + piloto diseñado con fecha.

---

## MES 6 (enero) — Consolidación y decisión de mainnet  ⛩️ GATE Q2

- Auditoría externa de savings_circle y attestation (Audit Bank del SCF idealmente).
- Pendientes de mainnet-readiness: `tx_hash` real en Executions, dominio propio + assetlinks.json para el rpId de passkey, revisión de fees/TTLs con parámetros de mainnet.
- Ejecutar el arranque del piloto (aunque sea en testnet con dinero simbólico: lo que se valida es el comportamiento, no el monto).

**⛩️ Gate Q2 (fin de enero):** decisión go/no-go de mainnet con criterios escritos: auditoría pasada + piloto con las métricas definidas en M5 + custodia sin clave única funcionando. Si algo falla, el gate dice exactamente qué reforzar antes de reintentar.

---

## Pista paralela F5 (investigación, ~medio día/semana, sin compromiso de fechas)

Orden por acotamiento técnico: **1) `stellar-light-verify`** (el más cerrado: verificar firmas SCP del tier-1 en Kotlin — prototipo razonable en 2-3 semanas de viernes), **2) PoC de mesh** (dos teléfonos, XDR por BLE, un gateway), **3) FROST** solo si entra financiación SCF dedicada — es el proyecto más profundo y el que más brilla como propuesta separada al fondo.

## Pista paralela F6 — Capa ZK: voto secreto (tercera ola, tras F2 estable)

Principio rector: **transparencia colectiva, privacidad individual** — el fondo común jamás se oculta; se privatiza el voto y, en fase 2, los aportes individuales. Regla de oro: **no escribir circuitos propios** (la clase de bug dominante en ZK son los circuitos under-constrained); usar el circuito estándar de Semaphore v4 con sus zkeys de ceremonia pública, y concentrar nuestro código en el verificador Soroban y la lógica de contrato.

Orden de trabajo (cada paso es un spike acotado):
1. **Vectores de prueba Poseidon↔circomlib** con `CryptoHazmat::poseidon_permutation` (validar equivalencia bit a bit antes de nada) — requiere la migración a SDK 26.1 hecha en F1.
2. **PoC verificador Groth16/BN254 en Soroban**: portar el ejemplo oficial `groth16_verifier` (BLS12-381) a `env.crypto().bn254()` + MSM de CAP-0080; medir instrucciones (presupuesto: caber con margen en ~100M/tx; referencia: 1 pairing ≈ 40M).
3. **Spike mopro en Android**: generar una prueba Semaphore-32 en un teléfono de gama media con bindings Kotlin (witnesscalc + rapidsnark; objetivo <1s).
4. **Contrato `vote_private`**: árbol LeanIMT del barrio + raíces válidas + nullifiers + tally público; integración con Governance (registro del commitment al acreditar residencia); el voto público actual queda como modo de compatibilidad hasta auditar.
5. **"Semaphore para Stellar" como segunda aplicación SCF** independiente: primitivo reutilizable por todo el ecosistema (no existe port; la SDF lista zkVoting como caso aspiracional).

Límites que se comunican siempre: no es receipt-free (la venta voluntaria de voto sigue siendo posible; MACI es la escalada si el soborno se vuelve amenaza), la coacción presencial no se elimina, y en censos pequeños el anonimato es estadísticamente débil (tallies agregados por cierre, nunca en vivo). Fase 2 (aportes privados con agregado público): esperar la salida de preview de los Confidential Tokens de OpenZeppelin sobre Stellar antes que reimplementar Pedersen a mano; alternativa v1 simple: denominaciones fijas de aporte.

## Riesgos operativos del plan

- **Testnet flaky en ráfagas** (ya lo sufres): los scripts reintentan; nunca programar demos sin haber corrido el seed ese mismo día.
- **Acoplamiento Kotlin/KSP/Hilt/SDK** (documentado en CLAUDE.md): congelar versiones durante el semestre salvo necesidad.
- **Scope creep**: cualquier idea nueva entra al backlog del Project, no al sprint. El gate trimestral es el único lugar donde se re-prioriza.
- **El equipo es chico**: si un mes se cae, se corre todo un mes — los gates lo absorben; lo que no se negocia es el orden F1→F2→SCF.

## Esta semana (arranque inmediato)

1. Commit de pendientes + decisión sobre ramas feat/.
2. Multisig 2-de-3 para el admin de testnet (Etapa A — medio día).
3. Reunión con tu contacto SDF usando el paper: objetivo explícito = entender el proceso de referido al SCF y qué les gustaría ver en la aplicación.
4. Spec del trait YieldAdapter escrita en `raiz_v2_spec_contratos.md`.
