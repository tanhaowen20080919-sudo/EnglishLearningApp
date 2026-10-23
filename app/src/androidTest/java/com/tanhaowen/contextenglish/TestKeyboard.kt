package com.tanhaowen.contextenglish

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/** Native IME control avoids Espresso selecting an obsolete DecorView during Android 15 IME transitions. */
internal fun hideTestKeyboard(activity: Activity) {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        // Stop the editor session before hiding: a pending SHOW can otherwise consume the next Back.
        val content=activity.findViewById<ViewGroup>(android.R.id.content)
        val descendants=content.descendantFocusability
        content.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.isFocusableInTouchMode=true
        content.requestFocus()
        content.descendantFocusability=descendants
        activity.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
    }
    val deadline=SystemClock.uptimeMillis()+5000
    do {
        var visible=false
        instrumentation.runOnMainSync {
            visible=ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true ||
                activity.getSystemService(InputMethodManager::class.java).isAcceptingText ||
                !activity.window.decorView.hasWindowFocus()
        }
        if(!visible) return
        SystemClock.sleep(16)
    } while(SystemClock.uptimeMillis()<deadline)
    error("Keyboard did not close before the next UI action")
}
internal fun hideTestKeyboard(scenario: ActivityScenario<MainActivity>) {
    lateinit var current: MainActivity
    scenario.onActivity { current=it }
    hideTestKeyboard(current)
}
/** Exercise Android's actual Back dispatch, without Espresso's window-root picker. */
internal fun pressTestBack() {
    val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}
