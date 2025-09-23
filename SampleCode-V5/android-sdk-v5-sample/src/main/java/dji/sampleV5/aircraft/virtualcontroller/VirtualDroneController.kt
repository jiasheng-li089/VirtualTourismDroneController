package dji.sampleV5.aircraft.virtualcontroller

import android.os.SystemClock
import android.util.Log
import dji.sampleV5.aircraft.MAXIMUM_ROTATION_VELOCITY
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.TEST_VIRTUAL_STICK_ADVANCED_PARAM
import dji.sampleV5.aircraft.currentControlScaleConfiguration
import dji.sampleV5.aircraft.currentEFence
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.models.Vector2D
import dji.sampleV5.aircraft.models.Vector3D
import dji.sampleV5.aircraft.utils.toJson
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.pow


typealias ControlStatusFeedback = (String, String) -> Unit
typealias MessageNotifier = (Int, String, Throwable?) -> Unit
typealias StatusUpdater = (String, String) -> Unit

private fun initDroneAdvancedParam(): VirtualStickFlightControlParam {
    val param = VirtualStickFlightControlParam()
    param.yaw = 0.0
    param.roll = 0.0
    param.pitch = 0.0
    param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    param.verticalControlMode = VerticalControlMode.VELOCITY
    param.yawControlMode = YawControlMode.ANGLE
    param.rollPitchControlMode = RollPitchControlMode.VELOCITY
    param.verticalControlMode = VerticalControlMode.POSITION
    return param
}

private fun adjustDroneVelocityOneTime(
    roll: Double? = null,
    pitch: Double? = null,
    yaw: Double? = null,
    throttle: Double? = null,
) {
    val param = initDroneAdvancedParam()
    if (null == yaw) {
        // no valid angle, convert to velocity mode and set velocity to 0 to avoid rotation
        param.yaw = 0.0
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
    } else {
        param.yawControlMode = YawControlMode.ANGLE
        param.yaw = yaw
    }
    if (null == roll) {
        param.roll = 0.0
    } else {
        param.roll = roll
    }
    if (null == pitch) {
        param.pitch = 0.0
    } else {
        param.pitch = pitch
    }
    if (null == throttle) {
        param.verticalControlMode = VerticalControlMode.VELOCITY
        param.verticalThrottle = 0.0
    } else {
        param.verticalControlMode = VerticalControlMode.POSITION
        param.verticalThrottle = throttle
    }

    Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
}

/**
 * adjust the angle of the gimbal; positive means rise the camera; negative means set the camera
 */
private fun adjustCameraOrientation(angle: Double) {
    val param = GimbalAngleRotation()
    // pitch: the camera will rise (positive) or set (negative)
    // roll: the camera will rotate counterclockwise (positive) and clockwise (negative)
    // yaw: the camera will rotate towards left (positive) and right (negative)
    param.pitch = angle
    param.mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
    param.duration = 1.0

    KeyTools.createKey(GimbalKey.KeyRotateByAngle).action(param, { result ->
        Timber.d("Rotate the gimbal successfully: $angle")
    }, { error->
        Timber.e("Failed to rotate the gimbal (${error.errorCode()}): ${error.hint()}")
    })
}

/**
 * calculate the shortest angle starts from the original angle to the target angle.
 * positive means change in clockwise direction; negative means change in counterclockwise direction
 *
 * @param originAngle the original angle
 * @param targetAngle the target angle
 */
private fun shortestAngleInSCS(originAngle: Double, targetAngle: Double): Double {
    return ((targetAngle - originAngle + 540) % 360 - 180)
}

interface IControlStrategy {

    fun isVirtualStickNeeded(): Boolean

    fun isVirtualStickAdvancedParamNeeded(): Boolean

    fun onControllerStatusData(data: ControlStatusData)

    fun updateDroneSpatialPositionMonitor(monitor: IPositionMonitor?)
}

class ControlViaThumbSticks() : IControlStrategy {
    override fun isVirtualStickNeeded() = true

