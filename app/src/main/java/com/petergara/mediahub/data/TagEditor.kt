package com.petergara.mediahub.data

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Čítanie a zápis tagov (ID3 pre MP3, Vorbis pre FLAC/OGG, MP4 atď.)
 * cez jaudiotagger – funguje lokálne, offline, bez internetu.
 *
 * Toto je miesto, kam AI zapisuje výsledky analýzy:
 *   analyze_audio → {title, artist, genre, language, mood, tags[]} → setTags()
 */
object TagEditor {

    /** Prečíta tagy súboru. Vráti JSON podľa kontraktu. */
    fun read(path: String): JSONObject {
        val f = File(path)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "súbor neexistuje")
        return try {
            val af = AudioFileIO.read(f)
            val tag: Tag? = af.tag
            JSONObject().apply {
                put("ok", true)
                put("title", tag?.getFirst(FieldKey.TITLE) ?: "")
                put("artist", tag?.getFirst(FieldKey.ARTIST) ?: "")
                put("album", tag?.getFirst(FieldKey.ALBUM) ?: "")
                put("genre", tag?.getFirst(FieldKey.GENRE) ?: "")
                put("year", tag?.getFirst(FieldKey.YEAR) ?: "")
                put("comment", tag?.getFirst(FieldKey.COMMENT) ?: "")
                put("duration_sec", af.audioHeader?.trackLength ?: 0)
                put("format", af.audioHeader?.format ?: "")
                put("bitrate", af.audioHeader?.bitRate ?: 0)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "čítanie zlyhalo")
        }
    }

    /**
     * Zapíše tagy. Prázdne hodnoty sa nechajú bez zmeny.
     * tags pole sa uloží ako viacnásobný GENRE + do COMMENT (AI tagy).
     */
    fun write(
        path: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        year: String? = null,
        comment: String? = null,
        tags: List<String>? = null
    ): JSONObject {
        val f = File(path)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "súbor neexistuje")
        return try {
            val af = AudioFileIO.read(f)
            var tag: Tag? = af.tag
            if (tag == null) {
                tag = af.createDefaultTag()
                af.tag = tag
            }
            title?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TITLE, it) }
            artist?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ARTIST, it) }
            album?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
            genre?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.GENRE, it) }
            year?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.YEAR, it) }

            // kombinácia: AI tagy + existujúci komentár
            val tagPart = tags?.filter { it.isNotBlank() }?.joinToString(", ")
            val newComment = listOfNotNull(
                comment?.takeIf { it.isNotBlank() },
                tagPart?.takeIf { it.isNotBlank() }
            ).joinToString(" | ")
            if (newComment.isNotBlank()) tag.setField(FieldKey.COMMENT, newComment)

            AudioFileIO.write(af)
            JSONObject().put("ok", true).put("path", path)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "zápis zlyhal")
        }
    }

    /** Zapíše výsledok AI analýzy do tagov (jeden krok). */
    fun applyAiAnalysis(path: String, analysis: JSONObject): JSONObject {
        val tagsArr = analysis.optJSONArray("tags")
        val tags = buildList {
            if (tagsArr != null) for (i in 0 until tagsArr.length()) add(tagsArr.getString(i))
            analysis.optString("language").takeIf { it.isNotBlank() }?.let { add("lang:$it") }
            analysis.optString("mood").takeIf { it.isNotBlank() }?.let { add("mood:$it") }
            analysis.optString("bpm").takeIf { it.isNotBlank() }?.let { add("bpm:$it") }
        }
        return write(
            path = path,
            title = analysis.optString("title").takeIf { it.isNotBlank() },
            artist = analysis.optString("artist").takeIf { it.isNotBlank() },
            album = analysis.optString("album").takeIf { it.isNotBlank() },
            genre = analysis.optString("genre").takeIf { it.isNotBlank() },
            comment = "AI: " + analysis.optString("description", "").take(200).ifBlank { "" },
            tags = tags
        )
    }
}