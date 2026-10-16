package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Keeps one diagnostic report only in app-private storage; no network upload is performed. */
class CrashReportStore(context: Context) {
    private val reportFile = File(context.applicationContext.filesDir, FILE_NAME)
    private val appContext = context.applicationContext

    fun readLast(): String? = runCatching {
        if (!reportFile.isFile || reportFile.length() <= 0L) return null
        reportFile.readText(Charsets.UTF_8).take(MAX_REPORT_CHARS)
    }.onFailure {
        Log.e(TAG, "Could not read local crash report", it)
    }.getOrNull()

    fun write(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val report = buildString {
            appendLine("Mínimo: informe local del cierre inesperado")
            appendLine("Fecha UTC: $timestamp")
            appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Paquete: ${appContext.packageName}")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Hilo: ${thread.name}")
            appendLine()
            appendLine("Excepción:")
            append(Log.getStackTraceString(throwable))
        }.take(MAX_REPORT_CHARS)

        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)
    }

    fun clear(): Boolean = runCatching { !reportFile.exists() || reportFile.delete() }
        .onFailure { Log.e(TAG, "Could not clear local crash report", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "MinimoCrashReport"
        const val FILE_NAME = "last_crash_report.txt"
        const val MAX_REPORT_CHARS = 100_000
    }
}