    override fun isVirtualStickAdvancedParamNeeded() = TEST_VIRTUAL_STICK_ADVANCED_PARAM

    private var currentValidStatusTimestamp = 0L

    override fun onControllerStatusData(data: ControlStatusData) {
        if (currentValidStatusTimestamp > data.sampleTimestamp) {
            // ignore the old data, avoid the situation in which the device receives the old data later than the new data
            return
        }
        currentValidStatusTimestamp = data.sampleTimestamp

        VirtualStickManager.getInstance().leftStick.let {
            // rotation
            it.horizontalPosition = (mappingValues(data.leftThumbStickValue.x)
                    / currentControlScaleConfiguration.scale.left_horizontal).toInt()
            // upward and downward
            it.verticalPosition = (mappingValues(data.leftThumbStickValue.y)
                    / currentControlScaleConfiguration.scale.left_vertical).toInt()
        }
        VirtualStickManager.getInstance().rightStick?.let {
            // left and right
            it.horizontalPosition = (mappingValues(data.rightThumbStickValue.x)
                    / currentControlScaleConfiguration.scale.right_horizontal).toInt()
            // forward and backward
            it.verticalPosition = (mappingValues(data.rightThumbStickValue.y)
                    / currentControlScaleConfiguration.scale.right_vertical).toInt()
        }
    }

    fun mappingValues(input: Float): Float {
        val tmp = input.toDouble().pow(5.0)

        return (tmp * Stick.MAX_STICK_POSITION_ABS).toFloat()
    }

    override fun updateDroneSpatialPositionMonitor(monitor: IPositionMonitor?) {
        // it does not matter, even with position monitor, it is still impossible to achieve accurate control through thumbsticks

    }
}

class ControlViaHeadset(private val updateVelocityInterval: Long) : IControlStrategy {

    private var positionMonitor: IPositionMonitor? = null

    private var lastValidSampleTime = 0L

    private var lastSendCmdTimestamp = 0L

    private var benchmarkRotation: Vector3D? = null

    private var lastValidRotation: Vector3D? = null

    private var benchmarkPosition: Vector2D? = null

    private var lastValidPosition: Vector2D? = null

    private var lastPositionAroundZ: Double = 0.0

    override fun isVirtualStickNeeded(): Boolean = true

    override fun isVirtualStickAdvancedParamNeeded(): Boolean = true

    // how to implement the control?
    // base on two states in each packet to calculate the difference and apply this difference to the drone?
    // or base on two states (last applied state and currently received state) to calculate the difference?

