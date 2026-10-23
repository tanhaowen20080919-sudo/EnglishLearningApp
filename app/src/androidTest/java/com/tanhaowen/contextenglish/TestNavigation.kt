package com.tanhaowen.contextenglish

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Real Android Back dispatch; call on a non-editor screen, where Back is navigation rather than IME dismissal. */
internal fun pressTestBack() {
    val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}
