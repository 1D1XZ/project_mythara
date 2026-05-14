package com.mythara.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/**
 * Mythara Wear OS companion. Single screen, single big PTT button.
 *
 * Flow:
 *  1. Tap the mic → request RECORD_AUDIO if not granted
 *  2. Start a local SpeechRecognizer (on-device on Wear 4+)
 *  3. Live partial → small live transcript above the button
 *  4. On Final → send the transcript to the paired phone via
 *     [com.google.android.gms.wearable.MessageClient] under path
 *     [WearPaths.PTT_SUBMIT], then auto-stop.
 *  5. Phone-side WearableListenerService runs the agent + speaks back
 *     via its TTS (which routes through the paired BT audio of the
 *     watch when active).
 *
 * No Hilt — the wear module is intentionally tiny, no DB or service
 * deps. Everything else lives in the phone-side process.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold {
                    PttScreen()
                }
            }
        }
    }
}

@Composable
private fun PttScreen() {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("tap to speak") }
    var recognizer: SpeechRecognizer? by remember { mutableStateOf(null) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            status = "ready"
        } else {
            status = "mic denied"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "MYTHARA",
            color = Color(0xFF6B50FF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        // Live partial transcript above the mic.
        Text(
            text = partial.ifBlank { status },
            color = if (partial.isBlank()) Color(0xFF999999) else Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        // The mic button.
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(if (listening) Color(0xFF68FFD6) else Color(0xFF6B50FF))
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                .clickable {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@clickable
                    }
                    if (listening) {
                        recognizer?.stopListening()
                        return@clickable
                    }
                    // Start listening.
                    listening = true
                    partial = ""
                    status = "listening…"
                    val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            listening = false
                            status = "error $error"
                            sr.destroy()
                            recognizer = null
                        }
                        override fun onResults(results: Bundle?) {
                            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                .orEmpty()
                            val text = texts.firstOrNull()?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                sendToPhone(ctx, text)
                                status = "sent"
                                partial = text
                            } else {
                                status = "no speech"
                            }
                            listening = false
                            sr.destroy()
                            recognizer = null
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                .orEmpty()
                            partial = texts.firstOrNull().orEmpty()
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    }
                    sr.startListening(intent)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (listening) "■" else "🎤",
                color = Color.Black,
                fontSize = 28.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "tap = speak  ·  tap again = stop",
            color = Color(0xFF666666),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Fire-and-forget: send the transcript to the paired phone via the
 * Wearable Data Layer. Goes to every node the watch is paired with
 * (typically just one) under the [WearPaths.PTT_SUBMIT] path.
 */
private fun sendToPhone(ctx: android.content.Context, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val nodeClient = Wearable.getNodeClient(ctx)
    val msgClient = Wearable.getMessageClient(ctx)
    nodeClient.connectedNodes
        .addOnSuccessListener { nodes ->
            for (node in nodes) {
                msgClient.sendMessage(node.id, WearPaths.PTT_SUBMIT, bytes)
                    .addOnSuccessListener {
                        Log.d(TAG, "PTT submitted to ${node.displayName}: \"${text.take(60)}\"")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "PTT send to ${node.displayName} failed: ${e.message}")
                    }
            }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "could not list connected nodes: ${e.message}")
        }
}

private const val TAG = "Mythara/Wear"

/**
 * Message paths shared between the watch and phone modules. Kept
 * tiny so the wear module doesn't need to depend on anything from
 * the phone app. Keep these in sync with the phone-side
 * MytharaWearListenerService.
 */
object WearPaths {
    const val PTT_SUBMIT = "/mythara/ptt/submit"
}
