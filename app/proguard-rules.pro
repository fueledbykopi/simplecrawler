# Pocket Crawler ProGuard / R8 rules
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Keep AndroidX Bundled SQLite JNI bindings
-keep class androidx.sqlite.driver.bundled.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
