# RAÍZ — Pitch hablado (hackathon, 7-10 min)

> Red de pagos turísticos sobre Stellar que devuelve valor al barrio que lo genera.
> Todo on-chain. App Android nativa + 4 contratos Soroban en testnet.

**Estructura:** Gancho (1') → Solución (1.5') → Demo en vivo (3-4') → Cómo funciona + por qué Stellar (1.5') → Tracción y visión (1') → Cierre (30").

---

## 1 · Gancho — el problema (≈1 min)

> *"Cuando viajas a un barrio con encanto —el Centro Histórico de Cartagena, un barrio bohemio de Bogotá— pagas por esa magia. Pero ¿a dónde va tu dinero?"*

- El turismo **extrae** valor de los barrios: la plusvalía, las plataformas y las cadenas se llevan el margen.
- Quien **crea** el atractivo —los residentes, los artesanos, el comercio de esquina— captura una fracción mínima y **no decide nada** sobre cómo se reinvierte.
- El resultado: gentrificación, barrios vaciados de su gente, cero transparencia sobre a dónde fue el dinero.

> *"El turismo mueve billones, pero el barrio que lo hace posible casi no ve un peso. Eso es lo que arreglamos."*

---

## 2 · La solución — RAÍZ (≈1.5 min)

> *"RAÍZ es una red de pagos turísticos donde un 2% de cada pago —el Tip Barrio— va automáticamente a un fondo comunitario que gobiernan los propios residentes. Todo on-chain, sobre Stellar."*

Tres ideas clave:

1. **Tip Barrio automático.** El turista paga al comercio en USDC; el contrato desvía un 2% (configurable) al **pool del barrio**. Sin intermediarios, sin confianza ciega.
2. **Gobernanza de residentes (soulbound).** Cada residente tiene un token de residencia **intransferible** = 1 persona, 1 voto. Proponen y votan en qué se invierte el fondo: arreglar una plaza, becas, un mural. *No se puede comprar el voto.*
3. **Ejecución trustless + transparencia total.** Si una propuesta pasa el quórum (30%) y la mayoría, el **treasury la ejecuta sin que nadie tenga la llave**. Y todo —cada pago, cada voto, cada ejecución— se ve en un dashboard público on-chain.

> *"El turista gana puntos canjeables por artesanías locales. El comercio cobra al instante en dólar digital. El barrio acumula y decide. Todos ganan, y la blockchain lo hace verificable."*

---

## 3 · Demo en vivo (≈3-4 min)

> Cambia a la app (ver `DEMO.md` para el guion exacto, paso a paso). Narrativa mientras tocas:

1. **Soy turista.** Tengo 20 USDC. Escaneo el QR de un café del barrio y pago. *(mostrar pago)*
2. **El Tip Barrio salta solo.** El 2% se fue al pool del Centro Histórico — pueden verlo arriba. Y gané puntos.
3. **El mapa.** Estos son los comercios reales del barrio, sobre Mapbox. *(abrir Mapa)*
4. **Soy residente.** Cambio de rol: aquí voto una propuesta para arreglar la plaza. *(votar)*
5. **Transparencia.** El dashboard muestra los pagos, los votos y las ejecuciones — todo leído directo de la cadena. *(abrir Dashboard)*
6. **Soy comercio.** Cualquiera registra su negocio en RAÍZ en 20 segundos y aparece en el mapa. *(become merchant)*

> *"Nada de esto es un mockup: la app está leyendo y escribiendo contra cuatro contratos desplegados en la testnet de Stellar ahora mismo."*

---

## 4 · Cómo funciona + por qué Stellar (≈1.5 min)

**Cuatro contratos Soroban (Rust), desplegados en testnet:**
    
| Contrato | Qué hace |
|---|---|
| **Pool** | Pagos turista→comercio + custodia del Tip Barrio |
| **Governance** | Tokens soulbound de residencia + votación |
| **Treasury** | Ejecución trustless de propuestas aprobadas |
| **Rewards** | Puntos no transferibles + catálogo de premios |

**Por qué Stellar y no otra cadena:**
- **Costo casi cero** — un Tip Barrio de centavos no tiene sentido si el fee se lo come. Stellar cuesta fracciones de centavo.
- **USDC nativo** — el turista y el comercio piensan en dólares, no en un token volátil.
- **Soroban** — contratos en Rust, seguros y auditables, para la lógica de gobernanza.
- **Anchors (SEP-24/38)** — rampa fiat↔cripto integrada: el turista entra con tarjeta, el comercio sale a su banco. *Esto es lo que lo hace usable en el mundo real.*

---

## 5 · Tracción y visión (≈1 min)

**Lo que ya está hecho (no es promesa, es código corriendo):**
- ✅ 4 contratos desplegados y poblados en testnet (3 barrios, 9 comercios).
- ✅ App Android nativa, 6 pantallas en producción visual.
- ✅ Flujos verificados **end-to-end on-chain**: pago con Tip Barrio, puntos, votación, ejecución de propuesta, alta de comercio, onboarding de wallets nuevas con rampa de USDC.

**Hacia dónde va:**
- **KYC de residencia real** (SEP-12) en vez del mint manual del MVP.
- **Wallets passkey** sin frase semilla — onboarding de 10 segundos.
- **Premios en IPFS** y catálogo descentralizado.
- **Piloto en un barrio real** de Cartagena, con un anchor local para la rampa fiat.

---

## 6 · Cierre (≈30 s)

> *"El turismo siempre va a llegar al barrio. La pregunta es si el barrio va a poder decidir qué hacer con ese dinero — o si seguirá viéndolo pasar de largo."*
>
> *"RAÍZ pone esa decisión on-chain, en manos de quien vive ahí. Es pago, es gobernanza y es transparencia, en una sola app, sobre Stellar."*
>
> **"Gracias — y el barrio agradece."**

---

### Notas para quien presenta
- Ten la app **ya abierta** en la wallet con 20 USDC antes de empezar; no hagas onboarding en vivo.
- Si la demo falla por red, el **dashboard de transparencia** es tu plan B: muestra estado on-chain real sin depender de escribir.
- Frase ancla si te quedas en blanco: *"2% de cada pago, gobernado por el barrio, verificable en la cadena."*
