# Mínimo — hoja de ruta de calidad de precios (22 fases)

**Prioridad actual:** que cada precio corresponda al producto/variante correcto, que no se mezclen artículos usados o promocionales con producto nuevo y que los datos antiguos inválidos no sigan apareciendo.

**Estado técnico actual:** Google Shopping España y búsqueda de Amazon.es mediante SerpApi. Google Shopping es una fuente agregadora, no una API directa de cada tienda. La búsqueda de Amazon.es es un segundo proveedor consultado por separado. No hay todavía feeds/API directos de todos los comercios ni monitorización automática. Las consultas consumen cuota SerpApi; no se deben lanzar lotes grandes sin validar primero el saldo.

**Progreso global:** no se declara un porcentaje numérico hasta validar las fases con pruebas y una búsqueda real reproducible en el móvil. Los resultados de un proveedor son precios observados, no confirmación de stock, coste de envío ni precio final en caja.

## 20 fallos o riesgos detectados

1. **Etiquetas contradictorias de demostración y consulta.** La ficha puede mostrar una observación de proveedor junto a mensajes heredados que sugieren que todo sigue siendo demostrativo.
2. **Precios antiguos de baja calidad sobreviven en Room.** Un filtro nuevo no elimina automáticamente observaciones guardadas por APK anteriores.
3. **Variantes de memoria no siempre se comparan de forma estricta.** Un resultado 8 GB/512 GB puede parecer coincidente con una consulta 16 GB/256 GB.
4. **La condición del artículo no se inspeccionaba en todos los parsers.** Campos como `second_hand_condition` pueden distinguir usado/reacondicionado de nuevo.
5. **Artículos promocionales y personalizados contaminan las búsquedas.** Ejemplo visible: batería impresa con logotipo, además de posibles lotes y multipacks no solicitados.
6. **Precios extremos altos no reciben el mismo control que los precios demasiado bajos.** El filtro actual puede dejar pasar una oferta atípica si no hay suficientes comparables.
7. **La confianza del resultado no se explica de forma uniforme.** Una observación de Google Shopping puede parecer una verificación directa de tienda.
8. **El enlace puede abrir Google en vez de la ficha del comercio.** El nombre de la tienda y el destino no siempre coinciden de forma visible.
9. **No se normalizan todos los nombres de vendedores y dominios.** Puede duplicarse una tienda o agruparse bajo un nombre inconsistente.
10. **El envío no forma parte del precio comparable.** La oferta más barata puede dejar de serlo al sumar transporte.
11. **La disponibilidad real no está confirmada.** La ficha puede conservar un precio observado aunque la oferta haya caducado o no tenga stock.
12. **El estado de caché no siempre destaca la antigüedad del dato.** Una búsqueda reutilizada puede parecer recién consultada.
13. **Google Shopping y Amazon no se agregan en una sola consulta comparativa.** Son flujos separados, y cada consulta tiene su propio coste/cuota.
14. **Faltan conectores autorizados de comercios.** La lista de tiendas es un directorio, no 33 APIs de precios en directo.
15. **La identidad de producto depende demasiado del título.** EAN/GTIN/MPN/SKU y atributos normalizados aún no cubren todos los artículos.
16. **El agrupado puede elegir un título representativo distinto del título de la oferta más barata.** Debe conservarse la relación exacta entre producto y oferta.
17. **Un único registro puede dibujarse como si fuera una evolución temporal.** Una observación no demuestra tendencia ni mínimo histórico.
18. **El término “mínimo” puede parecer un récord absoluto con muy pocos datos.** Debe distinguirse mínimo registrado de mínimo histórico suficiente.
19. **Las imágenes de Shopping pueden no corresponder exactamente a la variante.** La imagen no debe decidir por sí sola la identidad del producto.
20. **Faltan pruebas de regresión de extremo a extremo con casos reales adversos.** Hay que cubrir variantes, moneda, condición, enlaces, caché, persistencia y evolución del historial.

## Plan de 22 fases ordenadas por impacto y dependencias

