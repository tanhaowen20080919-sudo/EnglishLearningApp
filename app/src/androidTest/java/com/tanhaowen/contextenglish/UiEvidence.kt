package com.tanhaowen.contextenglish

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test-only export: AGP uninstalls the app before the workflow can pull its private directory. */
internal fun publishUiEvidence() {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    fun shell(command: String): String {
        val descriptor=instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    val destination="/sdcard/Download/englishlearning-ui-evidence"
    // UiAutomation executes a command directly: shell operators and wildcards are not expanded.
    shell("mkdir -p $destination")
    val files=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots").listFiles().orEmpty()
    check(files.isNotEmpty()) { "Android UI evidence was not written" }
    files.filter { it.isFile }.forEach { file ->
        require(file.absolutePath.matches(Regex("[A-Za-z0-9/_.-]+")))
        val target="$destination/${file.name}"
        shell("cp ${file.absolutePath} $target")
        check(shell("ls $target").trim()==target) { "Cannot export UI evidence: ${file.name}" }
    }
}
