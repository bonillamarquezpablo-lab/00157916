package com.example

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
 * Stores one redacted fatal-crash diagnostic in app-private storage.
 * Exception messages are intentionally omitted because networking libraries may embed request data.
 */
class CrashReportStore(context: Context) {
    private val appContext = context.applicationContext
    private val reportFile = File(appContext.filesDir, FILE_NAME)

    fun readLast(): String? = runCatching {
        if (!reportFile.isFile || reportFile.length() !in 1..MAX_REPORT_BYTES.toLong()) return null
        reportFile.readText(Charsets.UTF_8)
    }.onFailure {
        Log.e(TAG, "Could not read private crash report (${it.javaClass.simpleName}).")
    }.getOrNull()

    fun write(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val report = buildString {
            appendLine("Mínimo — informe local de cierre inesperado (redactado)")
            appendLine("Fecha UTC: $timestamp")
            appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Hilo: ${thread.name}")
            appendLine("Se omiten mensajes de excepción para reducir el riesgo de incluir datos sensibles.")
            var current: Throwable? = throwable
            var causeDepth = 0
            while (current != null && causeDepth < MAX_CAUSE_DEPTH) {
                appendLine()
                appendLine("Tipo de excepción ${causeDepth + 1}: ${current.javaClass.name}")
                current.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
                    append("  at ${frame.className}.${frame.methodName}(")
                    append(frame.fileName ?: "Unknown Source")
                    if (frame.lineNumber >= 0) append(":${frame.lineNumber}")
                    appendLine(")")
                }
                current = current.cause
                causeDepth++
            }
        }.take(MAX_REPORT_CHARS)

        reportFile.parentFile?.mkdirs()
        val temporary = File(appContext.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(report.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(reportFile)) {
            reportFile.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
    }

    fun clear(): Boolean = runCatching {
        !reportFile.exists() || reportFile.delete()
    }.onFailure {
        Log.e(TAG, "Could not clear private crash report (${it.javaClass.simpleName}).")
    }.getOrDefault(false)

    private companion object {
        const val TAG = "MinimoCrashReport"
        const val FILE_NAME = "minimo_last_crash_report.txt"
        const val MAX_CAUSE_DEPTH = 8
        const val MAX_FRAMES_PER_CAUSE = 24
        const val MAX_REPORT_CHARS = 48 * 1024
        const val MAX_REPORT_BYTES = 48 * 1024
    }
}
