// Runs the app's Android PDF readers against the real PDFBox-Android port
// on the JVM. See README.md in this directory for why.
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

/** The port, as the Android archive it is published as. */
val portArchive: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    portArchive(libs.pdfbox.android)
}

val unpackPort by tasks.registering(Sync::class) {
    description = "Unpacks the port's classes and assets out of its Android archive."
    from(portArchive.elements.map { archives -> archives.map { zipTree(it.asFile) } }) {
        include("classes.jar")
        include("assets/**")
    }
    into(layout.buildDirectory.dir("port"))
}

val portClasses = files(layout.buildDirectory.file("port/classes.jar")).builtBy(unpackPort)
val portAssets = files(layout.buildDirectory.dir("port/assets")).builtBy(unpackPort)

sourceSets {
    main {
        // The Android readers as they ship, not a copy of them. What is
        // left out needs the phone itself — a Context to reach the app's
        // files, a camera-quality image, the OCR engine — and there is
        // nothing about the PDF port to check in it. Anything else that
        // stops compiling here is a reader that has grown a dependency on
        // Android, which is worth knowing about.
        kotlin.srcDir("../../android/pdf/src/main/kotlin")
        kotlin.exclude("**/AndroidOcrReader.kt", "**/AndroidPdfReader.kt", "**/AndroidImageCapture.kt")
    }
}

dependencies {
    implementation(project(":layout"))
    implementation(portClasses)
    // The port reads its glyph list and its fonts from the assets of its
    // archive; on the JVM they have to be on the class path instead.
    runtimeOnly(portAssets)
    // The fixtures are written with desktop PDFBox, which shares no package
    // with the port, so both can stand side by side in one test.
    testImplementation(libs.pdfbox)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(portAssets)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
