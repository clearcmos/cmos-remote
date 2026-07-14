# CMOS Remote ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.clearcmos.cmosremote.**$$serializer { *; }
-keepclassmembers class com.clearcmos.cmosremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.clearcmos.cmosremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes
-keep class com.clearcmos.cmosremote.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
