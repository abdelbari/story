# Making the editor an actual editor

A plan, written 4 September 2026, to be started Saturday.

The ask: the part of the app that edits a converted document should work
the way Google Docs and Word work, not the way it works now.

## What is there now, exactly

Review Mode (`android/app/.../ReviewScreen.kt`, 460 lines) is a **list of
blocks**. Each row shows one block, its confidence band, where its content
came from, and a set of buttons: correct the words in a text field,
relabel the block's kind, remove it, restore it, join it upward, separate
it at its line breaks. The list is filtered by default to the blocks worth
checking.

Under it, and this is the part worth keeping, is a real edit model in the
engine:

- `DocumentEdit` — an immutable value holding the document plus four sets
  of marks (`corrected`, `removed`, `joins`, `splitBlocks`), with every
  edit a pure function from one value to the next, and `asWritten` giving
  the document that gets written. Blocks never move; a removal is applied
  only at write time. Held by ten tests and a fuzz over two thousand
  random edit sequences.
- `ParagraphEdit.retext` — lays typing *over* the existing runs instead of
  flattening them, keeping the longest shared head and tail and rewriting
  only what changed, in the look of the run it was typed into. This is
  already the hard half of "type into a formatted paragraph".
- `ParagraphEdit.join` / `.split`, `TableGrid`, `LineBreaks`.

So the *model* of editing is sound and tested. What is missing is that a
reader cannot see the document while editing it, cannot put a caret
anywhere, cannot select across two paragraphs, and cannot apply
formatting.

## The honest size of this

This is not a feature. It is the largest single subsystem in the app —
bigger than the whole untagged PDF reading, which is the biggest thing
built so far. A rich text editor is one of the genuinely hard pieces of
application software; Word and Docs are decades of it. The plan below is
staged so that each stage ships something a reader can use, and so that
stopping after any stage leaves the app better than it is now rather than
half-rebuilt. Nothing here promises Word parity, and the stages are
ordered by what a reader notices first.

## The decision that governs everything: what draws the editing surface

Three candidates. The choice must be made in Stage 0, by experiment, not
by argument.

**A. Compose-native.** One `BasicTextField` per block, laid out down a
page. Native, no WebView, matches the existing stack, and the security
posture is unchanged.
The wall: Compose's text field holds *one* styled string. Selection cannot
cross two fields, so "select from the middle of this paragraph to the
middle of the next and make it bold" — table stakes for an editor — has no
answer without writing selection from scratch. Tables and inline images in
flow are worse. This is close to what the app already does, which is why
what the app does now looks like a form rather than a document.

**B. WebView with `contenteditable`.** Render the document as HTML, let
the browser be the editor, read the edits back into the model.
The browser already solves caret placement, cross-block selection, IME,
selection handles, and — decisively for this app — **BiDi and Arabic
shaping at browser quality**. This is how most mobile rich text editors
actually ship.
The costs are real and are listed below.

**C. Own text engine on a Canvas.** `StaticLayout` shapes text but does
not edit it. Caret geometry, IME, selection handles, and Arabic cursor
movement would all be written by hand. Out of scope; recorded so it is not
revisited.

**Leaning B**, on the strength of the BiDi argument alone: this converter
exists for Arabic documents, and a hand-built caret that gets Arabic
cursor movement wrong is worse than no editor. But B has two prices that
have to be paid explicitly.

### Price one: JavaScript in a WebView

The preview WebView today sets `allowFileAccess = false`,
`allowContentAccess = false`, and never enables JavaScript — it defaults
off. `contenteditable` needs JavaScript, and reading the edits back needs
a bridge.

What makes this affordable is that **the app has no `INTERNET`
permission**, so script in that WebView cannot reach the network at all;
this is the same guarantee the CI Zero-Upload guard enforces, and it is
not being weakened. What remains is local, and is handled by construction:

- The editing surface is a **separate WebView** from the preview. The
  preview keeps its current hardened settings untouched.
- `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`
  and `allowUniversalAccessFromFileURLs` stay false on both.
- Document content is **injected as JSON and inserted through the DOM**,
  never interpolated into an HTML string. A converted document is
  attacker-controlled content; this is the same reasoning that already
  made the app refuse to write out an address it cannot vouch for.
- Exactly one `@JavascriptInterface` object, with a small number of
  narrowly typed methods, each validating its input as if it came from a
  hostile document — because it may have.
- A CI guard, beside the Zero-Upload one, asserting the settings above and
  that no second bridge has appeared.

This is a posture change and should be confirmed before Stage 1 starts,
the way the `INTERNET` question was.

### Price two: there is no HTML reader

`HtmlWriter` exists. There is no `HtmlReader` — the engine's readers are
`DocxReader` and `StructureTreeReader`, and the writers are Docx, Markdown
and Html. Route B needs the way back: HTML → `DocumentModel`, faithful
enough that a document can go out and come back unchanged.

This is the real risk of the whole project, and it is not the caret. It is
also the most testable thing in it — pure JVM, so it runs in the 1253-test
suite on every push rather than only in CI's six-minute Android build.

## Stage 0 — decide, by measurement, before building

Two spikes and a decision. Nothing ships.

1. **Round-trip spike.** Write `HtmlReader` far enough to carry paragraphs
   with mixed runs, headings, lists, tables and images. Fuzz
   model → HTML → model over the same generated documents the round-trip
   fuzz already uses, and require equality of the model, not of the bytes.