    override fun onControllerStatusData(data: ControlStatusData) {
        // no valid position monitor, skip the control status data first
        positionMonitor?.let {
            // receiving old data, ignore it
            if (lastValidSampleTime >= data.sampleTimestamp) return

            // there is a maximum frequency limitation regarding sending data to the drone through the virtual stick advanced parameters
            // but the frequency of sampling data in headset is bigger then this valid, so we need to combine multi frames of data into one drone command
            val currentLocalTimestamp = SystemClock.elapsedRealtime()
            if (lastValidSampleTime > 0L && currentLocalTimestamp - this.lastSendCmdTimestamp <= updateVelocityInterval) {
                return
            }

            if (null == benchmarkRotation) {
                this.benchmarkRotation = data.benchmarkRotation
                this.lastValidRotation = data.currentRotation
            }
            if (null == benchmarkPosition) {
                this.benchmarkPosition = data.benchmarkPosition.to2D()
                this.lastValidPosition = data.currentPosition.to2D()
            }

            var targetOrientationInNED: Double? = null
            var rollSpeed: Double? = null
            var pitchSpeed: Double? = null
            var throttle: Double? = null
            val timeGapInSeconds = if (this.lastSendCmdTimestamp == 0L)
                data.sampleTimestamp - data.benchmarkSampleTimestamp * 1.0
            else
                (currentLocalTimestamp - this.lastSendCmdTimestamp) / 1000.0

            // both these angles are relative orientation, range from 0 to 360
            var targetAngleInSCS = data.getOrientationInSCS()
            val currentAngleInSCS = it.getOrientationInSCS()

            val shortestAngle = shortestAngleInSCS(currentAngleInSCS, targetAngleInSCS)
            // calculate the rotation angle changes, if it is huge enough, apply this changes to the drone
//            if (abs(shortestAngle) >= 1.0) {
                // calculate if the difference between current angle and target one is too large
                // check if go around the shortest path, it still exceed the maximum rotation velocity
                val maximumAngleChange = MAXIMUM_ROTATION_VELOCITY * timeGapInSeconds
                // only log when changes need to be applied
                Timber.d(
                    buildString {
                        append("Drone current orientation: $currentAngleInSCS, ")
                        append("Headset current orientation: $targetAngleInSCS, ")
                        append("Shortest angle is: $shortestAngle, ")
                        append("Maximum angle change: $maximumAngleChange")
                    }
                )
                if (maximumAngleChange > 0 && abs(shortestAngle) > maximumAngleChange) {
                    Timber.d(buildString {
                        append("The expected target drone orientation: ${it.convertOrientationToNED(targetAngleInSCS)}")
                    })
                    targetAngleInSCS =
                        currentAngleInSCS + (if (shortestAngle > 0) maximumAngleChange else -maximumAngleChange)
                    targetAngleInSCS = targetAngleInSCS.normalizeToSCS()
                }

                targetOrientationInNED =
                    it.convertOrientationToNED(targetAngleInSCS)

                Timber.d(
                    buildString {
                        append("Enough orientation to move. ")
                        append("Calculated target drone orientation: $targetAngleInSCS, ")
                        append("Headset benchmark orientation: ${data.benchmarkRotation.y}, ")
                        append("Headset current orientation: ${data.currentRotation.y}, ")
                        append("Drone benchmark: ${positionMonitor!!.getOrientationBenchmark()}")
                        append("Target orientation in NED system: $targetOrientationInNED")
                    }
                )

                this.lastValidRotation = data.currentRotation
//            }

            // calculate the height (position changes around z axis)
            // TODO for debugging, get rid of the vertical position change first
//            if (abs(data.currentPosition.z - lastPositionAroundZ) >= 0.1) {
//                Timber.d("The change in the Z axis is large enough to assign a target position to the drone. Old position: $lastPositionAroundZ, new position: ${data.currentPosition.z}")
//                throttle = data.currentPosition.z.toDouble()
//                this.lastPositionAroundZ = throttle
//            }

            // calculate the position changes around x and y axes
            if (abs(data.currentPosition.x - lastValidPosition!!.x) >= 0.1 || abs(data.currentPosition.y - lastValidPosition!!.y) >= 0.1) {
                Timber.d("The changes in the X axis and Y axis are large enough to assign speeds in these two directions: Old position: (${lastValidPosition!!.x}, ${lastValidPosition!!.y}), new position: (${data.currentPosition.x}, ${data.currentPosition.y})")
                var velocityAroundX =
                    (data.currentPosition.x - lastValidPosition!!.x) / timeGapInSeconds
                var velocityAroundY =
                    (data.currentPosition.y - lastValidPosition!!.y) / timeGapInSeconds

                // TODO convert the velocities around x and y to the ones around north and east
            }

            if (null != targetOrientationInNED || null != rollSpeed || null != pitchSpeed || null != throttle) {
                adjustDroneVelocityOneTime(rollSpeed, pitchSpeed, targetOrientationInNED, throttle)
            }

            // update gimbal angle, don't care about update in last time, only synchronize user's attitude to the gimbal
            // range from 0 to 360,
            // the value increases from 0 when user sets his head;
            // the value decreases from 360 when user rises this head
            // valid value range [0, 90] and [270, 360)
            val currentAngle = data.currentRotation.x
            val targetAngle =
            if (currentAngle >= 0 && currentAngle <= 90) {
                - currentAngle
            } else if (currentAngle >= 270 && currentAngle < 360) {
                360 - currentAngle
            } else {
                null
            }
            targetAngle?.let {
                adjustCameraOrientation(it.toDouble())
            }

            this.lastValidSampleTime = data.sampleTimestamp

            // compare current rotation to last valid rotation to calculate the difference


            // make this thing simple, ignore the position of the drone, just synchronize the velocity of the headset to the drone

            // for the safety, only apply the rotation to the drone


//            val updateIntervalInSeconds = updateVelocityInterval / 1000.0
//
//            // calculate the velocities around x, y, z axes;
//            val currentRelativeHMDX = (data.currentPosition.x - data.benchmarkPosition.x) * HEADSET_MOVEMENT_SCALE
//            val currentRelativeHMDY = (data.currentPosition.y - data.benchmarkPosition.y) * HEADSET_MOVEMENT_SCALE
//            val currentRelativeHMDZ = data.currentPosition.z - data.benchmarkPosition.z
//
//            var velocities = DoubleArray(3)
//            velocities[0]
//                (currentRelativeHMDX - it.getX()) / updateIntervalInSeconds
//            velocities[1] =
//                (currentRelativeHMDY - it.getY()) / updateIntervalInSeconds
//            velocities[2] =
//                (currentRelativeHMDZ - it.getZ()) / updateIntervalInSeconds
//
//            // convert these velocities to the ones based on NED coordinate system;
//            velocities = it.convertCoordinateToNED(velocities)
//
//            // set the velocities to the drone
//            Timber.i("Update drone velocities: ${velocities[0]} / ${velocities[1]} / ${velocities[2]}")
//            adjustDroneVelocityOneTime(velocities[1], velocities[0], 0.0, velocities[2])
//
//            // update last valid sample time
            this.lastValidSampleTime = data.sampleTimestamp
            this.lastSendCmdTimestamp = SystemClock.elapsedRealtime()
        }
    }

