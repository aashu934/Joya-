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
            tts?.setSpeechRate(1.05f)
            try {
                val vn = prefs.getString("voice_name", null)
                if (vn != null) {
                    val v = tts?.voices?.firstOrNull { it.name == vn }
                    if (v != null) tts?.voice = v
                }
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
        if (stopAfterSpeak) shutdown() else listen()
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
        val a = w.filter { it.isLetterOrDigit() }
        if (a.isEmpty()) return false
        return a == name || (name.length >= 4 && levenshtein(a, name) <= 1)
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
        val name = wakeName()
        if (name.isEmpty()) {
            speak("Hi, main aa gayi. Bolo, kya karna hai?")
        } else {
            val shown = name.replaceFirstChar { it.uppercase() }
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
        if (!running || speaking) return
        muteBeep()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (useLang) putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
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
        handler.removeCallbacksAndMessages(null)
        unregisterCallListener()
        removeOverlay()
        unmuteBeep()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        running = false
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
package
package com.example.jarvis
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import java.util.Locale
object Persona {
    const val PET = "jaan"
}
fun jarvisPrefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return prev[b.length]
}
object PhoneControl {
    @Volatile
    var ringing = false
    fun answer(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            true
        } catch (e: Exception) {
            false
        }
    }
    @Suppress("DEPRECATION")
    fun hangup(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.endCall()
        } catch (e: Exception) {
            false
        }
    }
    @Suppress("DEPRECATION")
    fun speaker(ctx: Context, on: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = on
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val dev = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    am.clearCommunicationDevice()
                }
            }
        } catch (e: Exception) {
        }
    }
    @Suppress("DEPRECATION", "MissingPermission")
    fun bluetooth(on: Boolean): Boolean {
        return try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (on) a.enable() else a.disable()
        } catch (e: Exception) {
            false
        }
    }
}
class CommandHandler(private val ctx: Context) {
    data class Result(val reply: String, val stop: Boolean = false)
    private data class Contact(val name: String, val number: String)
    private class Lookup(val contact: Contact?, val error: String?)
    private val pet = Persona.PET
    private val main = Handler(Looper.getMainLooper())
    private val stopWords = setOf("bye", "by", "buy", "bai", "alvida", "goodbye")
    private val answerWords = setOf("uthao", "utha", "uthalo", "uthana", "uthaao", "pick", "receive", "answer")
    private val rejectWords = setOf("kaat", "kato", "kaato", "cut", "reject", "decline", "disconnect", "hangup")
    private val offWords = setOf("off", "band", "bandh", "hatao", "hata", "disable", "mat")
    private val openTriggers = setOf("kholo", "khol", "open", "chalao", "launch")
    private val fillerWords = setOf(
        "kholo", "khol", "open", "chalao", "chalu", "launch", "start",
        "kar", "karo", "do", "de", "dena", "ko", "app", "please", "the"
    )
    private val ytFillers = setOf(
        "par", "pe", "me", "mein", "on", "in", "search", "dhundo", "dhoondo", "khojo",
        "chalao", "play", "karo", "kar", "lagao", "laga", "dikhao", "dikha", "for",
        "kholo", "open", "ko", "se", "please"
    )
    private val doPrev = setOf("kar", "laga", "chala", "dikha")
    private val playVerbs = setOf("chalao", "chala", "play", "sunao", "dikhao", "dikha", "lagao", "laga")
    private val mediaWords = setOf(
        "video", "videos", "gana", "gaana", "song", "songs", "music", "trailer",
        "movie", "movies", "film", "reel", "reels"
    )
    private val movieWords = setOf("movie", "movies", "film")
    private val mediaFillers = setOf(
        "ka", "ki", "ke", "ek", "mera", "meri", "mujhe", "mere", "liye", "koi", "kar", "karo",
        "do", "de", "zara", "please", "ko", "ye", "wo", "vo", "the", "a", "my", "play",
        "chalao", "chala", "sunao", "dikhao", "dikha", "lagao", "laga", "for", "on", "and"
    )
    private val searchTriggers = setOf("search", "google", "dhundo", "dhoondo", "khojo", "batao")
    private val searchFillers = setOf(
        "search", "google", "dhundo", "dhoondo", "khojo", "batao", "par", "pe", "me", "mein",
        "karo", "kar", "do", "de", "ke", "bare", "baare", "about", "on", "for", "mujhe",
        "mere", "liye", "zara", "please", "ko", "ye", "ek", "thoda"
    )
    private val questionStarts = setOf(
        "kya", "kaun", "kon", "kaise", "kyun", "kyu", "kab", "kahan", "kitna", "kitne", "kisne",
        "what", "who", "how", "why", "when", "where", "which"
    )
    private val mapTriggers = setOf("map", "maps", "navigate", "rasta", "raasta", "directions", "route")
    private val mapFillers = setOf(
        "ka", "ki", "ke", "tak", "jana", "hai", "dikhao", "dikha", "chalo", "jao", "to", "from"
    )
    private val msgRegex = Regex(
        "^(.+?)\\s+ko\\s+(whatsapp|sms|message|msg)\\s*(?:bhejo|bhej do|bhej|karo|kar do|likho)?\\s*(.*)$"
    )
    private val callRegex1 = Regex("^(?:call|phone|dial)\\s+(.+)$")
    private val callRegex2 = Regex("^(.+?)\\s+ko\\s+(?:call|phone|fone|dial)(?:\\s+(?:karo|kar do|lagao|laga do))?$")
    fun handle(raw: String): Result {
        val t0 = raw.lowercase(Locale.ROOT).trim()
            .replace("you tube", "youtube")
            .replace("blue tooth", "bluetooth")
            .replace("blutooth", "bluetooth")
        if (t0.isEmpty()) return Result("")
        val words0 = t0.split(Regex("\\s+"))
        val wantSpeaker = t0.contains("speaker")
        val hasCall = words0.contains("call") || words0.contains("phone")
        val deviceCmd = wantSpeaker || t0.contains("bluetooth")
        if (!deviceCmd && !hasCall && words0.size <= 3 &&
            (words0.any { it in stopWords } || t0.contains("band karo"))
        ) {
            return Result("Theek hai, bye. Apna khayal rakhna!", true)
        }
        if (words0.any { it in answerWords } && (PhoneControl.ringing || hasCall)) {
            if (!PhoneControl.ringing) return Result("Abhi koi call nahi aa raha")
            val ok = PhoneControl.answer(ctx)
            if (ok && wantSpeaker) main.postDelayed({ PhoneControl.speaker(ctx, true) }, 1200)
            return Result(if (ok) "Ok, utha liya" else "Call nahi utha paayi, phone ki permission check karo")
        }
        if (words0.any { it in rejectWords } && (PhoneControl.ringing || hasCall)) {
            val ok = PhoneControl.hangup(ctx)
            return Result(if (ok) "Call kaat diya" else "Call nahi kaat paayi")
        }
        if (wantSpeaker && !hasCall) {
            val off = words0.any { it in offWords }
            PhoneControl.speaker(ctx, !off)
            return Result(if (off) "Speaker band kar diya" else "Speaker chalu kar diya")
        }
        if (t0.contains("bluetooth")) {
            val off = words0.any { it in offWords }
            return bluetooth(!off)
        }
        val t = if (wantSpeaker) {
            Regex("\\s*speaker(\\s*phone)?(\\s+(pe|par|per|on))?").replace(t0, "").trim()
        } else {
            t0
        }
        if (t.isEmpty()) return Result("")
        val words = t.split(Regex("\\s+"))
        smallTalk(t, words)?.let { return Result(it) }
        msgRegex.find(t)?.let { m ->
            return sendMessage(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3].trim())
        }
        (callRegex1.find(t) ?: callRegex2.find(t))?.let {
            return call(it.groupValues[1].trim(), wantSpeaker)
        }
        if (words.contains("youtube") || words.contains("yt")) {
            return youtube(words)
        }
        if (words.any { it in playVerbs } && (words.any { it in mediaWords } || words.first() == "play")) {
            return playMedia(words)
        }
        if (words.any { it in mapTriggers }) {
            return maps(words)
        }
        if (words.any { it in openTriggers }) {
            val name = words.filter { it !in fillerWords }.joinToString(" ")
            if (name.isNotEmpty()) return openApp(name)
        }
        val explicit = words.any { it in searchTriggers } || t.contains("ke bare") || t.contains("ke baare")
        if (explicit || words.first() in questionStarts) {
            val q = if (explicit) trimEdges(words, searchFillers).joinToString(" ") else t
            return if (q.isEmpty()) Result("Kya search karu?") else googleSearch(q)
        }
        return Result("")
    }
    private fun smallTalk(t: String, words: List<String>): String? {
        return when {
            t.contains("kaisi ho") || t.contains("kaise ho") || t.contains("how are you") ->
                listOf(
                    "Main bilkul theek hu, tum batao kaise ho?",
                    "Tumse baat ho rahi hai to mast hu $pet"
                ).random()
            t.contains("love you") || t.contains("miss you") ->
                listOf(
                    "Aww $pet, tum bahut pyaare ho",
                    "Main bhi tumhe yaad karti hu $pet",
                    "Itna pyaar? Mera dil khush ho gaya $pet"
                ).random()
            t.contains("good morning") || t.contains("suprabhat") ->
                "Good morning $pet! Aaj ka din bahut accha jayega"
            t.contains("good night") || t.contains("shubh ratri") ->
                "Good night $pet, meethe sapne. Kal milte hain"
            t.contains("thank you") || t.contains("thanks") || t.contains("shukriya") ->
                "Arre, isme thank you kaisa. Tumhare liye kuch bhi"
            words.size <= 2 && words.first() in setOf("hi", "hello", "hey", "namaste") ->
                "Hi, bolo kya karu tumhare liye?"
            else -> null
        }
    }
    private fun trimEdges(src: List<String>, fillers: Set<String>): List<String> {
        val w = src.toMutableList()
        while (w.isNotEmpty() && w.first() in fillers) w.removeAt(0)
        while (w.isNotEmpty()) {
            val last = w.last()
            val prev = w.getOrNull(w.size - 2)
            if (last in fillers) {
                w.removeAt(w.size - 1)
            } else if ((last == "do" || last == "de") && prev != null && prev in doPrev) {
                w.removeAt(w.size - 1)
            } else {
                break
            }
        }
        return w
    }
    private fun has(perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    private fun norm(s: String) =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == ' ' }.trim()
    private fun score(q: String, name: String): Int {
        val n = norm(name)
        if (n.isEmpty()) return 0
        if (n == q) return 100
        if (n.replace(" ", "") == q.replace(" ", "")) return 95
        if (n.startsWith(q)) return 85
        if (n.contains(q)) return 80
        val nw = n.split(" ")
        val qw = q.split(" ")
        if (qw.all { w -> nw.any { it == w } }) return 75
        if (qw.all { w -> nw.any { it.startsWith(w) } }) return 70
        var total = 0.0
        for (w in qw) {
            var bestSim = 0.0
            for (x in nw) {
                val sim = 1.0 - levenshtein(w, x).toDouble() / maxOf(w.length, x.length)
                if (sim > bestSim) bestSim = sim
            }
            total += bestSim
        }
        val avg = total / qw.size
        return if (avg >= 0.7) (avg * 60).toInt() else 0
    }
    private fun findContact(query: String): Contact? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val list = ArrayList<Contact>()
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        c?.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                list.add(Contact(n, num))
            }
        }
        var best: Contact? = null
        var bestScore = 0
        for (ct in list) {
            val s = score(q, ct.name)
            if (s > bestScore) {
                bestScore = s
                best = ct
            }
        }
        return if (bestScore >= 40) best else null
    }
    private fun lookup(who: String): Lookup {
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 7) return Lookup(Contact(who, digits), null)
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return Lookup(null, "Contacts ki permission nahi hai. App info me jaake Contacts allow karo")
        }
        val c = findContact(who) ?: return Lookup(null, "$who naam ka koi contact nahi mila")
        return Lookup(c, null)
    }
    private fun bluetooth(on: Boolean): Result {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            return Result("Bluetooth ki permission nahi hai")
        }
        if (PhoneControl.bluetooth(on)) {
            return Result(if (on) "Bluetooth chalu kar diya" else "Bluetooth band kar diya")
        }
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Bluetooth settings khol di, wahan se kar lo")
        } catch (e: Exception) {
            Result("Bluetooth nahi chal paya")
        }
    }
    private fun call(who: String, speaker: Boolean): Result {
        if (!has(Manifest.permission.CALL_PHONE)) return Result("Call ki permission nahi mili")
        val lk = lookup(who)
        val c = lk.contact ?: return Result(lk.error ?: "Contact nahi mila")
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            if (speaker) main.postDelayed({ PhoneControl.speaker(ctx, true) }, 2500)
            Result("Abhi ${c.name} ko call lagati hu")
        } catch (e: Exception) {
            Result("Sorry, call nahi lag paayi")
        }
    }
    private fun sendMessage(who: String, kind: String, body: String): Result {
        val lk = lookup(who)
        val c = lk.contact ?: return Result(lk.error ?: "Contact nahi mila")
        return if (kind == "whatsapp") whatsapp(c.name, c.number, body) else sms(c.name, c.number, body)
    }
    @Suppress("DEPRECATION")
    private fun sms(who: String, num: String, body: String): Result {
        if (body.isEmpty()) return Result("Message kya bhejna hai? Dobara bolo")
        if (!has(Manifest.permission.SEND_SMS)) return Result("SMS ki permission nahi mili")
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(body), null, null)
            Result("$who ko SMS bhej diya")
        } catch (e: Exception) {
            Result("Sorry, SMS nahi ja paya")
        }
    }
    private fun normalizeIn(num: String): String {
        val d = num.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }
    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains(ctx.packageName)
    }
    private fun whatsapp(who: String, num: String, body: String): Result {
        val enabled = a11yEnabled()
        if (body.isNotEmpty() && enabled) {
            AutoSendService.armedUntil = System.currentTimeMillis() + 20000
        }
        val uri = Uri.parse("https://wa.me/${normalizeIn(num)}?text=" + Uri.encode(body))
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            try {
                ctx.startActivity(Intent(base).setPackage("com.whatsapp"))
            } catch (e: ActivityNotFoundException) {
                ctx.startActivity(base)
            }
            when {
                body.isEmpty() -> Result("$who ki chat khol di")
                enabled -> Result("Ji, $who ko WhatsApp bhej rahi hu")
                else -> Result("$who ke liye WhatsApp khol diya, bas send dabana")
            }
        } catch (e: Exception) {
            Result("Sorry, WhatsApp nahi khul paya")
        }
    }
    private fun youtube(all: List<String>): Result {
        val q = trimEdges(all.filter { it != "youtube" && it != "yt" }, ytFillers).joinToString(" ")
        if (q.isEmpty()) return openApp("youtube")
        return ytSearch(q)
    }
    private fun playMedia(words: List<String>): Result {
        val isMovie = words.any { it in movieWords }
        val q = words.filter { it !in mediaFillers }.joinToString(" ")
        val onlyType = q.isEmpty() || q.split(" ").all { it in mediaWords }
        if (onlyType) return Result("Kaunsa chalau? Naam bolo")
        if (isMovie) {
            val name = q.split(" ").filter { it !in movieWords }.joinToString(" ")
            return ytSearch("$name full movie")
        }
        return ytSearch(q)
    }
    private fun ytSearch(q: String): Result {
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_SEARCH)
                    .setPackage("com.google.android.youtube")
                    .putExtra("query", q)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Ye lo, YouTube pe $q dhundh rahi hu")
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Result("Ye lo, YouTube pe $q dhundh rahi hu")
            } catch (e2: Exception) {
                Result("Sorry, YouTube nahi khul paya")
            }
        }
    }
    private fun googleSearch(q: String): Result {
        return try {
            ctx.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Ye lo, $q search kar rahi hu")
        } catch (e: Exception) {
            Result("Sorry, search nahi ho paya")
        }
    }
    private fun maps(words: List<String>): Result {
        val q = trimEdges(words.filter { it !in mapTriggers }, searchFillers + mapFillers).joinToString(" ")
        if (q.isEmpty()) return Result("Kahan ka rasta chahiye?")
        return try {
            ctxpackage com.example.jarvis
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
}package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Jarvis Voice Assistant\n\nPehle 1, 2 aur 3 karo, phir Start dabao.\nBand karne ke liye \"bye\" bolo."
            textSize = 16f
            setPadding(0, 0, 0, 32)
        })

        fun addButton(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
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
        addButton("Start") { startAssistant() }
        addButton("Stop") { stopService(Intent(this, VoiceService::class.java)) }

        setContentView(root)
    }

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
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
}
