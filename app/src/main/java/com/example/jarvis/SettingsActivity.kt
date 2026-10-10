package com.example.jarvis
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
fun requestAllPermissions(a: Activity) {
    val perms = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )
    if (Build.VERSION.SDK_INT >= 26) perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
    if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
    a.requestPermissions(perms.toTypedArray(), 1)
}
class SettingsActivity : Activity() {
    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()
    private var voiceIdx = -1
    private val pitches = floatArrayOf(1.0f, 1.2f, 1.35f, 1.5f, 1.7f)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val prefs = jarvisPrefs(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val all = tts?.voices ?: emptySet<Voice>()
                voices = all.filter {
                    (it.locale.language == "en" || it.locale.language == "hi") &&
                        !it.features.contains("notInstalled")
                }.sortedWith(compareBy<Voice>({ it.locale.language != "hi" }, { it.name }))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "AI dimaag ke liye: aistudio.google.com pe jaao, Get API key dabao (free), key yahan paste karo aur Save dabao. Key hogi to assistant Hindi me baat karegi aur sab kuch samjhegi."
            textSize = 13f
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        })
        val nameBox = EditText(this).apply {
            hint = "Assistant ka naam (jaise Jarvis)"
            setText(prefs.getString("name", "jarvis"))
            setSingleLine(true)
        }
        val keyBox = EditText(this).apply {
            hint = "Gemini API key"
            setText(prefs.getString("api_key", ""))
            setSingleLine(true)
        }
        val modelBox = EditText(this).apply {
            hint = "Model (khali chhodo to automatic)"
            setText(prefs.getString("model", ""))
            setSingleLine(true)
        }
        root.addView(nameBox)
        root.addView(keyBox)
        root.addView(modelBox)
        fun addButton(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }
        addButton("Sab save karo (naam, key, model)") {
            prefs.edit()
                .putString("name", nameBox.text.toString().trim())
                .putString("api_key", keyBox.text.toString().trim())
                .putString("model", modelBox.text.toString().trim())
                .apply()
            Toast.makeText(this, "Save ho gaya. Home pe Stop dabake dobara Start karo", Toast.LENGTH_LONG).show()
        }
        addButton("1. Permissions do") { requestAllPermissions(this) }
        addButton("2. Overlay permission (apps kholne ke liye)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton("3. WhatsApp auto-send (Accessibility ON karo)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("Hindi awaaz install karo (TTS settings)") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        addButton("Awaaz badlo (dabate jao, sunte jao)") { nextVoice() }
        addButton("Awaaz patli / mothi (pitch)") { nextPitch() }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
    private fun sample(v: Voice?) {
        val hi = v?.locale?.language == "hi"
        val text = if (hi) "नमस्ते, मैं आपकी असिस्टेंट हूँ। बताइए, क्या करना है?" else "Hello, I am your assistant"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s")
    }
    private fun nextVoice() {
        if (voices.isEmpty()) {
            Toast.makeText(this, "Koi awaaz nahi mili", Toast.LENGTH_SHORT).show()
            return
        }
        voiceIdx = (voiceIdx + 1) % voices.size
        val v = voices[voiceIdx]
        tts?.voice = v
        tts?.setPitch(jarvisPrefs(this).getFloat("pitch", 1.35f))
        jarvisPrefs(this).edit().putString("voice_name", v.name).apply()
        Toast.makeText(this, "Awaaz ${voiceIdx + 1}/${voices.size}: ${v.name}", Toast.LENGTH_SHORT).show()
        sample(v)
    }
    private fun nextPitch() {
        val p = jarvisPrefs(this)
        val cur = p.getFloat("pitch", 1.35f)
        val i = pitches.indexOfFirst { abs(it - cur) < 0.01f }
        val next = pitches[(if (i < 0) 0 else i + 1) % pitches.size]
        p.edit().putFloat("pitch", next).apply()
        tts?.setPitch(next)
        Toast.makeText(this, "Pitch: $next", Toast.LENGTH_SHORT).show()
        sample(tts?.voice)
    }
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
