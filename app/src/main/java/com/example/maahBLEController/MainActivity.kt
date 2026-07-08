package com.example.maahBLEController
import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.activity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.maahBLEController.ui.theme.BLEAdvertiseTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import java.util.UUID

private const val PERMISSION_REQUEST_CODE = 1
// Example UUIDs — generate your own unique ones if needed
private val phoneIOServiceUuid: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")

private val inputCharUUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")
private val pauseUUID: UUID = UUID.fromString("446be5b0-93b7-4911-abbe-e4e18d545640")
private val screenshotUUID: UUID = UUID.fromString("36d942a6-9e79-4812-8a8f-84a275f6b176")
private val controlCharUUID: UUID = UUID.fromString("4a55006e-990a-4737-9634-133466ef8e35") // UUID for sending control messages (file transfer start and stop)
private val CCCDUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // UUID for notifying on characteristic
private val heartbeatCharUUID: UUID = UUID.fromString("a5307aef-3109-42f7-b79e-a493856823ba")
private val fileTransferCharUUID: UUID = UUID.fromString("efcdbf7b-fee2-489b-8f79-b649aa50619b") // UUID for transfering layouts and images
private val stepCharUUID: UUID = UUID.fromString("c36f600d-a202-48cd-a839-7577abea4b1f")

enum class ConnectionState { IDLE, CONNECTED, DISCONNECTED}
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private var inputCharacteristic: BluetoothGattCharacteristic? = null
    private var pauseCharacteristic: BluetoothGattCharacteristic? = null
    private var screenshotCharacteristic: BluetoothGattCharacteristic? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var layoutCharacteristic: BluetoothGattCharacteristic? = null
    private var heartbeatCharacteristic: BluetoothGattCharacteristic? = null
    private var stepCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    private val inputManager by lazy {
        InputManager (
            context = this,
            scope = this.lifecycleScope,
            onStep = {
                writeToChar("Step",stepCharUUID, confirm = false)},
            onReport = {
                jsonString -> writeToChar(jsonString, inputCharUUID, confirm = false) }
    )
}
    private var isGattServerSetup = false
    private lateinit var appContext: Context

    private val bluetoothEnablingResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ){
        result ->
        if(result.resultCode != RESULT_OK){
            promptEnableBluetooth()
        }
    }
    var uiLayout by mutableStateOf(
        UIConfig(
            emptyList(),
            emptyList(),
            ""))
    var layoutRefreshKey by mutableStateOf(0)
    var connectionState by mutableStateOf(ConnectionState.IDLE)
    private var lastPingTime = 0L
    private var heartbeatMonitorJob: Job? = null
    private lateinit var fileReceiver: FileReceiver
