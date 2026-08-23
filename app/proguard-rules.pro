# --- kotlinx.serialization -------------------------------------------------
# Official recommended rules (kotlinx.serialization docs): without these, R8
# can strip the generated $serializer companions that Json.encodeToString/
# decodeFromString rely on for every @Serializable class in this project
# (DeepSeekClient's request/response models, MemoryStore's MemoryFact and
# RoutineCommand).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jarvis.assistant.**$$serializer { *; }
-keepclassmembers class com.jarvis.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.jarvis.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Vosk / JNA --------------------------------------------------------------
# JNA bridges to native code via reflection; R8 renaming or stripping any of
# this breaks the wake-word engine with an UnsatisfiedLinkError or
# ClassNotFoundException at runtime that only shows up in a release build,
# never in debug (isMinifyEnabled is release-only), which is exactly the kind
# of gap a "worked in debug" build hides.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**

# --- MediaPipe tasks-genai -----------------------------------------------
# Google's MediaPipe AARs generally bundle their own consumer-rules.pro, but
# this is a defensive keep for the LLM inference API surface this project
# calls directly, in case a future version of the library changes that.
-keep class com.google.mediapipe.tasks.genai.** { *; }
-dontwarn com.google.mediapipe.**

# --- OkHttp / Okio -----------------------------------------------------------
# OkHttp ships its own consumer rules; okio has a long-standing, documented R8
# false-positive warning on some versions for optional platform-specific
# classes it probes for at runtime and gracefully falls back from.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- App's own sealed classes / data models used across process boundaries --
# ToolResult, VoiceIntent, and friends are matched via `is` checks against
# sealed subclasses in JarvisForegroundService; keeping the hierarchy names
# intact avoids R8 merging/renaming interfering with that.
-keep class com.jarvis.assistant.tools.ToolResult { *; }
-keep class com.jarvis.assistant.tools.ToolResult$* { *; }
-keep class com.jarvis.assistant.ai.VoiceIntent { *; }
-keep class com.jarvis.assistant.ai.RouteOutcome { *; }
-keep class com.jarvis.assistant.ai.RouteOutcome$* { *; }

# --- AndroidX Security (EncryptedSharedPreferences / Tink) -------------------
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
