# Morpho release rules.
#
# R8 sees only Java/Kotlin references. Two of this app's dependencies are
# reached by paths it cannot follow — JNI from native code, and reflection —
# so their classes are kept by name. Everything else (the engine, the app,
# Compose, AndroidX) is ordinary code and is shrunk and optimized normally.

# --- Tesseract4Android -------------------------------------------------
# libjpeg/leptonica/tesseract call back into these classes and their fields
# and methods by name through JNI. Renaming or removing any member breaks
# OCR at runtime with an UnsatisfiedLinkError or a silent null, neither of
# which shows up at build time.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# --- PDFBox (tom-roush Android port) -----------------------------------
# PDFBox resolves fonts, filters and COS object types by name at runtime
# (its font mapper and filter registry are both reflective), and the port
# keeps the desktop lineage's optional references to AWT and ImageIO,
# which do not exist on Android and are never executed there.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# --- XML parsing -------------------------------------------------------
# The OOXML reader uses the platform's JAXP implementation, which is looked
# up through the service-provider mechanism.
-dontwarn javax.xml.**
-keepclassmembers class * implements org.xml.sax.ContentHandler { *; }

# Keep the line numbers a crash report needs, without keeping source paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- The editor's bridge -----------------------------------------------
# The editor's page calls the bridge's methods by name through the
# WebView's JavaScript interface. R8 cannot see those calls; a renamed
# method is a page that types and nothing happens.
-keepclassmembers class app.morpho.converter.EditorBridge {
    @android.webkit.JavascriptInterface <methods>;
}