//    private fun copyDefaultLayout(){
//        val targetFile = File(filesDir,"DefaultLayout.layout")
//        assets.open("DefaultLayout.layout").use {
//            input -> targetFile.outputStream().use {
//                output -> input.copyTo(output)
//        }
//        }
//    }

    private fun loadLayout(filename: String){
        uiLayout = LayoutParser(filename,this).apply {readJSON()}.getNewUI()
        inputManager.setButtons(uiLayout.buttons)
        inputManager.configureRollGate(uiLayout.buttons.any { it.type == "rollHold" })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        fileReceiver = FileReceiver(applicationContext)

        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }

        //for android 10 and below - BLE requires location perms
        //combine with BLE perms later
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        inputManager.startReportingLoop()

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        startAdvertising()
        enableEdgeToEdge()
        setContent {
            BLEAdvertiseTheme {
                val navController = rememberNavController()
                val context = LocalContext.current

                LaunchedEffect(connectionState) {
                    if (connectionState == ConnectionState.CONNECTED) {
                        Toast.makeText(context, "Connected to PC", Toast.LENGTH_SHORT).show()
                    }
                }

                if (connectionState == ConnectionState.DISCONNECTED) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Disconnected") },
                        text = { Text("The PC server disconnected unexpectedly.") },
                        confirmButton = {
                            Button(onClick = {
                                connectionState = ConnectionState.IDLE
                                navController.popBackStack("home", inclusive = false)
                            }) { Text("OK") }
                        }
                    )
                }

                NavHost(navController = navController, startDestination = "home"){
                    composable("home"){
                        HomeScreen(
                            context = appContext,
                            refreshKey = layoutRefreshKey,
                            onLayoutSelected = {
                                filename -> loadLayout(filename)
                                navController.navigate("controller")
                            }
                        )
                    }

                    composable("controller"){
                        DisposableEffect(Unit){
                            val originalOrientation = this@MainActivity.requestedOrientation
                            this@MainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                            onDispose {
                                this@MainActivity.requestedOrientation = originalOrientation
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                        AdvertiseScreen(
                            uiLayout = uiLayout,
                            onButtonStateChanged = {
                                name, isPressed ->
                                val btn = uiLayout.buttons.find {it.text == name }
                                when (btn?.type) {
                                    "screenshot" -> if (isPressed) writeToChar("Screenshot", screenshotUUID, confirm = false)
                                    "pause" -> if (isPressed) writeToChar("Pause", pauseUUID, confirm = false)
                                    "recenter" -> if(isPressed) inputManager.recenter()
                                    "rollHold" -> inputManager.setRollHold(isPressed)
                                    else -> inputManager.updateButtonState(name, isPressed)
                                }
                            }
                        )

                        BackHandler() {
                            navController.popBackStack()
                        }
                    }

                }

            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
        inputManager.setupSensors()
        inputManager.startListening()
        inputManager.resetCalibration()

    }

    override fun onPause() {
        super.onPause()
        writeToChar("Pause", pauseUUID, confirm = false)
        inputManager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectedDevice?.let { bluetoothGattServer?.cancelConnection(it)}
        bluetoothGattServer?.close()
        bluetoothGattServer = null
        isGattServerSetup = false
        bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private fun Activity.requestRelevantRuntimePermissions() {
        if(hasRequiredBluetoothPermissions()) {return}
        when{
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                requestBluetoothPermissions()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermissions() = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth permission required")
            .setMessage("Starting from Android 12, the system requires apps to be granted " +
                    "Bluetooth access in order to scan for and connect to BLE devices.")
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
            .show()
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val containsPermanentDenial = permissions.zip(grantResults.toTypedArray()).any {
            it.second == PackageManager.PERMISSION_DENIED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, it.first)
        }

        val containsDenial = grantResults.any {it == PackageManager.PERMISSION_DENIED}
        val allGranted = grantResults.all {it == PackageManager.PERMISSION_GRANTED }
        when {
            containsPermanentDenial -> {
                AlertDialog.Builder(this)
                    .setTitle("Permission Denied")
                    .setMessage("Cannot scan for BLE devices without the required permissions.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            containsDenial -> {
                requestRelevantRuntimePermissions()
            }
            allGranted && hasRequiredBluetoothPermissions() -> {
                startAdvertising()
            }
            else -> {
                recreate()
            }
        }
    }

    private fun promptEnableBluetooth(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        {
            return
        }
        if(!bluetoothAdapter.isEnabled){
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                bluetoothEnablingResult.launch(this)
            }
        }
    }

    private fun setupGattServer(){
        if (isGattServerSetup) {
            Log.w("BLE", "GATT server already set up")
            return
        }
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        inputCharacteristic = BluetoothGattCharacteristic(
            inputCharUUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        inputCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))

        pauseCharacteristic = BluetoothGattCharacteristic(
            pauseUUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        pauseCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))

        screenshotCharacteristic = BluetoothGattCharacteristic(
            screenshotUUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        screenshotCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))

        messageCharacteristic = BluetoothGattCharacteristic(
            controlCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                    BluetoothGattCharacteristic.PERMISSION_READ
        )
        messageCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)
        )

        layoutCharacteristic = BluetoothGattCharacteristic(
            fileTransferCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        heartbeatCharacteristic = BluetoothGattCharacteristic(
            heartbeatCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        heartbeatCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        ))

        stepCharacteristic = BluetoothGattCharacteristic(
            stepCharUUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        stepCharacteristic?.addDescriptor(BluetoothGattDescriptor(
            CCCDUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        ))
        val phoneIOService = BluetoothGattService(
            phoneIOServiceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)
        //add the characteristic to the service
        phoneIOService.addCharacteristic(inputCharacteristic)
        phoneIOService.addCharacteristic(pauseCharacteristic)
        phoneIOService.addCharacteristic(screenshotCharacteristic)
        phoneIOService.addCharacteristic(messageCharacteristic)
        phoneIOService.addCharacteristic(layoutCharacteristic)
        phoneIOService.addCharacteristic(heartbeatCharacteristic)
        phoneIOService.addCharacteristic(stepCharacteristic)
        bluetoothGattServer?.addService(phoneIOService)

        isGattServerSetup = true
    }
    private fun startHeartbeatMonitor() {
        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (System.currentTimeMillis() - lastPingTime > 8000) {
                    if (connectionState == ConnectionState.CONNECTED) {
                        connectionState = ConnectionState.DISCONNECTED
                    }
                }
                break
            }
        }
    }


    private fun restartGattServer(){
        if(isGattServerSetup){
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            isGattServerSetup = false
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            setupGattServer()
            startAdvertising()
            Log.d("BLE","GATT Server restarted")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                lastPingTime = System.currentTimeMillis()
                connectionState = ConnectionState.CONNECTED
                startHeartbeatMonitor()
                Log.i("GATT", "Device connected: ${device.address}")
            } else {
                connectedDevice = null
                heartbeatMonitorJob?.cancel()
                connectionState = ConnectionState.DISCONNECTED
                restartGattServer()
                Log.i("GATT", "Device disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                data
                )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.i("GATT", "onDescriptorWriteRequest: ${descriptor.uuid}")

            if (descriptor.uuid == CCCDUUID) {
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        value
                    )
                    Log.i("GATT", "Notification subscription confirmed")
                }
            } else {
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            var status = BluetoothGatt.GATT_SUCCESS
            try {
                when(characteristic?.uuid){
                    controlCharUUID -> {
                        val message = String(value!!, Charsets.UTF_8)
                        when{
                            message.startsWith("START") -> {
                                fileReceiver.handleStart(message = message)
                            }
                            message.startsWith("CHECKSUM") -> {
                                val receivedChecksum = message.substringAfter(":").toLong()
                                val acknowledgement = fileReceiver.handleCRC(receivedChecksum = receivedChecksum)
                                writeToChar(acknowledgement, controlCharUUID,confirm = true)
                                Log.d("FTP","Notifying: $acknowledgement")
                            }
                            message.startsWith("END") -> {
                                fileReceiver.handleEnd()
                                if(fileReceiver.isTransferComplete()){
                                    if(fileReceiver.getFilename().endsWith(".layout")){
                                        runOnUiThread {
                                            loadLayout(filename = fileReceiver.getFilename())
                                            layoutRefreshKey++
                                        }
                                    }
                                }
                            }
                            else -> {
                                Log.d("FTP","Error processing control message.")
                            }
                        }
                    }
                    fileTransferCharUUID -> {
                        fileReceiver.handleFileTransfer(value)
                    }
                    heartbeatCharUUID -> {
                        lastPingTime = System.currentTimeMillis()
                        writeToChar("PONG", heartbeatCharUUID, confirm = false)
                    }
                    else -> {
                        Log.d("BLE", "Write to unknown characteristic")
                    }
                }
            } catch (e: Exception) {
                status = BluetoothGatt.GATT_FAILURE
                Log.d("BLE","Error writing to characteristic: $e")
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
    }
    private var isAdvertising by mutableStateOf(false)
    private fun startAdvertising() {
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantRuntimePermissions()
        } else {
            if (!bluetoothAdapter.isMultipleAdvertisementSupported) return
            setupGattServer()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(phoneIOServiceUuid))
                .build()

            bluetoothAdapter.bluetoothLeAdvertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
            )
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.i("BLE", "Advertising started")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLE", "Advertising failed: $errorCode")
            isAdvertising = false
        }
    }

