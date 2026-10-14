package com.veer.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
                    VeerScreen(
                        onSpeak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

// सुनी हुई बात से ऐप के संभावित पैकेज नाम चुनना
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

@Composable
fun VeerScreen(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("नमस्ते, मैं वीर AI हूँ") }

    fun handleCommand(alternatives: List<String>) {
        val matched = alternatives.firstOrNull { findPackages(it).isNotEmpty() }
        val heard = matched ?: alternatives.firstOrNull() ?: ""
        statusText = "आपने कहा: $heard"

        val pkgs = findPackages(heard)
        if (pkgs.isEmpty()) {
            onSpeak("जी, आपने कहा $heard")
            return
        }

        val launch = pkgs
            .asSequence()
            .mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            onSpeak("जी भाई, खोल रहा हूँ")
        } else {
            onSpeak("माफ कीजिए, यह ऐप फोन में नहीं मिला")
            statusText = "ऐप नहीं मिला"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening(context, onResult = { handleCommand(it) }, onStatus = { statusText = it })
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
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startListening(context, onResult = { handleCommand(it) }, onStatus = { statusText = it })
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("बोलो")
        }
    }
}

fun startListening(
    context: android.content.Context,
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
