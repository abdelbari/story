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
    ├── core/design/    Theme (Material 3, dynamic color, Morpho palette)
    └── pdf/            On-device PDF reader (tom-roush PDFBox port)
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

## What works today

- **Engine (100+ tests):** document model with per-block confidence (the Fidelity Report seed); first-strong BiDi detection; text/Markdown import with inline `**bold**`/`*italic*` styling and per-run RTL direction; a from-scratch OOXML **writer** (styles, per-list restarting numbering, tables, `w:bidi`/`w:rtl`, run languages) and matching **reader** (.docx → model, numbering resolved through numbering.xml, tolerant of unknown content); a **Markdown writer** (model → .md); **PDF extraction with a tagged fast path** — structure, headings, lists, tables and logical reading order read straight from the tag tree when present (with a BDC named-properties fix PDFBox itself lacks), position-aware glyph-clustering heuristics with **column-alignment table detection** for untagged files, plain-text fallback; and a **FidelityScorer** (text + structure similarity) enforcing the multilingual corpus gate — 8 real documents (EN/FR/ES/DE/AR with tashkeel, Arabic headings, mixed Arabic-French) must survive import → write → read-back with exact text and ≥ 0.95 structure similarity. Adding a file to `ooxml/src/test/resources/corpus/` automatically extends the gate.
- **App:** three conversion paths, all fully on-device — text/Markdown → Word (.docx), Word (.docx) → Markdown, and **PDF → Word** (text PDFs via the position-aware layout heuristics; scanned PDFs get an honest "OCR arrives with M3" message), plus **text/Markdown/Word → PDF** two ways: direct-to-file (PdfFileExporter renders the document model with the platform text stack — StaticLayout + PdfDocument, so minikin does real Arabic shaping/BiDi with system fonts — and saves a genuine .pdf through the same save dialog as the other formats) or the system print sheet (engine-generated print-ready HTML rendered by WebView, for paper printing too) — via SAF pick-and-save, the share sheet (send a document to Morpho from any app), and "Open with" on supported types. UI localized in English, Arabic, French, Spanish, German with full RTL support, per-app language config, Material 3 dynamic color, and **no INTERNET permission in the manifest** — the Zero-Upload guarantee starts on day one.

## Decisions log

- **Custom OOXML writer/reader** instead of Apache POI/docx4j: 10–20 MB and desktop startup costs avoided; we grow exactly the WordprocessingML subset the engine speaks (plan §5.2).
- **PDF library strategy:** the layout heuristics (`PdfLine`/`PdfLayout`) live in `:engine:layout`, library-agnostic. The engine's `pdf-read` uses desktop PDFBox (Apache-2.0) for JVM development and tests; the app uses the API-compatible tom-roush `pdfbox-android` port in `android/pdf`, whose ~100-line position stripper deliberately mirrors the JVM one (kept in sync by hand until a shared-source split). The structure-tree fast path ships on both sides (StructureTreeReader and its Android twin).
- **DocxReader** skips empty spacer paragraphs and drops runs with no text — deliberate v0 choices documented in its KDoc.
- **MarkdownWriter losses are stated, not hidden:** Markdown has no underline, no direction markup, no run languages; RTL survives in the characters themselves.
- **Images:** PNG/JPEG flow end to end — DOCX media parts with inline `w:drawing` (auto-scaled into the content area) on write, read back as `ImageBlock`s, and self-contained data-URI syntax in Markdown. Unsupported image types are still rejected loudly by the writer (never silently dropped); the reader skips exotic media (EMF/WMF) like other unknown content, with per-part and total inflation caps. PDF images are captured from the content stream (CTM-tracked `Do` operators, forms recursed, sub-8px decorations skipped, marked-content ids recorded) and flow through both PDF paths: on the tagged fast path, `Figure` structure elements resolve to their captured image by marked-content id — logical order preserved — with unreferenced images appended at the end; on the untagged path they interleave into the reconstruction by page position. Either way, PDFs with figures convert to Word files with the figures in place.
- **No Hilt yet** — one ViewModel doesn't justify it; it arrives with the multi-feature module split (plan §5.1).
- **Known limitations (tracked):** process death while the save dialog is open discards the in-memory conversion (the empty stub file is deleted); real state restoration arrives with the WorkManager pipeline. The reader locates the main part at the fixed OPC path `word/document.xml` rather than following the officeDocument relationship.
- **Confidence field** on every block from day one: tagged-PDF extraction scores 0.9, untagged 0.6, native formats 1.0 — the Fidelity heatmap needs no engine rework later.

## Next (per the plan's roadmap)

M1 is complete. M2 remainder: print-pipeline and PDF-renderer polish after device testing, full BiDi run analysis (UAX #9). M3: OCR (ML Kit + Tesseract). M4: Google Docs sync + Review Mode.
