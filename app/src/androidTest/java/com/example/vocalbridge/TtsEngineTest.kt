package com.example.vocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalbridge.tts.TtsEngineManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class TtsEngineTest {

    private val TAG = "TtsEngineTest"

    @Test
    fun testTtsInitializationAndGeneration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        Log.i(TAG, "Initializing TTS Engine...")
        TtsEngineManager.initialize(context)

        assertTrue("TTS Engine should be initialized", TtsEngineManager.isInitialized)
        val tts = TtsEngineManager.tts
        assertNotNull("OfflineTts instance should not be null", tts)

        val sampleRate = tts!!.sampleRate()
        val numSpeakers = tts.numSpeakers()
        Log.i(TAG, "Engine ready: sampleRate=$sampleRate, numSpeakers=$numSpeakers")
        assertTrue("Sample rate should be positive", sampleRate > 0)
        assertTrue("Number of speakers should be positive", numSpeakers > 0)

        val genConfig = GenerationConfig(
            silenceScale = 0.2f,
            speed = 1.0f,
            sid = 9 // bm_george
        )

        var callbackCount = 0
        var totalSamples = 0

        fun localCallback(samples: FloatArray): Int {
            callbackCount++
            totalSamples += samples.size
            Log.d(TAG, "Callback received chunk: size=${samples.size}, totalSamples=$totalSamples")
            return 1 // Continue
        }

        Log.i(TAG, "Starting text synthesis generation...")
        val audio = tts.generateWithConfigAndCallback(
            text = "This is an automated test for the text to speech engine.",
            config = genConfig,
            callback = ::localCallback
        )

        Log.i(TAG, "Generation complete. callbackCount=$callbackCount, totalSamples=$totalSamples")
        assertTrue("Callback should be invoked at least once", callbackCount > 0)
        assertTrue("Total generated samples should be greater than zero", totalSamples > 0)
        assertNotNull("Generated audio result should not be null", audio)
        assertEquals("Generated audio samples count should match total callback samples", totalSamples, audio.samples.size)
    }
}
