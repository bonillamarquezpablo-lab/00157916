# Plan de Integración de Rama y Reparación de Búsqueda SerpApi

Plan estructurado para salvaguardar el estado actual del proyecto mediante copia de seguridad (ZIP), auditar la conectividad con el repositorio de GitHub `bonillamarquezpablo-lab/00157916` (rama `feature/online-shopping-search`) y reparar el formateo de consultas y parseo de precios de SerpApi en la aplicación nativa Android Mínimo.

---

### Decisiones Críticas y Estado de Verificación

> [!IMPORTANT]
> **Estado de la Conexión Git en Google AI Studio:**
> 1. **Sin repositorio Git local:** El entorno de ejecución en la nube de Google AI Studio no contiene un directorio `.git` directo ni credenciales locales para acceder a repositorios privados remotos.
> 2. **Sin riesgo de push o sobrescritura:** No se realizará ninguna operación de `git push`, fusión (`merge`) ni modificación destructiva.
> 3. **Protocolo de Importación Seguro:** Antes de alterar cualquier archivo, se instruye la descarga del ZIP de respaldo desde el menú superior de Google AI Studio.

- **Paso 0 Confirmado:** Descargar copia de seguridad en ZIP antes de cualquier inspección o modificación de código.
- **Paso 1 Confirmado:** Procedimiento para alinear la rama `feature/online-shopping-search` sin sobreescrituras en GitHub ni en AI Studio.
- **Paso 2 Confirmado:** Reparación del formateo de consultas (`q`, `engine=google_shopping`, `gl=es`, `hl=es`) y parseo robusto de respuestas JSON (`extracted_price`, `shopping_results`, `merchants`, monedas en €) en `SerpApiClient.kt`.

---

## 1. Diagnóstico de Repositorio y Protocolo de Importación Segura

### Diagnóstico Técnico Actual
- **Repositorio conectado en plataforma:** `bonillamarquezpablo-lab/00157916` (privado).
- **Entorno del contenedor:** La sesión de terminal no dispone de `.git` ni de token de autenticación para realizar `git checkout` o `git fetch` de forma directa hacia ramas remotas privadas.
- **Código local existente:** El espacio de trabajo actual ya cuenta con los archivos base de SerpApi (`SerpApiClient.kt`, `SerpApiTokenStore.kt` con cifrado Android Keystore, y `SettingsScreen.kt`).

### Procedimiento de Importación Segura (Paso a Paso)
1. **Descargar copia de seguridad actual (Backup):**
   - En la interfaz de Google AI Studio, pulsar en el menú de opciones (icono de tres puntos o ajustes del proyecto en la barra superior) y seleccionar **"Export as ZIP"** (Descargar como archivo ZIP).
   - Esto garantiza que todo el desarrollo previo realizado en AI Studio quede respaldado al 100% en tu ordenador.
2. **Alineación con la rama `feature/online-shopping-search`:**
   - Para incorporar los cambios de dicha rama sin conflictos:
     - **Opción A (Recomendada):** Subir/arrastrar a través del explorador de archivos de AI Studio los archivos específicos de la rama `feature/online-shopping-search` que quieras fusionar (o pegar su contenido/diff en la conversación).
     - **Opción B (Clon temporal):** Si proporcionas un Personal Access Token (PAT) de GitHub con alcance exclusivo de lectura (`repo:read`), podemos ejecutar un clon a una ruta temporal independiente `/tmp/feature-branch` para inspeccionar y comparar diferencias contra el código de AI Studio sin tocar tus archivos de trabajo.

---

## 2. Reparación del Formateo de Consultas y Parseo de SerpApi

### Deficiencias Identificadas en el Parseo Actual
1. **Formateo de la consulta (`queryTerm`):**
   - Google Shopping España rechaza o devuelve resultados inconsistentes cuando los títulos de productos contienen términos de ruido, códigos internos o especificaciones excesivas (ej. `"Apple iPhone 16 Pro (128 GB) - Titanio Natural 5G Dual SIM"` devuelve menos coincidencias que `"Apple iPhone 16 Pro 128GB"`).
   - Se debe refinar el constructor de términos para generar consultas limpias con palabras clave esenciales y EAN prioritario cuando esté disponible.
