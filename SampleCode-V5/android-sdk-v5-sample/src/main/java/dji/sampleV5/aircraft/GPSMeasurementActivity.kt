package dji.sampleV5.aircraft

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ChainStyle
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.ConstraintSet
import androidx.constraintlayout.compose.Dimension
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import dji.sampleV5.aircraft.databinding.ActivityGpsMeasurementBinding
import dji.sampleV5.aircraft.models.GPSMeasurementVM
import dji.sampleV5.aircraft.models.GeodeticLocation
import dji.sampleV5.aircraft.ui.theme.ComposeAppTheme

class GPSMeasurementActivity : AppCompatActivity() {


    private lateinit var binding: ActivityGpsMeasurementBinding

    private val viewModel: GPSMeasurementVM by viewModels()

    private lateinit var map: GoogleMap

    private fun formatLocation(any: Any?): String {
        return any?.toString() ?: "--"
    }

    private fun initializeViewModel() {
        map.uiSettings.let {
            it.isMyLocationButtonEnabled = true
            it.setAllGesturesEnabled(true)
        }
        viewModel.initialize(this)

        viewModel.geodeticPosList.observe(this) {
            if (binding.vMap.tag is List<*>) {
                val markers = binding.vMap.tag as List<Marker>
                markers.forEach { marker ->
                    marker.remove()
                }
            }
            val result = addMarkers(it)
            binding.vMap.tag = result

            updateSelectedItem()
            updateBenchmarkLocation()
        }

        viewModel.mapLocation.observe(this) {
            // update map location
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder().zoom(17.5f)
                        .target(LatLng(it.latitude!!, it.longitude!!)).build()
                )
            )
            updateSelectedItem()
            updateBenchmarkLocation()
        }

        map.setOnMarkerClickListener {
            viewModel.clickMapMarker(it.tag as GeodeticLocation)
        }

        viewModel.droneLocation.observe(this) { loc ->
            "Drone Location: ${formatLocation(loc.latitude)} / ${formatLocation(loc.longitude)} / ${
                formatLocation(
                    loc.height
                )
            }".also { binding.tvDroneLocation.text = it }
        }
        viewModel.trackingType.observe(this) { types ->
            (binding.spinnerTrackingType.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }

        viewModel.gapDistance.observe(this) { gap ->
            "Horizontal/Vertical Distance (m): ${formatLocation("%.2f".format(gap.first))} / ${
                formatLocation(
                    "%.2f".format(gap.second)
                )
            }".also {
                binding.tvGapDistance.text = it
            }
        }
        viewModel.recordStatus.observe(this) { status ->
            binding.btnStartRecord.text = if (status) "STOP" else "Record"
        }

        viewModel.recordFilePath.observe(this) { path ->
            "GPS Records file path: ${path ?: "--"}".also { binding.tvFilePath.text = it }
        }
        viewModel.droneStatus.observe(this) { status ->
            val keys = status.keys.toMutableList()
            keys.sort()

            val sb = StringBuilder()
            for (key in keys) {
                sb.append(key).append(":").append("\t\t\t").append(status[key]).append("\n")
            }
            binding.tvDroneStatus.text = sb.toString()
        }
        viewModel.simulationStatus.observe(this) {
            if (it!!) {
                binding.btnSimulation.text = "Stop Sim."
            } else {
                binding.btnSimulation.text = "Start Sim."
            }
        }
    }

    private fun updateSelectedItem() {
        var index = 0

        viewModel.geodeticPosList.value?.let {
            for (i in it.indices) {
                val pos = it[i]

                if (pos.latitude == viewModel.mapLocation.value?.latitude
                    && pos.longitude == viewModel.mapLocation.value?.longitude
                ) {
                    index = i + 1
                    break
                }
            }
        }
        binding.spinnerLocations.setSelection(index)
    }

    private fun updateBenchmarkLocation() {
        val loc = viewModel.mapLocation.value

        "Benchmark Location: ${formatLocation(loc?.latitude)} / ${formatLocation(loc?.longitude)} / ${
            formatLocation(
                loc?.height
            )
        }".also {
            binding.tvBenchmarkLocation.text = it
        }
    }

    private fun addMarkers(locations: List<GeodeticLocation>): List<Marker> {
        val markers = mutableListOf<Marker>()

        locations.forEach { loc ->
            val marker = map.addMarker(
                MarkerOptions().position(LatLng(loc.latitude, loc.longitude))
                    .title(loc.geodeticCode)
            )?.let {
                it.tag = loc
                markers.add(it)
            }
        }

        return markers
    }

    private fun initViews() {
        val dataCollections = listOf(viewModel.geodeticPosList, viewModel.trackingType)
        val spinnerViews = listOf(binding.spinnerLocations, binding.spinnerTrackingType)

        for (i in 0..dataCollections.size - 1) {
            val spinner = spinnerViews[i]
            val data = dataCollections[i]

            spinner.adapter = object : BaseAdapter() {
                override fun getCount(): Int {
                    return (data.value?.size ?: 0) + 1
                }

                override fun getItem(position: Int): Any? {
                    return if (0 == position) null else data.value?.get(position - 1)
                }

                override fun getItemId(position: Int): Long {
                    return getItem(position)?.hashCode()?.toLong() ?: 0
                }

                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup?,
                ): View? {
                    var view = convertView
                    if (view == null) {
                        view = LayoutInflater.from(this@GPSMeasurementActivity)
                            .inflate(R.layout.item_monitoring_status, parent, false)
                        view.setPadding(resources.getDimensionPixelSize(R.dimen.uxsdk_10_dp))
                        (view as TextView).setTextColor(Color.BLACK)
                    }
                    (view as TextView).let {
                        if (position == 0) {
                            it.text = "--"
                        } else {
                            val item = getItem(position)
                            it.text = (item as? GeodeticLocation)?.name ?: item.toString()
                        }
                    }

                    return view
                }
            }
        }
        binding.spinnerLocations.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val location = parent?.getItemAtPosition(position) as? GeodeticLocation?
                    viewModel.selectLocation(location)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }

            }

        binding.spinnerTrackingType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val trackingType = parent?.getItemAtPosition(position) as? String
                    viewModel.selectTrackingType(trackingType)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }

            }
        binding.btnStartRecord.setOnClickListener {
            viewModel.startOrStopRecord()
        }
        binding.btnSimulation.setOnClickListener {
            viewModel.clickSimulation()
        }

        "Log File Path: ${DJIApplication.getLogFile().absolutePath}".also {
            binding.tvLogPath.text = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uiState = MeasurementUIState(
            "Location",
            "Drone location",
            "Geo Distance",
            "Current log file path",
            "not flying",
            arrayOf(
                GeodeticLocation(name = "Loc1"),
                GeodeticLocation(name = "Loc2")
            ),
            arrayOf("Tracking Type 1", "Tracking Type 2")
        )

        setContent {
            MeasureActivity(uiState)
        }

//        binding = ActivityGpsMeasurementBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//        initViews()
//
//        binding.vMap.onCreate(savedInstanceState)
//        binding.vMap.getMapAsync {
//            map = it
//            initializeViewModel()
//        }
    }

