package com.example.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpApiProductMatchTest {
    @Test
    fun specificDysonModelIntentIsNotTreatedAsABroadFamilyQuery() {
        assertTrue(queryNamesSpecificDysonHairToolModel("plancha Dyson Corrale"))
        assertTrue(queryNamesSpecificDysonHairToolModel("secador Dyson Supersonic HD07"))
        assertTrue(queryNamesSpecificDysonHairToolModel("Dyson Airwrap i.d. Curly"))
        assertFalse(queryNamesSpecificDysonHairToolModel("plancha Dyson"))
        assertFalse(queryNamesSpecificDysonHairToolModel("herramientas de pelo Dyson"))
        assertFalse(
            returnedTitleMatchesSpecificDysonHairToolModel(
                "plancha Dyson Corrale",
                "Dyson Airwrap i.d. Curly + Coily Amber Silk"
            )
        )
        assertTrue(
            returnedTitleMatchesSpecificDysonHairToolModel(
                "plancha Dyson Corrale",
                "Dyson Corrale CoolShine"
            )
        )
        assertFalse(
            returnedTitleMatchesSpecificDysonHairToolModel(
                "secador Dyson Supersonic HD07",
                "Dyson Supersonic HD08"
            )
        )
        assertFalse(
            "HD07 must not match an HD070 prefix collision",
            returnedTitleMatchesSpecificDysonHairToolModel(
                "secador Dyson Supersonic HD07",
                "Dyson Supersonic HD070"
            )
        )
        assertTrue(
            returnedTitleMatchesSpecificDysonHairToolModel(
                "plancha Dyson",
                "Dyson Airwrap i.d. Curly"
            )
        )
    }

    @Test
    fun explicitStorageCapacityMustMatchAndVacuumSparesStayOutOfDysonHairSearch() {
        assertTrue(hasMatchingExplicitStorageCapacity("iPhone 15 128 GB", "Apple iPhone 15 128GB Negro"))
        assertFalse(hasMatchingExplicitStorageCapacity("iPhone 15 128 GB", "Apple iPhone 15 256GB Negro"))
        assertFalse(hasMatchingExplicitStorageCapacity("iPhone 15 128 GB", "Apple iPhone 15 Negro"))
        assertTrue(hasMatchingExplicitStorageCapacity("plancha Dyson", "Dyson Corrale"))
        assertFalse(
            isBroadDysonHairToolsQueryMatch(
                "plancha Dyson",
                "Boquilla para aspiradora Dyson V11 Detect"
            )
        )
    }

    @Test
    fun broadDysonHairToolSearchIncludesRelatedHairModelsButNotUnrelatedAppliances() {
        assertTrue(isBroadDysonHairToolsQueryMatch("plancha Dyson", "Dyson Airwrap i.d. Curly + Coily Amber Silk"))
        assertTrue(isBroadDysonHairToolsQueryMatch("plancha Dyson", "Promoción Boquilla para Plancha de Pelo Dyson Supersonic"))
        assertTrue(isBroadDysonHairToolsQueryMatch("secador Dyson", "Juguete Dyson secador de pelo para niños"))
        assertFalse(isBroadDysonHairToolsQueryMatch("plancha Dyson", "Dyson V15 Detect Absolute Aspiradora"))
        assertFalse(isBroadDysonHairToolsQueryMatch("Dyson Corrale", "Dyson Airwrap i.d. Curly + Coily"))
        assertFalse(isBroadDysonHairToolsQueryMatch("iPhone 15", "Dyson Supersonic"))
    }

    @Test
    fun toyTitlesHandleAccentsAndCompleteHairToolsAreNotMisclassifiedAsSpareParts() {
        assertTrue(isToyListingTitle("Secador Dyson de juguete para niños"))
        assertTrue(isToyListingTitle("Figura de acción Dyson de imitación"))
        assertTrue(isToyListingTitle("Juego de imitación de peluquería"))
        assertFalse(isAccessoryListingTitle("Dyson Supersonic secador con boquilla y difusor incluidos"))
        assertFalse(isReplacementPartListingTitle("Dyson Airwrap multistyler con accesorios incluidos"))
        assertFalse(isAccessoryListingTitle("Dyson Corrale con accesorios incluidos"))
        assertTrue(isReplacementPartListingTitle("Boquilla para secador Dyson Supersonic"))
        assertTrue(isAccessoryListingTitle("Dyson Supersonic diffuser attachment"))
    }
}
