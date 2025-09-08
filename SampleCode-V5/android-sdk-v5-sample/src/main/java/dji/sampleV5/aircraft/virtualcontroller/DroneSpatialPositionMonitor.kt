package dji.sampleV5.aircraft.virtualcontroller

import android.graphics.RectF
import android.os.SystemClock
import dji.sampleV5.aircraft.COMPASS_OFFSET
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.Velocity3D
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin


interface IPositionMonitor {

    fun getX(): Double

    fun getY(): Double

    fun getZ(): Double

    fun convertCoordinateToNED(bodyBasedVelocities: DoubleArray): DoubleArray

    fun start()

    fun stop()
}

open class DroneSpatialPositionMonitor (private var observable: RawDataObservable, private val statusUpdater: StatusUpdater?) : OnRawDataObserver, IPositionMonitor {

    private var x: Double = 0.0

    private var y: Double = 0.0

    private var z: Double = 0.0

    // range from 0 to 360, initially set to a value out of this range to mark as uninitialized.
    // while it equals to 180, the x axis is same as the north direction in NED coordinate system
    private var benchmarkOrientation: Double = Double.NaN

    private var currentOrientation: Double = Double.NaN

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private val velocityKey = FlightControllerKey.KeyAircraftVelocity

    private var lastPositionUpdateTime = 0L

    private fun resetStatusValues() {
        x = 0.0
        y = 0.0
        z = 0.0

        benchmarkOrientation = Double.NaN
        currentOrientation = Double.NaN
    }

    private fun updateVelocities(velocities: Velocity3D) {
        if (0L == lastPositionUpdateTime) return

        // only update the position after the attitude is got
        val timeDifference = SystemClock.elapsedRealtime() - lastPositionUpdateTime

        val velocityAroundNorth = velocities.x
        val velocityAroundEast = velocities.y
        val velocityAroundDownloadSide = velocities.z

        // position change around z axis is straightforward enough
        z += velocityAroundDownloadSide * timeDifference / 1000L

        var velocityAroundY = - velocityAroundNorth * cos(benchmarkOrientation)

        // TODO still need to figure out in which direction the drone will return positive value for benchmark
        // it decides `+` or `-` should be used in the next statement,
        // for now assume on the right side of north, it will return positive, so `+` is used in below statement
        velocityAroundY += velocityAroundEast * sin(benchmarkOrientation)


        var velocityAroundX = - velocityAroundEast * cos(benchmarkOrientation)
        // TODO same problem as above one
        velocityAroundX += velocityAroundNorth * sin(benchmarkOrientation)

        // update position around x and y axes
        y += velocityAroundY * timeDifference / 1000L
        x += velocityAroundX * timeDifference / 1000L
        lastPositionUpdateTime += timeDifference

        statusUpdater?.invoke("Drone Position (X/Y/Z)", "$x / $y / $z")
    }

    private fun updateAttitude(attitude: Attitude) {
        // using the yaw/Z axis as the attitude
        if (this.benchmarkOrientation.isNaN()) {
            // the range of yaw is from -180 to 180, convert it to new one from 0 to 360,
            this.benchmarkOrientation = attitude.yaw + 180 + COMPASS_OFFSET
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        currentOrientation = attitude.yaw + 180 + COMPASS_OFFSET
    }

    override fun convertCoordinateToNED(xyzVelocities: DoubleArray): DoubleArray {
        val velocities = xyzVelocities.copyOf(xyzVelocities.size)

        // velocity around north
        velocities[0] = - xyzVelocities[0] / cos(benchmarkOrientation) + xyzVelocities[1] / sin(benchmarkOrientation)

        // velocity around east
        velocities[1] = - xyzVelocities[1] / cos(benchmarkOrientation) + xyzVelocities[0] / sin(benchmarkOrientation)

        return velocities
    }

    override fun start() {
        resetStatusValues()

        observable.register(attitudeKey, this)
        observable.register(velocityKey, this)
    }

    override fun stop() {
        observable.unregister(attitudeKey, this)
        observable.unregister(velocityKey, this)
    }

    override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
        if (p1.innerIdentifier.equals(attitudeKey.innerIdentifier)) {
            (p2 as? Velocity3D)?.let {
                updateVelocities(it)
            }
        } else if (p1.innerIdentifier.equals(velocityKey.innerIdentifier)) {
            (p2 as? Attitude)?.let { updateAttitude(it) }
        }
    }

    override fun getX() = x

    override fun getY() = y

    override fun getZ() = z
}

class DroneSpatialPositionMonitorWithEFence(
    private val fence: RectF,
    private val updateVelocityInterval: Long,
    observable: RawDataObservable,
    statusUpdater: StatusUpdater?
): DroneSpatialPositionMonitor(observable, statusUpdater) {

    override fun convertCoordinateToNED(xyzVelocities: DoubleArray): DoubleArray {
        val updateIntervalInSeconds = updateVelocityInterval / 1000.0
        // modify the velocities around x and y axes to avoid flying out of the electrical fence
        // x
        if (xyzVelocities[0] > 0) {
            xyzVelocities[0] = min((fence.right - getX()) / updateIntervalInSeconds, xyzVelocities[0])
        } else {
            xyzVelocities[0] = max((fence.left - getX()) / updateIntervalInSeconds, xyzVelocities[0])
        }

        // y
        if (xyzVelocities[1] > 0) {
            xyzVelocities[1] = min((fence.top - getY()) / updateIntervalInSeconds, xyzVelocities[1])
        } else {
            xyzVelocities[1] = max((fence.bottom - getY()) / updateIntervalInSeconds, xyzVelocities[1])
        }

        return super.convertCoordinateToNED(xyzVelocities)
    }
}