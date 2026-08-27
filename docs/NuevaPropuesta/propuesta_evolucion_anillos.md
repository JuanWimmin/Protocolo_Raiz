# RAÍZ crece por anillos — evolución que conserva el núcleo

> Respuesta a la observación "la propuesta deja a un lado el proyecto existente" · 2026-08-11
> Repo: `github.com/JuanWimmin/Protocolo_Raiz` · testnet activa · roadmap canónico F1–F6 en `docs/NuevaPropuesta/`

---

## 1. El comentario acierta en la narrativa — y la arquitectura ya demostró lo contrario

La crítica es válida donde duele: el paper se titula *"de red de pagos turísticos **a** protocolo de ahorro"*, y ese "a" se lee como un pivote que abandona lo construido. Pero la arquitectura propuesta siempre fue aditiva, y ya no hay que creerlo por fe: **F1 se ejecutó el 31 de julio y es la prueba empírica del método**:

| Qué pasó en F1 | Evidencia (git) |
|---|---|
| Migración soroban-sdk 22.0.8 → 26.1.1 | `f691cd7` |
| Nace el 5º crate `yield_adapter` (BlendAdapter) — DeFindex fuera | `9816389` |
| La app cambia `DefindexClient` por `BlendClient` de lecturas puras | `b76a8ca` |
| Re-deploy a testnet, fondo **rindiendo en Blend v2 en vivo** | `f0ce9d3` |
| CI de contratos en GitHub Actions + toolchain pineado | `7fe2356` |

Resultado: **85/85 tests verdes** (governance 21, pool 28, rewards 12, treasury 6, yield_adapter 18), los cuatro contratos originales conservan su API pública, las 7 pantallas siguen siendo las mismas, y el guion de `DEMO.md` corre idéntico. No se dejó a un lado el proyecto: se le añadió un órgano. Esta propuesta formaliza ese método como regla para todo lo que viene.

## 2. Principio rector: anillos alrededor del núcleo

**La raíz no se arranca; se profundiza.** El núcleo de RAÍZ es intocable porque es el motor económico y el corazón del pitch: `pay_merchant` con Tip Barrio, gobernanza soulbound (1 vecino = 1 voto), Treasury trustless, Rewards, mapa, dashboard de transparencia y RAÍZ Passport. **Sin la red de pagos no existe el protocolo de ahorro: no hay pagos → no hay tip → no hay fondo → no hay nada que ahorrar.** El proyecto original no es el pasado de la propuesta; es su fuente de energía permanente.

Reglas de conservación (contrato de ingeniería del equipo):

1. **API del núcleo congelada.** Los 4 contratos originales no cambian firmas públicas; se extienden con funciones nuevas o contratos hermanos.
2. **Todo anillo es aditivo y reversible.** Cada capa nueva puede apagarse (o no desplegarse) sin que el núcleo lo note.
3. **Coexistencia, no sustitución.** Ejemplo: `vote_private` (ZK) convivirá con `vote`; el modo público no se retira hasta auditar el privado.
4. **Los 85 tests del núcleo nunca bajan.** CI ya lo vigila en cada push.
5. **La prueba de regresión de producto es `DEMO.md`:** después de cada anillo, la demo original de 90 segundos debe correr sin cambiar una palabra del guion.
6. **El pitch abre siempre con el Tip Barrio.** El ahorro, la custodia y el ZK se presentan como lo que son: consecuencias del motor, no reemplazos.

## 3. Mapa de anclaje: cada tecnología se conecta a un hook que el código original YA expone

Esta es la sección que responde técnicamente al comentario. Ninguna tecnología de la propuesta necesita reescribir el núcleo, porque el diseño original —sin saberlo— dejó los enchufes puestos:

| Anillo | Hook existente en el código original | Qué se toca del núcleo |
|---|---|---|
| **Yield Blend** (F1, ✅ hecha) | Interfaz `yield_adapter_client` en Pool (patrón que Pool ya usaba para el vault) | Pool mínimo; Governance/Rewards: nada |
| **Cadena de Barrio** (F2) | `Governance.get_resident()` — el contrato `savings_circle` **lee** la residencia soulbound sin modificar Governance; el bote rinde vía el `yield_adapter` ya desplegado | Nada. Contrato hermano + pestaña "Ahorro" nueva; las 7 pantallas quedan |
| **Custodia enjambre** (F3) | `BarrioData.treasury_contract` y los `Admin` son `Address` — una cuenta multisig o smart account comunal ocupa ese lugar **hoy** sin cambiar una línea de contrato; `scripts/setup_admin_multisig.sh` ya está en el repo | Nada en contratos; cambia *quién* firma, no *qué* se firma |
| **Atestación vecinal** (F3) | `Governance.set_barrio_admin(barrio, X)` acepta cualquier Address → X pasa a ser el contrato `attestation`/cuenta comunal. `mint_resident` no cambia | Nada. El "admin del barrio" deja de ser una persona y pasa a ser la comunidad |
| **Voto secreto ZK** (F6) | El prerequisito duro (SDK ≥25) **ya se cumplió en F1** (26.1.1). `vote_private` se añade junto a `vote` | Governance se extiende (función + árbol de commitments); `vote` sigue existiendo |
| **Mesh / light-verify / DePIN** (F5) | La tx Stellar es un XDR transportable; los eventos ya existen para indexar | Nada en contratos del núcleo; capa app + contrato nuevo `swarm_rewards` |

