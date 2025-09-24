package dji.sampleV5.aircraft.virtualcontroller

import android.os.SystemClock
import dji.sampleV5.aircraft.HEADSET_MOVEMENT_SCALE
import dji.sampleV5.aircraft.MAXIMUM_ROTATION_VELOCITY
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.TEST_VIRTUAL_STICK_ADVANCED_PARAM
import dji.sampleV5.aircraft.currentControlScaleConfiguration
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.models.Vector3D
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.et.action
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import timber.log.Timber
import kotlin.math.pow


internal fun initDroneAdvancedParam(): VirtualStickFlightControlParam {
    val param = VirtualStickFlightControlParam()
    param.yaw = 0.0
    param.roll = 0.0
    param.pitch = 0.0
    param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    param.verticalControlMode = VerticalControlMode.VELOCITY
    param.yawControlMode = YawControlMode.ANGLE
    param.rollPitchControlMode = RollPitchControlMode.VELOCITY
    return param
}

/**
 * assign velocities in four directions
 * @param roll  specify the velocity in the direction of north and south, positive value means going towards north, negative value means south
 * @param pitch specify the velocity in the direction of east and west, positive value means going towards east, negative value means west
 * @param yaw   specify the target attitude of the drone, null means keep its current attitude
 * @param throttle  specify the velocity in vertical direction, positive means going down, negative means going up
 */
internal fun adjustDroneVelocityOneTime(
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
        param.verticalThrottle = 0.0
    } else {
        param.verticalThrottle = throttle
    }

    Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
}

/**
 * adjust the angle of the gimbal; positive means rising the camera; negative means setting the camera, the angle should range from -90 to 90
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

    private var benchmarkPosition: Vector3D? = null

    private var lastValidPosition: Vector3D? = null

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
                this.benchmarkPosition = data.benchmarkPosition
                this.lastValidPosition = data.benchmarkPosition
            }

            val timeGapInSeconds = if (this.lastSendCmdTimestamp == 0L)
                data.sampleTimestamp - data.benchmarkSampleTimestamp * 1.0
            else
                (currentLocalTimestamp - this.lastSendCmdTimestamp) / 1000.0

            val targetOrientationInNED = calculateTargetAttitudeInNED(data, it, timeGapInSeconds, it)
            this.lastValidRotation = data.currentRotation

            // calculate the height (position changes around z axis)
            // TODO for debugging, get rid of the vertical position change first
//            if (abs(data.currentPosition.z - lastPositionAroundZ) >= 0.1) {
//                Timber.d("The change in the Z axis is large enough to assign a target position to the drone. Old position: $lastPositionAroundZ, new position: ${data.currentPosition.z}")
//                throttle = data.currentPosition.z.toDouble()
//                this.lastPositionAroundZ = throttle
//            }

            // calculate the velocities in north-south, east-west and downward-upward directions
            val nedVelocity = calculateVelocityInXYZ(lastValidPosition!!,
                data.currentPosition, timeGapInSeconds, it)

            if (null != targetOrientationInNED || 0.0 != nedVelocity[0] || 0.0 != nedVelocity[1] || 0.0 != nedVelocity[2]) {
                adjustDroneVelocityOneTime(nedVelocity[0], nedVelocity[1], targetOrientationInNED, nedVelocity[2])
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

//            // update last valid sample time
            this.lastValidSampleTime = data.sampleTimestamp
            this.lastSendCmdTimestamp = SystemClock.elapsedRealtime()
        }
    }

    private fun calculateVelocityInXYZ(lastPosition: Vector3D, currentPosition: Vector3D,
                                       gapTimestamp: Double, positionMonitor: IPositionMonitor): DoubleArray {
        val xyzVelocity = DoubleArray(3)

        xyzVelocity[0] = (currentPosition.x - lastPosition.x) / gapTimestamp * HEADSET_MOVEMENT_SCALE
        xyzVelocity[1] = (currentPosition.y - lastPosition.y) / gapTimestamp * HEADSET_MOVEMENT_SCALE
        xyzVelocity[2] = (currentPosition.z - lastPosition.z) / gapTimestamp * HEADSET_MOVEMENT_SCALE

        Timber.d("Velocity in X/Y/Z axes: ${xyzVelocity[0]} / ${xyzVelocity[1]} / ${xyzVelocity[2]}")

        val result = positionMonitor.convertCoordinateToNED(xyzVelocity)
        Timber.d("Velocity in N/E/D axes: ${result[0]} / ${result[1]} / ${result[2]}")

        return result
    }

    private fun calculateTargetAttitudeInNED(
        data: ControlStatusData,
        monitor: IPositionMonitor,
        timeGapInSeconds: Double,
        positionMonitor: IPositionMonitor,
    ): Double? {
        // both these angles are relative orientation, range from 0 to 360
        var targetAngleInSCS = data.getOrientationInSCS()
        val currentAngleInSCS = monitor.getOrientationInSCS()

        val shortestAngle = shortestAngleInSCS(currentAngleInSCS, targetAngleInSCS)
        // calculate if the difference between current angle and target one is too large
        // check if go around the shortest path, it still exceed the maximum rotation velocity

        // TODO remove the maximum speed limitation, the rotation data has been smoothed on the headset side
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

        var targetOrientationInNED =
            monitor.convertOrientationToNED(targetAngleInSCS)

        Timber.d(
            buildString {
                append("Enough orientation to move. ")
                append("Calculated target drone orientation: $targetAngleInSCS, ")
                append("Headset benchmark orientation: ${data.benchmarkRotation.y}, ")
                append("Headset current orientation: ${data.currentRotation.y}, ")
                append("Drone benchmark: ${positionMonitor.getOrientationBenchmark()}")
                append("Target orientation in NED system: $targetOrientationInNED")
            }
        )
        return targetOrientationInNED
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