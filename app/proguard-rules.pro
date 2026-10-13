-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.atmos.launcher.**$$serializer { *; }
-keepclassmembers class com.atmos.launcher.** { *** Companion; }
-keepclasseswithmembers class com.atmos.launcher.** { kotlinx.serialization.KSerializer serializer(...); }

# SDK de Spotify (usa Gson por reflexión sobre sus tipos)
-keep class com.spotify.** { *; }
-dontwarn com.spotify.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
