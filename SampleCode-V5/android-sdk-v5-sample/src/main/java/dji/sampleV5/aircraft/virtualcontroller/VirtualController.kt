package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.models.ControlStatusData
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

// maximum recommended frequency is 25 Hz
private const val SENDING_FREQUENCY = 5

interface IControlStrategy {

}

class VirtualController(
    private val scope: CoroutineScope,
    private val observable: RawDataObservable,
    private val messageNotifier: (Int, String, Throwable?) -> Unit,
) {

    var isDroneReady: Boolean = false
        private set

    private var prepareJob: Job? = null

    private var expectedTakeOffHeight = 1.2f

    @Volatile
    var initialLocation: LocationCoordinate3D? = null
        private set

    private var droneParam: VirtualStickFlightControlParam

    private var sendingCmdJob: Job? = null

    init {
        setObstacleAvoidanceWarningDistance(0.1)
        setObstacleAvoidance(false, null)

        KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(2, {
            messageNotifier.invoke(Log.DEBUG, "Set the maximum height of drone successfully", null)
        }, {
            messageNotifier.invoke(
                Log.ERROR,
                "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}",
                null
            )
        })

        droneParam = initDroneAdvancedParam()
    }

    private fun initDroneAdvancedParam(): VirtualStickFlightControlParam {
        val param = VirtualStickFlightControlParam()
        param.yaw = 0.0
        param.roll = 0.0
        param.pitch = 0.0
        param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        param.verticalControlMode = VerticalControlMode.VELOCITY
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        param.rollPitchControlMode = RollPitchControlMode.VELOCITY
        return param
    }

    private fun getDroneReady(isFlying: Boolean) {
        // TODO be careful, the logic of this method heavily depends on getting location of the drone, this should be verified if it works indoor
        if (isDroneReady || null != prepareJob) {
            // already got ready or in the preparing stage
            return
        }

        // for now, there is no obvious status regarding this
        // the only way to do is to check if the drone reaches the height obtained from `KeyAircraftAttitude`
        // however, the height recognition is not that accurate.

        val locationKey = FlightControllerKey.KeyAircraftLocation3D
        val ultrasonicHeightKey = FlightControllerKey.KeyUltrasonicHeight

        // 0 is the location callback, 1 is the ultrasound height callback
        val rawDataObserver = Array<OnRawDataObserver?>(2) {
            null
        }
        var initHeight = 0.0
        var isInDoor = false

        if (isFlying) {
            Timber.d("already flying, set the status to ready, ignore setting home location and enable virtual stick control")
            isDroneReady = true
            changeVirtualStickStatus(enable = true, syncAdvancedParam = true)
            prepareJob?.cancel()
            prepareJob = null
            return

            // because of these callbacks are unreliable within indoor and outdoor environments, respectively.
            Timber.d("already flying, try to retrieve current location as the initial position")
            initialLocation = null

            // already flying, regard current location as the initial location
            prepareJob = scope.launch(Dispatchers.IO) {
                // within indoor environment, the sdk won't return the location of the drone because of no GPS signal, then how to detect the height???
                rawDataObserver[0] = observable.register(locationKey) { key, value ->
                    if (locationKey.innerIdentifier == key.innerIdentifier && null != (value as? LocationCoordinate3D)) {
                        Timber.d("Retrieved valid location from gps (#1)")
                        rawDataObserver[0]?.let {
                            observable.unregister(locationKey, it)
                            rawDataObserver[0] = null
                        }
                        initialLocation = value
                        isInDoor = false
                        initHeight = initialLocation!!.altitude
                    }
                }

                do {
                    delay(100)
                    if (null == rawDataObserver[1]) {
                        Timber.d("Register ultrasonic height listener")
                        // that doesn't make sense at all, when the drone is hovering, ultrasonic won't return any thing
                        rawDataObserver[1] = observable.register(ultrasonicHeightKey) { key, value ->
                            if (ultrasonicHeightKey.innerIdentifier == key.innerIdentifier && null != (value as? Int)) {
                                Timber.d("Retrieved valid height from ultrasonic (#1): $value")
                                rawDataObserver[1]?.let {
                                    observable.unregister(ultrasonicHeightKey, it)
                                    rawDataObserver[1] = null
                                }
                                // the unit of the ultrasonic height is decimeter
                                initHeight = value / 10.0
                                isInDoor = true
                            }
                        }
                    }
                } while (prepareJob?.isActive == true && (!isInDoor && null == initialLocation))

                if (true == prepareJob?.isActive) {
                    if (null != initialLocation)  settingHomeLocation()
                    isDroneReady = true

                    // only enable advanced param when the drone is ready
                    changeVirtualStickStatus(enable = true, syncAdvancedParam = true)
                }
                prepareJob = null
            }
        } else {
            initialLocation = null
            isInDoor = true
            Timber.d("Not flying, take off the drone first")
            // not flying, takeoff first
            prepareJob = scope.launch(Dispatchers.IO) {
                KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).action()
                rawDataObserver[0] = observable.register(locationKey) { key, value ->
                    if (locationKey.innerIdentifier == key.innerIdentifier && null != (value as? LocationCoordinate3D)) {
                        Timber.d("Retrieved valid location from gps (#2)")
                        Timber.d("Retrieved valid location for checking if drone is ready or not")
                        initialLocation = value
                        isInDoor = false
                        initHeight = initialLocation!!.altitude
                    }
                }
                Timber.d("Register ultrasonic height listener")
                rawDataObserver[1] = observable.register(ultrasonicHeightKey) { key, value ->
                    if (ultrasonicHeightKey.innerIdentifier == key.innerIdentifier && null != (value as? Int)) {
                        Timber.d("Retrieved valid height from ultrasonic (#2): $value")
                        if (isInDoor) {
                            initHeight = value / 10.0
                        } else {
                            // within an outdoor environment, can receive the gps signal, so ignore the result of ultrasonic
                        }
                    }
                }

                // detect the height of the drone
                var isAroundExpectedHeight: Boolean
                do {
                    delay(100)

                    isAroundExpectedHeight =
                        (abs(initHeight - expectedTakeOffHeight) <= abs(expectedTakeOffHeight / 10f))
                } while (prepareJob?.isActive == true && !isAroundExpectedHeight)

                Timber.d("The drone is ready or the task becomes invalid (${true != prepareJob?.isActive})")
                rawDataObserver[0]?.let {
                    observable.unregister(locationKey, it)
                    rawDataObserver[0] = null
                }
                rawDataObserver[1]?.let {
                    observable.unregister(ultrasonicHeightKey, it)
                    rawDataObserver[1] = null
                }

                if (true == prepareJob?.isActive) {
                    if (null != initialLocation) settingHomeLocation()
                    Timber.d("The drone is ready now")
                    isDroneReady = true

                    // only enable advanced param when the drone is ready
                    changeVirtualStickStatus(enable = true, syncAdvancedParam = true)
                }
                prepareJob = null
            }
        }
    }

    fun onControlStatusData(data: ControlStatusData) {

    }

    fun prepareDrone() {
        if (!isDroneReady && null == prepareJob) {
            KeyTools.createKey(FlightControllerKey.KeyIsFlying).get({ flying ->
                flying?.let { getDroneReady(it) }
            }, {
                messageNotifier.invoke(
                    Log.ERROR,
                    "Unable to check if drone is flying(${it.errorCode()}): ${it.hint()}",
                    null
                )
            })
        }
    }

    fun changeDroneVelocity(
        forwardBackward: Double = 0.0,
        rightLeft: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 1000,
    ) {
        adjustDroneVelocity(forwardBackward, rightLeft, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }


    fun abort() {
        isDroneReady = false

        adjustDroneVelocityOneTime(0.0, 0.0, 0.0)
        changeVirtualStickStatus(enable = false, syncAdvancedParam = true)

        prepareJob?.cancel()
        prepareJob = null
    }

    fun destroy() {
        setObstacleAvoidance(true, null)
        setObstacleAvoidanceWarningDistance(4.0)

        changeVirtualStickStatus(enable = false, syncAdvancedParam = true)
    }

    /**
     * In velocity model, roll means forward/backward, pitch means right/left
     * yaw: positive rotate towards right, negative rotate towards left
     */
    private fun adjustDroneVelocity(roll: Double = 0.0, pitch: Double = 0.0, yaw: Double = 0.0) {
        droneParam.yaw = yaw
        droneParam.roll = roll
        droneParam.pitch = pitch

        val log =
            "Change drone's velocity: ${if (pitch > 0) "Backward" else "Forward"}: ${abs(pitch)}\t" +
                    "${if (roll >= 0) "Right" else "Left"}: ${abs(roll)}\tRotation: $yaw"
        messageNotifier.invoke(Log.INFO, log, null)

        if (null == sendingCmdJob || !sendingCmdJob!!.isActive) {
            sendingCmdJob = scope.launch(Dispatchers.IO) {
                while (sendingCmdJob?.isActive == true && isDroneReady) {
                    // don't output the log to screen, there is too much log
                    Timber.d("Sending advanced stick param to the drone: ${droneParam.toJson()}")
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(droneParam)
                    delay(1000L / SENDING_FREQUENCY)
                }
            }
        }
    }

    private fun adjustDroneVelocityOneTime(roll: Double, pitch: Double, yaw: Double) {
        val param = initDroneAdvancedParam()
        param.yaw = yaw
        param.roll = roll
        param.pitch = pitch

        Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
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

    private fun changeVirtualStickStatus(enable: Boolean, syncAdvancedParam: Boolean) {
        if (enable) {
            VirtualStickManager.getInstance()
                .enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier.invoke(Log.DEBUG, "Enable virtual stick successfully", null)

                        if (syncAdvancedParam) VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier.invoke(
                            Log.ERROR,
                            "Failed to enable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                    }

                })
        } else {
            if (syncAdvancedParam) VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)

            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    messageNotifier.invoke(Log.DEBUG, "Disable virtual stick successfully", null)
                }

                override fun onFailure(p0: IDJIError) {
                    messageNotifier.invoke(Log.ERROR, "Failed to disable the virtual stick(${p0.errorCode()}): ${p0.hint()}", null)
                }

            })
        }
    }

    private fun settingHomeLocation() {
        val homeLocation =
            LocationCoordinate2D(initialLocation!!.latitude, initialLocation!!.longitude)
        KeyTools.createKey(FlightControllerKey.KeyHomeLocation).set(homeLocation) {

        }
    }
}