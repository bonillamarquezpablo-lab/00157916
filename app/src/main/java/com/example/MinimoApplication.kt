package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

private const val CRASH_REPORTS_DIRECTORY = "crash-reports"
private const val MAX_STORED_CRASH_REPORTS = 5
private const val MAX_STACK_CHARS = 180_000
private const val MAX_REPORT_CHARS = 200_000

/** Returns the newest private crash report, if one exists. */
internal fun latestCrashReportFile(context: Context): File? =
    File(context.filesDir, CRASH_REPORTS_DIRECTORY)
        .listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.startsWith("Minimo-crash-") && it.extension == "txt" }
        ?.maxByOrNull { it.lastModified() }

/** Keep diagnostic storage bounded; cleanup failures must never mask the original crash. */
private fun trimOldCrashReports(directory: File, newest: File) {
    runCatching {
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("Minimo-crash-") && it.extension == "txt" }
            ?.filterNot { it == newest }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_STORED_CRASH_REPORTS - 1)
            ?.forEach { old -> runCatching { old.delete() } }
    }
}

/**
 * Captures fatal runtime exceptions before Android's default handler terminates the process.
 *
 * Reports are kept in private app storage and are never exported automatically. The user can
 * explicitly share the latest report from Settings when diagnostics are needed.
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
                val path = writeReport(thread, error)
                Log.e(TAG, "Fatal exception captured. Report: $path", error)
            } catch (reportError: Throwable) {
                // Never let diagnostic I/O prevent Android's normal fatal-exception handling.
                Log.e(TAG, "Could not persist the fatal exception report.", reportError)
                Log.e(TAG, "Original fatal exception.", error)
            }

            try {
                previous?.uncaughtException(thread, error) ?: terminateProcess()
            } catch (handlerError: Throwable) {
                Log.e(TAG, "The previous uncaught-exception handler failed.", handlerError)
                terminateProcess()
            }
        }

        private fun writeReport(thread: Thread, error: Throwable): String {
            val stack = StringWriter().also { writer ->
                PrintWriter(writer).use { error.printStackTrace(it) }
            }.toString()
            val report = buildString {
                appendLine("Mínimo — informe de error fatal")
                appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())}")
                appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Hilo: ${thread.name}")
                appendLine()
                // Bound the stack trace to prevent unbounded diagnostic storage.
                append(stack.take(MAX_STACK_CHARS))
            }.take(MAX_REPORT_CHARS)

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val fileName = "Minimo-crash-$stamp.txt"
            val directory = File(app.filesDir, CRASH_REPORTS_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("No se pudo crear el almacenamiento privado de diagnósticos.")
            }

            val reportFile = File(directory, fileName)
            reportFile.writeText(report, Charsets.UTF_8)
            trimOldCrashReports(directory, reportFile)
            return reportFile.absolutePath
        }

        private fun terminateProcess(): Nothing {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private companion object {
        const val TAG = "MinimoCrashReport"
    }
}
