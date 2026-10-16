package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaybackSamsungTitleCompatibilityTest {
    @Test
    fun acceptsRegionalSamsungTvModelPrefixDifference() {
        val client = WaybackPriceHistoryClient(ApplicationProvider.getApplicationContext<Context>())
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Samsung QE48S95HAEXXC OLED 48 inch Smart TV",
             "offers":{"@type":"Offer","price":"1394.10","priceCurrency":"EUR"}}
            </script>
        """.trimIndent()
        val quote = client.parseArchivedPrice(
            html = html,
            expectedTitle = "OLED Samsung TQ48S95HAEXXC 48 4K Smart TV",
            originalUrl = "https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            snapshotUrl = "https://web.archive.org/web/20250101120000id_/https://www.samsung.com/es/tvs/oled-tv/tq48s95haexxc/",
            observedAt = 1_735_732_800_000L
        )
        assertNotNull("TQ/QE prefix and omitted 4K label should not reject the same core TV model", quote)
        assertEquals(1394.10, quote!!.price, 0.001)
    }
}