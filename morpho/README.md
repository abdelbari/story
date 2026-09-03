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

- **Engine (968 tests):** document model with per-block confidence (the Fidelity Report seed); first-strong BiDi detection plus **full UAX #9 run analysis** (every reader splits mixed-direction paragraphs at direction boundaries, so writers mark direction per run — `w:rtl` per piece in Word, dir spans in HTML — instead of mislabeling a whole mixed run by its first strong character); text/Markdown import with inline `**bold**`/`*italic*`/`~~struck through~~` styling; **text a document struck through stays struck through** — a price that changed, a clause that was dropped, a name that was wrong: bold and italic always survived a conversion and this did not, and it is the one of the three that changes what a document means; it is written as Word's own strike, read back from a single or a double one, drawn through the words in the preview and in the exported PDF, and written and read again in Markdown, where a tilde that means nothing stays the tilde it is; **the emphasis a PDF only draws** — a PDF holds no italic, no bold beyond a font's name, no underline and no strike, so a producer fakes each of them: the type is skewed to lean, the letters are stroked round to thicken, and a hair of a rule is drawn under the words or through them. All four are read now, from the matrix a glyph was drawn with, the weight its font declares, and where a rule sits against the baseline — which is what recovers every italic word of an Arabic document, since no Arabic typeface Word ships has an italic cut; **what a document says about itself** — its title, author, subject and keywords, carried both ways, so a converted file is not anonymous and unnamed; **what a picture shows**, from a tagged figure's own description or from the words of a running head that had to be photographed, written as Word's alternative text and the preview's `alt`; a from-scratch OOXML **writer** (styles, per-list restarting numbering, tables, `w:bidi`/`w:rtl`, run languages) and matching **reader** (.docx → model, **found where the package says it is** — OPC names the main part by a relationship rather than by a path, and Word writes `word/document2.xml` after it has repaired a file, a document Word itself opens without a word that a reader knowing only the conventional path calls "not a .docx"; everything a document is made of is read beside its main part rather than under a fixed `word/` — its styles, numbering, notes, running header and footer, pictures and its own relationships — while a package that says nothing still means what a .docx means; numbering resolved through numbering.xml, the running header and footer with their pictures, tab stops and PAGE field read back from their own parts, tolerant of unknown content, **and reading what a document holds however it wrote it** — the wrappers Word puts round runs and paragraphs (a tracked insertion or move, a content control, a template's custom XML, a direction override) rather than walking past them, a field written the long way round as the field it is rather than the stale answer it last worked out, and a picture drawn the old way as the picture it is — while what a document *used* to say, the text deleted or moved away with changes tracked, stays out; **and the styles the document keeps its formatting in** — nearly every real Word file says how it looks once in `styles.xml` and then names a style on each paragraph, so a reader that reads only what a paragraph writes on itself hands back an unstyled document: the chain is resolved from the document's own defaults through however many styles are based on one another, a run style sits between the paragraph and the run, direct formatting beats all of them, and a heading is known by its style's id, failing that by the built-in name Word gives that style in every language — a Spanish “Título1” is named “heading 1” all the same — and failing that by the level it sits at in the outline; and a table's rules come from its style the same way — the Table Grid every Word user reaches for draws a full grid and writes not one border on the table itself, so a reader that looks only at the table hands back a table with no lines, while a table that turns its style's rules off has none and a table ruled a cell at a time is ruled all the same); **a numbered heading is a heading** — a report, a thesis and a standard number their chapters by a list their heading styles belong to, so the paragraph is a heading and an item of a list at once: told only that it was an item, the Word writer named it Word's List Paragraph and the preview made it a plain item, and every numbered chapter of every such document came back as body text with the document's outline gone; Word is now told both, as Word itself writes one, and the preview holds the heading inside the item; **a list inside a list stays inside it** — a document's lists are nested more often than not, a report's clauses with sub-clauses under them, and a converter that keeps one level hands them all back as one flat list: Word writes the depth as the level of its numbering and what each level counts with is the numbering's business, so the reader takes both (a clause lettered (a) is as numbered as one numbered 1., which the reader used to drop as prose for not being decimal), the writer writes the level back with a ladder to match — 1. then a) then i., and a bullet that changes with the depth — **and a list keeps the way it counts**, which for an Arabic document is often أ ب ت rather than 1 2 3: the way each level counts is read from the numbering (overrides included, which is where Word usually puts it), written back the same way so Word letters the list as it was lettered, and drawn on the page in those letters, in the alphabet's own order or the older abjad one, since a page has no numbering to count with; the tag tree's nested lists are read as lists rather than as more words of the item they sit in, an indented Markdown list is read by what it is indented past rather than by any fixed number of spaces, and HTML nests its own lists while the page draws the marker itself, since a page has no numbering to draw from; **the text a document keeps in boxes and controls is read as text** — a poster, a CV, a certificate, a form is laid out in text boxes, and a text box is written inside the run it is anchored to rather than in the body, so a reader that walks the body alone converts one to a blank page; Word writes each box twice over, as it draws one now and as a Word of 2007 would, and only the one Word itself would use is read, so nothing is said twice; and what a content control holds — a template's cover page, a table of contents, the fields of a form — is wrapped rather than replaced, and is read as the document it is; **a Markdown table is read as a table** — the writer has always written one, and nothing read it back, so a document's tables came out of Markdown as paragraphs full of pipe characters and the app could not read its own output: a head, the row of dashes under it and the rows themselves become a table, the dashes saying how each column is set, the head marked as one so a long table repeats it, an escaped pipe staying a character of its cell, and lines of pipes with no row of dashes left as the text they are; a **Markdown writer** (model → .md, with a note where its mark stood and the note itself at the end, in the syntax every Markdown that knows the idea uses — a writer that walks only the text drops the words of a paper's notes outright) **and a reader that hears what it says** — a link and a note's mark were written in Markdown's own syntax and nothing read either back, so a document converted to Markdown and then to Word arrived with the characters that spell its links showing in the middle of its sentences and the words of its notes as stray lines after the last paragraph: the app offers a document as Markdown and reads Markdown as a document, so its own output is one of its inputs, and every corpus document is now written as Markdown and read back to prove it. What is not a link stays what it is — a citation in square brackets, a cross-reference, an escaped bracket, a picture whose bytes nothing can put back — a mark whose note nobody defined refers to nothing and stays the characters it is made of, the line that would have defined a note nobody refers to stays a line of the document, a document's own brackets are written escaped so that "see [note 3]" is not read back as a link, and a paragraph that merely begins `#`, `- ` or `1.` — "1. Introduction" left over from a list a page drew, "#3 in the series" — says it is not the heading or the list item it is shaped like, rather than coming back a word short. **And the writer was made to say things its reader could hear**: Word splits a sentence into runs wherever it likes, so a struck-through phrase it split in the middle wrote `~~a~~~~b~~`, whose four tildes are four tildes on the page — each stretch that is struck through, and each of one weight and slope, is now written once, however many runs it is made of; a link is written around all the runs that carry it, so the emphasis inside one survives; a note's words are written the way the document's are, escaped and with their emphasis, having been written raw; and two notes can no longer answer to one label, which used to show both marks the first note's words and lose the second outright. What Markdown still cannot keep is two markers meeting with nothing between them — `**a***b*` is a run of asterisks that opens nothing, here or by CommonMark's own rules — where the words all survive and the markers show as characters; **an equation is written out rather than dropped** — Word writes one in a language of its own, in the paragraph beside the runs rather than inside one, so a reader that walks Word's own elements walks past it and a paper's formulas simply vanish; nothing this converter writes can hold an equation as an equation, so it is written the way it reads: a fraction as (a+b)/2, a power as x^2, a subscript as x_i, a root as √(x), and every symbol as the character it already is, in the line it stood in; **the page break somebody typed breaks the page** — Ctrl+Enter, which is how most page breaks in most documents are made, writes a break into a run rather than a property on a paragraph, so the paragraph that carries it is a paragraph with no words in it: it was dropped for being empty and the break went with it, and a document that laid its chapters out one to a page came back running them together; **a Word document's own notes come back as notes** — Word draws a note's number itself, so the run that refers to one writes nothing at all, and a reader that keeps only what is written loses the mark and the note it called: the mark is made here instead, counted as Word counts them (footnotes 1, 2, 3 and endnotes i, ii, iii, the two counted apart because Word counts them apart, so note 2 may be two different notes), and a thesis's endnotes, whose part the reader never even opened, are read as the notes they are; **the notes a page sets at its foot come back as footnotes** — a PDF has no notes, only a rule across the bottom of a page, small text under it and a raised mark somewhere above, so the mark is what joins them, in either of the two shapes a page sets them in: the mark at the head of the note's own line, and the mark on a line to itself with the words under it, which is what a page with no tags reads as, since nothing shares that line for the mark to be raised above. What keeps both honest is the far end — a mark is a note's mark only where the same mark is raised earlier in the document, so a page number under a rule stays a page number. The note moves onto the run that carries it, Word writes it into a part of its own with the mark the page printed rather than a number of Word's choosing, the reader brings it back, the preview gathers the notes under a rule at the end, and the phone's exporter keeps room at the foot of the page the mark lands on; **addresses a document writes out in full become links** — an author's email under a paper's title, a web address in a footnote — written as a real Word hyperlink with its target in the part's relationships, as an anchor in the HTML preview and as Markdown link syntax, with a rule tight enough that a file name, a ratio or an @mention is left as the text it is — and **a link the PDF declares as an annotation is read from the page itself**, joined to the words underneath it by geometry, which is the only way to learn where "click here" leads — and a link that leads back into the same PDF, which is every line of a book's or a manual's contents page and every cross-reference in it, is followed to the page it names and pointed at the first paragraph there, since nothing outside a PDF knows what "page 12" means and a converter that keeps only web addresses hands back a contents page that does nothing; **and the look of a paragraph's second line is its own** — a paragraph is drawn line by line and read back as one, and the runs measured off the page are walked in step with the joined text, which agrees with them character for character until the joiner puts a space between two lines or drops the hyphen of a word broken across them: a walk that gave up there lost its place for the rest of the paragraph and gave every line after the first the look of the line before, so an emphasised term on a second line came back plain, a heading that wrapped came back half black, and a link on any line but the first led wherever the first one led; **a contents page still leads somewhere** — the first thing in a thesis, a manual or a report is a contents page, and every line of it is a link into the document rather than out to the web: Word writes such a link as a name and marks the place it leads to with a bookmark of that name, so a converter that knows only web addresses turns every line of a contents page into a broken link to a website called "#_Toc1"; the names a document gives its places are kept, including the one Word writes around a whole run of paragraphs rather than inside any of them, both ends of a link are put through the same repair of what Word will accept as a name so they still meet, Word's own note of where the typist last was is not a place anyone links to, and the preview gives each named place an anchor of its own so a contents page works there too; **a form somebody filled in converts filled in** — a PDF form keeps its answers in its fields rather than on its pages, so the government form, the application, the registration all look filled in and extract blank: the answers are drawn onto the pages they were typed on before a word is read, in place, where the label beside them is, and a document that was filled in is read from its pages even where it carries a structure tree, since a tree written when the form was empty knows nothing of what was typed into it; **the marking a reader made on a PDF survives the conversion** — a highlight is an annotation, a colour and the quadrilaterals it covers, and the words underneath it know nothing about it, so the two are joined by geometry the way a link is: what a student marked in their reading comes back marked, written with Word's own highlighter where the colour is one of the sixteen it can name and as shading where it is not, drawn behind the words in the preview and in the exported PDF; **a PDF that asks for a password is asked for one** — the documents people most need converted are the ones that are locked (a bank statement, a payslip, an official record), and the answer a converter gives when it cannot be bothered is to send the reader off to strip the protection in some other app: the reader takes a password, tells a document that needs one from a password that was simply wrong, and opens a file locked against copying alone without asking at all; **PDF extraction with a tagged fast path** — structure, headings, lists, tables and logical reading order read straight from the tag tree when present (with a BDC named-properties fix PDFBox itself lacks), position-aware glyph-clustering heuristics with **column-alignment table detection** for untagged files, plain-text fallback, a shared line-reflow pass so a word hyphenated across a line break does not come back with a space in the middle of it, and a **painting-order reconstruction**: a PDF paints glyphs left to right in the order they land on the page, so right-to-left text arrives backwards, and UAX #9 run reordering puts it back, with presentation-form ligatures folded to the letters that were typed. Neither reader trusts the order glyphs were painted in — one Word-produced paper positions its short runs word by word right to left and paints its long paragraphs as a single left-to-right block, in the same document, so no rule about content order is right for both. Each run's glyphs are instead sorted by where they sit on the page and every line is reconstructed from that, in the tagged reader per marked-content run with the structure tree still deciding the order of runs, and in the untagged reader per line; every line is reconstructed against the document's own direction — its `/Lang`, else the direction most of its text runs in — because a line cannot tell its own, an Arabic line whose leftmost word is an email address starting, visually, with a Latin letter; and when a PDF's ToUnicode map is demonstrably corrupt — Word 2010 labels the digit 0 as 5 and the medial lam as meem in its Arabic subsets, so every العلمي came out العممي and every 2022 came out 2522 — **the embedded font's own cmap overrules it**, glyph by glyph, on fonts whose two maps name a glyph as different characters of the same script — two such glyphs are proof, since each is wrong in every word it appears in — and never on a healthy one, and only ever from the font's true Unicode cmap; a ligature glyph such as لا carries two letters already in logical order and is entered backwards so the line's reversal rights it rather than swapping them; a number with separators — a date, a page range — is fenced so it reads as one left-to-right unit wherever it sits, instead of 2022-04-21 coming back as 21-04-2022 after an Arabic word; and a glyph painted a kerning hair to the left of the one before it keeps its painting order, so الجزائر stays الجزائر; **and the look of the page comes across with the words**: every run keeps its face, size, weight and colour — a text engine is not given the colour operators unless it asks for them, so a heading a journal set in its own red read as black until both readers asked — so the bold label at the head of an abstract is bold alone and a raised footnote mark is a superscript; a paragraph keeps its first-line or hanging indent and its alignment, measured against the page's text block rather than the sheet; the spacing between paragraphs and the pitch of their lines are measured off the baselines; a line of dates Word spread with tabs keeps its tab stops; a rule drawn across the page — the line under a paper's dates, the separator above its footnote — goes to the paragraph it belongs to, while a running header's own does not; **an Arabic table is laid out from the right** — its first column is the rightmost one, and a converter that lays the same cells out from the left hands back a table read backwards, the years under the names and the names under the years, which is a different table rather than one that merely looks different: both readers work out which way a table runs from the text in it, order its cells and measure its columns that way round, Word is told on the table itself as Word says it, the preview turns the table round with them, and a table of English inside an Arabic paper is left running from the left where it belongs; **a table longer than a page is one table** — a statement of accounts, a schedule, a bibliography is painted as a table on every page it runs onto and the page says nothing to tie the parts together, so a twenty-page statement came back as twenty tables each starting again: they are joined where one ends at the foot of a page the page itself stopped, the next begins at the head of the following one with nothing between them, and their columns stand in the same places — and where the second begins by repeating the first's head, the repeat is dropped and the head is marked as one, so every writer sets it again at the top of each page the table runs onto, which is what the original page was doing by printing it twice; the head of a table is known as one — Word says which rows repeat at the top of every page a long table runs onto, that is written back and made a `<thead>` in the preview, where a browser repeats it for the same reason, and the pages the phone draws put it back at the top of each one the table runs onto, since the second page of a table is otherwise a grid of figures with nothing above it to say what they are; which rows are a head is one rule all three ask for rather than three readings of the same field — Word repeats only the leading rows a table marks and ignores the mark further down, a row in the middle of a table having nothing before it to sit above, and a table that is all head has none, there being no body under it to head; a table keeps the colour its cells are filled with, and the head of a table takes the colour its style gives it — Word writes the look of a head in the style and nothing at all on the cells, so a report's coloured header row is invisible to a reader that looks only at the cells — while a table that says it has no head is given none; a merged cell — a heading over two columns, a label beside three rows — keeps what it covers, through the tags that declare it, Word's own grid spans and vertical merges, and the preview; a table keeps the widths its columns had on the page — halved across the clear space between them, so a column of dates stays narrow and the prose beside it stays wide — and is ruled only where the page ruled it, since a table found by the alignment of its columns had no lines drawn around it at all; a page the producer broke to on purpose — a section starting fresh, a list of references — breaks again, while a page that merely filled up is left to fill up again, since whoever opens the file may not have the face it was set in and a forced break under a wider face leaves a nearly empty page behind every full one; a document is measured by the page most of it is written on rather than by whichever page comes first or last — a report of forty portrait pages with one landscape table in it is a portrait report, and a Word file says its last section's shape on the body itself, so reading that alone gave the whole report the shape of the table at the end of it; **and the one page it turns sideways is turned in Word too** — a document has one shape only in the sense that most of it does, and measured by that alone the wide page came back upright with every line of it set to the wrong width: where the sheet changes the reader starts a section, measured on that shape's own pages — including at the very start, since a document that opens on a cover or a wide table is measured at the shape most of its pages have and would otherwise have its first page written at a shape it never had, and the writer says a section's shape on the paragraph that ends it, as Word does, with a paragraph made to carry it where a section ends on a table; a document of one shape has one section and is written exactly as it always was; a page written portrait and turned a quarter turn to be read — a wide table, a plan — is measured as it is read rather than as it was written, since a page read landscape that comes back portrait sets every line to the wrong width; the page keeps its size and margins, its top margin where Word will put the first line's box rather than where the tallest glyph's ink starts, and every paragraph's line pitch is written as an exact height so a page breaks where the original did; **the running head and foot come across** — a producer marks them as pagination artifacts, and each is cropped from the rendered page at the size it had (the head of a journal paper is artwork: its title and its rules drawn as pictures) and repeated in Word's own header and footer at its distance from the edge — at the size it had, since a running head is set against the page rather than against the column of text and reaches into the margins as often as not: held to the column, as a picture in the body rightly is, a journal's head came back narrower than the page it heads, on every page of the document; and it takes no space or line spacing of Word's own, or the head is taller than the one it was cropped from and the text under it starts lower down the page — while a run of digits in the foot whose value advances by one from page to page is recognised as the page number, masked out of the picture and written as a PAGE field at a tab stop where it sat, with the document numbered from where the source started (48, for a paper that opens on page 48); a font whose ToUnicode is already known to be broken is also overruled where its two maps name different kinds of character — the colon that paper's map called a 4, so every ملخص: read ملخص4; **a symbol font's own codes are resolved to the characters they stand for** — the bullet Word draws before a list item arrives as U+F0B7, a private-use code that shows as a blank box and, counting as left-to-right, drags the whole Arabic item to the wrong side of the page, so Adobe's published tables for Symbol and ZapfDingbats name the glyph and the Adobe Glyph List gives the character (•, −), while a font whose codes are its own business is left alone; a private-use code point never decides a paragraph's direction; and a list item that carries the label the page drew for it keeps that label rather than being given a second marker, so a bullet, a dash of a second level and an author's own "أ-" all survive, at the indents they were measured at; **and the label is what says where one item ends and the next begins** — the items of a list are set closer together than the lines of a paragraph are, so a reader that breaks paragraphs on the gaps between lines reads a whole checklist as one block of prose with every item run into the next, which is what happened to every list in every PDF that carried no tags; the same reading of a label that keeps the tagged path from drawing a second marker now breaks the untagged path's paragraphs, one function asked by both; **a page set in columns is read column by column** — a PDF paints its lines down the page, not down each column, so a reader that takes them as they arrive reads across the gutter and hands back sentences nobody wrote; the gutter gives the page away, being a strip down the middle of the text that no mark crosses, and the lines that do cross it are full-width and cut the page into bands read one after the other, each column by column, right column first where the text runs that way. **A journal sets both its columns on the same grid**, so both are painted on the same baselines and every line of such a page reaches from the first column's margin to the second's: read line by line there is no clear strip to find at all, and the alignment of the two columns reads as a table of two, so a paper came back as a grid with half a sentence in every cell. The marks themselves say where the columns are — the gutter is the clear space the lines of the page agree on leaving, which is what the lines that run across it, a title or a running head, have nothing to say about, and each of those keeps its own gap of a word space and stays one line — and the page is cut apart where every character still knows where it was painted — but only where each side fills the measure it is set in, since a column of prose runs to its margin and the cells of a wide table of two do not, and cutting one of those apart would cost the document its table. **A page of three columns is a page of two, one of which is a page of two**, so each side is asked the same question again — where the marks are cut apart and again where the lines are put in the order they are read — up to four columns, since a page set in more than four is a table drawn without rules. Asked only once, a page of three gave up the second of its gutters alone and the two columns on the far side of it came back as a table of two with half a sentence in every cell, which is the very thing finding a gutter was for. And a heading set over some of a page's columns crosses the gutter between them and no other, so a gutter no line at all may cross is one such heading away from not being found: a few lines may cross one, and those are the headings, which cut the page into bands rather than belonging to a column; **a paragraph does not end because a page did** — every page of a book but the last ends in the middle of one, and breaking there gave a converted document a broken sentence at every page turn, hundreds of them in a book, each missing the space or the hyphen that joined its two halves and none of them recoverable afterwards, since by the time anyone opens the Word file they are two paragraphs like any other two; what can be asked across a page is what a line looks like and where it stops, not how far below the line before it it sits, so a line that ran to its margin carries on into the line under it on the next page where that line begins the way a paragraph's middle does — at the edge its block starts from rather than indented in from it, in the same face and weight, and not with the label of a list item — while a line that stops on a hyphen stopped in the middle of a word, and so in the middle of a paragraph, however short of its margin it stopped, so a word broken across a page turn comes back one word rather than two a page apart — and only over a page that filled up, judged against its own sheet rather than against the deepest text in the document, since a document may put text only at the top of every page and each is then as deep as the deepest while none of them is full; **and the untagged path reads the same things** — a PDF from a scanner or an older tool has no tags, and its lines now carry the face, size and weight of every run, keep the rules the page draws (a text engine is given no path operators unless it asks, so the line under a paper's dates and the separator above the note at its foot went unseen, and with them the notes themselves), take the running header and footer out of the text and put them where a document keeps them — what repeats in the margin of page after page is the page's own, and dropping it left a converted paper with no running head and nothing counting its pages, while the number among it that advances by one from page to page is written as a field so the pages go on numbering themselves from where the paper started; a head drawn as artwork is beyond a reader that only sees text, and is the one thing the tagged path still keeps that this one cannot — rebuild each line from the order its glyphs were painted — so a kerning step no longer swaps ز and ا and الجزائر keeps its letters, the fix the tagged reader has had since the reversal was solved — break paragraphs where a line stops short of a justified column or the weight changes, and measure alignment, indents, spacing and the page against the block the text occupies rather than the sheet; and a space Word's Arabic justification painted inside a word with nothing clear between the letters is not a word break, so خطوات stays one word; **a document that names its own chapters has them as headings** — an untagged PDF says nothing about which of its lines are headings and the reader has to tell from the type, which reads a paper well and a manual badly, since a manual's sections are often set in the body's own face at the body's own size; but a great many such documents carry an outline, the list of chapters a reader's sidebar shows, and an outline is the producer saying outright which lines are headings and how deep each sits, so a line an entry names, on the page that entry leads to, is a heading of that entry's depth — and is lifted out of the paragraph it would otherwise have been read as part of, which nothing about the type could have told; a contents page naming every chapter is not the chapters, since its lines carry the page numbers they point at, and a document with no outline is read exactly as before; **when a tagged file names no heading at all** — Word only tags one where the author used a heading style, so a paper whose headings were made by hand carries none — type size and bold recover the structure the tags never recorded; a picture tagged inside a paragraph — a logo in a heading, a formula in a line — follows the words it was tagged among instead of falling to the end of the document with the pictures nothing referenced at all; **a long document is converted or refused, never quietly emptied** — a reader guards each of its optional passes so that one failing costs a document its pictures rather than the reader its life, and running out of memory was being shrugged off the same way: a 220-page document read in a small heap came back 300 blocks short, whole and wrong, with nobody told; it is raised again now for the app to report honestly, the glyphs of a page are let go at the end of it rather than held to the end of the document, a picture drawn on every page is encoded once and stored once however many times it is drawn, and the app asks Android for a large heap, because holding a book in memory is what a converter does; **the untagged reader says which of its readings it is surest of** — everything read off a page without tags is a reconstruction, so flagging every block of it says nothing and hands a reader the whole document to check; a heading the document's own outline names is surer than one guessed from boldness, a paragraph whose lines all start from the same edge at a steady pitch is surer than one whose lines agree about nothing, and a table found by the alignment of its columns is the biggest guess of all and says so, while none of them ever claims to have been read from a structure the document does not have — so the report's most-doubtful-first list leads with what is actually worth looking at; **a part of a document is read the way the whole of it is** — asking for a few pages reads them as a document of their own, which is what lets the pictures, the outline and the page setup see the part as the whole it now is, but a document made that way carries no tags: a tagged file converted a chapter at a time was read the way a scan is read, its headings guessed from the type, its notes left in the middle of the text and its running head and foot lost. The tree names the pages it belongs to by the pages themselves, and the part holds those same pages, so it travels with them; a part now says what the same pages of the whole say; a **FidelityReport** generator (per-block confidence → bands *and provenance* — read exactly, read from PDF tags, reconstructed from positions, or recognized by OCR — plus a text-weighted overall score and a most-doubtful-first review list); and a gate that **the two PDF readers agree**: one page of Arabic, painted right to left the way a producer paints it, read once through its tags and once with the tags taken away, must come back word for word in the same order — the two paths are separate bodies of code that are meant to arrive at the same document; and a **FidelityScorer** (text + structure similarity) enforcing the multilingual corpus gate — 13 real documents (EN/FR/ES/DE/AR with tashkeel, Arabic headings, mixed Arabic-French, an Arabic guide whose lists nest three deep with a struck-through price in it, a report of two pipe tables, an Arabic journal issue with footnotes, a link and citations in square brackets, and two dense mixed-direction documents) must survive import → write → read-back with exact text and ≥ 0.95 structure similarity. Every corpus document is also written, read and written again, and the second writing must equal the third: anything that changed on every pass — an id renumbering itself, a property accreting, a run splitting again — would grow without bound in a document converted more than once and would never be noticed by a test that converts one only once. Adding a file to `ooxml/src/test/resources/corpus/` automatically extends the whole gate.
- **App:** four conversion paths, all fully on-device — text/Markdown → Word (.docx), Word (.docx) → Markdown, **PDF → Markdown** (the same reading written for a notebook, a repository or a static site, rather than made in Word and converted again), and **PDF → Word** (text PDFs via the position-aware layout heuristics; a PDF that will not open without a password asks for it in a dialog and converts it like any other, the password living in memory for that one document and typed again if it was wrong, since the file people most want converted is often the locked one; **a reader who wants one chapter converts one chapter** — a range of pages typed as 5-20, or ٥-٢٠ on an Arabic keyboard, is lifted out as a document of its own before a word is read, so the tags, the pictures and the outline all see the part as the whole it now is; it is offered on any PDF and again on a document that proved too large for the phone, because a document too large to convert whole is not a document that cannot be converted, and OCR reads only those pages too, which is minutes rather than an hour; scanned PDFs offer **on-device OCR** with live per-page progress and a cancel that stops cleanly between pages — Tesseract 5 with fast models for all five app languages bundled (ara/eng/fra/spa/deu, chosen by the app language with a second model riding along for mixed documents), pages rendered at 200 dpi, output scored 0.5 so the Fidelity Report calls it the guess it is), plus **text/Markdown/Word → PDF** two ways: direct-to-file (PdfFileExporter renders the document model with the platform text stack — StaticLayout + PdfDocument, so minikin does real Arabic shaping/BiDi with system fonts — on the document's own page and margins where a reader measured them, with each run set in the face, size and weight it carries, raised marks raised, and the indents and paragraph spacing the page showed, and saves a genuine .pdf through the same save dialog as the other formats) or the system print sheet (engine-generated print-ready HTML rendered by WebView, for paper printing too) — via SAF pick-and-save, the share sheet (send a document to Morpho from any app), and "Open with" on supported types. A finished conversion waits in hand until you save it, so dismissing the system save dialog costs a tap rather than a repeat of a three-minute OCR run — and the fidelity report can be read *before* the file is written. The **preview** that opens the moment a conversion finishes shows the document as pages — laid out on its own sheet by the same code that writes the app's PDFs, drawn by the platform's own text stack one page at a time as the reader scrolls, so what is compared against Word or the original is a page, not a web page in a box; the print path's HTML stands in if a document cannot be drawn. **Review Mode** turns the Fidelity Report into a screen: every block listed with a confidence band and, in words, where its content came from, filtered by default to the parts worth checking — and a block the reader mislabelled can be corrected there and the file written again, so conversion errors are fixed before the file is shared, not discovered after. An About screen carries the version, the privacy guarantee and the open-source attribution with the Apache License in full — a network-free app cannot link to a licence, so it ships one. UI localized in English, Arabic, French, Spanish, German with full RTL support, per-app language config, Material 3 dynamic color, and **no INTERNET permission in the manifest** — the Zero-Upload guarantee starts on day one.

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

- **The phone's PDF library is tested, not assumed:** the app reads PDFs with the tom-roush PDFBox-Android port, which is API-compatible with desktop PDFBox but not behaviour-compatible — a repair of a corrupt font map once counted its evidence one way on the desktop and another on the port, so a paper's bold words came out right on a laptop and wrong in the reader's hand. `engine/port-check` unpacks the port out of its Android archive, stubs the handful of platform classes it touches, compiles the app's own Android readers from where they ship, and runs them on the JVM, so that class of difference is caught by the build.
- **Custom OOXML writer/reader** instead of Apache POI/docx4j: 10–20 MB and desktop startup costs avoided; we grow exactly the WordprocessingML subset the engine speaks (plan §5.2).
- **PDF library strategy:** the layout heuristics (`PdfLine`/`PdfLayout`) live in `:engine:layout`, library-agnostic. The engine's `pdf-read` uses desktop PDFBox (Apache-2.0) for JVM development and tests; the app uses the API-compatible tom-roush `pdfbox-android` port in `android/pdf`, whose ~100-line position stripper deliberately mirrors the JVM one (kept in sync by hand until a shared-source split). The structure-tree fast path ships on both sides (StructureTreeReader and its Android twin).
- **DocxReader** skips empty spacer paragraphs and drops runs with no text — deliberate v0 choices documented in its KDoc.
- **MarkdownWriter losses are stated, not hidden:** Markdown has no underline, no direction markup, no run languages, no alignment outside a table's columns, and one top heading level, so a title comes back as a heading; RTL survives in the characters themselves. Reading Markdown back loses one thing more: a link may carry a title after its target, which is Markdown's tooltip and has nowhere in the model to go.
- **Images:** PNG/JPEG flow end to end — DOCX media parts with inline `w:drawing` (auto-scaled into the content area) on write, read back as `ImageBlock`s, and self-contained data-URI syntax in Markdown. Unsupported image types are still rejected loudly by the writer (never silently dropped); the reader skips exotic media (EMF/WMF) like other unknown content, with per-part and total inflation caps. PDF images are captured from the content stream (CTM-tracked `Do` operators, forms recursed, sub-8px decorations skipped, marked-content ids recorded) and flow through both PDF paths: on the tagged fast path, `Figure` structure elements resolve to their captured image by marked-content id — logical order preserved — with unreferenced images appended at the end; on the untagged path they interleave into the reconstruction by page position. Either way, PDFs with figures convert to Word files with the figures in place.
- **No Hilt yet** — one ViewModel doesn't justify it; it arrives with the multi-feature module split (plan §5.1).
- **Known limitations (tracked):** process death while the save dialog is open discards the in-memory conversion (the empty stub file is deleted); real state restoration arrives with the WorkManager pipeline. The reader locates the main part at the fixed OPC path `word/document.xml` rather than following the officeDocument relationship.
- **Minified release, with rules that explain themselves:** release builds run R8 and resource shrinking (the app carries ~10 MB of OCR models, so code size is worth reclaiming), and `proguard-rules.pro` keeps exactly the two dependencies R8 cannot see into — Tesseract4Android, reached by name from JNI, and PDFBox, whose font mapper and filter registry are reflective. CI assembles the release APK *and* the App Bundle on every push, because a missing keep rule is invisible in debug builds and fatal in shipped ones.
- **Zero network, permanently:** the app declares no `INTERNET` permission, and Google Docs sync is cut from the roadmap rather than parked — a converter that *cannot* upload your documents is a stronger promise than one that merely doesn't. CI enforces it: the Android job greps the merged manifest (which carries every dependency's permission requests) and fails on any network permission, so the guarantee cannot rot through a transitive dependency.
- **Confidence field** on every block from day one: tagged-PDF extraction scores 0.9, untagged 0.6, native formats 1.0 — the Fidelity heatmap needs no engine rework later. Within the untagged band the reader now varies by how sure it is — a heading the document's outline names sits above one guessed from boldness, a paragraph whose lines all agree above one whose lines do not, and a table found by column alignment below everything — because a report that flags every block of a reconstructed document flags nothing.
- **A filled-in form is read from its pages, not its tags:** a PDF form keeps its answers in its fields, so the pages are asked to draw them before anything is read, and a document that was filled in takes the untagged path even when it carries a structure tree — the tree was written when the form was empty and knows nothing of what was typed into it.
- **An equation is written out rather than kept:** nothing this converter writes can hold one, so a formula becomes the line it reads as ((a+b)/2, x^2, √(x)) in the place it stood. Losing the form is a stated cost; losing the formula is not.
- **A document is measured by the page most of it is written on:** Word says its last section's shape on the body itself and a PDF may open on a cover page of its own size, so taking either at face value gives a whole report the shape of one page. Sections of different shapes within one document are a known gap rather than a solved problem.
- **A section is what a change of sheet makes:** a paragraph may say the page changes with it, and only the shapes a document actually uses become sections — measured on their own pages, since a landscape page has landscape margins. A PDF whose appendix is turned sideways converts to a Word document whose appendix is turned sideways; a Word document's own sections are read back the same way, since Word says a section's properties on the last paragraph of it; and the phone's exporter, which draws both the preview's pages and the file the app writes, starts a fresh page of the new shape where a section does. The head and foot are measured against the page they are drawn on rather than the one the document opens on, so a head on a turned page runs its full width, while the pages go on being numbered from where the document started. The preview names a sheet for each shape the document turns to, so a browser printing it lays each part on the sheet that part was set on; on screen there are no sheets and a document of one shape is written exactly as it was.
- **A page is cut into its columns before it is read, not after:** the reader that finds columns works on whole lines, and a journal's two columns share their baselines — so every line spans both, no line fails to cross the gutter, and there is nothing for it to find. Nothing downstream can recover from that: the two columns' alignment reads as a table, and the paper comes back as a grid. The cut is made in the stripper instead, where every character still knows where it was painted, so both halves keep their faces, their weights and their colours. A page that is mostly one wide table of two columns looks the same from a distance, so each side must also fill the measure it is set in — prose runs to its margin, cells do not.
- **Copied, never rebuilt:** every reader hands its document to the direction pass and the link pass before anyone else sees it, and both walked into a table's cells to do their work by building a fresh row and a fresh cell around the words they had refined. The words survived and everything else the cell knew did not: how many columns it covers, how many rows, the colour it is filled with, whether its row is the head of the table. A heading over two columns came back over one, a report's coloured header row came back plain, and a long table stopped repeating its head — all after the reader had read them correctly. Both passes copy now, and a test holds each field through each pass. The same hazard was left standing one place further on: a picture compares by what it holds rather than by which object it is, so it writes its own `equals` — and a class that writes its own `equals` gets no `copy` written for it. Every place that rebuilt a picture therefore listed its fields by hand, and the field added this week to say what a picture shows had to be threaded through each of them. It has a `copy` now, and the two rebuilds use it. Holding it to the class is a test that does not list the fields either: it reads how many a picture has off the constructor and asks for one changed picture per field, so a field added later and forgotten fails the test rather than passing it quietly.
- **The label is the boundary:** a page's list items sit closer together than the lines within a paragraph, so the gaps between lines cannot say where one item ends. The label can, and both readers now ask the same function whether a line opens with one — a bullet, a dash, a "3.", an "أ-", each followed by a space, which is what tells a label from a sentence beginning with a dash. A rule tried alongside it — that a line standing out at the label's own edge has left the list — was withdrawn: it split the items of a real Arabic paper after their first line, because that paper's items do not hang. Measured against the same paper read through its tags, the untagged reading went from 164 blocks to 169 against the tags' 174, every one of the new ones a list item that had been swallowed by the item above it.
- **The phone had never been given the multi-column reading:** nine of the
  app's readers exist twice — once in the engine against desktop PDFBox,
  once in the app against the tom-roush port — and keeping the two in step
  was left to whoever remembered. It was not remembered. The engine
  learned to find every gutter of a page by asking each side of the first
  one again (a page of three columns being a page of two, one of which is
  a page of two) and the twin was never given it: it found one gutter and
  stopped. So a newspaper, a dictionary or a conference paper converted on
  a laptop came out right and converted on a phone came out with two of
  its columns interleaved line by line — fifty-five of the hundred and ten
  lines of a three-column page, measured with the shipped reader. Every
  engine test of three and four columns passed throughout, because not one
  of them ran the reader the phone runs.

  The reading is ported, and two things now stand behind it. A test reads
  a page of three columns, and one of four, with the app's own reader on
  the real port and asks the question that separates the two behaviours:
  does every line it found lie inside a single column? And a second test
  holds all nine twins to the engine readers they mirror — the engine
  source read, the library's name changed the way the twin changes it, and
  the two compared as code rather than as text, with the imports sorted
  (renaming a package moves them) and the comments left out (a twin says
  in its own words that it is one). Checked by drifting an engine reader
  on purpose: it names the file, the line, and what each side has there.
  The only difference the guard found on its first run was a default
  argument written out in one twin and left implicit in the other, which
  is now written out in both — an exception allowed is a guard given away.
