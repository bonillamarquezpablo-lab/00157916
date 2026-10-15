# Plan de datos para un historial de precios de hasta cuatro años

**Objetivo:** recuperar precios antiguos cuando exista una fuente trazable y combinar esos datos con observaciones propias, sin inventar puntos ni presentar una cobertura incompleta como si fueran cuatro años completos.

## Hallazgo prioritario: ya existe una fuente que conviene probar

### 1. SerpApi — Google Product API / `price_insights`

- Documentación: https://serpapi.com/google-product-api
- La respuesta puede incluir `product_results.price_insights`, con moneda y series `stores[].prices[]` que contienen fecha y precio por comercio.
- Es la primera prueba a realizar porque Mínimo ya usa SerpApi y la misma clave se puede usar para consultar `engine=google_product` mediante el `product_id` que devuelve Google Shopping.
- **Límite importante:** el proveedor documenta que el historial puede aparecer cuando Google lo muestra, pero no garantiza públicamente cuatro años de cobertura para cada producto o tienda. La disponibilidad y la profundidad se deben medir por producto; no se debe asumir que toda la respuesta contiene cuatro años.
- Cada punto importado debe conservar el comercio y la fecha indicados por el proveedor, junto con la fecha de importación y la referencia del producto Google. El punto no debe registrarse como si Mínimo lo hubiera observado ese día.

## Fuentes complementarias

### 2. PricesAPI (pricesapi.io) — candidato multi-tienda para España

- Documentación: https://pricesapi.io/docs
- La documentación lista el mercado español (`es`) y una API `Price History` que recupera ofertas retenidas por producto, comercio y fecha.
- La búsqueda de catálogo permite consultar por nombre/vendedor y devuelve un resumen de cobertura retenida (primera/última fecha y días con ofertas) **sin gastar créditos**. Es el primer paso recomendado para medir si merece la pena importar un producto.
- La API admite ventanas de hasta 1.500 días (aproximadamente 4,1 años). El coste documentado del historial es de 1 crédito por día con ofertas; la solicitud puede requerir reservar saldo para toda la ventana, aunque reembolse los días sin ofertas.
- **Límite importante:** la propia documentación dice que la profundidad varía por producto y mercado y que esta función está en beta pública. No garantiza cobertura de cuatro años para un artículo concreto. Antes de activarla hay que consultar la cobertura gratuita de catálogo, estimar el coste y obtener autorización para gastar créditos.
- Es la candidata más interesante que he encontrado para un histórico agregado multi-tienda en España; debe compararse con resultados reales de Mínimo antes de decidir.

### 3. PriceAPI / Idealo — alternativa comercial europea

- Documentación: https://readme.priceapi.com/reference/idealo
- El proveedor PriceAPI ofrece fuente Idealo para España (`es`) y documenta datos de ofertas, productos y precio histórico; la fuente se solicita al soporte y el uso se factura en créditos.
- Tiene sentido evaluarlo si PricesAPI no conserva suficiente profundidad en modelos relevantes, pero hay que solicitar condiciones y confirmar cobertura/coste antes de integrarlo.

### 4. Keepa API — historial especializado de Amazon.es

- Documentación: https://keepa.com/api-docs/
- Keepa entrega historial de precios de mercados Amazon, incluyendo datos por producto/ASIN. Su documentación describe una API con consumo de tokens y planes de pago: https://keepa.com/api-docs/plans-tokens.html
- La documentación de ofertas identifica el mercado español como `domainId=9`: https://keepa.com/api-docs/deals.html
- **Uso recomendado:** fuente especializada para Amazon.es y principal alternativa cuando Google no ofrezca un historial suficientemente profundo.
- **Límites:** requiere una cuenta y clave de API con saldo; el precio, disponibilidad y profundidad deben validarse por ASIN. No cubre de forma general todas las tiendas españolas. Antes de integrar, revisar coste por volumen y condiciones de uso, y mantener la clave fuera del APK en una versión pública.

### 5. Internet Archive / Wayback Machine — capturas de páginas

- Documentación del índice CDX: https://github.com/internetarchive/wayback-cdx-server
- Permite buscar capturas históricas de URL en intervalos de fechas, pero una captura no equivale a una base de precios estructurada.
- **Implementación de búsqueda gratuita:** la app consulta hasta 1.600 días de capturas de URLs directas HTTPS. Si la búsqueda actual solo tiene un enlace intermediario de Google Shopping o la URL exacta no ofrece precios antiguos, puede hacer una segunda búsqueda CDX gratuita en el dominio de un comercio reconocido, filtrando URLs por términos distintivos del producto. Para limitar carga se consulta un máximo de 80 filas de índice, se descargan hasta 12 capturas por candidato y se priorizan hasta dos comercios por búsqueda. La interfaz de ficha incluye «Buscar histórico gratis · Internet Archive»; no lee la clave ni consume cuota de SerpApi.
- Se aceptan únicamente precios EUR positivos que aparezcan en metadatos de producto verificables y cuyo título coincida con el producto buscado. Las páginas archivadas que no expongan un precio fiable se descartan.
- La base de datos distingue `observedAt` (fecha de la captura), `collectedAt` (fecha de importación), `evidenceType=archive`, la URL original y la URL de la captura. Los precios archivados no pueden reemplazar la oferta actual; los puntos quedan identificados en el historial. La caché evita repetir intentos sobre la misma URL o comercio durante 30 días.
- **Límites:** no se garantiza ningún número mínimo de puntos ni cobertura de 1.600 días. La consulta por dominio es una búsqueda aproximada, limitada y condicionada por la cobertura del archivo; aunque encuentre una página, puede que esta no contenga un precio EUR legible o que el título no permita verificar la variante exacta. No se inventan ni interpolan precios ausentes.

