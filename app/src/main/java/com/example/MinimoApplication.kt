package com.example

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Captures fatal runtime exceptions before Android's default handler terminates the process.
 *
 * On Android 10+, the report is written to the public Downloads collection without requesting
 * broad storage permissions. If that fails, a private app-specific fallback is used.
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
                append(stack)
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val fileName = "Minimo-crash-$stamp.txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var insertedUri: android.net.Uri? = null
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val outputUri = app.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw IOException("Android no creó el archivo en Descargas.")
                    insertedUri = outputUri
                    val output = app.contentResolver.openOutputStream(outputUri, "w")
                        ?: throw IOException("Android no abrió el archivo de diagnóstico.")
                    output.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    return "Descargas/$fileName"
                } catch (failure: Exception) {
                    insertedUri?.let { orphan ->
                        runCatching { app.contentResolver.delete(orphan, null, null) }
                    }
                    Log.e(TAG, "Writing to public Downloads failed; using app-specific storage.", failure)
                }
            }

            val directory = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: app.filesDir
            val fallback = File(directory, fileName)
            fallback.writeText(report, Charsets.UTF_8)
            return fallback.absolutePath
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