- **A scanned document was read as one long text, not as pages:**
  recognition works a page at a time and hands back that page's words with
  nothing to say what any of them were — the running head is text like any
  other, the page number is text, and a paragraph that carried on over the
  turn of the page comes back as two pieces with nothing joining them.
  They were handed to the importer as one string with a blank line between
  pages, which is what a blank line means: the end of a paragraph. So a
  scanned book converted with "Chapter Three: Instruments 47" dropped into
  the middle of a sentence at every page turn, and every paragraph that
  crossed a turn cut in two. Hundreds of each in a book, every one for the
  reader to repair by hand — and both are precisely what the reading of a
  laid-out PDF already avoids.

  Both can be settled from the words alone, which is all a scan has. What
  repeats at the same end of page after page belongs to the page and not
  to the document, so it is taken out of the text and kept as the head or
  foot of the converted file; the lines are compared with their digits
  blanked out, since the number counting the pages is the one thing about
  a running head that changes, and where those digits advance by one from
  page to page they are written as the field that counts them — so a
  chapter scanned from page 47 goes on numbering itself from 47 instead of
  starting again at one. Three pages must agree before anything is taken
  away: two is a coincidence, and deleting a line of somebody's document
  is worse than leaving furniture in it.

  Then each seam: a page whose last words do not finish a sentence did not
  finish a paragraph either, unless what follows plainly begins something
  of its own — a list item, a heading, a stray number. On a document of one
  or two pages the seams are left exactly as they were, because a running
  head cannot be told from the first words of a paragraph until it has
  repeated, and joining a paragraph onto an undetected head would put
  "Chapter Three" in the middle of a sentence, which is the very thing
  this is for.

  Measured on a book's worth of pages in the shape recognition returns —
  head and number on every page, lines broken where the page broke them,
  pages ending sometimes mid-paragraph and sometimes exactly at the end of
  one — the four paragraphs of prose come back as those four paragraphs,
  word for word, with the head lifted out and nothing of it left in the
  body. Read as one long text the same pages gave eight paragraphs, three
  of them the running head.

  The sheet comes with them: a page rendered for recognition was rendered
  from something with a size, so the converted file is laid out on that
  page rather than on whatever Word happens to open with. Margins are not
  stated anywhere a scan can be asked and none are invented — an invented
  margin sets every line of the document to the wrong width, which is
  worse than none.

  Making that change exposed something about the build. The module that
  runs the app's Android readers against the real PDFBox port left three
  of them out for needing the phone itself — a context to reach the app's
  files, its bundled language packs, the recognition library — so a change
  to the OCR reader was compiled for the first time by CI's Android job,
  minutes after it was pushed, which is exactly what happened to this one.
  Each of those Android classes is one small class, stubbed beside the
  `Log` and `Paint` that were already there, and with them stubbed all
  three compile in the build. Compile, not run: there is no canvas behind
  the bitmap, no assets to open and no Tesseract to ask. Checked by
  breaking the OCR reader on purpose and watching the engine build refuse
  it.
