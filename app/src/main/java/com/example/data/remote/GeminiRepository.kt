package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ChatMessageEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GeminiRepository(private val context: Context) {

    companion object {
        private const val TAG = "GeminiRepository"
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        const val MODEL_GEMINI_3_6_FLASH = "gemini-3.6-flash"
        const val MODEL_GEMINI_3_5_FLASH = "gemini-3.5-flash"
        const val MODEL_GEMINI_3_7_FLASH = "gemini-3.7-flash"

        val AVAILABLE_MODELS = listOf(
            "gemini-3.6-flash" to "Gemini 3.6 Flash (Fast & Balanced)",
            "gemini-3.5-flash" to "Gemini 3.5 Flash (Advanced Reasoning)",
            "gemini-3.7-flash" to "Gemini 3.7 Flash (Next-Gen Hybrid)"
        )

        val SAMADHAN_SYSTEM_PROMPT = """
            You are Samadhan AI (समाधान AI) — a natural, intelligent, helpful, and solution-oriented conversational AI assistant.
            Your founding motto is: "सिर्फ जवाब नहीं — सच्चा समाधान।" (Not just shallow answers — true, complete solutions).

            Core Guidelines & AI Behavior:
            1. Conversational & Human-like Interaction:
               - Speak and respond naturally like a wise, friendly, and articulate human expert.
               - Support real-time voice and text conversations seamlessly.
               - Understand Hindi, Hinglish, and English intuitively and answer in the user's preferred language.
               - Remember the ongoing conversation context and build upon previous turns effortlessly.

            2. Intent & Solution-Oriented Depth:
               - If a query is direct or conversational, provide a clear, engaging, and comprehensive answer immediately.
               - For complex questions or troubleshooting, offer structured, step-by-step guidance.
               - For code or technical queries, provide clean, fully explained, modern code snippets.

            3. Tone & Style:
               - Warm, respectful, intelligent, articulate, and empathetic.
               - Avoid robotic filler phrases. Speak with confidence and clarity.
               - Format with clean Markdown for easy visual reading and natural speech synthesis.
        """.trimIndent()

        fun isImagePrompt(text: String): Boolean {
            val lower = text.trim().lowercase()
            val imageKeywords = listOf(
                "create image", "generate image", "create an image", "generate an image",
                "draw", "paint", "picture of", "photo of", "wallpaper",
                "छवि बनाएं", "तस्वीर बनाएं", "फोटो बनाओ", "चित्र बनाओ", "तस्वीर दिखाओ",
                "इमेज बनाओ", "तस्वीर खींचो", "चित्र बनाएं", "image banao", "photo banao",
                "tasveer banao", "wallpaper banao", "picture banao"
            )
            return imageKeywords.any { lower.contains(it) }
        }
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val apiService: GeminiApiService = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApiService::class.java)

    fun getEffectiveApiKey(customKey: String?): String {
        val trimmedCustom = customKey?.trim()
        if (!trimmedCustom.isNullOrEmpty()) {
            return trimmedCustom
        }
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun convertUriToBase64(uriString: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext null

            // Scale down if image is too large for fast transfer
            val maxDimension = 1280
            val ratio = (maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val scaledBitmap = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            Pair(mimeType, base64Data)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding image to Base64", e)
            null
        }
    }

    suspend fun generateStreamResponse(
        messages: List<ChatMessageEntity>,
        latestUserMessage: String,
        attachedImageUri: String?,
        modelName: String = DEFAULT_MODEL,
        customApiKey: String? = null
    ): Flow<String> = flow {
        val apiKey = getEffectiveApiKey(customApiKey)
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            emit("API Key is missing. Please configure your Gemini API Key in the AI Studio Secrets panel or inside the App Settings.")
            return@flow
        }

        // Build contents list
        val contents = mutableListOf<GeminiContent>()

        // Add previous conversation history (limit to last 14 turns for fast token context)
        val historyTurns = messages.takeLast(14)
        for (msg in historyTurns) {
            val role = if (msg.role == "user") "user" else "model"
            val parts = mutableListOf<GeminiPart>()

            // If historical message has image
            if (msg.imageUri != null && msg.role == "user") {
                val imagePair = convertUriToBase64(msg.imageUri)
                if (imagePair != null) {
                    parts.add(GeminiPart(inlineData = GeminiInlineData(imagePair.first, imagePair.second)))
                }
            }

            if (msg.content.isNotBlank()) {
                parts.add(GeminiPart(text = msg.content))
            }

            if (parts.isNotEmpty()) {
                contents.add(GeminiContent(role = role, parts = parts))
            }
        }

        // Add current prompt
        val currentParts = mutableListOf<GeminiPart>()
        if (attachedImageUri != null) {
            val imagePair = convertUriToBase64(attachedImageUri)
            if (imagePair != null) {
                currentParts.add(GeminiPart(inlineData = GeminiInlineData(imagePair.first, imagePair.second)))
            }
        }
        currentParts.add(GeminiPart(text = latestUserMessage))
        contents.add(GeminiContent(role = "user", parts = currentParts))

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = GeminiContent(
                parts = listOf(GeminiPart(text = SAMADHAN_SYSTEM_PROMPT))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.7f,
                topP = 0.95f
            )
        )

        val effectiveModel = when {
            modelName.contains("2.5") -> DEFAULT_MODEL
            modelName.isNotBlank() -> modelName.trim().removePrefix("models/")
            else -> DEFAULT_MODEL
        }

        // Try streaming first, fallback to standard generate if streaming format fails
        var accumulatedText = ""
        var streamingSucceeded = false

        try {
            val responseBody = apiService.streamGenerateContent(
                model = effectiveModel,
                apiKey = apiKey,
                request = request
            )

            responseBody.byteStream().bufferedReader().use { reader ->
                var line: String?
                val responseAdapter = moshi.adapter(GeminiResponse::class.java)

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line?.trim() ?: continue
                    if (currentLine.isEmpty() || currentLine.startsWith(":")) continue

                    val jsonStr = if (currentLine.startsWith("data: ")) {
                        currentLine.removePrefix("data: ").trim()
                    } else {
                        currentLine
                    }

                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                    try {
                        val parsed = responseAdapter.fromJson(jsonStr)
                        val textChunk = parsed?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        if (!textChunk.isNullOrEmpty()) {
                            accumulatedText += textChunk
                            emit(accumulatedText)
                            streamingSucceeded = true
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Chunk parse notice: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming attempt encountered exception", e)
        }

        if (!streamingSucceeded) {
            // Fallback to standard synchronous generateContent call
            try {
                val nonStreamResponse = apiService.generateContent(
                    model = effectiveModel,
                    apiKey = apiKey,
                    request = request
                )
                val fullText = nonStreamResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!fullText.isNullOrEmpty()) {
                    emit(fullText)
                } else if (nonStreamResponse.error != null) {
                    emit("Error (${nonStreamResponse.error.code} - ${nonStreamResponse.error.status}): ${nonStreamResponse.error.message}")
                } else {
                    emit("Unable to generate response. Please try again.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateContent fallback failed", e)
                val errorMsg = extractApiErrorMessage(e)
                emit("Error: $errorMsg\nPlease check your internet connection or Gemini API Key.")
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun extractApiErrorMessage(e: Throwable): String {
        if (e is retrofit2.HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                try {
                    val adapter = moshi.adapter(GeminiResponse::class.java)
                    val geminiResp = adapter.fromJson(errorBody)
                    if (geminiResp?.error?.message != null) {
                        val status = geminiResp.error.status ?: "HTTP_$code"
                        return "[$status] (Code $code): ${geminiResp.error.message}"
                    }
                } catch (_: Exception) {}
                return "HTTP $code: $errorBody"
            }
            return "HTTP $code: ${e.message()}"
        }
        return e.localizedMessage ?: e.message ?: e.toString()
    }

    private val imageGenerationService: ImageGenerationService = ImageGenerationService(context)

    suspend fun generateImage(
        prompt: String,
        referenceImageUri: String? = null,
        customApiKey: String? = null
    ): Result<Pair<String, String>> {
        return imageGenerationService.generateImage(
            prompt = prompt,
            referenceImageUri = referenceImageUri,
            customApiKey = customApiKey
        )
    }
}
