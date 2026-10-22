package com.example.data.model

enum class ProductCategory(val displayName: String, val iconName: String) {
    ALL("Todos", "GridView"),
    SMARTPHONES("Móviles & Tablets", "Smartphone"),
    COMPUTING("Informática & PC", "Laptop"),
    GAMING("Gaming & Consolas", "SportsEsports"),
    TV_AUDIO("TV & Sonido", "Tv"),
    HOME_APPLIANCES("Hogar & Cocina", "Kitchen"),
    PERSONAL_CARE("Cuidado & Salud", "Spa"),
    SPORTS("Deporte", "FitnessCenter"),
    OTHER("Otros", "Category")
}