    override fun updateDroneSpatialPositionMonitor(monitor: IPositionMonitor?) {
        this.positionMonitor = monitor
    }
}

fun createControlStrategy(controlMode: Int): IControlStrategy {
    return when (controlMode) {
        0 -> ControlViaThumbSticks() // thumbsticks
        else -> ControlViaHeadset(1000L / SENDING_FREQUENCY) // headset
    }
}

interface IDroneController {

    fun getInitialLocation(): LocationCoordinate3D?

    fun prepareDrone(controlMode: Int)

    fun abort()

    fun landOff()

    fun changeDroneVelocity(
        forwardBackward: Double = 0.0,
        rightLeft: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 1000,
    )

    fun riseAndSetGimbal(angle: Double)

    fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double = 0.0,
        eastAndWest: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 1000,
    )

    fun onControllerStatusData(data: ControlStatusData)

    fun destroy()

    fun isDroneReady(): Boolean

}

abstract class BaseDroneController(
    protected val scope: CoroutineScope,
    protected val observable: RawDataObservable,
    protected val controlStatusFeedback: ControlStatusFeedback?,
    protected val messageNotifier: MessageNotifier?,
    protected val statusUpdater: StatusUpdater?,
) : IDroneController {

    private var isReady: Boolean = false

    override fun isDroneReady(): Boolean {
        return isReady
    }

    protected open fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            this@BaseDroneController.isReady = true

            controlStatusFeedback?.invoke("Control", "Start")
        } else {
            controlStatusFeedback?.invoke("Control", "Stop")

            this@BaseDroneController.isReady = false
        }
    }

    override fun landOff() {
    }
}

