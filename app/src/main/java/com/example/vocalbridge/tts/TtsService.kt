package com.example.vocalbridge.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Android TextToSpeechService implementation using Sherpa-ONNX with Kokoro model.
 *
 * Key optimization: Uses generateWithConfigAndCallback to stream audio chunks
 * to SynthesisCallback.audioAvailable() during inference, rather than waiting
 * for full synthesis to complete. This dramatically reduces time-to-first-audio.
 *
 * Text is split into sentences and each sentence is synthesized individually,
 * enabling the system to begin playing audio within ~100ms of the request.
 */
class TtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "TtsService"
    }

    // Flag to signal the generation callback to stop (when onStop is called)
    @Volatile
    private var stopRequested = false

    private var currentLanguage = "eng"
    private var currentCountry = "USA"
    private var currentVariant = ""

    // Continuous Playback Mode fields
    private var continuousPlaybackJob: Job? = null
    private val continuousTextChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var continuousAudioTrack: AudioTrack? = null
    private val continuousScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        Log.i(TAG, "onCreate TTS service")
        super.onCreate()

        // Pre-load language support
        onLoadLanguage("eng", "GBR", "")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy TTS service")
        super.onDestroy()
        // Don't release the engine here — other components may be using it
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onIsLanguageAvailable: lang=$lang, country=$country, variant=$variant")
        val l = lang ?: ""
        if (l.equals("eng", ignoreCase = true) || l.equals("en", ignoreCase = true)) {
            val c = country ?: ""
            val v = variant ?: ""
            return if (c.isNotEmpty() && v.isNotEmpty()) {
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            } else if (c.isNotEmpty()) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        Log.d(TAG, "onGetLanguage called: currentLanguage=$currentLanguage, currentCountry=$currentCountry, currentVariant=$currentVariant")
        return arrayOf(currentLanguage, currentCountry, currentVariant)
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onLoadLanguage: lang=$lang, country=$country, variant=$variant")
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = lang ?: "eng"
            currentCountry = country ?: ""
            currentVariant = variant ?: ""
        }
        return result
    }

    override fun onStop() {
        Log.i(TAG, "onStop called — requesting generation stop")
        stopRequested = true
        
        if (TtsEngineManager.continuousPlaybackMode) {
            continuousPlaybackJob?.cancel()
            continuousPlaybackJob = null
            // Drain channel
            while (continuousTextChannel.tryReceive().isSuccess) {}
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        Log.i(TAG, "onSynthesizeText request received")
        if (request == null || callback == null) return

        val text = request.charSequenceText?.toString() ?: request.text ?: return
        if (text.isBlank()) {
            // Don't call callback.done() without callback.start() — causes "Bad audio format 0" crash
            return
        }

        stopRequested = false

        // Ensure engine is initialized. Since this runs on the system's background synthesis thread,
        // it is safe to block here until the engine is fully initialized.
        try {
            runBlocking {
                TtsEngineManager.initialize(applicationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS engine during synthesis request", e)
            callback.error()
            return
        }

        val tts = TtsEngineManager.tts
        if (tts == null) {
            Log.e(TAG, "TTS engine not initialized")
            callback.error()
            return
        }

        val sampleRate = tts.sampleRate()

        // Start the audio stream
        val result = callback.start(
            sampleRate,
            AudioFormat.ENCODING_PCM_16BIT,
            1 // mono
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "callback.start() failed with result: $result")
            callback.error()
            return
        }

        // Get speed from request bundle or use our default
        // The Android system passes speed as a float via the "speed" bundle key
        val requestedSpeed = TtsEngineManager.speed

        if (TtsEngineManager.continuousPlaybackMode) {
            stopRequested = false
            ensureContinuousPlaybackJobRunning(sampleRate)
            // Send text to our internal pipeline and immediately tell Android we are done
            continuousTextChannel.trySend(text)
            callback.done()
            Log.d(TAG, "Sent text to continuous playback queue and returned early")
            return
        }

        // Split text into sentences for incremental synthesis
        val sentences = SentenceSplitter.split(text)
        Log.d(TAG, "Synthesizing ${sentences.size} sentences from ${text.length} chars")

        val genConfig = GenerationConfig(
            // Lower silence scale = less silence between words
            silenceScale = 0.2f,
            speed = TtsEngineManager.speed,
            sid = TtsEngineManager.speakerId
        )

        // Run producer and consumer in a coroutine scope
        kotlinx.coroutines.runBlocking {
            val audioChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 50)

            // Consumer coroutine: Reads chunks from channel and writes to Android TTS System
            val consumerJob = launch(Dispatchers.IO) {
                for (chunk in audioChannel) {
                    if (stopRequested) break
                    
                    var offset = 0
                    while (offset < chunk.size) {
                        if (stopRequested) break
                        var chunkSize = minOf(callback.maxBufferSize, chunk.size - offset)
                        if (chunkSize % 2 != 0) chunkSize -= 1 // Ensure even byte alignment
                        
                        val result = callback.audioAvailable(chunk, offset, chunkSize)
                        if (result == TextToSpeech.ERROR) {
                            stopRequested = true
                            break
                        }
                        offset += chunkSize
                    }
                }
            }

            for ((index, sentence) in sentences.withIndex()) {
                if (stopRequested) {
                    Log.i(TAG, "Stop requested, aborting at sentence $index")
                    break
                }

                Log.d(TAG, "Synthesizing sentence $index/${sentences.size}: \"${sentence.take(50)}...\"")

                try {
                    val callback = object : Function1<FloatArray, Int> {
                        override fun invoke(samples: FloatArray): Int {
                            if (stopRequested) return 0

                            if (samples.isNotEmpty()) {
                                val trimmedSamples = trimSilence(samples, sampleRate)
                                val pcmBytes = floatArrayToPcm16(trimmedSamples)
                                kotlinx.coroutines.runBlocking {
                                    audioChannel.send(pcmBytes)
                                }
                            }
                            return 1 // Continue generation
                        }
                    }

                    tts.generateWithConfigAndCallback(
                        text = sentence,
                        config = genConfig,
                        callback = callback
                    )

                    if (index < sentences.size - 1 && !stopRequested) {
                        val lastChar = sentence.trim().lastOrNull()
                        val gapMs = when (lastChar) {
                            '.', '!', '?' -> 250
                            ';', ':' -> 150
                            ',' -> 150
                            else -> 50
                        }
                        val gapFrames = (sampleRate * (gapMs / 1000.0)).toInt()
                        val gapBytes = ByteArray(gapFrames * 2)
                        audioChannel.send(gapBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error synthesizing sentence $index: ${e.message}", e)
                }
            }
            
            audioChannel.close()
            consumerJob.join()
        }

        callback.done()
        Log.d(TAG, "Synthesis complete")
    }

    private fun ensureContinuousPlaybackJobRunning(sampleRate: Int) {
        if (continuousPlaybackJob?.isActive == true) return

        continuousPlaybackJob = continuousScope.launch {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            continuousAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            continuousAudioTrack?.play()

            val audioChannel = Channel<ByteArray>(capacity = 100)

            val consumerJob = launch {
                try {
                    for (pcmBytes in audioChannel) {
                        if (stopRequested) break
                        val track = continuousAudioTrack ?: break
                        track.write(pcmBytes, 0, pcmBytes.size)
                        try {
                            if (track.state == AudioTrack.STATE_INITIALIZED &&
                                track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                track.play()
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    try {
                        continuousAudioTrack?.stop()
                        continuousAudioTrack?.release()
                    } catch (_: Exception) {}
                    continuousAudioTrack = null
                }
            }

            try {
                for (textBlock in continuousTextChannel) {
                    if (stopRequested) break
                    val tts = TtsEngineManager.tts ?: continue
                    val sentences = SentenceSplitter.split(textBlock)
                    
                    val genConfig = GenerationConfig(
                        silenceScale = 0.2f,
                        speed = TtsEngineManager.speed,
                        sid = TtsEngineManager.speakerId
                    )

                    for ((index, sentence) in sentences.withIndex()) {
                        if (stopRequested) break

                        val callback = object : Function1<FloatArray, Int> {
                            override fun invoke(samples: FloatArray): Int {
                                if (stopRequested) return 0
                                if (samples.isNotEmpty()) {
                                    val trimmedSamples = trimSilence(samples, sampleRate)
                                    val pcmBytes = floatArrayToPcm16(trimmedSamples)
                                    runBlocking { audioChannel.send(pcmBytes) }
                                }
                                return 1
                            }
                        }

                        tts.generateWithConfigAndCallback(
                            text = sentence,
                            config = genConfig,
                            callback = callback
                        )

                        if (index < sentences.size - 1 && !stopRequested) {
                            val gapMs = 150
                            val gapFrames = (sampleRate * (gapMs / 1000.0)).toInt()
                            audioChannel.send(ByteArray(gapFrames * 2))
                        }
                    }

                    if (!stopRequested) {
                        val gapMs = 500
                        val gapFrames = (sampleRate * (gapMs / 1000.0)).toInt()
                        audioChannel.send(ByteArray(gapFrames * 2))
                    }
                }
            } finally {
                audioChannel.close()
                consumerJob.join()
            }
        }
    }

    /**
     * Convert FloatArray of audio samples (range -1.0 to 1.0) to
     * Convert FloatArray of audio samples (range -1.0 to 1.0) or [-32768, 32767]
     * to PCM 16-bit little-endian byte array.
     */
    private fun floatArrayToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        var maxAbs = 0.0f
        for (s in samples) {
            val abs = kotlin.math.abs(s)
            if (abs > maxAbs) maxAbs = abs
        }
        val isNormalized = maxAbs <= 1.1f

        for (i in samples.indices) {
            val pcm16 = if (isNormalized) {
                (samples[i].coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
            } else {
                samples[i].coerceIn(-32768.0f, 32767.0f).toInt().toShort()
            }
            bytes[i * 2] = (pcm16.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm16.toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Trim trailing silence from generated audio samples.
     * The TTS model often appends silence at the end of sentences which compounds
     * into noticeable gaps, especially at paragraph boundaries.
     */
    private fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val threshold = 0.05f // amplitude below this is considered silence
        val minTailSamples = (sampleRate * 0.05).toInt() // keep at least 50ms tail
        val minHeadSamples = (sampleRate * 0.05).toInt() // keep at least 50ms head
        
        var firstAudibleIndex = 0
        while (firstAudibleIndex < samples.size && kotlin.math.abs(samples[firstAudibleIndex]) < threshold) {
            firstAudibleIndex++
        }

        var lastAudibleIndex = samples.size - 1
        while (lastAudibleIndex > firstAudibleIndex && kotlin.math.abs(samples[lastAudibleIndex]) < threshold) {
            lastAudibleIndex--
        }
        
        val trimStart = maxOf(0, firstAudibleIndex - minHeadSamples)
        val trimEnd = minOf(lastAudibleIndex + minTailSamples, samples.size)
        
        if (trimStart > 0 || trimEnd < samples.size) {
            val silenceTrimmed = samples.size - (trimEnd - trimStart)
            val silenceMs = silenceTrimmed * 1000 / sampleRate
            if (silenceMs > 50) {
                Log.d(TAG, "Trimmed ${silenceMs}ms of silence (Head: ${trimStart * 1000 / sampleRate}ms, Tail: ${(samples.size - trimEnd) * 1000 / sampleRate}ms)")
                return samples.copyOfRange(trimStart, trimEnd)
            }
        }
        
        return samples
    }
}