2. **Estructura de respuesta de Google Shopping en SerpApi:**
   - La API de SerpApi para `google_shopping` devuelve precios en múltiples nodos: `extracted_price`, `price`, `old_price`, `second_hand_condition`, y a veces dentro de `inline_shopping_results` o `sellers_results`.
   - Si `extracted_price` no está presente o viene formateado como String con coma decimal europea (ej. `"1.129,00 €"`), el parseo numérico puede fallar o descartar ofertas válidas.
3. **Filtro estricto de coincidencia (`isPlausibleProductMatch`):**
   - El umbral de coincidencia de tokens actual descarta resultados legítimos de tiendas españolas (como PcComponentes o MediaMarkt) cuando el título del vendedor en Google Shopping añade palabras como `"Envío rápido"`, `"Reacondicionado"` o `"Garantía 3 años"`.

---

## 3. Arquitectura y Flujo de Datos

```
┌────────────────────────────────────────────────────────┐
│               Jetpack Compose UI (Mínimo)              │
│  [Explorar / Mercado]   [Ajustes: SerpApi Key]        │
└───────────────────────────┬────────────────────────────┘
                            │ Disparar refresco / prueba
                            ▼
┌────────────────────────────────────────────────────────┐
│             PriceTrackerViewModel.kt                   │
│  - Limita peticiones horarias (< 45/h)                 │
│  - Orquesta lotes de productos                         │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│                SerpApiClient.kt                        │
│  1. Limpieza de consulta: Query Sanitizer              │
│  2. Request a https://serpapi.com/search.json          │
│     (engine=google_shopping, gl=es, hl=es)             │
│  3. Parseo robusto de precios (EUR, extracted_price)   │
│  4. Mapeo a tiendas españolas normalizadas             │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│               Room Database (SQLite)                   │
│  - Tablas: products, price_history, spanish_stores    │
│  - Actualización sin sobreescribir mínimos reales      │
└────────────────────────────────────────────────────────┘
```

---

## 4. Plan de Implementación Detallado

### Fase 1: Confirmación de Seguridad y Backup
- El usuario descarga el ZIP de respaldo desde la UI de AI Studio.
- Verificación de que no se realizarán modificaciones destructivas en Git ni push a repositorios remotos.

### Fase 2: Refactorización y Reparación de `SerpApiClient.kt`
1. **Sanitizador de Consultas Inteligente:**
   - Priorizar búsqueda por EAN si existe; si no, extraer `[Marca] + [Modelo Base] + [Capacidad/Variante]` eliminando términos de relleno.
2. **Extractor Numérico de Precios Multi-Formato:**
   - Soportar formatos europeos (`1.299,99 €`), anglosajones (`1,299.99 €`), y valores numéricos directos de `extracted_price`.
   - Excluir explícitamente productos de segunda mano o reacondicionados si el producto objetivo es nuevo, salvo que no haya alternativa.
3. **Mapeo Avanzado de Vendedores de España:**
   - Detectar e identificar tiendas del catálogo español (Amazon.es, PcComponentes, MediaMarkt, Carrefour, El Corte Inglés, Fnac, etc.) a partir del dominio del enlace final o el campo `source`.
4. **Resiliencia ante Respuestas Vacías o Variantes de JSON:**
   - Inspeccionar tanto `shopping_results` como `inline_shopping_results` y `sellers_results`.

### Fase 3: Pruebas y Validación
- Ejecutar suite de pruebas unitarias (`testDebugUnitTest`) con casos reales de JSON de SerpApi para Google Shopping España.
- Probar la opción "Probar con 1 producto" desde Ajustes en la app para verificar la clave sin agotar la cuota de la API.
