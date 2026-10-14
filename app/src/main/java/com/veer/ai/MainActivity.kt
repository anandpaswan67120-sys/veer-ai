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
import kotlin.math.abs

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
data class AppItem(val label: String, val pkg: String)

// बोलते समय इन शब्दों को हटा देते हैं
val fillerWords = setOf(
    "वीर", "veer", "खोलो", "खोल", "खोलिए", "ओपन", "open", "को", "ko",
    "ऐप", "app", "कर", "करो", "जरा", "please"
)

// बोले गए आम नाम को ऐप के असली नाम से जोड़ने के लिए
val appAliases = mapOf(
    "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp",
    "यूट्यूब" to "youtube", "इंस्टाग्राम" to "instagram", "इंस्टा" to "instagram",
    "फेसबुक" to "facebook", "क्रोम" to "chrome", "जीमेल" to "gmail",
    "स्पॉटिफाई" to "spotify", "पेटीएम" to "paytm", "फोटो" to "photos",
    "गूगल मैप्स" to "maps", "मैप्स" to "maps", "मैप" to "maps",
    "कैमरा" to "camera", "कैलकुलेटर" to "calculator", "कैलक्युलेटर" to "calculator",
    "सेटिंग्स" to "settings", "सेटिंग" to "settings", "कैलेंडर" to "calendar",
    "नोट्स" to "notes", "गूगल" to "google"
)

fun cleanAppQuery(heard: String): String {
    val words = heard.split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in fillerWords }
    var q = words.joinToString(" ")
    for ((hi, en) in appAliases.entries.sortedByDescending { it.key.length }) {
        q = q.replace(hi, en, ignoreCase = true)
    }
    return q
}

fun normalize(s: String): String = s.lowercase().replace(" ", "")

fun installedApps(context: Context): List<AppItem> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = context.packageManager
    return pm.queryIntentActivities(intent, 0)
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
}

fun findApp(context: Context, query: String): AppItem? {
    val q = normalize(query)
    if (q.isBlank()) return null
    val apps = installedApps(context)
    val exact = apps.firstOrNull { normalize(it.label) == q }
    if (exact != null) return exact
    return apps
        .filter { app ->
            val l = normalize(app.label)
            l.isNotBlank() && (l.contains(q) || (l.length >= 3 && q.contains(l)))
        }
        .minByOrNull { abs(normalize(it.label).length - q.length) }
}

fun launchApp(context: Context, app: AppItem): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return true
}

fun isSendCommand(heard: String): Boolean {
    val c = heard.lowercase()
    return listOf("मैसेज", "मेसेज", "message", "संदेश", "भेज", "send").any { c.contains(it) }
}

fun findContacts(context: Context, name: String): List<ContactItem> {
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        statusText = if (results[Manifest.permission.RECORD_AUDIO] == true) {
            "अनुमति मिल गई, अब बोलिए"
        } else {
            "माइक की अनुमति नहीं मिली"
        }
    }

    fun speakAndShow(text: String) {
        statusText = text
        onSpeak(text)
    }

    fun handleVoice(list: List<String>) {
        val heardAll = list.filter { it.isNotBlank() }
        if (heardAll.isEmpty()) {
            speakAndShow("कुछ सुनाई नहीं दिया, फिर से बोलिए")
            return
        }
        statusText = "आपने कहा: ${heardAll.first()}"

        if (heardAll.any { isSendCommand(it) }) {
            speakAndShow("मैसेज वाला हिस्सा अभी बंद है। नीचे नाम टाइप करके मैसेज भेजो")
            return
        }

        for (h in heardAll) {
            val app = findApp(context, cleanAppQuery(h))
            if (app != null) {
                if (launchApp(context, app)) speakAndShow("जी भाई, ${app.label} खोल रहा हूँ")
                else speakAndShow("${app.label} नहीं खुल पाया")
                return
            }
        }
        val asked = cleanAppQuery(heardAll.first()).ifBlank { heardAll.first() }
        speakAndShow("$asked नाम का ऐप फोन में नहीं मिला")
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
                if (name.isBlank()) {
                    statusText = "पहले नाम लिखिए"
                } else if (messageInput.isBlank()) {
                    statusText = "पहले संदेश लिखिए"
                } else {
                    val found = findContacts(context, name)
                    if (found.size == 1) {
                        openWhatsApp(context, found[0].number, messageInput)
                        speakAndShow("${found[0].name} के चैट में संदेश भर दिया। WhatsApp में भेजें दबाइए")
                        choices = emptyList()
                    } else if (found.isEmpty()) {
                        speakAndShow("$name नाम का कॉन्टैक्ट नहीं मिला")
                    } else {
                        choices = found.take(5)
                        statusText = "नीचे से कॉन्टैक्ट चुनिए"
                    }
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("मैसेज भेजो")
        }

        choices.forEach { c ->
            Button(
                onClick = {
                    openWhatsApp(context, c.number, messageInput)
                    speakAndShow("${c.name} के चैट में संदेश भर दिया")
                    choices = emptyList()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text("${c.name} (आखिरी अंक ${c.number.takeLast(4)})")
            }
        }

        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                } else {
                    startListening(
                        context,
                        onResult = { handleVoice(it) },
                        onStatus = { statusText = it }
                    )
                }
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("बोलो")
        }
    }
}
