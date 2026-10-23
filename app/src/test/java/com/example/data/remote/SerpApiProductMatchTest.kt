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
        assertTrue(
            returnedTitleMatchesSpecificDysonHairToolModel(
                "plancha Dyson",
                "Dyson Airwrap i.d. Curly"
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
}
