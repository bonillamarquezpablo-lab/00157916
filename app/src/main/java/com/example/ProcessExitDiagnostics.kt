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
 * ApplicationExitInfo is useful for ANRs and user-requested force-stops, which do not reliably
 * invoke Thread.UncaughtExceptionHandler. The system description and trace are intentionally not
 * copied because they may contain sensitive information.
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
        val latest = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_HISTORY)
            .firstOrNull { it.timestamp > lastProcessed }
            ?: return

        val reasonLabel = describeProcessExitReason(latest.reason)
        if (reasonLabel != null) {
            writeReport(latest.timestamp, reasonLabel)
        }
        // Mark ordinary exits as processed too, so they are not reconsidered on every launch.
        preferences.edit().putLong(LAST_PROCESSED_TIMESTAMP, latest.timestamp).apply()
    }

    fun readLast(): String? = runCatching {
        if (!reportFile.isFile) return null
        val size = reportFile.length()
        if (size !in 1L..MAX_REPORT_BYTES.toLong()) return null
        reportFile.readText(Charsets.UTF_8)
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

/** Maps Android exit codes to safe, human-readable text; normal lifecycle exits are ignored. */
internal fun describeProcessExitReason(reason: Int): String? = when (reason) {
    ApplicationExitInfo.REASON_ANR ->
        "ANR: Android detectó que la aplicación no respondía."
    ApplicationExitInfo.REASON_CRASH ->
        "Excepción fatal: Android registró que el proceso terminó por un fallo de la aplicación."
    ApplicationExitInfo.REASON_CRASH_NATIVE ->
        "Fallo nativo: Android registró una terminación por código nativo."
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
        "Falló la inicialización de la aplicación."
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
        "Android terminó el proceso por uso excesivo de recursos."
    ApplicationExitInfo.REASON_LOW_MEMORY ->
        "Android terminó el proceso por falta de memoria disponible."
    ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
        "Se detuvo un proceso o servicio del que dependía la aplicación."
    ApplicationExitInfo.REASON_USER_REQUESTED ->
        "Cierre solicitado por el usuario o el sistema. Puede incluir «Forzar detención» desde Ajustes; no implica por sí solo un fallo del código."
    else -> null
}
