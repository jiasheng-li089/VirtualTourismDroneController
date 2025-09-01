package dji.sampleV5.aircraft.utils

import com.google.gson.GsonBuilder
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