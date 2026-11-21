# The Swift bridge finds these by name from C (bridge/Sources/CMarqueeJNI/marquee_jni.c) when it loads.
-keep class com.nphil.marquee.core.NativeCore {
    static void onResult(long, byte[]);
    static void transportSend(long, byte[], byte[]);
    static void transportCancel(long);
}

# kotlinx.serialization keeps its generated serializers on the companion; R8 can't see the reflective
# link, so pin the serializer members of every @Serializable model.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional Conscrypt/BouncyCastle/Animal-Sniffer hooks that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
