package com.example.ui.screens

import com.example.data.model.Product
import com.example.data.remote.onlineProductIdentityKey
import java.text.Normalizer
import java.util.Locale

internal data class SavedStorageGroup(
    val label: String,
    val products: List<Product>
)

internal data class SavedVariantGroup(
    val key: String,
    val label: String,
    val storages: List<SavedStorageGroup>
) {
    val products: List<Product>
        get() = storages.flatMap { it.products }
}

internal data class SavedProductFamily(
    val key: String,
    val label: String,
    val variants: List<SavedVariantGroup>
) {
    val products: List<Product>
        get() = variants.flatMap { it.products }

    val productCount: Int
        get() = products.size

    val storeCount: Int
        get() = products.flatMap { product ->
            listOf(product.currentStore.name) + product.offers.map { it.store.name }
        }.filter { it.isNotBlank() }
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
            .size
}

private fun savedIdentityParts(product: Product): List<String> =
    onlineProductIdentityKey(product.title).split('|').filter { it.isNotBlank() }

private fun savedFamilyKey(product: Product): String {
    val parts = savedIdentityParts(product)
    // For recognized model keys, all edition/storage/colour variants share a family.
    // Accessory identity remains part of the key so a phone and its case never merge.
    val base = parts.filterNot {
        it.startsWith("variant:") ||
            it.startsWith("memory:") ||
            it.startsWith("color:") ||
            it.startsWith("specs:")
    }
    return base.joinToString("|").ifBlank { normalizeFamilyTitle(product.title) }
}

private fun normalizeFamilyTitle(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

private val SAVED_CAPACITY_PATTERN = Regex(
    """(?i)\b(\d+(?:[.,]\d+)?)\s*(TB|GB)\b(?!\s*(?:RAM|memoria))"""
)
private val SAVED_COLOUR_PATTERN = Regex(
    """(?i)\b(negro|negra|negros|negras|black|nero|blanco|blanca|white|bianco|azul|blue|bleu|navy|celeste|verde|green|mint|amarillo|amarilla|yellow|giallo|rojo|roja|red|rosso|rosa|pink|rose|morado|morada|purple|violet|violeta|gris|gray|grey|silver|plata|plateado|graphite|naranja|orange|dorado|dorada|gold|oro|marron|brown|cafe|beige|arena|sand|lavender|cream|starlight|ultramarine|teal)\b"""
)
private val SAVED_VARIANT_PATTERN = Regex(
    """(?i)\b(pro\s*max|max|plus|pro|ultra|fe|lite|mini|fold|flip|classic|edge|slim|fat|standard|estandar|digital|disc|disk|oled|series|edition|original)\b"""
)

private fun capacityLabel(title: String, identityParts: List<String>): String {
    val matches = SAVED_CAPACITY_PATTERN.findAll(title).toList()
    val match = matches.lastOrNull()
    if (match != null) {
        val amount = match.groupValues[1].replace(',', '.').removeSuffix(".0")
        return amount + " " + match.groupValues[2].uppercase(Locale.ROOT)
    }
    val memoryToken = identityParts.firstOrNull { it.startsWith("memory:") }
        ?.substringAfter(':')
        ?.uppercase(Locale.ROOT)
    return memoryToken ?: "Capacidad no indicada"
}

private fun capacitySortValue(label: String): Double {
    if (label == "Capacidad no indicada") return Double.POSITIVE_INFINITY
    val found = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB)""").find(label)
        ?: return Double.POSITIVE_INFINITY
    val amount = found.groupValues[1].replace(',', '.').toDoubleOrNull()
        ?: return Double.POSITIVE_INFINITY
    return if (found.groupValues[2].equals("TB", true)) amount * 1024.0 else amount
}

private fun variantKey(product: Product): String =
    savedIdentityParts(product).firstOrNull { it.startsWith("variant:") } ?: "variant:base"

private fun variantLabel(key: String): String {
    val raw = key.removePrefix("variant:")
    if (raw == "base" || raw.isBlank()) return "Estándar"
    val labels = raw.split('+').map { token ->
        when (token.lowercase(Locale.ROOT)) {
            "standard", "original", "fat", "estandar" -> "Estándar"
            "plus" -> "Plus"
            "pro" -> "Pro"
            "max" -> "Max"
            "ultra" -> "Ultra"
            "fe" -> "FE"
            "lite" -> "Lite"
            "mini" -> "Mini"
            "fold" -> "Fold"
            "flip" -> "Flip"
            "edge" -> "Edge"
            "slim" -> "Slim"
            "digital" -> "Digital"
            "disc", "disk" -> "Disco"
            "oled" -> "OLED"
            "series" -> "Series"
            "s" -> "S"
            "x" -> "X"
            "classic" -> "Classic"
            else -> token.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
    }.distinct()
    return labels.joinToString(" ")
}

private fun familyLabel(products: List<Product>): String =
    products.map { product ->
        product.title
            .replace(SAVED_CAPACITY_PATTERN, " ")
            .replace(SAVED_COLOUR_PATTERN, " ")
            .replace(SAVED_VARIANT_PATTERN, " ")
            .replace(Regex("""(?i)\b(ram|rom|memoria|storage|almacenamiento)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '/', '|', ',')
    }.filter { it.isNotBlank() }
        .minWithOrNull(compareBy<String> { it.length }.thenBy { it.lowercase(Locale.ROOT) })
        .orEmpty()
        .ifBlank { products.first().brand.ifBlank { products.first().title } }

/** Families -> model edition -> storage -> separately identified colour/SKU product rows. */
internal fun buildSavedProductFamilyBuckets(products: List<Product>): List<SavedProductFamily> =
    products.groupBy(::savedFamilyKey)
        .map { (familyKey, familyProducts) ->
            val variants = familyProducts.groupBy(::variantKey)
                .map { (key, variantProducts) ->
                    val storageGroups = variantProducts.groupBy { product ->
                        capacityLabel(product.title, savedIdentityParts(product))
                    }.map { (label, rows) ->
                        SavedStorageGroup(
                            label = label,
                            products = rows.sortedWith(
                                compareBy<Product> { product ->
                                    savedIdentityParts(product).firstOrNull { it.startsWith("color:") }.orEmpty()
                                }.thenBy { it.currentStore.name.lowercase(Locale.ROOT) }
                                    .thenBy { it.title.lowercase(Locale.ROOT) }
                                    .thenBy { it.id }
                            )
                        )
                    }.sortedWith(
                        compareBy<SavedStorageGroup> { capacitySortValue(it.label) }
                            .thenBy { it.label.lowercase(Locale.ROOT) }
                    )
                    SavedVariantGroup(key, variantLabel(key), storageGroups)
                }.sortedWith(
                    compareBy<SavedVariantGroup> {
                        if (it.key == "variant:base" || it.label == "Estándar") 0 else 1
                    }.thenBy { it.label.lowercase(Locale.ROOT) }
                )
            SavedProductFamily(
                key = familyKey,
                label = familyLabel(familyProducts),
                variants = variants
            )
        }
        .sortedWith(compareBy<SavedProductFamily> { it.label.lowercase(Locale.ROOT) }.thenBy { it.key })
