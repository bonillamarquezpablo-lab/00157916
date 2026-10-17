package com.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic startup smoke test. This intentionally launches the actual launcher Activity so
 * runtime crashes in Activity/ViewModel/Room/Compose initialization fail the CI build.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun mainActivityLaunchesWithoutFinishingImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity closed during startup", activity.isFinishing)
            }
        }
    }
}
