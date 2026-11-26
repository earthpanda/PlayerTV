package com.ai.playtv.support.network.json

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.lang.reflect.Type

/**
 * 从 Sony NoteDetailTextTypeAdapter 提取“JSON 被编码成字符串”的入站兼容。
 * 仅注册明确 Type；不全局把空串/错误对象转成空列表。对象/数组二选一属于业务适配器。
 * 写出仍为正常 JSON；需要双向字符串协议时另建对应 model 适配器。
 */
class JsonStringTypeAdapterFactory(types: Set<Type>) : TypeAdapterFactory {
    private val acceptedTypes = types.toSet()

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.type !in acceptedTypes) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun read(reader: JsonReader): T? {
                if (reader.peek() != JsonToken.STRING) return delegate.read(reader)
                val encoded = reader.nextString()
                if (encoded.isBlank()) throw JsonSyntaxException("Encoded JSON must not be blank")
                return JsonReader(StringReader(encoded)).use { JsonCodec.read(it, delegate) }
            }
            override fun write(writer: JsonWriter, value: T?) = delegate.write(writer, value)
        }
    }
}
