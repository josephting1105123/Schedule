# MediaPipe GenAI ships native code reached through JNI; keep its entry points so R8 can't strip
# the classes the native layer looks up by name.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Glance/AppWidget receivers are instantiated by the framework from the manifest.
-keep class com.EntropicSoftwareLab.Schedule.widget.** { *; }
