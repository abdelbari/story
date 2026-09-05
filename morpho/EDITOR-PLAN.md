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

## Where it stands, 5 September

Everything of Stages 0 to 4 is done, in the engine, in the page, and —
since the evening of 5 September — in the app: the screen exists
(`EditorScreen`), the bridge exists (`EditorBridge`), the formatting
tools are a toolbar over the page, autosave takes every edit, the
doubtful jump is a button, and a picture goes in from the device's own
bytes. The JavaScript posture (Price one below) is taken: the owner asked
for the edit screen to be a real editor, which a page that can be typed
in cannot be without script, and it was taken exactly as priced — see
the decisions log in the README for the whole of it. What is left needs
a phone:

- **On a phone:** an input method composing Arabic, touch selection
  handles, the keyboard showing and hiding, and the true round trip
  through the bridge. The page's part of each was driven in headless
  Chromium; the phone's part of each has not been seen, because neither
  CI nor the machine this was built on has a phone.
- **The toolbar by eye:** the page has been photographed and looks right;
  the Compose toolbar over it has only been compiled, in CI.

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
  that no second bridge has appeared. **Its first form is in place since 5
  September**: the guard refuses any WebView in the app that runs script,
  carries a bridge, or reads files — which is the posture today — and
  when the decision is taken it is the guard that changes, to allow
  exactly one file, rather than the guard that is skipped. The page's
  own side is held by `EditorPageTest`: three calls on the bridge and
  nothing else.

This is a posture change and should be confirmed before Stage 1 starts,
the way the `INTERNET` question was.

**Taken, 5 September.** The owner asked for the edit screen to be an
editor like Word's and Docs' — a document typed in, with the type tools
over it — which no page can be without script, so the ask is the
decision. It is taken exactly as priced above and not a step wider: the
editor's WebView is its own and the preview's is untouched; every
file-access setting is false in both; DOM storage, new windows, address
following and every resource fetch are refused in the editor's, behind
a page whose own policy allows no source; the one bridge object is
`EditorBridge`, with `send`, `status` and `tapped` and nothing else, each
reading its input as if a hostile document wrote it; and the guard
changed rather than skipped — it allows script and one bridge in exactly
`EditorScreen.kt`, requires the refusals to be written there, and still
refuses file access in every WebView in the app. If the decision is ever
to be reversed, the guard is where to start: refuse the one file again,
and the build says what depends on it.

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
An `HtmlReader` was still worth having — for pasting rich text in from
another app — and it exists since 5 September, for that alone: the
clipboard's `text/html` goes over the bridge with the plain text, is
read as blocks, and is pasted with Word's rule for what joins what. It
is a reader of pasted text, not of documents, and nothing of the DOM is
ever read back through it.

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
  them, but editable. **The page's look is done, 5 September**: a sheet
  on a ground with the document's own margins on a tablet, the screen
  as the sheet on a phone with tables reflowed, the ground dark in the
  dark and the sheet paper, and the selection, caret, picked picture,
  note and doubt marks drawn as an editor draws them — photographed in
  Chromium by `spike/editor-look.mjs` from `EditorLookTest`'s pages.
  What is left of the look is the app's chrome round it.
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
- **Done 5 September, in the engine and the page:** notes (`comment`,
  `uncomment`), shown as marked words with the number drawn by the
  page's style and not written as text, every reply naming the notes at
  the caret; the page set (`setPage`) and the document described
  (`describeDocument`). The sheets for all three are the app's.

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
- **Done 4 September, in the engine and the page:** a selection from
  one cell to another is of whole cells — the rectangle between them,
  grown to hold whole any merged cell it cuts through, as Word selects
  cells — and Backspace empties every one, typing empties them and
  writes in the first, formatting and styling reach every one, and
  deleting a row or a column takes every row or column selected. Tab
  moves to the next cell with the whole of it selected, from the last
  cell to a new row, and back with Shift; outside a table it moves an
  item of a list a level in or out, or types a tab. Cells selected are
  merged into one, holding their paragraphs, and a merged cell is split
  into the cells it covered. Over the bridge the status names the cells
  selected and whether they can be merged or the caret's cell split, so
  a toolbar's buttons are the engine's decision. Driven in Chromium: a
  drag across cells, Backspace over them, Tab and Shift+Tab, a merge, a
  split, and a table grown by Tab.
