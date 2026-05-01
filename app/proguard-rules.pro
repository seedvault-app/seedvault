# ProGuard rules for Backup
# Preserve debugging info for crash reports and retrace.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the main application classes
-keep class com.stevesoltys.seedvault.** { *; }

# Keep zstd-jni classes - native code accesses fields by name via JNI
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { *; }

# Keep logback classes - reflection-based initialization at runtime
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-keepclassmembers class ch.qos.logback.** { *; }
-keepclassmembers class org.slf4j.** { *; }

# javax.mail classes are optional for logback (SMTP appender) but not used in this app
-dontwarn javax.mail.**
-dontwarn javax.mail.internet.**

# Suppress warnings for Android framework classes not available on all API levels
-dontwarn android.provider.DeviceConfig$OnPropertiesChangedListener
-dontwarn android.provider.DeviceConfig$Properties
-dontwarn com.android.org.conscrypt.TrustManagerImpl
-dontwarn sun.misc.Cleaner
