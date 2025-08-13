package dji.sampleV5.aircraft.utils

import com.google.gson.GsonBuilder


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

private object Inner{

    val gson = GsonBuilder().create()

}