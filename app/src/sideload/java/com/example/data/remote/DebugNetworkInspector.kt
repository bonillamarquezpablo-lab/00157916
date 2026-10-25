package com.example.data.remote

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import okhttp3.OkHttpClient

/**
 * HTTP inspection for the private sideload diagnostic APK only.
 * Archive requests are retained for one hour and common credential headers are redacted.
 */
internal fun addDebugNetworkInspector(
    context: Context,
    builder: OkHttpClient.Builder
) {
    val appContext = context.applicationContext
    val collector = ChuckerCollector(
        context = appContext,
        showNotification = true,
        retentionPeriod = RetentionManager.Period.ONE_HOUR
    )
    builder.addInterceptor(
        ChuckerInterceptor.Builder(appContext)
            .collector(collector)
            .maxContentLength(250_000L)
            .redactHeaders(
                "Authorization",
                "Proxy-Authorization",
                "Cookie",
                "Set-Cookie",
                "X-API-Key",
                "Api-Key"
            )
            .build()
    )
}
