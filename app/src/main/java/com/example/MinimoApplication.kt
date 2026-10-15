package com.example

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Captures fatal runtime exceptions into app-private storage.
 *
 * Reports deliberately omit exception messages because third-party networking errors can include
 * request details. Reports are bounded in size and count and are never written to public Downloads.
 */
class MinimoApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is MinimoUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(
                MinimoUncaughtExceptionHandler(this, previous)
            )
        }
    }

    private class MinimoUncaughtExceptionHandler(
        private val app: MinimoApplication,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                writeReport(thread, error)
                // Do not attach the Throwable to Log.e: its message may contain sensitive request data.
                Log.e(TAG, "Fatal exception captured; a redacted report was saved privately (${error.javaClass.name}).")
            } catch (reportError: Throwable) {
                Log.e(TAG, "Could not persist private crash report (${reportError.javaClass.name}).")
                Log.e(TAG, "Fatal exception type: ${error.javaClass.name}.")
            }

            try {
                previous?.uncaughtException(thread, error) ?: terminateProcess()
            } catch (handlerError: Throwable) {
                Log.e(TAG, "Default exception handler failed (${handlerError.javaClass.name}); terminating process.")
                terminateProcess()
            }
        }

        private fun writeReport(thread: Thread, error: Throwable): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val report = buildString {
                appendLine("Mínimo — informe de error fatal (redactado)")
                appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())}")
                appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Hilo: ${thread.name}")
                appendLine()
                appendLine("Se omiten mensajes de excepción y valores de datos para reducir el riesgo de filtrar información sensible.")
                var current: Throwable? = error
                var depth = 0
                while (current != null && depth < MAX_CAUSE_DEPTH) {
                    appendLine()
                    appendLine("Tipo de excepción ${depth + 1}: ${current.javaClass.name}")
                    current.stackTrace.take(MAX_STACK_FRAMES_PER_CAUSE).forEach { frame ->
                        append("  at ${frame.className}.${frame.methodName}(")
                        append(frame.fileName ?: "Unknown Source")
                        if (frame.lineNumber >= 0) append(":${frame.lineNumber}")
                        appendLine(")")
                    }
                    current = current.cause
                    depth++
                }
            }

            val directory = File(app.filesDir, CRASH_REPORT_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create private crash-report directory.")
            }

            val fileName = "Minimo-crash-$stamp.txt"
            val file = File(directory, fileName)
            file.writeText(report.take(MAX_REPORT_CHARS), Charsets.UTF_8)

            // Retain at most three recent diagnostics to limit stored device information.
            directory.listFiles()
                ?.filter { it.isFile && it.name.startsWith("Minimo-crash-") && it != file }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_REPORT_FILES - 1)
                ?.forEach { old -> runCatching { old.delete() } }

            return "private files/$CRASH_REPORT_DIRECTORY/$fileName"
        }

        private fun terminateProcess(): Nothing {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private companion object {
        const val TAG = "MinimoCrashReport"
        const val CRASH_REPORT_DIRECTORY = "crash-reports"
        const val MAX_CAUSE_DEPTH = 8
        const val MAX_STACK_FRAMES_PER_CAUSE = 24
        const val MAX_REPORT_CHARS = 48 * 1024
        const val MAX_REPORT_FILES = 3
    }
}
