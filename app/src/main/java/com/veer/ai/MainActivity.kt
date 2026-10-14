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
import androidx.compose.foundation.layout.Row
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

@Composable
fun VeerScreen(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("नमस्ते, मैं वीर AI हूँ") }
    var nameInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<ContactItem>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusText = if (granted) "अनुमति मिल गई, अब नाम लिखकर भेजें" else "कॉन्टैक्ट की अनुमति नहीं मिली"
    }

    fun sendTo(contact: ContactItem) {
        openWhatsApp(context, contact.number, messageInput)
        statusText = "${contact.name} के चैट में संदेश भर दिया। WhatsApp में भेजें दबाइए"
        onSpeak("${contact.name} के चैट में संदेश भर दिया है")
        choices = emptyList()
    }

    fun onSendClick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        val name = nameInput.trim()
        if (name.isBlank()) { statusText = "पहले नाम लिखिए"; return }
        if (messageInput.isBlank()) { statusText = "पहले संदेश लिखिए"; return }

        val found = findContacts(context, name)
        when {
            found.isEmpty() -> {
                statusText = "$name नाम का कॉन्टैक्ट नहीं मिला"
                onSpeak("$name नाम का कॉन्टैक्ट नहीं मिला")
            }
            found.size == 1 -> sendTo(found[0])
            else -> {
                choices = found.take(5)
                statusText = "${choices.size} कॉन्टैक्ट मिले, नीचे से चुनिए"
                onSpeak("${choices.size} कॉन्टैक्ट मिले, नीचे से चुनिए")
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

        Button(onClick = { onSendClick() }, modifier = Modifier.padding(top = 12.dp)) {
            Text("मैसेज भेजो")
        }

        choices.forEach { c ->
            Button(onClick = { sendTo(c) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("${c.name} (आखिरी अंक ${c.number.takeLast(4)})")
            }
        }

        Button(onClick = { onSpeak("नमस्ते, मैं वीर AI हूँ") }, modifier = Modifier.padding(top = 16.dp)) {
            Text("बोलो")
        }
    }
}
