# Ficha técnica viva — motor de precio mínimo histórico de Mínimo

> Documento de referencia del proyecto. Se actualiza cuando una capacidad cambia de estado y queda respaldada por pruebas. No convertir una función escrita en código en una mejora de eficacia hasta que esté medida.

**Último informe histórico con versión documentada:** 1.7.898.1 (run 898). Nuevo hito manual comunicado el 28/10/2026: versión exacta de la búsqueda aún no registrada.  
**Última revisión de evidencias:** 28 de octubre de 2026
**Referencia de código revisada antes de esta actualización documental:** `main` · HEAD `ef512e3d850eb2a9085636036c10e6683435b8b6` (incluye cambios de interfaz; no equivale a una APK válida).
**Última APK verificada documentada:** run 898 produjo la APK 1.7.898.1 desde un SHA anterior. El run 998 del HEAD ef512e3d850eb2a9085636036c10e6683435b8b6 falló al compilar ProductDetailSheet.kt por una referencia Info no resuelta; no produjo una APK de ese HEAD.
**APK histórica verificada:** `1.7.898.1` · versionCode `99801` · artefacto del run 898. No atribuir esta APK a los commits posteriores de main.  
**Estado general:** hito positivo de uso real comunicado el 28/10: la gráfica ya muestra precios históricos. Es una observación manual de resultado visible; faltan la versión exacta, el informe copiable y los contadores para medir la eficacia global.  
**Regla de oro:** conservar las fichas, precios e históricos de Room. No borrar datos locales ni contar duplicados como hallazgos nuevos.

## 1. Qué es este motor

El motor intenta recuperar capturas antiguas de páginas comerciales, identificar si pertenecen al mismo producto y variante, extraer precios verificables y guardar únicamente observaciones válidas y no duplicadas. La fecha de una captura archivada es la fecha de observación histórica, no una prueba de que la oferta siga disponible hoy.

## 2. Ficha técnica — capacidades y evidencia

| Área | Capacidad registrada | Estado actual | Qué falta para darla por demostrada |
|---|---|---|---|
| Fuentes | Internet Archive / Wayback CDX | Implementado en código | Medir éxito y tiempo por tipo de consulta, tienda y producto |
| Fuentes | Availability / rutas alternativas | Implementado en código | Medir qué ruta aporta capturas útiles y cuándo conviene activarla |
| Respaldo | Memento y recuperación de Arquivo.pt | Implementado en código | Repetir casos reales de replay normal y documento `mp_`; registrar fechas nuevas |
| Selección | Muestreo histórico temporal, incluida selección trimestral | Implementado en código | Medir cobertura real y huecos del histórico por producto |
| URLs | Normalización de direcciones, variantes candidatas y redirecciones | Implementado en código | Conjunto de pruebas de URLs problemáticas y tasa de resolución |
| Extracción | HTML y datos estructurados Product/Offer (JSON-LD) | Implementado en código | Medir precios válidos, rechazo correcto y falsos positivos/negativos |
| Identidad | Comprobación de título, modelo, capacidad y variante | Implementado en código | Medir aciertos con fichas de referencia; no mezclar colores/capacidades |
| Validación | Fecha histórica, importe, EUR y límites razonables | Implementado en código | Registrar cada causa de rechazo de forma cuantificable |
| Base de datos | Guardado local en Room y deduplicación | Implementado en código | Medir puntos nuevos frente a capturas repetidas y verificar no pérdida de datos |
| Gráfica | Visualización de observaciones verificadas sin inventar una tendencia con un único punto | Implementado en código | Verificar todas las ventanas temporales y sincronización con la ficha |
| Diagnóstico | 22 fases generales y filas de detalle por captura | Implementado en código | Medir duración por fase y garantizar que el resumen no contradiga el detalle |
| Red | Pausa global cuando Internet Archive responde HTTP 429 | Implementado en código | Medir frecuencia de 429 y recuperación después de que termine la pausa |
| Red de diagnóstico | Chucker en compilaciones debug y sideload | Integración configurada | Confirmar en dispositivo que registra las llamadas esperadas y comprobar redacción de datos sensibles |
| Eficacia global | Porcentaje de búsquedas que añaden fechas nuevas | **Sin medir** | Añadir métricas persistentes o exportables |
| Eficacia global | Precisión de identidad de producto y variante | **Sin medir** | Crear casos de referencia etiquetados y calcular aciertos/fallos |
| Rendimiento | Mediana, percentil 95, consumo de memoria | **Sin medir** | Instrumentar por fase y ejecutar pruebas repetibles |
| Estabilidad | Errores por cada 100 búsquedas y regresiones por versión | **Sin medir** | Agrupar fallos por causa y comparar versiones en una batería fija |

