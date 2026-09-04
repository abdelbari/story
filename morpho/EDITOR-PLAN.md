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
2. **Device spike — its Blink half done 4 September.** The page is
   written (`HtmlWriter.writeEditor`), its script with it
   (`layout/src/main/resources/.../editor.js`), and the two were driven in
   headless Chromium — Blink, which is what Android's WebView is —
   against the real engine over a loopback socket (`EditorPageTest`, run
   where `node` and Playwright are to hand, skipped in CI). Nineteen
   actions: typing Latin and Arabic into an RTL paragraph, Return at the
   end and in the middle, Backspace joining upward and on an empty
   paragraph, a selection across two paragraphs made bold with Ctrl+B,
   undo and redo, a click on a table, a caret the browser placed itself,
   typing over a selection, a table put in, a paragraph made a list item
   (the whole-body repaint), Return continuing the list, and two hundred
   keystrokes. After every one the page's text and caret agree with the
   engine's document and selection, exactly. What the phone still has to
   answer, and only it can: **an input method composing** — the script
   lets a composition finish and then sends what it composed as typing,
   which repaints the block over what the browser had put there, but
   Playwright types without composing, so that path is untested until a
   phone's keyboard runs it; touch selection handles; the keyboard's
   showing and hiding; and the true round trip through the app's bridge,
   which is a call — the 49 ms the harness measures is its own socket
   and the browser's IPC. If the true figure is over a frame, the script
   batches operations while a paragraph is being typed into, which
   changes the timing and not the design.

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

Engine work (JVM, tested): done — the algebra, the history, the block
marks, the single-block and body renders, and the wire format with its
JSON, all of it fuzzed. See Stage 0.

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

- **Done 4 September, in the engine and the page:** a caret stands in a
  cell's paragraph (`Caret.cell`, five numbers over the bridge), and
  inside a cell every edit is the edit it is outside one — Return makes
  a second paragraph of the cell, Backspace at the head of a cell's
  first paragraph does nothing, nothing crosses a cell's edge. Rows and
  columns go in and come out of a table with no merged cell, the last
  of either taking the table with it; a column put in takes the width
  of the one beside it. Every cell is given a paragraph to stand in on
  opening. The page finds a cell by the browser's own `rowIndex` and
  `cellIndex`, which are the indices the model stores cells by, spans
  and all. Driven in Chromium: a click into a cell, typing, Return,
  rows, columns, undo, and a selection dragged out of a cell.
- Still to do: a selection across cells (Word selects whole cells);
  splitting and merging cells, which the model carries as spans and
  the row and column operations refuse for now; moving between cells
  by Tab.
- Insert an image, resize it, give it alt text — the model has alt text
  already, and it is the accessibility feature competitors mostly skip.
  The bytes are the app's to supply, so the operation is the app's to
  add to the grammar.
- Keep a table's ruling and column widths through an edit, since the
  reading works hard to recover them: held now for rows and columns.

## Stage 4 — what makes it feel finished

- Find and replace, including across a document of hundreds of blocks.
- A real formatting surface rather than a toolbar of everything: a bottom
  sheet on a phone, a toolbar on a tablet.
- Hardware keyboard shortcuts.
- **Autosave and restore across process death — the engine half done 4
  September.** Already a known limitation — a conversion in memory is
  lost if the process dies with the save dialog open — and an editor
  makes it unacceptable rather than merely annoying. `DocumentJson`
  writes a document as text exactly, every field of every block,
  pictures' bytes included, and reads it back as the same value — held
  over a thousand random documents — refusing anything that is not a
  document with one exception and never another. `EditorState.saved()`
  and `restored()` carry a session over it: the document as opened and
  as it stands, where each block came from so `modified` still means
  what it meant, and the caret; not the history, which a reader would
  least miss. What the app still owes: calling `saved()` on every edit
  or at least on `onStop`, writing it to its own files directory, and
  offering `restored()` on the next launch — a few lines, on a device.
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

Everything of Stage 0 and Stage 1 that a machine can do is done, the
page and its script included; what is left needs a device:

1. The JavaScript posture decision (Price one above), and the page on a
   real phone: an input method composing Arabic, touch selection, the
   keyboard showing and hiding.
2. The bridge — an object with `send(json): String` and `status(json)`
   given to the page as `Morpho`, which is all the script asks for — and
   the timing question above.

What the device spike lands on is all there. Every block of the body
carries `data-block="N"` on its outermost element; `HtmlWriter.writeBlock`
renders one block alone and `writeBody` the body alone; and
`EditorProtocol` is the bridge's whole grammar — `operation(json)` reads
what the script sends as if a hostile page wrote it, `step(state, json)`
applies it, and the reply is a splice of re-rendered blocks with where
to put them (or the whole body where a list or a sheet is involved), the
selection, whether there is anything to undo, and the look and paragraph
style at the caret for the toolbar. The script's job on the device is
exactly three things: turn `beforeinput` and toolbar presses into those
operations, apply a splice to the DOM and renumber the marks, and put the
caret where the reply says. Nothing it holds is the document.

Then hold at the gate and decide between routes A and B on what the two
spikes actually measured, rather than on what this document expects them
to.
