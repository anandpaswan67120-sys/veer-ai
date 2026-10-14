package com.veer.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
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
data class AppItem(val label: String, val pkg: String)

// ---------- हिंदी को अंग्रेज़ी में बदलने वाली सूची (आवाज़ के लिए) ----------
val hindiToEnglish = mapOf(
    "फ्लिपकार्ट" to "flipkart", "फ्लिप कार्ट" to "flipkart", "फ्लिपकार्ट" to "flipkart",
    "अजियो" to "ajio", "एजियो" to "ajio", "मीशो" to "meesho", "मीसो" to "meesho",
    "अमेज़न" to "amazon", "अमेजन" to "amazon", "ज़ोमैटो" to "zomato", "जोमैटो" to "zomato",
    "स्विगी" to "swiggy", "ओला" to "ola", "उबर" to "uber", "रैपिडो" to "rapido",
    "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp",
    "यूट्यूब" to "youtube", "इंस्टाग्राम" to "instagram", "इंस्टा" to "instagram",
    "फेसबुक" to "facebook", "क्रोम" to "chrome", "जीमेल" to "gmail",
    "स्पॉटिफाई" to "spotify", "पेटीएम" to "paytm", "फोटो" to "photos",
    "गूगल मैप्स" to "maps", "मैप्स" to "maps", "मैप" to "maps",
    "कैमरा" to "camera", "कैलकुलेटर" to "calculator", "कैलक्युलेटर" to "calculator",
    "सेटिंग्स" to "settings", "सेटिंग" to "settings", "कैलेंडर" to "calendar",
    "नोट्स" to "notes", "गूगल" to "google", "जूम" to "zoom", "नेटफ्लिक्स" to "netflix",
    "हॉटस्टार" to "hotstar", "प्राइम" to "prime", "ट्विटर" to "twitter", "एक्स" to "x"
)

// ---------- टॉर्च ----------
fun torchCommand(heard: String): Boolean? {
    val c = heard.lowercase()
    val torchWords = listOf("टॉर्च", "टार्च", "torch", "फ्लैश", "फ्लैशलाइट", "flashlight", "flash", "लाइट", "light")
    if (torchWords.none { c.contains(it) }) return null
    val offWords = listOf("बंद", "बुझा", "off", "ऑफ")
    val onWords = listOf("चालू", "जला", "शुरू", "on", "ऑन")
    val off = offWords.any { c.contains(it) }
    val on = onWords.any { c.contains(it) }
    return when {
        off -> false
        on -> true
        else -> null
    }
}

fun setTorch(context: Context, on: Boolean): String {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            "इस फोन में टॉर्च नहीं मिली"
        } else {
            cm.setTorchMode(id, on)
            if (on) "टॉर्च चालू कर दी" else "टॉर्च बंद कर दी"
        }
    } catch (e: Exception) {
        "टॉर्च चालू नहीं हो पाई"
    }
}

// ---------- आवाज़ ----------
fun volumeCommand(context: Context, heard: String): String? {
    val c = heard.lowercase()
    val isVolume = listOf("आवाज", "वॉल्यूम", "वोल्यूम", "volume", "साउंड", "sound").any { c.contains(it) }
    if (!isVolume) return null
    val raise = listOf("बढ़ा", "बढा", "तेज", "ज्यादा", "ज़्यादा", "up").any { c.contains(it) }
    val lower = listOf("घटा", "कम", "धीमी", "धीमा", "down").any { c.contains(it) }
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return when {
        raise -> {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "आवाज बढ़ा दी"
        }
        lower -> {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "आवाज घटा दी"
        }
        else -> "आवाज बढ़ानी है या घटानी है?"
    }
}

// ---------- गाना (यूट्यूब खोज) ----------
val songWords = listOf("गाना", "गाने", "गीत", "song", "बजा", "सुनाओ")

fun isSongCommand(heard: String): Boolean {
    val c = heard.lowercase()
    return songWords.any { c.contains(it) }
}

val songFillers = setOf(
    "वीर", "बजा", "बजाओ", "दो", "गाना", "गाने", "गीत", "नया", "नई", "नयी",
    "trending", "ट्रेंडिंग", "वाला", "वाली", "का", "की", "के", "song", "songs",
    "play", "सुनाओ", "चलाओ", "यूट्यूब", "youtube", "पर", "new", "ओपन", "खोलो"
)

fun songQuery(heard: String): String {
    val base = heard.split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in songFillers }
        .joinToString(" ")
    return if (base.isBlank()) "trending" else base
}

fun playSong(context: Context, query: String): String {
    val q = "$query new song"
    val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.youtube")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        "यूट्यूब पर $query खोल रहा हूँ, पहला गाना दबाइए"
    } catch (e: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "$query का गाना खोल रहा हूँ"
    }
}

// ---------- सभी ऐप ----------
val fillerWords = setOf(
    "वीर", "veer", "खोलो", "खोल", "खोलिए", "ओपन", "open", "को", "ko",
    "ऐप", "app", "कर", "करो", "जरा", "please", "on", "ऑन"
)

// बोली गई बात को ऐप के नाम के करीब लाना
fun cleanAppQuery(heard: String): String {
    val words = heard.split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in fillerWords }
    var q = words.joinToString(" ")
    for ((hi, en) in hindiToEnglish.entries.sortedByDescending { it.key.length }) {
        q = q.replace(hi, en, ignoreCase = true)
    }
    return q
}

// अक्षर, अंक के अलावा सब हटाकर तुलना के लिए छोटा करना
fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

fun installedApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
}

// ऐप ढूँढना: पूरा मेल, फिर एक-दूसरे में शामिल होना, फिर शब्दों का मेल
fun findApp(context: Context, query: String): AppItem? {
    val q = normalize(query)
    if (q.isBlank()) return null
    val apps = installedApps(context)

    apps.firstOrNull { normalize(it.label) == q }?.let { return it }

    apps.filter { app ->
        val l = normalize(app.label)
        l.isNotBlank() && (l.contains(q) || q.contains(l))
    }.minByOrNull { abs(normalize(it.label).length - q.length) }?.let { return it }

    val qWords = query.lowercase().split(" ").filter { it.length >= 3 }
    if (qWords.isNotEmpty()) {
        apps.firstOrNull { app ->
            val label = app.label.lowercase()
            qWords.any { w -> label.contains(w) }
        }?.let { return it }
    }
    return null
}

fun launchApp(context: Context, app: AppItem): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return true
}

// ---------- मैसेज (टाइप वाला) ----------
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

// ---------- बोलने-सुनने का हिस्सा ----------
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

fun commandReply(context: Context, h: String): String? {
    torchCommand(h)?.let { return setTorch(context, it) }
    volumeCommand(context, h)?.let { return it }
    if (isSongCommand(h)) return playSong(context, songQuery(h))
    return null
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

        for (h in heardAll) {
            val reply = commandReply(context, h)
            if (reply != null) {
                speakAndShow(reply)
                return
            }
        }

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
