package com.example.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight source guard for the Compose layout pattern that can trigger
 * NoIntrinsicsMeasurePolicy when LazyLayout is placed inside an intrinsically measured popup.
 */
class ComposeIntrinsicRegressionTest {
    @Test
    fun storeDropdownUsesBoundedNonLazyContent() {
        val source = java.io.File("src/main/java/com/example/ui/screens/MarketScreen.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: java.io.File("app/src/main/java/com/example/ui/screens/MarketScreen.kt").readText()

        assertTrue(
            "Store dropdown should use bounded Column content",
            source.contains("Column(modifier = Modifier.heightIn(max = 420.dp))")
        )
        assertTrue(
            "Store dropdown must not nest a LazyColumn with intrinsic sizing",
            !source.contains("LazyColumn(modifier = Modifier.heightIn(max = 420.dp))")
        )
    }
}
