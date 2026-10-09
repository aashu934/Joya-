package com.example.jarvis
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
class MainActivity : Activity() {
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
                tts?.language = Locale("en", "IN")
                val all = tts?.voices ?: emptySet<Voice>()
                voices = all.filter {
                    (it.locale.language == "en" || it.locale.language == "hi") &&
                        !it.features.contains("notInstalled")
                }.sortedBy { it.name }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.dp)
            layoutParams = LinearLayout.LayoutParams((140 * d).toInt(), (140 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = "Jarvis Voice Assistant\n\nPehle 1, 2 aur 3 karo, phir Start dabao.\nNaam bolke bulao, jaise \"Jarvis, open whatsapp\"."
            textSize = 15f
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
        })
        val nameBox = EditText(this).apply {
            hint = "Assistant ka naam (jaise Jarvis)"
            setText(prefs.getString("name", "jarvis"))
            setSingleLine(true)
        }
        root.addView(nameBox)
        fun addButton(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }
        addButton("Naam save karo") {
            prefs.edit().putString("name", nameBox.text.toString().trim()).apply()
            Toast.makeText(this, "Naam save ho gaya. Stop → Start karo", Toast.LENGTH_SHORT).show()
        }
        addButton("1. Permissions do") { askPermissions() }
        addButton("2. Overlay permission (apps kholne ke liye)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton("3. WhatsApp auto-send (Accessibility ON karo)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("Awaaz badlo (dabate jao, sunte jao)") { nextVoice() }
        addButton("Awaaz patli / mothi (pitch)") { nextPitch() }
        addButton("Start") { startAssistant() }
        addButton("Stop") { stopService(Intent(this, VoiceService::class.java)) }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
    private fun sample() {
        tts?.speak("Hi, main aapki assistant hu", TextToSpeech.QUEUE_FLUSH, null, "s")
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
        sample()
    }
    private fun nextPitch() {
        val p = jarvisPrefs(this)
        val cur = p.getFloat("pitch", 1.35f)
        val i = pitches.indexOfFirst { abs(it - cur) < 0.01f }
        val next = pitches[(if (i < 0) 0 else i + 1) % pitches.size]
        p.edit().putFloat("pitch", next).apply()
        tts?.setPitch(next)
        Toast.makeText(this, "Pitch: $next", Toast.LENGTH_SHORT).show()
        sample()
    }
    private fun askPermissions() {
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
        requestPermissions(perms.toTypedArray(), 1)
    }
    private fun startAssistant() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Pehle permissions do", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Jarvis shuru ho gaya", Toast.LENGTH_SHORT).show()
    }
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