Lectura del cuadro: el proyecto original no solo se conserva — **es la infraestructura de anclaje de todo lo nuevo**. La identidad soulbound que ya funciona es lo que hace posible la tanda sybil-resistente; el `treasury_contract` por barrio que ya existe es lo que hace trivial la custodia comunal; los eventos que ya alimentan el dashboard son lo que el enjambre indexará.

## 4. La propuesta no sustituye el roadmap original del README: lo ejecuta

| Roadmap original (README §9 / spec) | Cómo lo cumple la propuesta |
|---|---|
| "Admin → backend/relayer (quitar la clave del APK)" — requisito de mainnet | **Custodia enjambre**: lo cumple *mejor* que un backend — sin servidor, coherente con la tesis "RAÍZ no tiene backend propio" |
| "KYC de residencia (SEP-12) en vez del mint manual" | **Atestación vecinal** como KYC comunitario de primer nivel; SEP-12 queda disponible para montos altos, exactamente como preveía la spec original |
| "Anchors SEP-10/24/38 (MoneyGram, Vibrant/Anclap)" | Intacto en el plan (mes 5, piloto) — reforzado con candidatos nuevos (Pollar cubre Colombia con settlement USDC) |
| "tx_hash real en Executions" · "dominio propio para passkey" · "IPFS premios" | Se mantienen tal cual en el plan (mes 6) |
| "Mainnet" | Gate Q2 con criterios escritos |

Quien lea el README de hace dos meses y esta propuesta encontrará el mismo proyecto, más profundo.

## 5. La narrativa corregida

- ❌ Antes: *"RAÍZ pasa de red de pagos a protocolo de ahorro"* (suena a que los pagos fueron un borrador).
- ✅ Ahora: *"La red de pagos es el motor; el ahorro es donde vive el valor que el motor genera."*

Frase para el pitch: **"RAÍZ sigue siendo la app donde el turista paga y el barrio decide — ahora, además, el barrio ahorra, se custodia a sí mismo y vota en secreto."**

Cambios concretos que propongo al paper/HTML cuando apruebes este encuadre (30 min de trabajo, mismo contenido técnico):

1. Retitular: *"RAÍZ: una red de pagos turísticos que madura en infraestructura de ahorro comunitario"* (el "que madura en" conserva; el "de X a Y" abandonaba).
2. Nueva subsección temprana **"Principio de conservación"** con la tabla de anclaje del §3 y la regla de la demo intacta.
3. En la introducción, una línea que hoy falta: *"Nada de lo desplegado se reemplaza: los cuatro contratos originales son la infraestructura de anclaje de cada capa nueva."*
4. En la hoja de ruta, marcar F1 como **ejecutada** con sus commits como evidencia del método.

## 6. Estado real a 2026-08-11 y siguiente paso

- **F1 completada y desplegada** (fondo rindiendo en Blend v2 en testnet, sin DeFindex, sin API keys). SDK 26.1.1 migrado — el prerequisito de ZK quedó cumplido de pasada.
- **Nota de higiene git:** el working tree muestra ±16.401 líneas idénticas insertadas/borradas en 50 archivos — es churn de fines de línea (CRLF/LF), no trabajo en curso. Antes del próximo commit: añadir `.gitattributes` con `* text=auto` y `git add --renormalize .`, para que los diffs vuelvan a ser legibles.
- **Estrategia git para los anillos:** `main` protegido con el CI ya montado; una rama por anillo; merge solo con 85+ tests verdes y `DEMO.md` verificada en dispositivo.
- **Siguiente anillo: F2 — Cadena de Barrio** (`savings_circle`), como ya declara el CLAUDE.md del repo. Es también el mejor argumento futuro contra la crítica: la primera pantalla nueva que verá quien comentó será una tanda operada por los mismos residentes soulbound del proyecto que conoce.
