package com.example.vocalbridge.ui.main

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.example.vocalbridge.tts.SentenceSplitter
import com.example.vocalbridge.tts.TtsEngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainScreenUiState(
    val inputText: String = "Good morning. This is George, your British text to speech engine. I can read books, articles, and any text on your device locally and privately without internet. Each sentence is processed individually, so you hear the first part while the rest generates seamlessly.",
    val engineStatus: EngineStatus = EngineStatus.NOT_INITIALIZED,
    val statusMessage: String = "",
    val isSpeaking: Boolean = false,
    val speed: Float = TtsEngineManager.DEFAULT_SPEED,
    val speakerId: Int = TtsEngineManager.DEFAULT_SPEAKER_ID,
    val continuousPlaybackMode: Boolean = false,
    val error: String? = null
)

enum class EngineStatus {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    ERROR,
}

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainScreenVM"
    }

    private val _uiState = MutableStateFlow(MainScreenUiState())
    val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    init {
        // Auto-initialize engine on creation
        initializeEngine()
    }

    fun initializeEngine() {
        if (_uiState.value.engineStatus == EngineStatus.INITIALIZING) return

        _uiState.update { it.copy(engineStatus = EngineStatus.INITIALIZING, statusMessage = "Initializing…") }

        viewModelScope.launch {
            try {
                TtsEngineManager.initialize(
                    context = getApplication(),
                    onProgress = { progress ->
                        _uiState.update { it.copy(statusMessage = progress) }
                    }
                )
                _uiState.update {
                    it.copy(
                        engineStatus = EngineStatus.READY,
                        statusMessage = "Ready · ${TtsEngineManager.sampleRate}Hz · ${TtsEngineManager.numSpeakers} speakers"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine initialization failed", e)
                _uiState.update {
                    it.copy(
                        engineStatus = EngineStatus.ERROR,
                        statusMessage = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun updateSpeed(newSpeed: Float) {
        val clamped = newSpeed.coerceIn(TtsEngineManager.MIN_SPEED, TtsEngineManager.MAX_SPEED)
        TtsEngineManager.speed = clamped
        _uiState.update { it.copy(speed = clamped) }
    }

    fun updateContinuousPlaybackMode(enabled: Boolean) {
        TtsEngineManager.continuousPlaybackMode = enabled
        _uiState.update { it.copy(continuousPlaybackMode = enabled) }
    }

    fun updateSpeaker(speakerId: Int) {
        TtsEngineManager.speakerId = speakerId
        _uiState.update { it.copy(speakerId = speakerId) }
    }

    fun speak() {
        val text = _uiState.value.inputText
        if (text.isBlank() || _uiState.value.engineStatus != EngineStatus.READY) return

        val oldJob = playbackJob
        stopPlayback()

        playbackJob = viewModelScope.launch {
            oldJob?.join()
            _uiState.update { it.copy(isSpeaking = true) }
            stopRequested = false

            try {
                withContext(Dispatchers.IO) {
                    performPlayback(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
            } finally {
                _uiState.update { it.copy(isSpeaking = false) }
            }
        }
    }

    fun stopPlayback() {
        stopRequested = true
        playbackJob?.cancel()
        playbackJob = null
        
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            }
        } catch (_: Exception) {}
        
        _uiState.update { it.copy(isSpeaking = false) }
    }

    private suspend fun performPlayback(text: String) {
        val tts = TtsEngineManager.tts ?: return
        val sampleRate = tts.sampleRate()

        // Create AudioTrack for direct playback
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
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

        audioTrack = track
        val sentences = SentenceSplitter.split(text)
        Log.d(TAG, "Playing ${sentences.size} sentences")

        stopRequested = false
        val audioChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 50)

        // Use coroutineScope so all child coroutines are cancelled together
        // when playbackJob is cancelled (i.e. when Stop is pressed)
        kotlinx.coroutines.coroutineScope {
            // Consumer coroutine: Reads chunks from channel and plays them
            val consumerJob = launch(Dispatchers.IO) {
                try {
                    // Write 250ms of silence to wake up the audio amplifier/Bluetooth hardware
                    val silenceFrames = (sampleRate * 0.25).toInt()
                    val silenceBytes = ByteArray(silenceFrames * 2)
                    track.write(silenceBytes, 0, silenceBytes.size)
                    track.play()

                    for (pcmBytes in audioChannel) {
                        if (stopRequested) break

                        val written = track.write(pcmBytes, 0, pcmBytes.size)
                        if (written < 0) {
                            Log.e(TAG, "AudioTrack write failed with error code: $written")
                        }

                        // Safe play check — track may have been released by stopPlayback
                        try {
                            if (track.state == AudioTrack.STATE_INITIALIZED &&
                                track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                track.play()
                            }
                        } catch (_: IllegalStateException) {
                            break
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Normal cancellation from stop
                } finally {
                    try {
                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            track.stop()
                            track.release()
                        }
                    } catch (_: Exception) { }
                    audioTrack = null
                }
            }

            // Producer: generate sentences and send audio to channel
            val genConfig = GenerationConfig(
                silenceScale = 0.2f,
                speed = TtsEngineManager.speed,
                sid = TtsEngineManager.speakerId
            )

            launch(Dispatchers.IO) {
                try {
                    for ((index, sentence) in sentences.withIndex()) {
                        if (stopRequested) break
                        Log.d(TAG, "Generating sentence $index: \"${sentence.take(80)}...\"")
                        
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
                            // Dynamic pause based on punctuation
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
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Normal cancellation from stop
                } catch (e: Exception) {
                    Log.e(TAG, "Generation error", e)
                } finally {
                    audioChannel.close()
                }
            }
        }
    }

    /**
     * Trim trailing silence from generated audio samples.
     * The TTS model often appends silence at the end of sentences which compounds
     * into noticeable gaps, especially at paragraph boundaries.
     * 
     * Keeps a small tail (50ms) to avoid cutting off the last phoneme.
     */
    private fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val threshold = 0.05f // Increased to 0.05 to handle Kokoro's noisy silence floor
        val minTailSamples = (sampleRate * 0.05).toInt() // keep at least 50ms tail
        val minHeadSamples = (sampleRate * 0.05).toInt() // keep at least 50ms head
        
        // Find the first audible sample
        var firstAudibleIndex = 0
        while (firstAudibleIndex < samples.size && kotlin.math.abs(samples[firstAudibleIndex]) < threshold) {
            firstAudibleIndex++
        }

        // Find the last audible sample
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

    private fun releaseAudioTrack() {
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.stop()
                    track.release()
                }
            }
        } catch (_: Exception) { }
        audioTrack = null
    }

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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
