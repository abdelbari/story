# Test fonts

`NotoNaskhArabic-Regular.ttf` exists so the PDF readers can be tested against
Arabic, which is the language this project is least able to get right by
inspection and most needs to. No font that ships with a JDK has Arabic
glyphs, so an Arabic test PDF cannot be built without one.

It is used only by tests. It is not bundled in the app, and nothing in
`src/main` refers to it.

Source: https://github.com/google/fonts/tree/main/ofl/notonaskharabic
Licence: SIL Open Font License 1.1 — the full text is in `OFL.txt`, kept
alongside as the licence requires.
