package com.ai.playtv.support.network.json

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.EOFException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import java.lang.reflect.Type

/** HTTP 与 SSE JSON 共用同一 Gson 配置；拒绝空文档、根 null、尾随内容和宽松语法。 */
class JsonCodec(private val gson: Gson) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> decode(text: String, type: Type): T =
        JsonReader(StringReader(text)).use { read(it, gson.getAdapter(TypeToken.get(type))) as T }

    fun <T : Any> decode(text: String, type: Class<T>): T = decode(text, type as Type)

    internal companion object {
        fun <T> read(reader: JsonReader, adapter: TypeAdapter<T>): T {
            reader.isLenient = false
            try {
                val value = adapter.read(reader) ?: throw JsonSyntaxException("JSON root must not be null")
                if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonSyntaxException("Trailing JSON content")
                return value
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: EOFException) {
                throw JsonSyntaxException("Incomplete JSON document", exception)
            } catch (exception: MalformedJsonException) {
                throw JsonSyntaxException("Malformed JSON document", exception)
            } catch (exception: IllegalStateException) {
                throw JsonSyntaxException("Unexpected JSON token", exception)
            }
        }
    }
}
