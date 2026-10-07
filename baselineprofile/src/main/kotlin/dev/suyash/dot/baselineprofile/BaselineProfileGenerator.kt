package dev.suyash.dot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code runs at startup and in everyday use, so it is compiled ahead of time on install
 * instead of being interpreted on first use. Run with `./gradlew :app:generateBaselineProfile`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Startup only: also becomes the startup profile R8 uses to lay out the dex. */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startAndWaitForTasks()
    }

    @Test
    fun everydayUse() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startAndWaitForTasks()
        addSampleTasks()
        browseTasks()
        openCaptureSheet()
    }
}
