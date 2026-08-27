# Plan de ejecución + instrucciones para Claude Code — 2026-08-27

> Cómo usar: este documento es el mapa. Cada Work Package (WP) trae: objetivo, criterio de aceptación, micro-detalles (los que cuestan horas si se descubren tarde) y un **prompt listo** para pegar en Claude Code. Trabaja los WP en orden — los prompts asumen ese orden.
>
> Contexto de fondo: `REVISION_2026-08-27.md` (hallazgos H1–H10 citados aquí por código).

---

## 0. Reglas macro (cómo dirigir a Claude Code en este repo)

**M1 — Una sesión, un WP.** No mezclar el relayer con la landing con SEP-24 en una misma conversación: el contexto se degrada y los commits salen mezclados. Sesión nueva por WP; el CLAUDE.md del repo ya orienta al agente.

**M2 — Plan mode primero para todo lo que toque contratos o firma.** Pedir plan (shift+tab en Claude Code), revisarlo tú, aprobar, y solo entonces ejecutar. Para UI y docs puede ir directo.

**M3 — Gates innegociables por commit** (ya son cultura del repo, mantenerlas): (a) `cargo test --workspace` 85+ verdes si tocó contratos; (b) `./gradlew assembleDebug` compila si tocó app; (c) `DEMO.md` corre sin cambiar el guion si tocó flujo; (d) spec actualizada en el MISMO commit si cambió una firma (`/spec-check` lo audita).

**M4 — Usa los subagentes que ya tienes** (`soroban-contract-dev`, `kmp-stellar-integration`, `compose-ui-builder`, `spec-auditor`) — los prompts de abajo los invocan explícitamente cuando conviene. Y los slash commands (`/test-contracts`, `/build-contracts`, `/spec-check`, `/deploy-testnet`, `/seed-testnet`).

**M5 — Commits atómicos con la convención del repo** (`feat(x): …`, `fix(x): …`, `docs(x): …` en español). Un WP puede ser varios commits; un commit nunca es medio WP y medio otro.

**M6 — Nada de mainnet, nada de secretos en el repo.** Los secretos del relayer viven en variables de entorno del servicio; verificar SIEMPRE `git diff` antes de commit buscando `S[A-Z0-9]{55}` (seeds) — pídeselo a Claude Code como paso final de cada sesión.

**M7 — Testnet se actualiza a Protocol 28 HOY 27-ago 17:00 UTC.** Si un deploy/seed/invoke falla raro hoy o mañana, sospecha de la ventana de upgrade antes que del código. No agendar demo con la Ambassador Lead antes del 29-ago sin correr el seed ese mismo día (regla que ya tienen en el plan de trabajo).

**M8 — Evidencia como artefacto de primera clase.** Todo lo que D1-D3 produzca (screenshots, hashes, links) va cayendo en `docs/evidencia_sow/` desde el día 1 — no reconstruirla al final de memoria.

---

## 1. Calendario propuesto (recuperación del SOW)

| Días | WP | Entregable visible |
|---|---|---|
| Día 0 (hoy) | WP0 higiene + fixes públicos | Repo limpio, landing viva con contratos reales, mensaje a la Ambassador Lead |
| Días 1–6 | WP1 relayer (D1) | Repo `raiz-relayer` público + servicio en testnet + app migrada + APK sin secretos |
| Días 7–9 | WP2 tx hash real (D2) | Dashboard con ≥3 ejecuciones enlazadas a Stellar Expert |
| Días 10–15 | WP3 SEP-10/24 (D3) | Depósito completo desde la app + video 60s |
| Días 16–17 | WP4 paquete de evidencia | Evidence package entregado al chapter |
| Después | WP5 F2 `savings_circle` / WP6 UI+fixes | — |

La conversación con Laura Estupiñán va HOY con esta tabla (fechas absolutas), no cuando esté todo listo. Mensaje sugerido: F1 fortaleció el protocolo (yield propio, sin API keys — enseñar el fondo rindiendo), el sprint D1-D3 arranca ya con este calendario; confirmar contra qué fecha corre el reloj del award.

