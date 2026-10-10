package com.example.jarvis
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Locale
import java.util.concurrent.Executors
class VoiceService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "com.example.jarvis.STOP"
        private const val CHANNEL = "jarvis_channel"
        private const val NOTIF_ID = 1
    }
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitDone = false
    private var pendingStart = false
    private var running = false
    private var speaking = false
    private var stopAfterSpeak = false
    private var useLang = true
    private var thinking = false
    private var savedVoice: android.speech.tts.Voice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var activeUntil = 0L
    private var callRegistered = false
    private var overlayView: View? = null
    private val mutedStreams = mutableListOf<Int>()
    private lateinit var commands: CommandHandler
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        commands = CommandHandler(this)
        tts = TextToSpeech(this, this)
    }
    override fun onInit(status: Int) {
        ttsInitDone = true
        if (status == TextToSpeech.SUCCESS) {
            val prefs = jarvisPrefs(this)
            tts?.language = Locale("en", "IN")
            tts?.setPitch(prefs.getFloat("pitch", 1.35f))
            tts?.setSpeechRate(1.0f)
            try {
                val vn = prefs.getString("voice_name", null)
                if (vn != null) savedVoice = tts?.voices?.firstOrNull { it.name == vn }
            } catch (e: Exception) {
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = afterSpeak()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = afterSpeak()
            })
            ttsReady = true
        }
        if (pendingStart) {
            pendingStart = false
            begin()
        }
    }
    private fun afterSpeak() {
        handler.post { finishSpeaking() }
    }
    private fun finishSpeaking() {
        handler.removeCallbacks(speakWatchdog)
        speaking = false
        if (running) UiBus.update("Sun rahi hu...", true)
        if (stopAfterSpeak) shutdown() else listen()
    }
    private fun voiceFor(text: String): String {
        val t = tts ?: return text
        val hasDeva = text.any { it in '\u0900'..'\u097F' }
        val saved = savedVoice
        if (saved != null && (!hasDeva || saved.locale.language == "hi")) {
            t.voice = saved
            return text
        }
        val r = t.setLanguage(if (hasDeva) Locale("hi", "IN") else Locale("en", "IN"))
        if (hasDeva && (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)) {
            t.setLanguage(Locale("en", "IN"))
            return latinize(text)
        }
        return text
    }
    private fun handleLocal(cmd: String): Boolean {
        val l = cmd.lowercase(Locale.ROOT).trim()
        val w = l.split(Regex("\\s+"))
        if (PhoneControl.ringing) {
            val spk = l.contains("speaker") || l.contains("स्पीकर")
            val ans = listOf("utha", "uthao", "pick", "answer", "receive", "उठा", "रिसीव").any { l.contains(it) }
            val rej = listOf("kaat", "cut", "reject", "decline", "काट", "रिजेक्ट", "डिक्लाइन").any { l.contains(it) }
            if (ans) {
                val ok = PhoneControl.answer(this)
                if (ok && spk) handler.postDelayed({ PhoneControl.speaker(this, true) }, 1200)
                speak(if (ok) "ठीक है, कॉल उठा लिया" else "कॉल नहीं उठा पाई, फ़ोन की परमिशन देखिए")
                return true
            }
            if (rej) {
                val ok = PhoneControl.hangup(this)
                speak(if (ok) "कॉल काट दिया" else "कॉल नहीं काट पाई")
                return true
            }
        }
        val bye = listOf("bye", "alvida", "goodbye", "बाय", "अलविदा")
        if (w.size <= 3 && (bye.any { l.contains(it) } || l.contains("band karo") || l.contains("बंद करो"))) {
            speak("ठीक है, अपना ख्याल रखना। बाय!", true)
            return true
        }
        return false
    }
    private fun onBrainReply(asked: String, reply: Brain.Reply?) {
        if (!running) return
        if (reply == null) {
            val r = commands.handle(asked)
            if (r.reply.isNotEmpty()) speak(r.reply, r.stop) else speak("अभी मुझे समझ नहीं आया, फिर से बोलिए")
            return
        }
        var say = reply.say
        var stop = false
        if (reply.command.isNotBlank()) {
            val r = commands.handle(reply.command)
            stop = r.stop
            if (r.reply.contains("nahi", ignoreCase = true) || r.reply.startsWith("Sorry")) say = r.reply
        }
        if (say.isBlank()) say = "ठीक है"
        speak(say, stop)
    }
    private val speakWatchdog = Runnable { if (speaking) finishSpeaking() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startInForeground("Jarvis chal raha hai")
        addOverlay()
        if (!running) {
            running = true
            if (ttsInitDone) begin() else pendingStart = true
        }
        return START_STICKY
    }
    @Suppress("DEPRECATION")
    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return builder
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Band karo", stopPi)
            .build()
    }
    private fun startInForeground(text: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
    @Suppress("DEPRECATION")
    private fun addOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.TOP
            val v = View(this)
            wm.addView(v, lp)
            overlayView = v
        } catch (e: Exception) {
        }
    }
    private fun removeOverlay() {
        val v = overlayView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        overlayView = null
    }
    private fun muteBeep() {
        if (mutedStreams.isNotEmpty()) return
        val am = getSystemService(AudioManager::class.java) ?: return
        for (s in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(s)
                }
            } catch (e: Exception) {
            }
        }
    }
    private fun unmuteBeep() {
        if (mutedStreams.isEmpty()) return
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            for (s in mutedStreams) {
                try {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: Exception) {
                }
            }
        }
        mutedStreams.clear()
    }
    private fun updateNotif(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
        } catch (e: Exception) {
        }
    }
    private fun wakeName(): String =
        (jarvisPrefs(this).getString("name", "jarvis") ?: "").trim().lowercase(Locale.ROOT)
    private fun isName(w: String, name: String): Boolean {
        val a = latinize(w.lowercase(Locale.ROOT)).filter { it.isLetterOrDigit() }
        val n = latinize(name).filter { it.isLetterOrDigit() }
        if (a.isEmpty() || n.isEmpty()) return false
        return a == n || (n.length >= 4 && levenshtein(a, n) <= 1)
    }
    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                PhoneControl.ringing = true
                val who = callerName(phoneNumber)
                handler.post {
                    if (!running) return@post
                    recognizer?.cancel()
                    activeUntil = System.currentTimeMillis() + 30000
                    speak(
                        if (who != null) "$who ka call aa raha hai. Uthau ya kaat du?"
                        else "Call aa raha hai. Uthau ya kaat du?"
                    )
                }
            } else {
                PhoneControl.ringing = false
            }
        }
    }
    private fun callerName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
    @Suppress("DEPRECATION")
    private fun registerCallListener() {
        if (callRegistered) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            callRegistered = true
        } catch (e: Exception) {
        }
    }
    @Suppress("DEPRECATION")
    private fun unregisterCallListener() {
        if (!callRegistered) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
        }
        callRegistered = false
        PhoneControl.ringing = false
    }
    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotif("Speech recognition available nahi hai")
            speak("Is phone me speech recognition nahi hai", true)
            return
        }
        createRecognizer()
        registerCallListener()
        UiBus.update("Sun rahi hu...", true)
        val name = wakeName()
        val shown = name.replaceFirstChar { it.uppercase() }
        if (Brain.enabled(this)) {
            if (name.isEmpty()) speak("नमस्ते! बोलिए, क्या करना है?")
            else speak("नमस्ते! मैं $shown हूँ। मेरा नाम लेकर बुलाइए।")
        } else if (name.isEmpty()) {
            speak("Hi, main aa gayi. Bolo, kya karna hai?")
        } else {
            speak("Hi, main $shown hu. Mera naam lekar bulao")
        }
    }
    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
    }
    private fun listen() {
        if (!running || speaking || thinking) return
        muteBeep()
        val lang = if (Brain.enabled(this)) "hi-IN" else "en-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (useLang) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            restartLater(1500)
        }
    }
    private fun restartLater(ms: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, ms)
    }
    private val listenRunnable = Runnable { listen() }
    private fun speak(text: String, stopAfter: Boolean = false) {
        if (!ttsReady) {
            if (stopAfter) shutdown() else restartLater(300)
            return
        }
        unmuteBeep()
        speaking = true
        stopAfterSpeak = stopAfter
        UiBus.update("Bol rahi hu...", true, true)
        val r = tts?.speak(voiceFor(text), TextToSpeech.QUEUE_FLUSH, null, "u")
        if (r != TextToSpeech.SUCCESS) {
            finishSpeaking()
            return
        }
        handler.removeCallbacks(speakWatchdog)
        handler.postDelayed(speakWatchdog, 10000)
    }
    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                restartLater(200)
                return
            }
            val name = wakeName()
            val now = System.currentTimeMillis()
            val words = text.trim().split(Regex("\\s+"))
            var cmd = text
            var addressed = name.isEmpty() || PhoneControl.ringing || now < activeUntil
            if (name.isNotEmpty()) {
                val idx = words.indexOfFirst { isName(it.lowercase(Locale.ROOT), name) }
                if (idx in 0..2) {
                    addressed = true
                    cmd = words.drop(idx + 1).joinToString(" ")
                }
            }
            if (!addressed) {
                updateNotif("Naam nahi suna: $text")
                restartLater(200)
                return
            }
            activeUntil = now + 15000
            if (cmd.isBlank()) {
                speak("Ji, boliye")
                return
            }
            updateNotif("Suna: $cmd")
            if (Brain.enabled(this@VoiceService)) {
                if (handleLocal(cmd)) return
                thinking = true
                UiBus.update("Soch rahi hu...", true)
                val asked = cmd
                val shownName = name.replaceFirstChar { it.uppercase() }
                worker.execute {
                    val reply = try {
                        Brain.ask(this@VoiceService, asked, shownName.ifEmpty { "Jarvis" })
                    } catch (e: Exception) {
                        null
                    }
                    handler.post {
                        thinking = false
                        onBrainReply(asked, reply)
                    }
                }
                return
            }
            val result = commands.handle(cmd)
            when {
                result.stop -> speak(result.reply, true)
                result.reply.isNotEmpty() -> speak(result.reply)
                else -> {
                    updateNotif("Suna: $cmd (samajh nahi aaya)")
                    restartLater(200)
                }
            }
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotif("Mic permission nahi hai")
                    speak("Mic ki permission nahi mili", true)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartLater(200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartLater(1000)
                12, 13 -> {
                    useLang = false
                    restartLater(500)
                }
                else -> {
                    updateNotif("Error $error, dobara try kar raha hu")
                    createRecognizer()
                    restartLater(1000)
                }
            }
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    private fun shutdown() {
        running = false
        UiBus.update("Band hai. Mic dabao", false)
        handler.removeCallbacksAndMessages(null)
        unregisterCallListener()
        removeOverlay()
        unmuteBeep()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        running = false
        UiBus.update("Band hai. Mic dabao", false)
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        unregisterCallListener()
        removeOverlay()
        unmuteBeep()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
