# Keep JavaScript bridge method names and runtime annotations visible to Android WebView.
-keepattributes *Annotation*
-keepclassmembers class com.fieldworks.app.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}
