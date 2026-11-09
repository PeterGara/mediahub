package com.petergara.mediahub.ai

import org.json.JSONObject

/**
 * PEVNÝ PRÍKAZOVÝ KONTRAKT (docs/ai-contract.md).
 *
 * Toto je dohoda medzi appkou a AI – nikdy sa nemení, len pridávajú príkazy.
 * Mení sa iba KTO príkazy plní (lokálny Qwen, omni model na PC, cloud...).
 */
object AiCommands {

    data class Command(
        val name: String,
        val description: String,
        val params: List<Pair<String, String>>, // (meno, popis)
        val returns: String
    )

    val ALL = listOf(
        Command(
            "list_media",
            "Zoznam mediálnych súborov (hudba/fotky/videá) podľa filtra a hľadaného textu.",
            listOf(
                "type" to "audio|image|video|all",
                "query" to "voliteľný text na filtrovanie názvu/interpreta/žánru",
                "limit" to "max počet, predvolené 50"
            ),
            "{items:[{id, name, artist?, album?, duration_ms?, size, path}]}"
        ),
        Command(
            "rename",
            "Premenovanie súboru (aj súčasne v MediaStore). Vráti nový názov.",
            listOf(
                "id" to "MediaStore ID z list_media",
                "kind" to "audio|image|video",
                "new_name" to "nový názov vrátane prípony"
            ),
            "{ok, id, new_name}"
        ),
        Command(
            "move",
            "Premiestnenie súboru do priečinka (relatívne k koreňu médií).",
            listOf(
                "id" to "MediaStore ID",
                "kind" to "audio|image|video",
                "folder" to "napr. Hudba/Prehrávane alebo cesta"
            ),
            "{ok, new_path}"
        ),
        Command(
            "search",
            "Plnotextové hľadanie cez uložené tagy a metadáta (vracia rovnaký tvar ako list_media).",
            listOf("text" to "hľadaný výraz alebo tag"),
            "{items:[...]}"
        ),
        Command(
            "play",
            "Spustí prehrávanie súboru v appke.",
            listOf("id" to "MediaStore ID", "kind" to "audio|video"),
            "{ok}"
        ),
        Command(
            "analyze_audio",
            "AI analýza zvuku → názov, interpret, žáner, jazyk, nálada, tagy.",
            listOf("id" to "MediaStore ID"),
            "{title, artist, genre, language, mood, bpm?, tags[]}"
        ),
        Command(
            "analyze_image",
            "AI analýza obrázka → popis, tagy, návrh názvu súboru.",
            listOf("id" to "MediaStore ID"),
            "{description, tags[], suggested_name}"
        ),
        Command(
            "set_tags",
            "Zapísanie tagov do mediálneho súboru + do lokálnej DB.",
            listOf("id" to "MediaStore ID", "kind" to "audio|image|video", "tags" to "pole stringov"),
            "{ok}"
        )
    )

    /** JSON schéma príkazov – toto dostane model ako systémový kontext. */
    fun contractAsJson(): String = buildString {
        append("[")
        ALL.forEachIndexed { i, c ->
            if (i > 0) append(",")
            append("{\"name\":\"${c.name}\",\"description\":\"${c.description}\",\"params\":{")
            c.params.forEachIndexed { j, (n, d) ->
                if (j > 0) append(",")
                append("\"$n\":\"$d\"")
            }
            append("},\"returns\":\"${c.returns}\"}")
        }
        append("]")
    }

    /** Pohodlný vstup pre REST /tools. */
    fun asJsonString(): String = contractAsJson()

    /** Rozpoznanie tool-call JSON od modelu: {"cmd":"rename",...} */
    fun parseToolCall(json: String): Pair<String, JSONObject>? {
        val o = JSONObject(json)
        val name = o.optString("cmd").ifBlank { o.optString("name") }
        if (name.isBlank()) return null
        return name to o
    }
}