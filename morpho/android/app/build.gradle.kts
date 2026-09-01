plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from Gradle properties (or -P flags / ~/.gradle/
// gradle.properties) so no key material ever lives in the repository. With
// none supplied — as in CI — the release build is simply left unsigned,
// which is enough to exercise R8.
val releaseStore = providers.gradleProperty("MORPHO_KEYSTORE").orNull
val releaseStorePassword = providers.gradleProperty("MORPHO_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("MORPHO_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("MORPHO_KEY_PASSWORD").orNull

android {
    namespace = "app.morpho.converter"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.morpho.converter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Only the languages Morpho is actually translated into ship, so the
        // ~80 locales AndroidX carries do not pad the download. The set must
        // stay in step with res/xml/locales_config.xml, which drives the
        // per-app language picker.
        resourceConfigurations += listOf("en", "ar", "fr", "es", "de")
    }

    signingConfigs {
        if (releaseStore != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // On: the app ships ~10 MB of OCR models, so every byte of code
            // and unused resource is worth shrinking. proguard-rules.pro
            // explains what has to survive and why.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Null when no keystore was supplied; the build is unsigned then.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The About screen shows the version it was built from.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Conversion engine (pure JVM, substituted from ../engine via includeBuild).
    implementation("app.morpho.engine:layout")
    implementation("app.morpho.engine:ooxml")

    implementation(project(":core:design"))
    implementation(project(":pdf"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
