# RAÍZ — Deck de slides (hackathon, 7-10 min)

> 14 slides. Cada una: título + bullets + nota visual. Listo para pasar a Canva/PPT/HTML.
> Paleta RAÍZ: negro `#1a1a1a`, amarillo `#FBBF24`, púrpura `#534AB7`, verde `#0F6E56`, fondo `#FAFAF7`.
> Slogan oficial: **"El turismo que echa raíces."**

---

## Slide 1 — Portada
**RAÍZ**
*El turismo que echa raíces.*

- Pagos turísticos sobre **Stellar** · 2% al fondo comunitario · gobernado **on-chain** por los residentes.
- 🌐 `raizapp.xyz` · 📱 App Android nativa · ⛓️ 4 contratos Soroban en testnet.
- 🎨 Visual: logo RAÍZ (árbol) sobre `#FAFAF7`, foto de un barrio colorido (Cartagena/Bogotá). Equipo + hackathon.

---

## Slide 2 — El problema
**El barrio crea el valor. No lo captura.**

- El turismo **extrae**: plataformas y cadenas se llevan el margen.
- Residentes, artesanos y comercio de esquina ven una fracción mínima — y **cero voz** sobre cómo se reinvierte.
- Resultado: gentrificación, barrios vaciados de su gente, opacidad total.
- 🎨 Visual: flecha de dinero **saliendo** del barrio.

---

## Slide 3 — La solución
**2% de cada pago vuelve al barrio. Y el barrio decide.**

- **Tip Barrio**: 2% automático de cada pago → fondo comunitario (USDC, on-chain).
- **Gobernanza soulbound**: 1 residente = 1 voto, intransferible, no se compra.
- **Trustless + transparente**: ejecución sin que nadie tenga la llave + cada peso verificable en la cadena.
- 🎨 Visual: el mismo barrio, ahora con el dinero **circulando dentro**.

---

## Slide 4 — Cómo funciona (el flujo)
**Turista → Comercio → Barrio → Decisión. Todo on-chain.**

```
Turista paga 10 USDC + 2% Tip Barrio (opcional)
   ├─ 10 USDC → Comercio (cobra al instante)
   └─ 0.20    → Pool del Barrio (Tip Barrio)
        ↓
Residentes proponen y votan (token soulbound)
        ↓
Treasury ejecuta trustless si pasa quórum (30%) + mayoría
        ↓
Cada pago, voto y ejecución → evento on-chain público
```
- 🎨 Visual: diagrama de flujo horizontal con 4 íconos.

---

## Slide 5 — El fondo del barrio NO duerme: RINDE
**Mientras el barrio decide, su fondo crece.**

- El Tip Barrio acumulado se deposita en un **vault de DeFindex** → genera **rendimiento (yield)** automáticamente.
- El fondo comunitario no es una alcancía estática: **trabaja** mientras se vota en qué invertirlo.
- Composabilidad DeFi real: RAÍZ se para sobre el ecosistema Stellar, no lo reinventa.
- 🎨 Visual: el pool del barrio con una flecha de crecimiento + "TVL / APY" de la pantalla Tesorería.

---

## Slide 6 — Onboarding sin fricción: PASSKEY
**Una wallet en 10 segundos. Sin frase semilla. Con tu huella.**

- **Smart wallets con passkey (WebAuthn)**: el usuario crea su cuenta con biometría — **cero seed phrase** que perder.
- **Fees patrocinados** por un relayer → el turista **no necesita XLM** para empezar.
- **1 transacción por compra** (vs. 4 de una wallet clásica: crear cuenta + trustline + faucet + pago).
- **Recuperable en cualquier dispositivo** con la misma passkey (sincronizada). La cuenta vive en la cadena, no en el teléfono.
- 🎨 Visual: huella → wallet `C…` creada. Contraste "4 pasos vs 1 toque".

---

## Slide 7 — Demo (transición)
**Esto NO es un mockup. Corre en testnet ahora mismo.**

- 4 contratos desplegados · 3 barrios · comercios reales en el mapa.
- *(Cambiar a la app en vivo — ver `DEMO.md`)*
- 🎨 Visual: pantallazo de la WalletScreen (saldo USDC + RAÍZ Passport con sellos) a pantalla completa.

---

## Slide 8 — Los 3 roles
**Una app, tres formas de participar — cada una verificada on-chain.**