- **A word a page broke in half stayed broken:** a justified page fills
  its lines by hyphenating, and the reading kept every hyphen, so a
  converted paper read "admin-istrative" wherever the original had simply
  run out of line. Dropping every such hyphen instead is worse: it turns
  "well-known" into "wellknown", and a word quietly corrupted beats a
  word left untidy. There is no dictionary here to tell the two apart —
  this app carries no word lists, converts every language, and never
  reaches the network.

  The document is the dictionary. A paper that breaks "administrative" at
  one line writes it whole at another, and a paper that writes
  "well-known" writes it with its hyphen wherever it falls, so the words
  of every line are collected once and each broken word is looked up both
  ways: written whole somewhere, the hyphen goes; written with its hyphen
  somewhere, the hyphen stays; written neither way, the hyphen stays, as
  it always did. A line's own last word is left out of that vocabulary
  where the line breaks it, since half a word taken for a whole one would
  answer the very question it is asked to settle.

  Measured on a page printed with its long words broken at the margin, the
  two broken words come back whole and the "well-known" that happens to
  break at its own hyphen keeps it. On the ten documents of the corpus it
  changes nothing at all, and that is the honest measurement: not one of
  them hyphenates — Arabic fills a line by stretching its letters, and the
  rest were not set justified — so the page that demonstrates it had to be
  made for it.

  Finding that out turned up a defect worth more than the feature. The
  test for a broken word was "the line ends in a hyphen", and an Arabic
  page sets a list item's label against the item, which the reading finds
  at the line's end: every item of every Arabic list looked like a word
  broken in half. Forty-eight lines of one corpus document, each of them
  then telling the reading that a paragraph could not have ended
  there — the very thing the entry above works to establish. Breaking a
  word at a line's end is a habit of the scripts that have it, and Arabic
  and Hebrew are not among them, so a hyphen standing after an Arabic
  letter, or after nothing but a space, no longer counts as one. That
  drops those forty-eight to none. It happens to change no converted
  document in the corpus, because other signals already ended those
  paragraphs correctly — but it was a wrong answer waiting for a document
  where nothing else spoke.