- **Done 4 September:** rows and columns go into a table with merged
  cells and come out of it. `TableEdits` lays the table out on its grid,
  puts the row or column into the grid or takes it out — a cell that
  crosses the place grows or shrinks by it, one lying wholly in what is
  taken out goes with it, a row left with nothing goes too — and reads
  the rows back off the grid; a row or a column put in and taken out
  again gives back the table it was, held over six hundred random
  tables with random merges. The Word writer's stray cell under a cell
  merged both ways went with it.
- The editor's markup keeps every tab a tab stop places, out of sight,
  so a caret in a form's `Name:<tab>value` line is counted right; the
  preview's markup is as it was.
- **Resize an image and give it alt text — done 5 September** in the
  engine and the page (`describeImage`, `resizeImage`; a tap on a
  picture is told to the app as `Morpho.tapped`, since a caret cannot
  stand in one, and the app owns the sheet). Inserting one is still the
  app's: the bytes are the app's to supply, and `insertBlock` takes an
  `ImageBlock` in Kotlin without a base64 round trip over the bridge.
  Links at the caret (`link`) and a word count (`count`) came with it.
- Keep a table's ruling and column widths through an edit, since the
  reading works hard to recover them: held now for rows and columns.

## Stage 4 — what makes it feel finished

- **Find and replace — done 4 September.** `EditorState.find` lists
  every place a word is written, in the document's paragraphs and in
  every cell of every table, as selections; `replaceAll` writes them all
  as something else in one step to undo, each replacement set the way
  the first character it replaces was set. Over the bridge `find`
  answers with its matches and paints nothing; `replaceAll` comes back
  as the blocks it changed. The page's `morphoEditor.find` and
  `replaceAll` are what a search sheet calls; the sheet itself is the
  app's.
- A real formatting surface rather than a toolbar of everything: a bottom
  sheet on a phone, a toolbar on a tablet.
