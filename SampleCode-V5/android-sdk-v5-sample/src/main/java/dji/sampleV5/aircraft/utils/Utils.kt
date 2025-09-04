package dji.sampleV5.aircraft.utils

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dji.sampleV5.aircraft.ControlStickScaleConfiguration
import dji.sampleV5.aircraft.R
import okhttp3.internal.closeQuietly
import java.io.InputStreamReader
import java.lang.reflect.Type


fun Float.format(): String {
    return "%.2f".format(this)
}

fun Double.format(): String {
    return "%.2f".format(this)
}

fun Int.format(numberOfZero: Int = 2): String {
    return "%0${numberOfZero}d".format(this)
}

fun Any.toJson(): String {
    return Inner.gson.toJson(this)
}

fun <T> String.toData(clazz: Class<T>): T {
    return Inner.gson.fromJson<T>(this, clazz)
}

fun <T> String.toData(type: Type): T {
    return Inner.gson.fromJson(this, type)
}

private object Inner{

    val gson = GsonBuilder().create()

}

fun getControlStickScaleConfiguration(context: Context): List<ControlStickScaleConfiguration> {
    val rawInputStream = context.resources.openRawResource(R.raw.stick_control_scale_factors)
    val inputReader = InputStreamReader(rawInputStream)
    val configurationList = Inner.gson.fromJson<List<ControlStickScaleConfiguration>>(inputReader, object :
        TypeToken<List<ControlStickScaleConfiguration>>() {
    }.type)
    inputReader.closeQuietly()
    rawInputStream.closeQuietly()
    return configurationList
}