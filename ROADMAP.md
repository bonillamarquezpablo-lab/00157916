# Mínimo — hoja de ruta de producto

**Estado de la rama del buscador:** consulta manual de Google Shopping y Amazon.es mediante SerpApi integrada; falta confirmar clave y consulta real en el móvil · 33 comercios en el directorio · actualización en segundo plano y alertas automáticas no implementadas.

**Progreso global provisional: 1%** se mantiene hasta completar el primer flujo de precio real de extremo a extremo en el móvil. El código del conector está integrado, pero falta la clave del proveedor y validar una búsqueda real; el porcentaje se actualiza por resultados funcionales, no por líneas de código.

## Política estricta de historial (2026-10-13)

- El gráfico solo admite observaciones reales y fechadas de búsquedas válidas guardadas en Room.
- No se generan puntos retrospectivos, series 2022–2026 ni precios interpolados almacenados.
- El primer punto del historial define la etiqueta «Historial desde [fecha]»; si no hay observaciones globales muestra «Sin datos todavía» y si el rango elegido está vacío muestra «Sin datos en este rango».
- El catálogo precargado se marca como EJEMPLO y nunca alimenta el historial de precios.
- Se retiró la acción de simular una caída de precio, incluido el punto temporal de historial que añadía en memoria.
- En la rama revisada no existen funciones llamadas `generateHistory` o `generateInflatedHistory`; los tests ahora exigen que los productos de ejemplo tengan `priceHistory` vacío.

## Correcciones técnicas recientes en `fix/real-price-history-only`

- Las observaciones anteriores al 13 de octubre de 2026 quedan fuera de cualquier precio actual verificado o del gráfico, incluso si sobreviven en instalaciones antiguas.
- Los resultados solo se guardan con URL HTTPS directa al comercio; las URL de Google Shopping/búsqueda genérica ya no se tratan como enlaces de producto válidos.
- La ingesta descarta segunda mano, reacondicionados, alquileres y financiación cuando aparecen en el título o en los campos de condición del proveedor.
- La misma tienda y el mismo producto conservan una sola observación por día: las búsquedas posteriores actualizan esa fila en vez de duplicar datos o mostrar un precio obsoleto.
- Se corrigió la identidad para conservar capacidad mAh y potencia W de baterías/cargadores, y para normalizar formatos equivalentes del modelo Sony WH-1000XM5.
- La ficha utiliza la fuente real del último dato y el gráfico une puntos reales con segmentos rectos, sin curvas que sugieran precios no observados.
- Se añadieron y actualizaron pruebas de regresión para fecha de corte, estado del artículo, enlaces directos, variantes de producto y actualización diaria. La compilación y las pruebas todavía necesitan ejecución en un entorno Android; no se consideran aprobadas solo por estar escritas.

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
