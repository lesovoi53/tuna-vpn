package com.wireturn.app.kernel

/** Signal first: Android Process.destroy may close streams held by a blocking reader. */
internal fun signalAndroidProcess(process: Process, signal: Int) {
    if (!process.isAlive) return
    val field = process.javaClass.getDeclaredField("pid")
    field.isAccessible = true
    val pid = field.getInt(process)
    require(pid > 0)
    android.os.Process.sendSignal(pid, signal)
}
