package com.example.aitutor

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var chatView: TextView
    private lateinit var whiteboard: TextView
    private lateinit var avatarView: TextView
    private lateinit var grammarView: TextView
    private lateinit var inputField: EditText
    private lateinit var progressBar: TextView
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var llmReady = false
    private val history = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    private val modelFile: File by lazy { File(filesDir, "gemma3-1b-it.task") }
    private val modelUrl =
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it.task"

    private val systemPrompt = """
        You are Sarah, an AI English tutor teaching Russian speakers from scratch (Elementary).
        Reply ONLY with valid JSON:
        {"avatar_emotion":"smiling|thoughtful|strict","avatar_gesture":"none|pointing_to_board|crossing_arms","whiteboard_text":"short board text","audio_script":"70% English 30% Russian teaching reply"}
        If the student is lazy or jokes inappropriately, use "strict" and answer firmly but kindly in Russian.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        chatView = findViewById(R.id.chatView)
        whiteboard = findViewById(R.id.whiteboard)
        avatarView = findViewById(R.id.avatarView)
        grammarView = findViewById(R.id.grammarView)
        inputField = findViewById(R.id.inputField)
        progressBar = findViewById(R.id.progressBar)
        prefs = getSharedPreferences("progress", MODE_PRIVATE)
        updateProgress()

        tts = TextToSpeech(this) { }
        setupRecognizer()
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendText() }
        findViewById<Button>(R.id.lessonDoneButton).setOnClickListener { lessonDone() }
        findViewById<ImageButton>(R.id.micButton).setOnClickListener { startListening() }

        if (modelFile.exists()) loadModel() else askForToken()
    }

    // ---------- Модель ----------
    private fun askForToken() {
        val input = EditText(this).apply { hint = getString(R.string.token_hint) }
        AlertDialog.Builder(this)
            .setTitle("Модель Gemma 3 (один раз)")
            .setMessage("Введите бесплатный read-токен Hugging Face. Модель скачается и больше не понадобится.")
            .setView(input)
            .setPositiveButton(getString(R.string.download_model)) { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) downloadModel(token)
                else Toast.makeText(this, "Нужен токен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadModel(token: String) {
        avatarView.text = "⏳"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(modelUrl)
                        .addHeader("Authorization", "Bearer $token").build()
                    val tmp = File(filesDir, "gemma3-1b-it.task.tmp")
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext false
                        resp.body!!.byteStream().use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
                    }
                    tmp.renameTo(modelFile)
                } catch (e: Exception) { false }
            }
            avatarView.text = if (ok) "😊" else "😕"
            if (ok) loadModel()
            else Toast.makeText(this@MainActivity, "Ошибка загрузки, проверьте токен", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadModel() {
        scope.launch {
            avatarView.text = "⏳"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                    val optsCls = Class.forName(
                        "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
                    val builder = optsCls.getMethod("builder").invoke(null)
                    optsCls.getDeclaredMethod("setModelPath", String::class.java)
                        .invoke(builder, modelFile.absolutePath)
                    val opts = builder.javaClass.getMethod("build").invoke(builder)
                    val create = cls.methods.firstOrNull { it.name == "createFromOptions" }
                    llm = create?.invoke(null, applicationContext, opts)
                    llm != null
                } catch (e: Exception) { false }
            }
            llmReady = ok
            if (ok) {
                avatarView.text = "😊"
                whiteboard.text = "Готова! Скажите: Hello, Sarah!"
                speak("Hello! I am Sarah. Let's start your English journey!")
            } else {
                avatarView.text = "😕"
                whiteboard.text = "Модель не загрузилась. Проверьте память телефона."
            }
        }
    }

    private var llm: Any? = null

    private fun askModel(prompt: String): String {
        val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val m = cls.methods.firstOrNull { it.name == "generateResponse" }
        return m?.invoke(llm, prompt) as? String ?: ""
    }

    // ---------- Диалог ----------
    private fun sendText() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        onStudentReply(text)
    }

    private fun onStudentReply(text: String) {
        history.add("Student: $text")
        appendChat("Вы: $text")
        scope.launch {
            avatarView.text = "🤔"
            val raw = withContext(Dispatchers.IO) {
                if (!llmReady) return@withContext null
                val ctx = history.takeLast(6).joinToString("\n")
                try { askModel("$systemPrompt\n\nLesson context: ${currentLesson()}\n\n$ctx\nStudent: $text") }
                catch (e: Exception) { null }
            }
            showReply(raw)
            val grammar = withContext(Dispatchers.IO) { checkGrammar(text) }
            if (grammar != null) {
                grammarView.visibility = View.VISIBLE
                grammarView.text = grammar
            }
        }
    }

    private fun showReply(raw: String?) {
        if (raw == null) {
            avatarView.text = "😕"
            whiteboard.text = "Модель не ответила. Проверьте загрузку Gemma."
            return
        }
        var emotion = "smiling"; var board = ""; var script = ""
        try {
            val s = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
            val obj = JSONObject(s)
            emotion = obj.optString("avatar_emotion", "smiling")
            board = obj.optString("whiteboard_text", "")
            script = obj.optString("audio_script", "")
        } catch (e: Exception) {
            script = raw; board = raw.take(120)
        }
        avatarView.text = when (emotion) {
            "thoughtful" -> "🤔"; "strict" -> "😠"; else -> "😊"
        }
        whiteboard.text = board
        if (script.isNotEmpty()) {
            appendChat("Sarah: $script")
            history.add("Sarah: $script")
            speak(script)
        }
    }

    private fun checkGrammar(text: String): String? {
        if (!llmReady) return null
        return try {
            val p = "Find grammar mistakes in this sentence from a beginner. " +
                    "Answer in Russian, short. If no mistakes, say OK. Sentence: \"$text\""
            askModel(p).take(200)
        } catch (e: Exception) { null }
    }

    // ---------- Микрофон ----------
    private fun setupRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotEmpty()) onStudentReply(text)
            }
            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, "Не расслышала", Toast.LENGTH_SHORT).show()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
        avatarView.text = "🎤"
    }

    // ---------- Озвучка ----------
    private fun speak(text: String) {
        tts?.language = Locale.US
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarah")
    }

    // ---------- Прогресс ----------
    private fun currentLesson(): Int = prefs.getInt("lesson", 1)

    private fun updateProgress() {
        progressBar.text = "Урок ${currentLesson()} из 90"
    }

    private fun lessonDone() {
        val done = currentLesson()
        prefs.edit().putInt("lesson", (done + 1).coerceAtMost(90)).apply()
        updateProgress()
        Toast.makeText(this, "Урок $done пройден! Дальше — урок ${currentLesson()}", Toast.LENGTH_SHORT).show()
    }

    private fun appendChat(line: String) {
        chatView.append("$line\n\n")
        (chatView.parent as? ScrollView)?.scrollTo(0, chatView.height)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
