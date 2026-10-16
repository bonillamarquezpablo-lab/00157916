package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PriceTrackerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PriceTrackerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val crashStore = remember { CrashReportStore(applicationContext) }
            var pendingReport by remember { mutableStateOf(crashStore.readLast()) }

            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val report = pendingReport
                    if (report != null) {
                        AlertDialog(
                            onDismissRequest = { /* Keep the report visible until the user chooses. */ },
                            title = { Text("Mínimo se cerró inesperadamente") },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "He guardado el informe del último cierre en este teléfono. " +
                                            "No se ha enviado a ningún servidor. Compártelo o cópialo para que podamos " +
                                            "identificar la causa; después puedes continuar."
                                    )
                                    TextButton(
                                        onClick = {
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText("Informe de cierre de Mínimo", report)
                                            )
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Informe copiado. Pégalo en el chat.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    ) {
                                        Text("Copiar informe")
                                    }
                                    Text(
                                        report,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { shareCrashReport(report) }) {
                                    Text("Compartir informe")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        crashStore.clear()
                                        pendingReport = null
                                    }
                                ) {
                                    Text("Continuar")
                                }
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    private fun shareCrashReport(report: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Informe de cierre de Mínimo")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(sendIntent, "Compartir informe de cierre"))
    }
}
