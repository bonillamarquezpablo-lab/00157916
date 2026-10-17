package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PriceTrackerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PriceTrackerViewModel by viewModels()
    private lateinit var crashStore: CrashReportStore
    private var pendingCrashReport by mutableStateOf<String?>(null)
    private var showMissingCrashReport by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        if (::crashStore.isInitialized && pendingCrashReport == null) {
            crashStore.readLast()?.let { pendingCrashReport = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashStore = CrashReportStore(applicationContext)
        pendingCrashReport = crashStore.readLast()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onOpenCrashReport = {
                            val report = crashStore.readLast()
                            if (report.isNullOrBlank()) {
                                showMissingCrashReport = true
                            } else {
                                pendingCrashReport = report
                                showMissingCrashReport = false
                            }
                        }
                    )

                    if (showMissingCrashReport) {
                        AlertDialog(
                            onDismissRequest = { showMissingCrashReport = false },
                            title = { Text("Informes de cierre") },
                            text = {
                                Text(
                                    "No hay ningún informe de excepción fatal guardado. " +
                                        "Este registro no detecta las detenciones manuales desde Ajustes de Android " +
                                        "ni todos los bloqueos ANR."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showMissingCrashReport = false }) {
                                    Text("Entendido")
                                }
                            }
                        )
                    }

                    val report = pendingCrashReport
                    if (report != null) {
                        AlertDialog(
                            onDismissRequest = { /* The user chooses whether to keep or clear the report. */ },
                            title = { Text("Mínimo se cerró inesperadamente") },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "El informe se guardó en el almacenamiento privado de la aplicación. " +
                                            "No se ha enviado a ningún servidor. Los mensajes de excepción se omiten " +
                                            "para reducir el riesgo de incluir información sensible."
                                    )
                                    Text(
                                        report,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        runCatching {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText("Informe de cierre de Mínimo", report)
                                            )
                                        }.onSuccess {
                                            Toast.makeText(this@MainActivity, "Informe copiado", Toast.LENGTH_SHORT).show()
                                            crashStore.clear()
                                            pendingCrashReport = null
                                        }.onFailure {
                                            Toast.makeText(this@MainActivity, "No se pudo copiar el informe", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Text("Copiar")
                                    }
                                    TextButton(onClick = {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "Informe de error de Mínimo")
                                            putExtra(Intent.EXTRA_TEXT, report)
                                        }
                                        runCatching {
                                            startActivity(Intent.createChooser(sendIntent, "Compartir informe de Mínimo"))
                                        }.onSuccess {
                                            crashStore.clear()
                                            pendingCrashReport = null
                                        }.onFailure {
                                            Toast.makeText(this@MainActivity, "No se pudo abrir el menú para compartir", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Text("Compartir")
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    crashStore.clear()
                                    pendingCrashReport = null
                                }) {
                                    Text("Continuar")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
