package com.example.data.model

import androidx.compose.ui.graphics.Color
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Retailer directory entry. Being listed does not mean that Mínimo can retrieve its
 * live prices; that needs a separate, permitted data connector.
 */
data class Store(
    val id: String,
    val name: String,
    val domain: String,
    val brandColor: Color,
    val isVerifiedSpanish: Boolean = true,
    val category: String = "General",
    val physicalStores: Boolean = true,
    val hasLivePriceIntegration: Boolean = false,
    /** Count of valid saved price observations used by the live store directory. */
    val savedPriceCount: Int = 0,
    /** False when we only have an indirect Google Shopping link, not the merchant's website. */
    val hasOfficialSite: Boolean = true
) {
    val officialUrl: String
        get() = when (id) {
            "amazon_es" -> "https://www.amazon.es/"
            "pccomponentes" -> "https://www.pccomponentes.com/"
            "mediamarkt" -> "https://www.mediamarkt.es/"
            "elcorteingles" -> "https://www.elcorteingles.es/"
            "carrefour" -> "https://www.carrefour.es/"
            "fnac" -> "https://www.fnac.es/"
            "alcampo" -> "https://www.alcampo.es/"
            "leroymerlin" -> "https://www.leroymerlin.es/"
            "decathlon" -> "https://www.decathlon.es/"
            "worten" -> "https://www.worten.es/"
            "miravia" -> "https://www.miravia.es/"
            "game" -> "https://www.game.es/"
            "eroski" -> "https://www.eroski.es/"
            "conforama" -> "https://www.conforama.es/"
            "bauhaus" -> "https://www.bauhaus.es/"
            "ikea" -> "https://www.ikea.com/es/es/"
            "lidl" -> "https://www.lidl.es/"
            "aliexpress_plaza" -> "https://es.aliexpress.com/"
            "sprinter" -> "https://www.sprinter.es/"
            "powerplanet" -> "https://www.powerplanetonline.com/"
            "apple_store" -> "https://www.apple.com/es/store"
            "samsung_store" -> "https://www.samsung.com/es/"
            "google_store" -> "https://store.google.com/es/"
            "xiaomi_store" -> "https://www.mi.com/es/"
            "lenovo_store" -> "https://www.lenovo.com/es/es/"
            "dell_store" -> "https://www.dell.com/es-es/"
            "hp_store" -> "https://www.hp.com/es-es/shop/"
            "neobyte" -> "https://www.neobyte.es/"
            "coolmod" -> "https://www.coolmod.com/"
            "alternate" -> "https://www.alternate.es/"
            "tradeinn" -> "https://www.tradeinn.com/"
            "backmarket" -> "https://www.backmarket.es/"
            "ldlc" -> "https://www.ldlc.com/es-es/"
            else -> {
                val host = domain.substringBefore("/")
                "https://${if (host.startsWith("www.")) host else "www.$host"}/"
            }
        }

    /** Opens search results restricted to the retailer's domain; it does not fetch prices. */
    fun searchUrlFor(query: String): String {
        val host = domain.substringBefore("/").removePrefix("www.")
        val encoded = URLEncoder.encode("site:$host $query", StandardCharsets.UTF_8.name())
        return "https://www.google.com/search?q=$encoded"
    }

    companion object {
        val AMAZON_ES = Store("amazon_es", "Amazon España", "amazon.es", Color(0xFFFF9900), category = "Marketplace", physicalStores = false)
        val PC_COMPONENTES = Store("pccomponentes", "PcComponentes", "pccomponentes.com", Color(0xFFFF6000), category = "Informática & Gaming")
        val MEDIA_MARKT = Store("mediamarkt", "MediaMarkt España", "mediamarkt.es", Color(0xFFDF0000), category = "Electrónica & Hogar")
        val EL_CORTE_INGLES = Store("elcorteingles", "El Corte Inglés", "elcorteingles.es", Color(0xFF006738), category = "Grandes Almacenes")
        val CARREFOUR = Store("carrefour", "Carrefour España", "carrefour.es", Color(0xFF004E98), category = "Hipermercados")
        val FNAC = Store("fnac", "Fnac España", "fnac.es", Color(0xFFE5B517), category = "Tecnología & Ocio")
        val ALCAMPO = Store("alcampo", "Alcampo", "alcampo.es", Color(0xFFE30613), category = "Hipermercados")
        val LEROY_MERLIN = Store("leroymerlin", "Leroy Merlin España", "leroymerlin.es", Color(0xFF5BA42E), category = "Hogar & Bricolaje")
        val DECATHLON = Store("decathlon", "Decathlon España", "decathlon.es", Color(0xFF0082C3), category = "Deportes")
        val WORTEN = Store("worten", "Worten España", "worten.es", Color(0xFFE2001A), category = "Electrodomésticos & TV")
        val MIRAVIA = Store("miravia", "Miravia España", "miravia.es", Color(0xFF7C3AED), category = "Marketplace", physicalStores = false)
        val GAME = Store("game", "GAME España", "game.es", Color(0xFF1E40AF), category = "Videojuegos & Consolas")
        val EROSKI = Store("eroski", "Eroski", "eroski.es", Color(0xFFD9241C), category = "Supermercados")
        val CONFORAMA = Store("conforama", "Conforama España", "conforama.es", Color(0xFFDC2626), category = "Muebles & TV")
        val BAUHAUS = Store("bauhaus", "Bauhaus España", "bauhaus.es", Color(0xFFC8102E), category = "Hogar & Herramientas")
        val IKEA = Store("ikea", "IKEA España", "ikea.com/es", Color(0xFF0058A3), category = "Hogar & Domótica")
        val LIDL = Store("lidl", "Lidl España", "lidl.es", Color(0xFF0050AA), category = "Bazar & Herramientas")
        val ALIEXPRESS_PLAZA = Store("aliexpress_plaza", "AliExpress España", "es.aliexpress.com", Color(0xFFFF4747), category = "Marketplace", physicalStores = false)
        val SPRINTER = Store("sprinter", "Sprinter Deportes", "sprinter.es", Color(0xFF059669), category = "Deportes & Calzado")
        val POWERPLANET = Store("powerplanet", "Powerplanetonline", "powerplanetonline.com", Color(0xFF0284C7), category = "Telefonía & Electrónica", physicalStores = false)

        val APPLE_STORE = Store("apple_store", "Apple Store España", "apple.com/es/store", Color(0xFF3A3A3C), category = "Móviles & Informática")
        val SAMSUNG_STORE = Store("samsung_store", "Samsung Shop España", "samsung.com/es", Color(0xFF1428A0), category = "Móviles & Electrónica")
        val GOOGLE_STORE = Store("google_store", "Google Store España", "store.google.com/es", Color(0xFF4285F4), category = "Móviles & Hogar", physicalStores = false)
        val XIAOMI_STORE = Store("xiaomi_store", "Xiaomi Store España", "mi.com/es", Color(0xFFFF6900), category = "Móviles & Hogar")
        val LENOVO_STORE = Store("lenovo_store", "Lenovo España", "lenovo.com/es/es", Color(0xFFE2231A), category = "Informática", physicalStores = false)
        val DELL_STORE = Store("dell_store", "Dell España", "dell.com/es-es", Color(0xFF007DB8), category = "Informática", physicalStores = false)
        val HP_STORE = Store("hp_store", "HP Store España", "hp.com/es-es/shop", Color(0xFF0096D6), category = "Informática", physicalStores = false)
        val NEOBYTE = Store("neobyte", "Neobyte", "neobyte.es", Color(0xFF00A6A6), category = "Informática & Gaming")
        val COOLMOD = Store("coolmod", "Coolmod", "coolmod.com", Color(0xFF282828), category = "Informática & Gaming", physicalStores = false)
        val ALTERNATE = Store("alternate", "ALTERNATE España", "alternate.es", Color(0xFF2D5EAA), category = "Informática & Gaming", physicalStores = false)
        val TRADEINN = Store("tradeinn", "Tradeinn", "tradeinn.com", Color(0xFF222222), category = "Deportes & Outdoor", physicalStores = false)
        val BACKMARKET = Store("backmarket", "Back Market España", "backmarket.es", Color(0xFF1D6159), category = "Tecnología reacondicionada", physicalStores = false)
        val LDLC = Store("ldlc", "LDLC España", "ldlc.com/es-es", Color(0xFF303030), category = "Informática & Gaming", physicalStores = false)

        val ALL_STORES = listOf(
            AMAZON_ES, PC_COMPONENTES, MEDIA_MARKT, EL_CORTE_INGLES, CARREFOUR, FNAC,
            ALCAMPO, LEROY_MERLIN, DECATHLON, WORTEN, MIRAVIA, GAME, EROSKI,
            CONFORAMA, BAUHAUS, IKEA, LIDL, ALIEXPRESS_PLAZA, SPRINTER, POWERPLANET,
            APPLE_STORE, SAMSUNG_STORE, GOOGLE_STORE, XIAOMI_STORE, LENOVO_STORE,
            DELL_STORE, HP_STORE, NEOBYTE, COOLMOD, ALTERNATE, TRADEINN, BACKMARKET, LDLC
        )

        fun findByName(name: String): Store = ALL_STORES.firstOrNull {
            it.name.contains(name, ignoreCase = true) ||
                it.id.equals(name, ignoreCase = true) ||
                it.domain.contains(name, ignoreCase = true)
        } ?: AMAZON_ES
    }
}
