# Keep JNI entry points
-keep class com.tarmac.service.AirPlayJni { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# SessionStateBus is accessed from multiple components via its singleton
-keep class com.tarmac.service.SessionStateBus { *; }

# Leanback uses reflection for presenter instantiation
-keep class * extends androidx.leanback.widget.Presenter { *; }

# Fragments are instantiated reflectively by FragmentManager (initial create
# and after process death / config change). R8 doesn't see the call site and
# strips the no-arg <init>, causing NoSuchMethodException on activity restore
# — observed on Hisense H8G after the native AirPlay thread crashed and the
# OS relaunched MainActivity. Keep the no-arg constructor on every Fragment
# subclass.
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>();
}
-keepclassmembers class * extends androidx.leanback.app.BrowseSupportFragment {
    public <init>();
}
-keepclassmembers class * extends androidx.leanback.preference.LeanbackPreferenceFragmentCompat {
    public <init>();
}

# ExoPlayer / Media3 — keep the module service loaders
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# JmDNS — pure-Java mDNS responder. It uses reflection internally and pulls in
# javax.jmdns.* / java.beans references that don't exist on Android (harmless;
# they're only reached on full JDK). Suppress warnings and keep the public API.
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-dontwarn java.beans.**
