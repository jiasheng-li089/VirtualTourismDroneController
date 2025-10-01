package dji.sampleV5.aircraft.virtualcontroller

import android.graphics.RectF
import android.os.SystemClock
import dji.sampleV5.aircraft.COMPASS_OFFSET
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.Velocity3D
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin


interface IPositionMonitor {

    fun getX(): Double

    fun getY(): Double

    fun getZ(): Double

    /**
     * Returns drone's current orientation in self-maintained coordinate system (SCS), ranges from 0 - 360
     */
    fun getOrientationInSCS(): Double

    fun getOrientationBenchmark(): Double

    fun convertCoordinateToNED(velocitiesInSCS: DoubleArray): DoubleArray

    fun convertCoordinateToBody(velocitiesInSCS: DoubleArray): DoubleArray

    fun convertOrientationToNED(orientationInSCS: Double): Double

    fun start()

    fun stop()
}

open class BaseDroneSpatialPositionMonitor () {

    protected var currentOrientation: Double = Double.NaN

    protected var benchmarkOrientation: Double = Double.NaN

    fun innerConvertCoordinateToNED(xyzVelocities: DoubleArray): DoubleArray {
        val nedVelocities = xyzVelocities.copyOf(xyzVelocities.size)

        // conversion:
        //              x maps to east
        //              y maps to north
        // x' = x cos θ - y sin θ
        // y' = x sin θ + y cos θ
        // convert GROUND based to NED based
        val targetAngle = benchmarkOrientation.toRadians()
        val xVelocityNED = xyzVelocities[0] * cos(targetAngle) - xyzVelocities[1] * sin(targetAngle)
        val yVelocityNED = xyzVelocities[0] * sin(targetAngle) + xyzVelocities[1] * cos(targetAngle)

        nedVelocities[0] = yVelocityNED
        nedVelocities[1] = xVelocityNED
        nedVelocities[2] = xyzVelocities[2]

        return nedVelocities
    }

    fun innerConvertCoordinateToBody(xyzVelocities: DoubleArray): DoubleArray {
        val bodyVelocities = xyzVelocities.copyOf(xyzVelocities.size)

        // conversion:
        //  x maps to right
        //  y maps to forward
        // x' = x cos θ - y sin θ
        // y' = x sin θ + y cos θ
        var targetAngle = ((- (currentOrientation - benchmarkOrientation)) % 360).toRadians()
        val xVelocityBody = xyzVelocities[0] * cos(targetAngle) - xyzVelocities[1] * sin(targetAngle)
        val yVelocityBody = xyzVelocities[0] * sin(targetAngle) + xyzVelocities[1] * cos(targetAngle)

        bodyVelocities[0] = yVelocityBody
        bodyVelocities[1] = xVelocityBody
        bodyVelocities[2] = xyzVelocities[2]
        return bodyVelocities
    }

    private fun Double.toRadians() = Math.toRadians(this)
}

open class DroneSpatialPositionMonitor (private var observable: RawDataObservable, private val statusUpdater: StatusUpdater?): BaseDroneSpatialPositionMonitor(), OnRawDataObserver, IPositionMonitor {

    private var x: Double = 0.0

    private var y: Double = 0.0

    private var z: Double = 0.0

    // benchmark orientation, ranges from -180 to 180,
    // 0 means the drone is toward North,
    // 90 means the drone is toward East,
    // -90 means the drone is toward West
    // 180 and -180 means the drone is toward South
    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private val velocityKey = FlightControllerKey.KeyAircraftVelocity

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private var lastPositionUpdateTime = 0L

    private var lastVelocities: Velocity3D = Velocity3D(0.0, 0.0, 0.0)

    private var currentGimbalAttitude: Attitude? = null

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

        val velocityAroundNorth = (velocities.x + lastVelocities.x) / 2
        val velocityAroundEast = (velocities.y + lastVelocities.y) / 2
        val velocityAroundDownloadSide = (velocities.z + lastVelocities.z) / 2

        // position change around z axis is straightforward enough
        z += velocityAroundDownloadSide * timeDifference / 1000f

