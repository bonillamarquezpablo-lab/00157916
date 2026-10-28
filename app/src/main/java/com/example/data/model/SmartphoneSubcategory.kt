package com.example.data.model

import java.text.Normalizer
import java.util.Locale

/**
 * A lightweight browsing group inside the "Móviles & Tablets" storefront.
 *
 * Products stay in their existing Room category. Each subcategory declares the existing
 * catalogue streams it needs, then filters their already-visible rows by a conservative title
 * match. This avoids a database migration and never duplicates or moves saved product records.
 */
enum class SmartphoneSubcategory(
    val title: String,
    val description: String,
    val sourceCategories: List<ProductCategory>
) {
    PHONES(
        "Móviles",
        "iPhone, Samsung, Pixel y otros teléfonos",
        listOf(ProductCategory.SMARTPHONES)
    ),
    TABLETS(
        "Tablets",
        "iPad, Galaxy Tab y tablets Android",
        listOf(ProductCategory.SMARTPHONES)
    ),
    POWER_BANKS(
        "Baterías portátiles",
        "Power banks y baterías externas",
        listOf(ProductCategory.ACCESSORIES, ProductCategory.COMPUTING, ProductCategory.SMARTPHONES)
    ),
    CHARGERS(
        "Cargadores",
        "Cargadores de pared, inalámbricos y rápidos",
        listOf(ProductCategory.ACCESSORIES, ProductCategory.COMPUTING, ProductCategory.SMARTPHONES)
    ),
    CABLES(
        "Cables",
        "USB-C, Lightning y otros cables",
        listOf(ProductCategory.ACCESSORIES, ProductCategory.COMPUTING, ProductCategory.SMARTPHONES)
    ),
    CASES_PROTECTORS(
        "Fundas y protectores",
        "Fundas, carcasas y protección de pantalla",
        listOf(ProductCategory.ACCESSORIES, ProductCategory.SMARTPHONES)
    ),
    OTHER_ACCESSORIES(
        "Otros accesorios",
        "Soportes, adaptadores y complementos para móviles",
        listOf(ProductCategory.ACCESSORIES, ProductCategory.COMPUTING, ProductCategory.SMARTPHONES)
    );

    fun matches(product: Product): Boolean = matchesTitle(product.title)

    internal fun matchesTitle(title: String): Boolean {
        val value = normalizeForSubcategory(title)
        if (value.isBlank()) return false

        val isTablet = TABLET_TERMS.containsMatchIn(value)
        val isPowerBank = POWER_BANK_TERMS.containsMatchIn(value)
        val isCharger = CHARGER_TERMS.containsMatchIn(value) && !isPowerBank
        val isCable = CABLE_TERMS.containsMatchIn(value) && !isPowerBank && !isCharger
        val isCaseOrProtector = CASE_TERMS.containsMatchIn(value)
        val isPhone = !isTablet && !isPowerBank && !isCharger && !isCable && !isCaseOrProtector &&
            PHONE_TERMS.containsMatchIn(value)

        return when (this) {
            PHONES -> isPhone
            TABLETS -> isTablet
            POWER_BANKS -> isPowerBank
            CHARGERS -> isCharger
            CABLES -> isCable
            CASES_PROTECTORS -> isCaseOrProtector && (
                value.contains("iphone") ||
                    value.contains("ipad") ||
                    value.contains("galaxy") ||
                    value.contains("pixel") ||
                    value.contains("movil") ||
                    value.contains("telefono") ||
                    value.contains("smartphone") ||
                    value.contains("tablet")
                )
            OTHER_ACCESSORIES -> {
                val accessorySignal = OTHER_MOBILE_ACCESSORY_TERMS.containsMatchIn(value)
                accessorySignal && !isPhone && !isTablet && !isPowerBank && !isCharger &&
                    !isCable && !isCaseOrProtector
            }
        }
    }

    companion object {
        // Keep this list model-family based: brand-only hits would pull accessories into
        // Móviles. Accessories and tablets are still excluded before PHONE_TERMS is evaluated.
        private val PHONE_TERMS = Regex(
            """\b(iphone|smartphone|telefono movil|movil|telefono|redmi|poco\s+[fxmc]\s*\d+[a-z]*|poco f\d+|pixel\s+\d+(?:\s+(?:pro|xl|a))?|pixel fold|samsung galaxy\s+[sazm]\s*\d*|galaxy\s+[sazm]\s*\d+|galaxy z fold|galaxy z flip|xiaomi mi\s+\d{1,2}[a-z]*|xiaomi\s+\d{1,2}[a-z]*|oneplus(?:\s+\d+[a-z]*|\s+nord(?:\s+\w+)*|\s+open)|motorola edge|moto g\s*\d+[a-z]*|motorola razr|oppo (?:find|reno)\s+[a-z0-9]+|oppo a\s*\d+[a-z]*|vivo [xvy]\s*\d+[a-z]*|huawei (?:p|mate|nova|y|pura)\s*\d+[a-z]*|honor (?:magic\s*\d+|x\s*\d+|\d{2,3})|realme (?:gt(?:\s+neo)?|c|number)\s*\d+[a-z]*|nothing phone|sony xperia|asus zenfone|rog phone|nubia redmagic|fairphone\s*\d+)\b"""
        )
        private val TABLET_TERMS = Regex(
            """\b(tablet|tableta|ipad|galaxy tab|matepad|xiaomi pad|redmi pad|surface go|lenovo tab)\b"""
        )
        private val POWER_BANK_TERMS = Regex(
            """\b(power ?bank|bateria externa|bateria portatil|banco de energia|portable charger|magsafe battery pack)\b"""
        )
        private val CHARGER_TERMS = Regex(
            """\b(cargador(?:es)?|charger|charging adapter|adaptador de corriente|carga rapida|cargador inalambrico|wireless charger|dock de carga)\b"""
        )
        private val CABLE_TERMS = Regex(
            """\b(cable(?:s)?|usb ?c|usb ?a|lightning|micro ?usb|cable trenzado|cable de datos)\b"""
        )
        private val CASE_TERMS = Regex(
            """\b(funda(?:s)?|carcasa(?:s)?|protector(?:es)?|cristal templado|vidrio templado|screen protector|phone case|tablet case|cover)\b"""
        )
        private val OTHER_MOBILE_ACCESSORY_TERMS = Regex(
            """\b(soporte(?:s)?|holder|stand|palo selfie|selfie stick|stylus|lapiz tactil|magsafe|anillo magnetico|pop ?socket|adaptador usb|dongle|soporte para (?:iphone|movil|telefono|smartphone)|soporte de movil)\b"""
        )
    }
}

private fun normalizeForSubcategory(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
