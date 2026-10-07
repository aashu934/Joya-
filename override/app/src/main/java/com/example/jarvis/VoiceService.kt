package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var overlayView: View? = null
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
            tts?.language = Locale("en", "IN")
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

    private fun updateNotif(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
        } catch (e: Exception) {
        }
    }

    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotif("Speech recognition available nahi hai")
            speak("Is phone me speech recognition available nahi hai", true)
            return
        }
        createRecognizer()
        speak("Jarvis ready hai. Bolo")
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running || speaking) return
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
            updateNotif("Suna: $text")
            val result = commands.handle(text)
            when {
                result.stop -> speak(result.reply, true)
                result.reply.isNotEmpty() -> speak(result.reply)
                else -> speak("Suna: $text. Samajh nahi aaya")
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotif("Mic permission nahi hai")
                    speak("Mic ki permission nahi hai", true)
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
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