- [~] **F01 — Limpiar observaciones heredadas inválidas.** Revalidar los registros ya guardados al leerlos y sanear Room de observaciones que incumplen precio, vendedor, URL, moneda o identidad. Criterio: un MacBook a 67,50 € de un vendedor excluido no aparece como precio comparable aunque venga de una instalación anterior.
- [~] **F02 — Identidad estricta de variante.** Exigir coincidencia de modelo y capacidades solicitadas cuando estén presentes. Criterio: no mezclar 8/16 GB, 256/512 GB ni versiones Pro/Ultra/FE incompatibles.
- [~] **F03 — Separar nuevo, usado, reacondicionado y promocional.** Inspeccionar condición estructurada y título; rechazar usados y artículos personalizados/lotes no solicitados en el historial de producto nuevo. Criterio: regresiones para `second_hand_condition`, Wallapop, multipacks y artículos con logotipo.
- [~] **F04 — Validación temporal y de destino para nuevas observaciones.** En `ProductRepository`, rechazar URLs de búsqueda de Google y dominios de redirección publicitaria, fechas futuras de más de cinco minutos, timestamps no positivos, proveedores desconocidos y monedas inconsistentes. Las observaciones heredadas pasan la misma validación al mostrarse; las filas inválidas se excluyen sin borrar el historial completo. Se añadieron pruebas de regresión para enlaces de búsqueda y fechas futuras.
- [ ] **F04b — Puntuación de calidad y detección de atípicos.** Calcular confianza con identidad, dispersión de precios, antigüedad y calidad de URL; marcar valores sospechosos en vez de llamarlos chollos automáticamente.** Calcular confianza con identidad, dispersión de precios, antigüedad y calidad de URL; marcar valores sospechosos en vez de llamarlos chollos automáticamente.
- [ ] **F05 — Procedencia transparente.** Guardar proveedor, vendedor, URL de destino, hora de observación y tipo de enlace; diferenciar enlace directo de fallback a Google.
- [ ] **F06 — Comparación multifuente unificada.** Mostrar juntos resultados de Google Shopping y Amazon.es cuando se solicite, con deduplicación y procedencia por oferta; respetar la cuota antes de cada consulta.
- [ ] **F07 — Conectores oficiales/feeds autorizados.** Evaluar APIs, feeds de comercios y redes como Awin solo tras disponer de cuenta, permisos y condiciones compatibles. No simular APIs ni hacer scraping indiscriminado.
- [ ] **F08 — Normalización de tiendas.** Canonicalizar dominios, alias y vendedores para evitar duplicados y atribuciones falsas.
- [ ] **F09 — Identificadores de producto.** Incorporar GTIN/EAN, MPN y SKU cuando los provea la fuente; no inventarlos ni dar por verificados los códigos precargados.
- [ ] **F10 — Normalización de especificaciones.** Extraer capacidad, RAM, almacenamiento, color, tamaño, generación y estado, conservando variantes distintas.
- [ ] **F11 — Coste final comparable.** Separar precio de artículo, envío, impuestos cuando proceda y precio total; desconocido no significa cero.
- [ ] **F12 — Frescura y expiración.** Mostrar antigüedad de cada observación y retirar de la comparación activa los datos caducados sin borrar el historial.
- [ ] **F13 — Historial temporal honesto.** Registrar instantáneas por fecha, deduplicar por tienda/producto/día y no dibujar tendencia con un único día.
- [ ] **F14 — Mínimos y medias con evidencia.** Calcular mínimo observado, media y descuentos solo cuando el número de fechas y la ventana temporal sean suficientes.
- [ ] **F15 — Imágenes ligadas al producto.** Usar la imagen de la oferta correspondiente y fallback neutro cuando la relación no sea fiable.
- [ ] **F16 — Búsqueda por intención.** Distinguir búsqueda de modelo exacto, búsqueda de categoría y búsqueda de accesorios; adaptar filtros sin bloquear consultas válidas.
- [ ] **F17 — Cuota, caché y límites.** Evitar llamadas redundantes, mostrar saldo y coste estimado de los lotes, cancelar con seguridad y registrar errores sin filtrar claves.
- [ ] **F18 — Verificación de tienda.** Abrir el destino directo cuando exista y facilitar una comprobación manual del precio, envío y stock; no afirmar verificación automática sin fuente que la haga.
- [ ] **F19 — Seguimiento y alertas reales.** Programar consultas con límites y condiciones de batería/red solo cuando exista un proveedor autorizado y permisos claros.
- [ ] **F20 — Persistencia, migraciones y privacidad.** Mantener Room como fuente de verdad, respaldar/exportar datos del usuario y proteger claves; ningún secreto dentro del APK.
- [ ] **F21 — UI de confianza y accesibilidad.** Diferenciar “precio observado”, “enlace de Google”, “stock por confirmar”, “historial en construcción” y “mínimo histórico”; revisar contraste, tamaños y pantallas estrechas.
- [ ] **F22 — QA y publicación.** Pruebas unitarias, integración con respuestas guardadas, regresión de Room, cuota, APK instalable y validación manual en el móvil antes de liberar.

## Trabajo iniciado en la rama `fix/price-quality-phase-01`

- Revalidación de observaciones persistidas en tiempo de lectura y limpieza inicial de registros heredados que ya no cumplen las reglas actuales.
- Validación adicional de timestamps y de destino directo: se rechazan fechas futuras no plausibles y enlaces cuya URL final aparente ser una búsqueda de Google/redirección publicitaria.
- Prueba Robolectric de regresión para asegurar que una oferta válida de tienda se conserva mientras se excluyen los enlaces de búsqueda de Google y las observaciones futuras.
- Filtros en parsers para condición usada/reacondicionada, variantes de memoria incompatibles y lotes/artículos promocionales.
- Regresión de parser para un MacBook con memoria incorrecta, una oferta reacondicionada y una batería promocional con logotipo.
- El gráfico de precio deja de mostrarse con una única observación; la ficha explica que aún no existe una evolución temporal.
- La etiqueta del gráfico distingue mínimo registrado de mínimo histórico suficiente.

## Criterio de aceptación general

Ninguna oferta debe presentarse como chollo confirmado solo por ser el precio más bajo del conjunto. Para considerarla comparable debe coincidir con el producto/variante, tener precio en EUR, vendedor y destino identificables, condición comparable y fecha visible. Los conectores nuevos se incorporan solo con acceso autorizado y pruebas verificables.
