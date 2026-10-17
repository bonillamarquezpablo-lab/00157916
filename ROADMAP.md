# Mínimo — hoja de ruta de producto

**Estado actual de main (17/10/2026):** buscador unificado de Google Shopping con complemento automático de GAME para búsquedas de videojuegos/consolas, búsqueda de Amazon.es y selección de familias/variantes. La clave de SerpApi se configura en el dispositivo; la consulta real y la estabilidad final deben comprobarse en la tablet.

## Estado de estabilidad y confianza de precios

- Las consultas de red usan el hilo IO y límites de conexión/lectura/llamada; el complemento opcional de GAME tiene un límite de 12 s para no retrasar indefinidamente la búsqueda principal.
- Agrupación, clasificación y filtros caros de los resultados online se calculan fuera del hilo de interfaz. Los guardados de observaciones del buscador en Room se ejecutan en IO.
- Los precios sospechosos, variantes incompatibles, artículos promocionales y enlaces falsos se filtran o etiquetan; un resultado de Google Shopping no se confunde con una tienda conectada ni garantiza disponibilidad.
- La ficha distingue precios actuales verificados, datos guardados pendientes de revalidación y valores de demostración. Un precio guardado antiguo no se convierte en precio actual.
- El historial distingue tres series: observaciones en vivo guardadas por Mínimo, capturas de Internet Archive y datos históricos de Google Shopping. Las dos fuentes externas no alimentan la media en vivo ni pueden fabricar el mínimo histórico de la serie en vivo.
- Las capturas históricas conservan su procedencia y las filas de Room no se borran para “limpiar” el historial. Si hay una observación viva en un día, esa observación determina el punto visible de ese día.
- Los informes de cierre inesperado se guardan en almacenamiento privado, omiten el mensaje de excepción y no se envían automáticamente. El usuario puede copiar o compartir el informe de forma explícita al abrir la app.
- Las acciones de CI del flujo de recuperación están fijadas a revisiones inmutables. El escaneo de secretos y las pruebas relacionadas se ejecutan en CI.
- **Limitación de validación:** pasar CI demuestra que el código compila y supera las pruebas automatizadas incluidas; no garantiza ausencia absoluta de defectos ni sustituye las búsquedas reales con la clave del usuario en su dispositivo.

## Reglas de historial y precio

- No se generan puntos retrospectivos, curvas interpoladas ni series sintéticas.
- Las ofertas en vivo y las fuentes históricas externas se representan en series separadas y etiquetadas.
- El mínimo observado, la media y los juicios de “mínimo histórico” solo se basan en evidencia en vivo válida. Las afirmaciones históricas requieren suficientes fechas verificadas; una sola observación no basta.
- El catálogo precargado es de demostración y no alimenta la evolución real del precio.
- Las migraciones de Room deben preservar productos, observaciones y seguimientos existentes. No borrar datos locales ni recurrir a una migración destructiva para resolver incompatibilidades.

## Integración de cambios y dependencias

- Se han integrado selectivamente familias de producto, búsqueda unificada, GAME, filtros de calidad, endurecimiento de enlaces, separación de fuentes de historial, manejo del tiempo de espera y diagnóstico local de cierres.
- Las actualizaciones de dependencias solo se incorporan cuando compilan y pasan pruebas. No se debe forzar una dependencia que requiera una versión del SDK Android que el entorno de compilación no pueda instalar.
- Las ramas antiguas o divergentes no se fusionan completas si duplican funciones actuales o podrían revertir migraciones, controles de precio, enlaces seguros o versión/firma de la APK.

## 16 mejoras planificadas

