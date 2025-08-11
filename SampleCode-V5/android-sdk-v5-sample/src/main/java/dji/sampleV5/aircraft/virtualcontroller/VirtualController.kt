package dji.sampleV5.aircraft.virtualcontroller

import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class VirtualController(
    private val scope: CoroutineScope,
    private val observable: RawDataObservable,
) : OnRawDataObserver {


    var isDroneReady: Boolean = false
        private set

    private var prepareJob: Job? = null

    private var expectedTakeOffHeight = Float.MAX_VALUE

    private var locationRetriever: OnRawDataObserver? = null

    private var initialLocation: LocationCoordinate3D? = null

    override fun invoke(key: DJIKeyInfo<*>, value: Any?) {
    }

    fun prepareDrone(isFlying: Boolean? = null) {
        if (!isDroneReady && null == prepareJob) {
            // for now, there is no obvious status regarding this
            // the only way to do is to check if the drone reaches the height obtained from `KeyAircraftAttitude`
            // however, the height recognition is not that accurate.

            val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
            when (isFlying) {
                true -> {
                    initialLocation = null

                    // already flying, regard current location as the initial location
                    prepareJob = scope.launch(Dispatchers.IO) {
                        locationRetriever = observable.register { key, value ->
                            // TODO this kind of checking equality still need to be verified
                            if (locationKey.equals(key) && null != (value as? LocationCoordinate3D)) {
                                locationRetriever?.let {
                                    observable.unregister(it)
                                    locationRetriever = null
                                }
                                initialLocation = value
                            }
                        }

                        do {
                            delay(10)
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

                    expectedTakeOffHeight = Float.MAX_VALUE
                    KeyTools.createKey(FlightControllerKey.KeyTakeoffLocationAltitude).get({
                        expectedTakeOffHeight = it?.toFloat() ?: expectedTakeOffHeight
                    }, {
                        prepareJob?.cancel()
                    })

                    // not flying, takeoff first
                    prepareJob = scope.launch(Dispatchers.IO) {
                        KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).action()
                        locationRetriever = observable.register { key, value ->
                            if (locationKey.equals(key) && null != (value as? LocationCoordinate3D)) {
                                initialLocation = value
                            }
                        }

                        // detect the height of the drone
                        do {
                            delay(10)
                        } while (prepareJob?.isActive == true && (null == initialLocation || abs(
                                initialLocation!!.altitude - expectedTakeOffHeight
                            ) >= abs(
                                expectedTakeOffHeight
                            ) / 20)
                        )
                        locationRetriever?.let {
                            observable.unregister(it)
                            locationRetriever = null
                        }

                        if (true == prepareJob?.isActive) {
                            settingHomeLocation()
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

    fun abort() {
        prepareJob?.cancel()
        isDroneReady = false
    }

    private fun settingHomeLocation() {
        val homeLocation =
            LocationCoordinate2D(initialLocation!!.latitude, initialLocation!!.longitude)
        KeyTools.createKey(FlightControllerKey.KeyHomeLocation).set(homeLocation) {

        }
    }
}