class MockDroneController(
    scope: CoroutineScope,
    observable: RawDataObservable,
    controlStatusFeedback: ControlStatusFeedback?,
    messageNotifier: MessageNotifier?,
    statusUpdater: StatusUpdater?,
) : BaseDroneController(scope, observable, controlStatusFeedback, messageNotifier, statusUpdater) {

    override fun getInitialLocation(): LocationCoordinate3D? {
        return null
    }

    override fun prepareDrone(controlMode: Int) {
        Timber.d("Mock to start the control")
        switchDroneStatus(true)
    }

    override fun abort() {
        Timber.d("Mock to abort the control")
        switchDroneStatus(false)
    }

    override fun changeDroneVelocity(
        forwardBackward: Double,
        rightLeft: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        Timber.d("Mock to change drone velocity: $forwardBackward / $rightLeft / $rotateRightLeft / $period")
    }

    override fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double,
        eastAndWest: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        Timber.d("Mock to change drone velocity: $northAndSouth / $eastAndWest / $rotateRightLeft / $period")
    }

    override fun onControllerStatusData(data: ControlStatusData) {
        Timber.d("Received status changes from remote controller: ${data.toJson()}")
    }

    override fun destroy() {
    }

    override fun riseAndSetGimbal(angle: Double) {
    }
}

