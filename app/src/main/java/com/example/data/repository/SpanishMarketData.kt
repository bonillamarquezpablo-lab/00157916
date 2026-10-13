package com.example.data.repository

import com.example.data.local.ProductEntity
import com.example.data.local.StoreEntity
import com.example.data.model.Product
import com.example.data.model.ProductCategory
import com.example.data.model.Store
import com.example.data.model.StoreOffer

object SpanishMarketData {

    fun getInitialStoreEntities(): List<StoreEntity> = listOf(
        StoreEntity(
            id = "amazon_es",
            name = "Amazon España",
            domain = "amazon.es",
            brandColorHex = "#FF9900",
            category = "Marketplace Global",
            physicalStoresInSpain = false,
            storeCountApprox = 0,
            headquartersInSpain = "Madrid",
            description = "Mayor comercio electrónico en España, logística Prime y envíos en 1 día."
        ),
        StoreEntity(
            id = "pccomponentes",
            name = "PcComponentes",
            domain = "pccomponentes.com",
            brandColorHex = "#FF6000",
            category = "Informática & Gaming",
            physicalStoresInSpain = true,
            storeCountApprox = 4,
            headquartersInSpain = "Alhama de Murcia",
            description = "Líder español en informática, ordenadores y tecnología con almacén central en Alhama de Murcia."
        ),
        StoreEntity(
            id = "mediamarkt",
            name = "MediaMarkt España",
            domain = "mediamarkt.es",
            brandColorHex = "#DF0000",
            category = "Electrónica & Hogar",
            physicalStoresInSpain = true,
            storeCountApprox = 110,
            headquartersInSpain = "El Prat de Llobregat (Barcelona)",
            description = "Cadena líder de tiendas de electrónica de consumo en toda España con recogida express en 30 minutos."
        ),
        StoreEntity(
            id = "elcorteingles",
            name = "El Corte Inglés",
            domain = "elcorteingles.es",
            brandColorHex = "#006738",
            category = "Grandes Almacenes",
            physicalStoresInSpain = true,
            storeCountApprox = 85,
            headquartersInSpain = "Madrid",
            description = "Grandes almacenes de referencia nacional, garantía oficial y servicio postventa de máxima confianza."
        ),
        StoreEntity(
            id = "carrefour",
            name = "Carrefour España",
            domain = "carrefour.es",
            brandColorHex = "#004E98",
            category = "Hipermercados",
            physicalStoresInSpain = true,
            storeCountApprox = 205,
            headquartersInSpain = "Madrid",
            description = "Red de hipermercados y marketplace online con promociones ChequeAhorro y Días sin IVA."
        ),
        StoreEntity(
            id = "fnac",
            name = "Fnac España",
            domain = "fnac.es",
            brandColorHex = "#E5B517",
            category = "Tecnología & Ocio",
            physicalStoresInSpain = true,
            storeCountApprox = 38,
            headquartersInSpain = "Pozuelo de Alarcón (Madrid)",
            description = "Especialista en imagen, sonido, telefonía, informática y cultura con ventajas para socios."
        ),
        StoreEntity(
            id = "alcampo",
            name = "Alcampo",
            domain = "alcampo.es",
            brandColorHex = "#E30613",
            category = "Hipermercados",
            physicalStoresInSpain = true,
            storeCountApprox = 70,
            headquartersInSpain = "Madrid",
            description = "Hipermercados de gran distribución con precios bajos en tecnología, gran consumo y bazar."
        ),
        StoreEntity(
            id = "leroymerlin",
            name = "Leroy Merlin España",
            domain = "leroymerlin.es",
            brandColorHex = "#5BA42E",
            category = "Hogar & Bricolaje",
            physicalStoresInSpain = true,
            storeCountApprox = 130,
            headquartersInSpain = "Alcobendas (Madrid)",
            description = "Referente en España en acondicionamiento del hogar, domótica inteligente y climatización."
        ),
        StoreEntity(
            id = "decathlon",
            name = "Decathlon España",
            domain = "decathlon.es",
            brandColorHex = "#0082C3",
            category = "Deportes",
            physicalStoresInSpain = true,
            storeCountApprox = 175,
            headquartersInSpain = "San Sebastián de los Reyes (Madrid)",
            description = "Líder indiscutible en material deportivo, pulsómetros y tecnología fitness en España."
        ),
        StoreEntity(
            id = "worten",
            name = "Worten España",
            domain = "worten.es",
            brandColorHex = "#E2001A",
            category = "Electrodomésticos & TV",
            physicalStoresInSpain = true,
            storeCountApprox = 15,
            headquartersInSpain = "Madrid",
            description = "Especialistas en grandes electrodomésticos, televisores e informática con servicio de entrega e instalación."
        ),
        StoreEntity(
            id = "miravia",
            name = "Miravia España",
            domain = "miravia.es",
            brandColorHex = "#7C3AED",
            category = "Marketplace Oficial",
            physicalStoresInSpain = false,
            storeCountApprox = 0,
            headquartersInSpain = "Madrid",
            description = "Marketplace centrado en el mercado español con tiendas oficiales de marcas de tecnología y belleza."
        ),
        StoreEntity(
            id = "game",
            name = "GAME España",
            domain = "game.es",
            brandColorHex = "#1E40AF",
            category = "Videojuegos & Consolas",
            physicalStoresInSpain = true,
            storeCountApprox = 240,
            headquartersInSpain = "Madrid",
            description = "Cadena líder de tiendas de videojuegos, consolas PlayStation, Nintendo, Xbox y accesorios gaming."
        ),
        StoreEntity(
            id = "eroski",
            name = "Eroski",
            domain = "eroski.es",
            brandColorHex = "#D9241C",
            category = "Supermercados",
            physicalStoresInSpain = true,
            storeCountApprox = 1600,
            headquartersInSpain = "Elorrio (Bizkaia)",
            description = "Cooperativa de distribución con presencia en País Vasco, Galicia, Cataluña, Baleares y resto de España."
        ),
        StoreEntity(
            id = "conforama",
            name = "Conforama España",
            domain = "conforama.es",
            brandColorHex = "#DC2626",
            category = "Muebles & TV",
            physicalStoresInSpain = true,
            storeCountApprox = 45,
            headquartersInSpain = "El Prat de Llobregat (Barcelona)",
            description = "Especialistas en equipamiento del hogar, televisores Smart TV de gran diagonal y electrodomésticos."
        ),
        StoreEntity(
            id = "bauhaus",
            name = "Bauhaus España",
            domain = "bauhaus.es",
            brandColorHex = "#C8102E",
            category = "Hogar & Herramientas",
            physicalStoresInSpain = true,
            storeCountApprox = 12,
            headquartersInSpain = "Barcelona",
            description = "Especialistas para la casa, el taller, herramientas eléctricas y el jardín con centros en grandes urbes."
        ),
        StoreEntity(
            id = "ikea",
            name = "IKEA España",
            domain = "ikea.com/es",
            brandColorHex = "#0058A3",
            category = "Hogar & Domótica",
            physicalStoresInSpain = true,
            storeCountApprox = 20,
            headquartersInSpain = "San Sebastián de los Reyes (Madrid)",
            description = "Muebles funcionales, iluminación inteligente Zigbee/Matter y productos para el hogar inteligente."
        ),
        StoreEntity(
            id = "lidl",
            name = "Lidl España",
            domain = "lidl.es",
            brandColorHex = "#0050AA",
            category = "Bazar & Herramientas",
            physicalStoresInSpain = true,
            storeCountApprox = 670,
            headquartersInSpain = "Montcada i Reixac (Barcelona)",
            description = "Bazar tecnológico, electrodomésticos Silvercrest y herramientas Parkside con gran demanda popular."
        ),
        StoreEntity(
            id = "aliexpress_plaza",
            name = "AliExpress Plaza (España)",
            domain = "es.aliexpress.com",
            brandColorHex = "#FF4747",
            category = "Marketplace Almacén España",
            physicalStoresInSpain = false,
            storeCountApprox = 0,
            headquartersInSpain = "Madrid",
            description = "Sección de AliExpress con productos en almacenes españoles, envío en 1-3 días laborables y garantía europea."
        ),
        StoreEntity(
            id = "sprinter",
            name = "Sprinter Deportes",
            domain = "sprinter.es",
            brandColorHex = "#059669",
            category = "Deportes & Calzado",
            physicalStoresInSpain = true,
            storeCountApprox = 200,
            headquartersInSpain = "Elche (Alicante)",
            description = "Cadena española de moda deportiva, zapatillas técnicas y relojes deportivos."
        ),
        StoreEntity(
            id = "powerplanet",
            name = "Powerplanetonline",
            domain = "powerplanetonline.com",
            brandColorHex = "#0284C7",
            category = "Telefonía & Electrónica",
            physicalStoresInSpain = true,
            storeCountApprox = 2,
            headquartersInSpain = "Totana (Murcia)",
            description = "Comercio online español de smartphones, domótica y electrónica con entrega urgente 24h."
        )
    ) + listOf(
        StoreEntity("apple_store", "Apple Store España", "apple.com/es/store", "#3A3A3C", "Móviles & Informática", true, 0, "Web oficial", "Tienda oficial de Apple. Mínimo aún no recibe precios automáticamente."),
        StoreEntity("samsung_store", "Samsung Shop España", "samsung.com/es", "#1428A0", "Móviles & Electrónica", true, 0, "Web oficial", "Tienda oficial de Samsung. Sin integración automática de precios."),
        StoreEntity("google_store", "Google Store España", "store.google.com/es", "#4285F4", "Móviles & Hogar", false, 0, "Web oficial", "Tienda oficial de dispositivos Google. Sin integración automática de precios."),
        StoreEntity("xiaomi_store", "Xiaomi Store España", "mi.com/es", "#FF6900", "Móviles & Hogar", true, 0, "Web oficial", "Tienda oficial de Xiaomi. Sin integración automática de precios."),
        StoreEntity("lenovo_store", "Lenovo España", "lenovo.com/es/es", "#E2231A", "Informática", false, 0, "Web oficial", "Tienda online oficial Lenovo. Sin integración automática de precios."),
        StoreEntity("dell_store", "Dell España", "dell.com/es-es", "#007DB8", "Informática", false, 0, "Web oficial", "Tienda online oficial Dell. Sin integración automática de precios."),
        StoreEntity("hp_store", "HP Store España", "hp.com/es-es/shop", "#0096D6", "Informática", false, 0, "Web oficial", "Tienda online oficial HP. Sin integración automática de precios."),
        StoreEntity("neobyte", "Neobyte", "neobyte.es", "#00A6A6", "Informática & Gaming", true, 0, "Barcelona", "Tienda española de informática y gaming; precios aún no consultados por Mínimo."),
        StoreEntity("coolmod", "Coolmod", "coolmod.com", "#282828", "Informática & Gaming", false, 0, "Web oficial", "Tienda online de informática y gaming; precios aún no consultados por Mínimo."),
        StoreEntity("alternate", "ALTERNATE España", "alternate.es", "#2D5EAA", "Informática & Gaming", false, 0, "Web oficial", "Tienda online de informática; precios aún no consultados por Mínimo."),
        StoreEntity("tradeinn", "Tradeinn", "tradeinn.com", "#222222", "Deportes & Outdoor", false, 0, "Web oficial", "Comercio online de deporte y outdoor; precios aún no consultados por Mínimo."),
        StoreEntity("backmarket", "Back Market España", "backmarket.es", "#1D6159", "Tecnología reacondicionada", false, 0, "Web oficial", "Marketplace reacondicionado; precios aún no consultados por Mínimo."),
        StoreEntity("ldlc", "LDLC España", "ldlc.com/es-es", "#303030", "Informática & Gaming", false, 0, "Web oficial", "Tienda online de informática; precios aún no consultados por Mínimo.")
    )

