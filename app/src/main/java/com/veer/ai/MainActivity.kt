package com.veer.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
                    VeerScreen(onSpeak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) })
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

data class ContactItem(val name: String, val number: String)

// बोले गए वाक्य से सिर्फ नाम निकालना
val commandWords = setOf(
    "वीर", "veer", "को", "ko", "मैसेज", "मेसेज", "message", "संदेश", "भेजो", "भेज",
    "भेजना", "भेजिए", "send", "करो", "कर", "लिखो", "बोलो", "कि", "व्हाट्सएप", "whatsapp"
)

fun extractName(heard: String): String =
    heard.split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in commandWords }
        .joinToString(" ")

fun isSendCommand(heard: String): Boolean {
    val c = heard.lowercase()
    return listOf("मैसेज", "मेसेज", "message", "संदेश", "भेज", "send", "व्हाट्सएप", "whatsapp")
        .any { c.contains(it) }
}

fun findContacts(context: Context, name: String): List<ContactItem> {
    val result = mutableListOf<ContactItem>()
    // पूरा नाम और हर शब्द अलग-अलग दोनों से खोजो
    val terms = (listOf(name) + name.split(" ").filter { it.length >= 2 }).distinct()
    try {
        for (term in terms) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$term%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val cName = cursor.getString(0) ?: continue
                    val cNum = cursor.getString(1) ?: continue
                    if (result.none { it.number == cNum }) result.add(ContactItem(cName, cNum))
                }
            }
        }
    } catch (e: SecurityException) {
        return emptyList()
    }
    // पूरे नाम से मेल खाने वाले कॉन्टैक्ट सबसे ऊपर
    return result.sortedByDescending { c ->
        name.split(" ").count { w -> c.name.contains(w, ignoreCase = true) }
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

fun findPackages(command: String): List<String> {
    val c = command.lowercase()
    return when {
        c.contains("youtube") || c.contains("यूट्यूब") -> listOf("com.google.android.youtube")
        c.contains("instagram") || c.contains("इंस्टाग्राम") -> listOf("com.instagram.android")
        c.contains("maps") || c.contains("मैप") -> listOf("com.google.android.apps.maps")
        c.contains("calculator") || c.contains("कैलकुलेटर") ->
            listOf("com.vivo.calculator", "com.google.android.calculator", "com.android.calculator2")
        c.contains("camera") || c.contains("कैमरा") -> listOf("com.android.camera", "com.android.camera2")
        c.contains("settings") || c.contains("सेटिंग") -> listOf("com.android.settings")
        else -> emptyList()
    }
}

fun openApp(context: Context, heard: String): String {
    val pkgs = findPackages(heard)
    if (pkgs.isEmpty()) return "जी, आपने कहा $heard"
    val launch = pkgs.asSequence()
        .mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
        .firstOrNull()
    return if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        "जी भाई, खोल रहा हूँ"
    } else {
        "माफ कीजिए, यह ऐप फोन में नहीं मिला"
    }
}

