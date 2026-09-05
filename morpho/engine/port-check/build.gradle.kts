import org.gradle.api.tasks.PathSensitivity

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
        // The Android readers as they ship, not a copy of them, and all
        // of them: three used to be left out for needing the phone itself
        // — a context to reach the app's files, its bundled language
        // packs, the recognition library — so a change to any of the three
        // was compiled for the first time by CI's Android job, minutes
        // after it was pushed. Each of those is one small class, stubbed
        // here beside Log and Paint, and with them stubbed all three
        // compile in this build.
        //
        // Compile, not run: the stubs have no canvas behind the bitmap, no
        // assets to open and no Tesseract to ask, so what this catches is
        // a reader that stopped compiling, which is the mistake that is
        // easiest to make and cheapest to catch.
        kotlin.srcDir("../../android/pdf/src/main/kotlin")
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

    // Several of these tests read files rather than call code: the engine
    // readers each Android twin is compared against, the language packs
    // recognition can be asked for, and the exporter that must name no
    // type size of its own. None of those is a source of this module or a
    // dependency of it, so without saying so here the build calls this
    // task up to date and skips it — which it did, silently, the first
    // time a guard was written this way and then deliberately broken to
    // check it bites. It bit only after a clean. In CI every run starts
    // from an empty checkout and so always runs; on the machine where the
    // change is actually being made, it would not have.
    inputs.dir("../pdf-read/src/main/kotlin")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("engineReaders")
    inputs.dir("../../android/pdf/src/main/assets/tessdata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("languagePacks")
    inputs.file("../../android/app/src/main/kotlin/app/morpho/converter/PdfFileExporter.kt")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("pdfExporter")
    // What the app says, in all five languages. These are compiled by the
    // Android build and by nothing on this machine, so a mistake in them
    // is otherwise found minutes away in CI — which it has been, three
    // times.
    inputs.dir("../../android/app/src/main/res")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("appStrings")
}
