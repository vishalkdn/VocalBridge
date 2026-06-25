package com.example.vocalbridge.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Activity that responds to GET_SAMPLE_TEXT intent.
 * Returns sample text for the Android TTS settings preview.
 */
class GetSampleText : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent()
        result.putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            "Good morning. This is George, your British text to speech engine, powered by Sherpa ONNX and the Kokoro model. I can read books, articles, and any text on your device with natural sounding speech."
        )

        setResult(RESULT_OK, result)
        finish()
    }
}
