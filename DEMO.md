# DEMO.md — Guion de demo en vivo

> Núcleo de **90 segundos** (negrita) + extensiones opcionales para llegar a 3-4 min
> en el pitch de hackathon. Todo corre contra los 4 contratos en **testnet**.
> Guion completo de presentación: `docs/presentacion/pitch.md`.

---

## Antes de empezar (checklist)

- [ ] Teléfono con la app instalada y **wallet con saldo ≥ 20 USDC** ya activa (no hacer onboarding en vivo).
- [ ] Conexión estable. Probado en Motorola G04 / Android 14 (ver gotcha TLS en `CLAUDE.md`).
- [ ] Mirroring de pantalla al proyector (scrcpy / cable).
- [ ] App abierta en **Inicio** (WalletScreen), mostrando el saldo.
- [ ] Tener listo el QR de un comercio para escanear (segundo teléfono o impreso).
- [ ] Modo demo activo: el cambio de rol (turista/residente/comercio) está en **Perfil → Mi rol**.

---

## Guion de 90 segundos (núcleo)

**0:00 — Soy turista, llego al barrio.**
> *"Esta es mi wallet. Tengo 20 dólares digitales y estoy en el Centro Histórico."*
- Pantalla **Inicio**: señala el saldo **20 USDC** y arriba **"Pool del barrio · Centro: 0.4 USDC"**.

**0:15 — Pago a un comercio.**
> *"Pago un café escaneando su QR."*
- Tap **"Escanear y pagar"** → escanea el QR → confirma el monto.
- Señala el desglose: el comercio recibe el monto, **el 2% es el Tip Barrio**.

**0:35 — El barrio acaba de recibir.**
> *"Ese 2% se fue solo al fondo del barrio. Y yo gané puntos."*
- Vuelve a **Inicio**: el **Pool del barrio subió** y **Puntos / Aporte al barrio** se actualizan.

**0:50 — El barrio existe, es un mapa real.**
> *"Estos son los comercios reales de la zona."*
- Tap pestaña **Mapa**: pines de los comercios sobre Mapbox.

**1:05 — Ahora soy residente y decido.**
> *"Quien vive aquí vota en qué se gasta el fondo. Un residente, un voto — no se compra."*
- **Perfil → Mi rol →** cambia a **Residente** → **vota** una propuesta (ej. "arreglar la plaza").

**1:20 — Todo es verificable.**
> *"Cada pago, cada voto, cada ejecución — leído directo de la cadena."*
- En **Inicio**, tap **"Ver transparencia →"** (junto al Pool del barrio): muestra los eventos on-chain.

**1:30 — Cierre.**
> *"Pago, gobernanza y transparencia, en una app, sobre Stellar. Nada de esto es mockup."*

---

## Extensiones opcionales (para 3-4 min)

**+ Canje de puntos (Premios).**
> *"Mis puntos los canjeo por artesanía local."*
- Pestaña **Premios** → elige un premio → **canje firmado on-chain** (`Rewards.redeem`).

**+ Alta de comercio (become merchant).**
> *"Y cualquiera registra su negocio en 20 segundos."*
- **Perfil → Mi rol →** (como turista) **"Registrarme como comerciante"** → nombre + categoría + barrio → **Registrar**.
- Tras éxito, el rol pasa a **Comercio** y el negocio aparece en el mapa. *(Escribe `register_merchant` on-chain de verdad.)*

**+ Onboarding de wallet nueva.**
> *"Una wallet recién creada se activa en 3 pasos: la fondeamos, habilitamos USDC y le mandamos saldo de prueba."*
- Solo si tienes tiempo y red estable: banner de 3 pasos (XLM → trustline → faucet 20 USDC).

---

## Plan B si algo falla

| Falla | Qué hacer |
|---|---|
| No escanea el QR | Usa el QR impreso de respaldo, o salta al paso del mapa. |
| Pago lento / timeout | Narra el Tip Barrio sobre el desglose ya visible; no esperes confirmación en vivo. |
| Sin red | Abre el **Dashboard de transparencia**: muestra estado on-chain ya cargado. Pivota a la slide de arquitectura. |
| Onboarding falla | Sáltalo — es opcional. La wallet principal ya está lista. |

---

## Datos reales para citar (testnet)

- **Tip Barrio**: 2% (`tip_bps = 200`). **Fee protocolo**: 0.5% (`protocol_fee_bps = 50`).
- **Puntos**: 1 punto por cada 0.01 USDC de tip.
- **Quórum**: 30% de residentes · **mayoría simple** · propuestas de 3-14 días.
- **Barrios sembrados**: Centro Histórico (Cartagena), Barrio Norte (Bogotá), Costa Vieja (Cartagena) — 9 comercios.
- **Soulbound**: el token de residencia **no** tiene `transfer()`. 1 residente = 1 voto.
- Ejemplo verificado on-chain: comercio "SalsonBacano" registrado en Barrio Norte vía la app.
