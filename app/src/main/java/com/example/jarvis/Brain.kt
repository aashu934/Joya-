package com.example.jarvis
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
object UiBus {
    @Volatile
    var status: String = "Band hai. Mic dabao"
    @Volatile
    var running: Boolean = false
    @Volatile
    var speakingNow: Boolean = false
    @Volatile
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun update(text: String, run: Boolean, speak: Boolean = false) {
        status = text
        running = run
        speakingNow = speak
        main.post { listener?.invoke() }
    }
}
private val DEV_CONS = mapOf(
    'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
    'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
    'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y",
    'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
)
private val DEV_VOW = mapOf(
    'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ए' to "e",
    'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऋ' to "ri"
)
private val DEV_MATRA = mapOf(
    'ा' to "a", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e",
    'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ृ' to "ri"
)
fun latinize(src: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < src.length) {
        val c = src[i]
        val cons = DEV_CONS[c]
        if (cons != null) {
            sb.append(cons)
            var j = i + 1
            if (j < src.length && src[j] == '़') j++
            val n = if (j < src.length) src[j] else ' '
            val matra = DEV_MATRA[n]
            if (matra != null) {
                sb.append(matra)
                j++
            } else if (n == '्') {
                j++
            } else {
                val last = j >= src.length || src[j] !in '\u0900'..'\u097F'
                if (!last) sb.append("a")
            }
            i = j
            continue
        }
        val v = DEV_VOW[c]
        if (v != null) {
            sb.append(v)
        } else if (c == 'ं' || c == 'ँ') {
            sb.append("n")
        } else if (c in '\u0900'..'\u097F') {
        } else {
            sb.append(c)
        }
        i++
    }
    return sb.toString()
}
object Brain {
    class Reply(val say: String, val command: String)
    private val history = ArrayList<Pair<String, String>>()
    fun enabled(ctx: Context): Boolean =
        (jarvisPrefs(ctx).getString("api_key", "") ?: "").isNotBlank()
    private fun systemPrompt(name: String): String = """
You are "{NAME}", a sweet, cheerful, young Indian woman who is the voice assistant on the user's Android phone. The user speaks Hindi, English or Hinglish. Your replies are spoken aloud.
STYLE:
- Reply in natural, simple spoken Hindi written in Devanagari script. Common English words (WhatsApp, YouTube, call, message, movie, Google) can stay in English letters.
- Be warm, friendly and SHORT: one or two sentences. No emojis, no markdown, no lists.
- Use a pet name like "जान" only rarely, only when the user is being affectionate or romantic. Otherwise do not use any pet name.
OUTPUT: Reply with ONLY a JSON object: {"say": "<what you speak>", "command": "<phone command or empty>"}
"command" is "" or exactly ONE line in one of these formats (Roman letters, lowercase):
open <app name>
<contact name> ko call karo
<contact name> ko call karo speaker pe
<contact name> ko whatsapp karo <message text>
<contact name> ko sms karo <message text>
youtube par <song/video to play or search> search karo
<movie name> movie chalao
search karo <query>
map pe <place> ka rasta dikhao
bluetooth on karo
bluetooth band karo
speaker on karo
speaker band karo
RULES:
- Contact names and app names must be written in Roman letters as the user said them. Message text goes in Roman letters (Hinglish) if it is Hindi.
- Only put a command when the user wants something done on the phone. For chatting, general knowledge, advice or jokes, answer in "say" with command "".
- For current things (news, scores, prices, weather, latest info) use command "search karo <query>" and say that you are searching.
- For movie requests use command "<movie name> movie chalao" and say you are finding it on YouTube. Paid apps like Netflix cannot be opened to a specific movie.
- Never claim you did something on the phone unless you gave a command for it.
""".trimIndent().replace("{NAME}", name)
    fun ask(ctx: Context, userText: String, assistantName: String): Reply {
        val prefs = jarvisPrefs(ctx)
        val key = (prefs.getString("api_key", "") ?: "").trim()
        val custom = (prefs.getString("model", "") ?: "").trim()
        val models = ArrayList<String>()
        if (custom.isNotEmpty()) models.add(custom)
        models.add("gemini-3.1-flash-lite")
        models.add("gemini-flash-latest")
        val body = buildBody(userText, assistantName)
        var lastCode = 0
        for (m in models.distinct()) {
            try {
                val res = post(m, key, body)
                lastCode = res.first
                if (res.first in 200..299) {
                    val reply = parse(res.second)
                    if (reply != null) {
                        remember(userText, reply)
                        return reply
                    }
                    return Reply("मुझे समझ नहीं आया, फिर से बोलिए", "")
                }
                if (res.first == 401 || res.first == 403 || res.first == 429) break
            } catch (e: Exception) {
                lastCode = -1
            }
        }
        val msg = when (lastCode) {
            400, 401, 403 -> "लगता है API की चाबी या मॉडल का नाम गलत है। सेटिंग्स में जाँच लीजिए"
            429 -> "अभी थोड़ी देर रुकिए, आज की लिमिट पूरी हो गई है"
            -1 -> "इंटरनेट में कुछ दिक्कत है"
            else -> "कुछ गड़बड़ हो गई, थोड़ी देर बाद कोशिश कीजिए"
        }
        return Reply(msg, "")
    }
    private fun remember(user: String, reply: Reply) {
        val json = JSONObject().put("say", reply.say).put("command", reply.command).toString()
        history.add(Pair(user, json))
        while (history.size > 6) history.removeAt(0)
    }
    private fun buildBody(userText: String, name: String): String {
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", a))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(name))))
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.8)
                    .put("maxOutputTokens", 1024)
                    .put("responseMimeType", "application/json")
            )
            .toString()
    }
    private fun post(model: String, key: String, body: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }
    private fun parse(text: String): Reply? {
        return try {
            val parts = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            var out = sb.toString().trim()
            if (out.startsWith("```")) {
                out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            }
            val j = JSONObject(out)
            Reply(j.optString("say", "").trim(), j.optString("command", "").trim())
        } catch (e: Exception) {
            null
        }
    }
}
