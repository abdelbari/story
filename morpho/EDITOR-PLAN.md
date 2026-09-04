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

### Price two, as first written: there is no HTML reader

`HtmlWriter` exists. There is no `HtmlReader`. The first draft of this plan
made writing one the centre of Stage 0, so that the browser's edited DOM
could be read back into the model.

**Superseded on 4 September, before a line of it was written.** Reading a
`contenteditable` DOM back is the trap every serious editor climbed out
of: each browser mutates the DOM differently under the same keystroke —
a `<b>` here, a `<span style>` there, a `<div>` for a new line, a
non-breaking space for a trailing one — and a reader faithful to all of
them is a reader that has learned the browsers, not the document.
ProseMirror, Slate and Lexical all converged on the same answer, and it
is the one rule one of this plan already demanded: **the DOM is never the
truth.**

So the transport is *operations*, not markup. The engine renders each
block as HTML with a stable id and character offsets the script can
address; the script intercepts `beforeinput` and turns each keystroke,
selection and toolbar press into a typed operation — *type this text at
block 12 offset 34*, *make bold from here to there*, *split at the
caret* — and hands it over the bridge; the engine applies it to the
document, and hands back the blocks it changed, re-rendered, and where
the caret now is. The script is a thin translator with no model of its
own. Nothing in it is truth, and everything that decides what an edit
does runs in the JVM suite in seconds.

That engine half is what Stage 0 needed and it is built: see Stage 0.
An `HtmlReader` is still worth having — for pasting rich text in from
another app — and that is Stage 4's, not Stage 0's.

## Stage 0 — decide, by measurement, before building

Two spikes and a decision. Nothing ships.

1. **The edit algebra — done 4 September.** `EditorState` in the engine
   (`layout/.../EditorState.kt`): a caret and a selection in UTF-16
   offsets, which is what Kotlin and JavaScript both count in; typing
   that takes the look of the character to its left, or a look chosen
   with nothing selected; Backspace and Delete, including joining a
   paragraph to its neighbour and taking out the picture or table beside
   it; Return, with a word processor's rules for what the second half is;
   character formatting over a selection that crosses paragraphs, with a
   `Put` that tells setting a property to nothing apart from leaving it
   alone; paragraph styling; a block put in at the caret; undo and redo
   over the whole history, with a run of typing or erasing one step. A
   document is never left without a paragraph to stand in, and a
   surrogate pair is never split. Twenty-three targeted tests and a fuzz
   over two thousand random sessions with an independent oracle: the
   words are checked against a second, runs-blind statement of every
   edit, and undoing every step gives back the document opened, exactly.
   `DocumentEdit` is untouched — it is the review screen's, kept by
   position; the editor is opened on what it produces and keeps
   `modified` by origin instead.
2. **Device spike.** A throwaway `contenteditable` page in a WebView, on a
   real phone, driven by hand: type Arabic with an English phrase inside
   it, put the caret in the middle of a bold word, select across two
   paragraphs, use the IME, rotate the device. This is the question Compose
   cannot answer on this machine and is the reason the device pass matters.
   Then the same page wired to `EditorState` through the bridge, sending
   operations and painting what comes back — and the question the spike
   is really asking: whether a bridge round trip on every keystroke is
   fast enough to feel like typing. In-process, it should be well under a
   frame; if it is not, the script keeps the paragraph being typed into
   and sends the operations in batches, which changes the timing and not
   the design.

**Gate to Stage 1:** a paragraph of mixed Arabic and English, bolded
across a word boundary, edited on the device through the bridge, comes
out of `EditorState` identical to the same edit made through it directly
in a test — and writes to `.docx` the same bytes. If the caret and
selection cannot be kept honest across the bridge, take route A and
accept a lesser editor rather than an unfaithful one.

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

Engine work (JVM, tested): the algebra and the history exist; what
remains is the rendering side of the transport — `HtmlWriter` emitting
each top-level block with a stable id, so an operation can name one, and
a way to render one block on its own for the bridge to hand back — and
the operations as a wire format the script can send, which is a small
flat grammar rather than a document format.

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

Stage 0's engine half is done. What is left of Stage 0 needs a device:

1. The `contenteditable` device spike, and the JavaScript posture
   decision that goes with it.
2. The bridge, wired to `EditorState`, and the timing question above.

Before either, the two engine pieces Stage 1 needs and a machine can do:
block ids in `HtmlWriter`, and the operations' wire format with its own
fuzz, so that what the device spike sends has something to land on.

Then hold at the gate and decide between routes A and B on what the two
spikes actually measured, rather than on what this document expects them
to.
