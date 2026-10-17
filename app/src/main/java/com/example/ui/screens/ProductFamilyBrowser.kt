package com.example.ui.screens

import com.example.data.remote.OnlineProductGroup
import java.text.Normalizer
import java.util.Locale

/**
 * A console model tier shown between a broad family search and exact storage/edition listings.
 * Each nested OnlineProductGroup still represents one exact product identity with seller offers.
 */
internal data class ConsoleModelBucket(
    val key: String,
    val label: String,
    val groups: List<OnlineProductGroup>
) {
    val cheapestPrice: Double
        get() = groups.minOfOrNull { it.minPrice } ?: Double.POSITIVE_INFINITY

    val storeCount: Int
        get() = groups.flatMap { it.offers }.map { it.storeName.trim().lowercase(Locale.ROOT) }
            .distinct().size
}

private enum class ConsoleFamily { PLAYSTATION_5, XBOX_SERIES, NINTENDO_SWITCH }

private fun normalizeFamilyText(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\bplaystation\\s*5\\b"), "ps5")
        .replace(Regex("\\bxbox\\s*series\\s*([xs])\\b"), "xbox series $1")

/** True only for an overview query; searches for a specific edition/capacity remain exact. */
internal fun isConsoleFamilyOverviewQuery(query: String): Boolean {
    val normalized = normalizeFamilyText(query)
    if (normalized.isBlank() || isAccessoryIntent(normalized)) return false

    val isPs5 = Regex("\\bps5\\b").containsMatchIn(normalized)
    val isXbox = Regex("\\bxbox\\b").containsMatchIn(normalized)
    val isSwitch = Regex("\\bnintendo switch\\b|\\bswitch\\b").containsMatchIn(normalized)
    if (!isPs5 && !isXbox && !isSwitch) return false

    val qualifiers = Regex(
        "\\b(pro|slim|fat|original|standard|estandar|digital|disc|disk|blu ray|blu ray|oled|lite|series x|series s|1tb|2tb|825gb|825 gb|500gb|500 gb|512gb|512 gb|digital edition|disc edition)\\b"
    )
    if (qualifiers.containsMatchIn(normalized)) return false

    // Don't turn searches for console accessories, games, or specific generations into a model browser.
    val blocked = Regex(
        "\\b(mando|controller|gamepad|auricular|headset|cable|cargador|funda|carcasa|juego|game|soporte|ventilador|repuesto|skin|juego|bundle|pack)\\b"
    )
    if (blocked.containsMatchIn(normalized)) return false

    val allowed = setOf(
        "ps5", "sony", "playstation", "5", "xbox", "series", "nintendo", "switch",
        "consola", "consolas", "console", "precio", "precios", "version", "versiones", "ofertas", "la", "de", "el"
    )
    return normalized.split(' ').all { it in allowed }
}

private fun isAccessoryIntent(normalized: String): Boolean =
    Regex("\\b(mando|controller|gamepad|auricular|headset|cable|cargador|funda|carcasa|juego|game|soporte|ventilador|repuesto|skin|bundle|pack)\\b")
        .containsMatchIn(normalized)

private fun familyForQuery(query: String): ConsoleFamily? {
    val normalized = normalizeFamilyText(query)
    return when {
        Regex("\\bps5\\b").containsMatchIn(normalized) -> ConsoleFamily.PLAYSTATION_5
        Regex("\\bxbox\\b").containsMatchIn(normalized) -> ConsoleFamily.XBOX_SERIES
        Regex("\\bnintendo switch\\b|\\bswitch\\b").containsMatchIn(normalized) -> ConsoleFamily.NINTENDO_SWITCH
        else -> null
    }
}

private fun familyForTitle(title: String): ConsoleFamily? {
    val normalized = normalizeFamilyText(title)
    return when {
        Regex("\\bps5\\b").containsMatchIn(normalized) -> ConsoleFamily.PLAYSTATION_5
        Regex("\\bxbox\\s*(series\\s*)?[xs]\\b|\\bxbox series\\b").containsMatchIn(normalized) ->
            ConsoleFamily.XBOX_SERIES
        Regex("\\bnintendo switch\\b|\\bswitch\\s*(2|oled|lite)\\b").containsMatchIn(normalized) ->
            ConsoleFamily.NINTENDO_SWITCH
        else -> null
    }
}

private fun modelBucket(family: ConsoleFamily, title: String): Pair<String, String> {
    val normalized = normalizeFamilyText(title)
    return when (family) {
        ConsoleFamily.PLAYSTATION_5 -> when {
            Regex("\\bpro\\b").containsMatchIn(normalized) -> "ps5-pro" to "PlayStation 5 Pro"
            Regex("\\bslim\\b").containsMatchIn(normalized) -> "ps5-slim" to "PlayStation 5 Slim"
            else -> "ps5-original" to "PlayStation 5 estándar (original / Fat)"
        }
        ConsoleFamily.XBOX_SERIES -> when {
            Regex("\\bseries x\\b|\\bxbox x\\b").containsMatchIn(normalized) ->
                "xbox-series-x" to "Xbox Series X"
            Regex("\\bseries s\\b|\\bxbox s\\b").containsMatchIn(normalized) ->
                "xbox-series-s" to "Xbox Series S"
            else -> "xbox-series-other" to "Xbox Series (modelo por confirmar)"
        }
        ConsoleFamily.NINTENDO_SWITCH -> when {
            Regex("\\bswitch 2\\b|\\bnintendo switch 2\\b").containsMatchIn(normalized) ->
                "switch-2" to "Nintendo Switch 2"
            Regex("\\boled\\b").containsMatchIn(normalized) ->
                "switch-oled" to "Nintendo Switch OLED"
            Regex("\\blite\\b").containsMatchIn(normalized) ->
                "switch-lite" to "Nintendo Switch Lite"
            else -> "switch-original" to "Nintendo Switch estándar"
        }
    }
}

/**
 * Build the first model-selection level. Exact capacity/edition groups are deliberately retained
 * below each model: 825 GB, 1 TB, Digital and Disc must never be collapsed into a single price.
 */
internal fun buildConsoleModelBuckets(
    query: String,
    groups: List<OnlineProductGroup>
): List<ConsoleModelBucket> {
    if (!isConsoleFamilyOverviewQuery(query)) return emptyList()
    val family = familyForQuery(query) ?: return emptyList()
    val candidates = groups.filter { group ->
        familyForTitle(group.title) == family &&
            group.offers.isNotEmpty() &&
            group.offers.none { it.isAccessoryOffer } &&
            group.offers.none { it.isSecondHand }
    }
    return candidates.groupBy { modelBucket(family, it.title).first }
        .mapNotNull { (key, rows) ->
            val label = modelBucket(family, rows.first().title).second
            ConsoleModelBucket(key, label, rows.sortedBy { it.minPrice })
        }
        .sortedWith(
            compareBy<ConsoleModelBucket> {
                when (it.key) {
                    "ps5-original" -> 0
                    "ps5-slim" -> 1
                    "ps5-pro" -> 2
                    "switch-original" -> 0
                    "switch-lite" -> 1
                    "switch-oled" -> 2
                    "switch-2" -> 3
                    "xbox-series-s" -> 0
                    "xbox-series-x" -> 1
                    else -> 9
                }
            }.thenBy { it.label }
        )
}

internal fun normalizeConsoleTitleForTest(value: String): String = normalizeFamilyText(value)
