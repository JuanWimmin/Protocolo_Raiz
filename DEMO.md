# DEMO.md — Guion de demo en vivo

> Núcleo de **90 segundos** (negrita) + extensiones opcionales para llegar a 3-4 min
> en el pitch de hackathon. Todo corre contra los 5 contratos en **testnet**.
> Guion completo de presentación: `docs/presentacion/pitch.md`.

---

## Antes de empezar (checklist)

- [ ] Teléfono con la app instalada y **wallet con saldo ≥ 20 USDC** ya activa (no hacer onboarding en vivo).
- [ ] Conexión estable. Probado en Motorola G04 / Android 14 (ver gotcha TLS en `CLAUDE.md`).
- [ ] Mirroring de pantalla al proyector (scrcpy / cable).
- [ ] App abierta en **Inicio** (WalletScreen), mostrando el saldo.
- [ ] Tener listo el QR de un comercio para escanear (segundo teléfono o impreso).
- [ ] Modo demo activo: el cambio de rol (turista/residente/comercio) está en **Perfil → Mi rol**.
- [ ] APK **debug** compilado con `raiz.tourist.secret` en `local.properties` (el release no tiene modo demo).
- [ ] Relayer vivo: `curl -s $RELAYER/v1/health` → `ok:true`, `faucet.enabled:true`, `vaultEndpoints:true` (`RELAYER` = URL del relayer; default `https://raiz-relayer.fly.dev`).
- [ ] (Solo si se enseña el **Depósito SEP-24**) anchor de prueba vivo: `curl -s https://testanchor.stellar.org/sep24/info` → `deposit.USDC.enabled:true`; wallet **semilla** con XLM y Chrome instalado.

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
- Solo si tienes tiempo y red estable: banner de 3 pasos (XLM → trustline → "Consigue USDC": depósito con el anchor de prueba o faucet demo de 20 USDC).

**+ Depósito SEP-24 (anchor de prueba).**
> *"Y el USDC de prueba ya no me lo regala nadie: lo deposito con un anchor real, por los estándares de Stellar."*
- Solo con wallet **semilla** (passkey muestra "Disponible pronto para passkey (SEP-45)") y red estable.
- **Inicio → "Depositar · anchor de prueba"** → monto **5** → **"Depositar con el anchor"**. La app crea la trustline, se autentica (SEP-10, firma con tu wallet) y abre la web del anchor en una pestaña → confirma el depósito → vuelve a la app → **"¡Depósito recibido!"** con el hash y el chip **"Ver en Stellar Expert"**.
- En **Inicio** aparece "● USDC · anchor de prueba: 5 USDC", aparte del USDC del fondo (es otro activo: no sirve para pagar comercios hasta convertirlo, y se dice).
- **Tap "Convertir"** (extra): vuelve a **"Depositar · anchor de prueba"** → bajo el saldo, card **"Convertir a USDC del fondo"** con "Recibirás ≈ N USDC" → botón púrpura **"Convertir…"** → "Enviando la conversión…". De vuelta en **Inicio**, el saldo USDC subió ≈ N y la línea del anchor desapareció: con eso ya puedes **"Escanear y pagar"**.
  > *"En testnet el anchor emite otro USDC, así que lo convierto con un path payment que firmo yo, de mi cuenta a mi cuenta. En mainnet este paso no existe: el USDC de Circle es uno solo."*
  - N es la cotización en vivo de un pool de liquidez de testnet (para 5 USDC: 4,44 el 27-sep, 5,05 el 3-oct). Si conviertes desde la pantalla "¡Depósito recibido!" (la card está bajo el chip del hash), quedan a la vista "✓ Convertido: recibiste N USDC del fondo" y el chip a Stellar Expert.
- Tarda 1–2 min con el anchor de prueba. Guion de 60 s y plan B detallado: `docs/evidencia_sow/d3/guion_video.md`.

---

## Plan B si algo falla

| Falla | Qué hacer |
|---|---|
| No escanea el QR | Usa el QR impreso de respaldo, o salta al paso del mapa. |
| Pago lento / timeout | Narra el Tip Barrio sobre el desglose ya visible; no esperes confirmación en vivo. |
| Sin red | Abre el **Dashboard de transparencia**: muestra estado on-chain ya cargado. Pivota a la slide de arquitectura. |
| Onboarding falla | Sáltalo — es opcional. La wallet principal ya está lista. |
| Relayer caído | Salta **Alta de comercio** y **Onboarding** (son los que pasan por el relayer); pagos, votos y transparencia no dependen del relayer. |
| Anchor de prueba caído o lento (> 1 min en "Esperando al anchor…") | Salta el **Depósito SEP-24**; el on-ramp demo sigue siendo el faucet del relayer ("USDC demo (Blend) · relayer" en el paso 3 del banner). Nada más depende del anchor. |
| "Convertir" falla ("No hay liquidez en testnet…" / "La liquidez cambió: vuelve a cotizar.") o cotiza muy lejos de 5 | **"Reintentar"** vuelve a cotizar. Si sigue igual, salta el tap: el depósito ya quedó demostrado y el pago se enseña con el saldo USDC que la wallet ya tenía. |

---

## Datos reales para citar (testnet)

- **Tip Barrio**: 2% (`tip_bps = 200`). **Fee protocolo**: 0.5% (`protocol_fee_bps = 50`).
- **Puntos**: 1 punto por cada 0.01 USDC de tip.
- **Quórum**: 30% de residentes · **mayoría simple** · propuestas de 3-14 días.
- **Barrios sembrados**: Centro Histórico (Cartagena), Barrio Norte (Bogotá), Costa Vieja (Cartagena) — 9 comercios.
- **Soulbound**: el token de residencia **no** tiene `transfer()`. 1 residente = 1 voto.
- Ejemplo verificado on-chain: comercio "SalsonBacano" registrado en Barrio Norte vía la app.
