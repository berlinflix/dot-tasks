package dev.suyash.dot.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "dev.suyash.dot"

private const val WAIT_MS = 5_000L
private const val SHORT_WAIT_MS = 1_000L

/** Shown once the task list is up (the Settings button sits in its header). */
private val TASKS_SCREEN: BySelector = By.desc("Settings")

/** Fresh installs have no tasks. None of these has a date, so nothing rings on the test device. */
private val SAMPLE_TASKS = listOf(
    "Buy milk",
    "Plan the week",
    "Call the bank",
    "Water the plants",
    "Book a dentist appointment",
    "Renew passport",
    "Pay the electricity bill",
    "Read 20 pages",
)

/** Cold start until the tasks are on screen. */
internal fun MacrobenchmarkScope.startAndWaitForTasks() {
    startActivityAndWait()
    device.wait(Until.hasObject(TASKS_SCREEN), WAIT_MS)
}

internal fun MacrobenchmarkScope.addSampleTasks() {
    if (device.hasObject(By.text(SAMPLE_TASKS.first()))) return
    for (title in SAMPLE_TASKS) {
        if (!withObject(By.clazz("android.widget.EditText")) { it.text = title }) return
        withObject(By.desc("Add task")) { it.click() }
        device.waitForIdle()
    }
    backTo(TASKS_SCREEN)
}

/** The everyday path: switch views, scroll, open a task, search, and settings. */
internal fun MacrobenchmarkScope.browseTasks() {
    for (view in listOf("Upcoming", "Starred", "Today")) {
        withObject(By.text(view), SHORT_WAIT_MS) { it.click() }
        device.waitForIdle()
    }
    withObject(By.scrollable(true), SHORT_WAIT_MS) { list ->
        list.setGestureMarginPercentage(0.2f)
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
    if (withObject(By.text(SAMPLE_TASKS.first()), SHORT_WAIT_MS) { it.click() }) { // opens the editor sheet
        device.waitForIdle()
        backTo(TASKS_SCREEN)
    }
    if (withObject(By.desc("Search"), SHORT_WAIT_MS) { it.click() }) {
        withObject(By.focused(true)) { it.text = "milk" }
        device.waitForIdle()
        backTo(TASKS_SCREEN)
    }
    if (withObject(TASKS_SCREEN, SHORT_WAIT_MS) { it.click() }) {
        device.wait(Until.hasObject(By.desc("Back")), WAIT_MS)
        withObject(By.scrollable(true), SHORT_WAIT_MS) { it.fling(Direction.DOWN) }
        backTo(TASKS_SCREEN)
    }
}

/** The capture sheet that the widgets, the shortcuts and the in-app mic open. */
internal fun MacrobenchmarkScope.openCaptureSheet() {
    device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.RECORD_AUDIO")
    if (!withObject(By.desc("Add task by voice"), SHORT_WAIT_MS) { it.click() }) return
    device.wait(Until.gone(TASKS_SCREEN), WAIT_MS)
    device.waitForIdle()
    backTo(TASKS_SCREEN)
}

/**
 * Runs [action] on the element matching [selector]. Compose can replace an element between finding and
 * using it (e.g. the add button after typing), so a stale one is looked up again.
 */
private fun MacrobenchmarkScope.withObject(selector: BySelector, timeoutMs: Long = WAIT_MS, action: (UiObject2) -> Unit): Boolean {
    repeat(3) {
        val element = device.wait(Until.findObject(selector), timeoutMs) ?: return false
        try {
            action(element)
            return true
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
    return false
}

/** Back out of sheets, the keyboard and other screens until [screen] shows again. */
private fun MacrobenchmarkScope.backTo(screen: BySelector) {
    repeat(4) {
        if (device.wait(Until.hasObject(screen), 500)) return
        device.pressBack()
    }
}
