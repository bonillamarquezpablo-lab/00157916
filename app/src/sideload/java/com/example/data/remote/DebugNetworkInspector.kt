package com.example.data.remote

import android.content.Context
import okhttp3.OkHttpClient

/** No-op in sideload builds; preserve compatibility with the installed release-like APK. */
internal fun addDebugNetworkInspector(
    context: Context,
    builder: OkHttpClient.Builder
) = Unit
