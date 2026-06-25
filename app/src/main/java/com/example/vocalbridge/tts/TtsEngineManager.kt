package com.example.vocalbridge.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton that manages the Sherpa-ONNX OfflineTts engine lifecycle.
 *
 * - Copies model files from assets/kokoro/ → filesDir/kokoro/ on first launch
 * - Initializes OfflineTts with Kokoro model config
 * - Default voice: bm_george (British Male, sid=9)
 * - Default speed: 1.2x (configurable)
 */
object TtsEngineManager {
    private const val TAG = "TtsEngineManager"
    private const val ASSET_DIR = "kokoro"
    private const val MODEL_DIR = "kokoro"
    private const val MARKER_FILE = ".model_extracted"

    // Kokoro Voice Mapping
    data class Voice(val id: Int, val name: String, val description: String)
    val availableVoices = listOf(
        Voice(0, "Heart", "American Female"),
        Voice(1, "Bella", "American Female"),
        Voice(2, "Nicole", "American Female"),
        Voice(3, "Sarah", "American Female"),
        Voice(4, "Sky", "American Female"),
        Voice(5, "Adam", "American Male"),
        Voice(6, "Michael", "American Male"),
        Voice(7, "Emma", "British Female"),
        Voice(8, "Isabella", "British Female"),
        Voice(9, "George", "British Male"),
        Voice(10, "Lewis", "British Male")
    )

    // Default speaker: bm_george = sid 9
    const val DEFAULT_SPEAKER_ID = 9
    const val DEFAULT_SPEED = 1.2f
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    @Volatile
    var tts: OfflineTts? = null
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var initProgress: String = ""
        private set

    var speed: Float = DEFAULT_SPEED
    var speakerId: Int = DEFAULT_SPEAKER_ID
    var continuousPlaybackMode: Boolean = false

    private val initMutex = Mutex()

    val sampleRate: Int
        get() = tts?.sampleRate() ?: 0

    val numSpeakers: Int
        get() = tts?.numSpeakers() ?: 0

    /**
     * Initialize the TTS engine. Safe to call multiple times — will only
     * initialize once.
     */
    suspend fun initialize(context: Context, onProgress: ((String) -> Unit)? = null) {
        if (isInitialized && tts != null) return

        initMutex.withLock {
            // Double-check after acquiring lock
            if (isInitialized && tts != null) return

            try {
                val appContext = context.applicationContext
                val modelDir = File(appContext.filesDir, MODEL_DIR)

                // Step 1: Copy ONLY espeak-ng-data (required for eSpeak POSIX file access)
                val espeakDestDir = File(appContext.filesDir, "espeak-ng-data")
                if (!File(espeakDestDir, "phondata").exists()) { // Check a known file inside
                    val msg = "Extracting phoneme data…"
                    initProgress = msg
                    onProgress?.invoke(msg)
                    Log.i(TAG, msg)

                    withContext(Dispatchers.IO) {
                        copyAssetDir(appContext, "kokoro/espeak-ng-data", espeakDestDir)
                    }
                    Log.i(TAG, "Phoneme extraction complete")
                }

                // Step 2: Initialize the OfflineTts engine
                val msg2 = "Loading TTS model…"
                initProgress = msg2
                onProgress?.invoke(msg2)
                Log.i(TAG, msg2)

                // Use relative paths for model files (read from assets)
                // Use absolute path for dataDir (read from file system)
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model = "kokoro/model.onnx",
                    voices = "kokoro/voices.bin",
                    tokens = "kokoro/tokens.txt",
                    dataDir = espeakDestDir.absolutePath,
                    lexicon = "",
                    lang = "en-us",
                    dictDir = "",
                    lengthScale = 1.0f
                )

                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(),
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = kokoroConfig,
                    zipvoice = OfflineTtsZipVoiceModelConfig(),
                    kitten = OfflineTtsKittenModelConfig(),
                    pocket = OfflineTtsPocketModelConfig(),
                    supertonic = OfflineTtsSupertonicModelConfig(),
                    numThreads = 4,
                    debug = true,
                    provider = "cpu"
                )

                val config = OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = "",
                    ruleFars = "",
                    maxNumSentences = 1,
                    silenceScale = 0.2f
                )

                withContext(Dispatchers.IO) {
                    // Pass appContext.assets so Sherpa can read relative paths from APK
                    tts = OfflineTts(assetManager = appContext.assets, config = config)
                }

                isInitialized = true
                initProgress = "Ready"
                onProgress?.invoke("Ready")
                Log.i(TAG, "TTS engine initialized. Sample rate: ${tts?.sampleRate()}, Speakers: ${tts?.numSpeakers()}")

            } catch (e: Exception) {
                val errorMsg = "Failed to initialize TTS: ${e.message}"
                initProgress = errorMsg
                onProgress?.invoke(errorMsg)
                Log.e(TAG, errorMsg, e)
                throw e
            }
        }
    }

    fun release() {
        tts?.release()
        tts = null
        isInitialized = false
        initProgress = ""
        Log.i(TAG, "TTS engine released")
    }

    // ---- Asset copying utilities ----

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return

        if (!destDir.exists()) destDir.mkdirs()

        for (entry in entries) {
            val srcPath = "$assetPath/$entry"
            val destFile = File(destDir, entry)

            // Skip the tar.bz2 archive — it's the download source, not needed at runtime
            if (entry.endsWith(".tar.bz2")) {
                Log.d(TAG, "Skipping archive: $entry")
                continue
            }

            val children = assetManager.list(srcPath)
            if (children != null && children.isNotEmpty()) {
                // It's a directory — recurse
                copyAssetDir(context, srcPath, destFile)
            } else {
                // It's a file — copy
                if (!destFile.exists()) {
                    Log.d(TAG, "Copying: $srcPath → ${destFile.absolutePath}")
                    assetManager.open(srcPath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                }
            }
        }
    }
}
