package dji.sampleV5.aircraft


// if use the camera mounted on drone as the video source
const val USE_DRONE_CAMERA = false


// if use the mock control class to test the control data sent from the headset
const val USE_MOCK_CONTROL = false


// the interval between sending two 'Ping' packet to test the data latency
const val PING_INTERVAL = 1000L


// the frequency of sending control command to the drone via the VirtualStick
// maximum recommended frequency is 25 Hz
const val SENDING_FREQUENCY = 5


lateinit var currentControlScaleConfiguration: ControlStickScaleConfiguration


data class ScaleFactor(
    var left_horizontal: Float,
    var left_vertical: Float,
    var right_horizontal: Float,
    var right_vertical: Float
)

data class ControlStickScaleConfiguration(
    var name: String, var description: String, var scale: ScaleFactor
)