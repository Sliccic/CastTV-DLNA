# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep DLNA/UPnP classes
-keep class org.fourthline.cling.** { *; }
-dontwarn org.fourthline.cling.**

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep Gson serialization classes
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Keep AccessibilityService
-keep class com.casttv.dlna.service.** { *; }

# Keep data class for broadcast
-keepclassmembers class * {
  @android.os.Parcelable <fields>;
}
