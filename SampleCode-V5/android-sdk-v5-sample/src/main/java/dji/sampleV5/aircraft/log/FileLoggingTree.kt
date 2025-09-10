package dji.sampleV5.aircraft.log

import android.util.Log
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class FileLoggingTree (logFile: File, protected val targetLevel: Int = Log.INFO): Timber.DebugTree() {

    private val writer = BufferedWriter(FileWriter(logFile, true))

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val priorities = mapOf(
        Log.VERBOSE to "V",
        Log.DEBUG to "D",
        Log.INFO to "I",
        Log.WARN to "W",
        Log.ERROR to "E",
        Log.ASSERT to "A",
    )

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        super.log(priority, tag, message, t)
        if (priority >= targetLevel) {
            writer.write("${dateFormat.format(Date())}\t${priorities.getOrDefault(priority, "U")}\t$tag\t\t$message\n")
            if (null != t) {
                writer.write(Log.getStackTraceString(t))
                writer.write("\n")
            }
            writer.flush()
        }
    }

    fun destroy() {
        writer.flush()
        writer.closeQuietly()
    }
}

class ExactLevelLoggingTree(logFile: File, targetLevel: Int) : FileLoggingTree(logFile, targetLevel) {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?
    ) {
        if (priority == targetLevel) {
            super.log(priority, tag, message, t)
        }
    }
}