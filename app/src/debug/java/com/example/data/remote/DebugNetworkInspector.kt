package com.example.data.remote

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import okhttp3.OkHttpClient

/** Attach the HTTP inspector only to debug-network clients. */
internal fun addDebugNetworkInspector(
    context: Context,
    builder: OkHttpClient.Builder
) {
    builder.addInterceptor(ChuckerInterceptor.Builder(context.applicationContext).build())
}