---

## WP0 — Higiene + fixes públicos (medio día, HOY)

**Objetivo:** repo committeable y cara pública correcta antes de escribir código nuevo.

**Alcance:**
1. `.gitattributes` con `* text=auto` + `git add --renormalize .` (mata el churn CRLF de ~50 archivos; ya diseñado el 11-ago, solo ejecutar).
2. Commits temáticos de lo pendiente + untracked (SOW pdf, propuestas, plan — versionarlos; decidir `.stellar-memory/` → `.gitignore`).
3. **H1**: landing + `verificar-residente.html` a los contratos vigentes de `deployments.json` (los 4 IDs viejos `CAKYU5HW…/CAENXDX7…/CDGGFSV7…/CD5OET7F…` mueren). Idealmente: bloque `const DEPLOYMENTS = {…}` único por archivo y comentario "fuente: deployments.json — actualizar en cada redeploy" (o script `scripts/sync_landing_ids.sh`).
4. Borrar ramas remotas mergeadas (`git push origin --delete feat/…` ×3).
5. Actualizar el bloque "Estado actual / Próximo paso" del CLAUDE.md (bloque listo en §4 de este doc).
6. Arreglos `.gitignore` que su propia auditoría listó (línea ` .docs/` con espacio, `gradle.properties` sin ancla, `android/.shots/`).

**Aceptación:** `git status` limpio; raizapp.xyz muestra datos del deploy vigente; `verificar-residente` valida contra el governance actual; CLAUDE.md apunta al SOW.

**Prompt listo (pegar en Claude Code):**
```text
Lee CLAUDE.md y docs/REVISION_2026-08-27.md (hallazgo H1 y sección 5).
Tarea WP0 — higiene del repo y fixes públicos, en este orden:
1. Crea .gitattributes con `* text=auto`, corre `git add --renormalize .` y sepárame los cambios en commits temáticos coherentes (churn de EOL aparte del trabajo real). Muéstrame el plan de commits ANTES de ejecutarlos.
2. Versiona los untracked: RAIZ-Instaward-SOW.pdf, plan_trabajo_raiz.md, propuesta_*.md, raiz_paper.* (los duplicados de docs/NuevaPropuesta se quedan como canónicos — elimina las copias de la raíz si son idénticas, verifica con diff). Añade .stellar-memory/ a .gitignore.
3. Hallazgo H1: landing/index.html y landing/verificar-residente.html usan contract IDs del deploy pre-F1. Reemplázalos por los de deployments.json (pool CD775D33…, governance CBBYI45J…, treasury CACZWU3B…, rewards CDTTEZX2…, yield_adapter CA5J6YVH…). Centraliza los IDs en un solo objeto JS por archivo con comentario de procedencia. Verifica que no quede NINGÚN ID viejo en TODO landing/ (pitch.html incluido): grep de los 4 prefijos viejos debe dar 0.
4. Corrige .gitignore: línea " .docs/" (espacio inicial), ancla gradle.properties a raíz (/gradle.properties), añade android/.shots/.
5. Reemplaza la sección "Estado actual" y "Próximo paso" de CLAUDE.md por el bloque que está en docs/PLAN_CLAUDE_CODE_SOW.md §4.
Al final: `git log --oneline -10`, y un grep por secretos (S[A-Z0-9]{55}) sobre el diff completo antes de cada commit.
NO hagas push sin mostrarme el estado final.
```

---

## WP1 — D1: Admin Relayer (días 1–6)

**Objetivo SOW:** backend open-source que firma server-side los 3 flujos admin; la app lo consume; APK release con cero secretos, verificable por decompilación.

