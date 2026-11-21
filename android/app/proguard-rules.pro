# ProGuard rules for ScreenGuard

-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Firebase models
-keepclassmembers class * {
    public <init>();
    public <methods>;
    public <fields>;
}

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
