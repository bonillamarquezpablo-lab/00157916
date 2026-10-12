# Mínimo — hoja de ruta de producto

**Estado del código en main:** integración de consulta manual de Google Shopping España mediante SerpApi añadida; todavía no se ha configurado una clave de proveedor en el móvil · 33 comercios en el directorio · autenticación online no configurada · actualización en segundo plano y alertas automáticas no implementadas.

**Progreso global provisional: 1%** se mantiene hasta completar el primer flujo de precio real de extremo a extremo en el móvil. El código del conector está integrado, pero falta la clave del proveedor y validar una búsqueda real; el porcentaje se actualiza por resultados funcionales, no por líneas de código.

## 15 mejoras planificadas

- [x] **M01 — Base de integridad y procedencia.** Existe una tabla persistente de observaciones con producto, comercio, precio, moneda, fecha/hora, URL y estado de verificación; queda pendiente alimentarla con un proveedor real.
- [~] **M02 — Primer proveedor real (código preparado).** La app incluye consultas manuales de Google Shopping España a través de SerpApi, con almacenamiento local de la clave cifrada. Falta crear/configurar la cuenta, guardar la clave en Ajustes y probar una consulta real. No es una actualización continua.
- [ ] **M03 — Más proveedores autorizados.** Integrar fuentes oficiales o feeds con permiso explícito por comercio; no confundir una lista de webs con conectores.
- [ ] **M04 — Identidad de producto.** Normalizar EAN/GTIN, MPN/SKU, variante, capacidad, color y estado nuevo/reacondicionado.
- [~] **M05 — Ingesta fiable (parcial).** Se valida la respuesta y se limita la actualización manual a 40 productos y 45 búsquedas por hora en el dispositivo. Falta backend compartido para una app multiusuario, gestión central de cuotas, registros y tareas programadas.
- [ ] **M06 — Historial y mínimos reales.** Calcular mínimos y descuentos solo desde observaciones verificadas y fechadas.
- [ ] **M07 — Comparación de coste final.** Incluir envío, disponibilidad, vendedor, IVA, condición y fecha de comprobación.
- [ ] **M08 — Actualizaciones de fondo.** Programar consultas respetando batería, conectividad y condiciones de los proveedores.
- [ ] **M09 — Alertas reales.** Activar avisos solo con monitorización funcional, permisos y preferencias claras del usuario.
- [ ] **M10 — Cuenta online.** Autenticación segura, sincronización y recuperación; el perfil local actual no es una cuenta.
- [~] **M11 — Catálogo escalable (parcial).** Room es la fuente de verdad y las instalaciones existentes añaden solo los IDs que faltan sin sobrescribir filas. Quedan pendientes paginación y filtros escalables.
- [~] **M12 — Ordenación comprobable (parcial).** Ya hay orden por nombre/categoría y por precio verificado; falta ampliar por comercio y frescura del dato.
- [ ] **M13 — Seguimiento y objetivos.** Favoritos, objetivos de precio, exportación e historial por usuario.
- [ ] **M14 — Interfaz moderna y accesible.** Cargas, errores, estados vacíos, diseño adaptable y accesibilidad.
- [ ] **M15 — Calidad y publicación.** Pruebas de integración, privacidad, seguridad, observabilidad, copias y flujo de publicación.

## 5 fallos o limitaciones detectados en el código actual

1. **Conector listo, proveedor sin configurar.** El catálogo precargado sigue siendo de demostración; la consulta de Google Shopping solo funciona tras guardar la clave del proveedor en el dispositivo.
2. **[RESUELTO] Dos fuentes de verdad para productos.** El flujo principal ahora lee los productos desde Room; el catálogo estático solo sirve para añadir fichas que faltan.
3. **[PARCIALMENTE RESUELTO] Historial persistente con procedencia.** Ya existe una tabla/DAO con URL, fecha y verificación; sigue sin datos reales hasta conectar un proveedor autorizado.
4. **[RESUELTO] Riesgo de pérdida por migración destructiva.** La migración destructiva se retiró y el camino 2→3→4 ha superado pruebas de preservación.
5. **El seguimiento no monitoriza.** Los objetivos se guardan localmente, pero no hay comprobaciones automáticas ni alertas reales; la simulación de caída modifica objetos de demostración.

## 3 correcciones prioritarias

- [x] **C01 — Preservar datos del usuario.** Migraciones explícitas 2→3→4; se ha retirado la migración destructiva y las pruebas confirman que los seguimientos/productos se conservan.
- [~] **C02 — Persistir observaciones verificables (base completada).** La entidad, el DAO y el historial filtrado por verificación existen; falta conectar un proveedor real y registrar consultas auténticas.
- [~] **C03 — Bloquear falsas ofertas (parcial).** El modelo requiere un registro verificado para presentar precios e historial como reales. Las alertas auténticas esperan a que se conecte la monitorización.

## Registro de progreso

| Hito | Estado |
|---|---|
| APK Android instalable y rama principal privada | Completado |
| Catálogo y directorio de comercios ampliados | Completado como datos de descubrimiento; catálogo ahora sale de Room, pero no son precios en directo |
| Ajustes y perfil local | Completado; sin autenticación online |
| C01: migración no destructiva | Completado y probado |
| C02: esquema y DAO de observaciones | Completado; falta el proveedor real |
| Conector manual Google Shopping/SerpApi | Código integrado y pruebas superadas; falta configurar una cuenta/clave y verificar la primera consulta real |
| Monitorización y alertas | Pendiente |

## Criterio de finalización

Cada mejora debe compilar, pasar sus pruebas pertinentes y mantener explícita la diferencia entre datos de demostración y precios reales. Ninguna clave de proveedor se incluirá en el APK ni en el repositorio.
