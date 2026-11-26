package com.ai.playtv.support.network.converter

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ai.playtv.support.network.NetworkDiagnostic
import com.ai.playtv.support.network.NetworkDiagnostics
import com.ai.playtv.support.network.json.JsonCodec
import com.ai.playtv.support.network.report
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type

/** 保留 Sony 的 TypeToken/TypeAdapter 转换边界，流式解析并关闭 body，不记录原文。 */
class DiagnosticGsonConverterFactory private constructor(
    private val gson: Gson,
    private val diagnostics: NetworkDiagnostics,
) : Converter.Factory() {
    private val requestDelegate = GsonConverterFactory.create(gson)

    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>,
        retrofit: Retrofit): Converter<ResponseBody, *> {
        val adapter = gson.getAdapter(TypeToken.get(type))
        return Converter<ResponseBody, Any> { body ->
            body.use {
                try {
                    JsonCodec.read(gson.newJsonReader(it.charStream()), adapter)
                } catch (exception: Exception) {
                    diagnostics.report(NetworkDiagnostic("json", failureType = exception.javaClass.simpleName))
                    throw exception
                }
            }
        }
    }

    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>, retrofit: Retrofit): Converter<*, RequestBody>? =
        requestDelegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

    companion object {
        fun create(gson: Gson, diagnostics: NetworkDiagnostics = NetworkDiagnostics.DEFAULT) =
            DiagnosticGsonConverterFactory(gson, diagnostics)
    }
}
