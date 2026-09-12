-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }

# Markwon detects these optional decoders only when their dependencies are present.
# This app uses the default bitmap decoder. Keep missing-class suppression scoped.
# https://noties.io/Markwon/docs/v4/image/#mediadecoder
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn pl.droidsonroids.gif.GifDrawable