    fun getInitialProducts(): List<Product> = listOf(
        // 1. Sony WH-1000XM5 (At absolute ATH record today)
        Product(
            id = "sony_wh1000xm5",
            title = "Sony WH-1000XM5 Auriculares Inalámbricos Cancelación de Ruido (Negro)",
            brand = "Sony",
            category = ProductCategory.TV_AUDIO,
            currentPrice = 279.00,
            athMinPrice = 279.00,
            athMinDate = "Hoy (Récord Histórico)",
            athMinStore = "PcComponentes",
            athMaxPrice = 419.00,
            avg90dPrice = 345.00,
            officialPvp = 419.00,
            currentStore = Store.PC_COMPONENTES,
            storeUrl = "https://www.pccomponentes.com/sony-wh-1000xm5",
            ean = "4548736132573",
            imageResName = "prod_sony_headphones",
            imageUrl = "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.PC_COMPONENTES, 279.00, true, "Envío 24h gratis"),
                StoreOffer(Store.AMAZON_ES, 285.00, true, "Envío Prime gratis"),
                StoreOffer(Store.MEDIA_MARKT, 299.00, true, "Recogida 2h gratis"),
                StoreOffer(Store.FNAC, 319.00, true, "5% socios"),
                StoreOffer(Store.EL_CORTE_INGLES, 329.00, true, "Envío estándar")
            ),
            rating = 4.8,
            reviewCount = 3890
        ),

