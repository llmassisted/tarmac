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

# JmDNS — pure-Java mDNS responder. It uses reflection internally and pulls in
# javax.jmdns.* / java.beans references that don't exist on Android (harmless;
# they're only reached on full JDK). Suppress warnings and keep the public API.
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-dontwarn java.beans.**
