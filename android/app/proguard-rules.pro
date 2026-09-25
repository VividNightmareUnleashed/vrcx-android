# The page talks to native code through WebViewCompat.addWebMessageListener (no reflection), and bridge modules are
# registered explicitly in AppGraph, so R8 needs no keep rules for them. Kept for any future @JavascriptInterface use.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Readable stack traces in crash logs (logcat and the web console log).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
