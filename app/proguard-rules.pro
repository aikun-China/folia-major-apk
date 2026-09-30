# Folia WebView 壳 ProGuard 规则
# minifyEnabled 已关闭；保留规则以备将来开启混淆
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
