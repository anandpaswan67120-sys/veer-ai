package com.veer.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var controller: VeerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VeerScreen(
                        onSpeak = { text, done -> speakThen(text, done) }
                    )
                }
            }
        }
    }

    // बोलना खत्म होने के बाद ही done() चलेगा, ताकि वीर अपनी आवाज न सुने
    fun speakThen(text: String, done: () -> Unit) {
        val id = "veer_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) Handler(Looper.getMainLooper()).post { done() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) Handler(Looper.getMainLooper()).post { done() }
            }
        })
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

data class ContactItem(val name: String, val number: String)

private val stopWords = setOf(
    "वीर", "veer", "को", "ko", "पर", "पे", "मैसेज", "message", "संदेश",
    "भेजो", "भेज", "भेजना", "send", "to", "whatsapp", "व्हाट्सएप", "व्हाट्सऐप",
    "करो", "लिखो", "बोलो", "कि"
)

fun extractName(text: String): String =
    text.split(" ").filter { it.isNotBlank() && it.lowercase() !in stopWords }.joinToString(" ")

fun isMessageCommand(text: String): Boolean {
    val c = text.lowercase()
    if (c.contains("खोल") || c.contains("open")) return false
    return listOf("मैसेज", "message", "संदेश", "भेज", "व्हाट्सएप", "whatsapp", "send").any { c.contains(it) }
}

fun isWakeWord(text: String): Boolean {
    val c = text.lowercase()
    return c.contains("वीर") || c.contains("veer") || c.contains("बीर")
}

fun pickIndex(text: String, max: Int): Int? {
    val t = text.lowercase()
    val map = listOf(
        listOf("1", "एक", "one") to 1,
        listOf("2", "दो", "two") to 2,
        listOf("3", "तीन", "three") to 3,
        listOf("4", "चार", "four") to 4,
        listOf("5", "पाँच", "पांच", "five") to 5
    )
    for ((words, value) in map) {
        if (value <= max && words.any { t.contains(it) }) return value
    }
    return null
}

fun findPackages(command: String): List<String> {
    val c = command.lowercase()
    return when {
        c.contains("youtube") || c.contains("यूट्यूब") || c.contains("यूटयूब") ->
            listOf("com.google.android.youtube")
        c.contains("instagram") || c.contains("इंस्टाग्राम") || c.contains("इंस्टा") ->
            listOf("com.instagram.android")
        c.contains("maps") || c.contains("मैप") ->
            listOf("com.google.android.apps.maps")
        c.contains("calculator") || c.contains("calc") || c.contains("कैलकुलेटर") || c.contains("कैलक्युलेटर") ->
            listOf("com.vivo.calculator", "com.google.android.calculator", "com.android.calculator2")
        c.contains("camera") || c.contains("कैमरा") ->
            listOf("com.android.camera", "com.android.camera2")
        c.contains("settings") || c.contains("सेटिंग") ->
            listOf("com.android.settings")
        else -> emptyList()
    }
}

