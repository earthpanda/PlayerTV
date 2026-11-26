# Capability-specific reflection rules must be registered alongside that capability.

# Keep only explicitly marked reflection DTOs, not every class in model/support.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep @interface com.ai.playtv.support.network.json.JsonModel
-keep,allowobfuscation @com.ai.playtv.support.network.json.JsonModel class * {
    <fields>;
    <init>(...);
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
# Retrofit 2.9.0 supplies service proxy rules; preserve suspend generic endpoints in R8 full mode.
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