- 🧳 **Turista**: paga, deja Tip Barrio (opcional), gana puntos y **sellos** de barrios visitados.
- 🏠 **Residente**: token soulbound de su barrio → propone y vota **solo en su barrio**.
- 🏪 **Comercio**: cobra en USDC, **fija su local tocando el mapa**, aparece para los turistas.
- 🎨 Visual: tres tarjetas con Pay / Proposals / Cobros.

---

## Slide 9 — Arquitectura técnica
**4 contratos Soroban + Android nativo + smart accounts.**

| Contrato | Función |
|---|---|
| **Pool** | Pagos + custodia del Tip Barrio + integración DeFindex |
| **Governance** | Token soulbound de residencia + votación por barrio |
| **Treasury** | Ejecución trustless de propuestas aprobadas |
| **Rewards** | Puntos no transferibles + catálogo de premios |

- App: **Kotlin + Jetpack Compose** · SDK Soneso · **Mapbox** · **smart wallets passkey** + fallback semilla.
- 🎨 Visual: diagrama de los 4 contratos + la app + DeFindex, conectando a Stellar.

---

## Slide 10 — Por qué Stellar
**La única cadena donde un "tip de centavos" tiene sentido.**

- **Fees ~0**: el Tip Barrio no se lo come el gas.
- **USDC nativo**: turista y comercio piensan en dólares, no en un token volátil.
- **Soroban**: gobernanza segura y auditable en Rust + **account abstraction** (passkey).
- **Ecosistema componible**: DeFindex para yield, anchors SEP-24/38 para la rampa fiat↔cripto.
- 🎨 Visual: logo Stellar + íconos de ventaja.

---

## Slide 11 — Transparencia: todo es verificable
**Cada pago, cada voto, cada ejecución — pública en la cadena.**

- El contrato **emite un evento on-chain** por cada acción: `payment`, `vote`, `proposal`, `tally`, `execution`.
- En la app, **cada operación enlaza a Stellar Expert** con un toque (tx, cuenta, contrato).
- Herramienta web `/verificar-residente`: **cualquiera comprueba on-chain** a qué barrio pertenece un residente y en qué puede votar.
- El barrio audita su fondo **sin confiar en nadie** — ni en nosotros.
- 🎨 Visual: pantallazo de una tx de voto en Stellar Expert + la página verificar-residente.

---

## Slide 12 — Tracción
**Lo que YA corre (no es promesa, es código en producción).**

- ✅ 4 contratos desplegados + poblados en testnet (con yield DeFindex real por barrio).
- ✅ **Passkey operativo end-to-end**: crear wallet, pagar, votar y proponer **con la huella**, firmando contra los contratos.
- ✅ Verificado on-chain en dispositivo: pago+Tip, puntos, **sellos de barrio**, voto, ejecución, alta de comercio con ubicación, registro de residente.
- ✅ Landing pública (`raizapp.xyz`) + APK descargable + verificación on-chain en la web.
- 🎨 Visual: checklist verde + collage de pantallas (passkey, mapa, passport, tesorería).

---

## Slide 13 — Roadmap y visión
**De MVP a red de barrios autogobernados.**

- **Ahora**: MVP funcional en testnet — pagos, gobernanza, yield y passkey, todo on-chain.
- **Próximo**: KYC de residencia con documentos (SEP-12), backend de confianza, premios en IPFS.
- **Después**: piloto en un barrio real de Cartagena con un **anchor local** (rampa fiat).
- **Visión**: una red de barrios que capturan y gobiernan el valor del turismo que reciben — replicable en cualquier ciudad del mundo.
- 🎨 Visual: línea de tiempo en 4 hitos + un mapa con barrios encendiéndose.

---

## Slide 14 — Cierre
**El turismo siempre llega. RAÍZ hace que se quede.**

- **2% de cada pago · gobernado por el barrio · verificable en la cadena.**
- Pago + gobernanza + yield + transparencia, en una app, sobre Stellar.
- 🎨 Visual: logo RAÍZ + `raizapp.xyz` + QR del repo. *"Gracias — y el barrio agradece."*

---

### Notas para quien presenta
- **El gancho de hoy**: arranca por la demo de **passkey** (huella → wallet en segundos) — es lo que hace decir "wow".
- Ten la app **ya abierta** con saldo antes de empezar; no hagas onboarding en vivo salvo que sea el highlight de passkey.
- Plan B si falla la red: la página **`/verificar-residente`** y **Stellar Expert** muestran estado on-chain real sin depender de escribir.
- Frase ancla: *"2% de cada pago, gobernado por el barrio, verificable en la cadena — y la wallet se crea con tu huella."*
