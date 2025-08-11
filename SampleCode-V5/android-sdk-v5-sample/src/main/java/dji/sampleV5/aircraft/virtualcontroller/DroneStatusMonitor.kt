package dji.sampleV5.aircraft.virtualcontroller

import android.content.Context
import android.location.Location
import android.util.ArrayMap
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.util.Util
import dji.sampleV5.aircraft.utils.DroneStatusCallback
import dji.sampleV5.aircraft.utils.DroneStatusHelper
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.WindDirection
import dji.sdk.keyvalue.value.flightcontroller.WindWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

typealias OnRawDataObserver = (DJIKeyInfo<*>, Any?) -> Unit

interface RawDataObservable {

    fun register(observer: OnRawDataObserver): OnRawDataObserver

    fun unregister(observer: OnRawDataObserver)
}


class DroneStatusMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val statusNotifier: (Map<String, String>) -> Unit,
) : DroneStatusCallback, RawDataObservable {

    private var rawDataObserver: HashSet<OnRawDataObserver> = HashSet()

    private var droneStatusHandle: ArrayMap<DJIKeyInfo<*>, Pair<String, (Any?) -> String?>> =
        ArrayMap()

    private var statusHelper: DroneStatusHelper? = null

    private var droneInitialLocation: LocationCoordinate3D? = null
    fun startMonitoring() {
        if (null == statusHelper) {
            initializeDroneStatueHandle()
        }
        statusHelper?.startListen()
    }

    fun stopMonitoring() {
        statusHelper?.stopListen()
    }

    override fun onChange(key: DJIKeyInfo<*>, value: Any?) {
        droneStatusHandle.getOrDefault(key, null)?.let {
            val newValue = it.second.invoke(value)
            if (null == newValue) return@let

            statusNotifier.invoke(mapOf(it.first to newValue))
            Timber.i("${it.first}: $newValue")
        }
        Timber.d("$key --> ${value?.toString() ?: "null"}")
        notifyRawData(key, value)
    }

    override fun register(observer: (DJIKeyInfo<*>, Any?) -> Unit): (DJIKeyInfo<*>, Any?) -> Unit {
        if (Util.isMainThread()) {
            rawDataObserver.add(observer)
        } else {
            scope.launch(Dispatchers.Main) {
                rawDataObserver.add(observer)
            }
        }
        return observer
    }

    override fun unregister(observer: OnRawDataObserver) {
        if (Util.isMainThread()) {
            rawDataObserver.remove(observer)
        } else {
            scope.launch(Dispatchers.Main) {
                rawDataObserver.remove(observer)
            }
        }
    }

    private fun notifyRawData(key: DJIKeyInfo<*>, value: Any?) {
        scope.launch(Dispatchers.Main) {
            for (tmp in rawDataObserver) {
                tmp.invoke(key, value)
            }
        }
    }


    private fun Int.idToString(): String {
        return context.getString(this)
    }

    private fun initializeDroneStatueHandle() {
        val distanceResult = FloatArray(1)

        // drone is connected
        droneStatusHandle[ProductKey.KeyConnection] =
            R.string.hint_drone_connected.idToString() to {
                (it as? Boolean)?.let { connected ->
                    if (connected) "Yes" else "No"
                }
            }

        // drone flight control is connected
        droneStatusHandle[FlightControllerKey.KeyConnection] =
            R.string.hint_flight_control_connected.idToString() to {
                (it as? Boolean)?.let { connected ->
                    if (connected) "Yes" else "No"
                }
            }

        // monitor drone location and distance to initial location
        droneStatusHandle[FlightControllerKey.KeyAircraftLocation3D] =
            R.string.hint_drone_current_position.idToString() to {
                val location: LocationCoordinate3D? = it as? LocationCoordinate3D
                location?.let { l ->
                    val locationString = "${l.latitude} / ${l.longitude} / ${l.altitude}"

                    // calculate the distance from current location to initial location
                    droneInitialLocation?.let { iL ->
                        val horizontalDistance =
                            Location.distanceBetween(
                                l.latitude,
                                l.longitude,
                                iL.latitude,
                                iL.longitude,
                                distanceResult
                            )
                        val verticalDistance = l.altitude - iL.altitude
                        val result =
                            R.string.hint_drone_distance_to_ip.idToString() to "$horizontalDistance / $verticalDistance"
                        statusNotifier.invoke(mapOf(result))
                        Timber.i("${result.first} --> ${result.second}}")
                    }

                    locationString
                }
            }

        // monitor drone attitude
        droneStatusHandle[FlightControllerKey.KeyAircraftAttitude] =
            R.string.hint_drone_attitude.idToString() to {
                (it as? Attitude)?.let { attitude ->
                    "${attitude.yaw} / ${attitude.roll} / ${attitude.pitch}"
                }
            }

        // monitor battery temperature
        droneStatusHandle[BatteryKey.KeyBatteryTemperature] =
            R.string.hint_battery_temperature.idToString() to {
                it?.toString()
            }

        // monitor wind warning
        droneStatusHandle[FlightControllerKey.KeyWindWarning] =
            R.string.hint_wind_warning.idToString() to {
                (it as? WindWarning)?.name
            }
        // wind speed
        droneStatusHandle[FlightControllerKey.KeyWindSpeed] =
            R.string.hint_wind_speed.idToString() to {
                it?.toString()
            }
        // wind direction
        droneStatusHandle[FlightControllerKey.KeyWindDirection] =
            R.string.hint_wind_direction.idToString() to {
                (it as? WindDirection)?.name
            }
        // ultrasonic height
        droneStatusHandle[FlightControllerKey.KeyUltrasonicHeight] =
            R.string.hint_ultrasonic_height.idToString() to {
                it?.toString()
            }

        statusHelper = DroneStatusHelper(droneStatusHandle.keys.toList(), this)
    }
}