// Input / BLE
    private fun writeToChar(text: String, uuid: UUID, confirm: Boolean){
        val data = text.toByteArray()
        val characteristic = when(uuid){
            inputCharUUID -> inputCharacteristic
            pauseUUID -> pauseCharacteristic
            screenshotUUID -> screenshotCharacteristic
            controlCharUUID -> messageCharacteristic
            heartbeatCharUUID -> heartbeatCharacteristic
            stepCharUUID -> stepCharacteristic
            else -> null
        }
        connectedDevice?.let {device ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic!!,
                    confirm,
                    data
                )
            } else {
                characteristic?.value = data
                bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
            }
        }
    }

    fun hideSystemUI(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            val controller = WindowInsetsControllerCompat(window,window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }else{
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) hideSystemUI()
    }



}

fun Context.hasPermission(permissionType: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permissionType) ==
            PackageManager.PERMISSION_GRANTED
}

fun Context.hasRequiredBluetoothPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ){
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}



@Composable
fun AdvertiseScreen(
    onButtonStateChanged: (String, Boolean) -> Unit,
    uiLayout: UIConfig,
) {
    PixelLayout(uiLayout,onButtonStateChanged)
}

//@Preview(
//    showBackground = true,
//    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420"
//)
//@Composable
//fun AdvertiseApp(){
//    val buttonsList = listOf(
//    ButtonConfig("Fire", 50, 50, 0.25f, 0.1f,),
//    ButtonConfig("Pause", 200, 200, 0.5f, 0.5f,))
//
//    AdvertiseScreen(
//        sendPressed = {},
//        buttonsList
//    )
//}
