package dji.sampleV5.aircraft

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import dji.sampleV5.aircraft.log.ExactLevelLoggingTree
import dji.sampleV5.aircraft.log.FileLoggingTree
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.getControlStickScaleConfiguration
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()

    private var tree: FileLoggingTree? = null

    private var velocityLogTree: FileLoggingTree? = null

    override fun onCreate() {
        super.onCreate()
        context = this

        // Ensure initialization is called first
        msdkManagerVM.initMobileSDK(this)

        val file = File(this.getExternalFilesDir(null), "LOG")
        if (!file.exists()) {
            file.mkdirs()
        }
        val logSuffix = "${SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(
            Date()
        )}.log"
        logFile = File(file, logSuffix)
        velocityLogFile = File(file, "velocity_changes_$logSuffix")

        tree = FileLoggingTree(logFile, targetLevel = MINIMUM_LOG_LEVEL)
        velocityLogTree = ExactLevelLoggingTree(velocityLogFile, LogLevel.VERBOSE_DRONE_VELOCITY_READ_ACTIVELY)

        Timber.plant(tree!!)

        Thread.setDefaultUncaughtExceptionHandler { thread, e->
            Timber.e(e)
            tree?.destroy()
            velocityLogTree?.destroy()
        }

        currentControlScaleConfiguration = getControlStickScaleConfiguration(this)[0]
    }

    override fun onTerminate() {
        super.onTerminate()
        tree?.destroy()
        velocityLogTree?.destroy()
    }

    companion object {

        private lateinit var logFile: File

        private lateinit var velocityLogFile: File

        @SuppressLint("StaticFieldLeak")
        private lateinit var context: Context

        fun getLogFile(): File {
            return logFile
        }

        fun Int.idToString(): String {
            return context.getString(this)
        }
    }
}
