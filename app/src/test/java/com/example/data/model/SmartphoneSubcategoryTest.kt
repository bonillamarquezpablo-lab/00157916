package com.example.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartphoneSubcategoryTest {
    @Test
    fun separatesPhonesFromTabletsAndPhoneAccessories() {
        assertTrue(SmartphoneSubcategory.PHONES.matchesTitle("Apple iPhone 15 Plus 256GB"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Apple iPad Air 11 pulgadas"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Anker cargador iPhone USB C 20W"))
    }

    @Test
    fun recognizesXiaomiMiPhonesAndOtherMajorPhoneFamiliesWithoutLeakingAccessories() {
        listOf(
            "Xiaomi Mi 10 Pro Gris",
            "Xiaomi 14T Pro 256GB Negro",
            "Xiaomi Redmi Note 13 Pro",
            "POCO X6 Pro 5G",
            "Google Pixel Fold",
            "Samsung Galaxy Z Fold 6",
            "Huawei Pura 70 Ultra",
            "Honor Magic6 Pro",
            "OnePlus 12R",
            "Motorola Moto G84",
            "OPPO Reno 11 Pro",
            "vivo V30",
            "realme GT 6",
            "Sony Xperia 1 VI",
            "ASUS Zenfone 10",
            "Nothing Phone 2a"
        ).forEach { title ->
            assertTrue("Expected a mobile phone to appear in Móviles: $title", SmartphoneSubcategory.PHONES.matchesTitle(title))
        }

        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Xiaomi Pad 6"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Xiaomi Mi Band 8"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Funda para Xiaomi Mi 10 Pro"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Cargador Xiaomi 67W"))
    }

    @Test
    fun recognizesTabletsAndPowerBanksSeparately() {
        assertTrue(SmartphoneSubcategory.TABLETS.matchesTitle("Samsung Galaxy Tab S10"))
        assertTrue(SmartphoneSubcategory.POWER_BANKS.matchesTitle("Anker Nano Power Bank 45W iPhone"))
        assertFalse(SmartphoneSubcategory.CHARGERS.matchesTitle("Anker Nano Power Bank 45W iPhone"))
    }

    @Test
    fun separatesChargersFromCables() {
        assertTrue(SmartphoneSubcategory.CHARGERS.matchesTitle("Anker cargador USB C 20W para iPhone"))
        assertFalse(SmartphoneSubcategory.CABLES.matchesTitle("Anker cargador USB C 20W para iPhone"))
        assertTrue(SmartphoneSubcategory.CABLES.matchesTitle("Anker 322 Cable USB 1,8 m USB A USB C Blanco"))
    }

    @Test
    fun fundasAndProtectorsDoNotLeakIntoPhones() {
        assertTrue(SmartphoneSubcategory.CASES_PROTECTORS.matchesTitle("Funda transparente para iPhone 15"))
        assertFalse(SmartphoneSubcategory.PHONES.matchesTitle("Funda transparente para iPhone 15"))
    }

    @Test
    fun subcategoryQueriesUseOnlyRelevantExistingCategories() {
        assertTrue(SmartphoneSubcategory.CABLES.sourceCategories.contains(ProductCategory.ACCESSORIES))
        assertTrue(SmartphoneSubcategory.CABLES.sourceCategories.contains(ProductCategory.COMPUTING))
        assertFalse(SmartphoneSubcategory.CABLES.sourceCategories.contains(ProductCategory.ALL))
    }
}
