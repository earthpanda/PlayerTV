package com.ai.playtv.support.network.json

/** 标记 Gson 反射 DTO；搭配 @SerializedName 与随能力交付的定向 R8 规则。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonModel
