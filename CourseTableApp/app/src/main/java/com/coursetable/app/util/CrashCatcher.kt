package com.coursetable.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃自捕：把未捕获异常写入文件（外部私有目录），便于无 logcat 环境下把堆栈带回来排查。
 * 同时在系统默认处理前记录，不影响正常崩溃流程。
 */
object CrashCatcher {

    private const val FILE = "crash_log.txt"

    fun install(appContext: Context) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                val f = File(dir, FILE)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                f.appendText(
                    "==== ${System.currentTimeMillis()}  ${thread.name} ====\n" +
                        throwable.javaClass.name + ": " + throwable.message + "\n" + sw.toString() + "\n"
                )
            } catch (ignored: Throwable) {
            }
            // 转交系统默认处理器（应用按系统行为结束）
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun readAndClear(appContext: Context): String? {
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val f = File(dir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        runCatching { f.delete() }
        return text
    }
}
