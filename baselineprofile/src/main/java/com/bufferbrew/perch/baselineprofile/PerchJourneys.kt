package com.bufferbrew.perch.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE = "com.bufferbrew.perch"
private const val WAIT_MS = 5_000L

/** Cold start to the notes list. */
internal fun MacrobenchmarkScope.startToNotes() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.text("New note")), WAIT_MS)
}

/** The main note flow: new note, type a line, add a task, back to the list, scroll it. */
internal fun MacrobenchmarkScope.writeANote() {
    device.findObject(By.text("New note"))?.click() ?: return
    device.waitForIdle()
    device.executeShellCommand("input text Groceries")
    device.waitForIdle()
    device.pressBack() // keyboard
    device.pressBack() // back to the list (predictive back on 13+)
    device.wait(Until.hasObject(By.text("New note")), WAIT_MS)
    device.findObject(By.scrollable(true))?.let { list ->
        list.fling(androidx.test.uiautomator.Direction.DOWN)
        list.fling(androidx.test.uiautomator.Direction.UP)
    }
}

/** Opens the floating note over the launcher and minimises it to the bubble with Back. */
internal fun MacrobenchmarkScope.floatTheNote() {
    device.executeShellCommand("appops set $PACKAGE SYSTEM_ALERT_WINDOW allow")
    pressHome()
    device.executeShellCommand("am start -n $PACKAGE/.FloatLaunchActivity")
    device.wait(Until.hasObject(By.pkg(PACKAGE)), WAIT_MS)
    device.waitForIdle()
    device.pressBack() // minimise to the bubble
    device.waitForIdle()
}