**Decisiones de arquitectura (tomadas — no re-discutir con el agente, están alineadas al SOW y al stack):**
- **Repo NUEVO y público** `raiz-relayer` (el SOW promete "public open-source repository"; separado del monorepo para que su historia sea limpia y auditable). Licencia MIT/Apache-2.
- **TypeScript + Fastify + @stellar/stellar-sdk** (SDK oficial JS con soporte Soroban completo). Node 22 LTS. Razones: velocidad de desarrollo, ecosistema de deploy gratuito/barato (el SOW presupuestó VPS 3 meses), legibilidad para el reviewer.
- **Endpoints** (JSON, todos POST): `/v1/register-merchant`, `/v1/mint-resident`, `/v1/faucet` + `GET /v1/health` (usado por la app para feature-flag del relayer). Respuestas siempre `{ok, txHash?, error?}`.
- **Autorización pragmática de testnet** (documentada como tal): API key estática de la app (header) + rate limit por IP y por address destino (p.ej. faucet: 1/address/10min, cap diario) + allowlist de contratos destino (solo los 5 de deployments.json). NADA de esto pretende ser seguridad mainnet — el README lo dice explícito y remite a F3 (custodia comunal) como sucesor.
- **Firma:** la clave admin SOLO en env (`RELAYER_ADMIN_SECRET`). **Cola serializada de transacciones** (una a la vez) para no romper el sequence number; reintento con el patrón del repo (propagación RPC). Timeout + idempotency-key opcional por request.
- **Deploy:** Fly.io o Railway (Dockerfile incluido). Logs estructurados (pino) sin secretos.
- **App:** nuevo `RelayerClient.kt` (Ktor, ya está en deps); `SorobanClient.registerMerchant/mintResident/fundContractUsdc` migran a llamarlo; TODO rastro de `DEMO_ADMIN_SECRET` fuera de `build.gradle.kts` y `WalletManager` (los otros dos demo secrets pueden quedarse: son wallets de usuario demo, no autoridad). `BuildConfig.RELAYER_URL` desde local.properties con default al servicio desplegado.

**Micro-detalles que ahorran días:**
- `mint_resident` exige `require_auth` del **barrio_admin** guardado en Governance — el relayer firma con esa identidad (hoy = admin del protocolo; verificar que `set_barrio_admin` de los 3 barrios seed apunta a la G… del relayer/admin — está en `seed_testnet.sh`).
- El faucet transfiere el USDC de Blend: la cuenta admin necesita balance — el relayer debe responder un error CLARO cuando se agote (y el runbook del README explica re-fondear con el faucet de Blend, URL en CLAUDE.md).
- Verificación "cero secretos": `apktool d app-release.apk && grep -rE "S[A-Z0-9]{55}" .` → 0 matches (más: buscar el G… admin también, no debería estar hardcodeado). Ese comando literal va en el doc de evidencia.
- Regression en dispositivo físico de los 3 flujos (el SOW lo pide explícito) — Motorola G04 como referencia (tu dispositivo confirmado).
- `versionName 0.2.0`, `versionCode 2` para el APK evidencia.

**Aceptación:** los 3 flujos corren desde la app SIN `DEMO_ADMIN_SECRET` en el APK; relayer público con README de verificación; APK decompilado sin secretos; evidencia en `docs/evidencia_sow/d1/`.

