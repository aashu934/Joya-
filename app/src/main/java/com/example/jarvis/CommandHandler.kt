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
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Ye lo, $q ka rasta dikha rahi hu")
        } catch (e: Exception) {
            Result("Sorry, map nahi khul paya")
        }
    }
    private fun openApp(name: String): Result {
        val alias = mapOf("insta" to "instagram", "setting" to "settings", "yt" to "youtube")
        val q = (alias[name] ?: name).replace(" ", "")
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        fun label(i: Int) = apps[i].loadLabel(pm).toString().lowercase(Locale.ROOT).replace(" ", "")
        val idx = apps.indices.firstOrNull { label(it) == q }
            ?: apps.indices.firstOrNull { label(it).contains(q) }
            ?: apps.indices.firstOrNull { label(it).length > 2 && q.contains(label(it)) }
            ?: return Result("$name naam ka app nahi mila")
        val launch = pm.getLaunchIntentForPackage(apps[idx].activityInfo.packageName)
            ?: return Result("$name nahi khul paya")
        return try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result("Ji, $name khol rahi hu")
        } catch (e: Exception) {
            Result("Sorry, $name nahi khul paya")
        }
    }
}
