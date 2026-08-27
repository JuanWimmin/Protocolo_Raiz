---
description: Audita drift entre el código actual y la spec (docs/raiz_v2_spec_contratos.md + RaizModels.kt)
---

Lanza el subagente `spec-auditor` con la instrucción de comparar el código actual del workspace de contratos y los modelos Kotlin contra las dos fuentes de verdad:

- `docs/raiz_v2_spec_contratos.md`
- `docs/RaizModels.kt`

El agente debe revisar:

1. Cada uno de los 5 contratos (`contracts/{pool,governance,treasury,rewards,yield_adapter}/src/lib.rs`).
2. Si existe ya `android/app/src/main/java/com/raiz/app/data/model/`, audita esos modelos también.

Devuelve un reporte estructurado con:
- ✅ Coherente
- ⚠️ Drift detectado (con archivo:línea)
- ❌ No implementado aún
- 🟡 Existe en código pero no en spec

El agente NO debe modificar archivos. Solo reportar.