- **A damaged file is refused, never fatal:** the files people convert include half a download, an attachment cut short, a byte lost in transit. Both readers are given a real document damaged forty-five ways from a fixed seed — cut short, struck through, a stretch of it zeroed — and each reading must end as either a document or an exception the app can catch and report, within a bound: never an error nothing catches, and never a loop. A hundred and fifty rounds against the paper this app was built for found no hang, no error and no exhaustion; forty-five of them, seeded so a failure repeats exactly, ride in the build.
- **A page's furniture is the page's, not the document's:** on the untagged path the running head and foot were found — that is how they were kept out of the middle of the reading — and then thrown away, so a converted paper had no running head and numbered nothing. They are made into a header and a footer now, from the lines themselves where the lines can be read, with the number that keeps step with the pages written as a field. What a page draws rather than writes is photographed instead: where a rule repeats in the margin, or the same picture does, or two pages simply draw the identical thing there, the band is cropped from the page and trimmed to its ink. That is what recovers a head that is not text at all. The paper this app was built for has one: its running head and the words of its foot are drawn as outlines — paths, not letters — so counting the glyphs a page asks for finds a single space at the head of it and, at the foot, seventeen digits and nothing else. There is no line to find and nothing in the file that says the head is there, which is why the foot came out as "48 584820220105", every digit read and not one word, and why the answer has to be a photograph rather than a cleverer reading. It now comes out as the line the page prints. A book's two sides are told apart and kept apart, and a title page that carried no head keeps none.
- **A picture only where the words will not do:** the recovery above was
  reached for too readily. Both readers photographed a running head
  whenever there was any excuse to — the tagged one always, the untagged
  one wherever the band held a rule, which is where most books and
  journals draw one — so an ordinary head whose words the file states
  outright came back as a picture of itself: not editable, not
  searchable, not able to reflow onto a page of another size, and heavier
  in the file than the sentence it stands for. What settles it is a
  question the page can answer: every mark the reader read is painted
  white over the band, and whatever is still there is what no reading
  accounts for. Blank, and the words are given as words, with the line
  the page ruled beside them kept as a border of the paragraph rather
  than printed into a picture — in the .docx, in the preview and in the
  exported PDF alike. Not blank, and the band is photographed exactly as
  before, which is what the paper this app was built for needs: paint out
  its foot's seventeen digits and the Arabic drawn around them is still
  there. Of the corpus, a book, a journal and a printed web page moved
  from a picture to their own words, and the paper did not move.