**Prompt listo — sesión A (relayer, repo nuevo):**
```text
Contexto: monorepo RAÍZ (lee CLAUDE.md) + docs/PLAN_CLAUDE_CODE_SOW.md WP1 (decisiones de arquitectura tomadas — respétalas).
Crea el repo raiz-relayer en una carpeta hermana con: TypeScript + Fastify + @stellar/stellar-sdk (Node 22), endpoints POST /v1/register-merchant, /v1/mint-resident, /v1/faucet y GET /v1/health según el contrato de la sección WP1. Requisitos duros:
- Clave admin SOLO desde env RELAYER_ADMIN_SECRET; el proceso se niega a arrancar si falta o si NETWORK != testnet.
- Cola serializada de submits (sequence number), reintentos ante errores de propagación RPC (patrón: 5 intentos, backoff 3s), timeout 30s.
- Rate limits: faucet 1 req/address/10min y 50/día global; register/mint 20/día global; allowlist de contratos = los 5 de deployments.json (cópialos como config).
- API key estática por header x-raiz-app-key (env), CORS cerrado.
- invoke de contratos Soroban: register_merchant(MerchantData), mint_resident(barrio_admin, resident, barrio_id) — firmas exactas en contracts/pool/src/lib.rs y contracts/governance/src/lib.rs; faucet = SAC transfer del USDC de Blend (CAQCFVLO…) como en SorobanClient.fundContractUsdc (léelo como referencia de manejo de errores/polling).
- Tests: unit del rate-limit y la cola; integration opcional contra testnet detrás de env flag.
- Dockerfile + fly.toml + README en español: setup, endpoints, modelo de amenazas honesto (testnet), y runbook de re-fondeo del faucet.
Plan mode primero: muéstrame estructura de archivos y el contrato de request/response de cada endpoint antes de escribir código.
```

**Prompt listo — sesión B (migración de la app):**
```text
Lee CLAUDE.md y docs/PLAN_CLAUDE_CODE_SOW.md WP1. El relayer ya corre en <URL> (health OK).
Migra la app para eliminar la autoridad admin del APK:
1. Crea data/relayer/RelayerClient.kt (Ktor client, ya en deps): registerMerchant, mintResident, faucet, health — mapea errores a RaizResult con los RaizErrorCode existentes.
2. BuildConfig.RELAYER_URL y RELAYER_APP_KEY desde local.properties (defaults: URL del servicio, key vacía).
3. Sustituye los call-sites admin: BecomeMerchantViewModel (register), ProposalsViewModel/flujo verificar-residente (mint_resident), y el faucet de bienvenida (fundContractUsdc) → RelayerClient. Usa el subagente kmp-stellar-integration si hay dudas de tipos.
4. Elimina DEMO_ADMIN_SECRET de build.gradle.kts y demoAdminKeyPair() de WalletManager + todos sus usos. DEMO_TOURIST_SECRET y DEMO_RESIDENT_SECRET se quedan.
5. UI: estados de carga/error para llamadas al relayer (puede tardar ~10s por propagación) — spinner + mensaje reintentable, paleta del CLAUDE.md.
6. Sube versionName a 0.2.0, versionCode 2.
Gates: ./gradlew assembleDebug verde; luego assembleRelease y verifica tú mismo descomprimiendo el APK que no exista ningún S[A-Z0-9]{55} ni la G… del admin hardcodeada; pega el comando y su salida en docs/evidencia_sow/d1/verificacion_apk.md.
DEMO.md debe seguir corriendo igual (el guion no cambia: los flujos ahora pasan por el relayer).
```

---

## WP2 — D2: tx hash real en el dashboard (días 7–9)

**Objetivo SOW:** cada Execution del dashboard enlaza a su transacción real en Stellar Expert (matando el sha256 determinístico como "el" hash mostrado).

**Diseño (cliente, sin tocar contratos — decisión):** `getEvents` del RPC ya devuelve `txHash` por evento. Nuevo método en `SorobanClient`: `executionEvents(barrioId)` filtrando `topic[0]=Symbol("execution")` del contrato Treasury (mismo patrón de paginación por cursor que `tourPaymentEvents`, misma ventana/fallback de retención ~17k ledgers). Correlación con `get_execution_log` por `proposal_id` (+ `executed_at` como desempate). El `tx_hash` on-chain queda como campo de reproducibilidad (documentar en spec) — eliminarlo es decisión de redeploy futuro, no del sprint.