//    override fun onStart() {
//        super.onStart()
//        binding.vMap.onStart()
//    }
//
//    override fun onResume() {
//        super.onResume()
//        binding.vMap.onResume()
//    }
//
//    override fun onPause() {
//        super.onPause()
//        binding.vMap.onPause()
//    }
//
//
//    override fun onStop() {
//        super.onStop()
//        binding.vMap.onStop()
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        binding.vMap.onDestroy()
//
//        viewModel.destroy()
//    }

//    override fun onSaveInstanceState(outState: Bundle) {
//        super.onSaveInstanceState(outState)
//        binding.vMap.onSaveInstanceState(outState)
//    }
//
//
//    override fun onLowMemory() {
//        super.onLowMemory()
//        binding.vMap.onLowMemory()
//    }

}

data class MeasurementUIState(
    var benchmarkLocation: String,
    var droneLocation: String,
    var geoDistance: String,
    var filePath: String,
    var droneStatus: String,
    var benchmarkLocations: Array<GeodeticLocation>,
    var trackingType: Array<String>
)

@Preview(
    showBackground = true,
    name = "default",
    device = "spec:width=1280dp,height=800dp,orientation=landscape"
)
@Composable
private fun MeasurementActivityPreview() {
    MeasureActivity(
        MeasurementUIState(
            "-/-/-", "-/-/-", "-", "-", "-",
            benchmarkLocations = arrayOf(GeodeticLocation(name = "Demo")),
            trackingType = arrayOf("Demo Tracking")
        )
    )
}


