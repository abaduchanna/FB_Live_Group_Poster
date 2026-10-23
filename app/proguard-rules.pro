# 3SVerse FB Live Group Poster - R8/proguard rules (t197: anti-AI-reverse)
# R8 shrinks + obfuscates everything EXCEPT the kept components below.
# Accessibility service is driven by the Android system via the manifest -
# its callbacks must survive renaming or the service silently stops working.

# Accessibility service + its callback surface (system reflection)
-keep class com.threesverse.fbliveposter.PosterAccessibilityService { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public void on*(...);
}

# Campaign store persists JSON between runs (field names used as keys)
-keep class com.threesverse.fbliveposter.CampaignStore$* { *; }

# Web poster JS bridge (addJavascriptInterface targets are reflective)
-keepclassmembers class com.threesverse.fbliveposter.WebPosterActivity$* {
    public *;
    @android.webkit.JavascriptInterface <methods>;
}