**Micro-detalles:**
- Retención del RPC: ejecuciones más viejas que la ventana no tendrán evento disponible → la UI distingue "hash verificado" (evento encontrado, link Expert) de "ejecución histórica" (solo registro on-chain, sin link) — NO inventar links.
- El momento de captura barato: cuando la PROPIA app dispara `execute_proposal`, el `sendTransaction` devuelve el hash — persistirlo/mostrarlo directo (camino feliz sin getEvents).
- Para la evidencia hacen falta ≥3 ejecuciones: preparar en seed 3 propuestas aprobables y ejecutarlas (o script pequeño) — con el reloj de 3-14 días de duración mínima, OJO: usar propuestas creadas con `duration_days=3` y ledger time… en testnet no se puede viajar en el tiempo: **planear las 3 ejecuciones con 3+ días de antelación** (crear las propuestas el día 1 del sprint, votarlas, ejecutarlas el día 4+). Este es el micro-detalle que puede reventar el calendario si se descubre tarde → las propuestas de evidencia se crean EN WP0/WP1, no en WP2.
- `landing/index.html` (dashboard web) también muestra executions: replicar el link ahí (lee getEvents desde JS — ya consulta el RPC).

**Aceptación:** dashboard app + landing muestran ≥3 ejecuciones con link Stellar Expert funcionando; screenshots en `docs/evidencia_sow/d2/`.

**Prompt listo:**
```text
Lee CLAUDE.md, docs/PLAN_CLAUDE_CODE_SOW.md WP2 y la revisión H-D2. Implementa el enlace de transacción real de las Executions:
1. SorobanClient: nuevo executionEvents(barrioId): pagina getEvents (cursor, MAX_PAGES, ventana EVENTS_LOOKBACK con fallback — copia el patrón exacto de tourPaymentEvents) filtrando contrato Treasury topic[0]="execution"; parsea (proposal_id, amount, recipient) y captura txHash del evento.
2. Modelo Execution de la app: añade realTxHash: String? y verified: Boolean derivado.
3. En el flujo que ejecuta propuestas (executeProposal): captura el hash devuelto por sendTransaction y muéstralo inmediato (camino feliz).
4. Dashboard (DashboardScreen/ViewModel): cada Execution con realTxHash → chip/onda "Ver en Stellar Expert" (URL helper ya existe en ui/util/StellarExpert.kt); sin él → estado "histórica, sin evento en ventana RPC" (sin link). Nunca mostrar el sha256 como si fuera hash de transacción; renómbralo en UI a "ID de auditoría" o quítalo de la vista.
5. landing/index.html: replica la correlación (JS: getEvents del Treasury vigente) para que las executions del dashboard web enlacen a Expert.
6. Actualiza docs/raiz_v2_spec_contratos.md: documenta que Execution.tx_hash on-chain es hash de reproducibilidad, no tx hash — y que el real viene del evento.
Gates: app compila; captura screenshots de ≥3 ejecuciones enlazadas → docs/evidencia_sow/d2/. Si no existen aún 3 ejecuciones on-chain con evento en ventana, dime y creamos/ejecutamos propuestas primero.
```

---

## WP3 — D3: SEP-10 + SEP-24 (días 10–15)

**Objetivo SOW:** depósito SEP-24 interactivo completado end-to-end desde la app contra `testanchor.stellar.org`, reemplazando el faucet admin como on-ramp "real". Evidencia: video 60s + tx hash.

**Decisiones de alcance (tomadas — ver revisión §2-D3):**
- Asset del depósito = **el USDC del testanchor** (issuer `GBBD47IF…FLA5`) llegando a la wallet G… del usuario. El swap a USDC-Blend es stretch (si sobra tiempo: path payment strict receive, o documentar como "fase anchor de producción").
- **Solo wallets seed (G…)** en este sprint; passkey (C…) muestra estado "próximamente" con explicación (SEP-10 clásico no autentica contratos; eso es SEP-45/F3).
- El faucet admin NO se borra: queda como "modo demo" secundario; el on-ramp primario en UI pasa a ser "Depositar (anchor de prueba)".
- Flujo: stellar.toml → SEP-10 (`WebAuth`: challenge → firmar con KeyPair → JWT) → SEP-24 `interactive deposit` → abrir URL en **Chrome Custom Tab** → polling `GET /transaction` hasta `completed` → trustline USDC-anchor si falta (la app la crea antes del depósito) → balance visible.
- Todo con `kmp-stellar-sdk` 1.6.x (trae SEP-1/10/24 — cero dependencias nuevas).

