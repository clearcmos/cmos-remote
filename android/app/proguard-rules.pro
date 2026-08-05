# Desk Remote ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.clearcmos.deskremote.**$$serializer { *; }
-keepclassmembers class com.clearcmos.deskremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.clearcmos.deskremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes
-keep class com.clearcmos.deskremote.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
