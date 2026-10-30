# Ficha técnica completa — motor de recuperación de precios históricos de Mínimo

> **Documento técnico de referencia del proyecto.** Describe qué hace el motor, cómo lo hace, qué controles aplica, qué evidencia existe, qué límites tiene y qué falta para considerarlo validado. La ficha debe poder servir tanto para mantenimiento como para diagnosticar una búsqueda fallida sin tener que reconstruir el contexto a partir de conversaciones.
>
> **Regla de evidencia:** que una función exista en el código no demuestra que funcione en todas las tiendas. Que una prueba unitaria pase no demuestra que Internet Archive esté disponible. Que una captura se descargue no demuestra que su precio sea correcto. Que se extraiga un precio no demuestra que Room lo haya guardado. Cada nivel se documenta por separado.

**Revisión documental:** 30 de octubre de 2026.  
**Rama revisada:** \`main\`.  
**HEAD de \`main\` consultado:** \`723c3569b3b7c574677664ffcdf5fd4801dbfbcb\`.  
**Versión que aparece en los dos diagnósticos recientes del dispositivo:** \`1.7.1055.1\` (Samsung Galaxy A18 y Samsung Galaxy A27 5G).  
**Última APK verificada que esta ficha puede acreditar expresamente:** \`1.7.1013.1\`, run #1013; es una evidencia histórica y **no** debe presentarse como APK actual de \`main\`.  
**CI más reciente consultada:** run #1070, ID \`40990640592\`, iniciado sobre el SHA \`d2d2f3f715c71d97aa7c60243397f4ed14b23957\`. En el momento de esta revisión seguía en curso. \`main\` está tres commits por delante de ese SHA (\`723c3569...\`); por tanto, aunque el run termine correctamente, no certificará por sí solo esos tres cambios posteriores.  
**Datos de ejecución recientes:** Android 17 / API 37, según los informes facilitados desde el dispositivo.  
**Configuración Android del proyecto consultada:** minSdk 24, compileSdk 36.1 y targetSdk 36.  
**Estado global:** el motor ha demostrado que puede recuperar y guardar histórico real en dispositivo (caso Xiaomi Mi 10 Pro Gris, 28/10/2026). Los dos diagnósticos más recientes del 30/10/2026 quedaron limitados por respuestas externas HTTP 429/503 antes de descargar HTML. No existe todavía una tasa global de éxito, precisión o cobertura estadísticamente válida.  
**Integridad de datos:** no borrar, reinicializar ni reconstruir la base Room para solucionar problemas de consulta. Las correcciones del motor deben preservar fichas, precios en vivo y observaciones archivadas existentes.

---

## Índice

1. Objetivo, alcance y principios
2. Estado ejecutivo y niveles de evidencia
3. Arquitectura del sistema y responsabilidades
4. Fuentes de información y separación de tipos de precio
5. Flujo completo de una búsqueda histórica
6. Descubrimiento de capturas: CDX, URL directa y recuperación
7. Tratamiento de URL, identidad canónica y destino comercial
8. Descarga y lectura del documento archivado
9. Extracción de ofertas y precios
10. Identidad de producto, variante, tienda y condición
11. Validación temporal, monetaria y económica
12. Persistencia en Room e integridad histórica
13. Gráfica, series y cálculo del mínimo
14. Telemetría: informe v2 de 30 fases
15. Concurrencia, tiempos de espera, reintentos y protección de cuota
16. Caché, cooldown y circuit breakers
17. Interpretación de errores y estados
18. Límites técnicos y parámetros de ejecución
19. Seguridad, privacidad y tratamiento de datos de diagnóstico
20. Batería de pruebas y estrategia de validación
21. Métricas de calidad que deben medirse
22. Incidencias abiertas y riesgos conocidos
23. Procedimiento de diagnóstico operativo
24. Criterios de aceptación para cerrar una mejora
25. Reglas de mantenimiento del proyecto
26. Glosario técnico
27. Evidencia de uso real más reciente (30/10/2026)

---

## 1. Objetivo, alcance y principios

### 1.1 Qué hace

El motor de histórico gratuito intenta localizar páginas antiguas de una ficha de producto en archivos públicos de Internet. Recupera el documento de una fecha concreta, identifica la oferta que corresponde al producto y a la tienda seleccionados, extrae su precio, valida el dato y lo guarda como observación histórica.

El resultado útil no es simplemente «encontrar una página antigua». El resultado útil es una observación con:

- producto y variante identificados;
- comercio de origen correcto;
- URL de producto y URL de captura coherentes;
- fecha de captura verificable;
- precio y moneda identificados;
- condición del artículo compatible con la ficha;
- evidencia archivada reconocible;
- persistencia confirmada en la ficha de producto adecuada.

La fecha guardada es la fecha de la captura archivada. **No significa que la tienda siga vendiendo el producto a ese precio ni que la oferta siga disponible hoy.**

### 1.2 Qué no hace

El motor no es un rastreador ilimitado de toda la web, no puede obligar a Internet Archive o Arquivo.pt a responder y no puede reconstruir un precio que nunca quedó archivado. Tampoco debe convertir una búsqueda de una tienda o categoría en un precio concreto sin demostrar qué artículo de la página produjo el importe.

No se debe afirmar que un producto carece de histórico cuando una ruta de consulta ha fallado por timeout, HTTP 429, HTTP 503, error de red o respuesta parcial. Una respuesta vacía solo permite afirmar que esa consulta concreta respondió correctamente sin encontrar capturas.

### 1.3 Principios no negociables

1. **Identidad antes que precio.** Un precio bajo de otro modelo, capacidad, color relevante, vendedor o estado de conservación no es un dato válido.
2. **Evidencia antes que inferencia.** No convertir un número encontrado en HTML en un precio automáticamente.
3. **Proveniencia separada.** Precio actual de Mínimo, captura Wayback, captura Memento y dato histórico externo de Google son clases distintas.
4. **Fecha antes que duplicado.** El mismo precio en días diferentes puede representar observaciones históricas distintas. Un duplicado archivado se determina en el ámbito de producto, tienda y día local, no por igualdad del importe a lo largo de toda la vida del producto.
5. **Hallazgo no equivale a guardado.** Capturas, HTML, ofertas, precios aceptados, duplicados y filas nuevas son contadores diferentes.
6. **Una omisión no es un fallo.** Una fase no aplicable, una caché válida o una pausa por cuota no se debe registrar como fallo de extracción.
7. **Una búsqueda incompleta no es un histórico vacío.** Un resultado exacto vacío no tapa un prefijo que falló.
8. **No dañar Room.** No borrar históricos válidos, no recrear el catálogo y no reemplazar observaciones buenas con datos dudosos.
9. **No sacrificar precisión por cobertura.** Una búsqueda con menos precios, pero correctos, es preferible a una gráfica contaminada.
10. **No inventar métricas.** Los porcentajes solo se publican cuando existen numerador, denominador, muestra, fecha y método.

---

## 2. Estado ejecutivo y niveles de evidencia

### 2.1 Estado resumido por subsistema

| Subsistema | Estado según el código y la evidencia disponible | Qué se puede afirmar |
|---|---|---|
| Consulta directa Wayback CDX | Implementada; probada con tests y casos reales | Puede localizar índices de capturas de URLs concretas. Su disponibilidad depende del servicio remoto. |
| Prefijo, rutas alternativas y recuperación CDX | Implementadas con límites, timeouts y circuitos | Hay recuperación acotada; no equivale a un rastreo completo de todas las páginas del comercio. |
| Availability API de Internet Archive | Implementada | Puede aportar una captura candidata; también puede responder sin capturas o con errores/429. |
| Memento / Arquivo.pt | Cliente independiente implementado | Consulta TimeMap y, en ciertos casos, su índice CDX; obtiene el documento archivado para el extractor compartido. |
| Descarga de HTML archivado | Implementada | Se ha demostrado descarga HTTP 200 de capturas reales en el caso Phone House/Xiaomi. |
| Extracción JSON-LD Product/Offer | Implementada | Existen parsers y pruebas para datos estructurados; no todas las páginas conservan ese marcado. |
| Extracción de precio visible | Implementada con rutas específicas | Existe un parser específico de Phone House y parsers generales; no debe generalizarse un selector de una tienda a todas. |
| Identidad y variante | Implementada y con regresiones | Hay controles de modelo, almacenamiento, tokens de variante, URL, tienda y condición; falta cuantificar precisión con una muestra amplia etiquetada. |
| Conversión USD→EUR histórica | Implementada | La conversión usa contexto de fecha del precio; no debe presentarse como un tipo de cambio actual aplicado retroactivamente. |
| Guardado Room | Implementado y probado en persistencia | Hay un caso real con tres puntos nuevos guardados y pruebas de reinicio/deduplicación. |
| Gráfica histórica | Implementada | Se distinguen series y ventanas temporales; una observación no basta para afirmar un mínimo comparable. |
| Informe de diagnóstico | Formato v2 con 30 fases y detalle por captura | El formato actual es de 30 fases generales; los informes antiguos de 22 fases pertenecen a versiones anteriores del diagnóstico. |
| Ritmo y protección ante 429 | Implementados | Existe separación de cooldowns, intervalo mínimo y circuitos. No se puede garantizar que el servicio externo nunca limite las peticiones. |
| Eficacia global | **Sin medir** | No existe una tasa global demostrada de búsquedas que añaden fechas nuevas. |
| Precisión global de identidad | **Sin medir** | Faltan una muestra amplia etiquetada y métricas de falsos positivos/falsos negativos. |
| Rendimiento global | **Sin medir** | Faltan mediana y p95 consolidados por fase, producto y comercio. |
| Estabilidad a largo plazo | **Sin medir** | Hay pruebas de regresión, pero aún no hay una serie estadística de fallos por cada 100 búsquedas. |

### 2.2 Escala obligatoria de evidencia

- **Existe en código:** la implementación está presente. No implica que esté libre de defectos.
- **Cubierto por prueba automatizada:** hay al menos una prueba reproducible del comportamiento concreto.
- **Integrado en CI:** la ejecución de GitHub Actions correspondiente terminó correctamente en el SHA indicado.
- **Verificado en APK:** la APK se compiló desde ese SHA y pasaron las comprobaciones de paquete, versión, firma y componentes esperados.
- **Probado en dispositivo:** se ejecutó la búsqueda en un teléfono y se conserva el informe real.
- **Persistencia demostrada:** el repositorio/Room confirmó la inserción o el duplicado válido. Para demostrar que la gráfica creció, además debe haber puntos nuevos.
- **Eficacia medida:** existe un conjunto de búsquedas y denominadores que permiten calcular el indicador.
- **Pendiente / no demostrado:** no hay evidencia suficiente para cerrar la afirmación.

Una mejora puede estar implementada y tener tests, pero continuar pendiente de validación real. La ficha no debe subir su nivel de evidencia automáticamente porque aumente el número de versión.

---

## 3. Arquitectura del sistema y responsabilidades

### 3.1 Componentes principales

| Componente | Archivo principal | Responsabilidad |
|---|---|---|
| Coordinación de la búsqueda | \`PriceTrackerViewModel.kt\` | Decide producto y comercio objetivo, ordena candidatos, aplica límites, coordina Wayback/Memento y actualiza el diagnóstico. |
| Cliente Internet Archive | \`WaybackPriceHistoryClient.kt\` | Construye consultas CDX/Availability, interpreta capturas, descarga HTML, extrae y valida cotizaciones archivadas. |
| Cliente Memento | \`MementoClient.kt\` | Consulta TimeMap y CDX nativo de Arquivo.pt, selecciona capturas y recupera el documento de contenido. Reutiliza el parser/validador compartido. |
| Repositorio de productos | \`ProductRepository.kt\` | Comprueba que la observación pertenece a un producto existente, aplica validaciones finales y coordina el guardado. |
| Persistencia | \`PriceObservationEntity.kt\`, \`PriceObservationDao.kt\`, Room | Conserva precio, fecha, tienda, procedencia, URLs y estado de verificación. Aplica consultas e inserciones protegidas. |
| Modelo de gráfica | \`PricePoint.kt\`, \`PriceHistoryAnalytics.kt\` | Representa las observaciones y calcula resúmenes sin inventar puntos. |
| Gráfica | \`PriceHistoryChart.kt\` | Presenta series Mínimo, Histórico, Combinado y Google histórico con sus rangos temporales. |
| Diagnóstico visual y exportación | \`ProductDetailSheet.kt\` | Presenta fases, causas, resumen, informe completo y datos redactados. |
| Pruebas | \`app/src/test\` | Cubre consulta, identidad, extracción, persistencia, deduplicación, gráfica y presentación del diagnóstico. |

### 3.2 Flujo de datos

\`\`\`text
Producto guardado y URL de tienda
            |
            v
PriceTrackerViewModel
  - identifica el producto elegido
  - reúne URL directa y alternativas elegibles
  - inicializa 30 fases de diagnóstico
            |
            v
WaybackPriceHistoryClient
  - precontrol de cooldown/caché
  - consulta CDX exacta y prefijo
  - recuperación limitada / Availability
  - selección de capturas
  - descarga HTML archivado
  - extracción y validación
            |
            +------------------------------+
            |                              |
            v                              v
MementoClient                    Cotizaciones verificadas
  - TimeMap                          (con origen/fecha/URL)
  - CDX Arquivo.pt                         |
  - replay mp_                              |
  - parser compartido                       |
            |                                |
            +---------------+----------------+
                            |
                            v
             ProductRepository
              - producto objetivo
              - comercio y URL
              - fecha y moneda
              - modelo/variante/condición
              - precio plausible
                            |
                            v
                   Room / PriceObservationDao
                    - inserción
                    - rechazo motivado
                    - duplicado confirmado
                            |
                            v
                  Product / historial observado
                            |
                            v
                    Gráfica y diagnóstico
\`\`\`

### 3.3 Aislamiento del flujo de precio actual

La búsqueda de ofertas actuales y el motor de histórico son rutas distintas:

- SerpApi / búsqueda comercial recupera ofertas actuales y puede guardar observaciones actuales.
- Wayback/Memento recuperan páginas de fechas pasadas.
- Google Price Insights, cuando existe, es evidencia histórica externa separada.
- Las capturas archivadas no deben alimentar el precio actual, las alertas de precio ni el estado de disponibilidad.

Que el respaldo de precios actuales guarde ofertas no significa que el histórico se haya ejecutado. Es lo que muestran los informes recientes del Galaxy A18 y A27: hubo guardado de ofertas actuales, mientras el histórico quedó pausado o incompleto.

### 3.4 Dependencias técnicas relevantes

Versiones encontradas en el catálogo Gradle consultado:

| Dependencia | Versión |
|---|---:|
| Kotlin | 2.4.21 |
| Kotlin Coroutines | 1.11.0 |
| Room | 2.8.5 |
| OkHttp | 5.3.2 |
| Jsoup | 1.23.2 |
| Chucker | 4.3.1 |
| Compose BOM | 2026.06.01 |

Estas versiones son la fotografía del catálogo consultado en esta revisión. Deben actualizarse al modificar el catálogo, no copiarse automáticamente de una rama o conversación anterior.

---

## 4. Fuentes de información y separación de tipos de precio

| Fuente | Qué aporta | Cómo se obtiene | Qué no demuestra |
|---|---|---|---|
| Internet Archive CDX | Índice de capturas: timestamp, URL original y metadatos de la respuesta | Consulta al índice CDX | No contiene por sí mismo el precio ni garantiza que el HTML pueda descargarse. |
| Internet Archive Availability | Captura cercana a una fecha para una URL | API de disponibilidad | No es un índice exhaustivo de todo el histórico de una tienda. |
| Wayback replay | Documento archivado de una captura | URL de captura con timestamp y modificador de replay | El replay puede fallar, redirigir o devolver una envoltura; hay que validar el documento y su destino. |
| Arquivo.pt Memento TimeMap | Lista temporal de recursos archivados | TimeMap Link-format | La respuesta puede estar vacía, no disponible o limitada por cuota. |
| Arquivo.pt CDX | Índice alternativo para URL concreta | Endpoint CDX de Arquivo.pt | No es un rastreo completo del dominio. |
| Arquivo.pt replay \`mp_\` | Contenido archivado real de una captura | URL Memento transformada a replay de contenido | No se debe confundir con la página envoltorio del replay. |
| Google Price Insights | Observaciones históricas de un proveedor externo, cuando se reciben | Integración de catálogo/insights | No son capturas observadas por Mínimo ni cotizaciones en vivo. |
| SerpApi / oferta de tienda | Precio actual comunicado por un proveedor o comercio | Búsqueda comercial actual | No es evidencia de precio histórico archivado. |

### 4.1 Regla de procedencia

Cada punto conservado debe poder distinguirse por su tipo de evidencia. La etiqueta de una fila no basta: también se comprueba la forma de la URL de origen. Una fila etiquetada como archivo, pero que contiene una URL normal de tienda, no se debe contar como captura real.

El sistema reconoce formatos de captura como Wayback \`id_\` y las variantes relevantes de Arquivo.pt, incluidos \`mp_\`. Las reglas del DAO y del repositorio deben mantenerse sincronizadas: el contador de histórico, el listado de histórico y la deduplicación no pueden usar definiciones contradictorias.

---

## 5. Flujo completo de una búsqueda histórica

La siguiente secuencia describe el flujo lógico. No todas las ramas se ejecutan en todos los intentos: existen cortocircuitos intencionados para evitar peticiones innecesarias.

1. **Inicio explícito.** El usuario solicita buscar histórico desde una ficha de producto.
2. **Resolución de ficha.** Se comprueba que el producto elegido sigue existiendo y que dispone de una URL o una oferta directa utilizable.
3. **Identidad inicial.** Se conserva el título esperado, el identificador del producto seleccionado y la información de variante disponible.
4. **Preparación de URL.** Se descompone la URL para separar dominio, ruta, parámetros de seguimiento, parámetros que identifican variante y posibles redirecciones.
5. **Precontrol.** Se comprueba si Wayback está deshabilitado, limitado por cooldown, dentro de una caché reciente o afectado por un circuito de servicio no disponible.
6. **Descubrimiento directo.** Se consulta CDX exacto y, cuando procede, la ruta de prefijo.
7. **Recuperación limitada.** Si una consulta amplia agota el tiempo, se pueden intentar URL alternativa o ventanas temporales acotadas. Si hay HTTP 429, el proceso se detiene y no se amplía la búsqueda.
8. **Fallback de disponibilidad.** Cuando procede, se consulta Availability con URLs específicas y un conjunto limitado de fechas.
9. **Fuente alternativa.** Memento/Arquivo.pt puede ejecutarse como fuente independiente si no se ha alcanzado suficiente histórico y no está bloqueado por su propio cooldown.
10. **Consolidación de candidatos.** Se eliminan candidatos repetidos por identidad de captura y se conserva el origen de cada ruta.
11. **Selección temporal.** Las capturas se filtran por la ventana de histórico y se muestrean para no descargar cada repetición del índice.
12. **Descarga de HTML.** Se solicita el documento archivado, con límites de tiempo y tamaño.
13. **Inspección del documento.** Se comprueba que el contenido sea HTML útil y se detectan barreras, páginas vacías o contenido no recuperable.
14. **Identificación de la oferta.** Se intenta relacionar Product/Offer, SKU/URL y contenido visible con el artículo esperado.
15. **Extracción.** Se obtiene el candidato de precio con su moneda y metadatos.
16. **Validación.** Se comprueban fecha, moneda, importe, comercio, modelo, capacidad, variante y condición.
17. **Conversión monetaria.** Si el precio está en USD y existe evidencia suficiente, se convierte usando el contexto de tipo de cambio correspondiente a la fecha histórica.
18. **Envío al repositorio.** Se entrega una cotización estructurada con fecha, precio, moneda original, cambio aplicado, URL de origen, URL del snapshot e identidad destino.
19. **Persistencia segura.** El repositorio vuelve a comprobar propiedad e identidad, cuenta rechazos y solicita a Room la inserción protegida.
20. **Refresco.** La ficha y la gráfica deben reflejar lo que realmente quedó en Room.
21. **Cierre diagnóstico.** Se registra el resultado, el estado de fases y el siguiente paso accionable.

### 5.1 Umbral de suficiencia

El código del ViewModel dispone de un criterio de suficiencia de histórico basado en fechas distintas y puntos realmente insertados. La política de finalización temprana contempla al menos tres fechas distintas verificadas y tres puntos nuevos insertados para considerar que ya existe una serie suficiente para evitar ciertas rutas alternativas.

**Importante:** este umbral es una condición de optimización de la búsqueda, no una promesa de que siempre se encontrarán tres fechas. Un duplicado confirma que un dato ya está en Room, pero no cuenta como punto nuevo para aumentar la gráfica ni para demostrar que la búsqueda amplió el histórico.

---

## 6. Descubrimiento de capturas: CDX, URL directa y recuperación

### 6.1 Consulta CDX directa

El cliente construye una URL de consulta para el recurso comercial concreto. En el flujo directo se intenta el modo exacto para evitar consultas amplias cuando esa dirección ya aporta el histórico suficiente.

Una respuesta CDX válida proporciona metadatos de captura. El cliente interpreta campos como fecha, URL original, código HTTP y tipo de contenido. El índice no equivale a una descarga de página.

### 6.2 Consulta por prefijo

La consulta de prefijo amplía el conjunto de URL relacionadas con la dirección de producto. Es útil cuando la tienda ha cambiado algunos parámetros o direcciones, pero puede ser más costosa y lenta.

La aplicación acota el tiempo de esta ruta y evita convertirla en un rastreo indefinido. Si se agota el tiempo, el resultado se clasifica como incompleto, no como «sin capturas».

### 6.3 URL alternativa y redirecciones

El descompositor de URL puede generar variantes directas controladas y conservar la relación con la página comercial. Las rutas alternativas deben ser del mismo producto y comercio o estar vinculadas por evidencia suficiente. No se debe ampliar a una página de búsqueda genérica para aprovechar un título parecido.

### 6.4 Recuperación anual/trimestral

Si una consulta amplia de prefijo agota el tiempo, existen rutas temporales acotadas por año o trimestre. El código actual limita el número de bloques trimestrales de una ejecución a dos, y también limita las capturas adicionales por ruta.

El objetivo es recuperar una muestra adicional sin convertir cada búsqueda en una consulta exhaustiva de miles de capturas. Una respuesta parcial con precios válidos no convierte automáticamente en completa la consulta de prefijo que falló.

### 6.5 Recuperación por dominio comercial

La exploración por dominio es una última alternativa para cuando la URL concreta ya no aparece o ha cambiado. Busca hosts y URLs de producto candidatas, pero aumenta el riesgo de mezclar fichas, categorías y variantes.

Por eso:
- se limita el número de hosts consultados;
- se limita el número de URL de producto candidatas;
- se puntúa la especificidad de la URL frente al título/modelo;
- se comprueba la identidad antes de aceptar el precio;
- se puede abrir un circuito cuando el backend CDX está dando fallos repetidos;
- una respuesta vacía válida no se interpreta igual que una respuesta 503 o timeout.

### 6.6 Availability API

La Availability API puede localizar una captura cercana a una fecha de referencia. El cliente trabaja con un conjunto pequeño de URL candidatas y fechas objetivo —reciente y más antigua—, y limita el número de probes.

La captura resultante sigue pasando por descarga, parser, identidad y validación. Availability no autoriza a guardar directamente su respuesta como precio.

### 6.7 Interpretación correcta de respuestas vacías

| Resultado de las consultas | Interpretación correcta |
|---|---|
| Todas las rutas consultadas respondieron correctamente y no encontraron captura | No se encontraron capturas en esas rutas consultadas. |
| CDX exacto respondió vacío y el prefijo también respondió vacío | No se encontraron capturas en esas consultas concretas. No demuestra que nunca existiera ninguna página archivada en todo el archivo. |
| CDX exacto respondió vacío y el prefijo falló por timeout/503 | Búsqueda incompleta. No afirmar que el histórico está vacío. |
| CDX encontró capturas pero falló la descarga HTML | Capturas localizadas; recuperación del documento fallida. |
| HTML descargado, pero no se pudo identificar el producto | Fallo o rechazo de identidad, no ausencia de captura. |
| HTML descargado y precio no válido | Problema de extracción o validación; registrar motivo. |
| Petición detenida por HTTP 429 | Pausa por límite del servicio; fases posteriores no ejecutadas. |

---

## 7. Tratamiento de URL, identidad canónica y destino comercial

### 7.1 Descomposición de una URL

El cliente descompone la dirección en:
- URL resuelta;
- URL canónica;
- esquema y host;
- dominio del comercio;
- ruta y segmentos;
- parámetros de consulta;
- parámetros que identifican variante;
- identificadores de producto;
- parámetros de seguimiento;
- fragmento;
- profundidad de redirección.

Esta separación es necesaria porque algunos parámetros solo siguen campañas publicitarias y otros cambian realmente el artículo o la variante.

### 7.2 Seguimiento frente a identidad

Un parámetro de seguimiento no debe provocar que una página de producto válida se considere diferente de su captura canónica. Se documentó y corrigió, entre otros, el tratamiento de \`srsltid\`.

Sin embargo, no se pueden eliminar indiscriminadamente todos los parámetros. Algunos comercios codifican color, SKU, capacidad, selector de marketplace o variante en la URL.

### 7.3 Casuística Phone House

Se mantiene una regla limitada al dominio \`phonehouse.es\`:
- \`colorgris=\` vacío se puede normalizar para la consulta archivada;
- un valor de color no vacío se conserva;
- el selector \`opt\` es información específica del comercio;
- no se debe trasladar esa normalización a otras tiendas.

La normalización de URL no puede eliminar una variante auténtica por intentar aumentar el número de capturas.

### 7.4 Identidad de la página archivada

La URL del snapshot debe contener una dirección de archivo reconocible, timestamp válido y destino comercial coherente. Se comprueba que el destino archivado coincida con la página original canónica asociada a la ficha.

Formatos relevantes documentados:
- Wayback: \`https://web.archive.org/web/{timestamp}id_/{url-original}\`;
- Arquivo.pt: \`https://arquivo.pt/wayback/{timestamp}/{url-original}\`;
- Arquivo.pt contenido: \`https://arquivo.pt/wayback/{timestamp}mp_/{url-original}\`.

No basta con que la cadena de URL contenga las palabras «archive» o «wayback». Se parsea el host, formato, timestamp y destino.

### 7.5 Reglas de comercio

La oferta debe pertenecer a la tienda de la URL validada. Una oferta de Amazon no debe acabar guardada en la ficha de PcComponentes solo porque título y modelo coincidan. Los enlaces intermediarios de Google Shopping o una URL de búsqueda no sustituyen al enlace directo de la tienda.

---

## 8. Descarga y lectura del documento archivado

### 8.1 Descarga del snapshot

La descarga solicita el documento de una captura con:
- timeout específico de conexión, lectura y llamada;
- límite de tamaño de respuesta;
- control de redirects/host;
- clasificación de HTTP y errores de transporte;
- caché local de resultados de descarga dentro de una misma búsqueda;
- concurrencia limitada.

Límite actual de cuerpo de snapshot de Wayback: 2 MiB. Si el cuerpo excede el límite, llega vacío o no es HTML utilizable, no se debe continuar como si se hubiera descargado una ficha completa.

### 8.2 Decodificación

El cliente contempla metadatos de charset y codificaciones habituales. Las pruebas cubren, entre otros, HTML con Windows-1252, charset explícito y BOM. Una decodificación incorrecta puede convertir títulos o nombres de producto en texto irreconocible; por eso se registra y prueba de forma separada.

### 8.3 Replay normal y documento real

Un replay de archivo puede presentar una envoltura que contiene el documento original en un iframe. Para Arquivo.pt, Memento convierte la dirección en replay de contenido \`mp_\` para leer el documento archivado real y exponer el título y Product/Offer al mismo validador.

Si se analiza únicamente la envoltura, se puede concluir falsamente que no existe precio o JSON-LD.

### 8.4 Barreras y páginas no válidas

Antes de aceptar el contenido se identifican páginas de bloqueo, desafíos, documentos vacíos, contenido de error y respuestas de intermediarios. Una respuesta HTTP 200 no demuestra por sí sola que se haya recibido la ficha comercial.

### 8.5 Límites de lectura actuales

- cuerpo CDX Wayback: hasta 256 KiB;
- cuerpo Availability: hasta 64 KiB;
- cuerpo HTML de snapshot Wayback: hasta 2 MiB;
- cuerpo TimeMap Memento: hasta 512 KiB;
- cuerpo HTML de snapshot Memento: hasta 2 MiB;
- líneas del CDX Memento procesadas: hasta 100.

Los límites protegen memoria y tiempo de ejecución. Si se cambian, deben añadirse pruebas de respuestas grandes, truncadas y malformadas.

---

## 9. Extracción de ofertas y precios

### 9.1 Orden conceptual de extracción

El extractor puede utilizar varias señales del documento, manteniendo su procedencia:
1. datos estructurados JSON-LD, con tipos y relaciones Product/Offer;
2. estado de producto incrustado en JavaScript;
3. información enlazada de producto/identificadores;
4. metadatos del documento y atributos \`itemprop\`;
5. título y contenido de página;
6. parser visible específico de un comercio cuando existe una estructura conocida y probada.

No se deben sumar indiscriminadamente todos los números del HTML. La extracción debe resolver qué Product/Offer está relacionado con la ficha concreta y cuál es su importe vigente dentro de esa oferta.

### 9.2 JSON-LD

Se inspeccionan bloques estructurados y se relacionan productos y ofertas cuando es posible. El tipo Product/Offer puede aportar:
- título;
- URL/SKU/identificador;
- precio;
- moneda;
- vínculo entre producto y oferta.

JSON-LD es una fuente útil, no una autorización automática para guardar. Se aplican los mismos controles de identidad, moneda, fecha, tienda y condición.

### 9.3 Estado incrustado en JavaScript

Algunas páginas conservan los datos del producto en variables de estado como:
\`__INITIAL_STATE__\`, \`__PRELOADED_STATE__\`, \`__APOLLO_STATE__\`, \`__PRODUCT_STATE__\`, \`__PRODUCT_DATA__\`, \`__APP_DATA__\`, \`__STATE__\`, \`productState\`, \`productData\`, \`productDetails\`, \`initialState\`, \`preloadedState\` o \`apolloState\`.

El extractor limita el tamaño de estado incrustado a 512 KiB y el número de scripts estructurados a 24. Estos límites deben conservarse o justificarse con pruebas de memoria y de páginas reales.

### 9.4 Precio visible específico de Phone House

Existe un extractor restringido al dominio \`phonehouse.es\` que localiza el bloque principal de compra \`.buy-box-1 .precio.mb-10\` y el importe principal correspondiente.

La corrección se hizo para páginas donde el precio no estaba disponible en JSON-LD, pero sí aparecía en la caja visible de compra. No convierte cualquier número de la página en un precio aceptado y no debe aplicarse a otras tiendas.

El parser también debe distinguir:
- importe actual;
- importe recomendado o anterior tachado;
- etiquetas de oferta;
- precio de producto reacondicionado;
- precios de otros productos de la misma página.

### 9.5 Varias ofertas en una misma captura

Una captura puede contener varios Product/Offer, vendedores, variantes o artículos recomendados. El extractor debe relacionar cada oferta con el artículo solicitado y con el destino comercial correcto. Si no puede desambiguar, debe rechazar o dejar el candidato en descubrimiento, nunca elegir simplemente el precio menor.

### 9.6 Condición del artículo

El precio de un artículo reacondicionado, outlet, usado o con estado distinto no se debe mezclar en una serie configurada para producto nuevo. El informe debe explicar la condición incompatible como un descarte correcto, no como si faltara un número o se hubiera roto el parser.

Caso de referencia Phone House:
- captura de noviembre de 2024;
- estado visible: «Reacondicionado / Estado: Funcional»;
- precio principal: 380 EUR;
- precio recomendado tachado: 880 EUR;
- selección: producto estándar nuevo.

El rechazo de 380 EUR es correcto para esa selección. La incidencia pendiente en el informe antiguo era el código de causa: se clasificó como metadato de precio ausente en vez de conflicto de condición.

---

## 10. Identidad de producto, variante, tienda y condición

### 10.1 Señales de identidad

La evaluación combina señales disponibles:
- título esperado de la ficha;
- título de la página archivada;
- modelo y código de modelo;
- capacidad de almacenamiento;
- memoria RAM, separada del almacenamiento;
- variante comercial (Plus, Pro, Ultra, generaciones, diagonales, etc.);
- color cuando forme parte de la selección;
- SKU/identificador del Product/Offer;
- URL de producto y sus parámetros de variante;
- comercio y dominio;
- condición del artículo.

No se utiliza una única coincidencia textual como prueba suficiente.

### 10.2 Modelo y capacidad

El modelo solicitado debe coincidir. Si la selección expresa 256 GB, una página de 128 GB no se puede aceptar como equivalente.

El análisis separa almacenamiento de RAM. Por ejemplo, \`256GB+8GB RAM\` representa 256 GB de almacenamiento y 8 GB de RAM; no se puede confundir el segundo número con la capacidad de almacenamiento.

### 10.3 Variante confirmada por URL

Hay casos en los que el título archivado omite una variante que sí figura en la URL exacta o en el SKU. El modelo de cotización permite registrar cuándo la identidad de variante fue confirmada por la URL.

Esa excepción debe ser estricta: solo se aplica cuando la URL y el resto de señales respaldan inequívocamente la variante. No se utiliza para ignorar un conflicto explícito.

### 10.4 Color

El color puede tratarse como atributo no bloqueante cuando el usuario indica que le resulta indiferente y el producto base, modelo y capacidad coinciden. Cuando el color identifica una variante solicitada, debe conservarse y validarse.

El color no se puede utilizar como excusa para relajar la coincidencia de modelo, generación o capacidad.

### 10.5 Ambigüedad y candidatos de descubrimiento

Si la oferta tiene una identidad plausible pero no se puede asociar con seguridad a una ficha ya existente, puede quedar como candidato de descubrimiento. Un candidato de descubrimiento no es un punto histórico del producto seleccionado y no debe aumentar su gráfica.

### 10.6 Casos que deben acabar en rechazo

- modelo explícitamente distinto;
- capacidad de almacenamiento distinta;
- variante incompatible;
- comercio distinto del destino validado;
- página de categoría/listado sin oferta individual resuelta;
- URL intermediaria o buscador sin vínculo comercial inequívoco;
- producto reacondicionado cuando se pide nuevo;
- oferta o vendedor no elegible;
- coincidencia de título ambigua entre varias fichas.

---

## 11. Validación temporal, monetaria y económica

### 11.1 Timestamp

El timestamp procede de la identidad de la captura archivada y debe coincidir con el timestamp de la cotización. El repositorio rechaza una diferencia mayor que la tolerancia de un segundo aplicada al comparar el snapshot y la fecha observada.

El precio histórico se sitúa en el día de la captura, no en el día en que el usuario ejecutó la búsqueda. El campo de momento de importación es distinto del momento histórico observado.

### 11.2 Ventana histórica

La ventana por defecto es de 2.920 días, equivalente a aproximadamente ocho años de cobertura. Cuando se aplica la ventana completa, los límites se calculan usando calendario UTC para mantener coherencia entre búsqueda y persistencia.

Un precio fuera del período configurado se rechaza o excluye. La zona local del dispositivo se utiliza para el agrupamiento diario de persistencia; no debe confundirse con el timestamp UTC usado para definir la ventana de archivo.

### 11.3 Moneda

- El destino de la serie monetaria de la aplicación es EUR.
- Una cotización EUR se mantiene como EUR tras validación.
- Una cotización USD solo se puede convertir si se obtiene un cambio aplicable a la fecha de captura.
- Se conservan precio y moneda originales, tasa, fecha de cambio y fuente de conversión.
- Una moneda desconocida, no compatible o no verificable no se debe tratar como EUR.

La ficha histórica debe permitir explicar de qué moneda se partió y qué conversión produjo el importe guardado.

### 11.4 Límites numéricos

El cliente define un límite global de precio histórico entre 0,50 EUR y 10.000 EUR. El repositorio añade controles de plausibilidad vinculados al producto. Estos límites son salvaguardas técnicas, no sustituyen a la identidad ni significan que todo importe dentro del rango sea real.

También se rechazan valores no finitos, cero, negativos o fechas inválidas.

### 11.5 Precio principal frente a precio tachado

Un precio anterior tachado, precio recomendado, cuota, importe de financiación, precio por accesorio o importe de otro producto no debe confundirse con la oferta principal. Se exige una asociación clara entre el artículo y el importe extraído.

### 11.6 Causas de rechazo

Los motivos se mantienen diferenciados para que el diagnóstico sea accionable. Entre los códigos existentes o utilizados en la validación se incluyen:
- \`INVALID_ARCHIVE_SNAPSHOT_URL\`;
- \`ARCHIVE_TARGET_URL_MISMATCH\`;
- \`SNAPSHOT_TIMESTAMP_MISMATCH\`;
- \`QUOTE_ORIGINAL_URL_MISMATCH\`;
- \`INVALID_PRICE_CURRENCY_OR_DATE\`;
- \`PRODUCT_IDENTITY_MISMATCH\`;
- \`INELIGIBLE_PRODUCT_TITLE\`;
- \`IMPLAUSIBLE_PRODUCT_PRICE\`;
- \`PRODUCT_OWNERSHIP_NOT_CONFIRMED\`;
- \`AMBIGUOUS_VERIFIED_URL_MATCH\`;
- \`CONFLICTING_PRODUCT_URL_VARIANT\`;
- \`ARCHIVE_PRODUCT_CONDITION_MISMATCH\`.

La lista puede crecer; los informes deben conservar el código concreto y su recuento en vez de agrupar todos los rechazos como «no se encontró precio».

---

## 12. Persistencia en Room e integridad histórica

### 12.1 Qué se guarda

La observación conserva información de precio y procedencia. Entre los campos de modelo consultados aparecen:
- identificador y ficha de producto;
- tienda/vendedor;
- precio y moneda;
- fecha de observación;
- momento de recogida/importación;
- URL de la captura;
- URL de la página comercial original;
- nombre de fuente;
- tipo de evidencia;
- estado de verificación y nota asociada.

La estructura debe seguir permitiendo separar lo que Mínimo observó en vivo de lo que importó desde un archivo.

### 12.2 Propiedad del producto

El repositorio no debe asociar una captura a cualquier ficha que tenga un título parecido. La ruta de persistencia comprueba:
- que el producto objetivo exista;
- que el título/identidad coincida;
- que la URL comercial canónica pertenezca al producto o exista evidencia validada de esa relación;
- que el comercio coincida;
- que no haya otra ficha igualmente válida que haga ambigua la asociación.

Cuando se proporciona expresamente un identificador de producto que no coincide con el título esperado, la persistencia debe rechazar la operación.

### 12.3 URL original y URL del snapshot

Se validan por separado:
- la URL de captura: debe pertenecer a Wayback o Arquivo.pt y contener timestamp/destino reconocibles;
- la URL original de la cotización: debe corresponder a la página comercial canónica;
- la URL de la ficha: debe ser propiedad del producto seleccionado o tener una asociación probada.

No se permite que una captura real de una tienda termine en una ficha de otra tienda por compartir título.

### 12.4 Regla de deduplicación diaria

La unidad de deduplicación para capturas archivadas es:

\`producto + tienda + día local de observación + evidencia archivada válida\`

Consecuencias:
- dos capturas del mismo producto y tienda el mismo día no crean dos puntos diarios;
- el mismo precio en dos días distintos no se considera duplicado por el mero hecho de tener igual importe;
- una observación live del mismo día no bloquea automáticamente una observación archivada;
- las observaciones de Google histórico siguen su propia procedencia;
- las filas antiguas malformadas o con URL no archivada no deben bloquear una captura válida;
- un duplicado confirmado demuestra que el punto ya existe, pero no aumenta el número de puntos nuevos.

### 12.5 Inserción atómica

La búsqueda del duplicado y la inserción se realizan con protección transaccional en Room/DAO para reducir la posibilidad de que dos importaciones simultáneas creen dos filas para el mismo día.

El resultado detallado distingue:
- \`insertedPoints\`: observaciones nuevas insertadas;
- \`confirmedExistingDuplicates\`: cotizaciones válidas cuyo punto ya estaba almacenado;
- \`rejectedQuotes\`: cotizaciones rechazadas;
- \`duplicateQuotesInInput\`: repetidas dentro del propio lote;
- \`invalidExistingRowIds\`: filas heredadas que no cumplen los nuevos criterios;
- \`rejectionReasons\`: mapa de códigos de rechazo y recuentos.

### 12.6 Condición de éxito y crecimiento de gráfica

La lógica de estado de persistencia puede considerar completada la fase si se insertó al menos un punto o se confirmó un duplicado válido. Esto evita presentar como fallida una importación que recuperó una fecha que ya estaba guardada.

Pero el informe debe mantener separadas dos conclusiones:
- **persistencia correcta:** hubo inserción o duplicado válido;
- **crecimiento real del histórico:** \`insertedPoints > 0\` en el producto seleccionado.

Una fase de persistencia superada por duplicados no se debe traducir a «se añadieron fechas nuevas».

### 12.7 Conservación de Room

- No desinstalar para «probar» una corrección.
- No borrar datos de la aplicación.
- No ejecutar una migración destructiva.
- No sustituir tablas ni IDs existentes como solución de un fallo de URL.
- No purgar capturas buenas para que pase una prueba.
- No cambiar el esquema de persistencia sin revisar migraciones, DAO, reinicio y compatibilidad con datos ya guardados.

Toda corrección debe incluir pruebas con filas antiguas y actuales, reapertura de la base y recarga del histórico desde Room.

---

## 13. Gráfica, series y cálculo del mínimo

### 13.1 Series disponibles

La gráfica contempla cuatro familias de evidencia:
- **Mínimo:** observaciones válidas recogidas por Mínimo, utilizadas como base para precio actual y determinados resúmenes;
- **Histórico:** capturas verificadas de Internet Archive y Arquivo.pt;
- **Combinado:** une observaciones de Mínimo y capturas archivadas conservando el origen;
- **Google histórico:** datos fechados de Google Product Insights, separados de las cotizaciones en vivo.

El usuario no debe interpretar «Histórico» como una oferta disponible actualmente.

### 13.2 Rangos temporales

Rangos definidos en la gráfica:
- 30 días;
- 90 días;
- 6 meses (183 días);
- 1 año (365 días);
- 2 años (730 días);
- 3 años (1.095 días);
- 4 años (1.460 días);
- todo el histórico disponible.

Los puntos futuros se excluyen de las ventanas temporales. La opción «Todo» incluye observaciones hasta el momento actual.

### 13.3 Limpieza de la serie

El resumen de analítica:
- elimina timestamps no positivos;
- elimina precios no finitos o no positivos;
- ordena cronológicamente;
- conserva un punto por timestamp dentro de la serie que resume;
- elige determinísticamente el precio más bajo cuando existen registros con timestamp exactamente repetido en ese conjunto.

En la serie combinada se utiliza una clave más específica: timestamp + precio + tienda, de modo que dos fuentes con precios distintos a la misma hora no desaparezcan indebidamente. Si una cotización live y una de archivo son exactamente la misma observación, se prefiere la live.

### 13.4 Mínimo histórico

El resumen de mínimo solo lo considera demostrado cuando hay al menos dos observaciones fechadas distintas en la serie analizada. Un único dato es un primer registro, no una tendencia ni un mínimo comparable.

La curva visual suavizada no genera observaciones nuevas: la interpolación gráfica es solo una representación visual entre puntos reales.

### 13.5 Reglas de visualización

- No mezclar capturas con precio actual.
- No sumar una captura no verificada a la curva.
- No ocultar la procedencia de un punto combinado.
- No inventar fechas intermedias.
- No recalcular el mínimo desde un campo cacheado que no corresponda a la serie visible.
- Comprobar que el número de puntos de Room, el resumen de la ficha y la gráfica sean coherentes.

---

## 14. Telemetría: informe v2 de 30 fases

### 14.1 Formato actual

El informe actual declara:

**Formato v2 · 30 fases generales + detalle por captura.**

Los informes anteriores con 22 fases son históricos y deben conservarse como evidencia de versiones anteriores. Desde el formato actual no se debe volver a describir el diagnóstico como un informe de 22 fases.

### 14.2 Catálogo oficial de fases

| Nº | Fase | Qué verifica |
|---:|---|---|
| 1 | Solicitud iniciada (botón pulsado) | Que la operación fue iniciada por el usuario. |
| 2 | Ficha local y URL principal recuperadas | Que se resolvió la ficha guardada y existe URL de partida. |
| 3 | URL normalizada y destinos candidatos preparados | Que se prepararon URL canónicas y alternativas aptas. |
| 4 | Precontrol de pausa global y preparación de consulta | Que se comprobó la pausa de Internet Archive y el estado previo antes de consumir peticiones. |
| 5 | Petición enviada al índice CDX | Que se inició una consulta al índice. |
| 6 | Respuesta CDX o respuesta alternativa recibida | Que se recibió una respuesta utilizable o se documentó su fallo. |
| 7 | Fallback: CDX alternativo / Availability API | Que se evaluó la ruta de recuperación correspondiente. |
| 8 | Extracción de capturas (índice CDX o Availability) | Que se interpretaron candidatos de captura. |
| 9 | Normalización y deduplicación de URL candidatas | Que se depuraron y consolidaron las direcciones de captura/producto. |
| 10 | Validación de fechas y período histórico | Que las capturas respetan el timestamp y ventana solicitada. |
| 11 | Descarga HTML de capturas (resumen) | Resultado agregado de descargas de páginas. |
| 12 | Evaluación HTML de capturas (resumen) | Resultado agregado de inspección/extracción de las páginas. |
| 13 | Descarga HTML de rutas/capturas adicionales (resumen) | Descargas de rutas o capturas suplementarias. |
| 14 | Evaluación HTML de rutas/capturas adicionales (resumen) | Inspección de esas páginas adicionales. |
| 15 | Verificación del título e identidad del producto | Que título/modelo/variante corresponden al artículo elegido. |
| 16 | Extracción del precio | Que se obtuvo un candidato de precio del documento. |
| 17 | Validación de importe, EUR y cambio histórico | Que importe, moneda, fecha y conversión resultan aceptables. |
| 18 | Consolidación de precios y eliminación de duplicados | Que los precios del lote se consolidaron sin duplicaciones internas. |
| 19 | Guardado del histórico en la base de datos local | Resultado de la persistencia/duplicados/rechazos. |
| 20 | Actualización de la ficha/gráfica y resultado final | Resumen terminal de la búsqueda. |
| 21 | Perfil y decodificación de la página archivada | Diagnóstico detallado por captura del documento obtenido. |
| 22 | Decisión del extractor y causa de rechazo del precio | Diagnóstico detallado por captura de la selección o descarte del precio. |
| 23 | Identidad del producto y vínculo URL comprobados | Auditoría de coherencia de ficha, destino comercial y snapshot. |
| 24 | Precio, moneda y fecha validados | Auditoría de campos económicos y temporales. |
| 25 | Motivos de rechazo registrados y contabilizados | Auditoría de causas de rechazo. |
| 26 | Resultado de guardado local confirmado | Auditoría del resultado que devolvió Room/repositorio. |
| 27 | Duplicados existentes e integridad de filas revisados | Auditoría de duplicados y filas anteriores. |
| 28 | Coherencia entre capturas, guardados y rechazos | Conciliación de contadores entre etapas. |
| 29 | Actualización de ficha y gráfico comprobada | Verificación del refresco visible tras la persistencia. |
| 30 | Diagnóstico final y estado de la operación | Resumen terminal con causa y siguiente paso. |

### 14.3 Fases terminales y ejecutables

Las fases 20 y 30 son resúmenes terminales; no son peticiones de red ni operaciones independientes que tengan que ejecutarse como una consulta.

Las fases 21–22 contienen información detallada del perfil y del extractor para capturas. Las fases 23–29 funcionan como controles de coherencia posteriores al proceso principal. El código de agregación intenta impedir que un resultado verde posterior o una omisión genérica borre una incoherencia o rechazo significativo que ya se había registrado.

### 14.4 Estados

- \`PENDING\`: pendiente, no se ha iniciado.
- \`RUNNING\`: en curso.
- \`PASSED\`: superada.
- \`FAILED\`: fallo o incoherencia que requiere revisión.
- \`SKIPPED\`: omitida, no aplicaba, detenida por pausa/caché o no se llegó a ejecutar.

En la exportación se pueden representar etiquetas más explicativas:
- \`SUPERADO\`;
- \`FALLÓ\`;
- \`NO EJECUTADO\`;
- \`OMITIDO POR PAUSA\`;
- \`OMITIDO POR CACHÉ\`;
- \`OMITIDO / NO APLICABA\`;
- \`DESCARTADO CORRECTAMENTE (CONDICIÓN DISTINTA)\`.

La etiqueta final debe reflejar la causa real. En particular, la pausa global tiene prioridad sobre la mera mención de caché cuando ambas aparecen en el detalle.

### 14.5 Detalle por captura

Además de las 30 filas generales, el informe puede incluir eventos individualizados por captura: ordinal, fecha, identificador seguro, ruta, host, descarga, evaluación y causa de rechazo.

Los detalles repetidos se agregan por clave de captura y etapa. El código limita el volumen del diagnóstico a 120 líneas de eventos de captura y hasta 80 filas de detalle retenidas para evitar que el informe crezca sin límite.

### 14.6 Interpretación de contadores

El informe debe separar, como mínimo:

1. candidatos/capturas que el índice identificó;
2. capturas únicas tras deduplicación;
3. capturas cuyo HTML se intentó descargar;
4. descargas HTTP correctas;
5. páginas HTML que llegaron a evaluarse;
6. candidatos de oferta extraídos;
7. precios que superaron la validación;
8. cotizaciones enviadas a Room;
9. cotizaciones rechazadas por motivo;
10. duplicados de entrada;
11. duplicados ya existentes confirmados;
12. puntos nuevos insertados en el producto seleccionado;
13. resultado del refresco de ficha/gráfica.

No se deben sumar capturas y precios como si fueran el mismo tipo de elemento. Una página puede contener varios precios, y varias capturas pueden representar el mismo día.

### 14.7 Informe completo y redacción

La exportación incluye fecha/hora, versión de Mínimo, versión Android, producto, estado comunicado, resumen, hallazgo principal, primera fase fallida y detalle por fase/captura.

La exportación aplica estas salvaguardas:
- oculta URLs completas;
- oculta HTML;
- redacta cookies, Authorization, access tokens, refresh tokens y API keys;
- oculta valores de parámetros de consulta;
- recorta detalles excesivamente largos;
- limita el título mostrado;
- evita exponer la URL privada o identificadores sensibles en la etiqueta de captura.

El usuario debe poder copiar el informe completo para diagnosticar un caso. El HAR/Chucker es una herramienta técnica complementaria y debe compartirse con más cuidado porque puede contener datos de tráfico.

---

## 15. Concurrencia, tiempos de espera, reintentos y protección de cuota

### 15.1 Serialización y límites de concurrencia

El código dispone de:
- mutex global para serializar búsquedas de backfill histórico;
- semáforo global de Availability con capacidad 1;
- semáforo de descargas de snapshot con capacidad máxima 4;
- límites adicionales por producto, ruta y número de candidatos.

La existencia de concurrencia máxima no significa que el servicio externo reciba todas las llamadas a la vez: las peticiones deben pasar por el gate de ritmo y por los cooldowns.

### 15.2 Ritmo Wayback

La configuración actual aplica un intervalo mínimo de **2.500 ms (2,5 segundos)** entre peticiones controladas al archivo. Se conserva la marca temporal de la preferencia usada por versiones anteriores para que el ritmo guardado siga aplicándose después de una actualización.

Este intervalo reduce la presión y evita ráfagas innecesarias, pero no elimina los límites del servidor ni garantiza que no aparezca un HTTP 429.

### 15.3 HTTP 429

Cuando Internet Archive responde HTTP 429:
1. se registra la limitación;
2. se obtiene \`Retry-After\` si existe y se interpreta;
3. se activa una pausa global persistida;
4. se detiene la expansión de consultas;
5. no se debe lanzar una comprobación adicional para «ver si ya funciona»;
6. el informe indica la hora aproximada de reintento;
7. los intentos posteriores respetan la pausa.

La pausa adaptativa parte de un valor predeterminado de cinco minutos, escala ante respuestas repetidas dentro de una ventana de 24 horas y tiene un techo de 24 horas. El valor final también respeta un \`Retry-After\` más largo recibido del servidor, con los límites internos configurados.

### 15.4 HTTP 503 y fallos transitorios CDX

Los HTTP 500, 502, 503 y 504 se clasifican como respuestas transitorias reintentables dentro de un límite pequeño. El cliente dispone de un reintento CDX, con espera y jitter.

Los fallos repetidos de CDX pueden abrir un circuito local de backend no disponible, cuyo cooldown por defecto es de dos minutos y tiene un máximo interno de diez minutos. Durante ese estado se evita insistir en consultas amplias que no están aportando resultados.

### 15.5 Timeouts por ruta

Los límites actuales consultados en el cliente Wayback incluyen:

| Ruta | Timeout configurado |
|---|---:|
| CDX exacto | 4 s |
| CDX directo por prefijo | 7 s |
| Prefijo suplementario rápido | 10 s |
| CDX de dominio | 5 s |
| CDX exacto de dominio | 7 s |
| URL alternativa de producto | 12 s |
| Bloque trimestral de prefijo | 7 s |
| Descarga de snapshot directo | 12 s |
| Availability API | 6 s |
| Descarga de snapshot fallback | hasta 12 s |
| Fallback CDX de dominio | 3 s |

El ViewModel establece además un límite de hasta 130 segundos por candidato histórico y un máximo de tres páginas/candidatos comerciales por búsqueda dentro de su política de backfill. Estos límites son independientes de los timeouts de una petición individual.

Memento utiliza sus propios límites: TimeMap de 5 segundos, descarga de snapshot de 4 segundos y configuración separada de ritmo/cooldown.

### 15.6 Reintentos de snapshot

El snapshot puede repetir una vez determinados fallos transitorios de transporte, con espera breve. No se deben reintentar HTTP 429 ni errores de validación como si fueran fallos de red. La cancelación de coroutines se debe respetar y no convertir en un nuevo reintento.

### 15.7 Separación de cuotas

Las pausas de Internet Archive y Arquivo.pt son independientes:
- Wayback: cooldown global compartido entre productos y pantallas.
- Memento: cooldown específico de Memento.

Un 429 de Memento no se debe presentar como un 429 de Wayback, ni viceversa.

---

## 16. Caché, cooldown y circuit breakers

### 16.1 Caché de intentos Wayback

El cliente mantiene una clave de intento basada en producto/dominio/URL/título y registra:
- hora del último intento;
- si encontró cotizaciones;
- si el intento falló;
- si terminó limitado por HTTP 429.

Los intervalos configurados son:
- búsqueda con cotizaciones: 30 días;
- búsqueda negativa válida: 6 horas;
- fallo/timeout/transporte: 2 minutos;
- HTTP 429: gobierna el cooldown global persistido, no el caché de éxito largo.

Una búsqueda parcial que encontró una cotización, pero falló en una consulta suplementaria, debe seguir usando intervalo corto de fallo y no el caché largo de éxito solo porque obtuvo algún precio.

### 16.2 Caché de captura dentro de una búsqueda

Las respuestas de captura pueden reutilizarse durante la misma operación para no descargar una misma URL/timestamp repetidamente desde las distintas rutas. La clave debe identificar la captura, no solo el título del producto.

### 16.3 Circuito de backend no disponible

El cooldown corto de backend no disponible se utiliza frente a fallos repetidos de CDX/timeouts. Es distinto del 429:
- 429 significa que el servidor ha impuesto un límite y se debe respetar la pausa;
- 503/timeout indica que el servicio o ruta no está respondiendo adecuadamente;
- respuesta válida vacía significa que la consulta contestó, pero no encontró capturas.

### 16.4 Caché frente a omisión

Una fase omitida por caché significa que la aplicación decidió no repetir una consulta reciente. Una fase omitida por pausa significa que el gate global impidió una petición por límite externo. Una fase no ejecutada significa que la operación terminó antes de llegar a ella.

Las tres situaciones deben aparecer diferenciadas en el informe.

---

## 17. Interpretación de errores y estados

| Código o situación | Significado | Qué hacer | Qué no concluir |
|---|---|---|---|
| \`WAYBACK_RATE_LIMITED\` | Pausa global Wayback por 429 | Respetar hora de reintento; no probar repetidamente | No concluir que el producto no tenga histórico |
| \`ARCHIVE_AVAILABILITY_API_HTTP_429\` | Availability ha recibido 429 | Aplicar pausa global | No culpar al parser HTML |
| \`CDX_URL_PREFIX_HTTP_503\` | La ruta de prefijo CDX respondió 503 | Tratar como fallo externo/transitorio y usar cooldown limitado | No convertirlo en «cero capturas» |
| \`CDX_URL_PREFIX_TIMEOUT\` | El prefijo excedió el tiempo permitido | Búsqueda incompleta; reintento acotado cuando proceda | No afirmar que el índice está vacío |
| \`CDX_NO_CAPTURES\` | Consulta de índice válida sin candidatos para esa ruta | Revisar las otras rutas y completar el resumen | No afirmar que todo Internet Archive carece de capturas |
| \`MEMENTO_NO_CAPTURES\` | TimeMap/CDX respondió correctamente sin capturas utilizables | Registrar consulta negativa válida de esa fuente | No confundir con Memento bloqueado |
| \`MEMENTO_RATE_LIMITED\` | Arquivo.pt devolvió 429 o está en pausa | Respetar su cooldown independiente | No llamarlo pausa global Wayback |
| \`ALL_SNAPSHOTS_FAILED\` | Se encontraron candidatos, pero fallaron sus descargas | Revisar HTTP, timeout, DNS y URL de snapshot | No clasificar como ausencia de capturas |
| \`ARCHIVE_TARGET_URL_MISMATCH\` | El snapshot apunta a una página distinta | Rechazar y revisar canonicalización/variante | No guardar por coincidencia de título |
| \`ARCHIVE_PRODUCT_CONDITION_MISMATCH\` | La condición del artículo no coincide | Descartar correctamente y documentar condición | No tratarlo como falta de importe |
| \`PRODUCT_IDENTITY_MISMATCH\` | La oferta no representa el artículo elegido | Rechazar y revisar modelo/variante | No relajar identidad para obtener un precio |
| \`IMPLAUSIBLE_PRODUCT_PRICE\` | El importe no supera el control económico | Revisar fuente y reglas de plausibilidad | No modificar el umbral para forzar un resultado |
| Duplicado confirmado | La fecha ya estaba guardada de forma válida | Registrar duplicado, no crear otro punto | No contar una fecha nueva |
| Cero filas nuevas | No creció la serie en esta ejecución | Consultar duplicados/rechazos y producto objetivo | No equivale automáticamente a fallo total |
| Ficha sin refrescar | Room pudo guardar, pero la UI no refleja el cambio confirmado | Revisar emisión de Flow/selección/refresco | No decir que Room perdió los datos sin comprobarlo |

### 17.1 Estados de resultado que debe distinguir la aplicación

- **Éxito completo con puntos nuevos:** una o más observaciones nuevas se guardaron y la ficha/gráfica se actualizó.
- **Éxito de persistencia sin crecimiento:** la cotización válida ya existía; se confirmó duplicado.
- **Recuperación parcial:** hay cotizaciones válidas, pero alguna ruta importante falló y la cobertura queda incompleta.
- **Sin capturas en rutas consultadas:** las rutas aplicables respondieron válidamente sin candidatos.
- **Sin precio válido:** había documento o captura, pero la extracción/validación no produjo un precio aceptable.
- **Rechazo de identidad/condición:** se encontró un posible precio que no pertenece a la selección.
- **Pausa por cuota:** la consulta se detuvo antes de completar el proceso.
- **Omitida por caché:** ya existía un intento suficientemente reciente.
- **Fallo de transporte:** DNS, timeout, HTTP u otro problema impidió obtener o evaluar el documento.
- **Fallo de interfaz:** datos guardados o calculados no llegaron a reflejarse en la ficha.

---

## 18. Límites técnicos y parámetros de ejecución

Los parámetros de esta sección son los valores de código consultados en \`main\` el 30/10/2026. Si se modifican, actualizar esta tabla con el commit correspondiente.

| Parámetro | Valor consultado | Función |
|---|---:|---|
| Ventana por defecto | 2.920 días (~8 años) | Límite temporal de histórico |
| Capturas directas máximas por URL | 12 | Acotar descarga |
| Capturas de dominio por búsqueda | 8 | Acotar fallback |
| Filas CDX de dominio | 20 | Limitar lectura de índice |
| Hosts CDX de dominio | 2 | Evitar rastreo amplio |
| URL alternativas de producto por dominio | 2 | Acotar candidatos |
| URL candidatas de Availability | 6 | Acotar destino de probes |
| Probes Availability directos | 2 | Limitar llamadas |
| Probes Availability de dominio | 5 | Limitar llamadas de fallback |
| Snapshots CDX fallback | 8 | Acotar recuperación |
| Prefijos suplementarios rápidos | 3 snapshots | Reducir trabajo una vez obtenido histórico |
| Bloques trimestrales por lookup | 2 | Recuperación temporal limitada |
| Snapshots trimestrales por lookup | 2 | Limitar descargas de ventana |
| Concurrencia máxima de snapshots | 4 | Limitar trabajo paralelo |
| Concurrencia Availability | 1 | Serializar llamadas Availability |
| Intervalo mínimo Wayback | 2.500 ms | Espaciado entre peticiones |
| Reintentos CDX HTTP | 1 | Reintento limitado |
| Timeout HTTP retryable CDX | 500/502/503/504 | Respuestas tratadas como transitorias |
| Reintento snapshot | 1 | Solo algunos fallos de transporte |
| Tamaño máximo HTML | 2 MiB | Protección de memoria |
| Precio histórico global | 0,50–10.000 EUR | Guardia general de importe |

Parámetros Memento:

| Parámetro | Valor consultado | Función |
|---|---:|---|
| Intervalo mínimo entre peticiones | 2.000 ms | Ritmo independiente de Arquivo.pt |
| Cooldown HTTP 429 | 15 minutos | Pausa específica de Memento |
| Timeout TimeMap | 5 s | Límite de consulta |
| Timeout snapshot | 4 s | Límite de replay |
| Límite TimeMap | 512 KiB | Protección de memoria |
| Límite CDX NDJSON | 100 líneas | Acotación del índice |
| Snapshots máximos en fallback CDX | 3 | Acotar recuperación |
| Límite HTML | 2 MiB | Protección de memoria |

Estos valores son límites de implementación, no objetivos de calidad. No se deben aumentar todos simultáneamente para «buscar más»: cada cambio debe justificar cobertura adicional, coste de red, tiempo y efecto en la cuota externa.

---

## 19. Seguridad, privacidad y tratamiento de datos de diagnóstico

### 19.1 URLs y credenciales

Los informes de texto deben redactar:
- URLs completas;
- valores de parámetros de consulta;
- cookies;
- cabeceras Authorization;
- access tokens;
- refresh tokens;
- API keys;
- HTML bruto.

No se deben pegar secretos en el código, logs, tests, mensajes de commit o informes exportables.

### 19.2 Chucker

Chucker se incorpora en debug y en la compilación sideload de diagnóstico. La existencia de la dependencia en una APK no es por sí sola prueba de que el tráfico esperado se capture en el teléfono; hay que comprobarlo en dispositivo.

Un HAR puede contener rutas, parámetros, tokens, cookies o información de sesión. Revisarlo antes de compartirlo y eliminar información privada que no sea necesaria.

### 19.3 URLs externas

Las páginas comerciales y URLs archivadas son datos no confiables. El motor debe restringir hosts, validar destino y no ejecutar instrucciones embebidas. El HTML se analiza como contenido; no se debe tratar como código de confianza.

### 19.4 Escaneo de secretos en CI

El workflow actual incluye un paso de escaneo de credenciales en archivos e historial. Mantenerlo habilitado. No desactivar el escaneo para forzar una publicación de APK.

---

## 20. Batería de pruebas y estrategia de validación

### 20.1 Archivos de prueba principales identificados

| Archivo de pruebas | Ámbito |
|---|---|
| \`WaybackPriceHistoryClientTest.kt\` | CDX, URLs, indexado, muestreo, reintentos, cooldown, captura y extracción |
| \`WaybackSamsungTitleCompatibilityTest.kt\` | Compatibilidad de títulos y modelos Samsung |
| \`FreeArchiveHistoryPersistenceTest.kt\` | Persistencia real de histórico gratuito, deduplicación y recarga |
| \`ArchivedVariantPersistenceTest.kt\` | Identidad de variante en persistencia |
| \`PriceHistoryRestartPersistenceTest.kt\` | Conservación del histórico después de cerrar/reabrir Room |
| \`ProductRepositoryArchiveIsolationTest.kt\` | Aislamiento entre comercio, URL y producto |
| \`PriceHistoryChartEvidenceTest.kt\` | Series, evidencia y procedencia de puntos |
| \`PriceHistoryChartTest.kt\` | Ventanas, limpieza y presentación de gráfica |
| \`ProductDetailSheetDiagnosticTest.kt\` | Resumen, estados, causas, redacción y exportación del informe |
| \`ArchiveLiveFallbackPolicyTest.kt\` | Política del fallback y relación con cotizaciones live |

### 20.2 Casos mínimos de regresión

**Descubrimiento**
- CDX exacto encuentra capturas.
- CDX exacto vacío y prefijo correcto.
- CDX exacto vacío y prefijo 503.
- CDX timeout con respuesta parcial.
- Availability encuentra captura.
- Availability devuelve cero capturas.
- HTTP 429 corta nuevas peticiones.
- Memento TimeMap normal y CDX fallback.

**URL y destino**
- URL con parámetros de seguimiento.
- URL con parámetros auténticos de variante.
- Phone House con \`colorgris=\` vacío.
- Phone House con \`colorgris=gris\`.
- Redirección a la página de producto canónica.
- Página de categoría o búsqueda.
- Link de Google Shopping sin enlace directo identificable.
- Snapshot de otra tienda con mismo título.

**HTML y extracción**
- JSON-LD Product/Offer válido.
- Página sin JSON-LD pero con precio visible específico.
- HTML con estado incrustado de JavaScript.
- HTML con charset no UTF-8.
- HTML de replay que contiene una envoltura/iframe.
- página de bloqueo, HTML vacío, documento grande o truncado;
- varias ofertas, precio tachado y oferta principal.

**Identidad**
- Modelo exacto;
- modelo distinto;
- almacenamiento 128 frente a 256 GB;
- almacenamiento y RAM en el mismo título;
- variante Plus/Pro/Ultra;
- color solicitado y color irrelevante;
- artículo nuevo frente a reacondicionado;
- SKU que confirma variante omitida en el título.

**Persistencia**
- nueva observación en ficha correcta;
- URL de snapshot equivocada;
- timestamp distinto;
- moneda inválida;
- precio fuera de límites;
- producto ambiguo;
- duplicado mismo producto/tienda/día;
- mismo importe en días diferentes;
- observación live y archivo el mismo día;
- fila legacy con etiqueta archive pero URL no archivada;
- Memento \`mp_\` tras reinicio de aplicación.

**UI**
- serie live separada de archive;
- serie combinada conserva precios diferentes;
- ventanas 30D/90D/6M/1A/2A/3A/4A/Todo;
- un punto no muestra mínimo comparable;
- dos fechas distintas habilitan el resumen de mínimo;
- fase 29 confirma el refresco o documenta su fallo.

### 20.3 Validación completa de una APK

Un cambio de código no se declara listo solo porque el editor muestre «compilado». Para una APK distribuible se requiere:
1. pruebas unitarias;
2. compilación de \`compileDebugAndroidTestKotlin\`;
3. \`assembleSideload\`;
4. comprobación del identificador de paquete;
5. comprobación de versionCode/versionName;
6. manifiesto no depurable según la configuración de distribución;
7. verificación de firma y certificado esperado;
8. comprobación de Chucker en la variante sideload;
9. artefacto APK publicado;
10. prueba en dispositivo con informe y resultado de persistencia.

La sonda real Wayback del workflow puede estar omitida por diseño. Su omisión no convierte el build en fallo, pero tampoco demuestra que la API archive.org esté respondiendo.

### 20.4 Evidencia y SHA

Toda APK debe relacionarse con el SHA exacto que la generó. Un artefacto creado desde un commit anterior no certifica los cambios posteriores de \`main\`. La ficha debe registrar run, SHA, versión, versionCode, hash de APK y certificado cuando estén disponibles.

---

## 21. Métricas de calidad que deben medirse

No hay una puntuación de eficacia global verificada todavía. Para cerrarla, cada indicador necesita un lote de búsquedas reales y reproducibles.

| Indicador | Definición propuesta | Qué permite evaluar |
|---|---|---|
| Finalización | Búsquedas que alcanzan el cierre de fase 30 / búsquedas iniciadas | Estabilidad del flujo |
| Recuperación de candidatos | Capturas únicas candidatas / filas válidas devueltas por índices | Rendimiento del descubrimiento |
| Tasa de descarga | HTML útil descargado / capturas cuya descarga se intentó | Fiabilidad del replay |
| Rendimiento de extracción | Precios candidatos extraídos / páginas HTML evaluadas | Cobertura del parser |
| Aceptación de precio | Precios validados / candidatos de precio | Calidad de validación |
| Inserción nueva | Búsquedas con al menos un punto nuevo / búsquedas históricas completas | Capacidad real de ampliar series |
| Expansión media | Puntos nuevos insertados / búsqueda completa | Cuánto crece cada búsqueda |
| Duplicado confirmado | Duplicados válidos / cotizaciones válidas enviadas a Room | Repetición de fechas ya conocidas |
| Rechazo por identidad | Rechazos por modelo/variante/URL/tienda / candidatos revisados | Precisión del emparejamiento |
| Rechazo por condición | Rechazos por nuevo/usado/reacondicionado / candidatos revisados | Separación de condición |
| Límite externo | Respuestas 429 y 503 / peticiones de cada fuente | Salud de la fuente y presión de cuota |
| Tiempo total | Mediana y p95 de duración por búsqueda | Experiencia de usuario |
| Tiempo por fase | Mediana y p95 por fase CDX, HTML, extracción, Room y UI | Cuello de botella |
| Refresco de interfaz | Fichas actualizadas / persistencias con resultado | Coherencia Room/UI |
| Precisión de identidad | Aciertos manuales / casos etiquetados | Riesgo de falsos positivos |
| Falsos negativos | Capturas correctas rechazadas / capturas de referencia positivas | Cobertura perdida por filtros |

### 21.1 Requisitos de la muestra

La muestra debe combinar:
- varias tiendas;
- varias familias de producto;
- fichas con y sin JSON-LD;
- URLs con y sin variantes;
- páginas con precio visible;
- páginas con reacondicionados/outlet;
- casos con capturas ya guardadas;
- casos con respuesta CDX vacía;
- casos con 429/503/timeout;
- casos que sí terminan con nuevas fechas.

No publicar una tasa para una muestra de un único producto o para una búsqueda bloqueada antes de descargar HTML.

### 21.2 Resultado estadístico mínimo

Cada informe de métricas debe declarar:
- número de productos;
- número de tiendas;
- número de búsquedas iniciadas;
- número de búsquedas completas;
- número de capturas;
- número de páginas descargadas;
- número de ofertas/precios extraídos;
- número de puntos nuevos;
- duplicados y rechazos;
- período de medición;
- versión de app y SHA del código.

---

## 22. Incidencias abiertas y riesgos conocidos

| Prioridad | Incidencia | Evidencia | Criterio de cierre |
|---|---|---|---|
| Alta | Internet Archive responde 429/503 o timeout en algunas rutas | Informes A18 y A27 del 30/10 | No se puede eliminar el límite externo; sí demostrar que se respeta, reduce peticiones innecesarias y se informa correctamente. |
| Alta | La consulta puede quedar incompleta aunque una ruta exacta devuelva cero capturas | Informe A27: exacto vacío, prefijo 503 y Availability 429 | El resultado debe permanecer «incompleto», sin afirmar que no existe histórico. |
| Alta | Confirmación de refresco UI tras persistencia | Prueba real 1.7.1013.1 guardó tres puntos, pero fase 29 no confirmó ficha refrescada | Repetir en dispositivo y comprobar que la ficha seleccionada refleja los puntos de Room. |
| Media | Código de rechazo del reacondicionado | Captura Phone House de 2024 con 380 EUR reacondicionado | El motivo debe ser explícito \`ARCHIVE_PRODUCT_CONDITION_MISMATCH\`; el precio no debe contaminar producto nuevo. |
| Media | Cortocircuito tras alcanzar puntos suficientes | HAR 28/10 contiene Availability 429 inmediatamente después de un mensaje de objetivo alcanzado | Verificar que no se inicia nueva llamada tras alcanzar suficiencia, salvo que estuviera ya en vuelo. |
| Media | Cobertura incompleta por muestreo acotado | Muestreo, límites de capturas, bloques y timeouts | Medir fechas recuperadas frente a candidatos disponibles; no llamar «exhaustivo» al histórico. |
| Media | Eficacia y precisión global sin números | No existe todavía batería estadística | Ejecutar el conjunto etiquetado y calcular métricas de sección 21. |
| Media | Chucker validado en dispositivo en todas las APK sideload actuales | Integración en build, verificación depende del run/APK | Comprobar tráfico y redacción en teléfono. |

### 22.1 Lo que no es un fallo del extractor

Cuando se recibe 429 antes de consultar CDX, no hay HTML que analizar. Cuando CDX devuelve 503, no hay una respuesta válida de índice. Cuando Availability se bloquea por cuota, no hay documento que descargar.

En estos casos la corrección debe actuar sobre control de flujo, pausas, recuperación y diagnóstico. No se debe modificar el parser de HTML para resolver una consulta que nunca llegó al HTML.

### 22.2 Lo que sigue siendo una limitación externa

Mínimo no controla:
- disponibilidad de Internet Archive/Arquivo.pt;
- retención de páginas históricas;
- frecuencia con la que una tienda fue capturada;
- códigos HTTP que devuelven los archivos;
- cambios en el HTML original;
- bloqueos/robots de las páginas archivadas;
- datos incompletos que el archivo guardó.

El objetivo del software es manejar esos límites sin falsear resultados ni consumir más cuota de la necesaria.

---

## 23. Procedimiento de diagnóstico operativo

### 23.1 Antes de una búsqueda

1. Confirmar la versión instalada y el producto exacto.
2. Revisar si existe pausa Wayback o pausa Memento activa.
3. Comprobar que la ficha tiene una URL comercial directa, no un buscador o intermediario.
4. No repetir inmediatamente una búsqueda que acaba de devolver 429.
5. No gastar peticiones manuales para comprobar si el cooldown ha expirado.

### 23.2 Después de la búsqueda

1. Copiar el informe completo.
2. Revisar versión, hora, producto y resultado comunicado.
3. Empezar por la fase fallida más temprana.
4. Distinguir respuesta vacía de error de consulta.
5. Comprobar capturas encontradas y HTML descargado.
6. Revisar identidad y causas de rechazo.
7. Comprobar fase 19: insertados, duplicados, rechazos.
8. Comprobar fase 29: actualización de ficha/gráfica.
9. Relacionar el resultado con Room, si hace falta, antes de hablar de pérdida de datos.
10. Si hay HAR, utilizar solo el tramo temporal de esa ejecución y ocultar credenciales.

### 23.3 Diagnóstico rápido por patrón

- **Fases 1–4 únicamente:** operación bloqueada antes de la consulta, ficha inválida, caché o cooldown.
- **CDX exacto vacío + prefijo fallido:** consulta incompleta; revisar prefijo/circuito. No tocar extractor.
- **Capturas encontradas + cero HTML 200:** problema de replay, DNS, HTTP, timeout o barrera.
- **HTML 200 + cero precios:** inspeccionar documento, parser, estado incrustado, JSON-LD y estructura visible.
- **Precios extraídos + rechazos de identidad:** revisar modelo, capacidad, URL, tienda y condición.
- **Precios validados + cero insertados:** revisar propietario de ficha, URL original, snapshot, fecha, moneda y duplicados.
- **Room insertó + gráfica no cambió:** revisar Flow, selección de producto y fase 29.
- **Solo duplicados:** persistencia válida, pero sin expansión nueva.
- **429 en Wayback:** esperar a la hora mostrada; no buscar de nuevo repetidamente.
- **429 en Memento:** esperar el cooldown específico de Arquivo.pt; no atribuirlo al estado global de Wayback.

---

## 24. Criterios de aceptación para cerrar una mejora

Una mejora se considera cerrada solo cuando cumple el criterio correspondiente:

| Área | Criterio de aceptación |
|---|---|
| Descubrimiento | Pruebas para respuesta válida, vacía, parcial, timeout, 503 y 429. |
| URL | Casos de URL exacta, prefijo, seguimiento, variante y redirección; no se pierde identidad. |
| Captura | Snapshot de host reconocido, timestamp correcto y destino original coherente. |
| HTML | Descarga correcta, tamaño y charset controlados; fallos no se convierten en HTML útil. |
| Extracción | Precio asociado a Product/Offer o bloque de compra probado, no a números aleatorios. |
| Identidad | Modelo, almacenamiento, variante, comercio y condición probados, incluidos conflictos. |
| Moneda | EUR validado o conversión fechada trazable; moneda no soportada rechazada. |
| Persistencia | Pruebas de nueva fila, duplicado mismo día, importe igual en otro día, fila legacy y reinicio. |
| UI | La ficha seleccionada refleja las filas realmente guardadas; la gráfica no inventa puntos. |
| Diagnóstico | Informe v2 con fases y causas coherentes; no contradice el detalle por captura. |
| Red | 429/503/timeout respetan cooldown, cadencia y límite de reintentos. |
| Seguridad | No filtra URL sensible, token, cookie, API key ni HTML. |
| CI | Pruebas, compilación instrumentada, APK, firma, versión y Chucker superados en el SHA exacto. |
| Dispositivo | Informe real con resultado de Room y UI comprobado. |
| Eficacia | La mejora aporta datos comparables de cobertura, tiempo, precisión o coste de peticiones. |

No marcar «terminado» un parser para todos los comercios tras probar una sola tienda. La cobertura debe expresarse por tiendas y estructuras realmente cubiertas.

---

## 25. Reglas de mantenimiento del proyecto

1. Trabajar en \`main\` cuando esa sea la instrucción vigente; no crear ramas nuevas por comodidad.
2. Limitar el diff a la función que se está corrigiendo y sus pruebas.
3. No eliminar funciones existentes para hacer pasar una prueba; justificar cualquier sustitución y mantener compatibilidad.
4. No mezclar cambios de interfaz con cambios del extractor cuando no sea necesario.
5. No tocar ni borrar datos Room como método de depuración.
6. No relajar filtros de identidad para aumentar la cantidad de precios.
7. No contar el mismo producto/tienda/día como varios puntos históricos.
8. No contar importes iguales en días diferentes como duplicados globales.
9. No convertir HTTP 429/503 en una búsqueda negativa.
10. No iniciar nuevas peticiones después de alcanzar el objetivo si no hay una razón probada.
11. No añadir reintentos ilimitados.
12. Añadir una prueba de regresión por cada defecto reproducible.
13. Documentar commit, ejecución CI, resultado, APK y prueba real por separado.
14. Registrar los hitos como evidencia histórica; no reescribir la historia para que parezca que una versión antigua tenía capacidades posteriores.
15. Actualizar esta ficha cuando cambien arquitectura, fases, límites, formato, fuentes, criterios de identidad, persistencia o estado de validación.

### 25.1 Formato obligatorio de nuevo hito

Cada nueva entrada de la ficha debe incluir:
- fecha y versión de la aplicación;
- SHA del código;
- capacidad modificada;
- comportamiento anterior;
- cambio aplicado;
- archivos afectados;
- pruebas añadidas/ejecutadas;
- número de casos y resultado;
- ejecución CI y estado;
- APK y firma, si se generó;
- informe de dispositivo, si se ejecutó;
- puntos encontrados, descargados, extraídos, validados, duplicados y nuevos;
- impacto medido;
- regresiones comprobadas;
- limitación que sigue abierta.

---

## 26. Glosario técnico

| Término | Significado |
|---|---|
| CDX | Índice de capturas archivadas; permite localizar fechas y URL, pero no contiene necesariamente el precio. |
| Snapshot / captura | Versión archivada de una URL en una fecha y hora concretas. |
| Replay | Dirección desde la que el archivo presenta una captura. |
| TimeMap | Lista de recursos archivados asociados a una URL en Memento. |
| Memento | Marco/protocolo de acceso a versiones fechadas de recursos web; en este proyecto se utiliza Arquivo.pt. |
| \`mp_\` | Modificador de Arquivo.pt usado para recuperar el documento archivado de contenido. |
| PDP | Product Detail Page; ficha individual de producto. |
| Listing / categoría | Página que enumera varios productos; no equivale a una ficha individual. |
| URL canónica | Dirección normalizada que representa una misma página de producto sin parámetros de seguimiento irrelevantes. |
| Parámetro de seguimiento | Valor de campaña/analítica que no suele cambiar la identidad del producto. |
| Parámetro de variante | Valor que puede distinguir color, SKU, configuración, capacidad u otra variante. |
| JSON-LD | Formato estructurado embebido en la página para describir entidades como Product y Offer. |
| Product / Offer | Entidad de producto y oferta comercial descritas en datos estructurados. |
| SKU | Identificador de referencia de una variante o artículo en un comercio. |
| Candidato de descubrimiento | Precio/producto potencial aún no asociado de forma segura a la ficha seleccionada. |
| Duplicado | Observación válida que ya está almacenada para el mismo producto, tienda y día aplicable. |
| Cooldown | Pausa temporal persistida antes de volver a consultar una fuente. |
| Circuit breaker | Mecanismo que detiene temporalmente una ruta que está fallando de forma repetida. |
| p95 | Percentil 95; el 95 % de las mediciones queda en o por debajo de ese tiempo. |
| Falso positivo | El motor acepta como producto solicitado una página u oferta que en realidad corresponde a otro. |
| Falso negativo | El motor rechaza una captura que sí correspondía al producto solicitado. |
| Proveniencia | Origen y tipo de evidencia de cada observación de precio. |
| Room | Base de datos local Android donde se almacenan fichas y observaciones. |

## 27. Evidencia de uso real más reciente — 30/10/2026

Esta sección separa los resultados de las búsquedas históricas de los cambios de código y del estado de compilación.

### 27.1 Samsung Galaxy A18 — informe 1.7.1055.1

**Hora:** 30/10/2026, 03:10:52 GMT+01:00.  
**Resultado de ofertas actuales:** se encontraron dos ofertas actuales y se guardaron dos observaciones nuevas/actualizadas.  
**Resultado histórico:** no se modificó el histórico Wayback.

El precontrol detectó pausa global activa por HTTP 429. La aplicación informó que no enviaría nuevas peticiones hasta aproximadamente las 03:29. La pausa impidió continuar con CDX/Availability y las fases posteriores no llegaron a ejecutarse.

El resumen contabilizó 2 fases superadas y 28 no ejecutadas. La fase 4 se exportó inicialmente con una etiqueta que la hacía parecer una omisión por caché, aunque el detalle indicaba bloqueo por pausa global. Se añadió una prueba de regresión para que la pausa tenga prioridad sobre la palabra «caché» en esa etiqueta.

**Conclusión:** este intento demuestra que se detectó una pausa y se conservó el guardado de ofertas actuales. No proporciona evidencia sobre la existencia o ausencia de capturas históricas del Galaxy A18, porque la consulta histórica no llegó a iniciarse.

### 27.2 Samsung Galaxy A27 5G — informe 1.7.1055.1

**Hora:** 30/10/2026, 03:09:41 GMT+01:00.  
**Resultado de ofertas actuales:** el respaldo actual guardó ofertas verificadas; el siguiente uso de SerpApi se aplazó por la política de cuota.  
**Resultado histórico:** búsqueda incompleta y sin nuevos puntos archivados.

Hallazgos del informe:
- CDX exacto devolvió respuesta válida con cero capturas (aprox. 5.134 ms).
- CDX por prefijo respondió HTTP 503 (aprox. 5.208 ms).
- Memento informó que no encontró capturas utilizables para esa consulta.
- Availability respondió HTTP 429.
- No se descargó HTML, no se extrajeron precios archivados y no se guardaron observaciones históricas.
- El informe terminó con 7 fases superadas, 1 fase fallida y 22 fases no ejecutadas.

**Conclusión:** la respuesta vacía del CDX exacto no permite concluir que no exista histórico cuando el prefijo y Availability fallaron. El bloqueo se produjo en el descubrimiento/recuperación de capturas, antes del extractor HTML y de Room.

### 27.3 Ajustes de ritmo y diagnóstico integrados en el código

En el código consultado de \`main\`:
- el intervalo mínimo entre peticiones Wayback es de 2.500 ms;
- se mantiene cooldown global adaptativo por HTTP 429;
- existe cooldown corto de backend no disponible para fallos repetidos CDX;
- el estado de pausa tiene prioridad sobre el estado de caché al generar la etiqueta de fase 4;
- existen pruebas para la regla de etiqueta y para el intervalo de pacing.

El intervalo de 2,5 segundos es una medida de contención, no una garantía contra 429/503.

### 27.4 Estado de compilación al revisar esta ficha

El run #1070 se encontraba ejecutando pruebas unitarias cuando se consultó. Su SHA de entrada era \`d2d2f3f715c71d97aa7c60243397f4ed14b23957\`. La rama \`main\` ya había avanzado tres commits, hasta \`723c3569b3b7c574677664ffcdf5fd4801dbfbcb\`, con cambios en:
- \`ProductDetailSheet.kt\`;
- \`WaybackPriceHistoryClientTest.kt\`;
- \`ProductDetailSheetDiagnosticTest.kt\`.

Por ello, el resultado de ese run no puede usarse como validación del HEAD más reciente. Hace falta que CI valide el SHA actual o posterior antes de declarar una APK de esa revisión como verificada.

### 27.5 Última demostración positiva del flujo completo

La validación real de Xiaomi Mi 10 Pro Gris en Mínimo 1.7.1013.1, descrita en la sección 15, sigue siendo la evidencia positiva de dispositivo más clara de esta ficha:
- cinco capturas candidatas;
- cuatro HTML descargados con HTTP 200;
- tres precios válidos;
- tres puntos nuevos guardados en Room;
- cero duplicados y cero rechazos;
- persistencia confirmada.

Esa ejecución también dejó una incidencia separada de refresco de la ficha/gráfica (fase 29) y una clasificación incorrecta del rechazo de una oferta reacondicionada. Por tanto, demuestra que el ciclo de extracción y persistencia puede funcionar, pero no cierra todas las incidencias de interfaz/diagnóstico.

---

## Estado de la documentación

La ficha queda organizada como especificación técnica, manual de diagnóstico y registro de evidencia. El historial de hitos de las secciones siguientes se conserva para no perder el rastro de correcciones anteriores. Cuando una afirmación histórica describe un formato antiguo de 22 fases, se debe leer como la situación de aquella fecha; el formato de diagnóstico vigente documentado aquí es v2 de 30 fases.

**Criterio de verdad permanente:** Mínimo debe guardar únicamente precios históricos cuya página, tienda, fecha, moneda, producto y variante hayan sido comprobados. El objetivo no es producir más puntos a cualquier precio, sino construir un histórico fiable, trazable, persistente y útil sin castigar la cuota de los archivos ni comprometer los datos ya guardados.
## 8. Actualización de integridad y resultado de CI — 27/10/2026

### Cambios ya integrados en `main`

Los commits relevantes de esta revisión son:

- [Validar enlaces históricos en conteo y deduplicación diaria](https://github.com/bonillamarquezpablo-lab/00157916/commit/c401e80df829d59153d6e3468cfd761e6a123e16) y [ajuste final de la expresión SQL compartida](https://github.com/bonillamarquezpablo-lab/00157916/commit/5b8ff673e1477359e3e637d2ed7820960511e7ca).
- [Exigir un punto nuevo del producto seleccionado para dar la fase 19 por superada](https://github.com/bonillamarquezpablo-lab/00157916/commit/471bc3f476da10332edc426a1f2943270c0bb0e9).
- [Pruebas de regresión para URL histórica falsa y deduplicación](https://github.com/bonillamarquezpablo-lab/00157916/commit/335d32b3dbb99394f40cb79ccfb142f0572b4ee5).
- [Prueba de regresión del estado de guardado](https://github.com/bonillamarquezpablo-lab/00157916/commit/de8a8fd2aca2d2907c5353870bfe5bb5122da2be).

**Validación de enlaces archivados:** el DAO ya no debe confiar solo en la etiqueta `evidenceType=archive`. El conteo de observaciones verificadas y la consulta diaria de deduplicación aplican patrones de URL de captura reconocidos. Se evita que una URL normal de tienda etiquetada como archivo cuente como captura auténtica o bloquee una captura válida. Se mantienen formatos reconocidos de Wayback `id_` y de Arquivo.pt, incluidos `mp_`.

**Resultado real de la fase 19:** el estado del guardado depende de los puntos que se insertan en el producto seleccionado. Guardar observaciones de una variante relacionada, enviar un descubrimiento a cuarentena o encontrar un duplicado no debe aparentar que creció la gráfica de la ficha actual. Las pruebas de regresión cubren el caso de cero puntos y el de un punto nuevo.

### Resultado automatizado comprobado

Ejecución: [GitHub Actions · run 890](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40438796756).

- `:app:testDebugUnitTest`: **correcto**.
- `:app:compileDebugAndroidTestKotlin`: **correcto**.
- `:app:assembleSideload`: **BUILD SUCCESSFUL**.
- Verificación final: paquete `com.aistudio.preciominimo.esrxvt`, versionCode `99001`, versionName `1.7.890.1`, manifiesto no depurable, certificado de firma esperado y Chucker localizado en 5 archivos DEX.
- APK SHA-256: `efdfc53a718f724dfe61f6e6465cf591b0b06bcfa5dfa10c9ef58f4dfeaf4f0c`.
- Artifact `minimo-apk`: [descargar desde la ejecución](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40438796756/artifacts/12706121159). Caduca el 3 de noviembre de 2026.

El inventario estático de cinco archivos relevantes de pruebas suma **192 anotaciones `@Test`**: Wayback 151, Memento 10, descubrimientos 6, informe de diagnóstico 8 y persistencia de histórico 17. Es un recuento de código fuente, no el número total de pruebas del proyecto. El resultado correcto de `testDebugUnitTest` sí confirma que la suite unitaria del workflow terminó bien.

### Qué todavía no está demostrado

La sonda de Wayback en vivo es opcional y fue omitida en este run. Por tanto, el resultado verde confirma los tests automatizados y la construcción del APK, pero no demuestra que Internet Archive responda ahora, que una búsqueda real supere los timeouts/HTTP 429 anteriores o que añada fechas nuevas para un producto concreto. Tampoco reemplaza una prueba con el APK instalado.

**Siguiente comprobación:** instalar `1.7.890.1`, ejecutar una búsqueda que no esté bloqueada por el cooldown, exportar el informe de 22 fases y comparar capturas encontradas, precios válidos, duplicados y puntos realmente nuevos en la gráfica. La ficha solo debe declarar eficacia mejorada cuando ese caso real aporte evidencia.


## 9. Incidente real — Mínimo 1.7.890.1 — 27/10/2026

### Evidencia aportada por el dispositivo

Producto de prueba: Apple iPhone 15 Plus 256GB. Informe generado a las 04:12:03 GMT+01:00 en Android 17 / API 37.

- Wayback CDX encontró cuatro capturas candidatas, y el dispositivo descargó cuatro páginas HTML con HTTP 200.
- El extractor detectó ofertas estructuradas compatibles y registró cuatro precios verificados en las capturas Wayback; Memento/Arquivo.pt comunicó otras dos observaciones.
- La fase 19 terminó con cero puntos nuevos en el producto seleccionado, cero duplicados válidos confirmados y cinco precios/candidatos rechazados. Por tanto, extracción y persistencia divergieron: encontrar/validar el precio no implica que Room acepte la observación.
- CDX por prefijo agotó el tiempo (~16,8 s); la ventana trimestral acotada agotó el tiempo (~7,0 s). La fuente devolvió una respuesta válida parcial, pero no completó la búsqueda. Este dato no se interpreta como ausencia de capturas en el archivo.
- La ficha no debe decir que este intento amplió el histórico: según el informe, se añadieron **0 fechas nuevas**.

### Causas concretas identificadas en el código

1. **Parámetro de seguimiento `srsltid`.** El informe y el HAR muestran una consulta de Arquivo.pt con `srsltid`, mientras que la URL de captura histórica apunta a la misma página sin ese parámetro. `normalizedProductPageKey` no lo eliminaba de la clave de identidad; la comparación podía concluir erróneamente que el destino de la captura no coincidía. Se añadió `srsltid` a la lista de parámetros de seguimiento y una prueba de persistencia con URL de entrada y snapshot canónico.

2. **Captura de contenido `mp_` de Arquivo.pt.** El cliente Memento genera URLs como `https://arquivo.pt/wayback/{timestamp}mp_/https://tienda/...`, pero `parseArchivedSnapshotIdentity` solo aceptaba el formato simple y `id_`. Se amplió el analizador para aceptar `mp_` y se actualizó la prueba de Room para utilizar una captura de contenido real de ese tipo y comprobar que el punto se conserva tras recargar el histórico.

