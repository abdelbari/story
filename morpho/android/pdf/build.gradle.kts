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
}