### Significado de los estados

- **Implementado en código:** la ruta o función existe. No demuestra por sí sola una tasa de éxito.
- **Verificado en pruebas:** existe una prueba reproducible que cubre ese comportamiento.
- **Validado en dispositivo:** se ha comprobado en una instalación real y se ha guardado el informe asociado.
- **Sin medir:** aún no existe una cifra fiable; el campo se mantiene vacío a propósito.
- **En investigación:** hay evidencia de un problema sin una solución confirmada.

## 3. Línea base comprobable — informes reales

### Informe 1.7.835.1 — 26/10/2026, 00:43

- Wayback devolvió cuatro capturas y se descargaron sus cuatro páginas HTML.
- Las cuatro capturas coincidían con el título/modelo y contenían ofertas estructuradas compatibles.
- Memento/Arquivo.pt recuperó otras dos observaciones candidatas.
- Las observaciones ya existían en la base local; la búsqueda no añadió fechas nuevas.
- Las consultas CDX por prefijo y por bloques anuales terminaron por timeout; después apareció HTTP 429.
- Conclusión: hubo extracción de capturas válidas, pero **cero puntos históricos nuevos en ese intento**. No debe computarse como una búsqueda que amplió el histórico.

### Informe 1.7.838.1 — 26/10/2026, 01:27

- Solo se superaron las fases 1 y 2.
- La pausa global por HTTP 429 detuvo la operación antes de consultar CDX o Availability.
- Las otras 20 fases quedaron sin ejecutar.
- Conclusión: este informe prueba que la pausa se respetó; **no permite medir la eficacia del motor ni validar una búsqueda histórica completa**.

### Campos de línea base que aún deben medirse

| Indicador | Línea base |
|---|---|
| Búsquedas históricas ejecutadas hasta el final | — |
| Búsquedas que aportan al menos una fecha nueva | — |
| Capturas descargadas / capturas identificadas por el índice | — |
| Precios aceptados / páginas analizadas | — |
| Duplicados evitados por búsqueda | — |
| Mediana y p95 de duración | — |
| Porcentaje de HTTP 429 | — |
| Porcentaje de timeout CDX | — |
| Falsos positivos de modelo/variante | — |
| Errores por 100 búsquedas | — |
| Antigüedad máxima recuperada por producto y tienda | — |

No rellenar estas celdas con estimaciones. Cada cifra debe llevar método de cálculo, número de casos y fecha de medición.

## 4. Plan actualizado de evolución — 10 fases