**Micro-detalles:**
- La trustline requiere XLM de reserva — las cuentas seed del onboarding se fondean con friendbot, OK; verificar en flujo importado.
- El anchor de prueba pide claim del depósito en su UI web (simula KYC/monto) — el video debe mostrar ese paso sin cortes (es la gracia del "interactive").
- Polling con backoff y timeout visible (estado "el anchor está procesando…") — testnet flaky post-P28 (M7).
- El JWT SEP-10 expira: guardarlo en memoria de sesión, no persistirlo.
- HorizonStream ya observa balances de G… — el balance del asset nuevo aparece si la data class de balances no filtra por asset code/issuer (revisar: hoy probablemente filtra solo USDC-Blend → añadir el USDC-anchor a la vista wallet, etiquetado "USDC · anchor de prueba" para no mentir sobre fungibilidad).
- UI nueva mínima: 1 pantalla "Depositar" (monto sugerido, botón, estados) + entrada desde Wallet. Paleta y componentes existentes (`BalanceCard`, `StatBox`).

**Aceptación:** video 60s de un depósito completo (app → Custom Tab del anchor → balance actualizado) + tx hash del pago del anchor en `docs/evidencia_sow/d3/`.

**Prompt listo:**
```text
Lee CLAUDE.md y docs/PLAN_CLAUDE_CODE_SOW.md WP3 (alcance decidido: USDC del testanchor a wallets seed G…; passkey queda fuera con mensaje; faucet queda como modo demo).
Implementa SEP-10 + SEP-24 deposit contra testanchor.stellar.org usando los módulos SEP de kmp-stellar-sdk 1.6 (verifica primero en el SDK: sep.sep01 stellar.toml, sep.sep10 WebAuth, sep.sep24 InteractiveFlow — lista las APIs reales antes de codear, usa el subagente kmp-stellar-integration):
1. data/anchor/AnchorClient.kt: loadToml, webAuth(keyPair) → JWT en memoria, interactiveDeposit(asset=USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5, account, jwt) → interactiveUrl + id, y getTransaction(id, jwt) para polling (backoff 3s→10s, timeout 5 min).
2. Trustline: si la cuenta no tiene la trustline del USDC-anchor, créala antes (ChangeTrust firmada por el usuario) con explicación en UI.
3. UI: pantalla DepositScreen (Compose, paleta CLAUDE.md) accesible desde WalletScreen: CTA "Depositar", abre la interactiveUrl en Chrome Custom Tab (androidx.browser — añade la dep), al volver muestra polling de estado hasta completed y el balance nuevo. Para wallets passkey: estado deshabilitado "Disponible pronto para passkey (SEP-45)".
4. WalletScreen/HorizonStream: muestra el balance del USDC-anchor como línea propia "USDC · anchor de prueba" (no lo sumes al USDC de Blend).
5. El faucet actual se re-etiqueta "USDC demo (Blend)" y baja de prominencia.
Gates: compila, flujo completo probado en dispositivo físico; deja escritos los pasos exactos para grabar el video 60s en docs/evidencia_sow/d3/guion_video.md, incluyendo dónde capturar el tx hash del pago entrante del anchor (Horizon /payments de la cuenta).
Plan mode primero: enséñame las APIs del SDK que vas a usar y el diagrama del flujo antes de escribir código.
```

---

## WP4 — Paquete de evidencia + cierre del SOW (días 16–17)