2. **Device spike.** A throwaway `contenteditable` page in a WebView, on a
   real phone, driven by hand: type Arabic with an English phrase inside
   it, put the caret in the middle of a bold word, select across two
   paragraphs, use the IME, rotate the device. This is the question Compose
   cannot answer on this machine and is the reason the device pass matters.

**Gate to Stage 1:** a paragraph of mixed Arabic and English, bolded
across a word boundary, survives model → HTML → edit → model → `.docx`
byte-identically to the same paragraph edited through `ParagraphEdit`
directly. If it does not, take route A and accept a lesser editor rather
than an unfaithful one.

Also in Stage 0, and cheap: evaluate whether an existing Compose rich-text
editor library covers route A's inline styling well enough to be worth it
as a fallback. Build-time dependencies cost no runtime permission.

## Stage 1 — the document becomes editable in place

The stage that changes what the app *is*. Review Mode's list is replaced
by the document itself, laid out, with a caret in it.

- The document renders as flowing pages, as the preview already draws
  them, but editable.
- Type anywhere. Insert, delete, split a paragraph with Return, merge with
  Backspace at a boundary.
- Select, including across blocks.
- Character formatting on a selection: bold, italic, underline,
  strikethrough, colour, size, typeface — every one of which the model
  already carries on `TextRun` and every writer already emits.
- **Undo and redo**, over the whole history. `DocumentEdit` is already
  immutable, so history is a list of values and undo is an index. This
  belongs in the engine with tests, not in the view model.
- The existing operations keep working, expressed as edits rather than as
  buttons on a row: remove becomes deleting a selection, join becomes
  deleting the boundary between two paragraphs, separate becomes pressing
  Return.

Engine work (JVM, tested): `HtmlReader`; an edit history; a selection and
formatting algebra over runs — "apply bold from block 3 offset 12 to block
5 offset 4" — which is the piece `ParagraphEdit` does not yet have,
because it only ever edited one paragraph.

Android work: the editing WebView, the bridge, a formatting toolbar, and
the wiring in `ConvertViewModel` (1016 lines already; this is where it will
want splitting).

## Stage 2 — paragraph and structure

- Paragraph styles: the heading levels, body, and whatever the document
  arrived with, applied from a menu that shows what the document actually
  uses.
- Alignment, indents, line spacing, space before and after.
- Lists: bullet and numbered, nesting, and the numbering formats the model
  already carries (including `arabicAlpha`, which matters here).
- Insert and delete blocks; page breaks; links.

Most of this is model → HTML → model plumbing once Stage 1 stands, because
`ParagraphStyle` already holds nearly all of it.

## Stage 3 — tables and images

- Type in a cell; move between cells; add and remove rows and columns.
- Merged cells, which the model already carries as spans.
- Insert an image, resize it, give it alt text — the model has alt text
  already, and it is the accessibility feature competitors mostly skip.
- Keep a table's ruling and column widths through an edit, since the
  reading works hard to recover them.

## Stage 4 — what makes it feel finished

- Find and replace, including across a document of hundreds of blocks.
- A real formatting surface rather than a toolbar of everything: a bottom
  sheet on a phone, a toolbar on a tablet.
- Hardware keyboard shortcuts.
- **Autosave and restore across process death.** Already a known
  limitation — a conversion in memory is lost if the process dies with the
  save dialog open — and an editor makes it unacceptable rather than
  merely annoying. `DocumentEdit` is a value, so this is serialisation
  plus a place to put it, and it should be done in this stage at the
  latest.
- Review Mode's confidence marks return as a layer over the editor — a
  band in the margin, a filter that jumps between doubtful blocks —
  rather than a separate screen. The Fidelity Report was the reason to
  open this screen; it should not be the casualty of making it an editor.

## Rules that hold across every stage

1. **The engine model stays the truth.** `DocumentModel` and
   `DocumentEdit` are the document; HTML is a rendering of it and a
   transport for edits, never the source. The moment the DOM becomes the
   truth, every writer, every test and the whole Fidelity Report are
   downstream of a browser.
2. **Anything testable goes in the engine.** Android sources compile only
   in CI, six minutes a cycle; the JVM suite runs here in seconds. The
   round trip, the selection algebra, the history, and the formatting
   operations are all pure and all belong there.
3. **Fuzz the round trip**, as this project already fuzzes the others.
   Property: whatever a reader does, the document afterwards is the
   document plus exactly that edit, with no run lost and no formatting
   invented.
4. **Arabic is the acceptance case, not an afterthought.** Every stage's
   gate is checked on a mixed Arabic-and-English paragraph with formatting
   crossing a script boundary, because that is the document this app
   exists for and the one every editor gets wrong.
5. **No new permission.** Nothing in this plan needs one, and nothing in
   it may quietly acquire one.

## What to do first on Saturday

Stage 0, in this order, because the second is the one that can only be
answered on a device:

1. `HtmlReader` plus the round-trip fuzz — a day's work in the engine,
   entirely testable here.
2. The `contenteditable` device spike, and the JavaScript posture
   decision that goes with it.

Then hold at the gate and decide between routes A and B on what the two
spikes actually measured, rather than on what this document expects them
to.
