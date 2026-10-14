# Auditoría de búsqueda e historial — versión previa a los cambios

Esta lista se elaboró leyendo `main` antes de modificar el código. No se ha consultado ni solicitado ninguna clave de SerpApi.

## Fallos encontrados y prioridad

### P1 — No se pueden ver las ofertas que el parser descarta
`parseShoppingResultsDetailed` devuelve las ofertas aceptadas y contadores agregados, pero no conserva título, precio original ni motivo de cada descarte. En Amazon.es, `parseAmazonResults` descarta directamente y solo devuelve las ofertas aceptadas. La UI no puede mostrar los resultados que no llegaron a la lista.

### P1 — La condición y la razón del descarte se pierden entre capas
La condición usada/reacondicionada/alquiler se filtra dentro del parser. Más tarde, el repositorio vuelve a filtrar por coincidencia, vendedor y límites de precio. El mensaje final no permite saber qué regla eliminó una oferta concreta, por lo que un falso descarte no se puede investigar desde la app.

### P1 — Falta una matriz de regresión común para Google Shopping y Amazon.es
Las pruebas existentes cubren algunos casos del parser Google, pero no una matriz de muestras JSON de ambos proveedores que indique decisión y motivo para: enlaces Google, usado/reacondicionado, alquiler, cuota mensual (/mes), pack (12x300 ml), variante distinta de capacidad/potencia, precio sin símbolo €, dólares, título no coincidente y precio atípicamente bajo.

### P2 — Algunas reglas mezclan datos de origen con política de aceptación
El parser Google exige un precio formateado en euros si no recibe precio estructurado; Amazon puede aceptar un número estructurado en el escaparate español sin etiqueta monetaria. Esta diferencia debe documentarse en pruebas para evitar cambios accidentales. La falta de símbolo por sí sola no debe aceptarse en Google si la moneda no está respaldada; Amazon.es sí puede usar el contexto de tienda cuando el precio estructurado es numérico y no contradice una moneda extranjera explícita.

### P2 — No hay un motivo estable para cada categoría relevante
Los motivos que necesita la UI («segunda mano», «alquiler o cuota», «precio sospechoso», «otro modelo», «sin precio válido») no están modelados como dato. Los contadores actuales no bastan para una auditoría reproducible.

## Alcance de la corrección
- Introducir diagnósticos por oferta en memoria para resultados aceptados/descartados, sin persistir descartes en Room.
- Añadir pruebas con respuestas JSON representativas de SerpApi Google Shopping y Amazon.es, sin credenciales.
- Mostrar «Ver descartados» en Buscador online y conservar el diseño general.
- Corregir solo discrepancias demostradas por pruebas. El historial seguirá recibiendo exclusivamente ofertas válidas aceptadas; abrir la lista de descartados no guardará precios ni alterará historial.
