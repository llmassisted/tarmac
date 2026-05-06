# Keep JNI entry points
-keep class com.tarmac.service.AirPlayJni { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# SessionStateBus is accessed from multiple components via its singleton
-keep class com.tarmac.service.SessionStateBus { *; }

# Leanback uses reflection for presenter instantiation
-keep class * extends androidx.leanback.widget.Presenter { *; }

# ExoPlayer / Media3 — keep the module service loaders
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
