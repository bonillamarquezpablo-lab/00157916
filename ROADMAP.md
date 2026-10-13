# Mínimo — auditoría de calidad y hoja de ruta de 22 fases

**Fecha de auditoría:** 13 de octubre de 2026  
**Objetivo:** no enseñar un precio como oferta útil hasta comprobar que el producto, el estado, el vendedor, la fuente y la fecha tienen coherencia. Aumentar fuentes reales sin confundir un directorio de tiendas con conectores de precios.

## Diagnóstico ejecutivo

Las capturas muestran tres fallos de confianza: una oferta promocional de baterías personalizadas presentada como si fuera una batería portátil normal; un MacBook de segunda mano de Wallapop a 67,50 € dentro de un comparador; y fichas que mezclan el mensaje «Datos de demostración» con campos de precio/historial que parecen reales. No es un problema de diseño: es un problema de procedencia, limpieza de datos y relevancia de resultados.

Hoy el código consulta dos motores a través de SerpApi:
1. Google Shopping España, que puede devolver ofertas de muchos comercios.
2. Búsqueda pública de Amazon.es, que es un motor separado.

**Las 33 tiendas del directorio no son 33 conectores de precio en directo.** Los enlaces de cada comercio permiten abrir/buscar en su web; no equivalen a integraciones capaces de consultar, verificar y registrar un precio automáticamente.

