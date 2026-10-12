# Mínimo — hoja de ruta de producto

**Estado de versión:** main contiene la 1.5; la rama prepara la 1.6, aún pendiente de pruebas, revisión e instalación · 33 comercios registrados · 0 conectores de precios reales · autenticación online no configurada · alertas automáticas no implementadas.

**Progreso global provisional: 1%** se mantiene como base hasta completar el primer flujo de precio real de extremo a extremo. Ya se han mejorado las migraciones y el catálogo, pero aún no hay ningún proveedor conectado. Lo actualizaremos cuando se cierre ese hito funcional, no por líneas de código escritas.

## 15 mejoras planificadas

- [x] **M01 — Base de integridad y procedencia.** Existe una tabla persistente de observaciones con producto, comercio, precio, moneda, fecha/hora, URL y estado de verificación; queda pendiente alimentarla con un proveedor real.
- [ ] **M02 — Primer proveedor real.** Evaluar Keepa (coste, presupuesto de tokens, términos y uso en app Android) y conectarlo solo tras aprobación.
- [ ] **M03 — Más proveedores autorizados.** Integrar fuentes oficiales o feeds con permiso explícito por comercio; no confundir una lista de webs con conectores.
- [ ] **M04 — Identidad de producto.** Normalizar EAN/GTIN, MPN/SKU, variante, capacidad, color y estado nuevo/reacondicionado.
- [ ] **M05 — Ingesta fiable.** Servicio seguro, programación, caché, límites, reintentos, presupuesto y registros de errores.
- [~] **M06 — Historial y mínimos reales (reglas reforzadas).** El código exige cobertura de 90 días y varias observaciones verificadas para etiquetar mínimos/descuentos; falta la fuente real que alimente ese historial.
- [ ] **M07 — Comparación de coste final.** Incluir envío, disponibilidad, vendedor, IVA, condición y fecha de comprobación.
- [ ] **M08 — Actualizaciones de fondo.** Programar consultas respetando batería, conectividad y condiciones de los proveedores.
- [ ] **M09 — Alertas reales.** Activar avisos solo con monitorización funcional, permisos y preferencias claras del usuario.
- [ ] **M10 — Cuenta online.** Autenticación segura, sincronización y recuperación; el perfil local actual no es una cuenta.
- [~] **M11 — Catálogo escalable (parcial).** Room es la fuente de verdad y las instalaciones existentes añaden solo los IDs que faltan sin sobrescribir filas. Quedan pendientes paginación y filtros escalables.
- [~] **M12 — Ordenación comprobable (parcial).** Ya hay orden por nombre/categoría y por precio verificado; falta ampliar por comercio y frescura del dato.
- [ ] **M13 — Seguimiento y objetivos.** Favoritos, objetivos de precio, exportación e historial por usuario.
- [~] **M14 — Interfaz moderna y accesible (parcial).** Se explican los precios ausentes, la búsqueda abre Google de forma explícita y las fichas sin historial/ofertas muestran estados vacíos informativos.
- [~] **M15 — Calidad y publicación (parcial).** Se añaden pruebas para migraciones, metadatos separados e historial mínimo; quedan auditoría final, observabilidad y pruebas end-to-end del proveedor real.

## 5 fallos o limitaciones detectados en el código actual

1. **No hay proveedor de precios en directo activo.** El catálogo precargado es de demostración.
2. **[RESUELTO] Dos fuentes de verdad para productos.** El flujo principal ahora lee los productos desde Room; el catálogo estático solo sirve para añadir fichas que faltan.
3. **[PARCIALMENTE RESUELTO] Historial persistente con procedencia.** Ya existe una tabla/DAO con URL, fecha y verificación; sigue sin datos reales hasta conectar un proveedor autorizado.
4. **[RESUELTO] Riesgo de pérdida por migración destructiva.** La migración destructiva se retiró y el camino 2→3→4 ha superado pruebas de preservación.
5. **[LIMITACIÓN CONOCIDA] El seguimiento no monitoriza.** Los objetivos se guardan localmente, pero no hay comprobaciones automáticas ni alertas reales; la simulación de caída es local y se etiqueta como demo.
6. **[CORRECCIÓN EN ESTA RAMA] Metadatos mezclados con precio.** Un precio verificado podía hacer que PVP, valoración o EAN precargados parecieran verificados; ahora cada campo tiene su propio indicador.
7. **[CORRECCIÓN EN ESTA RAMA] Historial demasiado corto.** Una sola lectura podía parecer un mínimo; ahora se exigen varias observaciones repartidas a lo largo de 90 días.

## 3 correcciones prioritarias

- [x] **C01 — Preservar datos del usuario.** Migraciones explícitas 2→3→4→5; se ha retirado la migración destructiva y las pruebas confirman que los seguimientos/productos se conservan.
- [~] **C02 — Persistir observaciones verificables (base completada).** La entidad, el DAO y el historial filtrado por verificación existen; falta conectar un proveedor real y registrar consultas auténticas.
- [~] **C03 — Bloquear falsas ofertas (parcial).** El modelo exige 90 días de observaciones verificadas para emitir diagnósticos históricos; PVP, valoraciones y EAN requieren verificación independiente. Las alertas reales esperan a la monitorización.

## Registro de progreso

| Hito | Estado |
|---|---|
| APK Android instalable y rama principal privada | Completado |
| Catálogo y directorio de comercios ampliados | Completado como datos de descubrimiento; catálogo ahora sale de Room, pero no son precios en directo |
| Ajustes y perfil local | Completado; sin autenticación online |
| C01: migración no destructiva | Completado y probado |
| C02: esquema y DAO de observaciones | Completado; falta el proveedor real |
| Conectores de precios reales | Pendiente |
| Metadatos PVP/valoración/EAN independientes | En pruebas en la rama 1.6 |
| Historial y gráficos con rangos temporales reales | En pruebas en la rama 1.6 |
| Monitorización y alertas | Pendiente |

## Criterio de finalización

Cada mejora debe compilar, pasar sus pruebas pertinentes y mantener explícita la diferencia entre datos de demostración y precios reales. Ninguna clave de proveedor se incluirá en el APK ni en el repositorio.
