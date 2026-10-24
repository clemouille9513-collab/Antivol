package com.antivol.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Chatbot branché à une vraie IA (GPT ou Claude, au choix) avec appel d'outils :
 * l'IA comprend la phrase, appelle les ordres du S22 (verrouiller, sonner, position...) et répond
 * avec ce que le S22 a vraiment renvoyé.
 * La clé API est saisie par la personne dans la télécommande ; elle ne quitte pas le téléphone,
 * sauf pour parler directement au fournisseur choisi.
 */
object Ai {
    class AiError(message: String) : Exception(message)

    private class Tool(val name: String, val desc: String, val schema: JSONObject)

    private fun schema(props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))

    private val tools: List<Tool> = listOf(
        Tool("lock", "Verrouille le S22 : l'écran de codes s'affiche par-dessus tout.", schema()),
        Tool("free", "Libère le S22 : arrête l'alarme, les voix et enlève l'écran de blocage.", schema()),
        Tool("alarm", "Fait sonner une sirène au volume maximum sur le S22.", schema()),
        Tool(
            "stolen",
            "Active le mode volé : le S22 se verrouille, répète « j'ai été volé », passe en mode éco et envoie sa position. À n'utiliser que si la personne le demande clairement.",
            schema()
        ),
        Tool("locate", "Demande au S22 sa position GPS actuelle (latitude, longitude, précision).", schema()),
        Tool("status", "Demande au S22 son état : batterie, verrouillé ou non, mode volé ou non.", schema()),
        Tool(
            "photo", "Prend une photo avec le S22 et l'affiche dans la télécommande.",
            schema(JSONObject().put("camera", JSONObject().put("type", "string").put("enum", JSONArray(listOf("front", "back"))).put("description", "front = selfie, back = arrière")))
        ),
        Tool(
            "say", "Fait dire une phrase à voix haute par le S22.",
            schema(JSONObject().put("text", JSONObject().put("type", "string").put("description", "La phrase à dire")), listOf("text"))
        ),
        Tool(
            "airplane", "Active ou coupe le mode avion du S22. Attention : l'activer coupe la connexion, plus aucun ordre ne passe ensuite.",
            schema(JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on"))
        ),
        Tool("screen", "Démarre l'affichage en direct de l'écran du S22 dans la télécommande.", schema()),
        Tool(
            "open_app", "Ouvre une appli installée sur le S22 à partir de son nom (YouTube, WhatsApp, Appareil photo, Réglages...).",
            schema(JSONObject().put("name", JSONObject().put("type", "string").put("description", "Nom de l'appli")), listOf("name"))
        ),
        Tool(
            "open_url", "Ouvre un lien ou un site web dans le navigateur du S22.",
            schema(JSONObject().put("url", JSONObject().put("type", "string")), listOf("url"))
        ),
        Tool(
            "torch", "Allume ou éteint la lampe torche du S22.",
            schema(JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on"))
        ),
        Tool(
            "volume", "Règle le volume du S22 (0 à 100 pour cent).",
            schema(JSONObject().put("percent", JSONObject().put("type", "integer")), listOf("percent"))
        )
    )

    private const val SYSTEM =
        "Tu es l'assistant de l'application Antivol. Tu pilotes à distance le téléphone Samsung S22 de la personne, peut-être volé. " +
            "Réponds en français, en une ou deux phrases courtes. Exécute les ordres avec les outils. " +
            "Ne devine jamais un résultat : appuie-toi uniquement sur ce que les outils renvoient (une erreur veut dire que le S22 n'a pas répondu). " +
            "Tu peux aussi ouvrir une appli ou un lien, allumer la lampe, régler le volume, faire parler le téléphone. Enchaîne plusieurs outils si la demande en contient plusieurs. N'active le mode volé que si la personne le demande clairement. Prévins avant le mode avion : il coupe la connexion."

    fun configured(ctx: Context) = Prefs.str(ctx, "ai_key").isNotBlank()

    /** Pose la question à l'IA ; [run] exécute un outil sur le S22 et renvoie son résultat en JSON. */
    fun ask(ctx: Context, history: List<Pair<String, String>>, run: (String, JSONObject) -> String): String {
        val key = Prefs.str(ctx, "ai_key").trim()
        if (key.isEmpty()) throw AiError("Aucune clé d'IA enregistrée")
        val provider = Prefs.str(ctx, "ai_provider", "openai")
        val model = Prefs.str(ctx, "ai_model").trim().ifBlank {
            when (provider) { "anthropic" -> "claude-haiku-5-5"; "gemini" -> "gemini-2.5-flash"; else -> "gpt-4o-mini" }
        }
        val base = Prefs.str(ctx, "ai_base").trim().ifBlank {
            when (provider) {
                "anthropic" -> "https://api.anthropic.com"
                "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai"
                else -> "https://api.openai.com/v1"
            }
        }.trimEnd('/')
        val h = history.dropWhile { it.first != "user" }
        return if (provider == "anthropic") askAnthropic(base, key, model, h, run) else askOpenAi(base, key, model, h, run)
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val rb = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(rb).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val c = Ntfy.client.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
        try {
            c.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val detail = try {
                        val o = JSONObject(txt)
                        o.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: txt.take(160)
                    } catch (_: Exception) { txt.take(160) }
                    throw AiError("L'IA a répondu ${r.code} : $detail")
                }
                return txt
            }
        } catch (e: AiError) {
            throw e
        } catch (e: Exception) {
            throw AiError("Impossible de joindre l'IA : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun safeRun(run: (String, JSONObject) -> String, name: String, args: JSONObject): String =
        try { run(name, args) } catch (e: Exception) { JSONObject().put("erreur", e.message ?: "échec").toString() }

    // ---------- OpenAI et services compatibles ----------

    private fun askOpenAi(base: String, key: String, model: String, history: List<Pair<String, String>>, run: (String, JSONObject) -> String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
        history.forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }
        val defs = JSONArray()
        tools.forEach {
            defs.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", it.name).put("description", it.desc).put("parameters", it.schema)
                )
            )
        }
        repeat(5) {
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", defs).toString()
            val resp = post("$base/chat/completions", body, mapOf("Authorization" to "Bearer $key"))
            val msg = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return msg.optString("content", "").ifBlank { "OK." }
            msgs.put(msg)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val f = c.getJSONObject("function")
                val args = try { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                val out = safeRun(run, f.getString("name"), args)
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out))
            }
        }
        return "Je n'ai pas fini : il y a trop d'étapes. Reformule en une seule demande."
    }

    // ---------- Claude (Anthropic) ----------

    private fun askAnthropic(base: String, key: String, model: String, history: List<Pair<String, String>>, run: (String, JSONObject) -> String): String {
        val msgs = JSONArray()
        history.forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }
        val defs = JSONArray()
        tools.forEach { defs.put(JSONObject().put("name", it.name).put("description", it.desc).put("input_schema", it.schema)) }
        repeat(5) {
            val body = JSONObject().put("model", model).put("max_tokens", 1024).put("system", SYSTEM)
                .put("messages", msgs).put("tools", defs).toString()
            val resp = post(
                "$base/v1/messages", body,
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            )
            val content = JSONObject(resp).getJSONArray("content")
            val text = StringBuilder()
            val uses = mutableListOf<JSONObject>()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> uses += b
                }
            }
            if (uses.isEmpty()) return text.toString().ifBlank { "OK." }
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            val results = JSONArray()
            for (u in uses) {
                val out = safeRun(run, u.getString("name"), u.optJSONObject("input") ?: JSONObject())
                results.put(JSONObject().put("type", "tool_result").put("tool_use_id", u.getString("id")).put("content", out))
            }
            msgs.put(JSONObject().put("role", "user").put("content", results))
        }
        return "Je n'ai pas fini : il y a trop d'étapes. Reformule en une seule demande."
    }
}