**Checklist (mapea 1:1 con SOW §6):**
- D1: link repo relayer + APK release descargable (GitHub Release del monorepo) + `verificacion_apk.md` (1 página: comandos apktool/grep y salida) + screenshots de los 3 flujos.
- D2: screenshots dashboard + 3 links Stellar Expert vivos (probarlos en incógnito).
- D3: video 60s (YouTube no listado) + tx hash + link Expert.
- Nota de redeploy: párrafo que explica que los contratos del Annex A del SOW (deploy pre-F1) fueron re-desplegados el 31-jul (F1) con la tabla vieja→nueva y links nuevos — le ahorra al reviewer la confusión de contratos "muertos" (H1).
- Re-grabar/actualizar el video demo general si el tiempo alcanza (el relayer + SEP-24 en escena venden solos la aplicación al Build Award).

**Prompt listo:**
```text
Lee docs/PLAN_CLAUDE_CODE_SOW.md WP4 y el SOW (RAIZ-Instaward-SOW.pdf §6). Ensambla docs/evidencia_sow/README.md: tabla entregable→evidencia→link/archivo, con todo lo acumulado en d1/ d2/ d3/. Redacta la "nota de redeploy" (tabla contratos Annex A → vigentes). Verifica cada link (los Expert en incógnito). Formato: español, 1 página principal, verificable por un revisor no técnico en <10 minutos (requisito literal del SOW).
```

---

## WP5 — F2 `savings_circle` (arranca tras WP4; el diseño puede adelantarse en ratos muertos del sprint)

**Regla de herencia (lo importante):** F2 nace SIN las deudas que la revisión encontró en el núcleo:
- TTL on-touch desde el día 1 (H2) — una tanda de 12 semanas no puede archivarse a mitad de cadena.
- `__constructor` en vez de `initialize` (H4).
- Censo/membresía por **snapshot al abrir la ronda** (H8) — jamás denominador vivo.
- Strings acotados (H10-2) y eventos con valores REALES (H10-1).
- Depende de `revoke_resident` (H6): diseñarlo en Governance ANTES o junto con el circle (la reputación de tanda exige censo saneable).

**Secuencia sugerida:** (1) spec completa en `raiz_v2_spec_contratos.md` §Contrato 6 (estados de círculo, sorteo commit-reveal con datos de ledger, defaults y reputación persistent, yield del bote vía adapter, reparto prorrata/solidario) → (2) revisión tuya en frío → (3) contrato + suite de tests "nivel Pool" (28+) → (4) UI pestaña Ahorro. El plan de trabajo M2 ya lo describe bien; la spec-primero es la única adición (el repo funciona así: la spec ES la fuente de verdad).

**Prompt de arranque (cuando toque):**
```text
Lee CLAUDE.md, docs/raiz_v2_spec_contratos.md, docs/PLAN_CLAUDE_CODE_SOW.md WP5 (reglas de herencia H2/H4/H6/H8/H10 — son requisitos, no sugerencias) y propuesta_raiz_ahorro_enjambre.md §4.1. NO escribas código todavía: redacta la spec del Contrato 6 `savings_circle` en raiz_v2_spec_contratos.md con el mismo formato/rigor del Contrato 5 (firmas Rust exactas, storage keys, errores numerados, eventos con symbol_short válidos ≤9 chars, decisiones de diseño justificadas). Incluye: ciclo de vida del círculo, snapshot de miembros, sorteo commit-reveal, manejo de default (parcial/total), reputación, integración YieldAdapter (bote rinde), reparto, y estrategia TTL de cada key. Al final, lista de ≥25 tests que escribirás. Lo reviso antes de implementar.
```

---

## WP6 — Lote UI / issues / fixes de seguridad (paralelo a esperas de QA, o post-WP4)

