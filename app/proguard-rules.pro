# kotlinx.serialization keeps its own generated serializers; these are the usual safe additions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.buildersledger.**$$serializer { *; }
-keepclassmembers class com.buildersledger.** { *** Companion; }
-keepclasseswithmembers class com.buildersledger.** { kotlinx.serialization.KSerializer serializer(...); }