SerpApi documenta que el diseño actual de Google Shopping devuelve aproximadamente 40 resultados y que `start` no permite paginar esa vista; solicitar `num=100` no aumenta el tamaño de la página. Por eso no se deben prometer 100 resultados ni inventar fuentes adicionales. Véase [SerpApi — Google Shopping API, Pagination](https://serpapi.com/google-shopping-api).

La futura integración de eBay debe utilizar su API autorizada, con los filtros de condición adecuados y previa disponibilidad de credenciales/acceso de producción. La [documentación oficial de eBay Browse API](https://developer.ebay.com/api-docs/buy/static/api-browse.html) describe búsqueda por palabra clave, GTIN y producto, filtros y detalles de artículo. El código no debe simular ese acceso hasta que las credenciales y permisos estén configurados.

## 20 fallos detectados

| ID | Fallo | Efecto en el usuario | Prioridad / estado |
|---|---|---|---|
| F01 | La base Room podía conservar ofertas históricas de marketplace o precios implausibles si llevaban `isVerified=1`. | Wallapop y precios absurdos siguen saliendo tras corregir el filtro de nuevas búsquedas. | **P0 · Implementación en esta rama; pruebas de regresión activas** |
| F02 | El estado de ficha (demo/no verificado) y los datos numéricos de precio/historial no tienen una única política visible. | La ficha puede parecer contradictoria; hay que validar el APK nuevo contra una instalación con datos heredados. | **P0 · Parcial: el flujo visible filtra observaciones inválidas; falta prueba visual con una base heredada instalada** |
| F03 | Los anuncios de merchandising personalizado, logotipos o lotes promocionales podían pasar el filtro de relevancia. | Una batería promocional o un precio por volumen aparece como producto de consumo. | **P0 · Implementado en parser y filtro persistido; CI pendiente** |
| F04 | El parser de Amazon no pasaba la consulta al filtro de relevancia. | Una respuesta de Amazon podía incluir un artículo distinto al solicitado. | **P0 · En corrección en esta rama** |
| F05 | El filtro de vendedores actuaba principalmente por nombre, no por host de destino en todos los flujos. | Una URL de Wallapop podría pasar si el proveedor etiqueta el vendedor de otra forma. | **P0 · En corrección en esta rama** |
| F06 | El número «33 tiendas» parecía indicar 33 conectores activos. | El usuario espera precios en vivo de cada comercio, pero muchos son enlaces manuales. | **P1 · Texto corregido en esta rama** |
| F07 | Google Shopping y Amazon son las únicas dos fuentes online implementadas; no hay adaptadores de precios directos de 33 tiendas. | Pocas ofertas cuando el agregador no encuentra un producto. | **P1 · Arquitectura pendiente** |
| F08 | `num=100` no fuerza 100 resultados con el diseño actual de Google Shopping y no hay paginación útil. | La búsqueda puede seguir mostrando alrededor de 40 resultados antes de filtrar. | **P1 · Límite del proveedor documentado** |
| F09 | Si SerpApi no ofrece un enlace de comercio, el resultado puede abrir Google Shopping en lugar de la ficha de la tienda. | El usuario puede creer que se ha detectado un enlace directo. | **P1 · Mejorar etiqueta y resolución de URL** |
| F10 | No siempre se comprueba que el host del enlace corresponda con el comercio mostrado. | Nombre de comercio y destino podrían no coincidir. | **P1 · Ampliar las pruebas de procedencia** |
| F11 | El emparejamiento de texto usa una tolerancia relativamente permisiva para consultas genéricas. | Productos parecidos o accesorios pueden entrar en la lista. | **P1 · En progreso: parser exige compatibilidad de modelo, variante y memoria cuando esos datos aparecen** |
| F12 | La identidad se basa principalmente en título; aún no prioriza GTIN/EAN/MPN de forma completa. | Títulos diferentes o especificaciones parecidas pueden mezclarse. | **P1 · Fase de identidad pendiente** |
| F13 | La clave de agrupación usa heurísticas y códigos extraídos de títulos. | Variantes, paquetes o modelos con códigos cortos pueden agruparse mal. | **P1 · Ampliar corpus de pruebas** |
| F14 | Si una nueva oferta no trae miniatura, se puede conservar la imagen anterior del producto persistido. | Una tarjeta podría mostrar una imagen que ya no corresponde al título. | **P1 · Corregir al validar identidad y cambio de título** |
| F15 | Los límites de precio por categoría son umbrales fijos y no verifican por sí solos modelo, condición o accesorios. | Se escapan precios anómalos o se pueden rechazar ofertas legítimas. | **P1 · Necesita señal de modelo y distribución comparable** |
| F16 | El precio observado no representa necesariamente el coste final con envío, stock, vendedor y condición confirmados. | El precio puede cambiar al finalizar la compra. | **P1 · Modelo de oferta ampliado pendiente** |
| F17 | `isVerified` significa que la observación pasó validaciones del proveedor, no que el comercio confirme el checkout. | El término «verificado» puede interpretarse como precio final garantizado. | **P0 · Cambiar a estados de procedencia explícitos** |
| F18 | La deduplicación por tienda/producto/día se hace con consulta seguida de inserción, sin una restricción única atómica. | Dos consultas simultáneas podrían registrar duplicados. | **P1 · Transacción Room para insertar de forma atómica; prueba de concurrencia añadida en esta rama** |
| F19 | Un precio antiguo puede seguir apareciendo en Mercado sin un indicador fuerte de antigüedad. | El usuario puede confundir una observación anterior con una oferta actual. | **P1 · Parcial: observaciones de más de 24 h llevan aviso y se muestran como potencialmente desactualizadas** |
| F20 | Los diagnósticos de rechazos, cuotas y conectores no se presentan de manera uniforme. | Un resultado vacío puede parecer un fallo del buscador o falta de ofertas, sin explicar la causa. | **P2 · Observabilidad y mensajes pendientes** |

## Plan de trabajo — 22 fases ordenadas por riesgo

Los cambios se entregan por PR pequeños, con pruebas. No se marca una fase como terminada solo por compilar: debe cumplir su criterio verificable.

| Fase | Alcance | Criterio de salida | Estado |
|---|---|---|---|
| 01 | **Purga de datos heredados inválidos.** Eliminar observaciones antiguas de segunda mano, renting, promoción y precios imposibles; borrar los productos que solo dependan de ellas y evitar que aparezcan durante la limpieza asíncrona. | Una instalación con el MacBook de Wallapop a 67,50 € y un iPhone de demo ya no los emite en Mercado ni en la ficha. Un producto con observación válida sobrevive. | **En corrección en esta rama** |
| 02 | Unificar el estado de ficha: sin observación elegible no hay precio, gráfico, mínimo ni texto que lo llame real. Separar «observado por proveedor» de «confirmado en tienda». | Ningún componente mezcla etiqueta de demostración con precio/historial real; pruebas de UI/domain. | Pendiente |
| 03 | Filtro compartido de condición/vendedor/dominio para nuevas búsquedas, caché, historial y ofertas del comparador. | Una URL de marketplace no pasa aunque el nombre visible del vendedor sea distinto. | **Parcial en esta rama** |
| 04 | Coincidencia de consulta también en el motor de Amazon y rechazo explícito de merchandising personalizado. | Test de la consulta no devuelve un MacBook para una batería; el artículo de logo impreso se elimina. | **En corrección en esta rama** |
| 05 | Matching más estricto de tipo de producto y señales de modelo, sin perjudicar búsquedas genéricas válidas como UGREEN o Milwaukee. | Suite con marcas, erratas, accesorios y modelos parecidos; tasa de falsos positivos reducida. | **En progreso** |
| 06 | Análisis de precios anómalos por categoría/modelo y comparación robusta; no depender solo de un mínimo fijo universal. | MacBook a precio de funda, cuota mensual o unidad promocional se rechaza con causa diagnosticable. | Pendiente |
| 07 | Identidad de producto con GTIN/EAN, MPN/SKU, familia, capacidad, color, talla y condición cuando existan. | Ofertas solo se agrupan al compartir la identidad/configuración correcta; modelos cercanos no se mezclan. | Pendiente |
| 08 | Normalización de comerciantes y enlaces; preservar URL real del vendedor cuando exista y distinguir los enlaces indirectos de Google. | Conteos sin duplicar alias; cada oferta indica si abre la tienda o Google Shopping. | Pendiente |
| 09 | Capa de proveedores: búsqueda combinada de Google Shopping y Amazon con control de cuota, caché y deduplicación. | Ambas fuentes se pueden comparar en una consulta, dejando claro que una búsqueda combinada consume cuota adicional. | Pendiente |
| 10 | Adaptador oficial de eBay Browse API, solo con credenciales y acceso autorizados. Filtro de condición «nuevo» cuando corresponda. | Pruebas de condición, moneda, URL, vendedor y expiración de anuncio; integración inactiva si faltan credenciales. | Pendiente |
| 11 | Integraciones de feeds de afiliación/retailer autorizadas. Elegir proveedores según cobertura en España, términos y permisos reales. | Cada conector documenta su autorización, campos, límites y frecuencia; sin scraping encubierto. | Pendiente |
| 12 | Precio de compra final: envío, impuestos indicados por el proveedor, disponibilidad y condición. | El comparador separa precio del artículo, envío estimado y precio final conocido; desconocido se muestra como tal. | Pendiente |
| 13 | Modelo de procedencia: proveedor, comercio, URL, moneda, condición y estados de validación claramente separados. | El usuario distingue resultado de búsqueda, observación guardada y confirmación del comercio. | Pendiente |
| 14 | Deduplicación atómica en Room y registro de un lote de búsqueda como operación consistente. | Consultas concurrentes no duplican el mismo producto/comercio/día. | **Implementación y prueba de concurrencia en esta rama** |
| 15 | Frescura de precio y política de expiración visual; volver a consultar antes de afirmar que una oferta sigue actual. | Los datos antiguos aparecen marcados como antiguos y no reciben insignias de oferta actual. | **Parcial: aviso después de 24 h; expiración y reconsulta automática pendientes** |
| 16 | Historial real, agregación diaria, gráfico de una observación y evolución por fechas. | El primer punto se muestra sin inventar una curva y se conservan observaciones anteriores. | Parcial |
| 17 | Caché versionada por filtros/fuente, paginación real si el proveedor lo permite y gestión de cuotas. | Cambios de filtros no reutilizan resultados obsoletos; el límite del proveedor se explica. | Parcial |
| 18 | Diagnósticos por fuente: cantidad recibida, rechazada por relevancia/moneda/condición/enlace y errores de API. | La pantalla puede explicar por qué hubo cero resultados sin mostrar ofertas inseguras. | Parcial |
| 19 | Seguridad: claves fuera del repositorio/APK, permisos mínimos, redacción de logs, política de privacidad y eliminación de datos locales. | Secretos ausentes del código/logs y pruebas de eliminación/exportación de datos. | Pendiente |
| 20 | Actualización en segundo plano con WorkManager, conectividad, batería, límites del proveedor y reintentos con backoff. | La consulta se ejecuta solo bajo condiciones configuradas y respeta cuota. | Pendiente |
| 21 | Alertas de precio basadas únicamente en observaciones elegibles recientes, sin simulaciones ni datos de demostración. | Un aviso se dispara con un evento real auditable y preferencias explícitas del usuario. | Pendiente |
| 22 | Pruebas integradas, migraciones, accesibilidad, consumo de cuota, compilación reproducible y flujo de publicación. | Build, pruebas y revisión manual pasan en una instalación nueva y una instalación actualizada. | Pendiente |

## Fuentes reales de precio: alcance y límites

- **Google Shopping mediante SerpApi:** agregador multi-comercio, pero sus resultados no equivalen a 33 APIs directas; la vista actual puede devolver alrededor de 40 resultados y no admite `start` útil para paginar. [Documentación de SerpApi](https://serpapi.com/google-shopping-api).
- **Amazon.es mediante SerpApi:** búsqueda pública separada. No accede a cupones personales, cuenta Prime ni garantiza el precio final en checkout.
- **eBay Browse API:** fuente candidata para artículos nuevos, previa aprobación/credenciales; hay que filtrar la condición de manera estructurada. [Documentación de eBay Browse API](https://developer.ebay.com/api-docs/buy/static/api-browse.html).
- **Feeds de tiendas y afiliación:** candidatos solo cuando exista acuerdo, acceso permitido y estructura suficiente para precio/stock/URL. No se simulan como conectores mientras sean meros enlaces.
- **Tiendas restantes del directorio:** siguen siendo destinos de búsqueda manual, no fuentes automáticas de precio.

## Validación del trabajo activo

La rama de esta auditoría corrige F01/F03/F04/F05 y el etiquetado engañoso del número de fuentes (F06). Se han añadido pruebas de regresión para ofertas heredadas de Wallapop con `isVerified=1`, filtros de merchandising promocional y relevancia en la búsqueda de Amazon. No se considera terminado hasta que GitHub Actions pase las pruebas unitarias y `assembleDebug`, y se pueda comprobar el APK con datos antiguos en el móvil.
