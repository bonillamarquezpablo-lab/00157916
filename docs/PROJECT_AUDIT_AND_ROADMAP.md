# Auditoría técnica y hoja de ruta de Mínimo

**Fecha de revisión:** 2026-10-14  
**Base auditada:** `main` en `0cfb89244b916c6d86dac391ff10709a67191754`.  
**Alcance:** revisión estática del árbol, configuración Gradle/CI, historial de producto documentado y estado de las propuestas abiertas. No equivale a una auditoría de seguridad dinámica ni a una prueba manual en dispositivo.

## Resumen ejecutivo

Mínimo ya tiene una base Android con Kotlin/Jetpack Compose, Room, parsers de SerpApi, Google Shopping y Amazon.es, caché, reglas de alertas, gráficos y una suite de pruebas unitarias/Robolectric. El riesgo principal no es la falta de funciones: es demostrar que cada resultado representa el producto exacto, que el precio y el enlace son fiables y que el historial no convierte datos dudosos en hechos.

**Regla no negociable:** no fabricar precios, enlaces directos, stock, identidad del producto ni puntos históricos. Los datos de ejemplo nunca deben alimentar el historial verificado.

## Hallazgos

| Prioridad | Hallazgo | Evidencia / impacto | Acción |
|---|---|---|---|
| P0 | Las propuestas abiertas no equivalen a código integrado | PR #25, #26 y #27 estaban abiertas al revisar; no asumir que sus cambios están en `main` | Revisar cada PR por separado, comprobar base/diff y CI antes de integrar |
| P0 | CI no compila las pruebas instrumentadas Android | `.github/workflows/android.yml` ejecuta pruebas unitarias y APK, pero no la compilación de `androidTest` | Añadir `:app:compileDebugAndroidTestKotlin` como puerta de CI |
| P0 | Una búsqueda real de extremo a extremo aún requiere validación manual | La clave SerpApi debe configurarse en el dispositivo; la CI no demuestra disponibilidad ni calidad del proveedor en producción | Validar manualmente una búsqueda autorizada, con coste/cuota controlados |
| P1 | La calidad de identidad del producto es el principal riesgo de falsos positivos | Diferencias de modelo, capacidad, potencia, almacenamiento, condición y packs pueden hacer incomparables dos ofertas | Pruebas adversas por familia de producto y criterios de coincidencia estrictos |
| P1 | Los precios sospechosos requieren tratamiento separado | Un valor atípico puede ser error de extracción, variante o oferta real excepcional | No sustituir el mínimo verificado por un precio sospechoso; conservar el motivo |
| P1 | Historial real y datos importados deben ser distinguibles | Las observaciones necesitan precio, tienda, fuente, fecha del precio, fecha de captura, enlace y estado de verificación | Modelo de procedencia explícito; no extrapolar ni interpolar observaciones |
| P1 | Enlaces y coste final no están uniformados para todas las fuentes | Un resultado de búsqueda no es necesariamente la ficha del producto; envío/stock/vendedor pueden variar | Clasificar destino del enlace y mostrar coste total solo si la fuente lo acredita |
| P1 | El directorio de tiendas no implica conectores activos | La búsqueda activa documentada usa Google Shopping y Amazon.es a través de SerpApi | Integrar fuentes adicionales solo con acceso autorizado y pruebas reales |
| P2 | Caché y cuota afectan la frescura | La caché ahorra consultas, pero puede mostrar datos antiguos; la cuota limita la frecuencia | Etiquetar antigüedad, definir expiración y registrar fallos/reintentos sin bucles |
| P2 | Las alertas deben depender de observaciones verificadas | Los objetivos locales no garantizan monitorización remota continua | Alertar solo cuando una comprobación real confirma el objetivo; evitar duplicados |
| P2 | Persistencia, privacidad y operaciones requieren pruebas continuas | Room y migraciones existen; faltan validaciones de integración y flujo operativo más amplio | Tests de migración, retención/exportación y revisión de secretos/permisos |

## Hoja de ruta priorizada

### Fase 0 — Puerta de calidad (ahora)
- [x] Compilar explícitamente el código Kotlin de pruebas instrumentadas en CI.
- [ ] Confirmar pruebas unitarias, compilación instrumentada y generación de APK en el PR de esta mejora.
- [ ] Revisar el fallo CI de PR #27: la ejecución #538 registró un fallo en `ExampleRobolectricTest > Google Shopping fixture reports accepted offers and a reason for every rejected offer`, en `ExampleRobolectricTest.kt:1051`. No debe darse por validado ese PR hasta corregirlo y repetir CI.
- [ ] No fusionar cambios de búsqueda que no tengan CI verde.

### Fase 1 — Integridad de precio e identidad
- Validar producto exacto: GTIN/EAN/MPN cuando existan, modelo, variante y especificaciones.
- Separar nuevo, usado, reacondicionado, alquiler, financiación, packs y accesorios.
- Usar categorías de rechazo estables y diagnósticos que expliquen cada descarte.
- Probar respuestas SerpApi reales de ejemplo, incompletas, adversas y con campos alternativos.

### Fase 2 — Historial demostrable
- Una observación por producto/tienda/día según la regla del producto, con actualización controlada.
- Distinguir fecha del precio y fecha de captura; conservar fuente y URL.
- Calcular mínimo/media solo con observaciones válidas; no proclamar «mínimo histórico» con evidencia insuficiente.
- Importar historia de terceros solo si la fuente facilita datos verificables y su licencia/contrato lo permite; etiquetar el origen y la profundidad.

### Fase 3 — Calidad comercial
- Verificar destino de enlace, vendedor, condición, disponibilidad y frescura.
- Mostrar envío y coste total únicamente cuando estén respaldados por datos de la fuente.
- Clasificar anomalías sin ocultar automáticamente un chollo real ni incorporarlo como récord no verificado.

### Fase 4 — Fuentes y escala
- Evaluar APIs/feed autorizados por cobertura española, profundidad histórica, coste, cuotas, términos y estabilidad.
- Añadir conectores de uno en uno con contrato común y fixtures por proveedor.
- Separar credenciales del cliente cuando corresponda; nunca incluir secretos en Git o APK.

### Fase 5 — Seguimiento y experiencia
- Alertas basadas en verificaciones reales, con límites de cuota, preferencias y deduplicación.
- Mejorar filtros/ordenación/paginación sin perder la procedencia.
- Probar estados vacíos, errores, accesibilidad, rendimiento y dispositivos reales.

### Fase 6 — Preparación de producción
- Pruebas de migraciones y persistencia, privacidad, borrado/exportación y revisión de permisos.
- Observabilidad sin registrar claves ni datos sensibles.
- Pipeline reproducible de APK, artefactos, versión y lista de comprobación de publicación.

## Criterios de aceptación para cualquier mejora

1. No inventa precios, historial, enlaces, stock o especificaciones.
2. Conserva datos existentes y añade migraciones no destructivas si el esquema cambia.
3. Incluye pruebas de casos válidos, inválidos y límites relevantes.
4. CI verifica las pruebas pertinentes y la compilación del APK.
5. No afirma que hay más conectores activos de los que realmente existen.
6. No consume cuota de proveedor en pruebas automáticas.
7. Se desarrolla en rama nueva y PR independiente desde el `main` actualizado.

## Limitaciones de esta auditoría

La revisión estática no confirma que SerpApi responda correctamente con la clave del usuario, que las tiendas muestren disponibilidad real, que todos los enlaces abran el artículo esperado ni que las notificaciones funcionen en cada fabricante Android. Estas verificaciones necesitan una prueba autorizada en dispositivo o un entorno de integración adecuado.
