package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class VirtualController(
    private val scope: CoroutineScope,
    private val observable: RawDataObservable,
    private val messageNotifier: (Int, String, Throwable?) -> Unit,
) {

    var isDroneReady: Boolean = false
        private set

    private var prepareJob: Job? = null

    private var expectedTakeOffHeight = 1.2f

    private var locationRetriever: OnRawDataObserver? = null

    @Volatile
    var initialLocation: LocationCoordinate3D? = null
        private set

    private var droneParam: VirtualStickFlightControlParam

    private var sendingCmdJob: Job? = null

    init {
        setObstacleAvoidanceWarningDistance(0.1)
        setObstacleAvoidance(false) {
            VirtualStickManager.getInstance()
                .enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier.invoke(Log.DEBUG, "Enable virtual stick successfully", null)
                        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier.invoke(
                            Log.ERROR,
                            "Failed to enable the virtual stick(${p0.errorCode()}): ${p0.description()}",
                            null
                        )
                    }

                })
        }

        KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(2, {
            messageNotifier.invoke(Log.DEBUG, "Set the maximum height of drone successfully", null)
        }, {
            messageNotifier.invoke(
                Log.ERROR,
                "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}",
                null
            )
        })

        droneParam = VirtualStickFlightControlParam()
        droneParam.yaw = 0.0
        droneParam.roll = 0.0
        droneParam.pitch = 0.0
        droneParam.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        droneParam.verticalControlMode = VerticalControlMode.VELOCITY
        droneParam.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        droneParam.rollPitchControlMode = RollPitchControlMode.ANGLE
    }

    fun prepareDrone(isFlying: Boolean? = null) {
        // TODO be careful, the logic of this method heavily depends on getting location of the drone, this should be verified if it works indoor
        if (!isDroneReady && null == prepareJob) {
            // for now, there is no obvious status regarding this
            // the only way to do is to check if the drone reaches the height obtained from `KeyAircraftAttitude`
            // however, the height recognition is not that accurate.

            val locationKey = FlightControllerKey.KeyAircraftLocation3D
            when (isFlying) {
                true -> {
                    Timber.d("already flying, try to retrieve current location as the initial position")
                    initialLocation = null

                    // already flying, regard current location as the initial location
                    prepareJob = scope.launch(Dispatchers.IO) {
                        // within indoor environment, the sdk won't return the location of the drone because of no GPS signal, then how to detect the height???
                        locationRetriever = observable.register(locationKey) { key, value ->
                            if (locationKey.innerIdentifier == key.innerIdentifier && null != (value as? LocationCoordinate3D)) {
                                locationRetriever?.let {
                                    observable.unregister(locationKey, it)
                                    locationRetriever = null
                                }
                                initialLocation = value
                            }
                        }

                        do {
                            delay(100)
                        } while (null == initialLocation && prepareJob?.isActive == true)

                        if (true == prepareJob?.isActive) {
                            settingHomeLocation()
                            isDroneReady = true
                        }
                        prepareJob = null
                    }
                }

                false -> {
                    initialLocation = null
                    Timber.d("Not flying, take off the drone first")
                    // not flying, takeoff first
                    prepareJob = scope.launch(Dispatchers.IO) {
                        KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).action()
                        locationRetriever = observable.register(locationKey) { key, value ->
                            if (locationKey.innerIdentifier == key.innerIdentifier && null != (value as? LocationCoordinate3D)) {
                                Timber.d("Retrieved valid location for checking if drone is ready or not")
                                initialLocation = value
                            }
                        }

                        // detect the height of the drone
                        do {
                            delay(100)
                        } while (prepareJob?.isActive == true && (null == initialLocation || abs(
                                initialLocation!!.altitude - expectedTakeOffHeight
                            ) >= abs(
                                expectedTakeOffHeight / 10f
                            ))
                        )
                        Timber.d("The drone is ready or the task becomes invalid (${true != prepareJob?.isActive})")
                        locationRetriever?.let {
                            observable.unregister(locationKey, it)
                            locationRetriever = null
                        }

                        if (true == prepareJob?.isActive) {
                            settingHomeLocation()
                            Timber.d("The drone is ready now")
                            isDroneReady = true
                        }
                        prepareJob = null
                    }
                }

                else -> {
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying).get({ flying ->
                        flying?.let { prepareDrone(it) }
                    }, {
                        // do nothing, just ignore it
                    })
                }
            }
        }
    }

    fun changeDroneVelocity(
        backwardForward: Double = 0.0,
        rightLeft: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 100,
    ) {
        adjustDroneVelocity(rightLeft, backwardForward, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }


    fun abort() {
        if (isDroneReady) {
            // reset the velocity around different direction
            adjustDroneVelocity(0.0, 0.0, 0.0)
        }
        prepareJob?.cancel()
        prepareJob = null
        isDroneReady = false
    }

    fun destroy() {
        setObstacleAvoidance(true, null)
        setObstacleAvoidanceWarningDistance(4.0)
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        VirtualStickManager.getInstance()
            .disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    messageNotifier.invoke(Log.DEBUG, "Disable virtual stick successfully", null)
                }

                override fun onFailure(p0: IDJIError) {
                    messageNotifier.invoke(
                        Log.ERROR,
                        "Failed to disable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                        null
                    )
                }
            })
    }

    /**
     * roll: positive to right, negative to left
     * pitch: positive to backward, negative to forward
     * yaw: positive rotate towards right, negative rotate towards left
     */
    private fun adjustDroneVelocity(roll: Double = 0.0, pitch: Double = 0.0, yaw: Double = 0.0) {
        val param = VirtualStickFlightControlParam()
        param.yaw = yaw
        param.roll = roll
        param.pitch = pitch
        param.rollPitchControlMode = RollPitchControlMode.VELOCITY
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

        val log =
            "Change drone's velocity: ${if (pitch > 0) "Backward" else "Forward"}: ${abs(pitch)}\t" +
                    "${if (roll >= 0) "Right" else "Left"}: ${abs(roll)}\tRotation: $yaw"
        messageNotifier.invoke(Log.INFO, log, null)

        if (null == sendingCmdJob) {
            sendingCmdJob = scope.launch(Dispatchers.IO) {
                while (sendingCmdJob?.isActive == true) {
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    // maximum recommended frequency is 25 Hz
                    delay(40)
                }
            }
        }
    }

    private fun setObstacleAvoidanceWarningDistance(distance: Double) {
        listOf(
            PerceptionDirection.HORIZONTAL,
            PerceptionDirection.DOWNWARD,
            PerceptionDirection.UPWARD
        ).forEach { direction ->
            PerceptionManager.getInstance().setObstacleAvoidanceWarningDistance(
                distance,
                direction,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier.invoke(
                            Log.DEBUG,
                            "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                            null
                        )
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier.invoke(
                            Log.ERROR,
                            "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                            null
                        )
                    }
                })
        }
    }

    private fun setObstacleAvoidance(enable: Boolean, action: (() -> Unit)?) {
        // right here, can use the `setObstacleAvoidanceEnabled` to enable/disable obstacle avoidance in three directions,
        // because the mini 3 pro does not support this kind of operation
        PerceptionManager.getInstance().setObstacleAvoidanceType(
            if (enable) ObstacleAvoidanceType.BYPASS else ObstacleAvoidanceType.CLOSE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    messageNotifier.invoke(
                        Log.DEBUG,
                        "${if (enable) "Enable" else "Disable"} obstacle avoidance successfully",
                        null
                    )
                    action?.invoke()
                }

                override fun onFailure(p0: IDJIError) {
                    messageNotifier.invoke(
                        Log.ERROR,
                        "${if (enable) "Enable" else "Disable"} obstacle avoidance failed (${p0.errorCode()}): ${p0.hint()}",
                        null
                    )
                }
            })
    }

    private fun settingHomeLocation() {
        val homeLocation =
            LocationCoordinate2D(initialLocation!!.latitude, initialLocation!!.longitude)
        KeyTools.createKey(FlightControllerKey.KeyHomeLocation).set(homeLocation) {

        }
    }
}