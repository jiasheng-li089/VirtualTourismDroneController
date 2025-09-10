package dji.sampleV5.aircraft.virtualcontroller

import android.location.Location
import android.os.SystemClock
import android.util.ArrayMap
import android.util.Log
import dji.sampleV5.aircraft.DJIApplication.Companion.idToString
import dji.sampleV5.aircraft.MONITOR_VELOCITY_ACTIVELY
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.util.Util
import dji.sampleV5.aircraft.utils.DroneStatusCallback
import dji.sampleV5.aircraft.utils.DroneStatusHelper
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.format
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.RemoteControllerFlightMode
import dji.sdk.keyvalue.value.flightcontroller.WindDirection
import dji.sdk.keyvalue.value.flightcontroller.WindWarning
import dji.v5.et.get
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionInfo
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.math.abs

typealias OnRawDataObserver = (DJIKeyInfo<*>, Any?) -> Unit

interface RawDataObservable {

    fun register(key: DJIKeyInfo<*>, observer: OnRawDataObserver): OnRawDataObserver

    fun unregister(key: DJIKeyInfo<*>, observer: OnRawDataObserver)
}

private const val DEFAULT_OBSTACLE_DISTANCE = 6000

class DroneStatusMonitor(
    private val scope: CoroutineScope,
    private val messageNotifier: (Int, String, Throwable?) -> Unit,
    private val statusNotifier: (Map<String, String>) -> Unit,
) : DroneStatusCallback, RawDataObservable, VirtualStickStateListener,
    PerceptionInformationListener, ObstacleDataListener {

    private var rawDataObserver = HashMap<String, HashSet<OnRawDataObserver>>()

    private var droneStatusHandle: ArrayMap<DJIKeyInfo<*>, Pair<String, (Any?) -> String?>> =
        ArrayMap()

    private var statusHelper: DroneStatusHelper? = null

    private val obstacleCaches = ArrayMap<Int, Pair<Int, Long>>()

    var droneInitialLocation: LocationCoordinate3D? = null

    private var velocityScheduler = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var readVelocityJob: Job? = null


    fun startMonitoring() {
        if (null == statusHelper) {
            initializeDroneStatueHandle()
        }
        statusHelper?.startListen()

        VirtualStickManager.getInstance().setVirtualStickStateListener(this)
        PerceptionManager.getInstance().addPerceptionInformationListener(this)
        PerceptionManager.getInstance().addObstacleDataListener(this)

        if (MONITOR_VELOCITY_ACTIVELY) {
            readVelocityJob?.cancel()

            readVelocityJob = scope.launch(velocityScheduler) {
                val velocityKey = KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)
                while (null != readVelocityJob && true != readVelocityJob?.isCancelled) {
                    delay(10)

                    velocityKey.get()?.let {
                        Timber.log(
                            LogLevel.VERBOSE_DRONE_VELOCITY_READ_ACTIVELY,
                            "Velocity (N/E/D): ${it.x} / ${it.y} / ${it.z}"
                        )

                        onChange(velocityKey as DJIKeyInfo<*>, it)
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        if (MONITOR_VELOCITY_ACTIVELY) {
            readVelocityJob?.cancel()
            readVelocityJob = null
        }

        statusHelper?.stopListen()

        VirtualStickManager.getInstance().removeVirtualStickStateListener(this)
        PerceptionManager.getInstance().removePerceptionInformationListener(this)
        PerceptionManager.getInstance().removeObstacleDataListener(this)
    }

    override fun onChange(key: DJIKeyInfo<*>, value: Any?) {
        droneStatusHandle.getOrDefault(key, null)?.let {
            val newValue = it.second.invoke(value)
            if (null == newValue) return@let

            notifyRawData(key, value)

            notifyUpdate(mapOf(it.first to newValue))
        }
        Timber.d("$key --> ${value?.toString() ?: "null"}")
    }

    override fun register(
        key: DJIKeyInfo<*>,
        observer: (DJIKeyInfo<*>, Any?) -> Unit,
    ): (DJIKeyInfo<*>, Any?) -> Unit {
        if (Util.isMainThread()) {
            (rawDataObserver.get(key.innerIdentifier) ?: let {
                val set = HashSet<OnRawDataObserver>()
                rawDataObserver[key.innerIdentifier] = set
                set
            }).add(observer)
        } else {
            scope.launch(Dispatchers.Main) {
                register(key, observer)
            }
        }
        return observer
    }

    override fun unregister(key: DJIKeyInfo<*>, observer: OnRawDataObserver) {
        if (Util.isMainThread()) {
            (rawDataObserver.get(key.innerIdentifier) ?: let {
                val set = HashSet<OnRawDataObserver>()
                rawDataObserver[key.innerIdentifier] = set
                set
            }).remove(observer)
        } else {
            scope.launch(Dispatchers.Main) {
                unregister(key, observer)
            }
        }
    }

    private fun notifyRawData(key: DJIKeyInfo<*>, value: Any?) {
        scope.launch(Dispatchers.Main) {
            val set = rawDataObserver.get(key.innerIdentifier) ?: setOf()
            for (tmp in set) {
                tmp.invoke(key, value)
            }
        }
    }

    private fun initializeDroneStatueHandle() {
        val yesOrNoCallback: (Any?)-> String? = {
            (it as? Boolean)?.let { on ->
                if (on) "Yes" else "No"
            } ?: "No"
        }

        val distanceResult = FloatArray(1)

        // drone is connected
        droneStatusHandle[ProductKey.KeyConnection] =
            R.string.hint_drone_connected.idToString() to yesOrNoCallback

        // drone flight control is connected
        droneStatusHandle[FlightControllerKey.KeyConnection] =
            R.string.hint_flight_control_connected.idToString() to yesOrNoCallback

        // monitor drone location and distance to initial location
        droneStatusHandle[FlightControllerKey.KeyAircraftLocation3D] =
            R.string.hint_drone_current_position.idToString() to {
                val location: LocationCoordinate3D? = it as? LocationCoordinate3D
                location?.let { l ->
                    val locationString =
                        "${l.latitude} / ${l.longitude} / ${"%.2f".format(l.altitude)}"

                    // calculate the distance from current location to initial location
                    droneInitialLocation?.let { iL ->
                        Location.distanceBetween(
                            l.latitude,
                            l.longitude,
                            iL.latitude,
                            iL.longitude,
                            distanceResult
                        )
                        val verticalDistance = l.altitude - iL.altitude
                        val result =
                            R.string.hint_drone_distance_to_ip.idToString() to "%.2f / %.2f".format(
                                distanceResult[0],
                                verticalDistance
                            )
                        notifyUpdate(mapOf(result))
                        Timber.i("${result.first} --> ${result.second}}")
                    }

                    locationString
                }
            }

        // monitor drone attitude
        droneStatusHandle[FlightControllerKey.KeyAircraftAttitude] =
            R.string.hint_drone_attitude.idToString() to {
                (it as? Attitude)?.let { attitude ->
                    "%.2f / %.2f / %.2f".format(attitude.yaw, attitude.roll, attitude.pitch)
                } ?: "N/A"
            }

        if (!MONITOR_VELOCITY_ACTIVELY) {
            droneStatusHandle[FlightControllerKey.KeyAircraftVelocity] =
                R.string.hint_drone_velocity.idToString() to {
                    (it as? Velocity3D)?.let { velocity3D ->
                        "${velocity3D.x} / ${velocity3D.y} / ${velocity3D.z}"
//                        "%.2f / %.2f / %.2f".format(velocity3D.x, velocity3D.y, velocity3D.z)
                    } ?: "0 / 0 /0"
                }
        }

        // monitor motors status
        droneStatusHandle[FlightControllerKey.KeyAreMotorsOn] = R.string.hint_motors_are_on.idToString() to yesOrNoCallback

        // if drone is flying
        droneStatusHandle[FlightControllerKey.KeyIsFlying] = R.string.hint_is_flying.idToString() to yesOrNoCallback

        // monitor battery temperature
        droneStatusHandle[BatteryKey.KeyBatteryTemperature] =
            R.string.hint_battery_temperature.idToString() to {
                (it as? Float)?.format() ?: "N/A"
            }
        // battery remaining capacity
        droneStatusHandle[BatteryKey.KeyChargeRemainingInPercent] =
            R.string.hint_battery_capacity.idToString() to {
                (it as? Int)?.let { percentage ->
                    "${percentage}%"
                } ?: "N/A"
            }

        // monitor wind warning
        droneStatusHandle[FlightControllerKey.KeyWindWarning] =
            R.string.hint_wind_warning.idToString() to {
                (it as? WindWarning)?.name ?: "N/A"
            }
        // wind speed
        droneStatusHandle[FlightControllerKey.KeyWindSpeed] =
            R.string.hint_wind_speed.idToString() to {
                it?.toString() ?: "N/A"
            }
        // wind direction
        droneStatusHandle[FlightControllerKey.KeyWindDirection] =
            R.string.hint_wind_direction.idToString() to {
                (it as? WindDirection)?.name ?: "N/A"
            }
        // ultrasonic height
        droneStatusHandle[FlightControllerKey.KeyUltrasonicHeight] =
            R.string.hint_ultrasonic_height.idToString() to {
                it?.toString() ?: "N/A"
            }
        // air connection quality
        droneStatusHandle[AirLinkKey.KeySignalQuality] =
            R.string.hint_connection_signal.idToString() to {
                when (it) {
                    in 60..200 -> "Good"
                    in 40..59 -> "Normal"
                    in 0..39 -> "Bad"
                    else -> "N/A"
                }
            }
        droneStatusHandle[AirLinkKey.KeyDynamicDataRate] =
            R.string.hint_connection_capability.idToString() to {
                (it as? Float)?.format() ?: "N/A"
            }

        // flight model
        droneStatusHandle[FlightControllerKey.KeyFlightMode] =
            R.string.hint_flight_mode.idToString() to {
                (it as? FlightMode)?.name ?: "N/A"
            }
        droneStatusHandle[FlightControllerKey.KeyRemoteControllerFlightMode] =
            R.string.hint_remote_controller_flight_mode.idToString() to {
                (it as? RemoteControllerFlightMode)?.name ?: "N/A"
            }

        // INFO taking off altitude, unreliable status, should not be used for any use cases
        // returns a negative value, which is considered as an invalid value while placing the drone on the ground.
//        droneStatusHandle[FlightControllerKey.KeyTakeoffLocationAltitude] =
//            R.string.hint_taking_off_altitude.idToString() to {
//                if (null != it) "%.2f".format(it) else "N/A"
//            }
        statusHelper = DroneStatusHelper(droneStatusHandle.keys.toList(), this)
    }

    override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
        val result =
            "${if (stickState.isVirtualStickEnable) "Y" else "N"} / ${if (stickState.isVirtualStickAdvancedModeEnabled) "Y" else "N"}"
        notifyUpdate(mapOf(R.string.hint_virtual_stick_state.idToString() to result))
    }

    override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
    }

    private fun notifyUpdate(map: Map<String, String>) {
        statusNotifier.invoke(map)
        for (keyValue in map.entries) {
            Timber.i("${keyValue.key}: ${keyValue.value}")
        }
    }

    override fun onUpdate(information: PerceptionInfo) {
        notifyUpdate(mapOf(R.string.hint_obstacle_avoidance_type.idToString() to information.obstacleAvoidanceType.name))
    }

    override fun onUpdate(obstacleData: ObstacleData?) {
        obstacleData?.let { obstacle ->
            if (DEFAULT_OBSTACLE_DISTANCE == obstacle.downwardObstacleDistance
                && DEFAULT_OBSTACLE_DISTANCE == obstacle.upwardObstacleDistance
            ) {
                if (obstacle.horizontalObstacleDistance.isNullOrEmpty()
                    || obstacle.horizontalObstacleDistance[obstacle.horizontalAngleInterval] == DEFAULT_OBSTACLE_DISTANCE
                ) {
                    return
                }
            }

            // temporarily disable logging if the horizontal distance array is empty
            if (obstacle.horizontalObstacleDistance.isNullOrEmpty()) return

            val newDistance =
                obstacle.horizontalObstacleDistance[obstacle.horizontalAngleInterval]
            (obstacleCaches[obstacle.horizontalAngleInterval] ?: let {
                val cache: Pair<Int, Long> = Int.MAX_VALUE to SystemClock.elapsedRealtime()
                obstacleCaches[obstacle.horizontalAngleInterval] = cache
                cache
            }).let { newPair ->
                if (abs(newDistance - newPair.first) < abs(newPair.first / 10f)
                    && SystemClock.elapsedRealtime() - newPair.second < 1000L
                ) {
                    // the frequency of calling back is too high, which flushes the logs on screen, therefore,
                    // only log on the screen if the distance changes significantly or after a period of time.
                    return
                } else {
                    obstacleCaches[obstacle.horizontalAngleInterval] =
                        newDistance to SystemClock.elapsedRealtime()
                }
            }

            val msg =
                "Detected obstacle:  down->${(obstacle.downwardObstacleDistance / 1000f).format()}" +
                        "\tup->${(obstacle.upwardObstacleDistance / 1000f).format()}" +
                        "\tangle interval->${obstacle.horizontalAngleInterval}" +
                        "\thorizontal distance->${(newDistance / 1000f).format()}"
            messageNotifier.invoke(Log.WARN, msg, null)
        } ?: {
            Timber.d("Received obstacle update, but got invalid data")
        }
    }
}