Orden por (impacto público × esfuerzo):
1. **H7** `network_security_config`: `user` CAs solo bajo `<debug-overrides>` (5 líneas, seguridad real).
2. **H3** guard de `register_barrio` (`AlreadyExists`) + test.
3. **H9** Treasury: rescatar solo el faltante + test "con liquidez no toca el vault".
4. **H10-1** evento withdraw con `(burned, got)` reales; **H10-2** cap de description (500 bytes) + tests.
5. **H10-3** CI: job `stellar contract build` (wasm32v1-none) junto a los tests.
6. **H5** (el grande del lote): conectar firma passkey real en pagos (OZTransactionOperations.contractCall — el TODO de WalletManager.kt:211 lo describe). Sesión propia con plan mode; tocará PayViewModel + eventos (el filtro por G…/C… de tourPaymentEvents).
7. UI fina: estados vacíos consistentes (barrios sin comercios, historial vacío), accesibilidad de contraste en chips amarillos sobre blanco, animación de éxito unificada (RaizSuccessAnimation ya existe — usarla en depositar/canjear).
8. **H6** `revoke_resident` (si no cayó en WP5).

**Prompt lote corto (1-4):**
```text
Lee docs/REVISION_2026-08-27.md hallazgos H3, H7, H9, H10-1, H10-2 y docs/PLAN_CLAUDE_CODE_SOW.md WP6. Implementa los 5 en commits separados (fix(seguridad): …, fix(pool): …, etc.), cada uno con su test de regresión donde aplique (H7 no lleva test unit — verifica manualmente que debug sigue conectando a testnet). Gate: cargo test --workspace todo verde + assembleDebug. Actualiza raiz_v2_spec_contratos.md donde cambie comportamiento (H3, H9).
```

---

## 4. Bloque de reemplazo para CLAUDE.md (sección "Estado actual" y "Próximo paso")

```markdown
## Estado actual (2026-08-27)

- **F1 completada** (yield vía BlendAdapter en testnet, DeFindex eliminado). 85 tests verdes.
- **PRIORIDAD ABSOLUTA: sprint SOW Instaward (D1 relayer, D2 tx hash real, D3 SEP-10/24).**
  Plan, decisiones y prompts: `docs/PLAN_CLAUDE_CODE_SOW.md`. Revisión de contexto:
  `docs/REVISION_2026-08-27.md` (hallazgos H1–H10).
- F2 (`savings_circle`) queda EN PAUSA hasta entregar la evidencia del SOW; solo su spec
  puede avanzar (WP5).
- Regla nueva: todo contrato nuevo nace con gestión de TTL, `__constructor`, snapshot de
  censo y strings acotados (herencia de la revisión H2/H4/H8).

### Próximo paso

- El WP activo según `docs/PLAN_CLAUDE_CODE_SOW.md` §1 (calendario). Al cerrar cada WP,
  actualizar esta línea.
```

---

## 5. Micro-radar permanente (cosas pequeñas que muerden — tenerlas presentes en TODA sesión)

1. Testnet P28 desde hoy (M7) — y mainnet 16-sep: antes del próximo redeploy, subir Stellar CLI 23.2.1 → 27.x y verificar que el OZ smart account kit no necesita update por CAP-85 (custom accounts).
2. `deployments.json` → `android/app/src/main/assets/` sigue siendo copia MANUAL tras cada deploy (automatizarla en `deploy_testnet.sh` es un fix de 3 líneas que ya está en su lista).
3. Los montos SIEMPRE en stroops i128/Long; `symbol_short!` ≤ 9 chars; `barrio_id` hex-64.
4. Propuestas de gobernanza tardan ≥3 días en poder ejecutarse — TODA evidencia que necesite una ejecución se siembra con 3+ días de antelación.
5. Ninguna clave `S…` sale de local.properties/env; grep del diff antes de cada commit (M6).
6. Retención de eventos RPC ~24h/17k ledgers: los flujos que dependan de eventos deben degradar con elegancia (patrón fallback ya existente en tourPaymentEvents).
7. Al terminar cada WP: `docs/ESTADO_PROYECTO_*.md` NO se actualiza (histórico); lo vivo es CLAUDE.md "Estado actual" + este plan.
