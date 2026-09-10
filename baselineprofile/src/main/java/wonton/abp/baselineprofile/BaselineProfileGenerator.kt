package wonton.abp.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Critical user journeys used to generate the app's Baseline Profile and
 * Startup Profile.
 *
 * `startup()` is marked with `includeInStartupProfile = true`, so its rules also
 * feed the Startup Profile (which drives DEX layout optimization).
 * `appListAndStatus()` covers the non-startup navigation and scrolling.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait(mainActivityIntent())
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    @Test
    fun appListAndStatus() = rule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait(mainActivityIntent())
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), TIMEOUT_MS)
        device.waitForIdle()

        // App list tab: the package scan + the list itself.
        clickTab("Apps", "应用")
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.apply {
            fling(Direction.DOWN)
            fling(Direction.UP)
        }
        device.waitForIdle()

        // Status page (framework rows + the GMS notice path).
        clickTab("Status", "状态")
        device.waitForIdle()
    }

    /**
     * MainActivity deliberately has no LAUNCHER category (the module hides its
     * launcher icon), so `startActivityAndWait()` cannot resolve a launcher
     * intent for the package - the component has to be named explicitly.
     */
    private fun mainActivityIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.ui.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Clicks the first bottom-navigation tab whose label matches, trying every
     * supplied label so the generator also works on a non-English emulator.
     */
    private fun MacrobenchmarkScope.clickTab(vararg labels: String) {
        for (label in labels) {
            val node = device.findObject(By.text(label))
            if (node != null) {
                node.click()
                return
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "wonton.abp"
        const val TIMEOUT_MS = 5_000L
    }
}
