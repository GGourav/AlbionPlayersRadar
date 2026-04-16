-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep model classes
-keep class com.albionplayersradar.data.** { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
