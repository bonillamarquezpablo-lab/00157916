# Mínimo — hoja de ruta de producto

**Estado inicial:** versión instalada 1.2 · 33 comercios en directorio · catálogo de demostración · 0 conectores de precios reales · autenticación online no configurada · alertas automáticas no implementadas.

**Progreso global provisional: 1%**, según la estimación inicial del proyecto. Lo actualizaremos únicamente al cerrar hitos verificables, no por líneas de código escritas.

## 15 mejoras planificadas

- [ ] **M01 — Integridad de datos y procedencia.** Guardar cada observación con producto, comercio, precio, moneda, fecha/hora, URL y verificación. **En curso.**
- [ ] **M02 — Primer proveedor real.** Evaluar Keepa (coste, presupuesto de tokens, términos y uso en app Android) y conectarlo solo tras aprobación.
- [ ] **M03 — Más proveedores autorizados.** Integrar fuentes oficiales o feeds con permiso explícito por comercio; no confundir una lista de webs con conectores.
- [ ] **M04 — Identidad de producto.** Normalizar EAN/GTIN, MPN/SKU, variante, capacidad, color y estado nuevo/reacondicionado.
- [ ] **M05 — Ingesta fiable.** Servicio seguro, programación, caché, límites, reintentos, presupuesto y registros de errores.
- [ ] **M06 — Historial y mínimos reales.** Calcular mínimos y descuentos solo desde observaciones verificadas y fechadas.
- [ ] **M07 — Comparación de coste final.** Incluir envío, disponibilidad, vendedor, IVA, condición y fecha de comprobación.
- [ ] **M08 — Actualizaciones de fondo.** Programar consultas respetando batería, conectividad y condiciones de los proveedores.
- [ ] **M09 — Alertas reales.** Activar avisos solo con monitorización funcional, permisos y preferencias claras del usuario.
- [ ] **M10 — Cuenta online.** Autenticación segura, sincronización y recuperación; el perfil local actual no es una cuenta.
- [ ] **M11 — Catálogo escalable.** Búsqueda, paginación, filtros y actualización incremental sin duplicados.
- [ ] **M12 — Ordenación comprobable.** Precio verificado, nombre, categoría, comercio y frescura de la observación.
- [ ] **M13 — Seguimiento y objetivos.** Favoritos, objetivos de precio, exportación e historial por usuario.
- [ ] **M14 — Interfaz moderna y accesible.** Cargas, errores, estados vacíos, diseño adaptable y accesibilidad.
- [ ] **M15 — Calidad y publicación.** Pruebas de integración, privacidad, seguridad, observabilidad, copias y flujo de publicación.

## 5 fallos o limitaciones detectados en el código actual

1. **No hay proveedor de precios en directo activo.** El catálogo precargado es de demostración.
2. **Dos fuentes de verdad para productos.** Se guardan productos semilla en Room, pero el flujo que presenta el catálogo procede de listas en memoria; el DAO de productos no alimenta el listado principal.
3. **No existe historial persistente con procedencia.** El modelo local de producto no almacena observaciones de precio con URL y marca temporal.
4. **Riesgo de pérdida de datos.** La configuración de Room usa una migración destructiva si falta una migración compatible.
5. **El seguimiento no monitoriza.** Los objetivos se guardan localmente, pero no hay comprobaciones automáticas ni alertas reales; la simulación de caída modifica objetos de demostración.

## 3 correcciones prioritarias

- [ ] **C01 — Preservar datos del usuario.** Retirar la migración destructiva y añadir migraciones explícitas. **En curso.**
- [ ] **C02 — Persistir observaciones verificables.** Nueva tabla con precio, moneda, producto, comercio, fecha/hora, URL de fuente y estado de verificación. **En curso.**
- [ ] **C03 — Bloquear falsas ofertas.** Mínimos, descuentos y alertas solo podrán derivarse de observaciones válidas y verificadas.

## Registro de progreso

| Hito | Estado |
|---|---|
| APK Android instalable y rama principal privada | Completado |
| Catálogo y directorio de comercios ampliados | Completado como datos de descubrimiento, no como precios en directo |
| Ajustes y perfil local | Completado; sin autenticación online |
| C01: migración no destructiva | En curso |
| C02: tabla de observaciones | En curso |
| Conectores de precios reales | Pendiente |
| Monitorización y alertas | Pendiente |

## Criterio de finalización

Cada mejora debe compilar, pasar sus pruebas pertinentes y mantener explícita la diferencia entre datos de demostración y precios reales. Ninguna clave de proveedor se incluirá en el APK ni en el repositorio.
