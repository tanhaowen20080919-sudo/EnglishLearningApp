package com.tanhaowen.contextenglish

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Test-only shell export: AGP uninstalls the test app before the workflow can adb-pull its private directory. */
internal fun publishUiEvidence() {
    val command="mkdir -p /sdcard/Download/englishlearning-ui-evidence && cp /sdcard/Android/data/com.tanhaowen.contextenglish/files/screenshots/* /sdcard/Download/englishlearning-ui-evidence/"
    val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}
