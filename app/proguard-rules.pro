-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }

# Markwon 仅在对应依赖存在时检测这些可选解码器。
# 本应用使用默认位图解码器，缺失类警告仅对以下可选类定向忽略。
# https://noties.io/Markwon/docs/v4/image/#mediadecoder
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn pl.droidsonroids.gif.GifDrawable
