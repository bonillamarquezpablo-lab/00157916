package com.example.data.model

import androidx.compose.ui.graphics.Color

data class Store(
    val id: String,
    val name: String,
    val domain: String,
    val brandColor: Color,
    val isVerifiedSpanish: Boolean = true,
    val category: String = "General",
    val physicalStores: Boolean = true
) {
    companion object {
        // Grandes tiendas del mercado español
        val AMAZON_ES = Store("amazon_es", "Amazon España", "amazon.es", Color(0xFFFF9900), category = "Marketplace Global", physicalStores = false)
        val PC_COMPONENTES = Store("pccomponentes", "PcComponentes", "pccomponentes.com", Color(0xFFFF6000), category = "Informática & Gaming", physicalStores = true)
        val MEDIA_MARKT = Store("mediamarkt", "MediaMarkt España", "mediamarkt.es", Color(0xFFDF0000), category = "Electrónica & Hogar", physicalStores = true)
        val EL_CORTE_INGLES = Store("elcorteingles", "El Corte Inglés", "elcorteingles.es", Color(0xFF006738), category = "Grandes Almacenes", physicalStores = true)
        val CARREFOUR = Store("carrefour", "Carrefour España", "carrefour.es", Color(0xFF004E98), category = "Hipermercados", physicalStores = true)
        val FNAC = Store("fnac", "Fnac España", "fnac.es", Color(0xFFE5B517), category = "Tecnología & Ocio", physicalStores = true)
        val ALCAMPO = Store("alcampo", "Alcampo", "alcampo.es", Color(0xFFE30613), category = "Hipermercados", physicalStores = true)
        val LEROY_MERLIN = Store("leroymerlin", "Leroy Merlin España", "leroymerlin.es", Color(0xFF5BA42E), category = "Hogar & Bricolaje", physicalStores = true)
        val DECATHLON = Store("decathlon", "Decathlon España", "decathlon.es", Color(0xFF0082C3), category = "Deportes", physicalStores = true)
        val WORTEN = Store("worten", "Worten España", "worten.es", Color(0xFFE2001A), category = "Electrodomésticos & TV", physicalStores = true)
        val MIRAVIA = Store("miravia", "Miravia España", "miravia.es", Color(0xFF7C3AED), category = "Marketplace Moda & Tec", physicalStores = false)
        val GAME = Store("game", "GAME España", "game.es", Color(0xFF1E40AF), category = "Videojuegos & Consolas", physicalStores = true)
        val EROSKI = Store("eroski", "Eroski", "eroski.es", Color(0xFFD9241C), category = "Supermercados", physicalStores = true)
        val CONFORAMA = Store("conforama", "Conforama España", "conforama.es", Color(0xFFDC2626), category = "Muebles & TV", physicalStores = true)
        val BAUHAUS = Store("bauhaus", "Bauhaus España", "bauhaus.es", Color(0xFFC8102E), category = "Hogar & Herramientas", physicalStores = true)
        val IKEA = Store("ikea", "IKEA España", "ikea.com/es", Color(0xFF0058A3), category = "Hogar & Domótica", physicalStores = true)
        val LIDL = Store("lidl", "Lidl España", "lidl.es", Color(0xFF0050AA), category = "Bazar & Herramientas", physicalStores = true)
        val ALIEXPRESS_PLAZA = Store("aliexpress_plaza", "AliExpress Plaza (España)", "es.aliexpress.com", Color(0xFFFF4747), category = "Marketplace España", physicalStores = false)
        val SPRINTER = Store("sprinter", "Sprinter Deportes", "sprinter.es", Color(0xFF059669), category = "Deportes & Calzado", physicalStores = true)
        val POWERPLANET = Store("powerplanet", "Powerplanetonline", "powerplanetonline.com", Color(0xFF0284C7), category = "Telefonía & Electrónica", physicalStores = true)

        val ALL_STORES = listOf(
            AMAZON_ES,
            PC_COMPONENTES,
            MEDIA_MARKT,
            EL_CORTE_INGLES,
            CARREFOUR,
            FNAC,
            ALCAMPO,
            LEROY_MERLIN,
            DECATHLON,
            WORTEN,
            MIRAVIA,
            GAME,
            EROSKI,
            CONFORAMA,
            BAUHAUS,
            IKEA,
            LIDL,
            ALIEXPRESS_PLAZA,
            SPRINTER,
            POWERPLANET
        )

        fun findByName(name: String): Store {
            return ALL_STORES.firstOrNull {
                it.name.contains(name, ignoreCase = true) ||
                it.id.equals(name, ignoreCase = true) ||
                it.domain.contains(name, ignoreCase = true)
            } ?: AMAZON_ES
        }
    }
}
