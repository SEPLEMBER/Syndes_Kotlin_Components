# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Игнорируем отсутствующие классы JSR-223 из luaj-jse
-dontwarn javax.script.**
-dontwarn org.luaj.vm2.script.**

# Игнорируем отсутствующие классы Apache BCEL
-dontwarn org.apache.bcel.**

# ВАЖНО: LuaJ использует рефлексию, поэтому нужно сохранить все оригинальные имена классов и методов
-keep class org.luaj.vm2.** { *; }
-keepclassmembers class org.luaj.vm2.** { *; }

# Сохраняем аннотации ТОЛЬКО для LuaJ, а не для всего приложения
-keep,allowobfuscation @interface org.luaj.vm2.**
-keep @org.luaj.vm2.** class * { *; }
-keepclassmembers class org.luaj.vm2.** {
    @org.luaj.vm2.** *;
}