- **Masking a band never worked on the desktop, and nothing said so:** the
  test above rests on painting part of a band white, which is also how a
  page number is cut out of a photographed head. PDFBox lays the page's
  own transform over the graphics it is handed and clips to whatever it
  drew last, and leaves both behind; the desktop reader undid its own
  shift by shifting back, which compounded with that transform instead of
  cancelling it, and painted every mask through a leftover clip besides.
  The masks went somewhere off the picture, or nowhere, without an error.
  The Android twin saves and restores its canvas and so was always right,
  which is the sharpest argument for keeping twins: the same document
  converted on a phone and on a desktop disagreed, and only one of them
  was doing what the code said. Both now put the transform and the clip
  back as they found them, and a test paints a head out of its own band
  and requires the answer to be blank.
- **A vowelled Arabic page is a page, not a special case:** the marks over
  and under Arabic letters — a fatha, a shadda, a sukun — are separate
  glyphs, and a page paints them in whatever order its producer wrote
  them. Unicode has one order for them, and it is the order every Arabic
  keyboard produces and every search box holds. Get it the other way round
  and the converted document *renders* perfectly and still cannot be
  searched: the phrase a reader types is a different string from the one in
  the file, and nothing on the screen explains why nothing is found. Each
  letter and its marks are now settled into that order, and a mark written
  over a letter Unicode has a single character for becomes that character.
  Every page with no marks on it comes back byte for byte as before, the
  paper this was built for included.
- **Measured against another engine, on the paper this was built for:**
  the reading was checked against MuPDF — a PDF engine sharing no code
  with this one — over the same eleven pages. On six words that recur
  through the paper, MuPDF is wrong every time it meets them and this
  reader is right every time: المعلومات, العلمي, المنهج, الأسئلة, هذا,
  على — 152 occurrences, none of which MuPDF gets right, because that
  file's ToUnicode map names ل as م and ه as ي and MuPDF believes it.
  MuPDF also hands back the page's numbers reversed — 48 as "84", 2252 as
  "2522" — which is the same complaint that started this work. Where the
  two differ otherwise, it is the running foot: MuPDF reads its digits as
  text where this reader photographs the band, because the words there are
  drawn as outlines and the digits alone would say "48 584820220105". The
  comparison is a measurement, not a gate: MuPDF is not a dependency and
  nothing in the build depends on it.
- **A glyph the file will not name is asked of the font, then left out:**
  a browser printing a vowelled Arabic page maps every mark glyph it draws
  to U+0000 — not a character, but the producer declining to answer. Read
  as a fact and carried through, it puts a NUL in the middle of every
  second word, and a NUL is a thing no document can hold: Word drops it,
  the preview shows a gap, a search steps over it, and the word is broken
  in half for every reader in a different way. The embedded font is asked
  instead, which is not the cmap overruling the file — that takes a font
  proved wrong, and is a different rule — because the file said nothing to
  overrule. Where the font is a subset with no character map of its own and
  cannot answer either, the glyph is left out: a word missing a mark is a
  word, and a word with a hole through it is not.
- **Where one paragraph ends, read in the language the page is set in:**
  an untagged PDF holds lines, not paragraphs, so the break has to be read
  off the page — a line set in from the edge its block starts at, or a gap
  wider than the one between the lines of a paragraph. Both readings were
  written for a left-to-right page and did nothing on an Arabic one. The
  indent was measured at the left edge, which on a right-to-left page is
  the ragged side and says nothing, so an Arabic book that marks its
  paragraphs by indenting came back as one paragraph from its first page to
  its last; measured at the edge a line *starts* at, it reads the same on
  either. It was also measured against the line above rather than against
  the block, which fires twice for every paragraph — going in and coming
  back out — and cut every paragraph after its first line, on either side.
  And the gap was half the pitch again, a fixed multiple: the space a
  document puts between its paragraphs is a share of its type size, so the
  ratio of the two falls as a page is set more openly, and Arabic is set
  openly because its ascenders and its marks need the room. The same page
  in English came apart into its paragraphs and in Arabic did not, missing
  by a seventh of a point. The gap is now the smaller of half the pitch
  again and the pitch plus a space a producer chose to add. Every document
  of the corpus reads exactly as it did.
- **A table's head stands in its columns without being set like them:** the
  untagged reader knew a table by its cells starting at the same places on
  line after line, which is true of a table's body and not of its head. A
  head is centred over columns whose figures are ranged right, so its cells
  begin nowhere near theirs and need not overlap them by a point — and read
  that way it was not part of the table at all: a converted report kept its
  numbers and lost the words that say what they count. What a row and the
  row below really share is the clear space *between* their cells, and two
  rows stand in the same columns when their gutters do, whether a cell is
  centred, ranged left or ranged right. That reading is never allowed to
  found a table — two lines with a wide gap in each would qualify — only to
  take in the rows around one that rows lining up exactly have proved.
- **A row of cells is not one word because one of its cells holds a
  space:** a producer that paints its own spaces is trusted on where the
  words are, and its lines are read exactly as painted. That is right until
  the line is a row of a table, whose cells stand tens of points apart: one
  cell holding a space of its own was enough for every gap between the
  cells to be read as nothing. A head reading "Item Respondents Share" came
  back as three words in English and as one in Arabic, because the Arabic
  for "respondents" is two words and the English is one. A gap two whole
  type sizes wide is now a word break whatever else the line holds; nothing
  inside a word is ever that wide, and Word's stretched spaces and its
  kashida are painted rather than left as a gap.
- **The lines a table drew round itself are the table's:** a table found by
  the alignment of its columns says nothing about what was drawn around it,
  and its rules were left in the pile every paragraph is measured against.
  So a bordered table came back with no border at all, and the two
  sentences either side of it each gained a rule they never had — the
  table's own, read as belonging to whatever paragraph was nearest. A rule
  within the table's band and reaching across the width it occupies is now
  the table's: it makes the table a ruled one, and it is withheld from
  every paragraph. A table nothing was drawn around is still unruled,
  because ruling it would add ink the source never had.
- **A list ends where its last item finished, not where the page did:** the
  sentence after a list was swallowed by the item above it — every list on
  every page — because a line back at the margin looks exactly like the
  rest of an item that does not hang. The geometry alone was tried once
  before and withdrawn for that reason: it split the items of a real Arabic
  paper after their first line. What tells the two apart is whether the
  item had finished. A line that stops mid-sentence is being carried on
  wherever the line under it begins; a line that closed its sentence, with
  the next line back at the edge its block starts from, has ended the list.
  The paper this was built for gains exactly one paragraph by it, and the
  fragments the old rule left behind stay gone.

  Making those items into Word lists was tried in the same round and
  rejected. The tagged reading of that paper finds no list at all — its
  dashes were typed by hand, not drawn by Word's numbering — so a reader
  that turned them into a list would hand back a document whose markers,
  indents and numbering were Morpho's rather than the author's. The label
  a page drew stays the text it is until the model can carry the marker
  the page drew, at which point a list can be a list without changing what
  it looks like.
- **A ruled table's columns are where the page ruled them:** the columns
  were measured from the ink inside the cells, which is a poor measure — a
  column of one-word headings under a column of sentences comes back a
  third of the width the page gave it, and the converted table is a
  different shape from the one it was read from. The operators that find a
  page's rules already keep the box of every painted path, so the sides of
  a table's cells were there all along: thin, standing inside the table's
  band, at least as tall as a line of it. All of them or none — a table
  with a side missing would be cut into the wrong number of columns, which
  is worse than measuring them — and a table the page drew no sides for is
  measured from its ink as before.
- **A table the page ruled is read from its rules:** the untagged reader
  knew a table only by the alignment of its cells on line after line, which
  says nothing at all about a table whose cells wrap. A column of short
  labels beside a column of sentences has one line in the first cell and
  three in the second, so nothing lines up — and an ordinary bordered
  report table came back as a wall of prose with its head read as a section
  heading and the paragraph after it swallowed. A page that ruled its table
  said exactly where every cell is, and the boxes of its painted paths were
  already in hand: the thin wide ones are the lines across, the thin tall
  ones the lines down, and the cells are the spaces between them. Each
  piece of a line goes to the cell it stands in — the piece, not the line,
  or every row of a two-column table lands in whichever column its middle
  falls in — and a cell of three lines is three lines of one cell rather
  than three rows of a table. It is asked before the alignment, because it
  is exact where it applies. A box round a figure is one cell and not a
  table, a grid with next to nothing in it is not a table of words, and the
  paragraphs either side of a table stay their own.
- **A cell covers whatever the page drew no line between:** a head written
  across a whole table, a label set beside the rows it belongs to. The grid
  already says where every side and every line is, so it also says where
  one is missing, and a cell reaches on — across and down — until it meets
  one. Kept as separate cells, a converted table has blanks where the
  document has none; the rows a cell covers hold only the cells that begin,
  as a document's own rows do; and the columns have to be counted through
  the merge as well, or a head across a three-column table is read as a
  table of one column and its widths thrown away.
- **A list whose bullets the page draws is still a list:** a browser
  printing a page draws them — `list-style: disc` is a filled circle
  painted beside the item, not a character in the text — so nothing a
  reader extracts says the item is an item. The lines are then evenly
  spaced and evenly set, which is exactly what a paragraph looks like, and
  a printed web page's list came back as one paragraph of run-on
  sentences. What the page did draw is a small mark, the same size in the
  same place, beside line after line; one such mark is a mark on the page
  and several in a row are a list. The bullet is put back where the page
  drew it, after which everything downstream reads the line as what it is.
  A page that wrote its own bullets is left alone, or the marker would be
  drawn twice.
  Two things it must not do, each found by drawing a page dense enough to
  break it: the items of a list follow one another, so marks in the same
  place at opposite ends of a page are two marks and not two items; and a
  page that scatters hundreds of small marks is drawing something, where
  the same mark in the same place beside two lines running is a
  coincidence. On such a page every line of text was read as an item. A
  page holds a few dozen lines and so a few dozen markers, and that is
  where the count is bound — which also bounds the cost, every line of a
  page being measured against every mark on it. The grid of a ruled table
  is bounded the same way, since a page ruled cell by cell draws about one
  line for every cell and the work grows as the square of the count.

  All three readings of a page's ink are fuzzed together, against pages of
  words with ink drawn at random over them: forty seeded pages must give
  every word back, in one block or another, and a page carrying twenty
  thousand marks must be read in a moment rather than in a minute.
