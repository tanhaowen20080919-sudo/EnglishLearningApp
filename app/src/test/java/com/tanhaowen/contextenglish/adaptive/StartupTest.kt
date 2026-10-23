package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
class StartupTest {
    @Test fun mainActivityCanLaunchWithNoAiConfiguration() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNotNull(controller.get().window.decorView)
        }
    }
}
