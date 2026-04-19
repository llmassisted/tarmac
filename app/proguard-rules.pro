# Keep JNI entry points
-keep class com.tarmac.service.AirPlayJni { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
