package com.petergara.mediahub.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Univerzálna AI vrstva: hovorí OpenAI-compatible protokolom (/v1/chat/completions),
 * ktorý používa KAŽDÝ backend – llama.cpp, vLLM, Ollama, LM Studio, cloud.
 *
 * Backendy sa pridávajú len ako endpoint, appka a kontrakt sa nemenia.
 *
 * Capability probe: raz sa spýta /v1/models a podľa triedy modelu
 * vyberie šablónu – jednoduchú pre "hlúpy" malý model, bohatú pre chytrý.
 */
data class AiBackend(
    val name: String,
    val baseUrl: String,          // napr. http://127.0.0.1:8080/v1
    val apiKey: String = "",
    val model: String = "",
    val tier: Tier = Tier.SMART    // SMART = chytrý, DUMB = malý lokálny
) {
    enum class Tier { DUMB, SMART }
}

class AiGateway(private val backend: AiBackend) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Capabilities – výsledok probe. Ukladá sa, neptá sa pri každom volaní. */
    data class Capabilities(val models: List<String>, val reachable: Boolean)

    suspend fun probe(): Capabilities = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(backend.baseUrl.trimEnd('/') + "/models")
                .apply { if (backend.apiKey.isNotEmpty()) header("Authorization", "Bearer ${backend.apiKey}") }
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val arr = try { JSONObject(body).getJSONArray("data") } catch (e: Exception) { JSONArray() }
                val ids = buildList {
                    for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("id"))
                }
                Capabilities(ids, resp.isSuccessful)
            }
        } catch (e: Exception) {
            Capabilities(emptyList(), false)
        }
    }

    /**
     * Volanie modelu so šablónou podľa triedy.
     * Výstup modelu = JSON kontraktu, ktorý parsujeme.
     */
    suspend fun chat(userMessage: String, systemExtra: String = ""): String =
        withContext(Dispatchers.IO) {
            val system = buildString {
                append(systemTemplate(backend.tier))
                append("\n\nDOSTUPNÉ PRÍKAZY (vráť iba JSON príkazu alebo JSON výsledku):\n")
                append(AiCommands.contractAsJson())
                if (systemExtra.isNotEmpty()) append("\n").append(systemExtra)
            }
            val payload = JSONObject().apply {
                put("model", backend.model.ifEmpty { "local-model" })
                put("temperature", if (backend.tier == AiBackend.Tier.DUMB) 0.0 else 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", userMessage))
                })
            }
            val req = Request.Builder()
                .url(backend.baseUrl.trimEnd('/') + "/chat/completions")
                .apply { if (backend.apiKey.isNotEmpty()) header("Authorization", "Bearer ${backend.apiKey}") }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("AI HTTP ${resp.code}: ${body.take(300)}")
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }

    /** Šablóna: DUMB = striktný malý JSON, SMART = voľnejší, viac polí. */
    private fun systemTemplate(tier: AiBackend.Tier): String = when (tier) {
        AiBackend.Tier.DUMB -> buildString {
            append("Si ovládač appky MediaHub. Odpovedaj VŽDY len čistým JSON bez textu okolo. ")
            append("Ak dostaneš popis média, vráť JSON príkazu (napr. {\"cmd\":\"rename\",...}). ")
            append("Drž sa krátkych názvov a 3-6 tagov.")
        }
        AiBackend.Tier.SMART -> buildString {
            append("Si pokročilý asistent appky MediaHub. Analyzuj médium do hĺbky: ")
            append("štýl, jazyk, nálada, téma, kvalita pomenovania. ")
            append("Odpovedaj JSON podľa schémy kontraktu; pri analýze môžeš pridať poznámku \"reason\".")
        }
    }

    companion object {
        /** Aktívny backend – jednoduchý stav pre v0.1, neskôr cez nastavenia. */
        @Volatile var active: AiBackend = AiBackend(
            name = "Qwen local",
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "qwen3.5",
            tier = AiBackend.Tier.DUMB
        )
    }
}