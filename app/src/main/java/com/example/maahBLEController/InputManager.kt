package com.example.maahBLEController

import android.content.Context
import android.util.Log
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject


//need the custom button from Claude
// InputManager needs to be in UI - when button is pressed/released buttonState is released
class InputManager(
    private val context : Context,
    private val scope: CoroutineScope,
    private val onStep: () -> Unit,
    private val onReport : (String) -> Unit
    ) {

    private var accelerometer = Accelerometer(
        context = context,
        sensorDelay = SensorDelay.GAME
    )
    private var linearAccelerometer = LinearAccelerometer(
        context = context,
        sensorDelay = SensorDelay.GAME
    )
    private var gravity = Gravity(
        context = context,
        sensorDelay = SensorDelay.FASTEST
    )
    private var stepDetector = ManualStepDetector(
        linearAccelerometer = linearAccelerometer,
        gravity = gravity, 
        onStep = { onStep() }
    )


    private var reportingJob: Job? = null
    private val REPORTING_INTERVAL_MS = 50L

    private lateinit var buttons : List<ButtonConfig>

    private val buttonStates = mutableMapOf<String, Boolean>()
    fun setButtons(buttons: List<ButtonConfig>) {
        this.buttons = buttons
        buttonStates.clear()
        buttons.forEach { button ->
            if (!buttonStates.containsKey(button.text)) {
                buttonStates[button.text] = false
            }
        }
    }
    private var currentPitch = 0.0
    private var rollReportingEnabled = true
    private var currentRoll = 0.0
    private var currentGravity: List<Float> = listOf(0f,0f,0f)
    private var currentLinearAccel = listOf(0f,0f,0f)
    private var rollOffset = 0.0
    private var rawRoll = 0.0
    private var isCalibrated = false
    private fun calculatePitch(x: Float, y: Float, z: Float): Double {
        return atan2(-y.toDouble(), sqrt(x * x + z * z).toDouble())
    }

    private fun calculateRoll(x: Float, y: Float, z: Float): Double {
        return atan2(z.toDouble(), x.toDouble())
    }

    fun updateButtonState(buttonText: String, isPressed: Boolean){
        buttonStates[buttonText] = isPressed
    }

    fun startReportingLoop() {
        if(reportingJob?.isActive == true) return
        Log.d("InputManager","Reporting loop started")
        reportingJob?.cancel()
        reportingJob = scope.launch {
            Log.d("InputManager","Reporting loop running")
            while (isActive) {
                val controllerState = JSONObject()
                try{
                    controllerState.put("stepping",stepDetector.isCurrentlyStepping)
                    controllerState.put("roll", if (rollReportingEnabled) currentRoll else 0.0)
                    controllerState.put("pitch",currentPitch)
                    val buttonArray = JSONArray()
                    buttonStates.forEach {
                        (name, isPressed) ->
                        val buttonObj = JSONObject()
                        buttonObj.put("name",name)
                        buttonObj.put("pressed", isPressed)
                        buttonArray.put(buttonObj)
                    }
                    controllerState.put("buttons", buttonArray)
                    sendChunked(controllerState.toString())
                } catch (e: Exception){
                    Log.e("InputManager", "JSON Error: ${e.message}")
                    e.printStackTrace()
                }
                delay(REPORTING_INTERVAL_MS)
            }
            Log.d("InputManager","Reporting loop finished")

        }
    }

    private val CHUNK_SIZE = 400

    private fun sendChunked(json: String){
        val bytes = json.toByteArray()
        if (bytes.size <= CHUNK_SIZE){
            onReport(json)
            return
        }

        val chunks = bytes.toList().chunked(CHUNK_SIZE)
        val total = chunks.size
        chunks.forEachIndexed { index, chunk ->
            val prefix = when {
                index == 0 -> "START: $total:"
                index == total - 1 -> "END:"
                else -> "CHUNK:$index:"
            }
            onReport(prefix + String(chunk.toByteArray()))
        }
    }

    public fun startListening() {
        accelerometer.startListening()
        linearAccelerometer.startListening()
        gravity.startListening()
    }

    public fun stopListening() {
        accelerometer.stopListening()
        linearAccelerometer.stopListening()
        gravity.stopListening()
    }

    fun setupSensors(){
        accelerometer.setOnSensorValuesChangedListener { values ->
            currentPitch = calculatePitch(values[0], values[1], values[2])
            rawRoll = calculateRoll(values[0], values[1], values[2])
            if (!isCalibrated) {
                rollOffset = rawRoll
                isCalibrated = true
            }
            currentRoll = rawRoll - rollOffset
        }
        linearAccelerometer.setOnSensorValuesChangedListener { values ->
            currentLinearAccel = values
            stepDetector.updateValues(currentGravity,currentLinearAccel)}
        gravity.setOnSensorValuesChangedListener { values -> currentGravity = values}

    }

    fun recenter() {
        rollOffset = rawRoll
    }

    fun resetCalibration() {
        isCalibrated = false
    }

    fun configureRollGate(hasRollHoldButton: Boolean) {
        rollReportingEnabled = !hasRollHoldButton
    }

    fun setRollHold(isHeld: Boolean) {
        rollReportingEnabled = isHeld
        if (isHeld) {
            recenter()
        }
    }

}