### 6. Feeds y acuerdos directos con tiendas

La opción de mayor calidad a largo plazo son feeds, APIs de afiliación o acuerdos de datos que permitan explícitamente registrar y conservar precios históricos. Deben evaluarse tienda por tienda; disponer de una URL pública no implica permiso para crear una base de datos histórica comercial.

## Arquitectura requerida antes de importar historia antigua

La tabla `price_observations` conserva fecha de observación, fecha de adquisición, tipo de evidencia, comercio, moneda, URL de captura, URL original y estado de validación. La migración a la versión 8 incorpora `collectedAt`, `evidenceType` y `sourcePageUrl`. Las futuras fuentes estructuradas (por ejemplo, una API histórica externa) aún tendrán que incorporar su identificador externo de producto y una validación de coincidencia estricta antes de importar datos.

Las observaciones en vivo anteriores al límite temporal del catálogo siguen excluidas. Las capturas archivadas y los puntos históricos de Google solo pueden entrar por sus rutas específicas, con `evidenceType` explícito, precio EUR verificable, fecha original y comprobación estricta de producto; no se debe relajar el filtro global ni aceptar filas heredadas/sintéticas sin procedencia fiable.

## Reglas de calidad del historial

1. Separar siempre producto, modelo, variante, capacidad, color, región, moneda, nuevo/usado y vendedor cuando esos atributos cambien la comparabilidad.
2. No mezclar precios usados, reacondicionados, alquiler, financiación, cupones exclusivos ni precios sin envío/IVA conocido con el precio normal nuevo.
3. No extrapolar ni interpolar fechas ausentes. Los gráficos deben unir solo puntos documentados y mostrar huecos/cobertura de forma honesta.
4. La etiqueta «mínimo de cuatro años» solo se habilita si los datos válidos abarcan al menos cuatro años reales y tienen suficientes observaciones. Si la cobertura empieza hace tres meses, decir «mínimo observado desde [fecha]».
5. Calcular mínimos y medias por periodo con la serie real seleccionada; nunca confiar en campos precargados o valores cacheados para etiquetar un gráfico.
6. Comparar los datos retrorecuperados con una búsqueda actual del mismo comercio antes de aceptar una coincidencia. Conservar las divergencias para revisión, no forzar coincidencias por título genérico.

## Secuencia de implementación recomendada

- **Fase A — integridad del gráfico:** calcular todos los resúmenes con los puntos reales del rango visible; no llamar «mínimo» a una única observación; probar vacíos, duplicados, precios inválidos y periodos.
- **Fase B — prueba de cobertura sin coste accidental:** con una sola consulta manual de SerpApi, evaluar una muestra pequeña de productos populares en España y medir antigüedad de la primera/última fecha, número de puntos, comercios y moneda. No lanzar barridos automáticos.
- **Fase C — importación de proveedor:** crear un identificador externo persistente, parser probado y migración con `collectedAt` y tipo de evidencia. Importar solo precios históricos realmente devueltos por la fuente.
- **Fase D — Amazon.es:** contrastar Keepa y calcular coste por ASIN/mes antes de habilitarlo.
- **Fase E — cobertura colectiva:** para productos sin historia externa, comenzar observación diaria desde el primer registro. Un historial multiusuario de cuatro años requerirá un backend central y política de conservación; una base local nueva no puede recuperar por sí sola precios que nunca guardó.

## Criterio de éxito

Mínimo no debe prometer que todo producto dispone de cuatro años. Debe intentar recuperar ese historial cuando exista una fuente verificable, decir qué cobertura consiguió en cada producto y mantener visible la diferencia entre **historial externo recuperado** y **precio observado por Mínimo**.

## Observación periódica local de productos seguidos

La aplicación programa un trabajo periódico de WorkManager cada 24 horas (ejecución aproximada, condicionada a que Android tenga conexión). El trabajo recorre la lista de productos seguidos y consulta primero los que llevan más tiempo sin actualizarse.

- **Límite de fondo:** máximo 3 consultas de producto por día local; también se respeta la cuota restante y el límite horario que devuelve SerpApi.
- **Rotación:** la selección prioriza los productos con la consulta más antigua para que una lista grande avance gradualmente, sin favorecer siempre a los primeros.
- **Ámbito:** solo productos que el usuario ha añadido a Seguidos. No se barre el catálogo completo ni se lanzan búsquedas masivas.
- **Historial independiente de las alertas:** no se necesita permiso de notificaciones ni un precio objetivo para guardar observaciones válidas. Esas preferencias solo determinan si se muestra una notificación de bajada.
- **Requisitos:** conexión, clave SerpApi válida, saldo/límite horario disponible y que Android ejecute la tarea de WorkManager. El primer inicio de la aplicación debe registrar el trabajo.
- **Datos honestos:** se conserva una observación solo cuando SerpApi devuelve un precio válido y el repositorio acepta el producto, la tienda, la moneda y la fecha. Una ejecución sin oferta válida no crea un punto inventado.

El límite de tres por día es intencionadamente conservador; por tanto, una lista de muchos productos puede tardar varios días en completar una rotación. WorkManager no garantiza una ejecución exacta a una hora concreta. La cuota diaria local limita el uso de fondo, mientras que el límite real de SerpApi sigue siendo la autoridad final.