- **A bibliography's entries are one paragraph where a file has no tags,
  and only there:** measured on the paper this was built for, its entries
  and the lines that carry them on both begin at the right margin and sit
  the same distance apart — twenty-one and a half points, entry to entry
  and line to line alike. There is no hanging indent, no extra space, no
  rule: geometrically an entry and its continuation are indistinguishable,
  and every reading that would separate them would also cut real
  paragraphs in two. The untagged reading of that paper joins four pairs
  of entries this way, out of its eighteen, and a guess is worse than the
  join.

  The count reconciles exactly, which is how that claim can be checked
  rather than taken on trust: eighteen paragraphs read through the tags
  become eleven read without them, and the seven differences are those
  four wrong joins plus three the untagged reading gets *more* right than
  the tags do. Four of the eighteen are not entries at all but the tail
  of one — a place and a publisher the author left on a line of their own,
  "الجزائر: ديوان المطبوعات الجامعية" under the entry it belongs to, and
  "للنشر" under another — and the untagged reading puts three of the four
  back where they belong. The tagged reading keeps them apart because the
  file says to: the paragraphs are the author's, and reproducing the
  document as it stands is the job.

  Across the whole paper the two readings differ in eleven places and no
  more, which is the first time that gap has been counted rather than
  estimated: eight where the untagged reading runs paragraphs together —
  the six in the bibliography above, and two in the body where a line
  stops a hair short of its margin and the reading cannot tell that from
  a line that filled up — and three where it breaks one paragraph in two,
  one of them a citation the page set on a line of its own. Aligned in
  both directions, nothing is left over on either side. Each of the three
  classes was looked at for a rule that would settle it: the bibliography
  has none, since its entries are indented by anything between nothing
  and forty-four points with no pattern to it; and the one clean rule
  available for the third — that a line holding nothing but a bracketed
  citation is the tail of the line above — fires once in the three
  thousand eight hundred paragraphs of the corpus, which is not enough to
  earn a rule that could be wrong somewhere else.

  Where the file *is* tagged, none of that applies and none of it happens:
  the producer says outright where each paragraph begins, and the tagged
  reading — which is the reading that paper actually gets, and every
  tagged file gets — gives all eighteen entries back one by one, through
  Word and out again. This entry said otherwise until the tags were
  read out and counted, which is a reminder that a limitation measured on
  one path is not a limitation of the converter.

  The two readings are now held to that comparison rather than left to
  it: a page whose paragraphs the tags declare *and* whose spacing shows
  them must come back as the same paragraphs read either way. Comparing
  the words alone, which is all the gate did before, says nothing about
  it — a reading that ran three paragraphs into one still has every word
  of them — and paragraph splitting is the most-guessed part of the whole
  reading.
- **A cell holds more than its paragraphs:** Markdown wrote out only the
  paragraphs of a table's cell, so a table inside one — which is how a form
  and an invoice are laid out — was dropped whole, along with any picture
  standing in a cell. Markdown has no table inside a table and no way to
  invent one, so the words of the inner one are given in the order they are
  read; but they are given, because a file that quietly loses the half of
  itself carrying the figures says nothing about it, and the reader has no
  way to know. HTML and Word already kept both.
- **A picture written the old way was dropped whole:** Word drew its
  pictures in VML before DrawingML and still draws some of them that way
  — anything pasted in compatibility mode, an equation saved as a
  picture, the output of a good many converters. Pictures were looked for
  under `w:drawing` alone, so every one of those was passed over: a
  converted document simply had no picture where the original plainly has
  one, and nothing said so. A `w:pict` is read now, at the size its shape
  gives itself in whatever unit it gives it — points, inches, pixels,
  centimetres — and left to the writer where the shape says nothing this
  reader understands. A shape holding no picture is still not a picture,
  and a text box is text rather than a picture of one.

  Reading the old form brought its own hazard, which is why the walk that
  finds pictures is now the walk that finds text boxes rather than a flat
  search for every `w:drawing` and `w:pict` in sight: Word writes a shape
  twice where it can — the way it prefers, and a fallback drawn the old
  way for a reader that does not know the new one — so a flat search puts
  the same picture into the document twice. One branch is chosen and the
  other left, exactly as a text box's has always been. The same walk keeps
  the preview picture an embedded object shows for itself: an equation
  from the old editor, a chart pasted from a spreadsheet. The thing itself
  cannot be carried across; the picture of it is what a reader sees, and
  dropping it leaves a hole in the page.
- **A field written the long way round was read as its own stale
  answer:** Word writes a page number — and often a link — as five runs
  rather than one element: a begin, the instruction, a separator, the
  result it last worked out, and an end. Only the short form was read, so
  the long one came through as the plain text of that result. A Word
  document whose footer numbers its pages converted to a PDF that says the
  same number on every page of it, and a link written this way led
  nowhere. The long form is read now: `PAGE` becomes the field a writer
  fills in, `HYPERLINK` points where its instruction says — at an address
  or at a place in the document itself — and the instruction stops being
  mistaken for the document's words. Every other field is left as the
  words it worked out to, which is what a reader of the document sees.
- **A paragraph's runs are not always its children, and four wrappers
  were being walked past:** Word wraps runs in whatever it needs to say
  something about them — a tracked insertion, a smart tag, a content
  control, custom XML a template put there, a direction override, a
  tracked move. The reader knew four of those and passed over the rest in
  silence, so their words never reached the document: a paragraph comes
  back empty from a file that plainly has words in it, and nothing says
  why. `w:moveTo`, `w:customXml`, `w:dir` and `w:bdo` are read through
  now, and a direction override turns the runs it holds and nothing
  around them — which matters here more than anywhere, since a direction
  override is exactly what a producer marking right-to-left text writes.
  Two wrappers stay out on purpose: `w:del` holds what somebody deleted
  with changes tracked and `w:moveFrom` holds text moved away from where
  it stood, and reading either back in would put a deleted clause into a
  document that no longer has one. The same wrapper holds whole
  paragraphs at the level of the body — a template's custom XML round a
  section of the document — and walking past one lost every paragraph it
  held; that is read through as well now.
- **A note at the foot of a page hid where the page broke:** a document
  breaks where its producer broke it, and both readings find those breaks
  the same way — a page whose text stopped well short of where the
  document's text could have run was broken on purpose, not by filling
  up. But a note is pinned to the foot of its page whatever the text above
  it does, so a page that stopped a third of the way down still has ink
  near its bottom edge and looks full to the margin. The paper this was
  built for carries the corresponding-author note on its title page, and
  that page's break — the one break of the document a reader sees first —
  was lost, while a page that merely ran a line short was given one it
  never had. Both readings leave the notes out of the measurement now: the
  tagged one asks the blocks, which already know a note when they see one,
  and the untagged one reads the short rule a page draws above its notes,
  which is the same evidence by which a note is a note at all. The paper's
  title page breaks where it broke, the spurious break is gone, and the
  two readings agree on which pages broke as well as how many.
- **The preview is read the way a browser reads it:** three hundred
  documents nobody wrote already go through the preview writer and come
  back as the same words, parsed strictly as XML — which proves the tags
  are balanced and says nothing about where they stand. A `<div>` inside a
  `<p>` is perfectly well-formed XML, and a browser does not read it as
  written: a paragraph closes at the first block inside it, an inline
  element cannot hold one at all, and what a browser does instead is move
  the block out and leave the rest behind it — on a phone, a preview whose
  halves have swapped places. That is now checked too, over the same three
  hundred, and by an outside HTML5 parser over every document of the
  corpus. Nothing is misplaced today; nothing was looking.
- **Every picture of a converted document was unlabelled:** a screen
  reader met it, said "image", and stopped; Word's own accessibility check
  called the document out. Two places already knew what a picture shows
  and both were being thrown away. A tagged PDF carries on its Figure the
  description its author wrote for a reader who cannot see it — the one
  thing about a picture a file can state outright. And a running head
  photographed because its ink is not all accounted for by what was read
  there has words that are then nowhere in the document at all: not
  searchable, not read aloud, gone. Both are kept now, written as Word's
  alternative text, as the preview's `alt` and as the description
  Markdown's own image syntax has a slot for, and read back from Word so
  that converting a file twice does not lose what one conversion found.
  Only where they are words: a band whose text is drawn as outlines gives
  up its digits and nothing else — the paper this was built for yields
  "58 48 2022 01 05" from a foot that names a journal, a volume, an issue
  and a page range — and noise offered as a description says the picture
  has been accounted for when it has not.
- **Every table of a converted document had a blank line under it:**
  WordprocessingML wants a paragraph after a table in two places — between
  two tables, which Word would otherwise read as one table, and at the end
  of a body, a cell, a note or a running head, each of which must end with
  a paragraph. It was written after *every* table, so a report with ten
  tables came back with ten blank lines that were not in the original,
  each pushing what followed a line down the page. Our own reader drops an
  empty paragraph and so could never see it: what found it was reading the
  files we write with somebody else's parser and comparing what it saw
  with what we thought we had written. Every one of the thirty-eight
  documents of the corpus now agrees, block for block and run for run,
  between the two readings.

  That reading is kept, as a gate: a .docx is a package of parts that
  point at one another and Word is unforgiving about it — a relationship
  naming nothing, a list numbered against a definition that is not there,
  a part with no content type, and the file opens as "unreadable content",
  or opens with its numbering silently gone. None of it shows in a round
  trip, because our own reader resolves what it can and ignores what it
  cannot. So the packages we write are now checked as packages: every part
  typed, every reference resolving to something the package holds, every
  list and style named actually defined, and the children of every
  properties element in the order the schema puts them — over a document
  holding every part a document can have, over that document written, read
  and written again, and over every document of the corpus. That last
  check matters because the schema is a sequence rather than a set: Word
  reads a paragraph's properties in order and stops at the first one out
  of place, so a `w:jc` written before a `w:spacing` is not untidiness but
  a file that opens repaired with what came after it gone — and our own
  reader, which looks each child up by name, would never notice. Nothing
  is out of order today, across the forty-two distinct properties the
  writer emits. The checker is made to fail on purpose by five packages
  broken the five ways, because a checker that passes everything proves
  nothing.
- **Markdown replaced a noted word with the reference to its note:** a
  note's mark is the run's own text — a "1", a "*", a "†" — and Markdown's
  own `[^1]` says the same thing, so the writer put one where the other
  stood. A run carrying words *and* a note is a different thing, and the
  same line dropped the words: "before noted words after" came out
  "before [^1] after". No reader produces that shape — both attach a note
  to the mark, which is a character or three — but the model allows it and
  the writer is given models from anywhere, so the words are now kept in
  front of the reference. A real paper's mark is replaced exactly as
  before. Found by asking the three writers the same question about one
  document holding every feature the model has: with this closed, nothing
  in that document is lost by any of them.