3. **Diagnóstico insuficiente de los rechazos.** La fase 19 solo presentaba un contador agregado de capturas rechazadas. `ArchivedHistoryPersistenceResult` ahora incorpora un mapa de códigos de motivo y el ViewModel resume los recuentos por código en el informe. Se distinguen causas como URL de captura inválida, destino distinto, timestamp no coincidente, divisa/precio/fecha inválidos, identidad del producto no coincidente o precio implausible.

### Cambios y pruebas de regresión

- [Aceptar capturas Arquivo.pt `mp_`](https://github.com/bonillamarquezpablo-lab/00157916/commit/f825edfc2426a45ebc6dfa264d1613ceb07df857).
- [Ignorar `srsltid` como parámetro de seguimiento](https://github.com/bonillamarquezpablo-lab/00157916/commit/f1a214fce74c35fcc813abecfe83657c63f41e09).
- [Exponer causas de rechazo en fase 19](https://github.com/bonillamarquezpablo-lab/00157916/commit/354aa2feaade6a6305123afe10a9907f95c3449a).
- [Prueba Memento con `mp_`](https://github.com/bonillamarquezpablo-lab/00157916/commit/eb594c93a6e7fcaf4d75218c32e96e0c048e9991).
- [Prueba URL con `srsltid`](https://github.com/bonillamarquezpablo-lab/00157916/commit/1294ce80a82286e34f499c7af3450ea632537c9d).
- [Ajustar la prueba para esperar ambos puntos archivados válidos](https://github.com/bonillamarquezpablo-lab/00157916/commit/8ad0e11e7dabc3bc850e48eb89339011016e9878).

**Incidencia de pruebas:** la ejecución 897 falló en `invalidLegacyArchiveRowDoesNotBlockValidatedCaptureForSameDay` con `expected 1 but was 2`. El soporte nuevo de `mp_` hace visibles tanto la captura Wayback como la captura Arquivo.pt válida; la aserción antigua de una sola observación era incorrecta. Se actualizó para exigir ambos puntos y verificar sus precios. Esa ejecución fallida no produjo una APK.

**Resultado automatizado final:** [run 898 de GitHub Actions](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40444277910) terminó correctamente sobre el SHA de código/pruebas `8ad0e11e7dabc3bc850e48eb89339011016e9878`.

- `:app:testDebugUnitTest`: **PASS**.
- `:app:compileDebugAndroidTestKotlin`: **PASS**.
- `:app:assembleSideload`: **BUILD SUCCESSFUL**.
- Paquete `com.aistudio.preciominimo.esrxvt`; versión `1.7.898.1`; `versionCode 99801`.
- APK no depurable; huella SHA-256 del certificado estable verificada: `f631a8c0a194008e77a312a78775e95837dbc66df635eae9b966e5c294add450`.
- Chucker comprobado en cinco DEX.
- SHA-256 de APK: `801ef23cf954c20a5b8ecad2b0439a5d4ae1d94eb54e3aaaa0f5402164e0949e`.
- Artefacto `minimo-apk` (id `12708365153`, ZIP SHA-256 `7db797fa323a1c546775fc67caaa4237ed070f22a0b40ca84149a8ad4b7f0449`): [descargar el APK desde el run 898](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40444277910/artifacts/12708365153). Caduca el 3 de noviembre de 2026.

### Qué no está demostrado todavía

- Que una búsqueda real posterior a estos cambios añada puntos en la APK del nuevo build.
- Que CDX deje de agotar tiempos de espera o que se recupere mejor después de HTTP 429/503.
- Que los cinco rechazos originales fueran todos debidos a `srsltid` o `mp_`: el informe original no registraba la causa individual y no permite atribuir cada rechazo a una causa. Los códigos nuevos deben identificarlo en la siguiente ejecución.
- Que el mínimo histórico visible sea correcto para una batería de productos reales.

**Siguiente paso de aquella revisión (27/10):** instalar y repetir la búsqueda del iPhone 15 Plus 256GB. Esta nota queda como antecedente histórico. El hito manual comunicado el 28/10 indica que una búsqueda posterior ya consiguió mostrar precios históricos en la gráfica; falta asociar esa observación a su versión exacta e informe para medirla y reproducirla.


## 10. Hito positivo — precios históricos visibles en la gráfica y distinción de páginas — 28/10/2026

### Resultado nuevo comunicado desde el uso real

El usuario ha confirmado que **la gráfica por fin muestra precios históricos**. Este resultado posterior corrige la situación descrita en informes antiguos donde se descargaban páginas válidas, pero la búsqueda terminaba con cero puntos nuevos. No se debe seguir presentando aquellos intentos de 1.7.835.1, 1.7.838.1 o 1.7.890.1 como si describieran la búsqueda más reciente.

**Estado de evidencia:** resultado manual positivo comunicado por el usuario; la gráfica mostró precios históricos en al menos un caso. No se ha adjuntado en esta actualización el informe completo de esa ejecución, la versión exacta de la app, el producto probado ni los contadores de puntos nuevos/duplicados. Por tanto:
- Sí podemos registrar que el gráfico ya ha mostrado datos históricos en una prueba real observada por el usuario.
- No podemos todavía calcular la tasa de éxito global, cobertura por tienda, número medio de fechas nuevas, tiempo de respuesta ni precisión estadística.
- Los informes fallidos anteriores se conservan como regresiones históricas útiles; no se borran ni se reinterpretan retroactivamente.

### Terminología técnica: qué páginas está distinguiendo el motor

| Término técnico | Significado práctico | Ejemplo / tratamiento correcto |
|---|---|---|
| **Comercio o dominio del vendedor (merchant / retailer domain)** | Identificar a qué tienda pertenece una URL usando su dominio y el catálogo de tiendas conocido. | Phone House, PCComponentes u otra tienda. La tienda ayuda a contextualizar la URL; por sí sola no demuestra que el documento sea una ficha de producto ni que el precio sea válido. |
| **Ficha de producto (product detail page / PDP)** | Página dedicada a un producto y, a veces, a una variante concreta. | La página de un móvil concreto en Phone House. Es más útil para extraer un precio que una portada o una lista genérica, pero hay que validar modelo, capacidad y oferta. |
| **URL canónica y normalización de URL** | Reducir variantes técnicas de una dirección a una identidad estable, retirando parámetros de seguimiento o de enrutamiento que no identifican el producto. | En Phone House puede aparecer el selector de marketplace como una consulta mal formada duplicada, por ejemplo ?opt=marketplace?opt=phonehouse. El propio código lo trata como metadato de enrutamiento, no como SKU, para que no impida encontrar la misma página en el archivo. |
| **Parámetros de seguimiento** | Valores añadidos por publicidad, analítica o redirecciones que no cambian el producto. | srsltid se eliminó de la clave de identidad normalizada para permitir comparar la página con su captura archivada canónica. |
| **Página de categoría, búsqueda o listado** | Página con varios productos o resultados; no equivale a una ficha individual. | No aceptar cualquier precio de un listado como precio del producto consultado sin comprobar la identidad exacta. |
| **Captura archivada / snapshot / replay** | Copia de una página guardada por un archivo web, identificada por una fecha de captura y una dirección de destino. | Wayback usa formatos de captura como id_; Arquivo.pt puede usar mp_. Hay que validar tanto que la URL sea realmente de archivo como que su destino corresponda a la página comercial correcta. |
| **Datos estructurados de producto y oferta (Product / Offer, JSON-LD o marcado comercial)** | Datos de la página que pueden contener nombre, precio, moneda, disponibilidad u otros campos. | Son una fuente útil para extraer el precio; siguen necesitando validación de producto, variante, moneda y fecha de captura. |
| **Identidad de variante** | Distinguir modelo, almacenamiento, memoria, color o versión cuando son relevantes para el precio. | Un móvil de 256 GB no debe mezclar precios del modelo de 128 GB, de una funda o de un accesorio. |
| **URL archivada auténtica frente a URL normal etiquetada como archivo** | Comprobar la estructura de la URL archivada, no confiar solo en una etiqueta interna. | Las pruebas de persistencia cubren URLs normales de tienda y capturas válidas de Wayback / Arquivo.pt para evitar que una URL comercial común cuente como captura o bloquee una válida. |

### Qué está respaldado por el código y qué no debe exagerarse

- El repositorio tiene un modelo de tiendas y un análisis de URL que contempla tienda identificada y dominios comerciales conocidos: [Store.kt](https://github.com/bonillamarquezpablo-lab/00157916/blob/main/app/src/main/java/com/example/data/model/Store.kt) y [ProductRepository.kt](https://github.com/bonillamarquezpablo-lab/00157916/blob/main/app/src/main/java/com/example/data/repository/ProductRepository.kt).
- Hay una prueba específica para normalizar la URL de Phone House antes de la búsqueda histórica: [WaybackPriceHistoryClientTest.kt](https://github.com/bonillamarquezpablo-lab/00157916/blob/main/app/src/test/java/com/example/data/remote/WaybackPriceHistoryClientTest.kt). También consta el cambio de prueba de URL canónica de Phone House en [el commit de regresión](https://github.com/bonillamarquezpablo-lab/00157916/commit/f1d32c6236f4).
- La validación de URLs archivadas y los formatos de Wayback / Arquivo.pt se documentan en [PriceObservationDao.kt](https://github.com/bonillamarquezpablo-lab/00157916/blob/main/app/src/main/java/com/example/data/local/PriceObservationDao.kt) y en las pruebas de persistencia.
- Esto describe identificación de comercios, normalización de direcciones y validación de capturas. **No significa que exista un clasificador universal perfecto capaz de reconocer automáticamente cualquier tipo de página de cualquier web.** La cobertura debe medirse con ejemplos reales de varias tiendas y tipos de página.

### Incidencia de compilación que queda separada del motor histórico

El workflow [run 998](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40618739212), asociado al HEAD de código ef512e3d850eb2a9085636036c10e6683435b8b6, terminó con error de compilación en ProductDetailSheet.kt: referencia Icons.Filled.Info no resuelta en las líneas 843 y 936. Este fallo pertenece al cambio de interfaz de detalles del producto; no demuestra un fallo del motor histórico. No hay que distribuir una APK de ese HEAD hasta corregirlo y volver a ejecutar las comprobaciones.

### Acciones prioritarias tras el nuevo hito

1. Conservar y exportar el informe de 22 fases de una búsqueda en la que la gráfica muestre precios históricos; anotar versión, producto consultado, fecha y dispositivo.
2. Confirmar en el informe cuántos puntos se añadieron realmente a Room, cuántos eran duplicados, las fechas exactas y qué fuentes aportaron cada punto. Contrastar los puntos guardados con los puntos dibujados en la gráfica.
3. Repetir la comprobación con al menos una segunda tienda y un producto con variante exacta; incluir Phone House y otra tienda cuando haya candidatos históricos disponibles.
4. Medir por separado éxito de CDX/Wayback, Memento/Arquivo.pt, descarga de HTML, extracción de precio, validación de identidad y persistencia. No fijar tasas porcentuales hasta tener suficientes búsquedas comparables.
5. Mantener intactos los datos locales, el histórico ya recuperado y la ruta de dibujo de la gráfica. Cualquier mejora posterior debe ser pequeña, aislada y acompañada de regresión.
6. Corregir por separado el error de compilación de la interfaz del run 998; no mezclar ese arreglo de UI con los cambios del motor histórico.

**Conclusión a 28/10/2026:** se registra un avance decisivo: el histórico ya es visible en la gráfica según la prueba manual comunicada. La siguiente etapa no es rehacer el motor ni cambiar de tecnología por moda; es preservar el resultado, guardar su evidencia reproducible y medirlo en varias tiendas y productos.


## 11. Cierre de actualización documental — 28/10/2026

Esta sección consolida el hito positivo comunicado el 28/10 y aclara el estado técnico sin sobrescribir la línea base ni las incidencias anteriores.

### Estado de evidencia actualizado
- **Resultado manual positivo:** el usuario informa que la gráfica ya muestra precios históricos. Se registra como evidencia manual de que al menos un caso ha llegado a mostrar datos; no se asigna todavía una versión exacta ni una tasa de éxito porque falta adjuntar el informe completo de esa ejecución.
- **Histórico anterior preservado:** los informes 1.7.835.1, 1.7.838.1 y 1.7.890.1 siguen siendo referencias de fallos/limitaciones de sus ejecuciones concretas, no una descripción automática del estado más reciente.
- **Error de compilación independiente:** el run 998 del SHA `ef512e3d850eb2a9085636036c10e6683435b8b6` falló por la referencia `Icons.Filled.Info` no resuelta en `ProductDetailSheet.kt`. No distribuir APK de ese SHA. Este error es de interfaz y no prueba por sí mismo un fallo del motor histórico.
- **Última APK cuya compilación quedó documentada como correcta en el material consultado:** 1.7.898.1, run 898, SHA de código/pruebas `8ad0e11e7dabc3bc850e48eb89339011016e9878`. No atribuirla a commits posteriores.

### Clasificación de páginas que debe usar el diagnóstico
1. **Dominio/comercio (retailer domain):** identifica la tienda, pero no garantiza que la página sea una ficha de producto.
2. **Ficha de producto (PDP):** página dedicada a un producto o variante; validar modelo y capacidad antes de aceptar su precio.
3. **Categoría/listado/búsqueda:** contiene varios artículos; no asociar un precio a la consulta sin resolver la identidad del artículo concreto.
4. **URL canónica normalizada:** elimina parámetros de seguimiento como `srsltid` cuando no cambian la identidad, y normaliza variantes de URL/redirecciones conocidas.
5. **Captura archivada (snapshot/replay):** comprobar que la URL sea realmente de Wayback/Arquivo.pt y que el destino y timestamp sean coherentes. Reconocer los formatos relevantes, incluidos Wayback `id_` y Arquivo.pt `mp_`.
6. **Product/Offer estructurado:** señal útil para extraer nombre, precio y moneda, pero no sustituye la validación de identidad, variante, fecha y plausibilidad.

### Reglas de aceptación y diagnóstico
- El color puede tratarse como atributo no bloqueante cuando el usuario ha indicado que le da igual; modelo y capacidad de almacenamiento sí deben mantenerse como criterios relevantes cuando se han solicitado explícitamente.
- No rechazar una ficha solo porque tenga parámetros de seguimiento o una variante técnica de URL.
- Separar en el informe: páginas candidatas, páginas descargadas, ofertas extraídas, precios válidos, motivos de rechazo, duplicados y puntos nuevos persistidos en el producto seleccionado.
- La fase de guardado solo cuenta como éxito si realmente se insertó al menos un punto nuevo en el producto seleccionado; encontrar capturas o extraer precios no equivale a ampliar el histórico.
- No contar una captura repetida como fecha nueva y no eliminar ni reconstruir destructivamente los datos de Room.
- Mantener las 22 fases y los detalles por captura. Registrar la fase no ejecutada como tal; no inferir que falló si no llegó a ejecutarse.

### Siguiente validación reproducible
1. Corregir la referencia de interfaz del run 998 en un cambio separado.
2. Ejecutar tests unitarios, compilación instrumentada y `assembleSideload` desde el nuevo SHA; verificar firma, versión, manifiesto y Chucker.
3. Instalar la APK resultante y repetir una búsqueda real con informe de 22 fases.
4. Comparar los puntos persistidos en Room con los dibujados en la gráfica, anotando producto/variante, tienda, versión, fechas nuevas, duplicados y motivos de rechazo.
5. Repetir con Phone House y al menos otra tienda cuando haya capturas candidatas. Solo entonces cuantificar precisión, cobertura y tiempos.

**Criterio de cierre:** la documentación del hito queda consolidada; la eficacia global sigue sin medirse hasta adjuntar el informe real reproducible. La corrección de compilación del run 998 se aplicó posteriormente en el commit 6433ea7 (import explícito de `androidx.compose.material.icons.filled.Info`); su validación automática se sigue en la sección 12. La prueba manual posterior del motor histórico sigue pendiente.


## 12. Seguimiento del fallo de compilación y corrección — 28/10/2026

- La ejecución [#998](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40618739212) falló durante `:app:testDebugUnitTest`, porque la compilación de Kotlin falló antes de generar informes XML. Las dos referencias problemáticas estaban en `ProductDetailSheet.kt` (líneas 843 y 936): `Icons.Default.Info` sin importar la extensión `androidx.compose.material.icons.filled.Info`.
- La validación Wayback y las tareas posteriores se omitieron por la dependencia del trabajo: no fue un fallo de la prueba histórica ni prueba de que el motor estuviera roto.
- Corrección aplicada en [commit 6433ea7](https://github.com/bonillamarquezpablo-lab/00157916/commit/6433ea74a8c8a89aa570c1e10a20dbb6b08aebbd): se añadió el import explícito del icono `Info`, sin cambiar la lógica del motor histórico ni la identidad/validación de productos.
- La ejecución automática posterior es [run #999](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40621337362). En la última consulta estaba en curso; **no se declara aprobado ni se afirma que haya producido APK hasta que GitHub confirme el resultado final**.
- Próximo criterio: confirmar que unit tests, compilación instrumentada, APK, firma y comprobación de Chucker terminan correctamente. Después probar la APK en dispositivo con una búsqueda real y conservar su informe de 22 fases.


## 13. Corrección acotada de Phone House y diagnóstico de CDX — 28/10/2026

### Qué mostró el informe 1.7.999.1 y el HAR
- En la consulta del Xiaomi Mi 10 Pro Gris, la URL de Phone House incluía `colorgris=` vacío y el selector de marketplace `opt`. La búsqueda exacta respondió sin capturas y las consultas por prefijo/ventana trimestral incluyeron tiempos de espera o HTTP 503.
- Una respuesta CDX válida vacía no permite cubrir las consultas que agotaron el tiempo. El diagnóstico anterior combinaba ambos resultados y podía afirmar que no había histórico a pesar de que la búsqueda quedaba incompleta.

### Correcciones aplicadas en código
- [Normalización del selector Phone House y resumen de fallos parciales](https://github.com/bonillamarquezpablo-lab/00157916/commit/4c5c43f625fcf8fd56478bdbc7becdd9b66f96f0): `colorgris` solo se elimina de la URL de archivo cuando su valor está vacío y el dominio identificado es `phonehouse.es`. El selector `opt` sigue siendo metadato específico de Phone House.
- [Preservación de color en identidad de variante](https://github.com/bonillamarquezpablo-lab/00157916/commit/a91f80eba9db9c634da49dca7dcf7e9ba436e325) y [alcance limitado al dominio Phone House](https://github.com/bonillamarquezpablo-lab/00157916/commit/74ef0a0d80fa4be0af1d5f5f2d365b0e45dc5f86): si `colorgris` tiene un valor (por ejemplo `gris`), se conserva en la URL canónica y solo se trata como atributo de variante en Phone House. En otros comercios no se interpreta como clave especial de variante.
- [Pruebas de aislamiento y de diagnóstico incompleto](https://github.com/bonillamarquezpablo-lab/00157916/commit/d80bd01a84bd18f1479fd4f78f94211b7c89857e) y [aserción de identidad de color](https://github.com/bonillamarquezpablo-lab/00157916/commit/9ded15bedf5d5586af73bb8af07b1d73c841582c).

### Cambio de diagnóstico
Cuando al menos una consulta CDX responde válidamente sin capturas, pero otra falla o agota el tiempo, el informe debe decir que la búsqueda está incompleta y que no se puede concluir que el histórico esté vacío. Si todas las respuestas examinadas son válidas y vacías, sí puede informar que no se encontraron capturas en las rutas consultadas. Esto no declara que el archivo entero carezca de datos.

### Alcance y validación
La modificación del parámetro está limitada al dominio Phone House y al valor vacío de `colorgris`; los valores de color no vacíos se conservan. Las demás tiendas mantienen su normalización previa. La ejecución de CI correspondiente a los últimos cambios debe terminar antes de marcar la corrección como probada. Los HTTP 503 y timeouts de Internet Archive son fallos externos transitorios; el código puede acotarlos y diagnosticarlos, pero no garantizar que el servicio responda en cada intento.

**Próxima prueba real:** repetir la búsqueda del Xiaomi Mi 10 Pro Gris con el informe completo, comprobar que CDX recibe primero la URL canónica sin selectores vacíos, y verificar puntos históricos nuevos/fechas y gráfica. Repetir después un producto de otra tienda para confirmar ausencia de regresiones.


## 14. Informe real de Phone House: extracción de precio y validación de variante — 28/10/2026

### Evidencia de ejecución 1.7.1007.1
El informe del Xiaomi Mi 10 Pro Gris demuestra que CDX sí encontró cinco capturas y que la aplicación descargó su HTML con HTTP 200. El fallo principal ocurrió después, durante la validación/extracción:
- La captura archivada de 2022 contiene un Product/Offer JSON-LD con precio de 989 EUR y un SKU de Phone House; el título de la página expresa la variante como `Xiaomi Mi 10 Pro 256GB+8GB RAM`, omitiendo el color en el título.
- Varias capturas no contienen JSON-LD ni metadatos de precio, pero muestran la oferta principal en el bloque `.buy-box-1 .precio.mb-10`.
- La captura de noviembre de 2024 es de un producto reacondicionado (380 EUR, con precio recomendado tachado de 880 EUR). No se debe mezclar en el histórico de producto nuevo.
- El HTTP 429 de Availability sucedió después de las consultas CDX y las descargas; no explica por sí solo por qué se rechazaron las cinco capturas.

### Cambios aplicados
- Commit [cee75f6](https://github.com/bonillamarquezpablo-lab/00157916/commit/cee75f6f10056555d434d634d375eec073af3350): extractor de precio de Phone House acotado al bloque principal de compra y a su importe `strong`; valida la condición del artículo y no reutiliza el precio tachado.
- Commit [6f7a330](https://github.com/bonillamarquezpablo-lab/00157916/commit/6f7a33078aa0599e69bc8fcc5428426bbf62ee8e): normaliza el separador de capacidad de URL `256gbplus8gb-ram` para reconocer 256 GB de almacenamiento.
- Commit [21e4292](https://github.com/bonillamarquezpablo-lab/00157916/commit/21e42920ca7cb03fed297c953c88d1d99ae43093): corrige el escape Kotlin del reemplazo regex de ese normalizador.
- Se añadieron pruebas para oferta estructurada con URL exacta, precio visible sin JSON-LD, rechazo de reacondicionado, rechazo de capacidad explícitamente distinta y distinción entre almacenamiento y RAM.

### Salvaguardas
La corrección no convierte cualquier precio de la página en válido. Se sigue exigiendo que el modelo coincida, que una capacidad distinta se rechace cuando la selección la especifica, que las capacidades incluidas solo en el título archivado se confirmen con la URL exacta, que la moneda sea EUR y que la condición del producto coincida. El parser de precio visible específico solo se usa para `phonehouse.es`; las tiendas restantes conservan su extractor previo.

### Estado de validación
La ejecución [#1011](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40661629344) detectó cuatro fallos de regresión en las pruebas unitarias. Se corrigieron la separación entre almacenamiento y RAM, la confirmación de capacidad por URL exacta y el tratamiento del separador `plus` en slugs de productos. La corrección final está en [commit 375c53c](https://github.com/bonillamarquezpablo-lab/00157916/commit/375c53c19c3c5195c8ffb356ae9dbb4bedf881ca). La ejecución [#1013](https://github.com/bonillamarquezpablo-lab/00157916/actions/runs/40662902782) terminó con éxito: pruebas unitarias, compilación de pruebas Android, APK, verificación de paquete/versión/firma y presencia de Chucker superadas. Generó Mínimo 1.7.1013.1 (versionCode 111301), paquete `com.aistudio.preciominimo.esrxvt`, certificado SHA-256 `f631a8c0a194008e77a312a78775e95837dbc66df635eae9b966e5c294add450` y artefacto `minimo-apk` (SHA-256 del APK: `0171107a22057b0efefac83452eab0aabf53581e11e0c54f60d366356ed8aafd`). La sonda automática de Wayback se omitió y aún falta instalar la APK en el móvil y repetir una búsqueda real; por tanto, no se declara todavía recuperado y guardado el histórico del Xiaomi. El HTTP 429 sigue siendo un límite temporal externo y debe respetarse la pausa indicada por la aplicación.


## 15. Validación real en dispositivo de Mínimo 1.7.1013.1 — 28/10/2026, 14:46 (hora peninsular)

### Resultado real
El informe del Xiaomi Mi 10 Pro Gris confirma que el extractor corregido recupera y persiste tres puntos históricos nuevos:
- CDX devolvió cinco capturas candidatas.
- Las cuatro páginas que se llegaron a descargar devolvieron HTTP 200.
- Se verificaron tres precios válidos, sin rechazos de identidad/URL ni de moneda/fecha.
- Room confirmó tres filas nuevas, cero duplicados y cero rechazos.
- Resultado final de persistencia: `PASSED`.

### Fallo interno pendiente: fase 29
El mismo informe contiene `Actualización solicitada=true; ficha refrescada=false; puntos nuevos del producto seleccionado=3`. En `PriceTrackerViewModel.kt`, tras guardar, la app espera hasta 10 segundos a que `repository.allProductsStream` emita el producto elegido con algún punto de evidencia archivada. Esa espera termina sin encontrar el resultado esperado; por eso no se confirma `selectedProductRefreshed` ni se reemplaza el producto actualmente seleccionado en el estado de UI. El registro no demuestra pérdida de datos: los tres puntos ya quedaron guardados. Hace falta instrumentar el resultado de esa espera (timeout/excepción, producto encontrado, número de puntos de archivo) y asegurar que la ficha abierta recibe la versión actualizada desde Room.
- No marcar toda la operación como plenamente correcta sin reflejar esta incidencia: la persistencia fue correcta, pero el refresco visible quedó sin confirmar.

### Fallo de diagnóstico en la captura de noviembre de 2024
El HAR contiene el HTML de la captura `20241111213737`. Su bloque principal de compra incluye `.estado-outlet.outlet` con el estado **Reacondicionado / Estado: Funcional** y un precio principal de 380 EUR, junto a 880 EUR como precio recomendado tachado. Para la ficha seleccionada sin condición reacondicionada, no aceptar ese importe es correcto. Sin embargo, el informe lo clasifica como `ARCHIVE_PRICE_METADATA_MISSING` y como si no hubiera un número positivo. Es un motivo de rechazo mal clasificado: debe registrarse explícitamente como conflicto de condición/estado del artículo, sin contaminar el histórico del producto estándar.
  
### Pausa HTTP 429 y control de flujo
La última petición del HAR de esta búsqueda, a las 14:46:09, fue a `archive.org/wayback/available` y devolvió HTTP 429. El registro de la app dice a las 14:46:08 que ya había guardado suficientes fechas y que omitía consultas adicionales. La petición de Availability sucede inmediatamente después; hay que comprobar si estaba ya en vuelo o si el cortocircuito no cubre esa rama. El 429 es una limitación externa y no invalida los tres puntos guardados, pero no debería hacerse una consulta nueva después de alcanzar el objetivo.
  
### Conclusión de esta prueba
La extracción y el guardado históricos ya están funcionando en el dispositivo. Los próximos defectos a corregir son: **(1)** refresco/confirmación de la ficha y gráfica (fase 29), **(2)** código diagnóstico de precio rechazado por condición reacondicionada, **(3)** coherencia entre cortocircuito inteligente y peticiones Availability posteriores. El HAR combina llamadas de búsquedas anteriores; para esta ejecución las entradas relevantes son las últimas peticiones de las 14:45:52–14:46:09.
