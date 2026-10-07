package com.example.jarvis

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import java.util.Locale

object Persona {
    // Bolne ka pyaar bhara naam. Yahan badal sakte ho (jaise "babu", "dear" ya koi naam)
    const val PET = "jaan"
}

class CommandHandler(private val ctx: Context) {

    data class Result(val reply: String, val stop: Boolean = false)

    private val pet = Persona.PET

    private val stopWords = setOf("bye", "by", "buy", "bai", "alvida", "goodbye")
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
        "video", "videos", "gana", "gaana", "song", "songs", "music", "trailer", "movie", "reel", "reels"
    )
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
        val t = raw.lowercase(Locale.ROOT).trim().replace("you tube", "youtube")
        if (t.isEmpty()) return Result("")
        val words = t.split(Regex("\\s+"))

        // 1) Band karna
        if ((words.size <= 3 && words.any { it in stopWords }) || t.contains("band karo")) {
            return Result("Theek hai $pet, apna khayal rakhna. Bye!", true)
        }

        // 2) Pyaari baatein
        smallTalk(t, words)?.let { return Result(it) }

        // 3) SMS / WhatsApp: "<naam> ko whatsapp karo <message>"
        msgRegex.find(t)?.let { m ->
            return sendMessage(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3].trim())
        }

        // 4) Call
        (callRegex1.find(t) ?: callRegex2.find(t))?.let {
            return call(it.groupValues[1].trim())
        }

        // 5) YouTube: "youtube par <cheez> search karo"
        if (words.contains("youtube") || words.contains("yt")) {
            return youtube(words)
        }

        // 6) Video / gaana chalana: "arijit ka gaana chalao"
        if (words.any { it in playVerbs } && (words.any { it in mediaWords } || words.first() == "play")) {
            return playMedia(words)
        }

        // 7) Map / rasta
        if (words.any { it in mapTriggers }) {
            return maps(words)
        }

        // 8) App kholna
        if (words.any { it in openTriggers }) {
            val name = words.filter { it !in fillerWords }.joinToString(" ")
            if (name.isNotEmpty()) return openApp(name)
        }

        // 9) Google search: "search karo ...", "... ke baare me batao", "kya/kaun/kaise ..."
        val explicit = words.any { it in searchTriggers } || t.contains("ke bare") || t.contains("ke baare")
        if (explicit || words.first() in questionStarts) {
            val q = if (explicit) trimEdges(words, searchFillers).joinToString(" ") else t
            return if (q.isEmpty()) Result("Kya search karu $pet?") else googleSearch(q)
        }

        // Samajh nahi aaya -> chup raho aur sunte raho
        return Result("")
    }

    private fun smallTalk(t: String, words: List<String>): String? {
        return when {
            t.contains("kaisi ho") || t.contains("kaise ho") || t.contains("how are you") ->
                listOf(
                    "Main bilkul theek hu $pet, tum batao kaise ho?",
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
                "Arre $pet, isme thank you kaisa. Tumhare liye kuch bhi"
            words.size <= 2 && words.first() in setOf("hi", "hello", "hey", "namaste") ->
                "Hi $pet, bolo kya karu tumhare liye?"
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

    private fun resolveNumber(who: String): String? {
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 7) return digits
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$who%"),
            null
        )
        c?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun call(who: String): Result {
        if (!has(Manifest.permission.CALL_PHONE)) return Result("Call ki permission nahi mili $pet")
        val num = resolveNumber(who) ?: return Result("$who ka number nahi mila $pet")
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Abhi $who ko call lagati hu $pet")
        } catch (e: Exception) {
            Result("Sorry $pet, call nahi lag paayi")
        }
    }

    private fun sendMessage(who: String, kind: String, body: String): Result {
        val num = resolveNumber(who) ?: return Result("$who ka number nahi mila $pet")
        return if (kind == "whatsapp") whatsapp(who, num, body) else sms(who, num, body)
    }

    @Suppress("DEPRECATION")
    private fun sms(who: String, num: String, body: String): Result {
        if (body.isEmpty()) return Result("Message kya bhejna hai $pet? Dobara bolo")
        if (!has(Manifest.permission.SEND_SMS)) return Result("SMS ki permission nahi mili $pet")
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(body), null, null)
            Result("$who ko SMS bhej diya $pet")
        } catch (e: Exception) {
            Result("Sorry $pet, SMS nahi ja paya")
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
                body.isEmpty() -> Result("$who ki chat khol di $pet")
                enabled -> Result("Ji $pet, $who ko WhatsApp bhej rahi hu")
                else -> Result("$who ke liye WhatsApp khol diya, bas send dabana $pet")
            }
        } catch (e: Exception) {
            Result("Sorry $pet, WhatsApp nahi khul paya")
        }
    }

    private fun youtube(all: List<String>): Result {
        val q = trimEdges(all.filter { it != "youtube" && it != "yt" }, ytFillers).joinToString(" ")
        if (q.isEmpty()) return openApp("youtube")
        return ytSearch(q)
    }

    private fun playMedia(words: List<String>): Result {
        val q = words.filter { it !in mediaFillers }.joinToString(" ")
        val onlyType = q.isEmpty() || q.split(" ").all { it in mediaWords }
        if (onlyType) return Result("Kaunsa chalau $pet? Naam bolo")
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
            Result("Ye lo $pet, YouTube pe $q dhundh rahi hu")
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Result("Ye lo $pet, YouTube pe $q dhundh rahi hu")
            } catch (e2: Exception) {
                Result("Sorry $pet, YouTube nahi khul paya")
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
            Result("Ye lo $pet, $q search kar rahi hu")
        } catch (e: Exception) {
            Result("Sorry $pet, search nahi ho paya")
        }
    }

    private fun maps(words: List<String>): Result {
        val q = trimEdges(words.filter { it !in mapTriggers }, searchFillers + mapFillers).joinToString(" ")
        if (q.isEmpty()) return Result("Kahan ka rasta chahiye $pet?")
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result("Ye lo $pet, $q ka rasta dikha rahi hu")
        } catch (e: Exception) {
            Result("Sorry $pet, map nahi khul paya")
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
            ?: return Result("$name naam ka app nahi mila $pet")
        val launch = pm.getLaunchIntentForPackage(apps[idx].activityInfo.packageName)
            ?: return Result("$name nahi khul paya $pet")
        return try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result("Ji $pet, $name khol rahi hu")
        } catch (e: Exception) {
            Result("Sorry $pet, $name nahi khul paya")
        }
    }
}
