plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.morpho.pdf"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("app.morpho.engine:layout")
    // The Android port of PDFBox (API-compatible with the desktop PDFBox the
    // JVM engine uses, under the com.tom_roush package). Apache-2.0.
    implementation(libs.pdfbox.android)
    // Tesseract 5 for on-device OCR (M3) — the maintained tess-two successor,
    // Apache-2.0. Arabic is the point: ML Kit's on-device models cover Latin,
    // CJK and Devanagari scripts but not Arabic script.
    implementation(libs.tesseract4android)
}