        // 2. PlayStation 5 Slim 1TB (At ATH record)
        Product(
            id = "ps5_slim_digital",
            title = "Sony PlayStation 5 Slim Edición Digital (1TB SSD)",
            brand = "PlayStation",
            category = ProductCategory.GAMING,
            currentPrice = 379.00,
            athMinPrice = 379.00,
            athMinDate = "Esta semana",
            athMinStore = "Carrefour España",
            athMaxPrice = 449.99,
            avg90dPrice = 439.00,
            officialPvp = 449.99,
            currentStore = Store.CARREFOUR,
            storeUrl = "https://www.carrefour.es/consola-playstation-5-slim-digital",
            ean = "0711719577232",
            imageResName = "prod_ps5_slim",
            imageUrl = "https://images.unsplash.com/photo-1606813907291-d86efa9b94db?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.CARREFOUR, 379.00, true, "Envío gratis península"),
                StoreOffer(Store.GAME, 384.99, true, "Puntos GAME y garantía"),
                StoreOffer(Store.AMAZON_ES, 389.00, true, "Envío gratis"),
                StoreOffer(Store.MEDIA_MARKT, 389.00, true, "Envío gratis"),
                StoreOffer(Store.EL_CORTE_INGLES, 399.00, true, "Envío 24h")
            ),
            rating = 4.9,
            reviewCount = 5210
        ),

        // 3. Apple iPhone 16 Pro 128GB
        Product(
            id = "iphone_16_pro_128",
            title = "Apple iPhone 16 Pro (128 GB) - Titanio Natural",
            brand = "Apple",
            category = ProductCategory.SMARTPHONES,
            currentPrice = 1129.00,
            athMinPrice = 1099.00,
            athMinDate = "28 Nov 2025",
            athMinStore = "Amazon España",
            athMaxPrice = 1219.00,
            avg90dPrice = 1179.00,
            officialPvp = 1219.00,
            currentStore = Store.AMAZON_ES,
            storeUrl = "https://www.amazon.es/dp/B0DGH89XXX",
            ean = "0195949738124",
            imageResName = "prod_iphone_16",
            imageUrl = "https://images.unsplash.com/photo-1592750475338-74b7b21085ab?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.AMAZON_ES, 1129.00, true, "Envío Prime gratis"),
                StoreOffer(Store.MEDIA_MARKT, 1129.00, true, "Envío gratis"),
                StoreOffer(Store.PC_COMPONENTES, 1139.00, true, "Envío 24h gratis"),
                StoreOffer(Store.WORTEN, 1189.00, true, "Envío urgente"),
                StoreOffer(Store.EL_CORTE_INGLES, 1219.00, true, "Garantía oficial ECI")
            ),
            rating = 4.9,
            reviewCount = 1450
        ),

        // 4. Smart TV LG OLED evo C4 55"
        Product(
            id = "lg_oled_c4_55",
            title = "LG OLED evo C4 55\" 4K Smart TV (OLED55C44LA, 120Hz/144Hz)",
            brand = "LG",
            category = ProductCategory.TV_AUDIO,
            currentPrice = 1049.00,
            athMinPrice = 999.00,
            athMinDate = "15 Feb 2026",
            athMinStore = "MediaMarkt España",
            athMaxPrice = 1699.00,
            avg90dPrice = 1290.00,
            officialPvp = 1699.00,
            currentStore = Store.MEDIA_MARKT,
            storeUrl = "https://www.mediamarkt.es/lg-oled-55-c4",
            ean = "8806091873214",
            imageResName = "prod_smart_tv",
            imageUrl = "https://images.unsplash.com/photo-1593359677879-a4bb92f829d1?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.MEDIA_MARKT, 1049.00, true, "Instalación disponible"),
                StoreOffer(Store.EL_CORTE_INGLES, 1099.00, true, "Financiación 0%"),
                StoreOffer(Store.PC_COMPONENTES, 1149.00, true, "Envío gratuito"),
                StoreOffer(Store.CONFORAMA, 1199.00, true, "Garantía extendida")
            ),
            rating = 4.9,
            reviewCount = 2100
        ),

        // 5. De'Longhi Magnifica S (At ATH record)
        Product(
            id = "delonghi_magnifica_s",
            title = "De'Longhi Magnifica S ECAM 22.110.B Cafetera Superautomática Grano",
            brand = "De'Longhi",
            category = ProductCategory.HOME_APPLIANCES,
            currentPrice = 269.00,
            athMinPrice = 269.00,
            athMinDate = "Ayer",
            athMinStore = "Amazon España",
            athMaxPrice = 399.00,
            avg90dPrice = 325.00,
            officialPvp = 399.00,
            currentStore = Store.AMAZON_ES,
            storeUrl = "https://www.amazon.es/dp/B00400OM62",
            ean = "8004399324687",
            imageResName = "prod_coffee_machine",
            imageUrl = "https://images.unsplash.com/photo-1517668808822-9ebb02f2a0e6?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.AMAZON_ES, 269.00, true, "Envío Prime gratis"),
                StoreOffer(Store.MEDIA_MARKT, 279.00, true, "Recogida gratis"),
                StoreOffer(Store.CARREFOUR, 299.00, true, "Envío estándar"),
                StoreOffer(Store.ALCAMPO, 309.00, true, "Precio folleto")
            ),
            rating = 4.6,
            reviewCount = 42000
        ),

        // 6. Cosori Freidora de Aire Turbo Blaze 6.0L
        Product(
            id = "cosori_turbo_blaze",
            title = "Cosori Turbo Blaze Freidora sin Aceite 6.0L Motor DC 9 en 1",
            brand = "Cosori",
            category = ProductCategory.HOME_APPLIANCES,
            currentPrice = 109.99,
            athMinPrice = 109.99,
            athMinDate = "Hoy",
            athMinStore = "Amazon España",
            athMaxPrice = 169.99,
            avg90dPrice = 144.00,
            officialPvp = 159.99,
            currentStore = Store.AMAZON_ES,
            storeUrl = "https://www.amazon.es/dp/B0CKZJXXXX",
            ean = "0810043378912",
            imageUrl = "https://images.unsplash.com/photo-1586208958839-06c17cacdf08?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.AMAZON_ES, 109.99, true, "Envío Prime gratis"),
                StoreOffer(Store.MEDIA_MARKT, 129.00, true, "Envío 2.99€"),
                StoreOffer(Store.PC_COMPONENTES, 134.90, true, "Envío 24h")
            ),
            rating = 4.7,
            reviewCount = 8900
        ),

        // 7. Cecotec Mambo CooKing (OFERTA ENGAÑOSA DETECTADA / TRUCO PRE-DESCUENTO)
        Product(
            id = "cecotec_mambo_inflado",
            title = "Cecotec Robot de Cocina Multifunción Mambo CooKing Total Gourmet",
            brand = "Cecotec",
            category = ProductCategory.HOME_APPLIANCES,
            currentPrice = 429.00,
            athMinPrice = 329.00,
            athMinDate = "14 Enero 2026",
            athMinStore = "PcComponentes",
            athMaxPrice = 649.00,
            avg90dPrice = 369.00,
            officialPvp = 599.00,
            currentStore = Store.MEDIA_MARKT,
            storeUrl = "https://www.mediamarkt.es/cecotec-mambo-cooking",
            ean = "8435484042895",
            wasRecentlyInflated = true,
            fakePriorPrice = 649.00,
            imageUrl = "https://images.unsplash.com/photo-1556911220-e15b29be8c8f?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.MEDIA_MARKT, 429.00, true, "Rebajas 'supuestas'"),
                StoreOffer(Store.AMAZON_ES, 399.00, true, "Envío Prime"),
                StoreOffer(Store.PC_COMPONENTES, 379.00, true, "Mejor precio actual")
            ),
            rating = 4.2,
            reviewCount = 980
        ),

        // 8. Garmin Fenix 7 Pro Solar (At ATH record)
        Product(
            id = "garmin_fenix_7_pro",
            title = "Garmin Fénix 7 Pro Solar Reloj Inteligente GPS Multideporte Linterna LED",
            brand = "Garmin",
            category = ProductCategory.SPORTS,
            currentPrice = 549.00,
            athMinPrice = 549.00,
            athMinDate = "Hoy",
            athMinStore = "Decathlon España",
            athMaxPrice = 799.99,
            avg90dPrice = 679.00,
            officialPvp = 799.99,
            currentStore = Store.DECATHLON,
            storeUrl = "https://www.decathlon.es/reloj-garmin-fenix-7-pro",
            ean = "0753759317585",
            imageUrl = "https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.DECATHLON, 549.00, true, "Puntos Decathlon x2"),
                StoreOffer(Store.SPRINTER, 559.00, true, "Garantía deportiva"),
                StoreOffer(Store.AMAZON_ES, 569.00, true, "Envío gratis"),
                StoreOffer(Store.EL_CORTE_INGLES, 599.00, true, "Garantía oficial")
            ),
            rating = 4.8,
            reviewCount = 1120
        ),

        // 9. MacBook Air M3 13" (16GB RAM)
        Product(
            id = "macbook_air_m3_16gb",
            title = "Apple MacBook Air 13\" Chip M3 (16GB RAM, 256GB SSD)",
            brand = "Apple",
            category = ProductCategory.COMPUTING,
            currentPrice = 1079.00,
            athMinPrice = 1079.00,
            athMinDate = "Esta semana",
            athMinStore = "Amazon España",
            athMaxPrice = 1299.00,
            avg90dPrice = 1189.00,
            officialPvp = 1299.00,
            currentStore = Store.AMAZON_ES,
            storeUrl = "https://www.amazon.es/dp/B0CX23M3XX",
            ean = "0195949129482",
            imageUrl = "https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.AMAZON_ES, 1079.00, true, "Envío Prime gratis"),
                StoreOffer(Store.MEDIA_MARKT, 1089.00, true, "Recogida express"),
                StoreOffer(Store.PC_COMPONENTES, 1099.00, true, "Envío 24h"),
                StoreOffer(Store.FNAC, 1149.00, true, "5% socios Fnac")
            ),
            rating = 4.9,
            reviewCount = 2840
        ),

        // 10. ASUS Dual GeForce RTX 4070 Super 12GB
        Product(
            id = "asus_rtx_4070_super",
            title = "ASUS Dual GeForce RTX 4070 Super EVO 12GB GDDR6X DLSS 3",
            brand = "ASUS",
            category = ProductCategory.COMPUTING,
            currentPrice = 589.90,
            athMinPrice = 579.00,
            athMinDate = "05 Enero 2026",
            athMinStore = "PcComponentes",
            athMaxPrice = 689.00,
            avg90dPrice = 635.00,
            officialPvp = 659.90,
            currentStore = Store.PC_COMPONENTES,
            storeUrl = "https://www.pccomponentes.com/asus-dual-geforce-rtx-4070-super-12gb",
            ean = "4711387498124",
            imageUrl = "https://images.unsplash.com/photo-1587202372775-e229f172b9d7?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.PC_COMPONENTES, 589.90, true, "Envío 24h gratis"),
                StoreOffer(Store.AMAZON_ES, 599.00, true, "Envío gratis"),
                StoreOffer(Store.MEDIA_MARKT, 629.00, true, "Recogida tienda")
            ),
            rating = 4.8,
            reviewCount = 940
        ),

        // 11. Roborock Q Revo
        Product(
            id = "roborock_q_revo",
            title = "Roborock Q Revo Robot Aspirador Fregasuelos Base Autovaciado",
            brand = "Roborock",
            category = ProductCategory.HOME_APPLIANCES,
            currentPrice = 599.00,
            athMinPrice = 599.00,
            athMinDate = "Hoy",
            athMinStore = "Amazon España",
            athMaxPrice = 849.00,
            avg90dPrice = 699.00,
            officialPvp = 849.00,
            currentStore = Store.AMAZON_ES,
            storeUrl = "https://www.amazon.es/dp/B0C8VXXXXX",
            ean = "6970995786312",
            imageUrl = "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.AMAZON_ES, 599.00, true, "Envío Prime"),
                StoreOffer(Store.MEDIA_MARKT, 649.00, true, "Envío 24h"),
                StoreOffer(Store.LEROY_MERLIN, 669.00, true, "Garantía hogar"),
                StoreOffer(Store.PC_COMPONENTES, 679.00, true, "Stock disponible")
            ),
            rating = 4.7,
            reviewCount = 3400
        ),

        // 12. Nintendo Switch Modelo OLED
        Product(
            id = "switch_oled_white",
            title = "Nintendo Switch Modelo OLED (Joy-Con Blanco)",
            brand = "Nintendo",
            category = ProductCategory.GAMING,
            currentPrice = 309.00,
            athMinPrice = 289.00,
            athMinDate = "24 Nov 2025",
            athMinStore = "Carrefour España",
            athMaxPrice = 349.99,
            avg90dPrice = 329.00,
            officialPvp = 349.99,
            currentStore = Store.CARREFOUR,
            storeUrl = "https://www.carrefour.es/nintendo-switch-oled-blanca",
            ean = "0045496453435",
            imageUrl = "https://images.unsplash.com/photo-1578303512597-8be9abb9471f?w=600",
            priceHistory = emptyList(),
            offers = listOf(
                StoreOffer(Store.CARREFOUR, 309.00, true, "Envío gratuito"),
                StoreOffer(Store.GAME, 312.99, true, "Con puntos de socio"),
                StoreOffer(Store.AMAZON_ES, 314.99, true, "Envío Prime"),
                StoreOffer(Store.FNAC, 319.99, true, "5% socios"),
                StoreOffer(Store.MEDIA_MARKT, 329.00, true, "Recogida tienda")
            ),
            rating = 4.8,
            reviewCount = 15300
        )
    ) + getAdditionalDemoProducts()


    private data class DemoProductSeed(
        val id: String, val title: String, val brand: String,
        val category: ProductCategory, val referencePrice: Double, val store: Store
    )

    /**
     * Discovery fixtures only: reference amounts are never presented as live offers.
     * Every generated product is explicitly unverified until a source connector confirms it.
     */
    private fun getAdditionalDemoProducts(): List<Product> = listOf(
        DemoProductSeed("demo_galaxy_s26_ultra", "Samsung Galaxy S26 Ultra 512 GB", "Samsung", ProductCategory.SMARTPHONES, 1499.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_iphone_17_pro_max", "Apple iPhone 17 Pro Max 256 GB", "Apple", ProductCategory.SMARTPHONES, 1449.0, Store.APPLE_STORE),
        DemoProductSeed("demo_pixel_pro", "Google Pixel Pro 256 GB", "Google", ProductCategory.SMARTPHONES, 1099.0, Store.GOOGLE_STORE),
        DemoProductSeed("demo_xiaomi_flagship", "Xiaomi flagship 512 GB", "Xiaomi", ProductCategory.SMARTPHONES, 899.0, Store.XIAOMI_STORE),
        DemoProductSeed("demo_galaxy_tab_ultra", "Samsung Galaxy Tab Ultra 256 GB", "Samsung", ProductCategory.SMARTPHONES, 1199.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_ipad_air", "Apple iPad Air 11 pulgadas 128 GB", "Apple", ProductCategory.SMARTPHONES, 699.0, Store.APPLE_STORE),
        DemoProductSeed("demo_lenovo_legion", "Lenovo Legion 5 portátil gaming", "Lenovo", ProductCategory.COMPUTING, 1399.0, Store.LENOVO_STORE),
        DemoProductSeed("demo_thinkpad_e", "Lenovo ThinkPad E14", "Lenovo", ProductCategory.COMPUTING, 899.0, Store.LENOVO_STORE),
        DemoProductSeed("demo_dell_xps", "Dell XPS 14 portátil", "Dell", ProductCategory.COMPUTING, 1699.0, Store.DELL_STORE),
        DemoProductSeed("demo_hp_omen", "HP OMEN portátil gaming", "HP", ProductCategory.COMPUTING, 1599.0, Store.HP_STORE),
        DemoProductSeed("demo_rtx_5070_ti", "ASUS GeForce RTX 5070 Ti 16 GB", "ASUS", ProductCategory.COMPUTING, 899.0, Store.PC_COMPONENTES),
        DemoProductSeed("demo_rtx_5070", "MSI GeForce RTX 5070 12 GB", "MSI", ProductCategory.COMPUTING, 699.0, Store.COOLMOD),
        DemoProductSeed("demo_ryzen_9800x3d", "AMD Ryzen 7 9800X3D", "AMD", ProductCategory.COMPUTING, 499.0, Store.NEOBYTE),
        DemoProductSeed("demo_ryzen_9600x", "AMD Ryzen 5 9600X", "AMD", ProductCategory.COMPUTING, 259.0, Store.ALTERNATE),
        DemoProductSeed("demo_samsung_990pro", "Samsung 990 PRO NVMe SSD 2 TB", "Samsung", ProductCategory.COMPUTING, 189.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_wd_black", "WD_BLACK SN850X NVMe SSD 2 TB", "Western Digital", ProductCategory.COMPUTING, 179.0, Store.AMAZON_ES),
        DemoProductSeed("demo_kingston_ddr5", "Kingston Fury DDR5 32 GB", "Kingston", ProductCategory.COMPUTING, 119.0, Store.PC_COMPONENTES),
        DemoProductSeed("demo_gigabyte_b850", "Gigabyte B850 placa base AM5", "Gigabyte", ProductCategory.COMPUTING, 199.0, Store.ALTERNATE),
        DemoProductSeed("demo_lg_ultragear_oled", "LG UltraGear OLED 27 pulgadas", "LG", ProductCategory.COMPUTING, 799.0, Store.PC_COMPONENTES),
        DemoProductSeed("demo_logitech_g923", "Logitech G923 volante de simulación", "Logitech", ProductCategory.GAMING, 299.0, Store.GAME),
        DemoProductSeed("demo_ps5_pro", "Sony PlayStation 5 Pro", "Sony", ProductCategory.GAMING, 799.0, Store.GAME),
        DemoProductSeed("demo_xbox_series_x", "Xbox Series X 1 TB", "Microsoft", ProductCategory.GAMING, 549.0, Store.CARREFOUR),
        DemoProductSeed("demo_switch_2", "Nintendo Switch 2", "Nintendo", ProductCategory.GAMING, 469.0, Store.GAME),
        DemoProductSeed("demo_steam_deck", "Steam Deck OLED 1 TB", "Valve", ProductCategory.GAMING, 679.0, Store.AMAZON_ES),
        DemoProductSeed("demo_dualsense_edge", "Sony DualSense Edge", "Sony", ProductCategory.GAMING, 219.0, Store.GAME),
        DemoProductSeed("demo_playstation_portal", "PlayStation Portal", "Sony", ProductCategory.GAMING, 219.0, Store.GAME),
        DemoProductSeed("demo_meta_quest", "Meta Quest 3S 128 GB", "Meta", ProductCategory.GAMING, 329.0, Store.MEDIA_MARKT),
        DemoProductSeed("demo_sony_xm6", "Sony WH-1000XM6 auriculares", "Sony", ProductCategory.TV_AUDIO, 449.0, Store.FNAC),
        DemoProductSeed("demo_bose_qc_ultra", "Bose QuietComfort Ultra", "Bose", ProductCategory.TV_AUDIO, 379.0, Store.EL_CORTE_INGLES),
        DemoProductSeed("demo_sennheiser_m4", "Sennheiser Momentum 4 Wireless", "Sennheiser", ProductCategory.TV_AUDIO, 299.0, Store.FNAC),
        DemoProductSeed("demo_samsung_oled_42", "Samsung OLED 42 pulgadas", "Samsung", ProductCategory.TV_AUDIO, 1399.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_lg_oled_42", "LG OLED 42 pulgadas serie C", "LG", ProductCategory.TV_AUDIO, 1099.0, Store.MEDIA_MARKT),
        DemoProductSeed("demo_samsung_q990", "Samsung barra de sonido Q990", "Samsung", ProductCategory.TV_AUDIO, 1199.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_jbl_flip", "JBL Flip altavoz Bluetooth", "JBL", ProductCategory.TV_AUDIO, 149.0, Store.CARREFOUR),
        DemoProductSeed("demo_sonos_era", "Sonos Era 100", "Sonos", ProductCategory.TV_AUDIO, 229.0, Store.EL_CORTE_INGLES),
        DemoProductSeed("demo_galaxy_buds", "Samsung Galaxy Buds Pro", "Samsung", ProductCategory.TV_AUDIO, 199.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_roborock_saros", "Roborock Saros robot aspirador", "Roborock", ProductCategory.HOME_APPLIANCES, 1499.0, Store.AMAZON_ES),
        DemoProductSeed("demo_dreame_l40", "Dreame L40 Ultra robot aspirador", "Dreame", ProductCategory.HOME_APPLIANCES, 999.0, Store.AMAZON_ES),
        DemoProductSeed("demo_dyson_v15", "Dyson V15 Detect aspiradora", "Dyson", ProductCategory.HOME_APPLIANCES, 699.0, Store.EL_CORTE_INGLES),
        DemoProductSeed("demo_philips_hue", "Philips Hue kit de inicio", "Philips Hue", ProductCategory.HOME_APPLIANCES, 89.0, Store.LEROY_MERLIN),
        DemoProductSeed("demo_xiaomi_airfryer", "Xiaomi Air Fryer 6.5 L", "Xiaomi", ProductCategory.HOME_APPLIANCES, 129.0, Store.XIAOMI_STORE),
        DemoProductSeed("demo_conga_robot", "Cecotec Conga robot aspirador", "Cecotec", ProductCategory.HOME_APPLIANCES, 449.0, Store.POWERPLANET),
        DemoProductSeed("demo_delonghi_magnifica", "DeLonghi Magnifica cafetera automática", "DeLonghi", ProductCategory.HOME_APPLIANCES, 499.0, Store.CARREFOUR),
        DemoProductSeed("demo_bosch_dishwasher", "Bosch lavavajillas serie 6", "Bosch", ProductCategory.HOME_APPLIANCES, 699.0, Store.WORTEN),
        DemoProductSeed("demo_garmin_fenix", "Garmin Fenix reloj multideporte", "Garmin", ProductCategory.SPORTS, 999.0, Store.DECATHLON),
        DemoProductSeed("demo_garmin_forerunner", "Garmin Forerunner reloj GPS", "Garmin", ProductCategory.SPORTS, 399.0, Store.DECATHLON),
        DemoProductSeed("demo_apple_watch", "Apple Watch SE", "Apple", ProductCategory.SPORTS, 279.0, Store.APPLE_STORE),
        DemoProductSeed("demo_xiaomi_band", "Xiaomi Smart Band", "Xiaomi", ProductCategory.SPORTS, 49.0, Store.XIAOMI_STORE),
        DemoProductSeed("demo_oralb_io6", "Oral-B iO 6 cepillo eléctrico", "Oral-B", ProductCategory.PERSONAL_CARE, 149.0, Store.CARREFOUR),
        DemoProductSeed("demo_philips_oneblade", "Philips OneBlade 360", "Philips", ProductCategory.PERSONAL_CARE, 39.0, Store.ALCAMPO),
        DemoProductSeed("demo_philips_airfryer", "Philips Airfryer serie 5000", "Philips", ProductCategory.HOME_APPLIANCES, 149.0, Store.WORTEN),
        DemoProductSeed("demo_dji_mini", "DJI Mini dron compacto", "DJI", ProductCategory.TV_AUDIO, 899.0, Store.MEDIA_MARKT),
        DemoProductSeed("demo_canon_r50", "Canon EOS R50 cámara sin espejo", "Canon", ProductCategory.TV_AUDIO, 779.0, Store.EL_CORTE_INGLES),
        DemoProductSeed("demo_galaxy_watch_ultra", "Samsung Galaxy Watch Ultra", "Samsung", ProductCategory.SPORTS, 499.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_logitech_mx_master", "Logitech MX Master ratón inalámbrico", "Logitech", ProductCategory.COMPUTING, 119.0, Store.NEOBYTE),
        DemoProductSeed("demo_anker_prime_bank", "Anker Prime batería externa USB-C", "Anker", ProductCategory.HOME_APPLIANCES, 179.0, Store.AMAZON_ES),
        DemoProductSeed("demo_ugreen_charger", "UGREEN cargador USB-C GaN", "UGREEN", ProductCategory.HOME_APPLIANCES, 149.0, Store.AMAZON_ES),
        DemoProductSeed("demo_tp_link_mesh", "TP-Link Deco Wi-Fi mesh", "TP-Link", ProductCategory.COMPUTING, 399.0, Store.PC_COMPONENTES),
        DemoProductSeed("demo_kindle_paperwhite", "Amazon Kindle Paperwhite", "Amazon", ProductCategory.TV_AUDIO, 159.0, Store.AMAZON_ES),
        DemoProductSeed("demo_samsung_t7", "Samsung T7 Shield SSD externo 2 TB", "Samsung", ProductCategory.COMPUTING, 169.0, Store.SAMSUNG_STORE),
        DemoProductSeed("demo_tradeinn_shoes", "Zapatillas running de entrenamiento", "Running", ProductCategory.SPORTS, 119.0, Store.TRADEINN),
        DemoProductSeed("demo_backmarket_phone", "Smartphone reacondicionado", "Back Market", ProductCategory.SMARTPHONES, 299.0, Store.BACKMARKET),
        DemoProductSeed("demo_ldlc_pc", "PC gaming configurado", "LDLC", ProductCategory.COMPUTING, 1299.0, Store.LDLC)
    ).map { seed ->
        Product(
            id = seed.id, title = seed.title, brand = seed.brand, category = seed.category,
            currentPrice = seed.referencePrice, athMinPrice = seed.referencePrice,
            athMinDate = "Sin datos", athMinStore = "Sin datos",
            athMaxPrice = seed.referencePrice, avg90dPrice = seed.referencePrice,
            officialPvp = seed.referencePrice, currentStore = seed.store,
            storeUrl = seed.store.searchUrlFor(seed.title), ean = "",
            priceHistory = emptyList(), offers = emptyList(), rating = 0.0,
            reviewCount = 0, isPriceVerified = false
        )
    }

}
