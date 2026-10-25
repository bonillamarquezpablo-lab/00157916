package com.example.data.remote

import android.content.Context
import okhttp3.OkHttpClient

/** No-op in release builds; debug-only dependencies must not ship in production APKs. */
internal fun addDebugNetworkInspector(
    context: Context,
    builder: OkHttpClient.Builder
) = Unit
