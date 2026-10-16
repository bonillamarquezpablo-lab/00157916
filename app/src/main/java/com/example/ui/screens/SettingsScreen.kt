package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.example.security.openExternalHttpsUrl
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.latestCrashReportFile
import com.example.data.remote.SerpApiTokenStore

private const val PROFILE_PREFS = "minimo_user_profile"
private const val DEFAULT_PROFILE_NAME = "Usuario principal"

@Composable
fun SettingsScreen(
    storeCount: Int,
    isRefreshingPrices: Boolean,
    isTestingSingleProduct: Boolean,
    priceRefreshStatus: String?,
    onRefreshPrices: (testOneProduct: Boolean) -> Unit,
    isCheckingSerpApiAccount: Boolean,
    serpApiAccountStatus: String?,
    onCheckSerpApiAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
    }
    var displayName by remember {
        mutableStateOf(preferences.getString("display_name", DEFAULT_PROFILE_NAME) ?: DEFAULT_PROFILE_NAME)
    }
    var email by remember {
        mutableStateOf(preferences.getString("email", "") ?: "")
    }
    var saved by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf(false) }
    val serpApiTokenStore = remember(context) { SerpApiTokenStore(context) }
    var serpApiKeyInput by remember { mutableStateOf("") }
    var serpApiKeyConfigured by remember { mutableStateOf(serpApiTokenStore.isConfigured()) }
    var serpApiKeyMessage by remember { mutableStateOf<String?>(null) }

    // A failed decrypt can clear only a confirmed-invalid token. Keep the Settings badge in sync
    // with storage once the ViewModel surfaces that read error.
    LaunchedEffect(priceRefreshStatus, serpApiAccountStatus) {
        if (
            priceRefreshStatus?.contains("Vuelve a introducir tu clave", ignoreCase = true) == true ||
            serpApiAccountStatus?.contains("Vuelve a introducir tu clave", ignoreCase = true) == true
        ) {
            serpApiKeyConfigured = serpApiTokenStore.isConfigured()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsSuggest,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(13.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tu espacio en Mínimo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Perfil, datos y conexiones en un solo lugar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        SectionTitle("Cuenta principal", "Perfil guardado en este dispositivo")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(9.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(displayName.ifBlank { DEFAULT_PROFILE_NAME }, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Perfil local · sesión no iniciada",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Local", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nombre visible") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; saved = false; emailError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Correo (opcional)") },
                    placeholder = { Text("tucorreo@ejemplo.com") },
                    isError = emailError,
                    supportingText = {
                        if (emailError) Text("Introduce un correo válido o deja el campo vacío.")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        val normalizedEmail = email.trim()
                        if (normalizedEmail.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
                            emailError = true
                            saved = false
                            return@Button
                        }
                        preferences.edit()
                            .putString("display_name", displayName.trim().ifBlank { DEFAULT_PROFILE_NAME })
                            .putString("email", normalizedEmail)
                            .apply()
                        displayName = displayName.trim().ifBlank { DEFAULT_PROFILE_NAME }
                        saved = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Guardar perfil")
                }
                Text(
                    text = if (saved) {
                        "Perfil guardado correctamente en este móvil."
                    } else {
                        "Esto aún no es una cuenta online: el nombre y el correo no se sincronizan entre dispositivos ni autentican al usuario."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionTitle("Fuentes de precios", "Conexiones auténticas, sin datos inventados")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(11.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("SerpApi · Google Shopping España", fontWeight = FontWeight.Bold)
                        Text(
                            "Ofertas de comercios en Google Shopping",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (serpApiKeyConfigured) "Clave guardada" else "Sin configurar",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Text(
                    "Consulta Google Shopping España. Mínimo comprueba el saldo real de tu cuenta y el límite horario antes de los lotes; las búsquedas recientes se reutilizan desde caché local para evitar llamadas repetidas. El plan gratuito anuncia 250 búsquedas al mes y 50 por hora; otros planes pueden tener límites distintos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tu clave se cifra en este dispositivo con Android Keystore. No publiques esta versión con la clave dentro ni redistribuyas los resultados. Para una versión multiusuario necesitaremos un backend y revisar las condiciones del proveedor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = serpApiKeyInput,
                    onValueChange = { serpApiKeyInput = it; serpApiKeyMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Clave API (token)") },
                    placeholder = { Text(if (serpApiKeyConfigured) "Clave guardada; introduce otra para cambiarla" else "Pega aquí tu clave API de SerpApi") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        runCatching { serpApiTokenStore.save(serpApiKeyInput) }
                            .onSuccess {
                                serpApiKeyConfigured = true
                                serpApiKeyInput = ""
                                serpApiKeyMessage = "Clave guardada cifrada en este dispositivo. Aún no se ha validado con el proveedor."
                            }
                            .onFailure { error ->
                                serpApiKeyMessage = error.message ?: "No se pudo guardar la clave."
                            }
                    },
                    enabled = serpApiKeyInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, null, Modifier.size(17.dp))
                    Text("  Guardar clave cifrada")
                }
                if (serpApiKeyConfigured) {
                    OutlinedButton(
                        onClick = {
                            serpApiTokenStore.clear()
                            serpApiKeyConfigured = false
                            serpApiKeyInput = ""
                            serpApiKeyMessage = "Clave eliminada de este dispositivo."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Eliminar clave guardada")
                    }
                }
                serpApiKeyMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(
                    onClick = onCheckSerpApiAccount,
                    enabled = serpApiKeyConfigured && !isCheckingSerpApiAccount && !isRefreshingPrices,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingSerpApiAccount) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(17.dp))
                    }
                    Text("  Comprobar clave y cuota")
                }
                serpApiAccountStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { onRefreshPrices(true) },
                    enabled = serpApiKeyConfigured && !isRefreshingPrices,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingSingleProduct) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(17.dp))
                    }
                    Text(if (isTestingSingleProduct) "  Probando 1 producto…" else "  Probar con 1 producto (1 búsqueda)")
                }
                Button(
                    onClick = { onRefreshPrices(false) },
                    enabled = serpApiKeyConfigured && !isRefreshingPrices,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRefreshingPrices && !isTestingSingleProduct) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(17.dp))
                    }
                    Text(if (isRefreshingPrices && !isTestingSingleProduct) "  Actualizando catálogo…" else "  Actualizar hasta 40 productos")
                }
                priceRefreshStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRefreshingPrices) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "El plan gratuito anuncia 250 búsquedas al mes. Una actualización completa consume hasta 40 búsquedas por lote. Los resultados son una instantánea de Google Shopping, no una garantía del precio final: comprueba siempre el precio y la disponibilidad en la tienda antes de comprar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { openWeb(context, "https://serpapi.com/users/sign_up") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, null, Modifier.size(17.dp))
                    Text("  Crear cuenta de prueba gratuita")
                }
                OutlinedButton(
                    onClick = { openWeb(context, "https://serpapi.com/pricing") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, null, Modifier.size(17.dp))
                    Text("  Revisar precios antes de contratar")
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(11.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Keepa API · Amazon España", fontWeight = FontWeight.Bold)
                        Text(
                            "Historial, precios y ofertas de Amazon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "Sin conectar",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Text(
                    "La documentación de Keepa contempla la integración de precios e historiales directamente en aplicaciones. Requiere una suscripción mensual con consumo de tokens. Aún no se ha contratado ni conectado; antes de usarla, revisaremos sus condiciones para el uso previsto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { openWeb(context, "https://keepa.com/api-docs/") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, null, Modifier.size(17.dp))
                    Text("  Consultar API de Keepa")
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(11.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Awin · feeds de productos", fontWeight = FontWeight.Bold)
                        Text(
                            "Candidato para catálogos autorizados",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Pendiente", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    text = "Awin publica feeds con datos de producto, precio y disponibilidad cuando el anunciante concede acceso. Para usar una tienda en Mínimo hay que obtener permiso para su feed y comprobar sus condiciones específicas para aplicaciones Android y seguimiento de precios.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { openWeb(context, "https://help.awin.com/apidocs/retail-publisher-productapidocumentation-1") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, null, Modifier.size(17.dp))
                    Text("  Consultar documentación de feeds")
                }
                OutlinedButton(
                    onClick = { openWeb(context, "https://www.awin.com/es/publisher-terms") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, null, Modifier.size(17.dp))
                    Text("  Ver condiciones para publishers")
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text("Seguridad de las conexiones", modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                }
                Text(
                    "La clave personal de SerpApi se cifra en este dispositivo con Android Keystore. Para una versión pública multiusuario necesitaremos un servidor seguro; nunca publiques la clave dentro del APK ni en GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (serpApiKeyConfigured) {
                            "  Clave de SerpApi cifrada en este dispositivo"
                        } else {
                            "  No hay ninguna clave de proveedor guardada"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        SectionTitle("Diagnóstico privado", "Informes de errores")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Los informes de cierre se guardan en el almacenamiento privado de Mínimo. No se envían ni se comparten automáticamente.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val latestReport = remember(context) { latestCrashReportFile(context) }
                OutlinedButton(
                    onClick = { shareCrashReport(context, latestReport) },
                    enabled = latestReport != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (latestReport == null) "No hay informes de errores" else "Compartir último informe",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        SectionTitle("Aplicación", "Estado real de Mínimo")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoLine("Versión instalada", BuildConfig.VERSION_NAME)
                InfoLine("Tiendas con precios guardados", storeCount.toString())
                InfoLine("Fuente de búsqueda", if (serpApiKeyConfigured) "SerpApi · clave guardada, pendiente de prueba" else "No configurada")
                InfoLine("Perfil online", "No configurado")
                Text(
                    "Mínimo no afirma que una tienda esté conectada hasta que la fuente se haya autorizado y probado de verdad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun shareCrashReport(context: Context, report: java.io.File?) {
    if (report == null || !report.isFile) return

    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            report
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Informe de error de Mínimo")
            putExtra(
                Intent.EXTRA_TEXT,
                "Este informe contiene detalles técnicos del cierre. Revísalo antes de compartirlo."
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, report.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Compartir informe de error"))
    }
}

private fun openWeb(context: Context, url: String) {
    openExternalHttpsUrl(context, url)
}
