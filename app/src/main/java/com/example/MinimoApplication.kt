package com.example

import android.app.Application
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Local-only crash capture. Reports stay in this app's private files directory and are never
 * uploaded automatically. The next launch can show/share the report so crashes can be diagnosed.
 */
class MinimoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashReportStore(this).write(thread, throwable)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to save local crash report", error)
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MinimoCrashReport"
    }
}
