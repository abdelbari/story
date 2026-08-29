plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":layout"))
    // Desktop PDFBox for engine development and tests. On Android this is
    // swapped for the API-compatible tom-roush port behind the same reader
    // interface (see ../../README.md, "PDF library strategy").
    implementation(libs.pdfbox)
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
