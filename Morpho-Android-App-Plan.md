# Morpho — Product & Engineering Plan

**A native Android app that converts PDF ↔ Word ↔ Google Docs (and every major document format), entirely on-device, in every language — including full right-to-left Arabic support.**

Working title: **Morpho** (the morpho butterfly — transformation; the two wings mirror the app's signature side-by-side "original vs. converted" view). Alternative names: *Wathiq* (واثق — "confident", puns on وثائق "documents"), *Transmute*, *DocAlchemy*.

---

## 1. Vision

Every document converter on Android today makes the same three compromises: they **upload your files to a server**, they **butcher non-Latin scripts** (Arabic comes out disconnected, reversed, or as boxes), and they **hide how bad the conversion was** until you've already sent the file to someone.

Morpho's thesis is the opposite:

1. **Private by architecture** — conversion runs 100% on-device. The marketing demo is literally done in airplane mode.
2. **Multilingual by design, not translation** — Arabic, French, English, Spanish, German (and beyond) are first-class in both the UI *and* the conversion engine: BiDi text, letter shaping, RTL layout, mixed-direction paragraphs, per-script OCR.
3. **Honest by default** — every conversion produces a **Fidelity Report**: a visual confidence heatmap over the document showing exactly which parts converted perfectly and which were approximated, with one-tap fixes.

No watermarks. No file-size caps on core features. No ads. No account required.

---

## 2. The competition, and exactly how we beat it

| Competitor | Their weakness | Morpho's answer |
|---|---|---|
| Adobe Acrobat | PDF→Word locked behind subscription, cloud-only, slow round-trip | Free core conversion, instant, offline |
| Microsoft Word app | Server-side PDF import, mediocre layout fidelity, no transparency | On-device engine + Fidelity Report |
| iLovePDF / Smallpdf / "PDF Converter" apps | Mandatory upload, size limits, watermarks, subscription nagging, ads | Zero-upload, no limits, no watermarks, no ads |
| WPS Office | Bloated (hundreds of MB), aggressive ads, privacy record | < 30 MB base install, no ads, no telemetry on documents |
| Nearly all of them | **Arabic/RTL output is garbled** (disconnected letters, reversed word order) | Purpose-built BiDi + shaping pipeline, tested against an Arabic golden corpus |
| Nearly all of them | No Google Docs integration at all | Two-way Google Docs sync as a first-class feature |
| All of them | Black-box conversion — you discover errors after sharing | Fidelity heatmap + interactive Review Mode before export |

**Positioning line:** *"The document converter that never sees your documents."*

---

## 3. Feature set

### 3.1 Core conversions (v1)

- **PDF → Word (.docx)** — text PDFs first, scanned PDFs via OCR (§3.3).
- **Word (.docx) → PDF** — with correct RTL layout, embedded fonts, and *tagged (accessible) PDF* output — almost no competitor produces PDF/UA-friendly output; institutions care.
- **PDF / Word → Google Doc** — one tap: convert, upload via Drive API with import conversion, open directly in the Google Docs app.
- **Google Doc → PDF / Word** — pick a Doc from Drive, export, and optionally continue converting offline.
- **Any → Text / Markdown / HTML** — cheap to support, surprisingly demanded (developers, students).

### 3.2 Universal document support (v1.x → v2)

Phased, honestly sequenced:

- **Phase 1:** PDF, DOCX, TXT, Markdown, HTML, RTF, images (JPG/PNG/HEIC/WebP → OCR).
- **Phase 2:** ODT (LibreOffice), EPUB, legacy .doc (best-effort text+basic formatting; be transparent that binary .doc is lossy).
- **Phase 3:** PPTX ↔ PDF, XLSX ↔ PDF, and **PDF-tables → XLSX** (huge accountant/student demand).

### 3.3 Scan-to-Word pipeline

CameraX + ML Kit Document Scanner (edge detection, de-skew, shadow removal) → per-region OCR → structured DOCX. Point the camera at a paper contract in Arabic or a French invoice and get an editable Word file.

### 3.4 Signature features (the "nobody else has this" list)

1. **Fidelity Report & confidence heatmap** — after every conversion, a side-by-side view (original left, result right — the butterfly's wings) with green/amber/red overlay per block. Tap an amber/red block to fix it: reclassify (heading ↔ paragraph ↔ table ↔ image), re-run OCR with a different language, or keep the region as an image snippet so nothing is ever silently lost.
2. **Polyglot OCR** — per-region language detection on a single page. A Maghreb utility bill mixing Arabic and French, or a German contract with English clauses, OCRs each region with the right model. Competitors force one language per document.
3. **Zero-Upload Guarantee** — the conversion engine runs in a separate Android process with **no INTERNET permission**, verifiable by anyone. Only the optional Google Docs feature touches the network, and only with files the user explicitly picks.
4. **Magic Folders** — watch a folder (e.g., WhatsApp Documents); anything dropped in is auto-converted to a chosen format. Built on WorkManager + SAF tree URIs.
5. **Document Time Machine** — Morpho keeps the original + conversion settings linked to each output. When the engine improves in an update, one tap re-converts old documents with the new engine and shows a diff. *"Your past conversions get better with every update."*
6. **Table Lasso** — lasso-select any region of a PDF page and extract just that region as a Word table, Excel sheet, or Markdown table.
7. **Everywhere integration** — share-sheet target, "Open with" handler for all supported MIME types, home-screen widget (drop zone + recent conversions), Quick Settings tile, direct-share targets ("Convert & send back to WhatsApp chat").

---

## 4. Languages: the deep story

This is the moat. Two distinct layers, both specced:

### 4.1 UI localization

- **Launch locales:** English, Arabic, French, Spanish, German. **Fast follow:** Turkish, Portuguese (BR), Italian, Indonesian, Hindi, Russian, Japanese, Korean, Chinese (Simplified).
- Per-app language picker (Android 13 `LocaleManager`, backported via `AppCompatDelegate.setApplicationLocales`).
- **Full RTL mirroring** — every layout uses start/end, `layoutDirection` aware icons (back arrows, progress), RTL-verified with pseudolocale `ar-XB` in CI screenshots.
- Locale-aware numerals (option for Eastern Arabic digits ٠١٢٣), dates, and file sizes via ICU.
- Professional translation + paid native-speaker review for Arabic specifically (machine-translated Arabic UI is how competitors signal they don't care).

### 4.2 Content-level language engineering (the hard, valuable part)

- **Extraction:** Arabic text inside PDFs is frequently stored in visual order or with presentation-form glyphs (U+FB50–U+FEFF). The extractor normalizes presentation forms back to logical Unicode, runs the BiDi algorithm (ICU4J, bundled in Android) to recover logical order, and preserves diacritics (tashkeel) instead of stripping them.
- **Mixed-direction paragraphs** — Arabic sentence with an inline English product name, or French text quoting Arabic: handled by proper BiDi run analysis, round-trips correctly to DOCX (`w:bidi`, `w:rtl` runs) and back.
- **Rendering (DOCX→PDF):** shaping via the platform text stack (HarfBuzz under the hood), correct ligatures and contextual forms, Arabic-script line breaking, per-language hyphenation for Latin scripts (de/fr/es hyphenation patterns matter for justified text).
- **Fonts:** Noto subsets (Naskh Arabic, Sans, Serif) delivered via Play Asset Delivery; font-fallback chain per script; embedded (subset) fonts in generated PDFs so output renders identically everywhere.
- **OCR coverage:**
  - **ML Kit Text Recognition v2** (on-device, fast): Latin scripts (EN/FR/ES/DE/…), Chinese, Japanese, Korean, Devanagari.
  - **Tesseract (Tesseract4Android, Apache-2.0)** for Arabic, Farsi, Urdu, Hebrew and 100+ more — trained-data packs downloaded on demand (keeps base APK small); ship a fine-tuned Arabic pack (public `ara.traineddata` is mediocre; fine-tuning on printed Naskh/modern fonts is a cheap, real quality win).
  - **Auto language detection:** script detection on glyph histograms → candidate OCR engines → ML Kit Language ID on the extracted text to confirm; per-region, enabling Polyglot OCR (§3.4.2).
- **Golden corpus per language** (§8): real-world Arabic, French, English, Spanish, German documents — including the nasty ones (justified Arabic newspaper columns, German compound-word tables, French accented small caps).

---

## 5. Architecture

### 5.1 Stack

- **Kotlin**, Jetpack **Compose** + Material 3 (dynamic color / Material You, themed icon, predictive back).
- **Clean architecture, multi-module Gradle:**

```
:app                      — shell, navigation, DI graph
:core:design              — theme, components, RTL-safe primitives
:core:files               — SAF access, MIME detection, streaming IO
:core:i18n                — locale, BiDi helpers, numeral formatting
:engine:pdf-read          — PDF parsing, text/structure extraction
:engine:pdf-write         — PDF generation (tagged), font embedding
:engine:ooxml             — lightweight DOCX reader/writer (custom)
:engine:layout            — document model + layout-analysis heuristics/ML
:engine:ocr               — ML Kit + Tesseract behind one interface
:feature:convert          — pick → configure → progress → result
:feature:review           — fidelity heatmap, block fixes, Table Lasso
:feature:scan             — camera capture pipeline
:feature:gdocs            — Google auth + Drive sync
:feature:automation       — Magic Folders, widget, tile
:feature:history          — Time Machine, linked originals
```

- **Coroutines + Flow**; conversions run via **WorkManager** with a user-initiated foreground service (Android 14/15 FGS rules: `dataSync` for Drive transfers, `mediaProcessing`-style handling for long local jobs; expedited work for small files).
- **Room** for job history/Time Machine metadata, **DataStore** for prefs, **Hilt** for DI.
- **Storage:** SAF only (`ACTION_OPEN_DOCUMENT`, tree URIs for Magic Folders, Photo Picker for images). No `MANAGE_EXTERNAL_STORAGE` — keeps us Play-policy-clean.
- **Isolation:** the engine modules run in a **separate process with no INTERNET permission** (PDF parsers are a classic attack surface; untrusted-file parsing stays sandboxed). This is both real security and the marketing claim in §3.4.3.

### 5.2 Library choices (licensing checked — this is where competitors get trapped)

| Need | Choice | Why |
|---|---|---|
| PDF rendering (preview, heatmap underlay) | **PdfiumAndroid** (Pdfium, BSD) or platform `PdfRenderer` | Chrome's battle-tested renderer; permissive license |
| PDF parsing/extraction | **PdfBox-Android** (Apache-2.0) + our own extraction layer on top | Structure access, permissive; we own the BiDi/ordering logic |
| PDF writing | Android `PdfDocument` + custom tagged-PDF layer, or PdfBox-Android | Permissive, small |
| DOCX read/write | **Custom `:engine:ooxml`** (DOCX is a ZIP of XML) | Apache POI/docx4j are desktop-heavy (10–20 MB, slow on Android). A purpose-built OOXML kit covering the WordprocessingML subset we need is ~weeks of work, tiny, fast, and a durable competitive asset |
| DOCX→PDF layout | v1: DOCX → HTML/CSS → **WebView `PrintDocumentAdapter`** → PDF (Blink gives world-class shaping/BiDi for free). v2: native layout engine for pixel-perfect control | Fastest path to high-fidelity multilingual output |
| OCR | ML Kit TR v2 + **Tesseract4Android** (Apache-2.0) | §4.2 |
| Layout ML (phase 2) | TFLite region-classifier (text/table/figure/heading) | Downloadable model, optional |
| **Avoid** | iText 5+/7, MuPDF | **AGPL** — contaminates a closed-source app; several competitor apps quietly violate this. We audit every dependency's license in CI |

### 5.3 The PDF→Word engine (the crown jewel)

PDF has no paragraphs — only positioned glyphs. The pipeline:

1. **Fast path:** if the PDF is *tagged* (has a structure tree — many exported-from-Word PDFs do), read headings/paragraphs/tables/reading-order directly from the tags. Competitors almost universally ignore this free structure. Instant, near-perfect conversions when available.
2. **Untagged path (heuristics):** glyph→word→line clustering; whitespace/XY-cut column detection; paragraph merging by leading/indent; heading detection from font-size/weight statistics; list detection (bullet glyphs + hanging indent); table detection (ruling lines + whitespace lattice alignment); repeated-across-pages header/footer removal; **BiDi-aware reading order** (RTL documents read right column first — Latin-centric competitors get whole page order wrong).
3. **Confidence scoring:** every emitted block carries a confidence value from its detection heuristics → drives the Fidelity heatmap directly. The transparency feature falls out of the architecture for free.
4. **Images/vectors:** extracted and re-embedded; anything unconvertible (charts, complex vector art) is rasterized into the DOCX rather than dropped — *never silently lose content*.
5. **Scanned pages:** detected (no text layer) → routed through the OCR pipeline with layout reconstruction from OCR bounding boxes.
6. **ML upgrade (phase 2):** TFLite layout model refines region classification; user corrections in Review Mode become (opt-in, on-device) signals for heuristic tuning.

### 5.4 Google Docs integration

- **Auth:** Credential Manager sign-in; scope **`drive.file` only** (files the user explicitly opens/creates through Morpho) — privacy-honest and avoids Google's restricted-scope review. Start OAuth app verification in month 1; it has lead time.
- **To Google Doc:** upload DOCX via Drive `files.create` with target `mimeType application/vnd.google-apps.document` (Drive performs the import) → deep-link into the Docs app.
- **From Google Doc:** Drive picker → `files.export` as DOCX/PDF (note the ~10 MB export cap; chunk or warn) → continue offline.
- **Round-trip:** Doc → DOCX → (edit/convert offline) → push back as a new revision of the same Drive file.
- Clear UI boundary: everything is offline except this feature, and it says so.

---

## 6. UX principles

- **Three taps to done:** open → pick file → convert. Config (format, OCR language, quality) on one optional sheet with smart defaults (auto-detected language pre-selected).
- **Progress you can trust:** per-page progress with live thumbnail preview, cancellable, survives app death (WorkManager), completion notification with Share action.
- **Review Mode is optional** — power users get the heatmap; casual users share straight from the done screen.
- Empty states teach the signature features (drop-zone widget, Magic Folder, airplane-mode badge).
- **Accessibility:** full TalkBack passes (in RTL too), 200% font-scale layouts, generated PDFs tagged for screen readers.
- Foldable/tablet: two-pane (list + preview), drag-and-drop in from Files/Drive, DeX-friendly.

## 7. Privacy, security, trust

- Documents never leave the device (except explicit Google Docs actions). No document-content telemetry, ever. Crash reporting scrubbed of file names/paths.
- Engine process: no network permission, sandboxed, fuzzed in CI (parser fuzzing on the PDF/OOXML/RTF readers).
- Everything user-facing in a plain-language privacy page; the "no INTERNET permission on the engine process" claim is verifiable via `adb`/manifest and we publicly invite the check.
- Optional app lock (biometric) for the history screen.

## 8. Quality strategy

- **Golden corpus:** 500+ real documents across EN/AR/FR/ES/DE (+ CJK later): contracts, CVs, invoices, academic papers, newspaper layouts, scans of all qualities. Sourced + anonymized, licensed properly.
- **Automated fidelity scoring** in CI on every engine change: text similarity (normalized Levenshtein on logical-order text), structure similarity (heading/table/list trees), and **visual diff** (render output back to PDF, SSIM against original pages). Regressions block merge.
- Per-language fidelity dashboards — "Arabic fidelity ≥ Latin fidelity − 3 points" is an explicit release gate, so RTL quality can't silently rot.
- Device matrix via Firebase Test Lab / Play pre-launch reports; performance budgets (p50 < 1.5 s/page text PDFs, < 6 s/page OCR on a mid-range 2023 device; memory-streamed page-by-page processing for 500-page files).
- Crash-free sessions ≥ 99.8% release gate.

## 9. Monetization — fair, and itself a differentiator

- **Free forever:** unlimited PDF↔DOCX↔Google Doc single conversions, OCR in 5 launch languages, scan-to-Word, no watermark, no ads, no account.
- **Morpho Pro** (subscription *and* a one-time **Lifetime** purchase — subscription fatigue is real and competitors all refuse to offer lifetime): batch conversion, Magic Folders, Table Lasso to Excel, all 100+ OCR languages, Time Machine re-conversion history beyond 30 days, priority engine features (PPTX/XLSX).
- The free tier is deliberately generous: the growth engine is Play Store reviews from users burned by watermark/upload apps, and MENA/francophone word-of-mouth where Arabic quality is unmatched.

## 10. Roadmap

| Milestone | Weeks | Deliverable |
|---|---|---|
| **M0 — Foundations** | 1–2 | Repo, CI (lint, tests, license audit, screenshot RTL tests), module skeleton, design system, SAF file flows |
| **M1 — Core engine** | 3–8 | PDF→DOCX for text PDFs (tagged fast path + heuristics, Latin scripts), history, share/open-with integration; internal alpha |
| **M2 — RTL + Word→PDF** | 9–12 | Arabic/BiDi extraction end-to-end, DOCX→PDF via print pipeline with shaping/fonts, per-app language, AR/FR/ES/DE localization |
| **M3 — OCR & scanning** | 13–16 | ML Kit + Tesseract behind one API, language packs via asset delivery, scan-to-Word, Polyglot OCR v1 |
| **M4 — Google Docs + Review Mode** | 17–20 | Two-way Drive sync, fidelity heatmap + block fixes, Table Lasso v1 |
| **M5 — Automation & polish** | 21–24 | Batch, Magic Folders, widget/tile, Time Machine, closed beta in 6 locales, perf hardening |
| **v1.0 launch** | ~26 | Play launch: EN/AR/FR/ES/DE, staged rollout |
| **v1.x / v2** | post-launch | ODT/EPUB/RTF/.doc, PDF-tables→XLSX, PPTX/XLSX, TFLite layout model, form-field mapping (AcroForm→Word forms) |

**Team:** 2 Android engineers + 1 engine-focused engineer (+ contract designer & translators) ≈ 6 months to v1.0. Solo-dev variant: same order, ~11–12 months, cut M5 scope into v1.1.

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| PDF→Word fidelity expectations (perfection is impossible on untagged PDFs) | Tagged fast path; heatmap sets honest expectations; Review Mode converts failures into 30-second fixes; never silently drop content |
| Arabic OCR quality (stock Tesseract is mediocre) | Fine-tuned traineddata; corpus-driven eval; per-region OCR reduces error surface |
| Google OAuth verification delay | `drive.file` scope only; file for verification in month 1; feature-flag Google Docs so launch never blocks on it |
| AGPL contamination via transitive deps | License audit in CI (fails the build), documented allowlist |
| Play foreground-service / storage policy shifts | SAF-only design; WorkManager-first; FGS types reviewed each targetSdk bump |
| Big-file memory pressure | Streaming page-by-page architecture from day one; 500-page test in CI |
| Competitors copy the heatmap | The moat is the corpus + per-language engine quality + trust brand, which compound with time |

## 12. Success metrics

- Conversion success rate ≥ 95% (completes without error), per-language fidelity scores trending up every release.
- Play rating ≥ 4.6 with review keywords "Arabic", "offline", "no watermark" appearing organically.
- D30 retention ≥ 25% (converters are utility apps; Magic Folders and widget drive habit).
- ≥ 40% of installs from MENA + francophone Africa within 6 months (the underserved wedge), then expand.
- Crash-free ≥ 99.8%; p50 conversion < 1.5 s/page.

---

### TL;DR

Morpho wins by refusing the three compromises every competitor makes: it converts **on-device** (private, instant, verifiable), it treats **Arabic and every other language as first-class** in both UI and engine (BiDi, shaping, per-region OCR — the wedge market nobody serves), and it's **honest** about quality (fidelity heatmap + fix-it Review Mode instead of a black box). Kotlin + Compose, permissively-licensed engine stack with a custom lightweight OOXML core, WebView print pipeline for world-class multilingual Word→PDF at v1, Drive-API-native Google Docs round-tripping, and a corpus-driven quality gate per language. Six months to a launch that no current Android converter can match on privacy, languages, or trust.
