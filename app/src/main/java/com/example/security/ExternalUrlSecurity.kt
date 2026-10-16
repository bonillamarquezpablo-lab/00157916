package com.example.security

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.util.Locale

/**
 * Validates URLs before they are passed to Android's external intent resolver.
 *
 * Search results may originate from external providers. Only absolute HTTPS web links are
 * allowed; app-specific schemes, cleartext URLs, malformed hosts, credentials and unusual ports
 * are rejected so a listing cannot make this app launch an arbitrary intent.
 */
internal fun safeExternalHttpsUrl(value: String?): String? {
    val candidate = value?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTERNAL_URL_LENGTH }
        ?: return null

    if (candidate.any { it <= '\u001f' || it == '\u007f' }) return null

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.isOpaque || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null

    val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
    if (!host.contains('.')) return null

    // Result links are retailer web pages, not raw IP endpoints. Blocking IP literals also
    // prevents untrusted offers from opening router/admin panels or local network services.
    if (host.contains(':') || IPV4_LITERAL.matches(host)) return null
    if (host.endsWith(".test") || host.endsWith(".example") || host.endsWith(".invalid")) return null

    return runCatching { uri.toASCIIString() }.getOrNull()
}

/** Opens only a validated HTTPS web URL and never lets a missing browser crash the app. */
internal fun openExternalHttpsUrl(context: Context, value: String?): Boolean {
    val safeUrl = safeExternalHttpsUrl(value) ?: return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private val IPV4_LITERAL = Regex("""\\d{1,3}(?:\\.\\d{1,3}){3}""")
private const val MAX_EXTERNAL_URL_LENGTH = 4096