- Hardware keyboard shortcuts — **done 5 September**: Ctrl+B/I/U/Z/Y,
  Tab, Ctrl+X and Ctrl+V (rich), Ctrl+E/L/R/J, Ctrl+Shift+8/7,
  Ctrl+Alt+0–3, Ctrl+Shift+./, and raised/lowered, every one a mapping
  in the page to an operation the engine has. The toolbar's toggles read
  how the whole selection is set (`lookOf`, Word's all-or-nothing rule),
  not the character left of the caret.
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
- **Review Mode's confidence marks return as a layer over the editor —
  the engine and page half done 4 September.** The editor's markup
  carries the report's band on every doubtful block (`data-band`), the
  page draws it as a band in the margin (amber, red; green once the
  block is changed, every reply naming the blocks changed), and
  `doubtful()` / `nextDoubtful()` are the filter that jumps between
  them. What the app still owes: a button for the jump, and the
  report's summary somewhere on the screen. The Fidelity Report was the
  reason to open this screen; it should not be the casualty of making
  it an editor.

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

Everything of every stage is done, the screen included (see *Where it
stands* at the top); what is left needs a phone:

1. Install the CI build and open a converted document in the editor.
   Type Arabic with the phone's own input method — letters must join and
   compose as they do in Word — then select by touch, drag a handle, and
   watch the toolbar follow the caret. The keyboard showing must lift the
   page rather than cover it (`adjustResize` is set for exactly this).
2. The round trip: bold a word, put a table in, put a picture in from the
   gallery, leave the editor and save. Open the file in Word. Kill the app
   mid-edit and open it again: the edit must be there.

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

On route A's fallback, the cheap Stage 0 question — whether a Compose
rich-text library covers inline styling well enough: as far as is known
without a device, the libraries in that space (MohamedRejeb's
`compose-rich-editor`, Halilibo's `richeditor-compose`) hold the whole
document as one annotated string in one field, which gives selection
across paragraphs for free and bold, italic, underline, lists and links
over it — but none holds a table, and a form of merged cells is this
app's commonest document. So route A's fallback is a lesser editor for
prose and no editor for the documents the app is for. To be verified on
Saturday with the libraries' current releases before it is relied on.

## The bridge, exactly

What the app gives the page, and what crosses it. Held by
`EditorPageTest`: the script asks for exactly these three and reaches
for nothing else.

- `Morpho.send(json: String): String` — one operation in, its reply
  out, on the same call. The reply is what `EditorProtocol.step`
  returns: `EditorProtocol.opening(state)` gives the page its first
  body, and after that `step(state, json)` for each call, the state kept
  by the app between calls.
- `Morpho.status(json: String)` — after every reply, the state at the
  caret for the toolbar: `look` (bold, italic, underline, strikethrough,
  superscript, subscript, fontFamily, fontSizePt, colorRgb, highlightRgb,
  link), `paragraph` (kind, alignment, direction, listMarker,
  listLevel), `canUndo`, `canRedo`, `modified` (a count), `cells` (the
  cells selected together, as `[row, column]`), `canMerge`, `canSplit`,
  `table` (ruled, headRow, shadingRgb, columnWidthPt; null outside one),
  `comments` (id, text, author; the notes at the caret).
- `Morpho.tapped(json: String)` — a picture tapped: `{kind: "image",
  block, alt}`, for the app's sheet.

Every operation is `{"op": name, ...}`; anything else, or any field of
the wrong kind or past its bound, is refused with `{"error":"refused"}`
and the document exactly as it was. A caret is `[block, offset]` or, in
a cell, `[block, offset, row, column, paragraph]`; offsets are UTF-16.

| op | fields |
|---|---|
| `select` | `anchor`, `focus` (carets) |
| `type` | `text` (≤ 200 000 chars) |
| `paste` | `text`, optional `html` (≤ 2 000 000 chars; read as blocks where it reads as any) |
| `erase`, `eraseForward`, `split`, `undo`, `redo`, `tab` (`back`?) | — |
| `format` | any of `bold`, `italic`, `underline`, `strikethrough`, `superscript`, `subscript` (booleans); `fontFamily`, `fontSizePt`, `colorRgb`, `highlightRgb`, `link`, `language` (present-and-null clears) |
| `restyle` | any of `kind`, `alignment`, `direction`, `listMarker`, `listLevel` (0–8), `listFormat`, the indents and spacings in points, `pageBreakBefore` |
| `insertTable` | `rows`, `columns` (1–64) |
| `insertRow` (`below`?), `deleteRow`, `insertColumn` (`after`?), `deleteColumn`, `mergeCells`, `splitCell` | — |
| `shadeCells` | `rgb` or null |
| `ruleTable` | `ruled` |
| `headRow` | `header` |
| `setColumnWidth` | `widthPt` |
| `removeBlock` | `block` |
| `describeImage` | `block`, `description` or null |
| `resizeImage` | `block`, `widthPt`?, `heightPt`? |
| `link` | `url` or null, `text`? |
| `comment` | `text`, `author`? |
| `uncomment` | `id` |
| `find` | `query`, `ignoreCase`? — answers `matches`, paints nothing |
| `replaceAll` | `query`, `replacement`, `ignoreCase`? |
| `doubtful` | — answers `blocks`, paints nothing |
| `count` | — answers `count` (words, characters, charactersWithoutSpaces, paragraphs), paints nothing |
| `setPage` | `widthPt`, `heightPt`, `marginTopPt`, `marginBottomPt`, `marginLeftPt`, `marginRightPt` |
| `describeDocument` | any of `title`, `author`, `subject`, `keywords` (present-and-null clears) |

A reply carries the status above, plus either `all: true` with `body`
(the whole body, where a list, a sheet or a note is involved) or
`splice: {from, to, blocks}` (the blocks from `from` up to `to`
replaced by `blocks`, rendered), `selection`, and `changed` (the blocks
the reader has touched). The page's own API on top of all this is
`window.morphoEditor`, which is what a toolbar calls and a test reads.