| Fase | Trabajo | Entrega verificable | Estado |
|---|---|---|---|
| 1 | Ficha viva y línea base de métricas | Ficha consultable desde Ajustes y documento versionado; métricas aún vacías hasta instrumentarlas | Base creada; métricas pendientes |
| 2 | Subcategorías y filtros locales | Móviles, tablets, power banks, cargadores, cables, fundas y otros accesorios; filtros aplicados solo a la sección seleccionada; clasificación de casos dudosos sin perderlos | En revisión |
| 3 | Chucker y diagnóstico de red | Verificar captura de tráfico en sideload/debug, identificar la llamada fallida y proteger tokens, cookies y datos sensibles en informes/exportaciones | Integración en código; validación pendiente |
| 4 | Informe de búsqueda completo | 22 fases, eventos individuales por captura, tiempos, intentos, respuesta HTTP, motivo de rechazo, duplicados y puntos realmente guardados en un informe copiable y redactado | Parcial: el informe actual ya tiene 22 fases y detalle por captura |
| 5 | Mínimo histórico y gráfica | Definición inequívoca de mínimo histórico: precio validado + producto/variante correcta + fecha; ventanas temporales fiables y gráfica sincronizada con Room | Base existente; ampliar pruebas |
| 6 | Rendimiento del muestreo CDX | Muestreo temporal con límites, tiempos de espera y paginación medidos; evitar consultas repetidas sin beneficio | En investigación: hay timeouts por prefijo/año |
| 7 | Memento / Arquivo.pt | Recuperar el documento archivado real cuando el replay devuelva una envoltura; normalizar parámetros de seguimiento y validar fechas | Código presente; validación real pendiente |
| 8 | Extracción robusta y variantes | Pruebas con varias ofertas en una página, capacidades/colores/modelos, divisas y precios de accesorios; catálogo de falsos positivos y negativos | En mejora continua |
| 9 | Estabilidad y búsqueda de errores | Pruebas de regresión, límites de concurrencia, caché, cancelación, fallos de red, memoria, cierres y tiempo de respuesta | Métricas por instrumentar |
| 10 | Validación final y APK | Unitarias + integración + casos reales; verificación de firma/versión/Chucker del APK sideload; prueba en móvil y registro de resultados | Condicionada a las fases anteriores |

Las fases no se declaran completadas porque el código compile únicamente: cada entrega necesita pruebas y evidencia. Si aparece un defecto, se corrige y se repite la prueba afectada antes de avanzar.

## 5. Hito registrado — ficha visible en la aplicación

**Fecha:** 26 de octubre de 2026  
**Capacidad que cambia:** seguimiento visible de la evolución del motor.  
**Antes:** las capacidades y limitaciones se repartían entre código e informes de diagnóstico; no había una pantalla dedicada a consultarlas.  
**Cambio realizado:** se añadió una ficha en la aplicación, accesible desde Ajustes, con fuentes y capacidades detectadas en el código, problemas abiertos, métricas sin medir, evidencia de los dos informes disponibles y hoja de ruta.  
**Prueba ejecutada:** integración de la navegación y compilación pendiente de confirmación en GitHub Actions.  
**Resultado:** el código de la ficha está integrado; todavía no se marca como APK validada hasta que termine la compilación.  
**Impacto medido:** ninguno todavía. La ficha no inventa puntuaciones ni cifras de eficacia; deja explícitos los campos pendientes.  
**Siguiente limitación pendiente:** instrumentar el motor para calcular automáticamente capturas, precios aceptados, duplicados, puntos nuevos y duración por fase.

## 6. Formato obligatorio para cada nuevo hito

Al cerrar un cambio, añadir una entrada con:

- **Fecha y versión:**
- **Capacidad que cambia:**
- **Antes:**
- **Cambio realizado:**
- **Prueba ejecutada y número de casos:**
- **Resultado:** aprobado / fallido / inconcluso.
- **Impacto medido:** fechas nuevas, éxito, tiempo, errores, memoria o coste de peticiones.
- **Regresiones comprobadas:**
- **Enlace a commit, ejecución de CI e informe:**
- **Siguiente limitación pendiente:**

## 7. Próximas tareas prioritarias

1. Confirmar el comportamiento de las tarjetas y subcategorías y probar que los filtros no cruzan familias de productos.
2. Instrumentar el diagnóstico para que distinga capturas encontradas, HTML descargado, precios válidos, duplicados y puntos nuevos guardados.
3. Construir una batería repetible de productos y tiendas reales para evaluar CDX, Memento/Arquivo.pt, identificación de variantes y mínimo histórico.
4. Medir tiempos y errores por fase antes de ajustar límites, reintentos o concurrencia.
5. Rellenar las métricas de esta ficha solo con resultados de esa batería y con informes reales de dispositivo.

**Principio del proyecto:** el objetivo no es que el motor haga más peticiones, sino que recupere más fechas históricas correctas por búsqueda, con menos errores y en menos tiempo, sin inventar precios ni dañar el catálogo local.


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