- **A part of a document forgot what the whole said it was:** converting a
  range of pages lifts them out as a document of their own, which is what
  lets the tags, the pictures and the outline see the part as the whole it
  now is. But a document made that way has an empty information dictionary
  and no language on it, so a chapter of an Arabic paper converted on its
  own came out with nothing to say what language to proof it in — every
  word of it underlined in red by a Word left to guess — and, once the
  four properties below were carried at all, nameless and by nobody. The
  part now takes the whole's language and the whole's own account of
  itself, alongside the tags it already took.
- **Every converted file arrived called nothing, by nobody:** a PDF keeps
  its title, its author, what it is about and its keywords in an
  information dictionary; a .docx keeps the same four in its core
  properties. Word shows them in its Properties pane, a reader puts the
  title in the window, and a search across a folder of files reads them
  before a word of the text. Both readers threw them away and the Word
  writer signed every file it produced as its own work — `Morpho` as the
  creator, no title at all — so a paper converted from a PDF came out
  anonymous and unnamed, and a folder of converted papers was a folder of
  files with nothing to tell them apart. Now what the source said is
  written, the converter's name goes where it belongs (on the application
  that last touched the file, not on its author), and a source that named
  nothing still names nothing rather than gaining an empty title. Read
  back, the converter's own signature is not mistaken for an author, so
  converting a file twice does not end with nobody having written it. The
  preview is headed with the document's own name instead of "Document".
  The one path that cannot carry them is the PDF the phone exports:
  Android's own PDF writer has nowhere to put them.
- **Two of the three markings a reader leaves were thrown away:** a
  student's PDF is full of them, and the argument for keeping a highlight
  — that the marking is the reader's own reading of the document, and a
  converted file without it is the document before it was read — holds
  just as well for the other two, which are the ones that change what the
  document says. A line drawn under a term and a clause struck out are
  read now, the same way the highlight is: an annotation with the
  quadrilaterals it covers, joined to the words underneath by geometry. A
  wavy line counts as a line under the words, since that is what it is.
  And where a highlight without a colour is nothing anyone could see, a
  line drawn without one is drawn in whatever colour the reader was using
  and has marked the words all the same.
- **A marking says where; a comment says why, and the why was dropped:**
  a reader who highlights a passage can type a remark against it, and one
  who wants to say something about a line drops a note in the margin
  beside it. That is what a reviewed PDF is: a supervisor's reading of a
  thesis, a colleague's query about a figure — the reason the file was
  sent back. We read where every marking sat and threw away every word
  anybody had written about it, so a reviewed document converted here came
  back as the document before it was reviewed, with nothing to say the
  remarks had ever been in the file.

  Both are read now, and both come out as what Word calls a comment,
  which is the thing they are: the remark, who left it and when, anchored
  to the words it is about. Nothing in a PDF joins a note to those words,
  so the two are joined by geometry the way a highlight's are — for a
  remark typed against a highlight the words are already known, since
  they are the ones the highlight covers; a note left on its own marks
  nothing at all, so it is taken to be about the line it sits beside,
  which is the band it sits at read right across the page. A highlight
  nobody remarked on stays a marking and no more: most highlighting says
  nothing, and an empty comment against every yellow passage of a
  student's PDF would be worse than none.

  Three things the file decides rather than the reading. Word numbers a
  document's comments itself, from nothing upwards, so the writer
  renumbers what it was given and keeps the marks in step — a reader of a
  PDF numbers the notes the way that PDF listed them, and the two need
  not agree. A note nothing points at is left out of the .docx, because
  Word keeps such a note in the file and shows it nowhere, and reading
  the file back would lose it without a word. And a note's own words are
  written as paragraphs of the document, so a note left in Arabic is laid
  out from the right like everything else rather than turned round in the
  margin.

  The preview marks the words a note is about, shows what was said on
  hovering, and gathers the notes at the end the way it gathers footnotes,
  each led back to the words it came from. The page the app prints to
  make a PDF is the same page with the notes left out — both the marks and
  the notes — because the app's own layout draws none into a PDF either,
  and two export routes that disagree about the same document is a defect
  whichever of them is right. That leaves both matching what Word does
  when it saves a document as a PDF without its markup. Markdown has no
  comments and is given none: closing its round trip took long enough
  that adding text to it which cannot be read back is not worth the
  remarks.

  A date is the part most easily lost: a PDF states one in a shape of its
  own — `D:20260903091500+01'00'` — and every part after the year is
  optional, so a reading that expects fourteen digits throws away a
  producer that wrote only the year and the month. It is read as far as it
  goes and written as the ISO instant the rest of the world uses; a date
  with no zone is written with none rather than being stamped with one,
  and an offset the file is wrong about is dropped rather than carried.
- **A PDF has no underline and no strike, and both were lost:** where a
  document underlines a term or strikes out a clause, the producer draws a
  hair of a rule where the words are — under the baseline for one, across
  the middle of the letters for the other — and nothing in the file joins
  the line to the text it marks. Neither reader ever asked, so every
  underlined heading, every crossed-out price and every struck clause came
  back plain from a PDF, while the same document converted from Word kept
  both. Emphasis that changes what a document means is exactly what a
  converter must not quietly drop.

  The join is geometry, the way a highlight's is: where the rule sits
  against the baseline, how thick it is, and that it hugs the ink rather
  than running to the margins. There was already a number for how near a
  baseline a rule has to be to belong to the words rather than to the
  paragraph — the readings drew a paragraph's border outside it and threw
  away everything inside it — so that number is now shared, and both mark
  bands lie strictly inside it: no rule can be read twice, once as a line
  through the words and once as a box round them. Hugging the ink is what
  keeps a paragraph's border, a table's line and a bar of colour behind a
  highlighted word out of it — a border runs margin to margin, a table's rule the width of its
  column, and a bar as deep as the type is a colour, not a stroke, so
  reading it as a strike would have the document withdraw what it had
  emphasised. A hair too short to be one of the page's rules is kept apart
  and asked only about the words it lies on, because the line under a
  single underlined word is shorter than any rule. Both readers ask the
  same question, and across every document of the corpus — forty files —
  the only marks found are the ones that are there.
- **Bold a page declares or fakes was read light:** the same three ways a
  producer can say italic, it can say bold, and only one of them was read.
  A producer with the bold cut of the typeface names it —
  "Times New Roman,Bold" — which is what the readers looked for. A subset
  whose name the producer made up ("ABCDEE+Font1") says nothing in its
  name and everything in its descriptor, where the weight is a number the
  way a designer writes it. And a producer with no bold cut fakes one, by
  drawing each letter and stroking round it to thicken it — the same trick
  as the faked lean, for the same typefaces. All three are read now, in
  both readers; a family whose own name happens to hold one of the words
  for a heavy cut is not bold for it, since only what follows the name is
  read, and a face merely outlined is not bold either. The paper this was
  built for gains six runs by it: the bullets of its lists, which Word
  strokes round because the Symbol face it sets them in has no bold cut,
  and which open bold items.
- **Every italic word of an Arabic document was converted plain:** a PDF
  holds no italics. A producer with the italic cut of the typeface to hand
  switches to it and names it — "Times New Roman,Italic" — and that name
  was the only evidence either reader looked for. A producer with no
  italic cut fakes the lean instead, by skewing the matrix it draws with,
  and goes on naming the upright font it started from. This is not an edge
  case: no Arabic typeface Word ships has an italic cut, so *every* italic
  word of an Arabic document is faked this way. The paper this was built
  for paints 385 of the glyphs on its bibliography page at a shear of one
  third — Word's eighteen and a half degrees — and every book title in it,
  which is what the italics are, came back upright.

  The lean is now read from the matrix rather than from any one number in
  it, because the matrix also holds the size the text is set at and any
  turn the page was given: upright text, however turned or scaled, is drawn
  with its baseline and its up-stroke square to one another, and how far out
  of square they are is how far it leans. A page turned to be read is not an
  italic page; a back-slant, which a designer chooses on purpose and means
  the opposite by, is not an italic either. The font's own declared angle
  is asked as well, for a subset with a made-up name and nothing else left
  to say it with. Both readers ask the same question and both find the same
  twenty-five runs in the paper, which Word is given as `w:i` with the
  `w:iCs` that a complex script needs beside it.
- **A right-to-left Word document said so nowhere, and read back as
  left-to-right:** Word keeps a document's direction once, in its section
  properties, and every paragraph runs that way unless it says otherwise.
  The writer never put it there and the reader never looked for it, so an
  Arabic document went out and came back running from the left — its tables
  read backwards, its running head at the wrong margin, and every paragraph
  that had not spoken for itself turned round. Writing it exposed the other
  half at once: a paragraph that runs the *other* way from its section has
  to say so outright, and the one English paragraph of an Arabic document —
  an address, an abstract, a line of code — had nothing at all in it to say
  with, because a paragraph with nothing else to say was written with no
  properties at all. Both halves are written and both are read, and a
  mixed-direction document now comes back paragraph for paragraph as it
  went.

  Reading it turned out to be a second question. A section says which way
  it runs only when somebody set it that way outright; a document whose
  paragraphs each carry their own mark leaves the section bare, and read
  from the section alone such a file is left-to-right again. The file this
  was found on is one this converter itself wrote before the mark above
  existed — every paragraph of it Arabic and marked so, its section
  silent — and it read back as a left-to-right document with nothing in it
  to argue otherwise. So where the section says nothing the document's own
  words decide, as they already do for a page with no tags and for a plain
  text file. A section that does speak is still believed over them: that
  is the author's word, and the words are only what is left when there is
  none.

  This was found by asking the three writers the same question about one
  document holding every feature the model has, and comparing their
  answers — the same method that found the Markdown gap above.
