package dji.sampleV5.aircraft

import android.graphics.RectF
import android.util.Log


// if use the camera mounted on drone as the video source
const val USE_DRONE_CAMERA = false


// if use the mock control class to test the control data sent from the headset
const val USE_MOCK_CONTROL = true


// the interval between sending two 'Ping' packet to test the data latency
const val PING_INTERVAL = 1000L


// the frequency of sending control command to the drone via the VirtualStick's advanced parameters
// maximum recommended frequency is 25 Hz
const val SENDING_FREQUENCY = 5


// the orientation data returned from drone is not accurate, need to be calibrated.
const val COMPASS_OFFSET = 17.0


// current configuration of the thumb stick control scale factors
lateinit var currentControlScaleConfiguration: ControlStickScaleConfiguration

// all log with level larger or equals to this level will be stored in log file
const val MINIMUM_LOG_LEVEL = Log.DEBUG

// current valid electrical fence
val currentEFence = RectF(-10f, 10f, 10f, -10f)

data class ScaleFactor(
    var left_horizontal: Float,
    var left_vertical: Float,
    var right_horizontal: Float,
    var right_vertical: Float
)

data class ControlStickScaleConfiguration(
    var name: String, var description: String, var scale: ScaleFactor
)