package com.ai.playtv.model.repository.source

import com.ai.playtv.model.bean.domain.source.MAX_IMPORTED_SOURCES
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader

/** Only the static configuration and live-list fields have source semantics here. */
internal object SourceJsonRules {
    data class Reference(val url: String, val live: Boolean, val name: String?)
    data class Parsed(val references: List<Reference>, val truncated: Boolean, val recognized: Boolean)

    fun parse(text: String): Parsed {
        val references = mutableListOf<Reference>()
        var truncated = false
        var recognized = false
        try {
            JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Invalid JSON configuration")
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    if ((field != "urls" && field != "lives") || reader.peek() != JsonToken.BEGIN_ARRAY) {
                        if (field == "urls" || field == "lives") truncated = true
                        reader.skipValue()
                        continue
                    }
                    recognized = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) { truncated = true; reader.skipValue(); continue }
                        var url: String? = null
                        var name: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            when {
                                key == "url" && reader.peek() == JsonToken.STRING -> url = reader.nextString()
                                key == "name" && reader.peek() == JsonToken.STRING -> name = reader.nextString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (!url.isNullOrBlank()) {
                            if (references.size < MAX_IMPORTED_SOURCES) references.add(Reference(url, field == "lives", name))
                            else truncated = true
                        } else truncated = true
                    }
                    reader.endArray()
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Invalid JSON configuration")
            }
        } catch (e: IllegalStateException) {
            throw IOException("Invalid JSON configuration", e)
        } catch (e: com.google.gson.JsonParseException) {
            throw IOException("Invalid JSON configuration", e)
        }
        return Parsed(references, truncated, recognized)
    }
}