fun startListening(context: Context, onResult: (List<String>) -> Unit, onStatus: (String) -> Unit) {
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

@Composable
fun VeerScreen(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("नमस्ते, मैं वीर AI हूँ") }
    var nameInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    // pendingContact: कॉन्टैक्ट चुना जा चुका है, अब संदेश बोलना है
    var pendingContact by remember { mutableStateOf<ContactItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        statusText = if (results[Manifest.permission.RECORD_AUDIO] == true &&
            results[Manifest.permission.READ_CONTACTS] == true
        ) "अनुमति मिल गई, अब बोलिए" else "माइक या कॉन्टैक्ट की अनुमति नहीं मिली"
    }

    fun sendTo(contact: ContactItem, message: String) {
        openWhatsApp(context, contact.number, message)
        val msg = "${contact.name} के चैट में संदेश भर दिया। WhatsApp में भेजें दबाइए"
        statusText = msg
        onSpeak(msg)
        choices = emptyList()
        pendingContact = null
    }

    fun listenOnce(onText: (String) -> Unit) {
        startListening(context, onResult = { list ->
            val heard = list.firstOrNull { it.isNotBlank() } ?: ""
            if (heard.isBlank()) {
                statusText = "कुछ सुनाई नहीं दिया, फिर से बोलिए"
                onSpeak("कुछ सुनाई नहीं दिया, फिर से बोलिए")
            } else onText(heard)
        }, onStatus = { statusText = it })
    }

    // कॉन्टैक्ट मिलने के बाद चुने हुए कॉन्टैक्ट को संदेश बोलकर भेजना
    fun askMessageAndSend(contact: ContactItem) {
        pendingContact = contact
        choices = emptyList()
        val q = "${contact.name} को क्या संदेश भेजूँ?"
        statusText = q
        onSpeak(q)
        listenOnce { msg -> sendTo(contact, msg) }
    }

    fun handleName(rawName: String) {
        val name = extractName(rawName)
        if (name.isBlank()) {
            statusText = "नाम दोबारा बोलिए"
            onSpeak("नाम दोबारा बोलिए")
            listenOnce { handleName(it) }
            return
        }
        nameInput = name
        val found = findContacts(context, name)
        when {
            found.isEmpty() -> {
                statusText = "$name नाम का कॉन्टैक्ट नहीं मिला। नाम दोबारा बोलिए"
                onSpeak("$name नाम का कॉन्टैक्ट नहीं मिला। नाम दोबारा बोलिए")
                listenOnce { handleName(it) }
            }
            found.size == 1 -> askMessageAndSend(found[0])
            else -> {
                choices = found.take(5)
                val q = "$name के ${choices.size} कॉन्टैक्ट मिले। नीचे से चुनिए, या बोलिए एक, दो, तीन"
                statusText = q
                onSpeak(q)
                listenOnce { pick ->
                    val idx = listOf("एक" to 1, "1" to 1, "दो" to 2, "2" to 2, "तीन" to 3, "3" to 3, "चार" to 4, "4" to 4, "पाँच" to 5, "5" to 5)
                        .firstOrNull { pick.contains(it.first) }?.second
                    if (idx != null && idx <= choices.size) askMessageAndSend(choices[idx - 1])
                    else {
                        statusText = "नीचे से कॉन्टैक्ट चुनिए"
                        onSpeak("नीचे से कॉन्टैक्ट चुनिए")
                    }
                }
            }
        }
    }

    fun startVoiceFlow() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS))
            return
        }
        listenOnce { heard ->
            if (isSendCommand(heard)) {
                val name = extractName(heard)
                if (name.isBlank()) {
                    statusText = "किसे मैसेज भेजना है? नाम बोलिए"
                    onSpeak("किसे मैसेज भेजना है?")
                    listenOnce { handleName(it) }
                } else handleName(heard)
            } else {
                val reply = openApp(context, heard)
                statusText = reply
                onSpeak(reply)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = statusText)

        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("किसे भेजना है (नाम)") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = messageInput,
            onValueChange = { messageInput = it },
            label = { Text("संदेश") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Button(
            onClick = {
                val name = nameInput.trim()
                if (name.isBlank()) { statusText = "पहले नाम लिखिए"; return@Button }
                if (messageInput.isBlank()) { statusText = "पहले संदेश लिखिए"; return@Button }
                val found = findContacts(context, name)
                when {
                    found.size == 1 -> sendTo(found[0], messageInput)
                    found.isEmpty() -> statusText = "$name नाम का कॉन्टैक्ट नहीं मिला"
                    else -> { choices = found.take(5); statusText = "नीचे से चुनिए" }
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("मैसेज भेजो")
        }

        choices.forEach { c ->
            Button(
                onClick = {
                    if (messageInput.isNotBlank()) sendTo(c, messageInput)
                    else askMessageAndSend(c)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text("${c.name} (आखिरी अंक ${c.number.takeLast(4)})")
            }
        }

        Button(onClick = { startVoiceFlow() }, modifier = Modifier.padding(top = 16.dp)) {
            Text("बोलो")
        }
    }
}
