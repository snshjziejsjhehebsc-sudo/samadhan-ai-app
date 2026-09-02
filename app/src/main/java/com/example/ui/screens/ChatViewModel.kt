package com.example.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.auth.AuthRepository
import com.example.data.auth.UserProfile
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.remote.GeminiRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.chatDao()
    private val repository = GeminiRepository(application)
    private val authRepository = AuthRepository(application)
    private val sharedPrefs = application.getSharedPreferences("samadhan_ai_prefs", Context.MODE_PRIVATE)

    // User Profile
    val currentUser: StateFlow<UserProfile?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Conversations from DB
    val conversations: StateFlow<List<ConversationEntity>> = dao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active conversation ID
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    // Messages for the active conversation
    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    // Input state
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    // Image Generation Mode
    private val _isImageMode = MutableStateFlow(false)
    val isImageMode: StateFlow<Boolean> = _isImageMode.asStateFlow()

    // Generation state
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Settings
    private val _selectedModel: MutableStateFlow<String> = run {
        val saved = sharedPrefs.getString("selected_model", GeminiRepository.DEFAULT_MODEL)
        val sanitized = if (saved == null || saved.contains("2.5") || saved.isBlank()) {
            sharedPrefs.edit().putString("selected_model", GeminiRepository.DEFAULT_MODEL).apply()
            GeminiRepository.DEFAULT_MODEL
        } else {
            saved.removePrefix("models/")
        }
        MutableStateFlow(sanitized)
    }
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _customApiKey = MutableStateFlow(
        sharedPrefs.getString("custom_api_key", "") ?: ""
    )
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private var messageCollectionJob: Job? = null
    private var generationJob: Job? = null

    init {
        // Observe conversation changes
        viewModelScope.launch {
            _activeConversationId.collectLatest { convId ->
                messageCollectionJob?.cancel()
                if (convId != null) {
                    messageCollectionJob = viewModelScope.launch {
                        dao.getMessagesForConversation(convId).collectLatest { msgs ->
                            _messages.value = msgs
                        }
                    }
                } else {
                    _messages.value = emptyList()
                }
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun setSelectedImageUri(uri: Uri?) {
        _selectedImageUri.value = uri
    }

    fun setImageMode(enabled: Boolean) {
        _isImageMode.value = enabled
    }

    fun setSelectedModel(model: String) {
        val sanitized = if (model.contains("2.5") || model.isBlank()) {
            GeminiRepository.DEFAULT_MODEL
        } else {
            model.trim().removePrefix("models/")
        }
        _selectedModel.value = sanitized
        sharedPrefs.edit().putString("selected_model", sanitized).apply()
    }

    fun setCustomApiKey(key: String) {
        _customApiKey.value = key
        sharedPrefs.edit().putString("custom_api_key", key).apply()
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun startNewChat() {
        generationJob?.cancel()
        _isGenerating.value = false
        _activeConversationId.value = null
        _messages.value = emptyList()
        _inputText.value = ""
        _selectedImageUri.value = null
        _isImageMode.value = false
    }

    fun selectConversation(conversationId: String) {
        generationJob?.cancel()
        _isGenerating.value = false
        _activeConversationId.value = conversationId
        _inputText.value = ""
        _selectedImageUri.value = null
        _isImageMode.value = false
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                startNewChat()
            }
            dao.deleteConversationById(conversationId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            startNewChat()
            dao.clearAllMessages()
            dao.clearAllConversations()
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        _isGenerating.value = false
    }

    fun generateAgain(message: ChatMessageEntity) {
        val prompt = message.imagePrompt ?: message.content
        if (prompt.isNotBlank()) {
            generateImageMessage(prompt = prompt, referenceImageUri = null)
        }
    }

    fun prepareImageEdit(message: ChatMessageEntity) {
        if (message.imageUri != null) {
            _selectedImageUri.value = Uri.parse(message.imageUri)
            _isImageMode.value = true
            _inputText.value = "Make the sky blue"
        }
    }

    fun createAnotherImage() {
        _isImageMode.value = true
        _selectedImageUri.value = null
        _inputText.value = ""
    }

    // AI Image Mode will be enabled later.
    fun generateImageMessage(prompt: String, referenceImageUri: Uri? = null) {
        if (prompt.isBlank() || _isGenerating.value) return

        _inputText.value = ""
        _selectedImageUri.value = null

        viewModelScope.launch {
            var convId = _activeConversationId.value
            val isNewChat = convId == null

            if (isNewChat) {
                convId = UUID.randomUUID().toString()
                val title = if (prompt.length > 35) "🎨 " + prompt.take(30) + "..." else "🎨 $prompt"
                val newConv = ConversationEntity(
                    id = convId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                dao.insertConversation(newConv)
                _activeConversationId.value = convId
            } else {
                val existing = dao.getConversationById(convId!!)
                if (existing != null) {
                    dao.updateConversation(existing.copy(updatedAt = System.currentTimeMillis()))
                }
            }

            // Insert User Message
            val userMsgId = UUID.randomUUID().toString()
            val userMessage = ChatMessageEntity(
                id = userMsgId,
                conversationId = convId!!,
                role = "user",
                content = prompt,
                imageUri = referenceImageUri?.toString(),
                isGeneratedImage = false,
                timestamp = System.currentTimeMillis()
            )
            dao.insertMessage(userMessage)

            // Insert placeholder Assistant Message
            val assistantMsgId = UUID.randomUUID().toString()
            val initialAssistantMessage = ChatMessageEntity(
                id = assistantMsgId,
                conversationId = convId,
                role = "model",
                content = "छवि बनाई जा रही है... (Generating image...)",
                isGeneratedImage = true,
                imagePrompt = prompt,
                timestamp = System.currentTimeMillis() + 1,
                isStreaming = true
            )
            dao.insertMessage(initialAssistantMessage)

            _isGenerating.value = true

            generationJob = viewModelScope.launch {
                try {
                    val result = repository.generateImage(
                        prompt = prompt,
                        referenceImageUri = referenceImageUri?.toString(),
                        customApiKey = null
                    )

                    if (result.isSuccess) {
                        val (generatedUri, textNotice) = result.getOrThrow()
                        dao.updateMessage(
                            initialAssistantMessage.copy(
                                content = textNotice,
                                imageUri = generatedUri,
                                isGeneratedImage = true,
                                imagePrompt = prompt,
                                isStreaming = false,
                                isError = false
                            )
                        )
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message
                            ?: "Image service is temporarily unavailable."
                        dao.updateMessage(
                            initialAssistantMessage.copy(
                                content = errorMsg,
                                isGeneratedImage = false,
                                isStreaming = false,
                                isError = true
                            )
                        )
                    }
                } catch (e: Exception) {
                    dao.updateMessage(
                        initialAssistantMessage.copy(
                            content = "Image service is temporarily unavailable.",
                            isGeneratedImage = false,
                            isStreaming = false,
                            isError = true
                        )
                    )
                } finally {
                    _isGenerating.value = false
                }
            }
        }
    }

    fun sendMessage(customPrompt: String? = null, customImageUri: Uri? = null) {
        val messageText = (customPrompt ?: _inputText.value).trim()
        val imageUri = customImageUri ?: _selectedImageUri.value

        if (messageText.isBlank() && imageUri == null) return
        if (_isGenerating.value) return

        // AI Image Mode will be enabled later.
        /*
        if (_isImageMode.value || (imageUri == null && GeminiRepository.isImagePrompt(messageText))) {
            generateImageMessage(messageText, imageUri)
            return
        }
        */

        // Clear inputs
        _inputText.value = ""
        _selectedImageUri.value = null

        viewModelScope.launch {
            // Determine active conversation or create new one
            var convId = _activeConversationId.value
            val isNewChat = convId == null

            if (isNewChat) {
                convId = UUID.randomUUID().toString()
                val title = if (messageText.isNotBlank()) {
                    if (messageText.length > 35) messageText.take(32) + "..." else messageText
                } else {
                    "छवि विश्लेषण (Image)"
                }
                val newConv = ConversationEntity(
                    id = convId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                dao.insertConversation(newConv)
                _activeConversationId.value = convId
            } else {
                val existing = dao.getConversationById(convId!!)
                if (existing != null) {
                    dao.updateConversation(existing.copy(updatedAt = System.currentTimeMillis()))
                }
            }

            // Insert User Message
            val userMsgId = UUID.randomUUID().toString()
            val userMessage = ChatMessageEntity(
                id = userMsgId,
                conversationId = convId!!,
                role = "user",
                content = messageText,
                imageUri = imageUri?.toString(),
                timestamp = System.currentTimeMillis()
            )
            dao.insertMessage(userMessage)

            // Insert placeholder Assistant Message for streaming
            val assistantMsgId = UUID.randomUUID().toString()
            val initialAssistantMessage = ChatMessageEntity(
                id = assistantMsgId,
                conversationId = convId,
                role = "model",
                content = "",
                timestamp = System.currentTimeMillis() + 1,
                isStreaming = true
            )
            dao.insertMessage(initialAssistantMessage)

            _isGenerating.value = true

            // Fetch previous messages for context
            val contextMessages = dao.getMessagesListForConversation(convId).filter { it.id != assistantMsgId && it.id != userMsgId }

            generationJob = viewModelScope.launch {
                try {
                    var finalContent = ""
                    repository.generateStreamResponse(
                        messages = contextMessages,
                        latestUserMessage = messageText,
                        attachedImageUri = imageUri?.toString(),
                        modelName = _selectedModel.value,
                        customApiKey = _customApiKey.value
                    ).collectLatest { chunk ->
                        finalContent = chunk
                        dao.updateMessage(
                            initialAssistantMessage.copy(
                                content = chunk,
                                isStreaming = true
                            )
                        )
                    }

                    // Mark streaming completed
                    dao.updateMessage(
                        initialAssistantMessage.copy(
                            content = finalContent.ifEmpty { "जवाब प्राप्त करने में असमर्थ। कृपया पुनः प्रयास करें।" },
                            isStreaming = false
                        )
                    )
                } catch (e: Exception) {
                    dao.updateMessage(
                        initialAssistantMessage.copy(
                            content = "त्रुटि: ${e.localizedMessage ?: "Unknown error"}",
                            isStreaming = false,
                            isError = true
                        )
                    )
                } finally {
                    _isGenerating.value = false
                }
            }
        }
    }

    fun regenerateResponse(targetMessage: ChatMessageEntity) {
        if (_isGenerating.value) return
        val convId = targetMessage.conversationId

        viewModelScope.launch {
            val allMessages = dao.getMessagesListForConversation(convId)
            val targetIndex = allMessages.indexOfFirst { it.id == targetMessage.id }
            if (targetIndex < 0) return@launch

            // Find the preceding user message
            val previousUserMessage = allMessages.take(targetIndex).lastOrNull { it.role == "user" }
            val userPromptText = previousUserMessage?.content ?: return@launch
            val userImageUri = previousUserMessage.imageUri

            // Context is messages before that user message
            val userIndex = allMessages.indexOfFirst { it.id == previousUserMessage.id }
            val contextMessages = if (userIndex >= 0) allMessages.take(userIndex) else emptyList()

            // Reset target assistant message to streaming empty state
            dao.updateMessage(
                targetMessage.copy(
                    content = "",
                    isStreaming = true,
                    isError = false,
                    isGeneratedImage = false
                )
            )

            _isGenerating.value = true

            generationJob = viewModelScope.launch {
                try {
                    var finalContent = ""
                    repository.generateStreamResponse(
                        messages = contextMessages,
                        latestUserMessage = userPromptText,
                        attachedImageUri = userImageUri,
                        modelName = _selectedModel.value,
                        customApiKey = _customApiKey.value
                    ).collectLatest { chunk ->
                        finalContent = chunk
                        dao.updateMessage(
                            targetMessage.copy(
                                content = chunk,
                                isStreaming = true,
                                isError = false
                            )
                        )
                    }

                    dao.updateMessage(
                        targetMessage.copy(
                            content = finalContent.ifEmpty { "जवाब प्राप्त करने में असमर्थ। कृपया पुनः प्रयास करें।" },
                            isStreaming = false,
                            isError = false
                        )
                    )
                } catch (e: Exception) {
                    dao.updateMessage(
                        targetMessage.copy(
                            content = "त्रुटि: ${e.localizedMessage ?: "Unknown error"}",
                            isStreaming = false,
                            isError = true
                        )
                    )
                } finally {
                    _isGenerating.value = false
                }
            }
        }
    }
}
