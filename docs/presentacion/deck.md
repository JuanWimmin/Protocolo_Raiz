# RAÍZ — Deck de slides (hackathon, 7-10 min)

> 12 slides. Cada una: título + bullets + nota visual. Listo para pasar a Canva/PPT.
> Paleta RAÍZ: negro `#1a1a1a`, amarillo `#FBBF24`, púrpura `#534AB7`, verde `#0F6E56`, fondo `#FAFAF7`.

---

## Slide 1 — Portada
**RAÍZ**
*El turismo que echa raíz en el barrio.*

- Pagos turísticos sobre Stellar · 2% al fondo comunitario · gobernado on-chain.
- 🎨 Visual: logo RAÍZ sobre fondo `#FAFAF7`, una foto de un barrio colorido (Cartagena/Bogotá). Nombre del equipo + hackathon.

---

## Slide 2 — El problema
**El barrio crea el valor. No lo captura.**

- El turismo extrae: plataformas y cadenas se llevan el margen.
- Residentes, artesanos y comercio de esquina ven una fracción mínima.
- Cero voz sobre cómo se reinvierte → gentrificación, opacidad.
- 🎨 Visual: flecha de dinero **saliendo** del barrio. Contraste con la siguiente slide.

---

## Slide 3 — La solución
**RAÍZ: 2% de cada pago vuelve al barrio, y el barrio decide.**

- **Tip Barrio**: 2% automático de cada pago → fondo comunitario.
- **Gobernanza soulbound**: 1 residente = 1 voto, no se compra.
- **Trustless + transparente**: ejecución sin llave + dashboard público.
- 🎨 Visual: el mismo barrio, ahora con la flecha de dinero **circulando dentro**.

---

## Slide 4 — Cómo funciona (el flujo)
**Turista → Comercio → Barrio → Decisión, todo on-chain.**

```
Turista paga en USDC
   ├─ 97.5% → Comercio (cobra al instante)
   ├─  2%   → Pool del Barrio (Tip Barrio)
   └─  0.5% → Fee del protocolo
        ↓
Residentes proponen y votan (soulbound)
        ↓
Treasury ejecuta trustless si pasa quórum (30%) + mayoría
        ↓
Dashboard público muestra cada peso
```
- 🎨 Visual: diagrama de flujo horizontal con los 4 íconos.

---

## Slide 5 — Demo (transición)
**Esto no es un mockup. Está corriendo en testnet.**

- 4 contratos desplegados · 3 barrios · 9 comercios.
- *(Cambiar a la app en vivo — ver `DEMO.md`)*
- 🎨 Visual: pantallazo de la WalletScreen (saldo 20 USDC + RAÍZ Passport) a pantalla completa.

---

## Slide 6 — Los 3 roles
**Una app, tres formas de participar.**

- 🧳 **Turista**: paga, deja Tip Barrio, gana puntos canjeables.
- 🏠 **Residente**: propone y vota en qué se invierte el fondo.
- 🏪 **Comercio**: cobra en USDC, aparece en el mapa del barrio.
- 🎨 Visual: tres tarjetas con las pantallas Pay / Proposals / Merchant.

---

## Slide 7 — Arquitectura técnica
**4 contratos Soroban + Android nativo + Stellar SDK.**

| Contrato | Función |
|---|---|
| Pool | Pagos + custodia del Tip Barrio |
| Governance | Soulbound de residencia + votación |
| Treasury | Ejecución trustless |
| Rewards | Puntos + premios |

- App: Kotlin + Jetpack Compose · SDK Soneso · Mapbox · wallet passkey/seed.
- 🎨 Visual: diagrama de los 4 contratos + la app conectando a Stellar.

---

## Slide 8 — Por qué Stellar
**La única cadena donde un "tip de centavos" tiene sentido.**

- **Fees ~0**: el Tip Barrio no se lo come el gas.
- **USDC nativo**: turista y comercio piensan en dólares.
- **Soroban**: gobernanza segura y auditable en Rust.
- **Anchors (SEP-24/38)**: rampa fiat↔cripto real — entra con tarjeta, sale al banco.
- 🎨 Visual: logo Stellar + los 4 íconos de ventaja.

---

## Slide 9 — Transparencia
**Cada pago, cada voto, cada ejecución: verificable.**

- Dashboard lee el estado directo de la cadena (no una base de datos nuestra).
- Eventos on-chain: `payment`, `execution`, `redemption`.
- El barrio puede auditar a dónde fue su fondo, sin confiar en nadie.
- 🎨 Visual: pantallazo del DashboardScreen.

---

## Slide 10 — Tracción
**Lo que ya está hecho.**

- ✅ 4 contratos desplegados + poblados en testnet.
- ✅ 6 pantallas Android nativas.
- ✅ Verificado E2E on-chain: pago+tip, puntos, voto, ejecución, alta de comercio, onboarding de wallet con rampa USDC.
- 🎨 Visual: checklist verde + collage de pantallas.

---

## Slide 11 — Roadmap
**De MVP a barrio real.**

- **Ahora**: MVP funcional en testnet.
- **Próximo**: KYC de residencia (SEP-12), wallets passkey sin seed.
- **Después**: premios en IPFS, piloto en un barrio de Cartagena con anchor local.
- **Visión**: red de barrios autogobernados por el turismo que reciben.
- 🎨 Visual: línea de tiempo en 4 hitos.

---

## Slide 12 — Cierre
**El turismo siempre llega. RAÍZ hace que se quede.**

- 2% de cada pago · gobernado por el barrio · verificable en la cadena.
- *Pago + gobernanza + transparencia, en una app, sobre Stellar.*
- 🎨 Visual: logo RAÍZ + contacto/QR del repo. *"Gracias — y el barrio agradece."*