- **A figure a page draws is a figure a page has:** a chart, a diagram, an organisation tree, a signature — every drawing tool exports one as paths, not as a picture the file holds. Both readers gathered pictures, found none of it, and converted the text of a report while every figure in it vanished: the worst kind of loss, because what is missing leaves no gap in the words. The operators that find a page's rules see every painted path, so the box each one covers is kept; paths that touch are one drawing, and each is photographed from the page and placed where the page placed it. Where the file is tagged the tree says outright that a figure is there and still holds no picture of it, and that Figure is photographed where it drew. Saying which paths are a figure is the whole of the difficulty, and the test is one line: a figure holds no words of the document, because it is not behind any — which is what keeps the rules of a table, the shading behind its head, a highlight over a word and the border round a sheet from being photographed with the text inside them and put into the document twice. A rule is not a figure however the tree labels it, which the paper this was built for proves: it tags the rule under its dates as a Figure, and the first version of this put a strip of ink one point tall into it.
- **Every writer is asked whether it kept the words:** the strongest check available without a phone is to read a real document and count what comes out the other side. Word, HTML and Markdown are each given the model and asked for every character of it back; that is how the notes Markdown was dropping were found, and it is how the two Word bugs below were found. Word now returns the document word for word and weight for weight, and HTML and Markdown hold every character the reader found.
- **What a style says, a run may unsay:** a heading's style is bold, so a run the document does not set bold inherits it — and the light digit of a numbered heading came back bold, with nothing in the file to say it had ever been otherwise. A run under a style that sets bold now writes its own weight either way. The same reading holds for a note marked by hand: Word numbers a note itself, so a star or a dagger is written into the note as text as well as onto the run that refers to it, and a reader that keeps both says it twice. Both were found by reading a real document out of a PDF, writing it as Word, and reading it back: the two now agree word for word and weight for weight.
- **A part of a document keeps the document's tags:** a page range is read as a document of its own — that is what lets everything else see the part as a whole — and a document built that way has no structure tree, so the tagged fast path was silently lost for anyone converting a chapter at a time. The tree is carried across because it points at the pages themselves and the part holds them; an element naming a page left behind finds no words, which is the right answer.
- **A page is not a place, so it is made one:** a PDF's own links point at pages, and a page means nothing in a Word file. Rather than drop them, the page a link leads to is given a name — its first paragraph answers to it — and the link is pointed at the name, so a manual's contents page still works after the conversion. The reader marks such a link with a scheme of its own while the pages exist and the paragraphs do not, and one pass turns every one of them into a real link; a test holds the line that no mark of the reader's own ever reaches a converted file.
- **A link into a document is a name, not an address:** Word reaches a place in its own file by the name a bookmark gives it, and writing that as a relationship would send a reader off to a website called "#_Toc1" — which is what a contents page converted by a tool that knows only web links becomes. The names are carried through the model, so both ends of the link survive; a name Word would not accept is repaired, and the link and the place it points at are put through the same repair so they still meet.
- **A picture in a cell is part of the table:** a letterhead's logo, a CV's photo, the product beside its price — the exporter laid out a cell's paragraphs and nothing else, so the words of such a table came through and the pictures did not. A cell holds pieces now, words or a picture, and a picture is one line of the stack: drawn whole or carried to the next page, never cut in half, and never taller than a page can hold.
- **A cell that covers several rows is drawn when the last of them is:** an invoice's label beside three lines, a schedule's day beside its hours, a form's field beside its parts — the exporter drew such a cell in the first of its rows, so it stood beside one of them with whatever did not fit cut off, and the rules of the rows below ran through where it should have been. It waits now, because the rows it covers are laid out one at a time and how tall they come to is not known until they are; whatever it holds beyond what its rows came to falls to the last of them, which is where a table puts it, and a merge with more to show than the page has left takes a fresh page rather than being cut off at the foot of this one. Where it still runs off a page it is drawn as far as that page goes and started again on the next, which is what a page can show of it. A cell of a head is drawn where it stands as before: the head is put back at the top of every page the table runs onto, so it has no last row to wait for. None of this can be run without a device, so it was exercised against a recording canvas: the boxes it draws, page by page, for a merge over three rows, a merge taller than its rows, a merge that crosses a page, and a merge under a repeating head — which is how the merge came to be drawn behind the head it should sit under, and then not.
- **A row longer than the page carries on over it:** an exporter that draws a table row into a band and moves on loses everything past the bottom edge — the notes column of a contract, a syllabus, the one long cell a CV puts its history in — and loses it silently. A row is cut between lines now and continued at the top of the next page, each cell carrying on from where it stopped. Where the cut may fall is arithmetic rather than drawing, so it lives in the engine under test, including the property the drawing depends on: every cut moves each unfinished cell on by at least one line, so a row is always finished and the loop that draws it always ends.
- **Running out of memory is raised, never swallowed:** the readers guard their optional passes so one failing costs a document its pictures rather than the reader its life, and Kotlin's `runCatching` catches that too — which handed back long documents with pages quietly missing. The app asks Android for a large heap for the same reason: holding a document whole in memory is what a converter does.
- **A note's mark is said once, and only a note that carries one:** a page prints a footnote's mark twice, raised in the line that refers to it and again at the head of the note itself, and a Word file keeps only the second of those as words — so the mark is taken off the note's first line, where it would otherwise be read as the note's opening word. A note Word numbers for itself carries no mark as words at all: it opens with the element that draws the number, the reader makes the mark instead, and taking a mark off its words strips whatever the note actually begins with — a note reading "1 January 1999, p. 4." under Word's own number 1 loses its date. So the words of a note are only ever trimmed where the note is the one that printed the mark.
- **A head is a run of rows from the top, and every writer asks the same question:** Word repeats the rows a long table marks as repeating, ignores the mark on a row further down — a row in the middle of a table has nothing before it to sit above — and a table that is all head has no head at all, there being no body under it to head and no page it would not repeat itself on. Three writers read the same field three ways: the preview took the leading run, Word's writer marked any row that said so, and the phone's pages did not repeat anything. So the reading lives once, beside the walk that puts a merged cell's places back, and a document's head is the same head wherever it is drawn.
- **A writer's output is a reader's input:** the app writes Markdown and reads Markdown, so whatever it says in Markdown it may be asked to convert next. Its pipe tables had this defect and were fixed; its links and its notes still had it, and a document converted to Markdown and then to Word came back with the characters that spell a link showing in the middle of its sentences and the words of its notes as stray lines at the end. Learning to read them means a document's own brackets are no longer safe as they stand, so they are written escaped — and a bracket that opens nothing, a mark whose note nobody defined, and a line that merely looks like a definition are all left as the text they are, since the failure to avoid is reading a document's words as syntax. Every corpus document is now written as Markdown and read back, and the corpus gates count a note's words as the document's words: the .docx gate read the body part alone and would have let a writer drop every note in the file without a test noticing.
- **Documents nobody wrote:** the round trips that break are the ones nobody thinks to write a test for — a paragraph whose first word is `1.`, a note whose words hold a bracket, two struck-through runs Word happened to split in the middle of a phrase, a mark that cannot be its own label landing on a number another note already answers to. Every one of those was found by generating documents out of the characters the writers must escape and the words they must not read as syntax, and checking that the words, the links and the notes come back. Three hundred such documents run in a quarter of a second, so the gate is cheap enough to keep; the .docx round trip has yet to fail one of them, and the one thing Markdown is known not to keep is written down as a test rather than left to be discovered again.
- **The same trick, turned on .docx:** the generator that found four ways Markdown lost a document was pointed at the format the app exists to produce, asking two things of six hundred documents nobody wrote — the shape (every paragraph's kind, the list it sits in and how deep, its alignment and its page break; every table's size, rules and head, cells walked as the document is) and the look, per character rather than per run, since a reader may split a run wherever it likes and has lost nothing by it, while a character that arrives less bold than it left has. It found the numbered heading. A look the document did not name is not asked about: null means "whatever the file says", and a reader that resolves the styles answers with what the character actually looks like, which is the point of it.
- **The preview is a page built out of somebody else's words:** a document's own text ends up inside markup, and the document is not ours. Three hundred documents nobody wrote, made of `</p>`, `<script>`, `]]>`, a bare ampersand and a quotation mark in the middle of a word — and, for the places that end up inside attributes rather than between tags, a link whose target holds `">`, a typeface named with a quotation mark, a bookmark named with both — must each leave a well-formed page showing exactly the document's words and nothing that was markup. The WebView runs no JavaScript and may reach no file, so a page that escaped nothing would still not be dangerous; it would be wrong, which is enough.
- **A package says where its document is; the path is only where it usually is:** OPC names the main part by a relationship, and the file Word writes after repairing one names `word/document2.xml`. Word opens it without a word and the converter said "not a .docx" — the worst answer a converter can give, since the reader has no way to tell that the file is fine and the reader is not. The relationship is followed now, everything else is read beside whatever part it names, and a package that declares nothing still means the conventional path, so no file that opened before opens differently.
- **A page of three columns is a page of two:** the gutter finder was asked for one gutter and answered with one, so a page of three gave up the second of its gutters and the two columns left on the far side of it were read as a single column, a line of each in turn — or as a table of two, since two columns read as one look exactly like a table and only the gutter says otherwise. Each side is asked again instead, twice deep, which is up to four columns; past that a page is a table drawn without rules, and the check that each side fills the measure it is set in is what tells the two apart. The same recursion runs twice over, once where the marks are cut apart and once where the lines are put in reading order, because those are two different questions asked of two different things. A heading over some of the columns is the case that makes it hard: it crosses one gutter and no other, and a gutter that no line at all may cross is one such heading away from not being found at all.
- **A page is not a paragraph break:** the reader broke a paragraph at every page boundary, unconditionally, so a book converted from PDF came back with a sentence cut in two at every page turn — the most common defect there is in this kind of document, and invisible in a test that reads one page. The rule was a stand-in for a hard question: the gap between the foot of one page and the head of the next says nothing, so the geometry that decides between two lines cannot decide here. What can be asked is what the lines look like and where they stop, and whether the page filled up at all — and that last is judged against the page's own sheet, because a document whose pages carry text only at the top makes every page as deep as the deepest while none of them is full, which is how three tests written for other things caught the first attempt.
- **A table longer than a page is still one table:** the same defect as the broken paragraph, one level up. A page paints a long table again on every page it runs onto, and nothing in the file says the parts are one, so the reader made a table of each page — a twenty-page statement as twenty tables, and the head the page printed on each of them as twenty rows of data. The join asks the same question the paragraph does (did the page stop this, or did the writing?) and two more of its own: the parts must be adjacent with nothing between them, and their columns must stand in the same places. Where the head repeats, dropping the repeat and marking the head is what makes the head-repeating work pay off on the untagged path: the page was printing it twice for a reason, and now Word, the preview and the exported page do the same.

## Next (per the plan's roadmap)

**Everything a 1.0 needs that can be built without a device is built.** The
release bundle assembles under R8 on every push, the launch material is
written (`store/`), and the remaining work is the kind CI cannot stand in
for — see `store/RELEASE-CHECKLIST.md`, which starts with an ordered
device-test pass because no line of this app has ever run on physical
hardware.

Known and deliberate for 1.0: process death while the save dialog is open
discards the in-memory conversion; a PDF the phone exports carries neither
what the document says about itself nor the language it is written in,
because Android's own PDF writer has nowhere to put either — every other
path carries both; a table inside a cell is drawn in the
exported PDF as the lines it holds — one line to a row, its cells set
apart — rather than as a table, since the width to draw one in is whatever
is left of the cell and the cell's own height is not known until the
table inside it has been laid out. The words are kept, which is what the
Markdown writer does with the same question; Word and the preview keep the
inner table itself. A document whose
sections are set on different pages — a report with one landscape table in
it — keeps them in Word, where the turned page is turned, and in the pages the
phone draws, head and foot measured against the page they sit on, and in
the preview when it is printed, which names a sheet for each shape. An equation is written out the way it reads (a fraction as
(a+b)/2) rather than kept as an equation, because nothing this converter
writes can hold one. A Word document's comments are not carried: they are
about the document rather than part of it. A heading an untagged document
never named is recognised by the type it is set in, which a page range
judges from the pages asked for: a range wide enough to hold both body
and headings reads them as the whole document does, and a single page on
its own has less to go on. A form somebody filled in is
read from its pages rather than its tags, since the tags were written
before the answers were.

After the first real documents come back: OCR page-segmentation and
resolution tuning (`RENDER_DPI`, and `tessdata_best` if accuracy demands
it), PDF-export layout, then the M4 remainder — re-running OCR on a single
region, keeping a region as an image, Table Lasso. M5's automation (batch,
Magic Folders, widget, history) is post-1.0 by the plan's own monetization
split. Google Docs sync is cut; the app stays network-free.
