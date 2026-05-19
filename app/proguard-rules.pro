# Gson 직렬화 보호
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.minesweeper4d.db.LeaderboardEntry { *; }

# 기본 규칙
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
