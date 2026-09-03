# Morpho — release checklist

Everything here needs a human: a Play account, a signing key, and a phone.
The code side is done and verified in CI on every push.

## 1. Before anything else: test on a real device

Nothing in this app has ever run on physical hardware. CI proves it compiles,
lints, passes 519 engine tests and survives R8 — it cannot prove that OCR
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
- [ ] **The footnote on the paper's first page** must sit at the foot of
      the page in Word, under Word's own separator, with the star as its
      mark — not in the middle of the abstract, and not with two rules
      above it. Check an untagged PDF the same way: a page with no tags
      sets the mark on a line of its own, and the note must still be a
      note.
- [ ] **A section that starts on a fresh page** — the paper's list of
      references — must start on a fresh page in Word too, and no ordinary
      page turn may have become a forced break.
- [ ] **A table longer than a page**, exported to PDF: a syllabus, a
      price list, or a CV whose history sits in one long cell. It must
      carry on over the page — every row present, and a cell longer than
      the page continuing at the top of the next one — rather than
      stopping at the bottom edge with the rest of it gone. Use one with
      a picture in a cell — a letterhead's logo, a CV's photo — and check
      the picture is in the exported PDF too.
- [ ] **A document with a table** — one with rules and one that is only
      aligned columns. The columns must keep their widths (a column of
      dates stays narrow), and the second must arrive with no lines
      around it. A table with a heading over two columns must keep it
      over two columns, not split into two headings.
- [ ] **The author's email on the paper's first page** must be clickable
      in Word and open a mail window, and no ordinary word may have turned
      into a link.
- [ ] **A document with coloured text** — a report with red headings or
      blue links — converted both ways. Each run must keep the colour the
      page shows, and an ordinary black document must gain no colour it
      never had.
- [ ] **The lists of the Arabic paper** (pages 51–54). Every item's marker —
      the round bullet, the dash of the second level, the author's own
      "أ-" and "3-" — must sit at the right-hand end of its line, as a
      real character and not a blank box, and each item must carry one
      marker, not two.
- [ ] **The head and foot of the Arabic paper**, in Word and in the app's
      preview. Every page should carry the journal's running head — its
      title, the author, the two rules — at the same distance from the top
      as the PDF, and the foot with the volume line and a page number that
      counts 48, 49, 50 as the paper does, not 1, 2, 3. The first line of
      each page should start where the PDF's does, and each page should end
      on the same line; if the phone's pages run long or short, the line
      pitch is not being honoured.
- [ ] **A two-column paper without tags** — a journal article from an
      older tool. Each column must be read to its foot before the next one
      starts, and a heading that runs across both must sit between the
      bands, not inside one.
- [ ] **An Arabic document with a table in it**, opened in real Word. The
      first column must be the rightmost one, as it is in the original —
      a mirrored table reads as a different table, not a wrong-looking one.
- [ ] **An Arabic document whose clauses are lettered أ ب ت.** They must
      come back lettered, in Word and in the exported PDF, rather than
      renumbered 1 2 3.
- [ ] **A document whose lists are nested** — numbered clauses with lettered
      sub-clauses, or bullets under bullets. Each level must keep its own
      marker and sit in from the one above, in the preview, in the exported
      PDF and in Word.
- [ ] **A paper with equations in it.** Nothing can hold an equation as
      an equation, but every formula must still be there, written out in
      the line it stood in, rather than gone.
- [ ] **A document with a contents page** — a thesis or a manual whose
      first page lists its chapters, written in Word itself. Every line of
      that page must still jump to its chapter after the conversion, in
      Word and in the app's preview, rather than trying to open a website.
      Check a cross-reference too — a "see section 4" in the body.
- [ ] **A Word document with footnotes and endnotes**, written in Word
      itself rather than by Morpho. Every note must be in the converted file
      under a mark that is numbered in reading order.
- [ ] **A Word document laid out in text boxes** — a CV or a certificate
      from a template. Every box's text must be in the converted file, once
      each, in the order the boxes are anchored.
- [ ] **A PDF with a contents page** — a manual, a book, a thesis whose
      first pages list the chapters with page numbers. In the converted
      file every one of those lines must jump to its chapter, in Word and
      in the app's preview. Check a paragraph whose second line carries
      an emphasised or coloured word too: it must keep it, rather than
      taking the look of the line above.
- [ ] **A PDF with bookmarks but no tags** — a manual or a book, where the
      sidebar lists the chapters. Every chapter it lists must come back as a
      heading, at the depth the sidebar gives it.
- [ ] **A filled-in PDF form** — an official form with typed answers. Every
      answer must be in the converted file, beside the label it answers.
- [ ] **A PDF somebody highlighted** — mark a few lines in any PDF reader,
      then convert it. The marked words must come back marked, in the
      colour they were marked in, and the words beside them must not.
- [ ] **A password-protected PDF.** The app must ask for the password rather
      than refuse the file; a wrong one must say so and let it be typed
      again; Cancel must leave the document picked and unconverted. Check a
      scanned locked PDF too — OCR reads it with the same password.
- [ ] **A scanned PDF → OCR → Word.** Watch the page counter advance; try
      Cancel mid-way and confirm nothing is saved. Judge the recognition
      quality — this is the number one thing to tune (see §5).
- [ ] **Word → PDF**, both ways: "Convert to PDF" (direct file) and "Print…".
      Compare an Arabic document across both renderers.
- [ ] **A Word document written in styles**, which is what a real one is: a
      thesis or a report whose headings, body and quotations come from the
      style pane rather than from formatting each paragraph by hand. The PDF
      must keep the faces, sizes and colours the styles set, its tables must keep
      the grid Word draws around them and the colour of their header rows,
      and its headings
      must still read as headings — including from a Word in another language,
      whose heading style is called Titre1 or Título1.
- [ ] **Review Mode**: convert something imperfect, correct a block's kind,
      save the corrected file, confirm the change is in the output.
- [ ] **Share sheet and Open-with** from Files, Gmail, WhatsApp.
- [ ] **Rotate the screen** during a conversion and while the save dialog is
      open. (Process death during the save dialog is a known, documented gap.)
- [ ] **Each of the five languages**, with Arabic checked for full RTL layout.
- [ ] **Converting part of a document.** "Convert only some pages…" on a
      picked PDF, and again after one is refused as too large: a range like
      5-20 must give exactly those pages, and an empty box the whole file.
      Try it with OCR too — it should read only the pages asked for. On a
      PDF exported from Word, the pages asked for must come out as well as
      they do when the whole file is converted — same headings, same
      notes, same running head — not as though they had been scanned.
- [ ] A **large document** (100+ pages) for memory behaviour. On a desktop
      JVM, 220 pages of the Arabic paper convert in about six seconds and
      need roughly 240 MB; the app asks for a large heap for this reason.
      A document too big for the device must say so — it must never come
      back short. Watch for missing pages at the end, which is what a
      swallowed out-of-memory used to look like.

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
- **PDF export layout.** `PdfFileExporter` documents what it still
  simplifies (a cell covering several rows is drawn in the first of them,
  and a table inside a cell is skipped); real documents will say which
  matter.

## 6. Staged rollout

- [ ] Closed test with a handful of people, including at least one Arabic
      reader converting real documents.
- [ ] Fix what they hit.
- [ ] Production at 20%, then widen once the crash rate holds.
