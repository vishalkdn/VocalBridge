package com.example.vocalbridge.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File

/**
 * Activity that responds to CHECK_TTS_DATA intent.
 * Android TTS system calls this to verify voice data is available.
 *
 * Returns RESULT_OK with available voices if model files are present,
 * or triggers data installation if they're not.
 */
class CheckVoiceData : Activity() {

    companion object {
        private const val TAG = "CheckVoiceData"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Checking voice data availability")

        val modelDir = File(filesDir, "kokoro")
        val modelFile = File(modelDir, "model.onnx")
        val voicesFile = File(modelDir, "voices.bin")
        val tokensFile = File(modelDir, "tokens.txt")
        val espeakDir = File(modelDir, "espeak-ng-data")

        val resultIntent = Intent()

        if (modelFile.exists() && voicesFile.exists() && tokensFile.exists() && espeakDir.exists()) {
            Log.i(TAG, "Voice data is available")

            // Report available voices
            val availableVoices = ArrayList<String>()
            availableVoices.add("eng-USA")
            availableVoices.add("eng-GBR")
            availableVoices.add("eng-US")
            availableVoices.add("eng-GB")
            resultIntent.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                availableVoices
            )

            val unavailableVoices = ArrayList<String>()
            resultIntent.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                unavailableVoices
            )

            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        } else {
            Log.w(TAG, "Voice data not yet extracted. Model exists: ${modelFile.exists()}")

            // Data needs to be installed (will happen when service starts)
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_MISSING_DATA, resultIntent)
        }

        finish()
    }
}
