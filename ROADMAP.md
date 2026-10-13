# Mínimo — hoja de ruta en 20 fases

**Orden:** de menor a mayor dificultad. Cada fase debe cerrarse con pruebas y evidencia; que una función exista parcialmente no equivale a que esté terminada en producción.

**Situación actual conocida:** el catálogo usa Room y existe almacenamiento de observaciones verificables. El buscador manual de Google Shopping España y Amazon.es mediante SerpApi está implementado, y el fallback a búsqueda de Google Shopping cuando falta un enlace útil ya está en `main` (PR #15). La mejora adicional de relevancia, anuncios no comparables y precios anormalmente bajos se propone en el PR #17. Aún falta validar la búsqueda real en el móvil; las actualizaciones de fondo, las alertas automáticas y las cuentas sincronizadas no están completas.

## Las 20 fases, de fácil a difícil

### Fase 01 — Distinguir siempre datos de muestra y datos reales
**Estado:** 🟡 Parcial.  
**Trabajo:** unificar las etiquetas de demostración, precios consultados, precios verificados e historial simulado en todas las pantallas. Evitar que una simulación parezca una oferta real.  
**Cierre:** pruebas que demuestren que los datos de muestra no se guardan ni se muestran como observaciones verificadas.

### Fase 02 — Corregir los enlaces de los resultados
**Estado:** ✅ Fallback de búsqueda integrado en `main` (PR #15); la precisión del resultado sigue en mejora (PR #17).  
**Trabajo:** cuando haya título y precio válido, pero no un enlace HTTPS útil, generar una búsqueda de Google Shopping España codificada con el título; identificar visualmente ese destino como búsqueda, no como tienda.  
**Cierre:** probar enlaces directos, enlaces Google y resultados sin enlace utilizable; no atribuir una búsqueda de Google a una tienda como si fuera la ficha del producto.

### Fase 03 — Explicar por qué una búsqueda no devuelve resultados
**Estado:** ⬜ Pendiente.  
**Trabajo:** separar claramente cero resultados del proveedor, productos irrelevantes, precios/monedas inválidos y enlaces ausentes. Mostrar nombres de campos recibidos cuando todos los resultados fallen por enlaces.  
**Cierre:** pruebas para cada causa y mensajes en español útiles para corregir la consulta.

### Fase 04 — Mejorar la normalización de las consultas
**Estado:** ⬜ Pendiente.  
**Trabajo:** normalizar espacios, guiones, acentos, marca y modelo sin borrar identificadores importantes como XM5, capacidad, tamaño o generación.  
**Cierre:** batería de búsquedas equivalentes para casos como Sony WH-1000XM5 y sus variantes, sin aceptar productos distintos solo por compartir marca.

### Fase 05 — Endurecer la lectura de precios y metadatos
**Estado:** ⬜ Pendiente.  
**Trabajo:** interpretar de forma consistente los campos del proveedor, separar precio mostrado y precio numérico, rechazar monedas extranjeras explícitas y validar URL, título e imagen.  
**Cierre:** pruebas para formatos españoles, importes malformados, monedas no EUR, enlaces HTTP y respuestas incompletas.

### Fase 06 — Validar el primer recorrido real de extremo a extremo
**Estado:** 🟡 Código preparado; prueba real pendiente.  
**Trabajo:** configurar SerpApi desde Ajustes y ejecutar en el móvil una búsqueda controlada; comprobar consulta, resultado, precio, origen, apertura del enlace y persistencia. No guardar claves en el repositorio ni en el APK.  
**Cierre:** evidencia de una búsqueda real con producto identificable y revisión manual del precio final en el vendedor.

### Fase 07 — Hacer inequívoca la identificación del destino
**Estado:** ⬜ Pendiente.  
**Trabajo:** distinguir ficha directa de comercio, página de producto de Google Shopping y búsqueda genérica. No llamar “oferta en tienda” a un enlace que solo conduce a Google.  
**Cierre:** etiqueta y acción del botón coinciden con el tipo de URL en todos los casos probados.

### Fase 08 — Afianzar las búsquedas de Google Shopping y Amazon.es
**Estado:** 🟡 Funciones disponibles; precisión global pendiente de validar.  
**Trabajo:** probar ambas fuentes por separado, mantener sus resultados y estados aislados, y explicar que Amazon no conoce cupones personalizados ni precios de una sesión Prime.  
**Cierre:** búsquedas de control en ambas fuentes con resultados etiquetados correctamente y errores que no mezclen un proveedor con otro.

### Fase 09 — Identificar correctamente cada producto y variante
**Estado:** ⬜ Pendiente.  
**Trabajo:** priorizar EAN/GTIN y MPN/SKU cuando existan; diferenciar color, memoria, tamaño, generación, vendedor y nuevo/reacondicionado.  
**Cierre:** pruebas que eviten comparar variantes distintas y detecten duplicados de la misma oferta.

### Fase 10 — Controlar caché, duplicados, límites y reintentos
**Estado:** 🟡 Parcial.  
**Trabajo:** consolidar caché, deduplicación, reintentos acotados, gestión de errores, cuotas reales del proveedor y cancelación de consultas. Evitar gastar créditos innecesariamente.  
**Cierre:** pruebas con límite agotado, timeout, repetición de búsqueda, respuesta parcial y varias consultas seguidas sin duplicar precios.

### Fase 11 — Formalizar proveedores autorizados
**Estado:** ⬜ Pendiente.  
**Trabajo:** documentar para cada fuente los términos, coste, límites y campos disponibles; integrar únicamente APIs o feeds con permiso adecuado. Un directorio de tiendas no cuenta como conector.  
**Cierre:** ficha de proveedor con autorización, política de uso, gestión de errores y pruebas verificables antes de declararlo activo.

### Fase 12 — Completar la persistencia y procedencia de observaciones
**Estado:** 🟡 Esquema/DAO disponibles; alimentación real pendiente.  
**Trabajo:** guardar cada observación auténtica con producto, tienda, URL, precio, moneda, fecha, fuente y estado de verificación. Mantener migraciones no destructivas.  
**Cierre:** pruebas que reinicien la app, conserven seguimientos y distingan observaciones verificadas de no verificadas.

### Fase 13 — Completar filtros y ordenaciones fiables
**Estado:** 🟡 Parcial.  
**Trabajo:** ordenar y filtrar por precio verificado, nombre, categoría, comercio y fecha de observación; no colocar precios desconocidos por delante de precios verificados en ordenaciones de precio.  
**Cierre:** pruebas con productos verificados, sin precio y con observaciones de distinta antigüedad.

### Fase 14 — Comparar el coste real de compra
**Estado:** ⬜ Pendiente.  
**Trabajo:** mostrar, cuando la fuente lo permita, envío, disponibilidad, vendedor real, IVA, condición y fecha/hora de consulta; no inventar los campos ausentes.  
**Cierre:** una comparación distingue precio del artículo de coste total y marca con claridad los datos que el proveedor no facilita.

### Fase 15 — Crear historial y mínimos históricos reales
**Estado:** ⬜ Pendiente.  
**Trabajo:** calcular historial, mínimo, máximo y descuentos exclusivamente desde observaciones verificables, con fechas y procedencia. Excluir simulaciones y datos de catálogo de demostración.  
**Cierre:** pruebas con observaciones mezcladas —reales, no verificadas y simuladas— que demuestren que solo las válidas afectan a las métricas.

### Fase 16 — Escalar el catálogo y la experiencia de búsqueda
**Estado:** 🟡 Parcial; Room ya es la fuente de verdad.  
**Trabajo:** añadir paginación, consultas eficientes, filtros que escalen y estados vacíos/carga/error coherentes en catálogos grandes.  
**Cierre:** pruebas con catálogo ampliado, sin sobrescribir productos ni seguimientos existentes y sin bloquear la interfaz.

### Fase 17 — Programar actualizaciones en segundo plano
**Estado:** ⬜ Pendiente.  
**Trabajo:** implementar tareas periódicas con restricciones de batería y conectividad, límites del proveedor, política de reintentos y cancelación. No convertir la búsqueda manual en monitorización implícita.  
**Cierre:** pruebas de planificación, recuperación tras reinicio, falta de red, límites de consulta y ausencia de ejecuciones duplicadas.

### Fase 18 — Activar alertas de precio auténticas
**Estado:** ⬜ Pendiente.  
**Trabajo:** evaluar objetivos del usuario contra observaciones reales recientes y enviar notificaciones solo cuando el dato cumpla las reglas; respetar permisos y preferencias.  
**Cierre:** pruebas que generen una alerta al cumplirse el objetivo, eviten duplicados y nunca alerten por una simulación o precio no verificado.

### Fase 19 — Crear una arquitectura backend segura para varios dispositivos
**Estado:** ⬜ Pendiente.  
**Trabajo:** sacar las credenciales del proveedor del cliente para una versión multiusuario; añadir proxy/backend, control de cuotas central, registros minimizados y protección contra abuso.  
**Cierre:** revisión de seguridad y pruebas que confirmen que los secretos no aparecen en APK, código cliente, logs ni repositorio.

### Fase 20 — Cuentas, sincronización y publicación de producción
**Estado:** ⬜ Pendiente.  
**Trabajo:** añadir autenticación real, sincronización y recuperación de datos; gestión de privacidad y borrado de cuenta/datos; observabilidad, copias y flujo reproducible de CI, firma y publicación.  
**Cierre:** pruebas de registro, inicio/cierre de sesión, sincronización, recuperación, borrado, privacidad, instalación/actualización y compilación limpia de producción.

## Fallos y limitaciones detectados

1. **Precios reales incompletos:** el catálogo de demostración no equivale a un catálogo de ofertas en directo; SerpApi requiere configuración y prueba real.
2. **Enlaces de producto poco fiables:** algunos resultados devuelven destinos de Google o enlaces no válidos; la solución de fallback está en revisión en el PR #15.
3. **Coincidencias irrelevantes o escasas:** la precisión depende de normalizar la consulta, validar el resultado y probar distintos formatos devueltos por el proveedor.
4. **Historial de observaciones sin alimentación completa:** existe la estructura persistente, pero debe llenarse con consultas auténticas y mantener la procedencia.
5. **Seguimiento sin monitorización:** los objetivos locales no garantizan comprobaciones automáticas ni alertas reales.

## Reglas de ejecución

- Abordar las fases en orden, salvo que una dependencia técnica justifique explícitamente lo contrario.
- No marcar una fase como completada solo porque se haya escrito código.
- Cada cierre requiere compilación correcta, pruebas pertinentes y revisión del comportamiento real cuando dependa de un proveedor externo.
- No publicar precios inventados ni presentar datos de demostración como verificados.
- No subir claves, tokens, contraseñas ni credenciales al repositorio o al APK.
- Actualizar el estado y la evidencia de cierre al terminar cada fase.

## Correcciones prioritarias existentes

- **C01 — Migraciones no destructivas:** completada y probada.
- **C02 — Observaciones de precio con procedencia:** base de datos implementada; falta un flujo real completo y validado.
- **C03 — Evitar falsas ofertas e historiales:** protección parcial; la monitorización y las alertas auténticas siguen pendientes.

## Progreso

El porcentaje global no debe estimarse por líneas de código ni por número de tareas escritas. Se actualizará según las fases cerradas con criterios verificables. La primera referencia práctica es completar una búsqueda real en el móvil, validar el destino y persistir la observación correctamente.
