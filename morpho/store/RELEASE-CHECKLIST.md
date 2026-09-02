# Morpho — release checklist

Everything here needs a human: a Play account, a signing key, and a phone.
The code side is done and verified in CI on every push.

## 1. Before anything else: test on a real device

Nothing in this app has ever run on physical hardware. CI proves it compiles,
lints, passes 256 engine tests and survives R8 — it cannot prove that OCR
reads an Arabic receipt correctly or that a printed PDF looks right.

```
git clone <repo> && cd morpho/android
./gradlew :app:installDebug        # with a device attached
```

Never run an Android project from a clone before? `morpho/RUNNING.md` walks
through it from installing Android Studio to the app on your phone, including
the folder-to-open step that catches everyone.

Work through, in this order — each exercises a path nothing else covers:

- [ ] **A born-digital PDF → Word.** Headings, lists and tables should survive.
- [ ] **A PDF exported from Word** (a tagged PDF, the fast path). Reading order
      must be right even if the layout is complex.
- [ ] **An Arabic document → Word**, opened in real Word. Letters must stay
      joined, words must not reverse, and Latin words or digits inside Arabic
      paragraphs must sit on the correct side. Try **both a tagged and an
      untagged PDF** — the two take different paths and each reversed text
      in its own way before 1.0; a PDF from Word is tagged, one from an
      older tool or a scanner usually is not.
- [ ] **An Arabic document whose headings were typed by hand** (bold or
      larger text, not Word's heading styles). Headings should still come
      out as headings. A bold line that is not a heading — a byline, say —
      may be promoted, which is what Review Mode is for.
- [ ] **The preview shows pages.** After converting the Arabic paper, the
      preview should look like the PDF: the same page shape and margins, the
      title centred, the bold labels and raised marks in place, page after
      page as you scroll. Rotate the phone; the pages should redraw at the
      new width. The bold words ملخص and الكلمات must read so on the phone,
      not ممخص and الكممات — the repair of the font map runs on the phone's
      own PDF library, which is not the desktop one.
- [ ] **The look of the Arabic paper**, converted and opened in Word beside the
      PDF. The bold label at the head of the abstract, the raised author
      mark, first-line indents, the three dates spread on tab stops, the
      spacing between paragraphs, the rules under the dates and above the
      footnote, and the page's own margins should all
      match the page; nothing should be split or joined that the page does
      not split or join. Check an untagged PDF the same way: it should also
      keep its faces and weights, drop the running header and footer, and
      break its paragraphs where the page does.
- [ ] **A scanned PDF → OCR → Word.** Watch the page counter advance; try
      Cancel mid-way and confirm nothing is saved. Judge the recognition
      quality — this is the number one thing to tune (see §5).
- [ ] **Word → PDF**, both ways: "Convert to PDF" (direct file) and "Print…".
      Compare an Arabic document across both renderers.
- [ ] **Review Mode**: convert something imperfect, correct a block's kind,
      save the corrected file, confirm the change is in the output.
- [ ] **Share sheet and Open-with** from Files, Gmail, WhatsApp.
- [ ] **Rotate the screen** during a conversion and while the save dialog is
      open. (Process death during the save dialog is a known, documented gap.)
- [ ] **Each of the five languages**, with Arabic checked for full RTL layout.
- [ ] A **large document** (100+ pages) for memory behaviour.

Report anything wrong and it can be fixed from the description.

## 2. Signing key

Create it once and never lose it — Play ties the app's identity to it forever.

```
keytool -genkeypair -v -keystore morpho-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias morpho
```

Back the file up somewhere durable and offline. Put the credentials in
`~/.gradle/gradle.properties` (never in the repository):

```
MORPHO_KEYSTORE=/absolute/path/morpho-release.jks
MORPHO_KEYSTORE_PASSWORD=…
MORPHO_KEY_ALIAS=morpho
MORPHO_KEY_PASSWORD=…
```

Enrolling in Play App Signing is recommended: Google keeps the app signing key
and you keep only the upload key, so a lost key is recoverable.

## 3. Build the bundle

```
cd morpho/android && ./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Install that exact build once before uploading — a release build differs from
debug in ways only R8 can cause:

```
./gradlew :app:installRelease   # or bundletool for the .aab
```

- [ ] The release build launches, converts a document, and runs OCR. If OCR
      fails only in release, a ProGuard keep rule is missing —
      `app/proguard-rules.pro` explains what is kept and why.

## 4. Play Console

- [ ] Create the app (Productivity, free).
- [ ] Copy the listing text from `listing-en.md` and the four translations.
- [ ] Host `privacy-policy.md` at a public URL (GitHub Pages is free and
      enough) and paste the link. **Add your contact email to the policy
      first** — there is a placeholder in it.
- [ ] Data safety form: answer from `data-safety.md`.
- [ ] Content rating questionnaire (this app rates as "Everyone").
- [ ] Screenshots: at least 2, phone size. Take them on the device — home
      screen, a finished conversion, Review Mode showing a flagged block, and
      the Arabic interface. The Arabic one is the differentiator; make it one
      of the first two.
- [ ] Feature graphic (1024×500) and app icon (512×512).
- [ ] Target audience, ads declaration (none), app access (no login needed).
- [ ] Upload the .aab to a **closed test track first**, not production.

## 5. After the first real use

The two things most likely to need tuning, both of which need real documents
to judge:

- **OCR quality.** If recognition is poor, the levers in order of impact are:
  the render resolution (`RENDER_DPI`, currently 200), switching from
  `tessdata_fast` to the slower and more accurate `tessdata_best` models, and
  Tesseract page-segmentation mode.
- **PDF export layout.** `PdfFileExporter` documents its v1 simplifications
  (uniform table columns, no row splitting across pages); real documents will
  say which matter.

## 6. Staged rollout

- [ ] Closed test with a handful of people, including at least one Arabic
      reader converting real documents.
- [ ] Fix what they hit.
- [ ] Production at 20%, then widen once the crash rate holds.