- [x] **M01 — Base de integridad y procedencia.** Existe una tabla persistente de observaciones con producto, comercio, precio, moneda, fecha/hora, URL y estado de verificación; queda pendiente alimentarla con un proveedor real.
- [~] **M02 — Primer proveedor real (código preparado).** La app incluye consultas manuales de Google Shopping España a través de SerpApi, con almacenamiento local de la clave cifrada. Falta crear/configurar la cuenta, guardar la clave en Ajustes y probar una consulta real. No es una actualización continua.
- [ ] **M03 — Más proveedores autorizados.** Integrar fuentes oficiales o feeds con permiso explícito por comercio; no confundir una lista de webs con conectores.
- [ ] **M04 — Identidad de producto.** Normalizar EAN/GTIN, MPN/SKU, variante, capacidad, color y estado nuevo/reacondicionado.
- [~] **M05 — Ingesta fiable (parcial).** Se valida la respuesta, se consulta el saldo real/límite horario de SerpApi antes de los lotes, se adaptan los lotes a la cuota disponible y se reutilizan búsquedas recientes desde caché local (hasta 40 entradas / 55 min). Falta backend compartido para una app multiusuario, gestión central de cuotas, registros y tareas programadas.
- [ ] **M06 — Historial y mínimos reales.** Calcular mínimos y descuentos solo desde observaciones verificadas y fechadas.
- [ ] **M07 — Comparación de coste final.** Incluir envío, disponibilidad, vendedor, IVA, condición y fecha de comprobación.
- [ ] **M08 — Actualizaciones de fondo.** Programar consultas respetando batería, conectividad y condiciones de los proveedores.
- [ ] **M09 — Alertas reales.** Activar avisos solo con monitorización funcional, permisos y preferencias claras del usuario.
- [ ] **M10 — Cuenta online.** Autenticación segura, sincronización y recuperación; el perfil local actual no es una cuenta.
- [~] **M11 — Catálogo escalable (parcial).** Room es la fuente de verdad y las instalaciones existentes añaden solo los IDs que faltan sin sobrescribir filas. Quedan pendientes paginación y filtros escalables.
- [~] **M12 — Ordenación comprobable (parcial).** Ya hay orden por nombre/categoría y por precio verificado; falta ampliar por comercio y frescura del dato.
- [ ] **M13 — Seguimiento y objetivos.** Favoritos, objetivos de precio, exportación e historial por usuario.
- [~] **M14 — Interfaz moderna y accesible.** Cargas, errores, estados vacíos, diseño adaptable y accesibilidad. El gráfico conserva 30D, 90D, 6M, 1A, 2A, 3A, 4A y Todo, con etiquetas basadas exclusivamente en observaciones reales.
- [ ] **M15 — Calidad y publicación.** Pruebas de integración, privacidad, seguridad, observabilidad, copias y flujo de publicación.
- [x] **M16 — Buscador online manual.** Búsqueda libre en Google Shopping España y búsqueda directa de Amazon.es a través de SerpApi; ambas muestran resultados con precio en euros dentro de Mínimo. Los resultados de Amazon.es incluyen, cuando se informa, la elegibilidad de entrega Prime. No leen precios/cupones personalizados de la sesión personal y requieren SerpApi; no hay monitorización automática.

## 5 fallos o limitaciones detectados en el código actual

1. **Conector listo, proveedor sin configurar.** El catálogo precargado sigue siendo de demostración; las consultas manuales de Google Shopping y Amazon.es solo funcionan tras guardar la clave de SerpApi en el dispositivo.
2. **[RESUELTO] Dos fuentes de verdad para productos.** El flujo principal ahora lee los productos desde Room; el catálogo estático solo sirve para añadir fichas que faltan.
3. **[PARCIALMENTE RESUELTO] Historial persistente con procedencia.** Ya existe una tabla/DAO con URL, fecha y verificación; sigue sin datos reales hasta conectar un proveedor autorizado.
4. **[RESUELTO] Riesgo de pérdida por migración destructiva.** La migración destructiva se retiró y el camino 2→3→4 ha superado pruebas de preservación.
5. **El seguimiento no monitoriza.** Los objetivos se guardan localmente, pero no hay comprobaciones automáticas ni alertas reales; la acción de simular caídas se ha retirado para evitar historial sintético.

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
| Conectores manuales Google Shopping y Amazon.es mediante SerpApi | Código integrado en la rama del buscador; falta configurar la clave y probar precios reales en el móvil |
| Monitorización y alertas | Pendiente |

## Criterio de finalización

Cada mejora debe compilar, pasar sus pruebas pertinentes y mantener explícita la diferencia entre datos de demostración y precios reales. Ninguna clave de proveedor se incluirá en el APK ni en el repositorio.
