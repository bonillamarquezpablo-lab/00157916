package com.example

import android.app.Application
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Captures fatal exceptions into app-private storage. Reports omit exception messages and
 * are never uploaded or written to public Downloads.
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
        // Android's exit history also covers cases that bypass the uncaught-exception handler,
        // including ANRs and user-requested force-stops. Reports remain local and redacted.
        runCatching { ProcessExitDiagnostics(this).captureLatestPreviousExit() }
            .onFailure {
                Log.e(TAG, "Could not inspect the previous process exit (${it.javaClass.name}).")
            }
    }

    private class MinimoUncaughtExceptionHandler(
        private val app: MinimoApplication,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                CrashReportStore(app).write(thread, error)
                // Do not log the Throwable: its message can contain request or credential data.
                Log.e(TAG, "Fatal exception saved privately (${error.javaClass.name}).")
            } catch (reportError: Throwable) {
                Log.e(TAG, "Could not persist private crash report (${reportError.javaClass.name}).")
            }

            try {
                previous?.uncaughtException(thread, error) ?: terminateProcess()
            } catch (handlerError: Throwable) {
                Log.e(TAG, "The default exception handler failed (${handlerError.javaClass.name}).")
                terminateProcess()
            }
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