        var velocityAroundY = - velocityAroundNorth * cos(benchmarkOrientation)

        // TODO still need to figure out in which direction the drone will return positive value for benchmark
        // it decides `+` or `-` should be used in the next statement,
        // for now assume on the right side of north, it will return positive, so `+` is used in below statement
        velocityAroundY += velocityAroundEast * sin(benchmarkOrientation)

        var velocityAroundX = - velocityAroundEast * cos(benchmarkOrientation)
        // TODO same problem as above one
        velocityAroundX += velocityAroundNorth * sin(benchmarkOrientation)

        // update position around x and y axes
        y += velocityAroundY * timeDifference / 1000f
        x += velocityAroundX * timeDifference / 1000f
        lastPositionUpdateTime += timeDifference

        statusUpdater?.invoke("Drone Velocity (X/Y/Z)", "$velocityAroundX / $velocityAroundY / $velocityAroundDownloadSide")
        statusUpdater?.invoke("Drone Position (X/Y/Z)", "$x / $y / $z")
        lastVelocities = velocities
    }

    private fun updateAttitude(attitude: Attitude) {
        // using the yaw/Z axis as the attitude
        if (this.benchmarkOrientation.isNaN()) {
            // the range of yaw is from -180 to 180
            this.benchmarkOrientation = attitude.yaw + COMPASS_OFFSET
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        currentOrientation = attitude.yaw + COMPASS_OFFSET

        statusUpdater?.invoke("Drone Position (X/Y/Z)", "$x / $y / $z")
        Timber.d("Update drone virtual position (X/Y/Z): $x / $y / $z")
    }

    private fun updateGimbalAttitude(attitude: Attitude) {
        this.currentGimbalAttitude = attitude
    }

    override fun convertCoordinateToNED(velocitiesInSCS: DoubleArray): DoubleArray {
        return super.innerConvertCoordinateToNED(velocitiesInSCS)
    }

    override fun convertCoordinateToBody(velocitiesInSCS: DoubleArray): DoubleArray {
        return super.innerConvertCoordinateToBody(velocitiesInSCS)
    }

    override fun convertOrientationToNED(orientationInSCS: Double): Double {
        // calculate the absolute orientation without benchmark
        val result = (orientationInSCS + benchmarkOrientation).normalizeToSCS()

        // calibrate the orientation to range (-180, 180)
        return if (result >= -180 && result <= 180) {
            result
        } else if (result > 180) {
            // -180 + (result - 180)
            result - 360
        } else { // < -180
            // 180 - (-180 - result)
            360 - result
        }
    }

    override fun start() {
        resetStatusValues()

        observable.register(attitudeKey, this)
        observable.register(velocityKey, this)
        observable.register(gimbalAttitudeKey, this)
    }

    override fun stop() {
        observable.unregister(attitudeKey, this)
        observable.unregister(velocityKey, this)
        observable.unregister(gimbalAttitudeKey, this)
    }

    override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
        if (p1.innerIdentifier.equals(velocityKey.innerIdentifier)) {
            (p2 as? Velocity3D)?.let {
                updateVelocities(it)
            }
        } else if (p1.innerIdentifier.equals(attitudeKey.innerIdentifier)) {
            (p2 as? Attitude)?.let { updateAttitude(it) }
        } else if (p1.innerIdentifier.equals(gimbalAttitudeKey.innerIdentifier)) {
            (p2 as? Attitude)?.let { updateGimbalAttitude(it) }
        }
    }

    override fun getX() = x

    override fun getY() = y

    override fun getZ() = z

    override fun getOrientationInSCS(): Double {
        // INFO both currentOrientation and benchmarkOrientation range from -180 to 180
        // so the result should range from -360 to 360
        return (currentOrientation - benchmarkOrientation).normalizeToSCS()
    }

    override fun getOrientationBenchmark() = benchmarkOrientation
}


fun Double.normalizeToSCS(): Double {
    return if (this >= 0) {
        this % 360
    } else {
        360.0 + this
    }
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