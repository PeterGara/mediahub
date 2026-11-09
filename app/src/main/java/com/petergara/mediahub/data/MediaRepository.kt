package com.petergara.mediahub.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prístup k médiám cez MediaStore – reálne čítanie, hľadanie, premenovanie, presun.
 * Toto je motor, ktorý volajú UI aj AI príkazy.
 */
class MediaRepository(private val context: Context) {

    data class MediaItem(
        val id: Long,
        val kind: String,      // audio|image|video
        val name: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val size: Long,
        val path: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("kind", kind); put("name", name)
            if (artist != null) put("artist", artist)
            if (album != null) put("album", album)
            if (durationMs != null) put("duration_ms", durationMs)
            put("size", size); put("path", path)
        }
    }

    private fun collectionFor(kind: String) = when (kind) {
        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    fun list(kind: String, query: String = "", limit: Int = 50): List<MediaItem> {
        val types = if (kind == "all") listOf("audio", "image", "video") else listOf(kind)
        val out = mutableListOf<MediaItem>()
        for (t in types) {
            val projection = when (t) {
                "audio" -> arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATA
                )
                else -> arrayOf(
                    when (t) { "video" -> MediaStore.Video.Media._ID; else -> MediaStore.Images.Media._ID },
                    when (t) { "video" -> MediaStore.Video.Media.DISPLAY_NAME; else -> MediaStore.Images.Media.DISPLAY_NAME },
                    when (t) { "video" -> MediaStore.Video.Media.SIZE; else -> MediaStore.Images.Media.SIZE },
                    when (t) { "video" -> MediaStore.Video.Media.DATA; else -> MediaStore.Images.Media.DATA },
                    when (t) { "video" -> MediaStore.Video.Media.DURATION; else -> MediaStore.Images.Media.DATE_TAKEN }
                )
            }
            val selection: String?
            val args: Array<String>?
            if (query.isNotBlank()) {
                val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
                selection = "$nameCol LIKE ?"
                args = arrayOf("%$query%")
            } else { selection = null; args = null }

            val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val max = if (limit > 0) limit else 10_000
            context.contentResolver.query(
                collectionFor(t), projection, selection, args, sort
            )?.use { c ->
                val idIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val artistIx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durIx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext() && out.size < max) {
                    out.add(
                        MediaItem(
                            id = c.getLong(idIx), kind = t,
                            name = c.getString(nameIx) ?: "?",
                            artist = if (artistIx >= 0) c.getString(artistIx) else null,
                            album = if (albumIx >= 0) c.getString(albumIx) else null,
                            durationMs = if (durIx >= 0 && !c.isNull(durIx)) c.getLong(durIx) else null,
                            size = c.getLong(sizeIx), path = c.getString(pathIx) ?: ""
                        )
                    )
                }
            }
        }
        return out
    }

    fun listJson(kind: String, query: String, limit: Int): String {
        val arr = JSONArray()
        list(kind, query, limit).forEach { arr.put(it.toJson()) }
        return JSONObject().put("items", arr).toString()
    }

    /** Premenovanie – najprv MediaStore, potom priamy súbor (All files access). */
    fun rename(id: Long, kind: String, newName: String): JSONObject {
        require(newName.isNotBlank() && !newName.contains('/')) { "Neplatný názov" }
        val uri = ContentUris.withAppendedId(collectionFor(kind), id)
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        val rows = try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: SecurityException) { 0 }
        if (rows > 0) return JSONObject().put("ok", true).put("id", id).put("new_name", newName)

        // Fallback: priamy súbor (vyžaduje MANAGE_EXTERNAL_STORAGE / All files access)
        val oldPath = byId(id, kind)?.path ?: return err("nepremenované (nenašlo cestu)")
        val f = java.io.File(oldPath)
        val dest = java.io.File(f.parentFile, newName)
        val ok = try { f.renameTo(dest) } catch (e: Exception) { false }
        return if (ok) JSONObject().put("ok", true).put("id", id).put("new_name", newName)
        else err("nepremenované (treba All files access)")
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    /** Presun do priečinka – najprv MediaStore, potom priamy súbor. */
    fun move(id: Long, kind: String, folder: String): JSONObject {
        val rel = folder.trim('/')
        val uri = ContentUris.withAppendedId(collectionFor(kind), id)
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
        }
        val rows = try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: SecurityException) { 0 }
        if (rows > 0) return JSONObject().put("ok", true).put("new_path", "/storage/emulated/0/$rel")

        // Fallback priamy presun
        val oldPath = byId(id, kind)?.path ?: return err("nepremiestnené (nenašlo cestu)")
        val f = java.io.File(oldPath)
        val dir = java.io.File("/storage/emulated/0/$rel")
        val dest = java.io.File(dir, f.name)
        val ok = try {
            dir.mkdirs(); f.renameTo(dest)
        } catch (e: Exception) { false }
        return if (ok) JSONObject().put("ok", true).put("new_path", dest.absolutePath)
        else err("nepremiestnené (treba All files access)")
    }

    fun byId(id: Long, kind: String): MediaItem? =
        list(kind, "", 10_000).find { it.id == id }

    companion object {
        @Volatile var instance: MediaRepository? = null
        fun get(context: Context): MediaRepository =
            instance ?: synchronized(this) {
                instance ?: MediaRepository(context.applicationContext).also { instance = it }
            }
    }
}