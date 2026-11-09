package com.petergara.mediahub.api

import android.content.Context
import com.petergara.mediahub.ai.AiCommands
import com.petergara.mediahub.ai.AiGateway
import com.petergara.mediahub.data.MediaRepository
import com.petergara.mediahub.data.TagEditor
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Lokálny REST server (len 127.0.0.1:8765) – dvere pre AI aj človeka.
 *
 *   GET  /tools        → kontrakt príkazov
 *   GET  /ai_status    → backend AI
 *   POST /list_media   {"type","query","limit"}
 *   POST /rename       {"id","kind","new_name"}
 *   POST /move         {"id","kind","folder"}
 *   POST /search       {"text"}
 *   POST /read_tags    {"id","kind"}
 *   POST /write_tags   {"id","kind","title","artist","album","genre","tags":[]}
 *   POST /analyze_audio {"id"}            → AI analýza + zápis do tagov
 *   POST /analyze_image {"id"}            → AI popis
 */
class LocalApiServer(
    context: Context,
    port: Int = 8765
) : NanoHTTPD("127.0.0.1", port) {

    init {
        setAsyncRunner(DefaultAsyncRunner())   // bez toho sa server zasekne na jednom spojení
    }

    private val appContext = context.applicationContext
    private val repo get() = MediaRepository.get(appContext)

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val body = readBody(session)
        return try {
            when (path) {
                "/tools" -> json(AiCommands.asJsonString())
                "/ai_status" -> json(aiStatus())
                "/list_media" -> json(repo.listJson(
                    body.optString("type", "all"),
                    body.optString("query", ""),
                    body.optInt("limit", 50)
                ))
                "/rename" -> json(repo.rename(
                    body.getLong("id"), body.getString("kind"), body.getString("new_name")
                ))
                "/move" -> json(repo.move(
                    body.getLong("id"), body.getString("kind"), body.getString("folder")
                ))
                "/search" -> json(repo.listJson("all", body.optString("text", ""), 50))
                "/read_tags" -> json(readTags(body))
                "/write_tags" -> json(writeTags(body))
                "/set_tags" -> json(writeTags(body))
                "/analyze_audio" -> json(analyzeAudio(body))
                "/analyze_image" -> json(analyzeImage(body))
                else -> json(JSONObject().put("error", "neznámy endpoint $path").toString(), statusFor(404))
            }
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: e.toString()).toString(), statusFor(400))
        }
    }

    private fun aiStatus(): JSONObject {
        val b = AiGateway.active
        return JSONObject().apply {
            put("backend", b.name); put("url", b.baseUrl)
            put("model", b.model); put("tier", b.tier.name)
        }
    }

    private fun itemOf(b: JSONObject): MediaRepository.MediaItem? =
        repo.byId(b.getLong("id"), b.optString("kind", "audio"))

    private fun readTags(b: JSONObject): JSONObject {
        val item = itemOf(b) ?: return JSONObject().put("ok", false).put("error", "nenájdené")
        return TagEditor.read(item.path)
    }

    private fun writeTags(b: JSONObject): JSONObject {
        val item = itemOf(b) ?: return JSONObject().put("ok", false).put("error", "nenájdené")
        return TagEditor.write(
            path = item.path,
            title = b.optString("title").takeIf { it.isNotBlank() },
            artist = b.optString("artist").takeIf { it.isNotBlank() },
            album = b.optString("album").takeIf { it.isNotBlank() },
            genre = b.optString("genre").takeIf { it.isNotBlank() },
            year = b.optString("year").takeIf { it.isNotBlank() },
            comment = b.optString("comment").takeIf { it.isNotBlank() },
            tags = b.optJSONArray("tags")?.let { a ->
                buildList { for (i in 0 until a.length()) add(a.getString(i)) }
            }
        )
    }

    /**
     * AI analýza hudby: model dostane existujúce metadáta + názov súboru,
     * vráti JSON kontraktu, ktorý sa zapíše do ID3/FLAC tagov.
     */
    private fun analyzeAudio(b: JSONObject): JSONObject {
        val item = itemOf(b) ?: return JSONObject().put("ok", false).put("error", "nenájdené")
        val existing = TagEditor.read(item.path)
        val prompt = buildString {
            append("Analyzuj hudobnú skladbu. Súbor: ${item.name}")
            if (existing.optBoolean("ok")) {
                append(", interpret=${existing.optString("artist")}")
                append(", album=${existing.optString("album")}")
                append(", žáner=${existing.optString("genre")}")
                append(", dĺžka=${existing.optInt("duration_sec")}s")
            }
            append(". Odhadni štýl a vráť IBA JSON: ")
            append("{\"title\":\"...\",\"artist\":\"...\",\"genre\":\"...\",")
            append("\"language\":\"sk|en|...\",\"mood\":\"...\",\"bpm\":0,")
            append("\"tags\":[\"tag1\",\"tag2\"]}")
        }
        val answer = kotlinx.coroutines.runBlocking { AiGateway(AiGateway.active).chat(prompt) }

        // vyčisti prípadné markdown bloky z odpovede modelu
        val clean = answer.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = try {
            JSONObject(clean.substring(clean.indexOf('{'), clean.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            return JSONObject().put("ok", false)
                .put("error", "model nevrátil JSON")
                .put("raw", answer.take(500))
        }
        val written = TagEditor.applyAiAnalysis(item.path, parsed)
        return JSONObject().apply {
            put("ok", written.optBoolean("ok"))
            put("analysis", parsed)
            if (!written.optBoolean("ok")) put("write_error", written.optString("error"))
        }
    }

    private fun analyzeImage(b: JSONObject): JSONObject {
        val item = itemOf(b) ?: return JSONObject().put("ok", false).put("error", "nenájdené")
        val prompt = "Analyzuj obrázok. Súbor: ${item.name}. " +
                "Vráť IBA JSON: {\"description\":\"...\",\"tags\":[\"...\"],\"suggested_name\":\"...\"}"
        val answer = kotlinx.coroutines.runBlocking { AiGateway(AiGateway.active).chat(prompt) }
        val clean = answer.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val parsed = JSONObject(clean.substring(clean.indexOf('{'), clean.lastIndexOf('}') + 1))
            JSONObject().put("ok", true).put("analysis", parsed)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "model nevrátil JSON")
                .put("raw", answer.take(500))
        }
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: ""
        return if (raw.isBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (e: Exception) { JSONObject() }
    }

    private fun json(content: String, code: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(code, "application/json; charset=utf-8", content)

    private fun json(obj: JSONObject): Response = json(obj.toString())

    private fun statusFor(code: Int): Response.Status = when (code) {
        400 -> Response.Status.BAD_REQUEST
        404 -> Response.Status.NOT_FOUND
        else -> Response.Status.OK
    }

    companion object {
        @Volatile private var server: LocalApiServer? = null
        fun start(context: Context): LocalApiServer? = try {
            server ?: synchronized(this) {
                server ?: LocalApiServer(context).apply { start(); server = this }
            }
        } catch (e: Exception) { null }
        fun stop() { server?.stop(); server = null }
    }
}