# Morpho

A native Android app that converts documents — PDF ↔ Word ↔ Google Docs — entirely on-device, in every language, with first-class Arabic/RTL support.

The full product & engineering plan lives at [`../Morpho-Android-App-Plan.md`](../Morpho-Android-App-Plan.md). This directory is its implementation.

## Repository layout: two Gradle builds, on purpose

```
morpho/
├── engine/     Pure-JVM Gradle build — the conversion engine.
│   ├── layout/     Document model (IR), BiDi helpers, text/Markdown importer
│   ├── ooxml/      Custom lightweight DOCX writer (no POI, no docx4j)
│   └── pdf-read/   PDF inspection + extraction (tagged-PDF detection)
└── android/    Android Gradle build — the app.
    ├── app/            Compose UI, conversion flow, SAF integration
    └── core/design/    Theme (Material 3, dynamic color, Morpho palette)
```

The engine is a **separate build with no Android dependency, enforced by construction**: it cannot even resolve Android APIs. The app composite-includes it (`includeBuild("../engine")`) and depends on `app.morpho.engine:layout` / `app.morpho.engine:ooxml`. This is the plan's §5.1 module architecture — engine modules must stay platform-independent so they can be developed, tested, and fuzzed on the JVM at full speed.

## Building

Engine (works anywhere with a JDK 17+):

```
cd morpho/engine && ./gradlew build        # compiles + runs all engine tests
```

App (needs the Android SDK; open `morpho/android` in Android Studio, or):

```
cd morpho/android && ./gradlew :app:assembleDebug
```

## What works today (v0 vertical slice)

- **Engine:** document model with per-block confidence (the Fidelity Report seed), first-strong BiDi detection, plain-text/Markdown import (headings, bullet + numbered lists, paragraph unwrapping, per-paragraph RTL tagging), a from-scratch OOXML writer producing valid .docx (styles, numbering, tables, `w:bidi`/`w:rtl`, run languages), and PDFBox-based extraction with tagged-PDF detection. 25 unit tests.
- **App:** pick a text/Markdown file via SAF → convert on-device with the engine → save the .docx wherever the user chooses. UI localized in English, Arabic, French, Spanish, German with full RTL support, per-app language config, Material 3 dynamic color, and **no INTERNET permission in the manifest** — the Zero-Upload guarantee starts on day one.

## Decisions log

- **Custom OOXML writer** instead of Apache POI/docx4j: 10–20 MB and desktop startup costs avoided; we grow a writer that covers exactly what the engine emits (plan §5.2).
- **PDF library strategy:** the engine's `pdf-read` uses desktop PDFBox (Apache-2.0) for JVM development and tests. On Android it swaps to the API-compatible tom-roush `pdfbox-android` port behind the same `PdfReader` interface when the M1 extraction pipeline lands. The app does not include `pdf-read` yet.
- **Numbered lists** currently share one numbering instance (continuous numbering across separate lists); per-list restart needs distinct `w:num` instances — queued for M1.
- **Inline Markdown emphasis** (`**bold**`) is imported verbatim for now; run-level styling is M1 scope.
- **Images** are rejected loudly by the writer (never silently dropped) until the media-part work in M1.
- **No Hilt yet** — one ViewModel doesn't justify it; it arrives with the multi-feature module split (plan §5.1).
- **Confidence field** on every block from day one, so the Fidelity heatmap needs no engine rework later.

## Next (per the plan's roadmap)

M1: real PDF→DOCX (tagged fast path + layout heuristics), DOCX reading, share-sheet targets. M2: DOCX→PDF print pipeline + full BiDi run analysis. M3: OCR (ML Kit + Tesseract). M4: Google Docs sync + Review Mode.
