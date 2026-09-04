# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements kotlinx.serialization.KSerializer {
    <init>(...);
}
-keepclassmembers class * implements kotlinx.serialization.internal.GeneratedSerializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# Models, Domain, and Security
-keep class com.example.domain.models.** { *; }
-keep class com.example.data.models.** { *; }
-keep class com.example.domain.security.** { *; }
-keep class com.example.security.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }
