# Morpho

A native Android app that converts documents — PDF ↔ Word ↔ Markdown, with OCR for scans — entirely on-device, in every language, with first-class Arabic/RTL support. It holds no network permission at all.

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

- **Engine (255 tests):** document model with per-block confidence (the Fidelity Report seed); first-strong BiDi detection plus **full UAX #9 run analysis** (every reader splits mixed-direction paragraphs at direction boundaries, so writers mark direction per run — `w:rtl` per piece in Word, dir spans in HTML — instead of mislabeling a whole mixed run by its first strong character); text/Markdown import with inline `**bold**`/`*italic*` styling; a from-scratch OOXML **writer** (styles, per-list restarting numbering, tables, `w:bidi`/`w:rtl`, run languages) and matching **reader** (.docx → model, numbering resolved through numbering.xml, tolerant of unknown content); a **Markdown writer** (model → .md); **PDF extraction with a tagged fast path** — structure, headings, lists, tables and logical reading order read straight from the tag tree when present (with a BDC named-properties fix PDFBox itself lacks), position-aware glyph-clustering heuristics with **column-alignment table detection** for untagged files, plain-text fallback, a shared line-reflow pass so a word hyphenated across a line break does not come back with a space in the middle of it, and a **painting-order reconstruction**: a PDF paints glyphs left to right in the order they land on the page, so right-to-left text arrives backwards, and UAX #9 run reordering puts it back, with presentation-form ligatures folded to the letters that were typed. Neither reader trusts the order glyphs were painted in — one Word-produced paper positions its short runs word by word right to left and paints its long paragraphs as a single left-to-right block, in the same document, so no rule about content order is right for both. Each run's glyphs are instead sorted by where they sit on the page and every line is reconstructed from that, in the tagged reader per marked-content run with the structure tree still deciding the order of runs, and in the untagged reader per line; every line is reconstructed against the document's own direction — its `/Lang`, else the direction most of its text runs in — because a line cannot tell its own, an Arabic line whose leftmost word is an email address starting, visually, with a Latin letter; and when a PDF's ToUnicode map is demonstrably corrupt — Word 2010 labels the digit 0 as 5 and the medial lam as meem in its Arabic subsets, so every العلمي came out العممي and every 2022 came out 2522 — **the embedded font's own cmap overrules it**, glyph by glyph, on fonts whose two maps disagree beyond doubt and never on a healthy one; a ligature glyph such as لا carries two letters already in logical order and is entered backwards so the line's reversal rights it rather than swapping them; a number with separators — a date, a page range — is fenced so it reads as one left-to-right unit wherever it sits, instead of 2022-04-21 coming back as 21-04-2022 after an Arabic word; and a glyph painted a kerning hair to the left of the one before it keeps its painting order, so الجزائر stays الجزائر; **and the look of the page comes across with the words**: every run keeps its face, size and weight, so the bold label at the head of an abstract is bold alone and a raised footnote mark is a superscript; a paragraph keeps its first-line or hanging indent and its alignment, measured against the page's text block rather than the sheet; the spacing between paragraphs and the pitch of their lines are measured off the baselines; a line of dates Word spread with tabs keeps its tab stops; a rule drawn across the page — the line under a paper's dates, the separator above its footnote — goes to the paragraph it belongs to, while a running header's own does not; the page keeps its size and margins; **and the untagged path reads the same things** — a PDF from a scanner or an older tool has no tags, and its lines now carry the face, size and weight of every run, drop the running header and footer that repeat in the margin of page after page, rebuild each line from the order its glyphs were painted — so a kerning step no longer swaps ز and ا and الجزائر keeps its letters, the fix the tagged reader has had since the reversal was solved — break paragraphs where a line stops short of a justified column or the weight changes, and measure alignment, indents, spacing and the page against the block the text occupies rather than the sheet; and a space Word's Arabic justification painted inside a word with nothing clear between the letters is not a word break, so خطوات stays one word; **when a tagged file names no heading at all** — Word only tags one where the author used a heading style, so a paper whose headings were made by hand carries none — type size and bold recover the structure the tags never recorded; a **FidelityReport** generator (per-block confidence → bands *and provenance* — read exactly, read from PDF tags, reconstructed from positions, or recognized by OCR — plus a text-weighted overall score and a most-doubtful-first review list); and a **FidelityScorer** (text + structure similarity) enforcing the multilingual corpus gate — 10 real documents (EN/FR/ES/DE/AR with tashkeel, Arabic headings, mixed Arabic-French, and two dense mixed-direction documents) must survive import → write → read-back with exact text and ≥ 0.95 structure similarity. Adding a file to `ooxml/src/test/resources/corpus/` automatically extends the gate.
- **App:** three conversion paths, all fully on-device — text/Markdown → Word (.docx), Word (.docx) → Markdown, and **PDF → Word** (text PDFs via the position-aware layout heuristics; scanned PDFs offer **on-device OCR** with live per-page progress and a cancel that stops cleanly between pages — Tesseract 5 with fast models for all five app languages bundled (ara/eng/fra/spa/deu, chosen by the app language with a second model riding along for mixed documents), pages rendered at 200 dpi, output scored 0.5 so the Fidelity Report calls it the guess it is), plus **text/Markdown/Word → PDF** two ways: direct-to-file (PdfFileExporter renders the document model with the platform text stack — StaticLayout + PdfDocument, so minikin does real Arabic shaping/BiDi with system fonts — on the document's own page and margins where a reader measured them, with each run set in the face, size and weight it carries, raised marks raised, and the indents and paragraph spacing the page showed, and saves a genuine .pdf through the same save dialog as the other formats) or the system print sheet (engine-generated print-ready HTML rendered by WebView, for paper printing too) — via SAF pick-and-save, the share sheet (send a document to Morpho from any app), and "Open with" on supported types. A finished conversion waits in hand until you save it, so dismissing the system save dialog costs a tap rather than a repeat of a three-minute OCR run — and the fidelity report can be read *before* the file is written. **Review Mode** turns the Fidelity Report into a screen: every block listed with a confidence band and, in words, where its content came from, filtered by default to the parts worth checking — and a block the reader mislabelled can be corrected there and the file written again, so conversion errors are fixed before the file is shared, not discovered after. An About screen carries the version, the privacy guarantee and the open-source attribution with the Apache License in full — a network-free app cannot link to a licence, so it ships one. UI localized in English, Arabic, French, Spanish, German with full RTL support, per-app language config, Material 3 dynamic color, and **no INTERNET permission in the manifest** — the Zero-Upload guarantee starts on day one.

## Building a release

CI builds the launch artifacts on every push (`:app:assembleRelease` and
`:app:bundleRelease`, unsigned) so R8 problems surface here rather than in
users' hands. To produce a signed bundle for Play, supply the keystore
through Gradle properties — never the repository:

```
./gradlew :app:bundleRelease \
  -PMORPHO_KEYSTORE=/absolute/path/morpho.jks \
  -PMORPHO_KEYSTORE_PASSWORD=… -PMORPHO_KEY_ALIAS=morpho -PMORPHO_KEY_PASSWORD=…
```

Put them in `~/.gradle/gradle.properties` instead of the command line to
keep them out of your shell history. With no properties supplied the
release build still succeeds, simply unsigned.

## Shipping

Everything Play needs that can be written ahead of time lives in `store/`:
listing copy in all five languages, a privacy policy, the Data safety form
answers, and `RELEASE-CHECKLIST.md` — which is the honest list of what still
needs a human, starting with the device testing nothing in CI can stand in
for.

## Decisions log

- **Custom OOXML writer/reader** instead of Apache POI/docx4j: 10–20 MB and desktop startup costs avoided; we grow exactly the WordprocessingML subset the engine speaks (plan §5.2).
- **PDF library strategy:** the layout heuristics (`PdfLine`/`PdfLayout`) live in `:engine:layout`, library-agnostic. The engine's `pdf-read` uses desktop PDFBox (Apache-2.0) for JVM development and tests; the app uses the API-compatible tom-roush `pdfbox-android` port in `android/pdf`, whose ~100-line position stripper deliberately mirrors the JVM one (kept in sync by hand until a shared-source split). The structure-tree fast path ships on both sides (StructureTreeReader and its Android twin).
- **DocxReader** skips empty spacer paragraphs and drops runs with no text — deliberate v0 choices documented in its KDoc.
- **MarkdownWriter losses are stated, not hidden:** Markdown has no underline, no direction markup, no run languages; RTL survives in the characters themselves.
- **Images:** PNG/JPEG flow end to end — DOCX media parts with inline `w:drawing` (auto-scaled into the content area) on write, read back as `ImageBlock`s, and self-contained data-URI syntax in Markdown. Unsupported image types are still rejected loudly by the writer (never silently dropped); the reader skips exotic media (EMF/WMF) like other unknown content, with per-part and total inflation caps. PDF images are captured from the content stream (CTM-tracked `Do` operators, forms recursed, sub-8px decorations skipped, marked-content ids recorded) and flow through both PDF paths: on the tagged fast path, `Figure` structure elements resolve to their captured image by marked-content id — logical order preserved — with unreferenced images appended at the end; on the untagged path they interleave into the reconstruction by page position. Either way, PDFs with figures convert to Word files with the figures in place.
- **No Hilt yet** — one ViewModel doesn't justify it; it arrives with the multi-feature module split (plan §5.1).
- **Known limitations (tracked):** process death while the save dialog is open discards the in-memory conversion (the empty stub file is deleted); real state restoration arrives with the WorkManager pipeline. The reader locates the main part at the fixed OPC path `word/document.xml` rather than following the officeDocument relationship.
- **Minified release, with rules that explain themselves:** release builds run R8 and resource shrinking (the app carries ~10 MB of OCR models, so code size is worth reclaiming), and `proguard-rules.pro` keeps exactly the two dependencies R8 cannot see into — Tesseract4Android, reached by name from JNI, and PDFBox, whose font mapper and filter registry are reflective. CI assembles the release APK *and* the App Bundle on every push, because a missing keep rule is invisible in debug builds and fatal in shipped ones.
- **Zero network, permanently:** the app declares no `INTERNET` permission, and Google Docs sync is cut from the roadmap rather than parked — a converter that *cannot* upload your documents is a stronger promise than one that merely doesn't. CI enforces it: the Android job greps the merged manifest (which carries every dependency's permission requests) and fails on any network permission, so the guarantee cannot rot through a transitive dependency.
- **Confidence field** on every block from day one: tagged-PDF extraction scores 0.9, untagged 0.6, native formats 1.0 — the Fidelity heatmap needs no engine rework later.

## Next (per the plan's roadmap)

**Everything a 1.0 needs that can be built without a device is built.** The
release bundle assembles under R8 on every push, the launch material is
written (`store/`), and the remaining work is the kind CI cannot stand in
for — see `store/RELEASE-CHECKLIST.md`, which starts with an ordered
device-test pass because no line of this app has ever run on physical
hardware.

Known and deliberate for 1.0: process death while the save dialog is open
discards the in-memory conversion; the OOXML reader resolves the main part
by its conventional path rather than the officeDocument relationship;
`PdfFileExporter` uses uniform table columns and never splits a row across
pages.

After the first real documents come back: OCR page-segmentation and
resolution tuning (`RENDER_DPI`, and `tessdata_best` if accuracy demands
it), PDF-export layout, then the M4 remainder — re-running OCR on a single
region, keeping a region as an image, Table Lasso. M5's automation (batch,
Magic Folders, widget, history) is post-1.0 by the plan's own monetization
split. Google Docs sync is cut; the app stays network-free.