fun openWhatsApp(context: Context, number: String, message: String) {
    var digits = number.filter { it.isDigit() }
    if (digits.length == 10) digits = "91$digits"
    val text = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
    val url = "https://wa.me/$digits?text=$text"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage("com.whatsapp")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class VeerController(
    private val context: Context,
    private val speak: (String, () -> Unit) -> Unit,
    private val setStatus: (String) -> Unit
) {
    private var mode = "idle"
    private var choices: List<ContactItem> = emptyList()
    private var targetName = ""
    private var targetNumber = ""
    private val handler = Handler(Looper.getMainLooper())

    // आदेश सुनने के लिए माइक चालू करो
    fun listen() {
        setStatus("सुन रहा हूँ...")
        startListening(context, onResult = { handle(it) }, onStatus = setStatus)
    }

    // पहले बोलो, बोलना खत्म होने पर सुनो
    private fun ask(question: String, nextMode: String) {
        mode = nextMode
        speak(question) { listen() }
    }

    private fun say(text: String) {
        mode = "idle"
        speak(text) { setStatus("बोलो") }
    }

    private fun handle(alternatives: List<String>) {
        val heard = alternatives.firstOrNull { it.isNotBlank() } ?: ""
        if (heard.isBlank()) {
            say("कुछ सुनाई नहीं दिया, फिर से बोलिए")
            return
        }
        setStatus("आपने कहा: $heard")

        when (mode) {
            "ask_name" -> searchContact(heard)
            "ask_choice" -> pickContact(heard)
            "ask_message" -> sendMessage(heard)
            else -> {
                if (!isWakeWord(heard) && mode == "idle") {
                    // बिना वीर कहे भी आदेश मान लो, पर बातचीत की तरह
                }
                if (isMessageCommand(heard)) {
                    val name = extractName(heard)
                    if (name.isBlank()) ask("जी बोलिए, किसे मैसेज भेजना है?", "ask_name")
                    else searchContact(name)
                } else {
                    openApp(heard)
                }
            }
        }
    }

    private fun findContacts(name: String): List<ContactItem> {
        val result = mutableListOf<ContactItem>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val cName = cursor.getString(0) ?: continue
                    val cNum = cursor.getString(1) ?: continue
                    if (result.none { it.number == cNum }) result.add(ContactItem(cName, cNum))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return result
    }

    private fun searchContact(rawName: String) {
        val name = extractName(rawName).ifBlank { rawName }
        val found = findContacts(name)
        when {
            found.isEmpty() -> say("$name नाम का कॉन्टैक्ट नहीं मिला। कॉन्टैक्ट की अनुमति जाँचिए")
            found.size == 1 -> {
                targetName = found[0].name
                targetNumber = found[0].number
                ask("$targetName को क्या संदेश भेजूँ?", "ask_message")
            }
            else -> {
                choices = found.take(5)
                val options = choices.mapIndexed { i, c ->
                    "${i + 1}, ${c.name}, आखिरी अंक ${c.number.takeLast(4).toList().joinToString(" ")}"
                }.joinToString(". ")
                ask("$name नाम के ${choices.size} कॉन्टैक्ट मिले। $options. कौन सा चुनूँ? एक या दो बोलिए", "ask_choice")
            }
        }
    }

    private fun pickContact(heard: String) {
        val idx = pickIndex(heard, choices.size)
        if (idx == null) {
            ask("कृपया नंबर बोलिए, जैसे एक या दो", "ask_choice")
            return
        }
        val c = choices[idx - 1]
        targetName = c.name
        targetNumber = c.number
        ask("ठीक है, ${c.name} को क्या संदेश भेजूँ?", "ask_message")
    }

    private fun sendMessage(text: String) {
        openWhatsApp(context, targetNumber, text)
        say("$targetName के चैट में संदेश भर दिया है। भेजने के लिए WhatsApp में भेजें बटन दबाइए")
    }

    private fun openApp(heard: String) {
        val pkgs = findPackages(heard)
        if (pkgs.isEmpty()) {
            say("जी, आपने कहा $heard")
            return
        }
        val launch = pkgs
            .asSequence()
            .mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            say("जी भाई, खोल रहा हूँ")
        } else {
            say("माफ कीजिए, यह ऐप फोन में नहीं मिला")
        }
    }
}

@Composable
fun VeerScreen(onSpeak: (String, () -> Unit) -> Unit) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("नमस्ते, मैं वीर AI हूँ") }
    val controller = remember {
        VeerController(context, onSpeak) { statusText = it }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            controller.listen()
        } else {
            statusText = "माइक की अनुमति नहीं मिली"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = statusText)
        Button(
            onClick = {
                val missing = listOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS
                ).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) controller.listen()
                else permissionLauncher.launch(missing.toTypedArray())
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("बोलो")
        }
    }
}

fun startListening(
    context: Context,
    onResult: (List<String>) -> Unit,
    onStatus: (String) -> Unit
) {
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onStatus("सुन रहा हूँ...") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStatus("समझ रहा हूँ...") }
        override fun onError(error: Int) {
            onStatus("कुछ समझ नहीं आया, फिर से बोलिए")
            recognizer.destroy()
        }
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            onResult(list?.toList() ?: emptyList())
            recognizer.destroy()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }
    recognizer.startListening(intent)
}
