package com.example

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Captures Android's previous-process exit reason when available.
 *
 * Only genuine app crashes (Java/native) and ANRs are reportable. User-initiated exits, task
 * removal, low-memory kills, resource limits, dependency exits and package updates are ignored.
 * System descriptions and traces are intentionally not copied because they may contain sensitive
 * information. Reports are shown only when the user opens Settings > Error reports.
 */
class ProcessExitDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val reportFile = File(appContext.filesDir, FILE_NAME)

    /**
     * Saves only the newest unprocessed, diagnostically useful exit. Android 11 (API 30) is
     * required. This method is called at app startup and never performs network access.
     */
    fun captureLatestPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val lastProcessed = preferences.getLong(LAST_PROCESSED_TIMESTAMP, 0L)
        val newExits = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_HISTORY)
            .filter { it.timestamp > lastProcessed }
        if (newExits.isEmpty()) return

        // Look across every new exit, not just the newest one: a routine OS kill after a real
        // crash must not hide that crash, and a routine exit by itself must never create a report.
        val reportableExit = newExits.mapNotNull { exit ->
            describeProcessExitReason(exit.reason)?.let { label -> exit to label }
        }.maxByOrNull { (exit, _) -> exit.timestamp }

        reportableExit?.let { (exit, label) ->
            writeReport(exit.timestamp, label)
        }

        // Mark every newly observed exit as processed, including non-reportable system exits.
        preferences.edit()
            .putLong(LAST_PROCESSED_TIMESTAMP, newExits.maxOf { it.timestamp })
            .apply()
    }

    fun readLast(): String? = runCatching {
        if (!reportFile.isFile) return null
        val size = reportFile.length()
        if (size !in 1L..MAX_REPORT_BYTES.toLong()) return null
        val report = reportFile.readText(Charsets.UTF_8)
        if (!isReportableProcessExitReport(report)) {
            // Purge legacy reports created by older versions for user/system exits.
            reportFile.delete()
            return null
        }
        report
    }.onFailure {
        Log.e(TAG, "Could not read private process-exit report (${it.javaClass.simpleName}).")
    }.getOrNull()

    fun clear(): Boolean = runCatching {
        !reportFile.exists() || reportFile.delete()
    }.onFailure {
        Log.e(TAG, "Could not clear private process-exit report (${it.javaClass.simpleName}).")
    }.getOrDefault(false)

    private fun writeReport(exitTimestamp: Long, reasonLabel: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(exitTimestamp))
        val report = buildString {
            appendLine("Mínimo — diagnóstico del cierre anterior (registro de Android)")
            appendLine("Fecha de salida UTC: $timestamp")
            appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Motivo registrado: $reasonLabel")
            appendLine()
            appendLine("Este diagnóstico se ha guardado solo en el almacenamiento privado de Mínimo.")
            appendLine("Android no siempre aporta una traza que permita identificar la causa exacta.")
        }
        val bytes = report.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REPORT_BYTES) { "El diagnóstico supera el límite seguro de tamaño." }
        reportFile.parentFile?.mkdirs()
        val temporary = File(appContext.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(reportFile)) {
            reportFile.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private companion object {
        const val TAG = "MinimoExitDiagnostics"
        const val PREFERENCES = "minimo_process_exit_diagnostics"
        const val LAST_PROCESSED_TIMESTAMP = "last_processed_exit_timestamp"
        const val FILE_NAME = "minimo_last_process_exit_report.txt"
        const val MAX_HISTORY = 8
        const val MAX_REPORT_BYTES = 16 * 1024
    }
}

/** Maps only genuine app crashes and ANRs to reportable, human-readable labels. */
internal fun describeProcessExitReason(reason: Int): String? = when (reason) {
    ApplicationExitInfo.REASON_ANR ->
        "ANR: Android detectó que la aplicación no respondía."
    ApplicationExitInfo.REASON_CRASH ->
        "Excepción fatal: Android registró que el proceso terminó por un fallo de la aplicación."
    ApplicationExitInfo.REASON_CRASH_NATIVE ->
        "Fallo nativo: Android registró una terminación por código nativo."
    else -> null
}

/** Rejects legacy diagnostic files that were written for routine OS/user exits in older builds. */
internal fun isReportableProcessExitReport(report: String): Boolean {
    if (!report.startsWith("Mínimo — diagnóstico del cierre anterior (registro de Android)")) return false
    val reason = report.lineSequence()
        .firstOrNull { it.startsWith("Motivo registrado: ") }
        ?.removePrefix("Motivo registrado: ")
        ?: return false
    return reason.startsWith("ANR: ") ||
        reason.startsWith("Excepción fatal: ") ||
        reason.startsWith("Fallo nativo: ")
}
