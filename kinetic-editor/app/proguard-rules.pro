# Kinetic release rules.

# Media3 selects renderers, decoders, effects and muxers reflectively; keeping
# the public surface avoids "no suitable decoder" / missing-effect failures that
# only appear in a minified build.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# GL shader programs are instantiated by the effect pipeline.
-keep class com.kinetic.editor.effects.** { *; }

# The document is persisted as JSON and re-read after process death, so the
# model classes and their generated serializers must survive shrinking.
-keep class com.kinetic.editor.core.model.** { *; }
-keepclassmembers class com.kinetic.editor.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.kinetic.editor.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static <1>$Companion Companion;
    static **$* *;
}

# WorkManager instantiates workers by name.
-keep class com.kinetic.editor.engine.ExportWorker { <init>(...); }

# Kotlin coroutines / immutable collections internals.
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.collections.immutable.**
