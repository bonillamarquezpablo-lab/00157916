# Ficha técnica viva — motor de precio mínimo histórico de Mínimo

> Documento de referencia del proyecto. Se actualiza cuando una capacidad cambia de estado y queda respaldada por pruebas. No convertir una función escrita en código en una mejora de eficacia hasta que esté medida.

**Versión observada en el último informe aportado:** 1.7.838.1  
**Última revisión de evidencias:** 27 de octubre de 2026
**Referencia de código revisada:** `main` · HEAD `5b8ff673e1477359e3e637d2ed7820960511e7ca`
**CI / APK actual:** run 890 finalizado correctamente; pruebas unitarias, compilación instrumentada, build sideload, firma, paquete y Chucker verificados.
**APK generada:** `1.7.890.1` · versionCode `99001` · artifact `minimo-apk` (se conserva hasta el 3 de noviembre de 2026).  
**Estado general:** motor implementado con rutas de archivo y validación; eficacia global todavía sin métricas comparables.  
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
