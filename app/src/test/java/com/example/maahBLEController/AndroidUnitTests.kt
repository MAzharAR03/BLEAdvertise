package com.example.maahBLEController

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class AndroidUnitTests {

    private lateinit var context: Context
    private lateinit var detector: ManualStepDetector

    // Constructor params are unused by the detector, but the type requires them.
    private fun newDetector(onStep: (() -> Unit)? = null) = ManualStepDetector(
        linearAccelerometer = LinearAccelerometer(context, SensorDelay.GAME),
        gravity = Gravity(context, SensorDelay.FASTEST),
        onStep = onStep
    )

    /** Feed one rise-then-fall vertical acceleration peak. Gravity is unit-Z,
     *  so verticalAccel equals the linear-accel Z component directly. */
    private fun feedPeak(d: ManualStepDetector, peak: Float) {
        d.updateValues(listOf(0f, 0f, 9.81f), listOf(0f, 0f, 2.0f))   // rising baseline
        d.updateValues(listOf(0f, 0f, 9.81f), listOf(0f, 0f, peak))   // peak
        d.updateValues(listOf(0f, 0f, 9.81f), listOf(0f, 0f, 1.5f))   // fall -> peak evaluated
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        detector = newDetector()
    }

    // ---------------------------------------------------------------- UT-01
    @Test
    fun ut01_peakAboveThresholdCountsOneStep() {
        feedPeak(detector, 8.0f)                       // above initial threshold 6.0
        assertEquals(1, detector.stepCounter.value)
        assertTrue(detector.isCurrentlyStepping)
    }

    // ---------------------------------------------------------------- UT-02
    @Test
    fun ut02_subNoiseAccelerationIgnored() {
        // Everything at or below NOISE_THRESHOLD (1.0) is filtered out.
        repeat(50) {
            detector.updateValues(listOf(0f, 0f, 9.81f), listOf(0f, 0f, 0.8f))
        }
        assertEquals(0, detector.stepCounter.value)
    }

    // ---------------------------------------------------------------- UT-03
    @Test
    fun ut03_peaksWithinStepIntervalDebounced() {
        feedPeak(detector, 9.0f)
        feedPeak(detector, 9.0f)                       // arrives well inside 250 ms
        assertEquals(1, detector.stepCounter.value)
    }

    // ---------------------------------------------------------------- UT-04
    @Test
    fun ut04_adaptiveThresholdClampedAtUpperBound() {
        // 20 huge peaks would push the running-average threshold to 28,
        // but it must clamp at 15. Verified behaviourally: a 14 peak is
        // rejected, a 16 peak is accepted.
        repeat(20) {
            feedPeak(detector, 40.0f)
            Thread.sleep(260)                          // clear the step debounce
        }
        val before = detector.stepCounter.value

        Thread.sleep(260)
        feedPeak(detector, 14.0f)                      // below clamped threshold
        assertEquals(before, detector.stepCounter.value)

        Thread.sleep(260)
        feedPeak(detector, 16.0f)                      // above clamped threshold
        assertEquals(before + 1, detector.stepCounter.value)
    }

    // ---------------------------------------------------------------- UT-07
    @Test
    fun ut05_pitchAndRollDerivedFromAccelerometerAxes() {
        val manager = InputManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onStep = {},
            onReport = {}
        )

        val pitchFn = InputManager::class.java
            .getDeclaredMethod("calculatePitch",
                Float::class.java, Float::class.java, Float::class.java)
            .apply { isAccessible = true }
        val rollFn = InputManager::class.java
            .getDeclaredMethod("calculateRoll",
                Float::class.java, Float::class.java, Float::class.java)
            .apply { isAccessible = true }

        val g = 9.81f
        val tol = 1e-6

        // Phone flat on table: gravity entirely on Z.
        // pitch = atan2(-0, sqrt(0 + g²)) = 0
        // roll  = atan2(g, 0)             = +90°
        assertEquals(0.0, pitchFn.invoke(manager, 0f, 0f, g) as Double, tol)
        assertEquals(PI / 2, rollFn.invoke(manager, 0f, 0f, g) as Double, tol)

        // Phone tilted top-edge down: gravity entirely on -Y.
        // pitch = atan2(g, 0) = +90°, roll axis undisturbed by y so roll = atan2(0, 0)...
        // use z slightly nonzero-free case: roll = atan2(0, 0) is undefined, so check pitch only
        assertEquals(PI / 2, pitchFn.invoke(manager, 0f, -g, 0f) as Double, tol)

        // Phone on its side: gravity entirely on X.
        // pitch = atan2(0, g) = 0, roll = atan2(0, g) = 0
        assertEquals(0.0, pitchFn.invoke(manager, g, 0f, 0f) as Double, tol)
        assertEquals(0.0, rollFn.invoke(manager, g, 0f, 0f) as Double, tol)

        // 45° tilt about the pitch axis: y = -g·sin45, z = g·cos45.
        assertEquals(PI / 4, pitchFn.invoke(manager, 0f,
            (-g * sin(PI / 4)).toFloat(), (g * cos(PI / 4)).toFloat()) as Double, tol)
    }

    @Test
    fun ut07_payloadsOver400BytesAreChunkedWithFraming() {
        val reports = mutableListOf<String>()
        val manager = InputManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onStep = {},
            onReport = { reports.add(it) }
        )

        // sendChunked is private — invoke via reflection to test the framing.
        val send = InputManager::class.java
            .getDeclaredMethod("sendChunked", String::class.java)
            .apply { isAccessible = true }

        val payload = "x".repeat(950)                  // 3 chunks at CHUNK_SIZE 400
        send.invoke(manager, payload)

        assertEquals(3, reports.size)
        assertTrue(reports[0].startsWith("START: 3:"))
        assertTrue(reports[1].startsWith("CHUNK:1:"))
        assertTrue(reports[2].startsWith("END:"))

        // Reassembling the framed chunks must restore the original payload,
        // mirroring DeviceBLE.input_handler on the PC side.
        val rebuilt = reports.joinToString("") { it.substringAfter(':').substringAfter(':') }
            .let { reports[0].removePrefix("START: 3:") +
                   reports[1].removePrefix("CHUNK:1:") +
                   reports[2].removePrefix("END:") }
        assertEquals(payload, rebuilt)

        // Small payloads pass through unframed.
        reports.clear()
        send.invoke(manager, "small")
        assertEquals(listOf("small"), reports)
    }
}