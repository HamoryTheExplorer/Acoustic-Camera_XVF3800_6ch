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

# 保留 JNI 回调类不被 R8 内联/移除
# NativePcmCallback 是 abstract class，其 onPcmData 等抽象方法
# 在 JNI 层通过 GetMethodID 按名称查找。R8 可能将父类内联到
# Kotlin 匿名子类中，导致 GetSuperclass 返回 Object 而非 NativePcmCallback。
-keep class com.example.acousticcamera.data.NativePcmCallback { *; }
-keep class * extends com.example.acousticcamera.data.NativePcmCallback { *; }