@Composable
private fun MeasureActivity(uiState: MeasurementUIState) {
    ComposeAppTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            ConstraintLayout(
                Modifier
                    .fillMaxSize()
                    .padding(
                        0.dp,
                        innerPadding.calculateTopPadding(),
                        0.dp,
                        innerPadding.calculateBottomPadding()
                    )
            ) {
                val (map, contentCl) = createRefs()

                createHorizontalChain(
                    map,
                    contentCl,
                    chainStyle = ChainStyle.SpreadInside,
                )

                com.google.maps.android.compose.GoogleMap(
                    modifier = Modifier.constrainAs(map) {
                        top.linkTo(parent.top)
                        start.linkTo(parent.start)
                        bottom.linkTo(parent.bottom)
                        width = Dimension.percent(0.4.toFloat())
                    }
                ) {

                }

                ConstraintLayout(modifier = Modifier.constrainAs(contentCl) {
                    start.linkTo(map.end)
                    end.linkTo(parent.end)
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                    width = Dimension.percent(0.6.toFloat())
                }.fillMaxHeight()) {
                    val (locSpinner, trackingTypeSpinner, btnRecord, btnSimulator, benchmarkRow, droneLocRow, geoDistanceRow, filePathRow, filePathValueRow, infoRow) = createRefs()

                    var isRecording by remember { mutableStateOf(false) }

                    Spinner(content = uiState.benchmarkLocations, modifier = Modifier.constrainAs(locSpinner) {
                        start.linkTo(parent.start, margin = 15.dp)
                        top.linkTo(parent.top)
                        width = Dimension.fillToConstraints
                        height = Dimension.value(50.dp)
                    })

                    Spinner(content = uiState.trackingType, modifier = Modifier.constrainAs(trackingTypeSpinner) {
                        start.linkTo(locSpinner.start)
                        top.linkTo(locSpinner.bottom, margin = 15.dp)
                        width = Dimension.fillToConstraints
                        height = Dimension.value(50.dp)
                    })

                    Button(modifier = Modifier.constrainAs(btnRecord) {
                        start.linkTo(locSpinner.end, margin = 15.dp)
                        top.linkTo(locSpinner.top)
                    }, onClick = {
                        isRecording = !isRecording
                    }) {
                        Text(text = "Start Record", color = androidx.compose.ui.graphics.Color.White)
                    }

                    Button(modifier = Modifier.constrainAs(btnSimulator) {
                        start.linkTo(btnRecord.end, margin = 15.dp)
                        top.linkTo(btnRecord.top)
                    }, onClick = {

                    }) {
                        Text(text = "Start Simulator", color = androidx.compose.ui.graphics.Color.White)
                    }

                    InfoRow(
                        "Benchmark Location", uiState.benchmarkLocation,
                        modifier = Modifier.constrainAs(benchmarkRow) {
                            start.linkTo(locSpinner.start)
                            top.linkTo(trackingTypeSpinner.bottom, margin = 15.dp)
                        })

                    InfoRow("Drone Location", uiState.droneLocation,
                        modifier = Modifier.constrainAs(droneLocRow) {
                            start.linkTo(locSpinner.start)
                            top.linkTo(benchmarkRow.bottom, margin = 15.dp)
                        })

                    InfoRow("Gap Distance", uiState.geoDistance,
                        Modifier.constrainAs(geoDistanceRow) {
                            start.linkTo(locSpinner.start)
                            top.linkTo(droneLocRow.bottom, margin = 15.dp)
                        })

                    InfoRow("GPS Records file path", "",
                        Modifier.constrainAs(filePathRow) {
                            start.linkTo(locSpinner.start)
                            top.linkTo(geoDistanceRow.bottom, margin = 15.dp)
                        })

                    InfoRow("", uiState.filePath,
                        Modifier.constrainAs(filePathValueRow) {
                            start.linkTo(locSpinner.start)
                            top.linkTo(filePathRow.bottom, margin = 15.dp)
                        })

                    Column (Modifier.constrainAs(infoRow) {
                        start.linkTo(locSpinner.start)
                        top.linkTo(filePathValueRow.bottom, margin = 15.dp)
                        bottom.linkTo(parent.bottom)
                        end.linkTo(parent.end)
                        width = Dimension.fillToConstraints
                        height = Dimension.fillToConstraints
                    }.verticalScroll(rememberScrollState())) {
                            Text(uiState.droneStatus)
                    }
                }
            }
        }
    }
}


@Composable
@Preview
private fun PreviewSpinner() {
    Spinner(arrayOf("A", "B", "C"), Modifier.wrapContentWidth())
}

@Composable
private fun InfoRow(hint: String, content: String, modifier: Modifier) {
    Row(modifier) {
        if (!hint.isEmpty()) {
            Text("$hint:", modifier = Modifier.padding(0.dp, 0.dp, 10.dp, 0.dp))
        }
        if (!content.isEmpty()) {
            Text(content)
        }
    }
}

@Composable
private fun Spinner(content: Array<*>, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf(content[0]) }

    Box(modifier = modifier) {
        val innerModifier = Modifier
            .wrapContentWidth()
            .clickable { expanded = true }
            .padding(15.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = innerModifier
        ) {
            Text(text = selectedItem.toString())
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content.forEach { item ->
                DropdownMenuItem(onClick = {
                    selectedItem = item
                    expanded = false
                }) {
                    Text(text = item.toString())
                }
            }
        }
    }
}