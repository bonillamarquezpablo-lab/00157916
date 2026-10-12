package com.example.data.repository

import com.example.data.local.ProductEntity
import com.example.data.local.StoreEntity
import com.example.data.model.PricePoint
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
            priceHistory = generateHistory(
                basePrice = 345.0,
                minPrice = 279.0,
                maxPrice = 419.0,
                currentPrice = 279.0,
                eventDate = "Black Friday",
                eventMin = 289.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 440.0,
                minPrice = 379.0,
                maxPrice = 449.99,
                currentPrice = 379.0,
                eventDate = "Días Gaming",
                eventMin = 399.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 1180.0,
                minPrice = 1099.0,
                maxPrice = 1219.0,
                currentPrice = 1129.0,
                eventDate = "Black Friday",
                eventMin = 1099.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 1290.0,
                minPrice = 999.0,
                maxPrice = 1699.0,
                currentPrice = 1049.0,
                eventDate = "Día sin IVA",
                eventMin = 999.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 325.0,
                minPrice = 269.0,
                maxPrice = 399.0,
                currentPrice = 269.0,
                eventDate = "Semana del Café",
                eventMin = 279.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 145.0,
                minPrice = 109.99,
                maxPrice = 169.99,
                currentPrice = 109.99,
                eventDate = "Oferta Flash",
                eventMin = 119.0
            ),
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
            priceHistory = generateInflatedHistory(
                baseline = 369.0,
                inflated = 649.0,
                current = 429.0,
                ath = 329.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 679.0,
                minPrice = 549.0,
                maxPrice = 799.99,
                currentPrice = 549.0,
                eventDate = "Días del Deporte",
                eventMin = 599.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 1190.0,
                minPrice = 1079.0,
                maxPrice = 1299.0,
                currentPrice = 1079.0,
                eventDate = "Ofertas Primavera",
                eventMin = 1089.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 635.0,
                minPrice = 579.0,
                maxPrice = 689.0,
                currentPrice = 589.90,
                eventDate = "Rebajas Enero",
                eventMin = 579.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 699.0,
                minPrice = 599.0,
                maxPrice = 849.0,
                currentPrice = 599.0,
                eventDate = "Prime Day",
                eventMin = 619.0
            ),
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
            priceHistory = generateHistory(
                basePrice = 329.0,
                minPrice = 289.0,
                maxPrice = 349.99,
                currentPrice = 309.0,
                eventDate = "Black Friday",
                eventMin = 289.0
            ),
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
    )

    private fun generateHistory(
        basePrice: Double,
        minPrice: Double,
        maxPrice: Double,
        currentPrice: Double,
        eventDate: String,
        eventMin: Double
    ): List<PricePoint> {
        val now = System.currentTimeMillis()
        val dayMillis = 86400000L

        return listOf(
            PricePoint(now - (300 * dayMillis), "Hace 10 meses", maxPrice, "Amazon España", "Lanzamiento oficial"),
            PricePoint(now - (240 * dayMillis), "Hace 8 meses", basePrice + 30.0, "El Corte Inglés", null),
            PricePoint(now - (180 * dayMillis), "Hace 6 meses", basePrice + 15.0, "MediaMarkt España", null),
            PricePoint(now - (130 * dayMillis), "Hace 4 meses", eventMin, "PcComponentes", eventDate),
            PricePoint(now - (90 * dayMillis), "Hace 3 meses", basePrice, "Amazon España", null),
            PricePoint(now - (60 * dayMillis), "Hace 2 meses", basePrice - 10.0, "Carrefour España", null),
            PricePoint(now - (30 * dayMillis), "Hace 1 mes", basePrice + 5.0, "PcComponentes", null),
            PricePoint(now - (14 * dayMillis), "Hace 2 sem.", basePrice - 15.0, "MediaMarkt España", null),
            PricePoint(now - (5 * dayMillis), "Hace 5 días", minPrice, "Amazon España", "Mínimo Registrado"),
            PricePoint(now, "Hoy", currentPrice, "Mercado Español", if (currentPrice <= minPrice) "MÍNIMO HISTÓRICO" else null)
        )
    }

    private fun generateInflatedHistory(
        baseline: Double,
        inflated: Double,
        current: Double,
        ath: Double
    ): List<PricePoint> {
        val now = System.currentTimeMillis()
        val dayMillis = 86400000L

        return listOf(
            PricePoint(now - (120 * dayMillis), "Hace 4 meses", baseline, "MediaMarkt España", null),
            PricePoint(now - (90 * dayMillis), "Hace 3 meses", ath, "PcComponentes", "Mínimo real"),
            PricePoint(now - (60 * dayMillis), "Hace 2 meses", baseline + 10.0, "Amazon España", null),
            PricePoint(now - (25 * dayMillis), "Hace 25 días", baseline, "MediaMarkt España", "Precio habitual"),
            PricePoint(now - (14 * dayMillis), "Hace 14 días", inflated, "MediaMarkt España", "¡Subida artificial pre-campaña!"),
            PricePoint(now - (7 * dayMillis), "Hace 7 días", inflated, "MediaMarkt España", "Inflado mantenido"),
            PricePoint(now, "Hoy", current, "MediaMarkt España", "Falsa rebaja anunciada (Ojo)")
        )
    }
}
