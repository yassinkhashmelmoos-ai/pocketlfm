# Thunder AGI ProGuard/R8 Rules
# Obfuscates code to prevent reverse engineering

# Keep Thunder AGI subscription logic intact (critical security)
-keep class com.thunder.agi.subscription.** { *; }
-keepclassmembers class com.thunder.agi.subscription.SubscriptionManager {
    private static final java.lang.String ADMIN_USERNAMES;
    private static final java.lang.String RESET_KEY;
    private static final java.lang.String BANKAK_RECEIVER;
}

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Keep JNI methods (native bridge must not be renamed)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.thunder.agi.llama.ThunderModel {
    public static native *;
}
-keep class com.thunder.agi.agent.SchemeInterpreter {
    public static native *;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }

# Keep Room entities
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep WorkManager
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Remove logging in release (security + size)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Prevent reflection-based APK extraction from working
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