class VirtualDroneController(
    scope: CoroutineScope,
    observable: RawDataObservable,
    controlStatusFeedback: ControlStatusFeedback?,
    messageNotifier: MessageNotifier?,
    statusUpdater: StatusUpdater?,
) : BaseDroneController(scope, observable, controlStatusFeedback, messageNotifier, statusUpdater) {

    private var prepareJob: Job? = null

    private var expectedTakeOffHeight = 1.2f

    @Volatile
    private var initialLocation: LocationCoordinate3D? = null

    private var droneParam: VirtualStickFlightControlParam

    private var sendingCmdJob: Job? = null

    private var controlStrategy: IControlStrategy? = null

    private var positionMonitor: IPositionMonitor? = null

    init {
        setObstacleAvoidanceWarningDistance(0.1)
        setObstacleAvoidance(false, null)

        // set maximum height the drone can fly
        KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(2, {
            messageNotifier?.invoke(Log.DEBUG, "Set the maximum height of drone successfully", null)
        }, {
            messageNotifier?.invoke(
                Log.ERROR,
                "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}",
                null
            )
        })

        droneParam = initDroneAdvancedParam()
        // TODO just for test
        droneParam.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    }

    override fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            positionMonitor?.stop()

            positionMonitor = if (true == controlStrategy?.isVirtualStickAdvancedParamNeeded())
                DroneSpatialPositionMonitorWithEFence(
                    currentEFence,
                    1000L / SENDING_FREQUENCY, observable, statusUpdater
                )
            else
                DroneSpatialPositionMonitor(observable, statusUpdater)

            if (true == controlStrategy?.isVirtualStickNeeded()) {
                positionMonitor?.start()
                changeVirtualStickStatus(
                    enable = true,
                    syncAdvancedParam = true == controlStrategy?.isVirtualStickAdvancedParamNeeded()
                ) {
                    if (it) {
                        prepareJob?.cancel()
                        prepareJob = null
                        controlStrategy?.updateDroneSpatialPositionMonitor(positionMonitor!!)
                        super.switchDroneStatus(isReady)
                    } else {
                        positionMonitor?.stop()
                    }
                }
            } else {
                // this should not happen
                positionMonitor?.start()
                controlStrategy?.updateDroneSpatialPositionMonitor(null)
                prepareJob?.cancel()
                prepareJob = null
                super.switchDroneStatus(true)
            }
        } else {
            super.switchDroneStatus(false)
            adjustDroneVelocityOneTime(0.0, 0.0, null)
            changeVirtualStickStatus(enable = false, syncAdvancedParam = true, null)
            positionMonitor?.stop()
            positionMonitor = null
        }
    }

    private fun getDroneReady(isFlying: Boolean) {
        // TODO be careful, the logic of this method heavily depends on getting location of the drone, this should be verified if it works indoor
        if (isDroneReady() || null != prepareJob) {
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
            switchDroneStatus(true)
            return
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
                    switchDroneStatus(true)
                }
                prepareJob = null
            }
        }
    }

    override fun onControllerStatusData(data: ControlStatusData) {
        if (isDroneReady()) {
            controlStrategy?.onControllerStatusData(data)
        }
    }

    override fun getInitialLocation(): LocationCoordinate3D? {
        return this.initialLocation
    }

    override fun prepareDrone(controlMode: Int) {
        controlStrategy = createControlStrategy(controlMode)

        if (!this.isDroneReady() && null == prepareJob) {
            KeyTools.createKey(FlightControllerKey.KeyIsFlying).get({ flying ->
                flying?.let { getDroneReady(it) }
            }, {
                messageNotifier?.invoke(
                    Log.ERROR,
                    "Unable to check if drone is flying(${it.errorCode()}): ${it.hint()}",
                    null
                )
            })
        }
    }

    override fun changeDroneVelocity(
        forwardBackward: Double,
        rightLeft: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        adjustDroneVelocity(forwardBackward, rightLeft, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }

    override fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double,
        eastAndWest: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        adjustDroneVelocity(northAndSouth, eastAndWest, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }


    override fun abort() {
        switchDroneStatus(false)

        prepareJob?.cancel()
        prepareJob = null
    }

    override fun landOff() {
        Timber.d("Start to land off the drone.")
        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).action()
    }

    override fun destroy() {
        setObstacleAvoidance(true, null)
        setObstacleAvoidanceWarningDistance(4.0)

        changeVirtualStickStatus(enable = false, syncAdvancedParam = true, null)
    }

    /**
     * In velocity mode and body coordinate system, roll means forward/backward, pitch means right/left
     * yaw: positive rotate towards right, negative rotate towards left
     *
     * TODO In velocity mode and ground coordinate system, roll means x axis (North), pitch means y axis (East),
     *  throttle means z axis (Down), and yaw for rotation
     */
    private fun adjustDroneVelocity(
        roll: Double = 0.0,
        pitch: Double = 0.0,
        yaw: Double = 0.0,
        throttle: Double = 0.0,
    ) {
        droneParam.yaw = yaw
        droneParam.roll = roll
        droneParam.pitch = pitch
        droneParam.verticalThrottle = throttle

        val log =
            "Change drone's velocity: ${if (pitch > 0) "Backward" else "Forward"}: ${abs(pitch)}\t" +
                    "${if (roll >= 0) "Right" else "Left"}: ${abs(roll)}\tRotation: $yaw"
        messageNotifier?.invoke(Log.INFO, log, null)

        if (null == sendingCmdJob || !sendingCmdJob!!.isActive) {
            sendingCmdJob = scope.launch(Dispatchers.IO) {
                while (sendingCmdJob?.isActive == true && isDroneReady()) {
                    // don't output the log to screen, there is too much log
                    Timber.d("Sending advanced stick param to the drone: ${droneParam.toJson()}")
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(droneParam)
                    delay(1000L / SENDING_FREQUENCY)
                }
            }
        }
    }

    override fun riseAndSetGimbal(angle: Double) {

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
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                            null
                        )
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
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
                    messageNotifier?.invoke(
                        Log.DEBUG,
                        "${if (enable) "Enable" else "Disable"} obstacle avoidance successfully",
                        null
                    )
                    action?.invoke()
                }

                override fun onFailure(p0: IDJIError) {
                    messageNotifier?.invoke(
                        Log.ERROR,
                        "${if (enable) "Enable" else "Disable"} obstacle avoidance failed (${p0.errorCode()}): ${p0.hint()}",
                        null
                    )
                }
            })
    }

    private fun changeVirtualStickStatus(
        enable: Boolean,
        syncAdvancedParam: Boolean,
        action: ((Boolean) -> Unit)?,
    ) {
        if (enable) {
            VirtualStickManager.getInstance()
                .enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "Enable virtual stick successfully",
                            null
                        )

                        if (syncAdvancedParam) VirtualStickManager.getInstance()
                            .setVirtualStickAdvancedModeEnabled(true)

                        action?.invoke(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "Failed to enable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                        action?.invoke(false)
                    }
                })
        } else {
            if (syncAdvancedParam) VirtualStickManager.getInstance()
                .setVirtualStickAdvancedModeEnabled(false)

            VirtualStickManager.getInstance()
                .disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "Disable virtual stick successfully",
                            null
                        )
                        action?.invoke(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "Failed to disable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                        action?.invoke(false)
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