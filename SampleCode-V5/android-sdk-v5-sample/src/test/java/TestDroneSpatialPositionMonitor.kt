import dji.sampleV5.aircraft.virtualcontroller.BaseDroneSpatialPositionMonitor
import org.junit.Test
import kotlin.test.assertTrue

data class DataBean (
    val angle: Double,
    val x: Double,
    val y: Double,
    val north: Double,
    val east: Double
)

class TestDroneSpatialPositionMonitor {

    fun testXYZToNED(data: DataBean) {
        val monitor = BaseDroneSpatialPositionMonitor()

        val field = BaseDroneSpatialPositionMonitor::class.java.getDeclaredField(
            "currentOrientation"
        )
        field.isAccessible = true
        field.set(monitor, data.angle)

        val xyzVelocity = arrayOf(data.x, data.y, 0.0).toDoubleArray()
        val targetVelocity = arrayOf(data.north, data.east, 0.0).toDoubleArray()

        assertTrue {
            targetVelocity.contentEquals(monitor.innerConvertCoordinateToNED(xyzVelocity))
        }
    }

    @Test
    fun testMultiData () {
        val list = listOf(
            DataBean(0.0, 100.0, 0.0, 0.0, 100.0),
            DataBean(0.0, 0.0, 100.0, 100.0, 0.0)
        )
        for (x in list) {
            testXYZToNED(x)
        }
    }
}