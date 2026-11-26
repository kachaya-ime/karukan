# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Karukan IME package classes and members for JNI interop
-keep class io.github.kachaya.karukan.** { *; }
