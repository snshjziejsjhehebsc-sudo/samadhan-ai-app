package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ElevenLabsTtsService(private val context: Context) {

    companion object {
        private const val TAG = "ElevenLabsTtsService"
        const val VOICE_ID = "CNKl99QEbWm8RQ4D8GfC"
        private const val MODEL_ID = "eleven_multilingual_v2"
        private const val API_BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var synthesisJob: Job? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentTempAudioFile: File? = null

    fun isConfigured(): Boolean {
        val key = getApiKey()
        return !key.isNullOrBlank() && !key.startsWith("YOUR_")
    }

    private fun getApiKey(): String? {
        return try {
            val key = BuildConfig.ELEVENLABS_API_KEY
            if (key.isNotBlank() && !key.startsWith("YOUR_")) key else null
        } catch (e: Exception) {
            null
        }
    }

    fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        val apiKey = getApiKey()
        if (apiKey == null) {
            onError("ElevenLabs API Key is not configured")
            return
        }

        synthesisJob = scope.launch {
            try {
                val jsonBody = JSONObject().apply {
                    put("text", text)
                    put("model_id", MODEL_ID)
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                        put("style", 0.0)
                        put("use_speaker_boost", true)
                    })
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val url = "$API_BASE_URL/$VOICE_ID?output_format=mp3_44100_128"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    Log.w(TAG, "ElevenLabs TTS request failed with HTTP $code")
                    withContext(Dispatchers.Main) {
                        onError("ElevenLabs request failed ($code)")
                    }
                    return@launch
                }

                val responseBody = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        onError("Empty response body from ElevenLabs")
                    }
                    return@launch
                }

                // Save audio stream to cache file
                val tempFile = File(context.cacheDir, "elevenlabs_speech_${System.currentTimeMillis()}.mp3")
                FileOutputStream(tempFile).use { output ->
                    responseBody.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                currentTempAudioFile = tempFile

                withContext(Dispatchers.Main) {
                    playAudioFile(tempFile, onStart, onDone, onError)
                }

            } catch (e: Exception) {
                Log.w(TAG, "ElevenLabs synthesis exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("ElevenLabs synthesis error: ${e.localizedMessage ?: "Unknown"}")
                }
            }
        }
    }

    private fun playAudioFile(
        file: File,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            cleanupMediaPlayer()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                        onStart()
                    } catch (e: Exception) {
                        onError("Error starting media player")
                    }
                }
                setOnCompletionListener {
                    cleanupMediaPlayer()
                    onDone()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    cleanupMediaPlayer()
                    onError("MediaPlayer error ($what, $extra)")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing MediaPlayer", e)
            cleanupMediaPlayer()
            onError("MediaPlayer preparation failed")
        }
    }

    fun stop() {
        synthesisJob?.cancel()
        synthesisJob = null
        cleanupMediaPlayer()
    }

    private fun cleanupMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }

        // Delete temporary audio files to avoid cache bloat
        try {
            currentTempAudioFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore file deletion error
        } finally {
            currentTempAudioFile = null
        }
    }

    fun release() {
        stop()
    }
}
