# jaudiotagger uses reflection for tag field lookup
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# NewPipe Extractor (Rhino JS engine, jsoup)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.xvin.palimpsest.**$$serializer { *; }
-keepclassmembers class dev.xvin.palimpsest.** { *** Companion; }

-dontwarn org.slf4j.**
-dontwarn org.jspecify.**

# Navigation type-safe routes look enum argument classes up by name.
-keep enum dev.xvin.palimpsest.** { *; }
